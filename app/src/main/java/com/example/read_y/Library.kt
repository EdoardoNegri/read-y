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
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class BookMeta(val title: String, val author: String, val pages: Int, val chapters: Int)

/** Reading position. For PDFs [chapter] is the page index; for EPUBs it is the spine index. */
data class BookState(
    var chapter: Int = 0,
    var fraction: Float = 0f,
    var percent: Float = 0f,
    var lastRead: Long = 0,
)

/** The geometry of the bookshelf grid (see item_book.xml), shared by the real shelf and its miniatures. */
class ShelfSpec(val width: Int, val columns: Int, val density: Float) {
    fun px(dp: Int) = (dp * density + 0.5f).toInt()

    /** Thickness of the bookcase's side posts. */
    val post get() = px(12)
    val itemW get() = (width - 2 * post) / columns
    val coverW get() = itemW - 2 * px(9)
    val coverH get() = (coverW * 1.5f).toInt()
    val rowH get() = px(16) + coverH + px(5) + px(34) + px(5)
}

/** The on-disk library: the "Read-y" folder in shared storage, plus cached metadata and reading state. */
object Library {
    private const val TAG = "Library"
    private const val COVER_W = 360
    private const val FAVORITES = "favorites"
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

    fun coverFile(ctx: Context, f: File): File =
        File(File(ctx.filesDir, "covers"), "${Integer.toHexString(f.path.hashCode())}_${f.lastModified()}.jpg")

    fun cachedMeta(ctx: Context, f: File): BookMeta? {
        val json = metaPrefs(ctx).getString(f.path, null) ?: return null
        return try {
            val o = JSONObject(json)
            if (o.optString("sig") != sig(f)) null
            else BookMeta(o.optString("title"), o.optString("author"), o.optInt("pages"), o.optInt("chapters"))
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
        val o = JSONObject().put("sig", sig(f)).put("title", title).put("author", author)
            .put("pages", pages).put("chapters", chapters)
        metaPrefs(ctx).edit().putString(f.path, o.toString()).apply()
        return meta
    }

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
        "dir:${dir.path}:${dir.lastModified()}:${spec.width}:${spec.columns}"

    fun cachedFolderPreview(dir: File, spec: ShelfSpec): Bitmap? = bitmaps.get(folderKey(dir, spec))

    /**
     * A picture of the top of the shelf inside [dir], drawn with the same geometry as the real
     * shelf ([spec]) and scaled down, so that opening the folder is a pure enlargement. Blocking.
     */
    fun folderPreview(ctx: Context, dir: File, spec: ShelfSpec, depth: Int = 0): Bitmap {
        if (depth == 0) cachedFolderPreview(dir, spec)?.let { return it }
        val bmp = Bitmap.createBitmap(COVER_W, COVER_W * 3 / 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Draw in real shelf pixels: the visible part is one shelf-width wide and 1.5 as tall.
        c.scale(COVER_W.toFloat() / spec.width, COVER_W.toFloat() / spec.width)
        val w = spec.width.toFloat()
        val h = w * 1.5f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        paint.alpha = 255
        paint.shader = LinearGradient(
            0f, 0f, w, 0f, intArrayOf(SHELF_BACK, SHELF_BACK_LIGHT, SHELF_BACK), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val rowH = spec.rowH
        var plankBottom = rowH.toFloat()
        while (plankBottom - spec.px(49) < h) {
            ShelfDecoration.drawPlank(c, w, plankBottom, spec.density, paint)
            plankBottom += rowH
        }
        paint.shader = null
        if (depth > 1) {
            ShelfDecoration.drawCase(c, w, h, spec, paint)
            return bmp
        }

        val rows = (h / rowH).toInt() + 1
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3B2A05.toInt()
            textSize = 11 * spec.density
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        list(dir).take(spec.columns * rows).forEachIndexed { i, f ->
            val left = spec.post + (i % spec.columns) * spec.itemW + spec.px(9).toFloat()
            val top = (i / spec.columns) * rowH + spec.px(16).toFloat()
            val cover = RectF(left, top, left + spec.coverW, top + spec.coverH)
            paint.color = 0xFF24160D.toInt()
            c.drawRect(cover, paint)
            val name: String
            if (f.isDirectory) {
                name = f.name
                c.drawBitmap(folderPreview(ctx, f, spec, depth + 1), null, cover, paint)
            } else {
                name = meta(ctx, f).title
                cover(ctx, f)?.let { drawCropped(c, it, cover, paint) }
                ShelfArt.drawBinding(c, cover, spec.density, paint)
                val percent = state(ctx, f).percent
                if (percent > 0f) {
                    val barTop = cover.bottom - spec.px(4)
                    paint.color = 0x55FFFFFF
                    c.drawRect(cover.left, barTop, cover.right, cover.bottom, paint)
                    paint.color = 0xFFF6E27A.toInt()
                    c.drawRect(cover.left, barTop, cover.left + cover.width() * percent, cover.bottom, paint)
                }
                if (isFavorite(ctx, f)) ContextCompat.getDrawable(ctx, R.drawable.ic_star)?.apply {
                    val size = spec.px(22)
                    val x = (cover.right - spec.px(3) - size).toInt()
                    val y = (cover.top + spec.px(3)).toInt()
                    setBounds(x, y, x + size, y + size)
                    draw(c)
                }
            }

            val plaque = Rect(
                left.toInt(), (cover.bottom + spec.px(5)).toInt(),
                cover.right.toInt(), (cover.bottom + spec.px(5) + spec.px(34)).toInt(),
            )
            PlaqueDrawable(spec.density).apply {
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
        ShelfDecoration.drawCase(c, w, h, spec, paint)
        paint.shader = null
        if (depth == 0) bitmaps.put(folderKey(dir, spec), bmp)
        return bmp
    }

    /** Draws [b] into [dst] the way an ImageView with centerCrop does. */
    private fun drawCropped(c: Canvas, b: Bitmap, dst: RectF, paint: Paint) {
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

    private fun favoritePaths(ctx: Context): Set<String> =
        statePrefs(ctx).getStringSet(FAVORITES, null) ?: emptySet()

    fun isFavorite(ctx: Context, f: File) = f.path in favoritePaths(ctx)

    fun setFavorite(ctx: Context, f: File, on: Boolean) {
        val set = HashSet(favoritePaths(ctx))
        if (on) set.add(f.path) else set.remove(f.path)
        statePrefs(ctx).edit().putStringSet(FAVORITES, set).apply()
        FavoritesWidget.refresh(ctx)
    }

    fun favorites(ctx: Context): List<File> =
        favoritePaths(ctx).map(::File).filter { it.isFile }.sortedBy { it.name.lowercase() }
}
