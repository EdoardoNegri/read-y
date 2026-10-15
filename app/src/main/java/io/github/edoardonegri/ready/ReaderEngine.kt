package io.github.edoardonegri.ready

import android.view.View

interface ReaderHost {
    /**
     * [percent] is progress through the whole book, 0..1: where the page being shown begins, so
     * that the first page is 0 and the last 1, and the seek bar's knob stands on the mark of a
     * chapter when the chapter has just been opened. [label] says where that is. [page] is
     * the part of it that says where in the chapter, for a host that names the chapter itself
     * after the book's contents; null if the book is not read by chapters. [pageOfChapter] is the
     * page of the chapter being shown, counted from 1, and how many the chapter has, if it has pages;
     * [pageOfBook] is the same for the whole book, if the engine can tell.
     */
    fun onPosition(
        chapter: Int, fraction: Float, percent: Float, label: String, page: String?,
        pageOfChapter: Pair<Int, Int>? = null, pageOfBook: Pair<Int, Int>? = null,
    )
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
