package io.github.edoardonegri.ready

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/** What a book says about itself. [published] is a day, a month or a year, as 2011-05-17, 2011-05 or 2011; empty if unknown. */
data class BookMeta(val title: String, val author: String, val pages: Int, val published: String = "")

/**
 * Reading position. For PDFs [chapter] is the page index; for EPUBs it is the spine index. [version]
 * is how the spine was counted when it was written down: before [VERSION], an EPUB's spine still
 * began with the page of its cover, which is now left out of it (FlowBook.skipped).
 */
data class BookState(
    var chapter: Int = 0,
    var fraction: Float = 0f,
    var percent: Float = 0f,
    var lastRead: Long = 0,
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 2
    }
}

/**
 * The ways a shelf can be put in order. A shelf standing on it takes its place among the books:
 * by its name, or by the book on it, or on a shelf inside it, that would come first. Each can be
 * turned round; [descends] is the way it runs until the reader does so.
 */
enum class Sort(val label: Int, val descends: Boolean = false) {
    /** The order the reader gave the shelf by dragging; until then folders first, then by file name. */
    MANUAL(R.string.sort_manual),
    TITLE(R.string.sort_title),
    AUTHOR(R.string.sort_author),

    /** Most recently read first. */
    LAST_READ(R.string.sort_last_read, descends = true),

    /** Furthest read first. */
    PROGRESS(R.string.sort_progress, descends = true),
}

/** The geometry of the bookshelf grid (see item_book.xml), shared by the real shelf and its miniatures. */
class ShelfSpec(val width: Int, val columns: Int, val density: Float) {
    fun px(dp: Int) = (dp * density + 0.5f).toInt()

    /** Thickness of the bookcase's side posts. */
    val post get() = px(12)

    /** The room a place leaves free at either side of its book: half of what lies between two books. */
    val gap get() = px(7)

    /**
     * How far from the edge the places start: clear of the post, and by as much more as keeps
     * the outer books further from the posts than the books are from each other.
     */
    val side get() = post + px(2)
    val itemW get() = (width - 2 * side) / columns
    val coverW get() = itemW - 2 * gap
    val coverH get() = (coverW * BOOK).toInt()
    val rowH get() = miniRise + coverH + plank

    /**
     * How far a miniature reaches above the top of the books beside it: all the way up to the
     * shelf above, so that it fills the space between the two shelves.
     */
    val miniRise get() = px(14)

    /** The part of a row that lies on its plank: the plaque and the space around it. */
    val plank get() = px(44)

    /**
     * The rows of the case a miniature shows, from under its top rail down: as few as let it
     * stand between its neighbours, which makes it as large as it can be. The books of the last
     * row stand on the plank the miniature itself stands on.
     */
    val miniRows get() =
        ceil((width.toFloat() * (coverH + miniRise) / (itemW - px(4)).coerceAtLeast(1) + plank) / rowH.coerceAtLeast(1))
            .toInt().coerceAtLeast(1)

    /** The height of the part of the case a miniature shows, which ends where the plank of its last row begins. */
    val miniH get() = (miniRows * rowH - plank).coerceAtLeast(1)

    /** The width of a miniature, as tall as a book and [miniRise]: a little more than a book's. */
    val miniW get() = (width.toLong() * (coverH + miniRise) / miniH).toInt()

    companion object {
        /** How many times as tall as wide a book stands on the shelf. */
        const val BOOK = 1.6f

        /** How many places a row of the shelves has on the screen [res] describes: one for every 118 dp of width, and three at least. */
        fun columns(res: Resources) = (res.configuration.screenWidthDp / 118).coerceAtLeast(3)
    }
}

/** The library: the "Read_y" folder the reader let the app have (see [Store]), plus cached metadata and reading state. */
object Library {
    private const val TAG = "Library"

    /** How wide covers are drawn, at the least, and what the made ones are drawn for (placeholder). */
    private const val COVER_W = 360

    /**
     * How wide the covers kept for the shelf are made: as wide as a cover stands on the shelf of this
     * phone, held upright or turned, so that none is shown larger than it was made. Never narrower
     * than [COVER_W]. Worked out once, from the size of the screen.
     */
    @Volatile
    private var coverWidth = 0

    private fun coverWidth(ctx: Context): Int {
        coverWidth.takeIf { it > 0 }?.let { return it }
        val m = ctx.resources.displayMetrics
        val widest = intArrayOf(m.widthPixels, m.heightPixels).maxOf { w ->
            ShelfSpec(w, (w / m.density / 118).toInt().coerceAtLeast(3), m.density).coverW
        }
        return maxOf(COVER_W, widest).also { coverWidth = it }
    }

    /**
     * Which way the covers kept in files were made, in their names, with how wide ([coverWidth]):
     * covers made before the made covers took the shape of a book on the shelf, or narrower, are
     * made anew, and the old files let go.
     */
    private const val COVERS_MADE = 4

    /**
     * How far in from the edge the gold frame of a made cover runs (placeholder): on it, all but a
     * hair; on the spine side, just beside the crease the shelf draws there (ShelfArt.HINGE). The
     * frame is [FRAME_W] wide, and what is written stands [TEXT_IN] inside it.
     */
    private const val FRAME_W = 4f
    private const val FRAME_IN = 4f
    private const val FRAME_SPINE = COVER_W * ShelfArt.HINGE + FRAME_W / 2
    private const val TEXT_IN = 32f

    /** How much shows of what stands beside the chosen things in a miniature, of 255; as on the shelf (ShelfAdapter). */
    private const val FAINT_ALPHA = 102
    private const val FAVORITES = "favorites"
    private const val SORT = "sort"
    private const val DESCENDING = "descending"
    private const val ORDER = "order:"
    private const val TITLE = "title:"
    private const val AUTHOR = "author:"
    private const val COVER = "cover:"

    /** What is remembered of the shelves and the books by their paths, by its prefix, with the name it has in the backup. */
    private const val MODE = "covermode:"
    private const val TONE = "tone:"
    private val BY_PATH = linkedMapOf(
        ORDER to "orders", TITLE to "titles", AUTHOR to "authors", COVER to "covers", MODE to "covermodes", TONE to "tones",
    )
    private const val FINISHED = "finished"

    /** How far into a book it counts as read to the end: the last page, with room for rounding. */
    const val FINISHED_AT = 0.999f

    /**
     * The most a page counts for that is not the cover that ends the book: counted in pages, the last
     * page of a book of a thousand is 99.9% of it, which would finish the book before its cover is
     * turned to, and keep it finished after the reader has gone back from the cover.
     */
    const val LAST_PAGE_AT = FINISHED_AT - 0.001f

    private const val WIDGET = "widget"
    private const val WIDGET_ORDER = "widgetOrder"
    private const val BIN = ".bin"
    private const val META_VERSION = 3
    private const val SHELF_BACK = 0xFF2A1A10.toInt()
    private const val SHELF_BACK_LIGHT = 0xFF3A2416.toInt()

    /** The work behind the screen that is not for the shelves, in the order it comes; its threads end once there is none for a while. */
    val io: ExecutorService = idle(ThreadPoolExecutor(2, 2, IDLE_S, TimeUnit.SECONDS, LinkedBlockingQueue(), behind("library")))

    /** How long a thread of [io] waits for work before it ends, in seconds. */
    const val IDLE_S = 30L

    /** [pool], whose threads all end once they have had no work for a while, and are made again when there is some. */
    fun idle(pool: ThreadPoolExecutor): ThreadPoolExecutor = pool.apply { allowCoreThreadTimeOut(true) }

