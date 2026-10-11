package com.example.read_y

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil

data class BookMeta(val title: String, val author: String, val pages: Int, val chapters: Int)

/** Reading position. For PDFs [chapter] is the page index; for EPUBs it is the spine index. */
data class BookState(
    var chapter: Int = 0,
    var fraction: Float = 0f,
    var percent: Float = 0f,
    var lastRead: Long = 0,
)

/** The ways a shelf can be put in order; folders always come before books except in [MANUAL]. */
enum class Sort(val label: Int) {
    /** The order the reader gave the shelf by dragging; until then folders first, then by file name. */
    MANUAL(R.string.sort_manual),
    TITLE(R.string.sort_title),
    AUTHOR(R.string.sort_author),

    /** Most recently read first. */
    LAST_READ(R.string.sort_last_read),

    /** Furthest read first. */
    PROGRESS(R.string.sort_progress),
}

/** The geometry of the bookshelf grid (see item_book.xml), shared by the real shelf and its miniatures. */
class ShelfSpec(val width: Int, val columns: Int, val density: Float) {
    fun px(dp: Int) = (dp * density + 0.5f).toInt()

    /** Thickness of the bookcase's side posts. */
    val post get() = px(12)
    val itemW get() = (width - 2 * post) / columns
    val coverW get() = itemW - 2 * px(9)
    val coverH get() = (coverW * 1.5f).toInt()
    val rowH get() = px(16) + coverH + px(5) + px(34) + px(5)

    /** Height of the case's top rail (top_bar.xml without the status bar inset): a plank with its lip. */
    val rail get() = px(49)

    /**
     * A miniature shows the case from its top rail down to the plank of this row, so that it has a
     * board at both ends. That is at least as tall as a book, which it stands beside.
     */
    val miniRows get() = ceil((width * 1.5f - rail) / rowH).toInt().coerceAtLeast(1)
    val miniH get() = rail + miniRows * rowH

    /** How far a miniature reaches above the top of the books beside it, into the gap under the shelf above. */
    val miniRise get() = px(11)
}

/** The on-disk library: the "Read-y" folder in shared storage, plus cached metadata and reading state. */
object Library {
    private const val TAG = "Library"
    private const val COVER_W = 360
    private const val FAVORITES = "favorites"
    private const val OPEN_BOOK = "open"
    private const val SORT = "sort"
    private const val ORDER = "order:"
    private const val TITLE = "title:"
    private const val FINISHED = "finished"
    private const val BIN = ".bin"
    private const val META_VERSION = 2
    private const val SHELF_BACK = 0xFF2A1A10.toInt()
    private const val SHELF_BACK_LIGHT = 0xFF3A2416.toInt()

    val io: ExecutorService = Executors.newFixedThreadPool(2)
    val main = Handler(Looper.getMainLooper())

    private val bitmaps = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun root(): File = File(Environment.getExternalStorageDirectory(), "Read-y")

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun isEpub(f: File) = f.extension.equals("epub", true)
    fun isPdf(f: File) = f.extension.equals("pdf", true)
    fun isBook(f: File) = f.isFile && (isEpub(f) || isPdf(f))

    /**
     * The shelf of favorite books. It is not a real folder, but it stands at the top of the
     * library and is shown and opened like one.
     */
    fun favoritesShelf(): File = File(root(), ".favorites")

    /** Whether [f] opens as a shelf of its own rather than as a book. */
    fun isShelf(f: File) = f.isDirectory || f == favoritesShelf()

    /** What stands on the shelf [dir], in the order chosen for the library. */
    fun shelf(ctx: Context, dir: File): List<File> =
        arrange(ctx, dir, if (dir == favoritesShelf()) favorites(ctx) else list(dir))

    // ---- order ----

