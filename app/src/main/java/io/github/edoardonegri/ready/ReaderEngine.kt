package io.github.edoardonegri.ready

import android.view.View

interface ReaderHost {
    /**
     * [percent] is progress through the whole book, 0..1. [label] says where that is. [page] is
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

    /** The book's table of contents: each line's title and how deep it is nested. Empty if it has none. */
    val contents: List<Pair<String, Int>> get() = emptyList()

    /**
     * Where in the whole book, 0..1, the page being shown begins, if that differs from how much
     * of the book is read, which counts the page itself too. The seek bar shows this, so that
     * its knob stands on the mark of a chapter when the chapter has just been opened.
     */
    val place: Float? get() = null

    /** Where in the whole book, 0..1, each line of [contents] leads. */
    val contentPositions: List<Float> get() = emptyList()

    /** The line of [contents] that the place being read belongs to, or -1. */
    val currentContent: Int get() = -1

    /** Opens the place the line [index] of [contents] leads to. */
    fun openContent(index: Int) {}

    /** Gives the content a chance to handle a tap (e.g. a link) before it turns the page. */
    fun tap(x: Float, y: Float, done: (handled: Boolean) -> Unit) = done(false)
    /** The reader left the screen, or is back on it; nothing needs to go on in between. */
    fun pause() {}
    fun resume() {}
    fun close()
}