    /**
     * Reads the covers and draws the miniatures the shelves show, in the order the reader sees them
     * wanted: first the books of the shelf and the wooden case of each shelf in it, then what
     * stands in those shelves, and then what stands on the shelves in those. What is asked for
     * [ahead] comes last. Of the same kind the first asked for is done first, which is the top left
     * of the shelf.
     */
    private val shelfWork = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>(), behind("shelf"),
    ).apply {
        // Else the first two jobs would be run at once, whatever waits behind them.
        prestartAllCoreThreads()
    }

    /** What is done first in [shelfWork]: lower is sooner. */
    const val WORK_FIRST = 0
    const val WORK_BOOKS_IN_SHELF = 1
    const val WORK_NESTED = 2
    const val WORK_AHEAD = 3

    private val jobs = AtomicLong()

    private class Job(private val priority: Int, private val work: () -> Unit) : Runnable, Comparable<Job> {
        private val order = jobs.incrementAndGet()
        override fun run() = work()
        override fun compareTo(other: Job): Int =
            if (priority != other.priority) priority.compareTo(other.priority) else order.compareTo(other.order)
    }

    /** Has [work] done in the background for the shelves, after all that was asked with a lower [priority]. */
    fun queue(priority: Int, work: () -> Unit) = shelfWork.execute(Job(priority, work))

    /**
     * Has [work] queued once what is being done on the screen right now is done: the places of a
     * shelf are all asked for in one go, and what is asked first must not start before the covers
     * of the places still to be asked for have been queued behind it.
     */
    fun later(priority: Int, work: () -> Unit) {
        main.post { queue(priority, work) }
    }

    /**
     * Does [work] for the shelves, such as loading a cover about to come into view, once nothing
     * asked of [queue] otherwise is waiting; work asked for ahead is done in the order asked.
     */
    fun ahead(work: () -> Unit) = queue(WORK_AHEAD, work)

    /** Threads named [name] that work behind the screen, giving way to the thread that draws it. */
    private fun behind(name: String) = ThreadFactory { work ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        }, name)
    }
    val main = Handler(Looper.getMainLooper())

    // The covers and miniatures of a whole shelf or two, however many there are on the phone's memory.
    private val bitmaps = object : LruCache<String, Bitmap>(maxOf(24L shl 20, Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun root(): File = Store.root()

    fun hasAccess(): Boolean = Store.hasAccess()

    /**
     * Makes the folder [uri], which the reader picked, the library. What was remembered about the
     * books in the folder used before is remembered for the same places in this one, so that a
     * library moved over keeps its places. False if [uri] cannot be the library.
     */
    fun grant(ctx: Context, uri: Uri): Boolean {
        val old = Store.grant(uri) ?: return false
        if (old != root()) remember(ctx, old.path, root().path)
        adopted = false
        stamp++
        FavoritesWidget.refresh(ctx)
        return true
    }

    /** A book of flowing text, which FlowEngine lays out, rather than one of fixed pages for PageEngine. */
    fun isFlowing(f: File) = f.extension.lowercase() in FlowBook.EXTENSIONS
    fun isBook(f: File) = f.isDoc && f.extension.lowercase().let { it in FlowBook.EXTENSIONS || it in PageSource.EXTENSIONS }

    /** What stands on the shelf [dir], in the order chosen for the library. Blocking: the folder is listed anew. */
    fun shelf(ctx: Context, dir: File): List<File> = arrange(ctx, dir, list(dir))

    /**
     * What stood on the shelf [dir] when its folder was last listed, in the order chosen for the
     * library; null if it never was. Quick, for showing the shelf while [shelf] looks again.
     */
    fun knownShelf(ctx: Context, dir: File): List<File>? = Store.known(dir)?.let { arrange(ctx, dir, shown(it)) }

    // ---- order ----

    // The preferences are opened on the application, which already knows where they are kept; a
    // screen asking for them first looks at the storage for that.
    private fun libraryPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)

    /**
     * Changes whenever something changes on all the shelves at once, such as the order they are
     * sorted in, so that every miniature is drawn anew; see [touched] for one shelf.
     */
    private var stamp = 0L

    /**
     * For each shelf, by its path, how often something on it, or on a shelf inside it, has
     * changed, so that its miniature is drawn anew, and only it; see [stamp] for all of them.
     */
    private val stamps = ConcurrentHashMap<String, Long>()

    /**
     * Something about [f] changed, or it came or went: the miniatures of the shelves it stands on,
     * near or far, are drawn anew, and its own if it is a shelf.
     */
    internal fun touched(f: File) {
        val top = root().path
        var at: File? = f
        while (at != null) {
            stamps.merge(at.path, 1L, Long::plus)
            if (at.path == top) break
            at = at.parentFile
        }
    }

    fun sort(ctx: Context): Sort =
        Sort.entries.getOrElse(libraryPrefs(ctx).getInt(SORT, 0)) { Sort.MANUAL }

    /** Puts the library in the order [sort], the way round that order usually runs. */
    fun setSort(ctx: Context, sort: Sort) {
        libraryPrefs(ctx).edit { putInt(SORT, sort.ordinal).remove(DESCENDING) }
        stamp++
    }

    /** Whether the order runs from the last to the first: Z to A, the most recently or the furthest read first. */
    fun descending(ctx: Context): Boolean = libraryPrefs(ctx).getBoolean(DESCENDING, sort(ctx).descends)

    fun setDescending(ctx: Context, on: Boolean) {
        libraryPrefs(ctx).edit { putBoolean(DESCENDING, on) }
        stamp++
    }

    /**
     * What is being carried about on the shelves. It has left its place, so the miniatures of
     * the shelves do not show it either until it is put down.
     */
    @Volatile
    var carried: Set<File> = emptySet()

    /** The names on the shelf [dir] in the order the reader gave them; empty if it was never touched. */
    private fun order(ctx: Context, dir: File): List<String> {
        val json = libraryPrefs(ctx).getString(ORDER + dir.path, null) ?: return emptyList()
        return try {
            JSONArray(json).strings()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setOrder(ctx: Context, dir: File, names: List<String>) {
        libraryPrefs(ctx).edit { putString(ORDER + dir.path, JSONArray(names).toString()) }
        touched(dir)
    }

    /**
     * Puts [items] in the order chosen for the library. [dir] is the shelf they stand on, whose own
     * order [Sort.MANUAL] follows; search results, from several shelves, have none.
     */
    fun arrange(ctx: Context, dir: File?, items: List<File>): List<File> {
        // Everything is looked up once, however often the sorting compares it, the disk included.
        val shelves = items.filterTo(HashSet()) { it.isFolder }
        val byName = items.sortedWith(compareBy({ it !in shelves }, { it.name.lowercase() }))
        val titles by lazy {
            byName.associateWith { (if (it in shelves) it.name else cachedMeta(ctx, it)?.title ?: it.nameWithoutExtension).lowercase() }
        }
        fun title(f: File) = titles.getValue(f)

        /** What [of] is for the book [f], or the most it is for any book on the shelf [f] and the shelves inside it. */
        fun <T : Comparable<T>> most(f: File, of: (BookState) -> T): T? =
            if (f in shelves) mostOf(ctx, f.path + File.separator, of = of)?.second else of(state(ctx, f))
        val down = descending(ctx)

        /** By [key], the way round that was chosen; what is the same by it stands by its title. */
        fun <T : Comparable<T>> by(key: (File) -> T): Comparator<File> =
            (if (down) compareByDescending(key) else compareBy(key)).thenBy(::title)
        return when (sort(ctx)) {
            Sort.MANUAL -> {
                val order = if (dir == null) emptyMap() else order(ctx, dir).withIndex().associate { it.value to it.index }
                // What was added since stays behind the rest, in its usual order.
                byName.sortedBy { f -> order[f.name] ?: Int.MAX_VALUE }
                    .let { if (down) it.asReversed() else it }
            }
            Sort.TITLE -> byName.sortedWith(by(::title))
            Sort.AUTHOR -> {
                // A shelf stands among the authors under its own name; books without a known author go last.
                val authors = byName.associateWith {
                    if (it in shelves) title(it) else cachedMeta(ctx, it)?.author?.lowercase().orEmpty().ifEmpty { "\uFFFF" }
                }
                byName.sortedWith(by { authors.getValue(it) })
            }
            Sort.LAST_READ -> {
                val read = byName.associateWith { f -> most(f) { it.lastRead } ?: 0L }
                byName.sortedWith(by { read.getValue(it) })
            }
            Sort.PROGRESS -> {
                val far = byName.associateWith { f -> most(f) { it.percent } ?: 0f }
                byName.sortedWith(by { far.getValue(it) })
            }
        }
    }

    // ---- changing the library ----

    /** [name] as it can be given to a shelf or a book, or null if it cannot. */
    internal fun cleanName(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && !it.startsWith(".") && it.none { c -> c == '/' || c == '\\' } }

    /** Makes a new shelf, a folder, on the shelf [dir]. False if the name cannot be used or is taken. */
    fun createShelf(dir: File, name: String): Boolean {
        val clean = cleanName(name) ?: return false
        val made = Store.mkdir(File(dir, clean))
        if (made) touched(File(dir, clean))
        return made
    }

    /**
     * Moves a book, or a folder with all it holds, onto the shelf [dest]. False if it cannot go
     * there, e.g. because [dest] already has something of that name.
     */
    fun move(ctx: Context, f: File, dest: File): Boolean =
        dest.isFolder && dest != f && !dest.path.startsWith(f.path + File.separator) &&
            relocate(ctx, f, File(dest, f.name))

    /**
     * Gives a shelf or a book the name [name]. A book keeps its file ending, and is shown under
     * that name from then on, whatever title is written in the file. Returns it under its new
     * name, or null if the name cannot be used or is taken.
     */
    fun rename(ctx: Context, f: File, name: String): File? {
        val clean = cleanName(name) ?: return null
        val parent = f.parentFile ?: return null
        val target = File(parent, if (f.isFolder) clean else "$clean.${f.extension}")
        if (target != f) {
            if (!relocate(ctx, f, target)) return null
            // It keeps its place on the shelf.
            val names = order(ctx, parent)
            if (f.name in names) setOrder(ctx, parent, names.map { if (it == f.name) target.name else it })
        }
        if (!target.isFolder) libraryPrefs(ctx).edit { putString(TITLE + target.path, clean) }
        touched(target)
        return target
    }

    private fun bin() = File(root(), BIN)
    private var binned = 0

    /**
     * Takes a book, or a folder with all it holds, off its shelf, which is quick. It is not gone
     * yet: it lies in a hidden folder of the library until [purge] or [emptyBin].
     * Returns where it lies, or null if it could not be taken away.
     */
    fun delete(ctx: Context, f: File): File? {
        // Several things deleted in the same moment each get a place of their own.
        val place = File(bin(), "${System.currentTimeMillis()}-${binned++}")
        if (!Store.mkdirs(place)) return null
        val target = File(place, f.name)
        return if (relocate(ctx, f, target)) target else null
    }

    /** Deletes for good what [delete] took away to [binned], and forgets what was remembered about it. Blocking. */
    fun purge(ctx: Context, binned: File) = erase(ctx, binned.parentFile ?: binned)

    /** Deletes for good whatever is left in the bin, e.g. after the app was closed in the middle of deleting. Blocking. */
    fun emptyBin(ctx: Context) {
        if (Store.exists(bin())) erase(ctx, bin())
    }

    private fun erase(ctx: Context, f: File) {
        val books = booksIn(f)
        if (!Store.delete(f)) return
        remember(ctx, f.path, null)
        for ((path, modified) in books) coverFile(ctx, path, modified).delete()
    }

    // ---- backup ----

    /** Where the backup is kept: hidden in the library folder, so that it moves with the books. */
    private fun backupFile() = File(root(), ".backup.json")

    /** What the backup file looked like when this app last wrote or read it. */
    private const val BACKUP_SIG = "backup"

    /** Whether [adoptBackup] has looked at the backup file since the app was started. */
    private var adopted = false

    /** What was last written to the backup file, to leave it alone while nothing changes. */
    private var written: String? = null

    /**
     * Takes over the backup file if it is not this app's own writing: one that came with a
     * library folder copied from another phone, or that was here before the app was installed.
     * Until this has looked, nothing is written over the file. True if something was taken over.
     */
    @Synchronized
    fun adoptBackup(ctx: Context): Boolean {
        if (adopted || !hasAccess()) return false
        adopted = true
        val file = backupFile()
        if (!file.isDoc || sig(file) == libraryPrefs(ctx).getString(BACKUP_SIG, null)) return false
        val taken = restoreBackup(ctx)
        libraryPrefs(ctx).edit { putString(BACKUP_SIG, sig(file)) }
        return taken
    }

    /** Brings the backup file up to date in the background; called whenever the app is left. */
    fun backUp(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { saveBackup(app) }
    }

    /**
     * Writes reading positions, favorites, the books on the widget, finished books, the order of
     * the shelves, the names, authors and covers given to books and the reader settings to [backupFile]; the covers themselves are kept in the library folder. Books are
     * named by where they are in the library, so the backup also fits the same library on
     * another phone. Blocking.
     */
    @Synchronized
    private fun saveBackup(ctx: Context) {
        if (!hasAccess()) return
        adoptBackup(ctx)
        try {
            val text = backupText(ctx)
            if (text == written) return
            Store.mkdirs(root())
            Store.writeText(backupFile(), text)
            written = text
            libraryPrefs(ctx).edit { putString(BACKUP_SIG, sig(backupFile())) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot save the backup", e)
        }
    }

    private fun backupText(ctx: Context): String {
        val base = root().path
        fun inLibrary(path: String) = path.startsWith(base + File.separator) || path == base
        val positions = JSONObject()
        for ((key, value) in statePrefs(ctx).all) if (value is String && inLibrary(key)) positions.put(key.removePrefix(base), value)
        fun set(key: String) = JSONArray(
            (statePrefs(ctx).getStringSet(key, null) ?: emptySet()).filter(::inLibrary).map { it.removePrefix(base) },
        )
        val byPath = BY_PATH.mapValues { JSONObject() }
        for ((key, value) in libraryPrefs(ctx).all) {
            if (value !is String) continue
            for ((kind, saved) in byPath) if (key.startsWith(kind)) saved.put(key.removePrefix(kind).removePrefix(base), value)
        }
        val settings = JSONObject()
        for ((key, value) in ctx.applicationContext.getSharedPreferences("reader", Context.MODE_PRIVATE).all) {
            if (value is Int || value is Boolean) settings.put(key, value)
        }
        val o = JSONObject().put("version", 1).put("positions", positions).put("favorites", set(FAVORITES))
        for ((kind, saved) in byPath) o.put(BY_PATH.getValue(kind), saved)
        o.put("sort", sort(ctx).ordinal).put("settings", settings)
        if (libraryPrefs(ctx).contains(DESCENDING)) o.put("descending", descending(ctx))
        // Left out until the reader has chosen the books for the widget, which shows the favorites till then.
        if (statePrefs(ctx).contains(WIDGET)) {
            // In the order they stand in on the widget.
            o.put("widget", JSONArray(widgetList(ctx).filter(::inLibrary).map { it.removePrefix(base) }))
        }
        return o.toString(1)
    }

    /** Takes over what [saveBackup] wrote, in place of what is remembered now. */
    private fun restoreBackup(ctx: Context): Boolean = try {
        val o = JSONObject(Store.readText(backupFile()))
        val base = root().path
        fun paths(key: String) = o.optJSONArray(key)?.strings().orEmpty().mapTo(HashSet()) { base + it }
        statePrefs(ctx).edit {
            o.optJSONObject("positions")?.let { for (key in it.keys()) putString(base + key, it.getString(key)) }
            putStringSet(FAVORITES, paths("favorites"))
            // Older backups name the finished books; adoptFinished puts them at the end of their books.
            if (o.has("finished")) putStringSet(FINISHED, paths("finished"))
            if (o.has("widget")) {
                // The backup lists them in the order they stand in on the widget.
                val listed = o.getJSONArray("widget").strings().map { base + it }
                putStringSet(WIDGET, listed.toHashSet()).putString(WIDGET_ORDER, JSONArray(listed).toString())
            } else {
                remove(WIDGET).remove(WIDGET_ORDER)
            }
        }
        // What was read of the positions before is out of date now.
        forgetStates()
        adoptFinished(ctx)
        libraryPrefs(ctx).edit {
            for ((kind, name) in BY_PATH) o.optJSONObject(name)?.let { for (key in it.keys()) putString(kind + base + key, it.getString(key)) }
            putInt(SORT, o.optInt("sort"))
            if (o.has("descending")) putBoolean(DESCENDING, o.optBoolean("descending")) else remove(DESCENDING)
        }
        ctx.applicationContext.getSharedPreferences("reader", Context.MODE_PRIVATE).edit {
            o.optJSONObject("settings")?.let {
                for (key in it.keys()) when (val value = it.get(key)) {
                    is Boolean -> putBoolean(key, value)
                    // Saved by an older version, the warmth is on the scale of then.
                    is Int -> if (key == ReaderSettings.OLD_WARMTH) putInt(ReaderSettings.WARMTH, ReaderSettings.warmthOf(value)) else putInt(key, value)
                }
            }
        }
        stamp++
        FavoritesWidget.refresh(ctx)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot restore the backup", e)
        false
    }

    /** The books that are [f] or inside it, each with its date: what their covers are filed under. */
    private fun booksIn(f: File): Map<String, Long> =
        (if (f.isFolder) Store.walk(f).filter(::isBook) else sequenceOf(f)).associate { it.path to Store.lastModified(it) }

    /** Takes [f] to [target], which must be free, taking along what is remembered about it. */
    private fun relocate(ctx: Context, f: File, target: File): Boolean {
        // Storage that does not tell capitals apart says that a name which only changes them is
        // taken, by the very thing being renamed; the listing has the name as it is really written.
        val recapitalized = target.path.equals(f.path, ignoreCase = true) &&
            target.parentFile?.let(Store::list)?.any { it.name == target.name } != true
        if (Store.exists(target) && !recapitalized) return false
        val old = f.path
        // Note the covers before the paths change.
        val books = booksIn(f)
        // It either changes its name or its shelf, never both.
        val done = when {
            recapitalized -> renameViaTemp(f, target)
            target.parentFile == f.parentFile -> Store.rename(f, target.name)
            target.name == f.name -> Store.move(f, target.parentFile ?: return false)
            else -> false
        }
        if (!done) return false
        remember(ctx, old, target.path)
        for ((path, modified) in books) {
            coverFile(ctx, path, modified).renameTo(coverFile(ctx, target.path + path.removePrefix(old), modified))
        }
        // The shelves it left, and those it came to.
        touched(f)
        touched(target)
        FavoritesWidget.refresh(ctx)
        return true
    }

    /**
     * Gives [f] the name [target], which only differs from its own by capitals. Storage that does
     * not tell them apart takes a rename straight to it for done and leaves the name as it was,
     * so it is passed through a name that is nothing like either.
     */
    private fun renameViaTemp(f: File, target: File): Boolean {
        val temp = File(f.parentFile ?: return false, ".renaming-${System.nanoTime()}")
        if (!Store.rename(f, temp.name)) return false
        if (Store.rename(temp, target.name)) return true
        Store.rename(temp, f.name)
        return false
    }

    /**
     * Everything remembered under the path [old] or a path inside it (reading positions, details,
     * favorites, the books on the widget, finished books, orders, names, authors and covers) is
     * remembered under [new] instead, or forgotten if that is null: a cover given to a book
     * that is gone for good goes with it.
     */
    private fun remember(ctx: Context, old: String, new: String?) {
        fun within(path: String) = path == old || path.startsWith(old + File.separator)
        fun moved(path: String) = new + path.removePrefix(old)
        synchronized(statesLock) {
            for (prefs in arrayOf(statePrefs(ctx), metaPrefs(ctx))) prefs.edit {
                for ((key, value) in prefs.all) if (value is String && within(key)) {
                    remove(key)
                    if (new != null) putString(moved(key), value)
                }
            }
            forgetStates()
        }
        metas.keys.removeAll(::within)
        for (key in arrayOf(FAVORITES, FINISHED, WIDGET)) {
            val paths = statePrefs(ctx).getStringSet(key, null) ?: continue
            if (paths.none(::within)) continue
            val kept = HashSet<String>()
            for (path in paths) if (!within(path)) kept.add(path) else if (new != null) kept.add(moved(path))
            statePrefs(ctx).edit { putStringSet(key, kept) }
        }
        val order = widgetOrder(ctx)
        if (order.any(::within)) statePrefs(ctx).edit {
            val kept = order.mapNotNull { if (!within(it)) it else if (new != null) moved(it) else null }
            putString(WIDGET_ORDER, JSONArray(kept).toString())
        }
        val library = libraryPrefs(ctx)
        library.edit {
            for ((key, value) in library.all) for (kind in BY_PATH.keys) {
                val path = key.removePrefix(kind)
                if (key.startsWith(kind) && value is String && within(path)) {
                    remove(key)
                    if (new != null) putString(kind + moved(path), value)
                    else if (kind == COVER) Store.delete(File(coversDir(), value))
                }
            }
        }
    }

    /**
     * The books on the shelf [dir] and on every shelf inside it whose title, author or file name
     * contains [query]; with [starred], the favorites among all the books of the library
     * instead, and all of them if nothing is asked for. Blocking: reads the details of books not
     * seen before. Unless [fresh], the folders are taken as they were last listed, see [books].
     */
    fun search(ctx: Context, dir: File, query: String, starred: Boolean, fresh: Boolean = true): List<File> {
        val q = query.trim().lowercase()
        if (q.isEmpty() && !starred) return emptyList()
        val books =
            if (starred) favorites(ctx).asSequence()
            else books(dir, fresh)
        return books.filter { f ->
            val m = meta(ctx, f)
            q.isEmpty() || q in m.title.lowercase() || q in m.author.lowercase() || q in f.name.lowercase()
        }.toList()
    }

    /**
     * Every book on the shelf [dir] and the shelves inside it: by default, every book in the
     * library. Unless [fresh], the folders listed before are taken as they were found then, which
     * is quick; see Store.walk.
     */
    fun books(dir: File = root(), fresh: Boolean = true): Sequence<File> =
        Store.walk(dir, fresh) { !it.name.startsWith(".") }.filter(::isBook)

    /** Sub-folders first, then books, both alphabetical. */
    fun list(dir: File): List<File> = shown(Store.list(dir))

    /** Of the things in a folder, the shelves and books, without what is hidden; sub-folders first, then books, both alphabetical. */
    private fun shown(kids: List<File>): List<File> =
        kids.filter { !it.name.startsWith(".") && (it.isFolder || isBook(it)) }
            .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))

    // ---- metadata & covers ----

    private fun metaPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("meta", Context.MODE_PRIVATE)
    private fun statePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("state", Context.MODE_PRIVATE)

    private fun sig(f: File) = "${Store.lastModified(f)}_${Store.length(f)}"

    fun coverFile(ctx: Context, f: File): File = coverFile(ctx, f.path, Store.lastModified(f))

    private fun coverFile(ctx: Context, path: String, modified: Long): File =
        File(File(ctx.filesDir, "covers"), "${Integer.toHexString(path.hashCode())}_${modified}_${COVERS_MADE}_${coverWidth(ctx)}.jpg")

    /**
     * What the books say about themselves, as [meta] read it, by path: with the size and date of
     * the file it was read from, which must still be the same for it to hold. Kept so that the
     * shelf need not read it from the preferences again for every book it shows.
     */
    private val metas = ConcurrentHashMap<String, Pair<String, BookMeta>>()

    fun cachedMeta(ctx: Context, f: File): BookMeta? {
        val sig = sig(f)
        metas[f.path]?.let { (known, m) -> if (known == sig) return named(ctx, f, m) }
        val json = metaPrefs(ctx).getString(f.path, null) ?: return null
        return try {
            val o = JSONObject(json)
            if (o.optString("sig") != sig || o.optInt("v") != META_VERSION) null
            else {
                val m = BookMeta(o.optString("title"), o.optString("author"), o.optInt("pages"), o.optString("published"))
                metas[f.path] = sig to m
                named(ctx, f, m)
            }
        } catch (e: Exception) {
            null
        }
    }

    private val DATE = Regex("(?<!\\d)(\\d{4})(?:-?(\\d{2})(?:-?(\\d{2}))?)?")

    /** The date in [raw], as a book file gives it, the way [BookMeta.published] has it; empty if there is none. */
    private fun date(raw: String): String {
        val (year, month, day) = DATE.find(raw)?.destructured ?: return ""
        return when {
            year.toInt() !in 1000..2999 -> ""
            month.toIntOrNull() !in 1..12 -> year
            day.toIntOrNull() !in 1..31 -> "$year-$month"
            else -> "$year-$month-$day"
        }
    }

    /**
     * Locks under which [meta] reads books, each for some of them: a book asked for by two
     * threads at once, e.g. as it is loaded ahead of coming into view and then comes into view,
     * is read once, and its cover written once rather than over itself.
     */
    private val reading = Array(16) { Any() }

    /** Reads title, author, date, page count and cover from the file. Blocking; results are cached. */
    fun meta(ctx: Context, f: File): BookMeta {
        // Gone, e.g. deleted while its cover was being asked for: nothing is made or remembered of it.
        if (!f.isDoc) return BookMeta(f.nameWithoutExtension, "", 0)
        val cover = coverFile(ctx, f)
        cachedMeta(ctx, f)?.let { if (cover.exists()) return it }
        return synchronized(reading[f.path.hashCode() and reading.size - 1]) {
            cachedMeta(ctx, f)?.takeIf { cover.exists() } ?: readMeta(ctx, f, cover)
        }
    }

    /** Reads what [meta] asks for from [f], writing its cover to [cover]. */
    private fun readMeta(ctx: Context, f: File, cover: File): BookMeta {
        var title = f.nameWithoutExtension
        var author = ""
        var published = ""
        var pages = 0
        var bmp: Bitmap? = null
        try {
            if (isFlowing(f)) {
                FlowBook.open(f).use { b ->
                    if (b.title.isNotBlank()) title = b.title
                    author = b.author
                    published = date(b.published)
                    pages = (b.weights.sum() / b.pageWeight).toInt().coerceAtLeast(1)
                    bmp = b.coverBitmap(coverWidth(ctx))
                }
            } else {
                if (f.extension.equals("pdf", true)) PdfInfo.read(f)?.let { (named, by, made) ->
                    if (named.isNotEmpty()) title = named
                    author = by
                    published = date(made)
                }
                PageSource.open(f).use { p ->
                    pages = p.count
                    bmp = p.render(0, coverWidth(ctx), 0)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Cannot read ${f.path}", e)
        }

        val meta = BookMeta(title, author, pages, published)
        try {
            cover.parentFile?.mkdirs()
            // Written in full before it is put in its place: a cover half written as the app was
            // ended would otherwise stand for the book until its file changed.
            val part = File(cover.path + ".part")
            FileOutputStream(part).use { (bmp ?: placeholder(title, author, coverWidth(ctx))).compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!part.renameTo(cover)) part.delete()
            // A book read before has changed since: the cover made of it then is not shown again.
            if (metaPrefs(ctx).contains(f.path)) {
                val same = cover.name.substringBefore('_') + "_"
                cover.parentFile?.listFiles { _, name -> name.startsWith(same) && name != cover.name }?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot save cover for ${f.path}", e)
        }
        val o = JSONObject().put("sig", sig(f)).put("v", META_VERSION).put("title", title).put("author", author)
            .put("pages", pages).put("published", published)
        metaPrefs(ctx).edit { putString(f.path, o.toString()) }
        metas[f.path] = sig(f) to meta
        return named(ctx, f, meta)
    }

    /** [m] under the name and the author the reader gave the book with [rename] and [setAuthor], if any. */
    private fun named(ctx: Context, f: File, m: BookMeta): BookMeta {
        val prefs = libraryPrefs(ctx)
        return m.copy(
            title = prefs.getString(TITLE + f.path, null) ?: m.title,
            author = prefs.getString(AUTHOR + f.path, null) ?: m.author,
        )
    }

    /** Says that the book [f] is by [author], whatever is written in the file. */
    fun setAuthor(ctx: Context, f: File, author: String) {
        libraryPrefs(ctx).edit { putString(AUTHOR + f.path, author.trim()) }
        // A cover made for the book has the author on it.
        bitmaps.remove(coverKey(f))
        touched(f)
    }

    private fun coverKey(f: File) = "${f.path}:${Store.lastModified(f)}"

    fun cachedCover(f: File): Bitmap? = bitmaps.get(coverKey(f))

    /**
     * Decodes the cover the book is shown with: the one it was given, or else the one written by
     * [meta]. It is kept in the app's own memory rather than by the drawing hardware alone: some
     * phones leave a picture kept there out of a snapshot of the shelf (View.snapshot), which the
     * shelves zoom and the books open from. Blocking.
     */
    fun cover(ctx: Context, f: File): Bitmap? {
        cachedCover(f)?.let { return it }
        val bmp = decodeCover(ctx, f) ?: return null
        // Handed to the drawing hardware now, rather than as it first comes into view.
        bmp.prepareToDraw()
        bitmaps.put(coverKey(f), bmp)
        return bmp
    }

    /**
     * The cover of the book [f] to draw into a picture made in memory, such as a miniature of a
     * shelf, which a cover kept by the drawing hardware ([cover]) cannot be: at half its size,
     * which is more than the miniatures need. Blocking.
     */
    private fun coverToDraw(ctx: Context, f: File): Bitmap? =
        cachedCover(f)?.takeIf { Build.VERSION.SDK_INT < 26 || it.config != Bitmap.Config.HARDWARE }
            ?: decodeCover(ctx, f, BitmapFactory.Options().apply { inSampleSize = 2 })

    /**
     * The covers the reader gave books in place of their first pages. They are kept hidden in the
     * library folder, so that they go along with it, and its backup, to another phone.
     */
    private fun coversDir() = File(root(), ".covers")

    /**
     * The cover of [f] as large as the cover that ends it, [width] wide, read from the book itself as
     * the reader reads it (see [endCover]): for a book that only grows out of the shelf to show, sharper
     * than the one the shelf keeps. Blocking.
     */
    fun bigCover(ctx: Context, f: File, width: Int): Bitmap = endCover(ctx, f, width) {
        if (isFlowing(f)) FlowBook.open(f).use { it.coverBitmap(width) } else PageSource.open(f).use { it.render(0, width, 0) }
    }

    /**
     * The picture that ends the book [f], the cover as if it had been closed, [width] wide: the one
     * the reader gave it, or else [own], the cover the book itself has, or else one made for it.
     * Blocking.
     */
    fun endCover(ctx: Context, f: File, width: Int, own: () -> Bitmap?): Bitmap {
        try {
            customCover(ctx, f)?.let { c -> Store.inputStream(c).use { BitmapFactory.decodeStream(it) } }?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read the cover given to ${f.path}", e)
        }
        if (coverMode(ctx, f) == CoverMode.MADE) return madeCover(ctx, f, width)
        try {
            own()?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read the cover of ${f.path}", e)
        }
        val m = meta(ctx, f)
        return placeholder(m.title, m.author, width)
    }

    /** The cover the reader gave the book [f]; null while it has its own. */
    private fun customCover(ctx: Context, f: File): File? =
        libraryPrefs(ctx).getString(COVER + f.path, null)?.let { File(coversDir(), it) }?.takeIf { it.isDoc }

    fun hasCustomCover(ctx: Context, f: File) = customCover(ctx, f) != null

    /**
     * What the cover is of a book that was given no picture: the one it has itself, its first page
     * (which the book then reads on from, as it is the cover), or one made for it.
     */
    enum class CoverMode { OWN, FIRST_PAGE, MADE }

    fun coverMode(ctx: Context, f: File): CoverMode = when (libraryPrefs(ctx).getString(MODE + f.path, null)) {
        "first" -> CoverMode.FIRST_PAGE
        "made" -> CoverMode.MADE
        else -> CoverMode.OWN
    }

    /**
     * The leathers a cover made for a book can be of, by the name they go by and their colour. A book
     * that is not told which gets one of the first five by its title.
     */
    val TONES = listOf(
        R.string.tone_red to 0xFF5B1A1A.toInt(), R.string.tone_blue to 0xFF1F3A5F.toInt(), R.string.tone_green to 0xFF234D32.toInt(),
        R.string.tone_purple to 0xFF4A2C5A.toInt(), R.string.tone_brown to 0xFF5A3A1A.toInt(),
        R.string.tone_black to 0xFF1E1E22.toInt(), R.string.tone_teal to 0xFF1F4F52.toInt(), R.string.tone_grey to 0xFF4A4A50.toInt(),
    )
    private const val AUTO_TONES = 5

    /** The leather of the cover made for [f], an index of [TONES]: the one the reader chose, or else the one its title gives it. */
    fun tone(ctx: Context, f: File): Int =
        libraryPrefs(ctx).getString(TONE + f.path, null)?.toIntOrNull()?.takeIf { it in TONES.indices }
            ?: (((cachedMeta(ctx, f)?.title ?: f.nameWithoutExtension).hashCode() and 0x7fffffff) % AUTO_TONES)

    /**
     * Says what the cover of [f] is, see [CoverMode], and for one made for it, of which leather
     * ([tone], an index of [TONES]). A picture the reader gave it is given up. Blocking.
     */
    fun setCoverMode(ctx: Context, f: File, mode: CoverMode, tone: Int? = null) {
        libraryPrefs(ctx).edit {
            when (mode) {
                CoverMode.OWN -> remove(MODE + f.path)
                CoverMode.FIRST_PAGE -> putString(MODE + f.path, "first")
                CoverMode.MADE -> putString(MODE + f.path, "made")
            }
            if (mode == CoverMode.MADE && tone != null) putString(TONE + f.path, tone.toString())
            else if (mode != CoverMode.MADE) remove(TONE + f.path)
        }
        setCover(ctx, f, null)
    }

    /** The cover made for [f], [width] wide, of the leather it has. Blocking. */
    private fun madeCover(ctx: Context, f: File, width: Int = coverWidth(ctx)): Bitmap {
        val m = cachedMeta(ctx, f) ?: meta(ctx, f)
        return placeholder(m.title, m.author, width, tone(ctx, f))
    }

    /** Decodes the picture the book [f] is shown with. Unless it was given one, it is written by [meta]. Blocking. */
    fun decodeCover(ctx: Context, f: File, opts: BitmapFactory.Options? = null): Bitmap? = try {
        customCover(ctx, f)?.let { c -> Store.inputStream(c).use { BitmapFactory.decodeStream(it, null, opts) } }
            ?: if (coverMode(ctx, f) == CoverMode.MADE) madeCover(ctx, f) else BitmapFactory.decodeFile(coverFile(ctx, f).path, opts)
    } catch (e: Exception) {
        null
    }

    /**
     * Gives the book [f] the picture [uri] as its cover, or gives it back its own, the first
     * page, if that is null. False if the picture cannot be read. Blocking.
     */
    fun setCover(ctx: Context, f: File, uri: Uri?): Boolean {
        val old = customCover(ctx, f)
        if (uri == null) {
            libraryPrefs(ctx).edit { remove(COVER + f.path) }
        } else {
            val bmp = readPicture(ctx, uri, coverWidth(ctx) * 2) ?: return false
            val name = "${UUID.randomUUID()}.jpg"
            try {
                Store.mkdirs(coversDir())
                Store.outputStream(File(coversDir(), name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot save the cover for ${f.path}", e)
                return false
            }
            libraryPrefs(ctx).edit { putString(COVER + f.path, name) }
        }
        old?.let(Store::delete)
        bitmaps.remove(coverKey(f))
        touched(f)
        FavoritesWidget.refresh(ctx)
        return true
    }

    /** The picture [uri], upright and not much wider than [width]; null if it cannot be read. Blocking. */
    private fun readPicture(ctx: Context, uri: Uri, width: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            // A photo taken with the phone on its side is turned upright by the decoder.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                if (w > width) decoder.setTargetSize(width, (info.size.height.toLong() * width / w).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the picture $uri", e)
        null
    }

    /**
     * Where the menu of the reader stood over the cover that ends the book [f], the last time it was
     * pictured there (BookOpening.EndMenu, without its pictures); null if it never was. Kept only on
     * this phone, as it is for the size of its screen.
     */
    fun endMenu(ctx: Context, f: File): BookOpening.EndMenu? = try {
        endMenuPrefs(ctx).getString(f.path, null)?.split(',')?.let { v ->
            BookOpening.EndMenu(
                v[0].toInt(), v[1].toInt(), v[2].toInt(), RectF(v[3].toFloat(), v[4].toFloat(), v[5].toFloat(), v[6].toFloat()),
                v[7].toFloat(), v[8].toFloat(),
            )
        }
    } catch (e: Exception) {
        null
    }

    fun setEndMenu(ctx: Context, f: File, m: BookOpening.EndMenu) {
        val t = m.title
        endMenuPrefs(ctx).edit {
            putString(f.path, listOf(m.screenW, m.screenH, m.height, t.left, t.top, t.right, t.bottom, m.titleText, m.titleInset).joinToString(","))
        }
    }

    private fun endMenuPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("endmenu", Context.MODE_PRIVATE)

    /**
     * What is chosen on the shelves, to be carried or marked together. The miniature of a shelf
     * that has any of it shows it standing out from the rest, as the shelf itself does.
     */
    var chosen: Set<File> = emptySet()

    /** Those of [things] that stand somewhere on the shelf [dir], or on the shelves on it. */
    private fun within(dir: File, things: Set<File>) = things.filter { it.path.startsWith(dir.path + File.separator) }

    // A miniature drawn while things are carried lacks them, and is a picture of its own. So is
    // one that shows what is chosen.
    private fun folderKey(dir: File, spec: ShelfSpec) =
        "dir:${dir.path}:${Store.lastModified(dir)}:$stamp:${stamps[dir.path] ?: 0}:${spec.width}:${spec.columns}" +
            carried.let { if (it.isEmpty()) "" else ":${it.hashCode()}" } +
            within(dir, chosen).let { if (it.isEmpty()) "" else ":c${it.hashCode()}" }

    fun cachedFolderPreview(dir: File, spec: ShelfSpec): Bitmap? = bitmaps.get(folderKey(dir, spec))

    /** A miniature of the shelf [dir] that shows its books but not yet what stands on the shelves in it, if one was drawn. */
    fun cachedFolderDraft(dir: File, spec: ShelfSpec): Bitmap? = bitmaps.get(folderKey(dir, spec) + BOOKS_ONLY)

    /**
     * The case of [dir] with nothing in it, which is drawn in a moment, for its miniature to show
     * the wood at once while the books are being drawn. Null while the grain of the wood is not
     * ready: it is decoded in the background as the app starts.
     */
    fun cachedEmptyCase(ctx: Context, dir: File, spec: ShelfSpec): Bitmap? =
        if (spec.width <= 0 || !ShelfDecoration.isReady()) null else folderPreview(ctx, dir, spec, 0, DETAIL_CASE)

    private const val BOOKS_ONLY = ":books"

    // ---- miniatures kept between two uses of the app ----

    private fun savedFile(ctx: Context, dir: File, spec: ShelfSpec): File {
        val name = "${dir.path}|${spec.width}|${spec.columns}"
        val id = MessageDigest.getInstance("SHA-1").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.cacheDir, "minis"), "$id.webp")
    }

    /**
     * The miniature of [dir] as it was drawn when the app was last used, if the phone kept it. It
     * may be out of date, so it is only shown until the miniature is drawn anew. Blocking.
     */
    fun savedFolderPreview(ctx: Context, dir: File, spec: ShelfSpec): Bitmap? = try {
        savedFile(ctx, dir, spec).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
    } catch (e: Exception) {
        null
    }

    /**
     * Keeps the miniature [bmp] of [dir] for the next use of the app, behind all else that is asked
     * of the shelves. What is carried or chosen is not what the shelf looks like the next time.
     */
    fun keepFolderPreview(ctx: Context, dir: File, spec: ShelfSpec, bmp: Bitmap) = ahead {
        if (carried.isEmpty() && within(dir, chosen).isEmpty()) saveFolderPreview(ctx, dir, spec, bmp)
    }

    private fun saveFolderPreview(ctx: Context, dir: File, spec: ShelfSpec, bmp: Bitmap) {
        try {
            val file = savedFile(ctx, dir, spec)
            file.parentFile?.mkdirs()
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            FileOutputStream(file).use { bmp.compress(format, 85, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot keep the miniature of $dir", e)
        }
    }

    /** How much a miniature shows: only the case, its books, or everything that stands in it. */
    const val DETAIL_CASE = 0
    const val DETAIL_BOOKS = 1
    const val DETAIL_FULL = 2

    /**
     * A picture of the case that [dir] opens into, drawn with the same geometry as the real one
     * ([spec]) and scaled down, so that opening the folder is a pure enlargement: its first
     * [ShelfSpec.miniRows] shelves, from under the top rail to the foot of the books on the last
     * of them, whose plank is the one the miniature stands on.
     * Blocking.
     */
    fun folderPreview(ctx: Context, dir: File, spec: ShelfSpec, depth: Int = 0, wanted: Int = DETAIL_FULL): Bitmap {
        // A shelf with no shelves in it has nothing more to show than its books.
        val detail = if (wanted == DETAIL_BOOKS && depth == 0 && shelf(ctx, dir).none { it.isFolder }) DETAIL_FULL else wanted
        if (depth == 0 && detail == DETAIL_FULL) cachedFolderPreview(dir, spec)?.let { return it }
        val key = when (detail) {
            DETAIL_CASE -> "case:${spec.width}:${spec.columns}:${ShelfDecoration.isReady()}"
            DETAIL_BOOKS -> folderKey(dir, spec) + BOOKS_ONLY
            else -> folderKey(dir, spec)
        }
        if (detail != DETAIL_FULL) bitmaps.get(key)?.let { return it }
        val held = carried
        val picked = within(dir, chosen).toSet()
        ShelfDecoration.prepare(ctx)
        val w = spec.width.toFloat()
        val h = spec.miniH.toFloat()
        val bmp = createBitmap(COVER_W, (COVER_W * h / w).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Draw in real case pixels.
        c.scale(COVER_W / w, COVER_W / w)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.alpha = 255
        paint.shader = LinearGradient(
            0f, 0f, w, 0f, intArrayOf(SHELF_BACK, SHELF_BACK_LIGHT, SHELF_BACK), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, paint)
        ShelfDecoration.drawWallGrain(c, w, h, paint)
        val rowH = spec.rowH
        for (row in 1..spec.miniRows) ShelfDecoration.drawPlank(c, w, (row * rowH).toFloat(), spec.density, paint, row - 1)
        ShelfDecoration.drawShade(c, w, spec.density, paint)
        paint.shader = null

        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3B2A05.toInt()
            textSize = 11 * spec.density
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        // Cases nested deeper than this are too small to show what stands in them.
        val items = if (depth > 1 || detail == DETAIL_CASE) emptyList() else shelf(ctx, dir).filter { it !in held }.take(spec.columns * spec.miniRows)
        items.forEachIndexed { i, f ->
            val left = spec.side + (i % spec.columns) * spec.itemW + spec.gap.toFloat()
            val top = (i / spec.columns) * rowH + spec.miniRise.toFloat()
            val cover = RectF(left, top, left + spec.coverW, top + spec.coverH)
            // Beside what is chosen, the rest is faint. A case that holds some of it sees to that itself.
            val faint = picked.isNotEmpty() && f !in picked && picked.none { it.path.startsWith(f.path + File.separator) }
            val layer = if (faint) c.saveLayerAlpha(0f, 0f, w, h, FAINT_ALPHA) else -1
            val name: String
            if (f.isFolder) {
                name = f.name
                // A case is a little wider than a book and stands taller, up to the shelf above.
                val inner = folderPreview(ctx, f, spec, depth + 1, if (detail == DETAIL_FULL) DETAIL_FULL else DETAIL_CASE)
                val half = spec.miniW / 2f
                c.drawBitmap(
                    inner, null,
                    RectF(cover.centerX() - half, cover.top - spec.miniRise, cover.centerX() + half, cover.bottom), paint,
                )
            } else {
                name = meta(ctx, f).title
                paint.color = ShelfArt.LEATHER
                c.drawRect(cover, paint)
                coverToDraw(ctx, f)?.let { drawCropped(c, it, cover, paint) }
                ShelfArt.drawBinding(c, cover, paint)
            }

            val plaque = Rect(
                left.toInt(), (cover.bottom + spec.px(6)).toInt(),
                cover.right.toInt(), (cover.bottom + spec.px(6) + spec.px(32)).toInt(),
            )
            PlaqueDrawable(spec.density).apply {
                favorite = !f.isFolder && isFavorite(ctx, f)
                finished = !f.isFolder && isFinished(ctx, f)
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
            c.withTranslation((plaque.left + inset).toFloat(), plaque.top + (faceHeight - text.height) / 2f) { text.draw(c) }
            if (layer >= 0) c.restoreToCount(layer)
        }

        ShelfDecoration.drawCase(c, w, h, spec, paint)
        paint.shader = null
        if (depth == 0 || detail == DETAIL_CASE) {
            bmp.prepareToDraw()
            bitmaps.put(key, bmp)
        }
        return bmp
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

    /**
     * The cover made for a book that has none: dark leather with a gold frame round its edge, its
     * [title] and its [author]. It has the shape of a book on the shelf (ShelfSpec.BOOK), which
     * shows all of it, frame and all. It is drawn for a picture [width] wide, as large as the
     * screen when it ends the book.
     */
    private fun placeholder(title: String, author: String, width: Int = COVER_W, tone: Int? = null): Bitmap {
        val w = COVER_W
        val h = (w * ShelfSpec.BOOK).toInt()
        val bmp = createBitmap(width, (width * ShelfSpec.BOOK).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Drawn as for the size of a cover on the shelf, and enlarged to the one asked for.
        c.scale(width / w.toFloat(), width / w.toFloat())
        val gold = 0xFFD4AF37.toInt()
        c.drawColor(TONES[tone ?: ((title.hashCode() and 0x7fffffff) % AUTO_TONES)].second)
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold
            style = Paint.Style.STROKE
            strokeWidth = FRAME_W
        }
        c.drawRect(FRAME_SPINE, FRAME_IN, w - FRAME_IN, h - FRAME_IN, frame)
        // What is written stands in the middle of the frame, as far inside it on either side, the crease left out.
        val textLeft = FRAME_SPINE + TEXT_IN

        fun text(s: String, size: Float, style: Int, lines: Int): StaticLayout {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gold
                textSize = size
                typeface = Typeface.create(Typeface.SERIF, style)
            }
            return StaticLayout.Builder.obtain(s, 0, s.length, tp, (w - FRAME_IN - TEXT_IN - textLeft).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }

        val t = text(title, 36f, Typeface.BOLD, 6)
        c.withTranslation(textLeft, (h - t.height) / 2f - 24f) { t.draw(c) }
        if (author.isNotBlank()) {
            val a = text(author, 24f, Typeface.ITALIC, 2)
            c.withTranslation(textLeft, h - 44f - a.height) { a.draw(c) }
        }
        return bmp
    }

    // ---- reading state & favorites ----

    /**
     * Every reading position remembered, by the path of its book: read from the preferences once
     * and kept as they are written, so that the shelf need not read them again for every book it
     * shows. Null until it is first asked for, and again whenever paths change all at once.
     */
    private var states: HashMap<String, BookState>? = null
    private val statesLock = Any()

    private fun parseState(json: String): BookState? = try {
        val o = JSONObject(json)
        BookState(
            o.optInt("chapter"),
            o.optDouble("fraction", 0.0).toFloat(),
            o.optDouble("percent", 0.0).toFloat(),
            o.optLong("lastRead"),
            o.optInt("v", 1),
        )
    } catch (e: Exception) {
        null
    }

    /** Runs [use] on all the reading positions, under the lock that keeps them. */
    private fun <T> withStates(ctx: Context, use: (Map<String, BookState>) -> T): T = synchronized(statesLock) {
        val all = states ?: HashMap<String, BookState>().also { map ->
            // The preferences also hold the lists of favorites and the like, which are not positions.
            for ((key, value) in statePrefs(ctx).all) if (value is String) parseState(value)?.let { map[key] = it }
            states = map
        }
        use(all)
    }

    /** Forgets the reading positions read, after paths have changed; they are read again when next asked for. */
    private fun forgetStates() = synchronized(statesLock) { states = null }

    /** The reading position in [f]; a copy, which may be changed without changing what is remembered. */
    fun state(ctx: Context, f: File): BookState = withStates(ctx) { it[f.path]?.copy() } ?: BookState()

    fun saveState(ctx: Context, f: File, s: BookState) {
        // A place is always told as the spine is counted now.
        val o = JSONObject().put("chapter", s.chapter).put("fraction", s.fraction.toDouble())
            .put("percent", s.percent.toDouble()).put("lastRead", s.lastRead).put("v", BookState.VERSION)
        val was = isFinished(ctx, f)
        synchronized(statesLock) {
            statePrefs(ctx).edit { putString(f.path, o.toString()) }
            states?.put(f.path, s.copy(version = BookState.VERSION))
        }
        // Read to its end, or taken back from it, the plaque of the book is of another metal on the
        // miniatures of the shelves it stands on, which are drawn anew for that.
        if (was != (s.percent >= FINISHED_AT)) touched(f)
    }

    /**
     * Whether [f] is a book standing on a shelf of the library, which a hidden folder such as the
     * bin is not.
     */
    private fun shelved(f: File): Boolean {
        val base = root().path + File.separator
        return f.path.startsWith(base) && f.path.removePrefix(base).split(File.separatorChar).none { it.startsWith(".") } && isBook(f)
    }

    /**
     * Of the books remembered under [prefix] (all of them if it is empty) whose place is [wanted],
     * the one for which [of] is the most, of those still on the shelves; null if none is.
     */
    private fun <T : Comparable<T>> mostOf(
        ctx: Context, prefix: String, wanted: (BookState) -> Boolean = { true }, of: (BookState) -> T,
    ): Pair<File, T>? {
        val all = withStates(ctx) { map ->
            map.entries.filter { it.key.startsWith(prefix) && wanted(it.value) }.map { it.key to of(it.value) }
        }
        // Only the books looked at are asked whether they are still there, the likeliest first.
        return all.sortedByDescending { it.second }.asSequence().map { File(it.first) to it.second }.firstOrNull { shelved(it.first) }
    }

    /**
     * The book that was read last, wherever it stands in the library, of those not read to the end:
     * one that is, is not gone on with. Null if there is none. Blocking.
     */
    fun lastRead(ctx: Context): File? =
        mostOf(ctx, "", { it.percent < FINISHED_AT }) { it.lastRead }?.takeIf { it.second > 0 }?.first

    private fun favoritePaths(ctx: Context): Set<String> =
        statePrefs(ctx).getStringSet(FAVORITES, null) ?: emptySet()

    fun isFavorite(ctx: Context, f: File) = f.path in favoritePaths(ctx)

    fun setFavorite(ctx: Context, f: File, on: Boolean) {
        val set = HashSet(favoritePaths(ctx))
        if (on) set.add(f.path) else set.remove(f.path)
        statePrefs(ctx).edit { putStringSet(FAVORITES, set) }
        touched(f)
        FavoritesWidget.refresh(ctx)
    }

    /** A book is finished while it stands at its very end, and no longer once the reader leaves that page. */
    fun isFinished(ctx: Context, f: File) = withStates(ctx) { (it[f.path]?.percent ?: 0f) >= FINISHED_AT }

    /**
     * Marking a book as read puts it on its last page, which is all that finishes it; taking the
     * mark off puts it back at the start, as if never opened.
     */
    fun setFinished(ctx: Context, f: File, on: Boolean) {
        val was = state(ctx, f)
        // A place beyond the end is taken to be the last page by the engines.
        saveState(ctx, f, if (on) BookState(Int.MAX_VALUE, 1f, 1f, was.lastRead) else BookState(lastRead = was.lastRead))
        touched(f)
    }

    /**
     * Books that were marked as finished before that was told by the place in the book, which is
     * now where they are put, and forgotten as a list.
     */
    private fun adoptFinished(ctx: Context) {
        val old = statePrefs(ctx).getStringSet(FINISHED, null) ?: return
        for (path in old) File(path).let { if (!isFinished(ctx, it)) setFinished(ctx, it, true) }
        statePrefs(ctx).edit { remove(FINISHED) }
    }

    /** Takes the books that were marked as finished by an older version of the app into the new way of telling. */
    fun upgrade(ctx: Context) = adoptFinished(ctx)

    /**
     * Starts reading, while the app starts, what the first shelf needs first: the preferences,
     * the reading positions of the books, the library folder, which the documents provider is
     * slow to list the first time, and the grain of the wood. The bookshelf then finds them read,
     * rather than reading them itself on the thread that draws it.
     */
    fun warmUp(ctx: Context) {
        io.execute {
            // Opening the preferences starts reading them from storage, on a thread of their own.
            for (name in arrayOf("library", "state", "meta", "reader", "widget")) ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            upgrade(ctx)
            withStates(ctx) {}
            if (hasAccess()) {
                Store.list(root())
                adoptBackup(ctx)
            }
            ShelfDecoration.prepare(ctx)
        }
    }

    /**
     * Lets go of what is kept in memory that can be read again when it is needed, as the phone
     * asks for its memory back: the covers and miniatures, all of them if it is short of it.
     */
    fun trim(level: Int) {
        @Suppress("DEPRECATION")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> bitmaps.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> bitmaps.trimToSize(bitmaps.maxSize() / 2)
        }
    }

    fun favorites(ctx: Context): List<File> = standing(favoritePaths(ctx)).sortedBy { it.name.lowercase() }

    /** The books under [paths] that are in the library. */
    private fun standing(paths: Collection<String>): List<File> =
        paths.map(::File)
            // A deleted book lies in the bin until it is gone for good, and counts for nothing there.
            .filter { it.isDoc && !it.path.startsWith(bin().path + File.separator) }

    /**
     * The books put on the home-screen widget. Until the reader puts one on it or takes one off,
     * it holds the favorites, as it always did before the two were told apart.
     */
    private fun widgetPaths(ctx: Context): Set<String> =
        statePrefs(ctx).getStringSet(WIDGET, null) ?: favoritePaths(ctx)

    fun isOnWidget(ctx: Context, f: File) = f.path in widgetPaths(ctx)

    /** The order the reader gave the books on the widget. */
    private fun widgetOrder(ctx: Context): List<String> = try {
        statePrefs(ctx).getString(WIDGET_ORDER, null)?.let(::JSONArray)?.strings().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /** The books on the widget in the order they stand in: those never given a place come last, by name. */
    private fun widgetList(ctx: Context): List<String> {
        val on = widgetPaths(ctx)
        val order = widgetOrder(ctx).filter { it in on }
        return order + (on - order.toSet()).sortedBy { File(it).name.lowercase() }
    }

    /** Puts [f] on the widget, behind the books already there, or takes it off. */
    fun setOnWidget(ctx: Context, f: File, on: Boolean) {
        val list = widgetList(ctx) - f.path
        saveWidget(ctx, if (on) list + f.path else list)
    }

    /** Puts the books on the widget in the order of [books], which must be all of them. */
    fun setWidgetOrder(ctx: Context, books: List<File>) = saveWidget(ctx, books.map { it.path })

    private fun saveWidget(ctx: Context, paths: List<String>) {
        statePrefs(ctx).edit { putStringSet(WIDGET, paths.toHashSet()).putString(WIDGET_ORDER, JSONArray(paths).toString()) }
        FavoritesWidget.refresh(ctx)
    }

    fun widgetBooks(ctx: Context): List<File> = standing(widgetList(ctx))

    /** The strings this array holds, in order. */
    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
}