    private fun libraryPrefs(ctx: Context) = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)

    /** Changes whenever something moves on the shelves, so that the miniatures showing them are redrawn. */
    private var stamp = 0L

    fun sort(ctx: Context): Sort =
        Sort.values().getOrElse(libraryPrefs(ctx).getInt(SORT, 0)) { Sort.MANUAL }

    fun setSort(ctx: Context, sort: Sort) {
        libraryPrefs(ctx).edit().putInt(SORT, sort.ordinal).apply()
        stamp++
    }

    /** The names on the shelf [dir] in the order the reader gave them; empty if it was never touched. */
    private fun order(ctx: Context, dir: File): List<String> {
        val json = libraryPrefs(ctx).getString(ORDER + dir.path, null) ?: return emptyList()
        return try {
            JSONArray(json).let { a -> List(a.length()) { a.getString(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setOrder(ctx: Context, dir: File, names: List<String>) {
        libraryPrefs(ctx).edit().putString(ORDER + dir.path, JSONArray(names).toString()).apply()
        stamp++
    }

    /**
     * Puts [items] in the order chosen for the library. [dir] is the shelf they stand on, whose own
     * order [Sort.MANUAL] follows; search results, from several shelves, have none.
     */
    fun arrange(ctx: Context, dir: File?, items: List<File>): List<File> {
        fun title(f: File) = (if (f.isDirectory) f.name else cachedMeta(ctx, f)?.title ?: f.nameWithoutExtension).lowercase()
        val byName = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        return when (sort(ctx)) {
            Sort.MANUAL -> {
                val order = if (dir == null) emptyList() else order(ctx, dir)
                // What was added since stays behind the rest, in its usual order.
                byName.sortedBy { f -> order.indexOf(f.name).let { if (it < 0) Int.MAX_VALUE else it } }
            }
            Sort.TITLE -> byName.sortedWith(compareBy({ !it.isDirectory }, ::title))
            Sort.AUTHOR -> byName.sortedWith(
                // Books without a known author go last.
                compareBy({ !it.isDirectory }, { cachedMeta(ctx, it)?.author?.lowercase().orEmpty().ifEmpty { "\uFFFF" } }, ::title),
            )
            Sort.LAST_READ -> byName.sortedWith(compareBy({ !it.isDirectory }, { -state(ctx, it).lastRead }, ::title))
            Sort.PROGRESS -> byName.sortedWith(compareBy({ !it.isDirectory }, { -state(ctx, it).percent }, ::title))
        }
    }

    // ---- changing the library ----

    /** [name] as it can be given to a shelf or a book, or null if it cannot. */
    private fun cleanName(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && !it.startsWith(".") && it.none { c -> c == '/' || c == '\\' } }

    /** Makes a new shelf, a folder, on the shelf [dir]. False if the name cannot be used or is taken. */
    fun createShelf(ctx: Context, dir: File, name: String): Boolean {
        val clean = cleanName(name) ?: return false
        val made = File(dir, clean).let { !it.exists() && it.mkdir() }
        if (made) stamp++
        return made
    }

    /**
     * Moves a book, or a folder with all it holds, onto the shelf [dest]. False if it cannot go
     * there, e.g. because [dest] already has something of that name.
     */
    fun move(ctx: Context, f: File, dest: File): Boolean =
        dest.isDirectory && dest != f && !dest.path.startsWith(f.path + File.separator) &&
            relocate(ctx, f, File(dest, f.name))

    /**
     * Gives a shelf or a book the name [name]. A book keeps its file ending, and is shown under
     * that name from then on, whatever title is written in the file. Returns it under its new
     * name, or null if the name cannot be used or is taken.
     */
    fun rename(ctx: Context, f: File, name: String): File? {
        val clean = cleanName(name) ?: return null
        val parent = f.parentFile ?: return null
        val target = File(parent, if (f.isDirectory) clean else "$clean.${f.extension}")
        if (target != f) {
            if (!relocate(ctx, f, target)) return null
            // It keeps its place on the shelf.
            val names = order(ctx, parent)
            if (f.name in names) setOrder(ctx, parent, names.map { if (it == f.name) target.name else it })
        }
        if (!target.isDirectory) libraryPrefs(ctx).edit().putString(TITLE + target.path, clean).apply()
        stamp++
        return target
    }

    private fun bin() = File(root(), BIN)

    /**
     * Takes a book, or a folder with all it holds, off its shelf. It is not gone yet: it lies in
     * a hidden folder of the library until [purge] or [emptyBin], and [restore] puts it back.
     * Returns where it lies, or null if it could not be taken away.
     */
    fun delete(ctx: Context, f: File): File? {
        val place = File(bin(), System.currentTimeMillis().toString())
        if (!place.mkdirs()) return null
        val target = File(place, f.name)
        return if (relocate(ctx, f, target)) target else null
    }

    /** Puts what [delete] took away back [to] where it was, with all that was remembered about it. */
    fun restore(ctx: Context, binned: File, to: File): Boolean =
        to.parentFile?.isDirectory == true && relocate(ctx, binned, to).also { if (it) binned.parentFile?.delete() }

    /** Deletes for good what [delete] took away to [binned], and forgets what was remembered about it. Blocking. */
    fun purge(ctx: Context, binned: File) = erase(ctx, binned.parentFile ?: binned)

    /** Deletes for good whatever is left in the bin, e.g. after the app was closed before the undo ran out. Blocking. */
    fun emptyBin(ctx: Context) {
        if (bin().exists()) erase(ctx, bin())
    }

    private fun erase(ctx: Context, f: File) {
        val books = booksIn(f)
        if (!f.deleteRecursively()) return
        remember(ctx, f.path, null)
        for ((path, modified) in books) coverFile(ctx, path, modified).delete()
    }

    // ---- books from other apps ----

    /**
     * Copies the book another app handed over as [uri] onto the first shelf of the library and
     * returns it there; a book that already is in the library is returned as it is. Null if it
     * is neither an EPUB nor a PDF. [mime] is what the other app said it is. Blocking.
     */
    fun import(ctx: Context, uri: Uri, mime: String?): File? {
        if (uri.scheme == "file") uri.path?.let(::File)?.let {
            if (it.path.startsWith(root().path + File.separator) && isBook(it)) return it
        }
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = -1L
        if (uri.scheme == "content") try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            // The name is only a nicety.
        }
        root().mkdirs()
        val temp = File(root(), ".import")
        val head = ByteArray(4)
        (ctx.contentResolver.openInputStream(uri) ?: return null).use { ins ->
            FileOutputStream(temp).use { ins.copyTo(it) }
        }
        temp.inputStream().use { it.read(head) }
        // What the file starts with says best what it is.
        val ext = when {
            String(head, Charsets.ISO_8859_1) == "%PDF" -> "pdf"
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                (name.endsWith(".epub", true) || mime == "application/epub+zip" || mime == "application/octet-stream" || mime == null) -> "epub"
            else -> {
                temp.delete()
                return null
            }
        }
        val base = (cleanName(name.substringBeforeLast('.', name)) ?: "Book").take(120)
        var target = File(root(), "$base.$ext")
        var n = 2
        // The same book handed over again is not added a second time.
        while (target.exists() && target.length() != temp.length()) target = File(root(), "$base (${n++}).$ext")
        if (target.exists()) temp.delete() else if (!temp.renameTo(target)) return null
        stamp++
        return target
    }

    /** The books that are [f] or inside it, each with its date: what their covers are filed under. */
    private fun booksIn(f: File): Map<String, Long> =
        (if (f.isDirectory) f.walkTopDown().filter(::isBook) else sequenceOf(f)).associate { it.path to it.lastModified() }

    /** Takes [f] to [target], which must be free, taking along what is remembered about it. */
    private fun relocate(ctx: Context, f: File, target: File): Boolean {
        if (target.exists()) return false
        val old = f.path
        // Note the covers before the paths change.
        val books = booksIn(f)
        if (!f.renameTo(target)) return false
        remember(ctx, old, target.path)
        for ((path, modified) in books) {
            coverFile(ctx, path, modified).renameTo(coverFile(ctx, target.path + path.removePrefix(old), modified))
        }
        stamp++
        FavoritesWidget.refresh(ctx)
        return true
    }

    /**
     * Everything remembered under the path [old] or a path inside it (reading positions, details,
     * favorites, finished books, the open book, orders and names) is remembered under [new]
     * instead, or forgotten if that is null.
     */
    private fun remember(ctx: Context, old: String, new: String?) {
        fun within(path: String) = path == old || path.startsWith(old + File.separator)
        fun moved(path: String) = new + path.removePrefix(old)
        for (prefs in arrayOf(statePrefs(ctx), metaPrefs(ctx))) {
            val e = prefs.edit()
            for ((key, value) in prefs.all) if (value is String && within(key)) {
                e.remove(key)
                if (new != null) e.putString(moved(key), value)
            }
            e.apply()
        }
        for (key in arrayOf(FAVORITES, FINISHED)) {
            val paths = statePrefs(ctx).getStringSet(key, null) ?: continue
            if (paths.none(::within)) continue
            val kept = HashSet<String>()
            for (path in paths) if (!within(path)) kept.add(path) else if (new != null) kept.add(moved(path))
            statePrefs(ctx).edit().putStringSet(key, kept).apply()
        }
        openBook(ctx)?.let { if (within(it.path)) setOpenBook(ctx, if (new == null) null else File(moved(it.path))) }
        val library = libraryPrefs(ctx)
        val e = library.edit()
        for ((key, value) in library.all) for (kind in arrayOf(ORDER, TITLE)) {
            val path = key.removePrefix(kind)
            if (key.startsWith(kind) && value is String && within(path)) {
                e.remove(key)
                if (new != null) e.putString(kind + moved(path), value)
            }
        }
        e.apply()
    }

    /**
     * The books on the shelf [dir] and on every shelf inside it whose title, author or file name
     * contains [query]. Blocking: reads the details of books not seen before.
     */
    fun search(ctx: Context, dir: File, query: String): List<File> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val books =
            if (dir == favoritesShelf()) favorites(ctx).asSequence()
            else dir.walkTopDown().onEnter { !it.name.startsWith(".") }.filter(::isBook)
        return books.filter { f ->
            val m = meta(ctx, f)
            q in m.title.lowercase() || q in m.author.lowercase() || q in f.name.lowercase()
        }.toList()
    }

    /** Sub-folders first, then books, both alphabetical. */
    fun list(dir: File): List<File> =
        dir.listFiles()
            ?.filter { !it.name.startsWith(".") && (it.isDirectory || isBook(it)) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()

    // ---- metadata & covers ----

    private fun metaPrefs(ctx: Context) = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)
    private fun statePrefs(ctx: Context) = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)

    private fun sig(f: File) = "${f.lastModified()}_${f.length()}"

    fun coverFile(ctx: Context, f: File): File = coverFile(ctx, f.path, f.lastModified())

    private fun coverFile(ctx: Context, path: String, modified: Long): File =
        File(File(ctx.filesDir, "covers"), "${Integer.toHexString(path.hashCode())}_$modified.jpg")

    fun cachedMeta(ctx: Context, f: File): BookMeta? {
        val json = metaPrefs(ctx).getString(f.path, null) ?: return null
        return try {
            val o = JSONObject(json)
            if (o.optString("sig") != sig(f) || o.optInt("v") != META_VERSION) null
            else named(ctx, f, BookMeta(o.optString("title"), o.optString("author"), o.optInt("pages"), o.optInt("chapters")))
        } catch (e: Exception) {
            null
        }
    }

    /** Reads title, author, page count and cover from the file. Blocking; results are cached. */
    fun meta(ctx: Context, f: File): BookMeta {
        val cover = coverFile(ctx, f)
        cachedMeta(ctx, f)?.let { if (cover.exists()) return it }

        var title = f.nameWithoutExtension
        var author = ""
        var pages = 0
        var chapters = 0
        var bmp: Bitmap? = null
        try {
            if (isEpub(f)) {
                EpubBook.open(f).use { b ->
                    if (b.title.isNotBlank()) title = b.title
                    author = b.author
                    chapters = b.spine.size
                    pages = (b.weights.sum() / 2600).toInt().coerceAtLeast(1)
                    bmp = b.coverBitmap(COVER_W)
                }
            } else {
                PdfInfo.read(f)?.let { (named, by) ->
                    if (named.isNotEmpty()) title = named
                    author = by
                }
                PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                    pages = r.pageCount
                    if (pages > 0) r.openPage(0).use { p ->
                        val h = (COVER_W.toFloat() * p.height / p.width).toInt().coerceIn(1, COVER_W * 3)
                        val b = Bitmap.createBitmap(COVER_W, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp = b
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Cannot read ${f.path}", e)
        }

        val meta = BookMeta(title, author, pages, chapters)
        try {
            cover.parentFile?.mkdirs()
            FileOutputStream(cover).use { (bmp ?: placeholder(title, author)).compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot save cover for ${f.path}", e)
        }
        val o = JSONObject().put("sig", sig(f)).put("v", META_VERSION).put("title", title).put("author", author)
            .put("pages", pages).put("chapters", chapters)
        metaPrefs(ctx).edit().putString(f.path, o.toString()).apply()
        return named(ctx, f, meta)
    }

    /** [m] under the name the reader gave the book with [rename], if any. */
    private fun named(ctx: Context, f: File, m: BookMeta): BookMeta =
        libraryPrefs(ctx).getString(TITLE + f.path, null)?.let { m.copy(title = it) } ?: m

    private fun coverKey(f: File) = "${f.path}:${f.lastModified()}"

    fun cachedCover(f: File): Bitmap? = bitmaps.get(coverKey(f))

    /** Decodes the cover written by [meta]. Blocking. */
    fun cover(ctx: Context, f: File): Bitmap? {
        cachedCover(f)?.let { return it }
        val bmp = BitmapFactory.decodeFile(coverFile(ctx, f).path) ?: return null
        bitmaps.put(coverKey(f), bmp)
        return bmp
    }

    private fun folderKey(dir: File, spec: ShelfSpec) =
        "dir:${dir.path}:${dir.lastModified()}:$stamp:${spec.width}:${spec.columns}"

    fun cachedFolderPreview(dir: File, spec: ShelfSpec): Bitmap? = bitmaps.get(folderKey(dir, spec))

    /**
     * A picture of the case that [dir] opens into, drawn with the same geometry as the real one
     * ([spec]) and scaled down, so that opening the folder is a pure enlargement: the bare top
     * rail, then [ShelfSpec.miniRows] shelves, the last plank closing it at the bottom.
     * Blocking.
     */
    fun folderPreview(ctx: Context, dir: File, spec: ShelfSpec, depth: Int = 0): Bitmap {
        if (depth == 0) cachedFolderPreview(dir, spec)?.let { return it }
        val w = spec.width.toFloat()
        val h = spec.miniH.toFloat()
        val rail = spec.rail.toFloat()
        val bmp = Bitmap.createBitmap(COVER_W, (COVER_W * h / w).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Draw in real case pixels.
        c.scale(COVER_W / w, COVER_W / w)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        paint.alpha = 255
        paint.shader = LinearGradient(
            0f, 0f, w, 0f, intArrayOf(SHELF_BACK, SHELF_BACK_LIGHT, SHELF_BACK), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, rail, w, h, paint)
        paint.shader = null
        val rowH = spec.rowH
        for (row in 1..spec.miniRows) ShelfDecoration.drawPlank(c, w, rail + row * rowH, spec.density, paint)
        paint.shader = null

        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3B2A05.toInt()
            textSize = 11 * spec.density
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        // Cases nested deeper than this are too small to show what stands in them.
        val items = if (depth > 1) emptyList() else shelf(ctx, dir).take(spec.columns * spec.miniRows)
        items.forEachIndexed { i, f ->
            val left = spec.post + (i % spec.columns) * spec.itemW + spec.px(9).toFloat()
            val top = rail + (i / spec.columns) * rowH + spec.px(16)
            val cover = RectF(left, top, left + spec.coverW, top + spec.coverH)
            val name: String
            if (isShelf(f)) {
                name = f.name
                // A case is narrower than a book but stands taller, centred in its place.
                val inner = folderPreview(ctx, f, spec, depth + 1)
                val top = cover.top - spec.miniRise
                val innerW = (cover.bottom - top) * inner.width / inner.height
                val x = cover.centerX() - innerW / 2
                c.drawBitmap(inner, null, RectF(x, top, x + innerW, cover.bottom), paint)
            } else {
                name = meta(ctx, f).title
                paint.color = 0xFF24160D.toInt()
                c.drawRect(cover, paint)
                cover(ctx, f)?.let { drawCropped(c, it, cover, paint) }
                ShelfArt.drawBinding(c, cover, spec.density, paint)
            }

            val plaque = Rect(
                left.toInt(), (cover.bottom + spec.px(5)).toInt(),
                cover.right.toInt(), (cover.bottom + spec.px(5) + spec.px(34)).toInt(),
            )
            PlaqueDrawable(spec.density).apply {
                favorite = !isShelf(f) && isFavorite(ctx, f)
                finished = !isShelf(f) && isFinished(ctx, f)
                bounds = plaque
                draw(c)
            }
            val inset = spec.px(PlaqueDrawable.TEXT_INSET_DP)
            val faceHeight = plaque.height() - spec.px(PlaqueDrawable.SHADOW_DP)
            val text = StaticLayout.Builder
                .obtain(name, 0, name.length, title, (plaque.width() - 2 * inset).coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.9f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            c.save()
            c.translate((plaque.left + inset).toFloat(), plaque.top + (faceHeight - text.height) / 2f)
            text.draw(c)
            c.restore()
        }

        c.save()
        c.translate(0f, rail)
        ShelfDecoration.drawCase(c, w, h - rail, spec, paint)
        c.restore()
        ShelfDecoration.drawRail(c, w, rail, spec.density, paint)
        paint.shader = null
        if (depth == 0) bitmaps.put(folderKey(dir, spec), bmp)
        return bmp
    }

    /**
     * How far a book is read, as a band along the foot of its [cover], for the widget, which has
     * no room for the rod the shelf shows above a book (reading_progress.xml). Nothing for a book
     * not begun. [d] is the density.
     */
    fun drawProgress(c: Canvas, cover: RectF, percent: Float, d: Float, paint: Paint) {
        if (percent <= 0f) return
        val top = cover.bottom - 6 * d
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0xE61A0F08.toInt()
        c.drawRect(cover.left, top, cover.right, cover.bottom, paint)
        paint.color = 0xFFEBCB5C.toInt()
        c.drawRect(cover.left, top + d, cover.left + cover.width() * percent, cover.bottom, paint)
        paint.alpha = 255
    }

    /** Draws [b] into [dst] the way an ImageView with centerCrop does. */
    fun drawCropped(c: Canvas, b: Bitmap, dst: RectF, paint: Paint) {
        val scale = maxOf(dst.width() / b.width, dst.height() / b.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val src = Rect(
            ((b.width - sw) / 2).toInt(), ((b.height - sh) / 2).toInt(),
            ((b.width + sw) / 2).toInt(), ((b.height + sh) / 2).toInt(),
        )
        c.drawBitmap(b, src, dst, paint)
    }

    private fun placeholder(title: String, author: String): Bitmap {
        val w = COVER_W
        val h = w * 3 / 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val leather = intArrayOf(
            0xFF5B1A1A.toInt(), 0xFF1F3A5F.toInt(), 0xFF234D32.toInt(), 0xFF4A2C5A.toInt(), 0xFF5A3A1A.toInt(),
        )
        val gold = 0xFFD4AF37.toInt()
        c.drawColor(leather[(title.hashCode() and 0x7fffffff) % leather.size])
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        c.drawRect(16f, 16f, w - 16f, h - 16f, frame)

        fun text(s: String, size: Float, style: Int, lines: Int): StaticLayout {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gold
                textSize = size
                typeface = Typeface.create(Typeface.SERIF, style)
            }
            return StaticLayout.Builder.obtain(s, 0, s.length, tp, w - 72)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }

        val t = text(title, 36f, Typeface.BOLD, 6)
        c.save()
        c.translate(36f, (h - t.height) / 2f - 24f)
        t.draw(c)
        c.restore()
        if (author.isNotBlank()) {
            val a = text(author, 24f, Typeface.ITALIC, 2)
            c.save()
            c.translate(36f, h - 44f - a.height)
            a.draw(c)
            c.restore()
        }
        return bmp
    }

    // ---- reading state & favorites ----

    fun state(ctx: Context, f: File): BookState {
        val json = statePrefs(ctx).getString(f.path, null) ?: return BookState()
        return try {
            val o = JSONObject(json)
            BookState(
                o.optInt("chapter"),
                o.optDouble("fraction", 0.0).toFloat(),
                o.optDouble("percent", 0.0).toFloat(),
                o.optLong("lastRead"),
            )
        } catch (e: Exception) {
            BookState()
        }
    }

    fun saveState(ctx: Context, f: File, s: BookState) {
        val o = JSONObject().put("chapter", s.chapter).put("fraction", s.fraction.toDouble())
            .put("percent", s.percent.toDouble()).put("lastRead", s.lastRead)
        statePrefs(ctx).edit().putString(f.path, o.toString()).apply()
    }

    /** The book currently open in the reader, remembered so that it is reopened after the app was closed. */
    fun openBook(ctx: Context): File? = statePrefs(ctx).getString(OPEN_BOOK, null)?.let(::File)

    fun setOpenBook(ctx: Context, f: File?) {
        statePrefs(ctx).edit().apply { if (f == null) remove(OPEN_BOOK) else putString(OPEN_BOOK, f.path) }.apply()
    }

    private fun favoritePaths(ctx: Context): Set<String> =
        statePrefs(ctx).getStringSet(FAVORITES, null) ?: emptySet()

    fun isFavorite(ctx: Context, f: File) = f.path in favoritePaths(ctx)

    fun setFavorite(ctx: Context, f: File, on: Boolean) {
        val set = HashSet(favoritePaths(ctx))
        if (on) set.add(f.path) else set.remove(f.path)
        statePrefs(ctx).edit().putStringSet(FAVORITES, set).apply()
        stamp++
        FavoritesWidget.refresh(ctx)
    }

    /** Books the reader marked as read to the end, or read to the end. */
    fun isFinished(ctx: Context, f: File) = f.path in (statePrefs(ctx).getStringSet(FINISHED, null) ?: emptySet())

    fun setFinished(ctx: Context, f: File, on: Boolean) {
        val set = HashSet(statePrefs(ctx).getStringSet(FINISHED, null) ?: emptySet())
        if (on) set.add(f.path) else set.remove(f.path)
        statePrefs(ctx).edit().putStringSet(FINISHED, set).apply()
        stamp++
    }

    fun favorites(ctx: Context): List<File> =
        favoritePaths(ctx).map(::File)
            // A deleted book lies in the bin until it is gone for good, and is no favorite there.
            .filter { it.isFile && !it.path.startsWith(bin().path + File.separator) }
            .sortedBy { it.name.lowercase() }
}
