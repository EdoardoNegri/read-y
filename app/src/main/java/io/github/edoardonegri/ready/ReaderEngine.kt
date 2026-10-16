package io.github.edoardonegri.ready

import android.view.View
import java.io.Closeable
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

interface ReaderHost {
    /**
     * [percent] is progress through the whole book, 0..1: where the page being shown begins, so
     * that the first page is 0 and the last 1, and the seek bar's knob stands on the mark of a
     * chapter when the chapter has just been opened. [pageOfChapter] is the page of the chapter
     * being shown, counted from 1, and how many the chapter has, if it has pages; [pageOfBook] is
     * the same for the whole book, if the engine can tell.
     */
    fun onPosition(
        chapter: Int, fraction: Float, percent: Float,
        pageOfChapter: Pair<Int, Int>? = null, pageOfBook: Pair<Int, Int>? = null,
    )

    /**
     * The engine is about to show nothing for a moment, while another chapter is laid out:
     * [then] is called once the page as it is seen now stands in front of it, until [release].
     */
    fun hold(then: () -> Unit) = then()

    /** What [hold] put in front of the page may go: the engine shows its page again. */
    fun release() {}

    /** The engine shows its first page now, drawn in full in the next frame. */
    fun shown() {}

    /**
     * The engine cannot show the book any more, e.g. as the phone ended the process its pages
     * were drawn in to have its memory back: it is to be closed, and the book opened anew.
     */
    fun reopen() {}
}

interface ReaderEngine {
    val view: View
    fun next()
    fun prev()

    /** Re-reads [ReaderSettings] and lays the book out again, keeping the position. */
    fun applySettings()

    /** Jumps to a position in the whole book, 0..1. */
    fun seekTo(fraction: Float)

    /**
     * The page of its chapter and the page of the book that the place [fraction] of the whole
     * book, 0..1, would be shown on, as [ReaderHost.onPosition] tells them for the place shown:
     * for the seek bar's knob while it is dragged. Either is null if the engine cannot tell.
     */
    fun pagesAt(fraction: Float): Pair<Pair<Int, Int>?, Pair<Int, Int>?> = null to null

    /** The book's table of contents: each line's title and how deep it is nested. Empty if it has none. */
    val contents: List<Pair<String, Int>> get() = emptyList()

    /** Where in the whole book, 0..1, each line of [contents] leads. */
    val contentPositions: List<Float> get() = emptyList()

    /** The line of [contents] that the place being read belongs to, or -1. */
    val currentContent: Int get() = -1

    /** Opens the place the line [index] of [contents] leads to. */
    fun openContent(index: Int) {}

    /** Gives the content a chance to handle a tap (e.g. a link) before it turns the page. */
    fun tap(x: Float, y: Float, done: (handled: Boolean) -> Unit) = done(false)

    /**
     * How much room, in pixels, the engine leaves free at the top and the bottom of its view by
     * itself, as the margins of the text: what is shown around the text may go there.
     */
    val innerMargin: Int get() = 0

    /** Where in the whole book, 0..1, the chapter being read ends; null if the engine does not read it by chapters. */
    val chapterEnd: Float? get() = null

    /** Whether a pinch enlarges the page, as it does a fixed page, or the text of a book of flowing text. */
    val zoomable: Boolean get() = false

    /** Whether the page is enlarged now; a swipe then moves it about rather than turning it. */
    val zoomed: Boolean get() = false

    /** The fingers of a pinch have changed the size of the page by [factor] around ([x], [y]). */
    fun zoomBy(factor: Float, x: Float, y: Float) {}

    /** The fingers of a pinch were lifted: the page is drawn in the detail its size asks for. */
    fun zoomEnd() {}

    /** A finger moved the enlarged page by ([dx], [dy]). */
    fun panBy(dx: Float, dy: Float) {}

    /** The reader left the screen, or is back on it; nothing needs to go on in between. */
    fun pause() {}
    fun resume() {}
    fun close()
}

/**
 * A book read in the background as it is about to be opened in the reader, of whatever kind: the
 * reader then finds it read rather than reading it itself, on the thread that draws the screen,
 * while it starts.
 */
object BookPrep {
    /** The book being read, by its path; see [prepare]. */
    private var prepared: Pair<String, Future<Closeable>>? = null

    /**
     * Reads the books [prepare] is asked for, on a thread of its own: the threads that read the
     * covers and draw the shelves may well be busy, and the reader waits for the book.
     */
    private val reading = Executors.newSingleThreadExecutor()

    /** Starts reading [file] in the background, unless it is kept open from when it was last read. */
    @Synchronized
    fun prepare(file: File) {
        if (prepared?.first == file.path || KeptBook.holds(file)) return
        prepared?.second?.let(::discard)
        prepared = file.path to reading.submit(Callable<Closeable> { if (Library.isFlowing(file)) FlowBook.open(file) else PageSource.open(file) })
    }

    /** The book [file] as one of [type], kept open (KeptBook) or read by [prepare]; null if neither. Blocking. */
    fun <T : Closeable> take(file: File, type: Class<T>): T? {
        KeptBook.take(file, type)?.let { return it }
        val ready = synchronized(this) { prepared?.takeIf { it.first == file.path }?.second?.also { prepared = null } } ?: return null
        val book = try {
            ready.get()
        } catch (e: ExecutionException) {
            // Read again, to fail as the reader expects.
            return null
        }
        if (type.isInstance(book)) return type.cast(book)
        book.close()
        return null
    }

    /** Closes the book read ahead, if nobody took it, e.g. when the phone runs short of memory. */
    @Synchronized
    fun drop() {
        prepared?.second?.let(::discard)
        prepared = null
    }

    /** Closes the book [book] will hold, which nobody took. */
    private fun discard(book: Future<Closeable>) {
        reading.execute {
            try {
                book.get().close()
            } catch (e: Exception) {
                // It could not be read; there is nothing to close.
            }
        }
    }
}

/**
 * The book last closed in the reader, kept open for a while: one left and opened again soon after,
 * as when the reader goes to the shelf to look something up, need not be read again.
 */
object KeptBook {
    /** How long a book is kept after its reader closed. */
    private const val KEEP_MS = 30_000L

    /** The path of the book kept, the date its file had then, and the book. */
    private var kept: Triple<String, Long, Closeable>? = null
    private val expire = Runnable { drop() }

    /** Keeps [book], read from [file], rather than closing it; the one kept before is closed. */
    @Synchronized
    fun keep(file: File, book: Closeable) {
        drop()
        kept = Triple(file.path, Store.lastModified(file), book)
        Library.main.removeCallbacks(expire)
        Library.main.postDelayed(expire, KEEP_MS)
    }

    /** The book kept for [file], if it is one of [type] and the file has not changed since; it is not kept any more. */
    @Synchronized
    fun <T : Closeable> take(file: File, type: Class<T>): T? {
        val (path, modified, book) = kept ?: return null
        if (path != file.path || !type.isInstance(book)) return null
        kept = null
        Library.main.removeCallbacks(expire)
        if (modified != Store.lastModified(file)) {
            close(book)
            return null
        }
        return type.cast(book)
    }

    /** Whether the book kept is [file], as it is now. */
    @Synchronized
    fun holds(file: File) = kept?.let { it.first == file.path && it.second == Store.lastModified(file) } == true

    /** Closes the book kept, if there is one, e.g. when the phone runs short of memory. */
    @Synchronized
    fun drop() {
        val book = kept?.third ?: return
        kept = null
        close(book)
    }

    private fun close(book: Closeable) = Library.io.execute {
        try {
            book.close()
        } catch (e: Exception) {
            // It is let go of all the same.
        }
    }
}
