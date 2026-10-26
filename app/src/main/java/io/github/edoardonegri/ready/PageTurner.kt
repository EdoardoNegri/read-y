package io.github.edoardonegri.ready

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Window

/**
 * Turns the pages of a book as the reader asks, for ReaderActivity and for the page in the
 * settings that shows what they do (PagePreview): the [engine] shows the book in [content], with
 * what is shown around the text in [info] over it, and [sheet] turns over both like paper, if that
 * is asked for. A tap on a side of the page, a swipe, or the keys turn it.
 */
class PageTurner(
    private val window: Window,
    private val s: ReaderSettings,
    private val content: GestureFrame,
    private val sheet: PageTurnView,
    private val info: PageInfoView,
    private val engine: () -> ReaderEngine?,
) {
    /**
     * Which way a page is being turned, 1 or -1, from asking the engine for it until the engine
     * shows it; 0 otherwise.
     */
    private var turning = 0

    /** Where the page shown is, as the engine last told: its chapter and how far into it. */
    private var chapter = 0
    private var fraction = 0f

    /** The engine has the new page: the sheet is turned over to it. */
    private val startTurn = Runnable {
        // Back onto the last page is from the cover that ends the book, which is one picture across the screen.
        val spread = bound && !(turning < 0 && engine()?.let { it.hasCover && it.onLastPage } == true)
        // Not turned like paper, the picture of the page left only stood in front for the book to shut over it, if it is.
        if (s.slidePages && !s.vertical) sheet.turn(turning > 0, ::picture, place(), spread) else sheet.drop()
        turning = 0
    }

    /**
     * Whether the page being turned is one of two side by side (ReaderSettings.spread), and
     * is not turned to the cover that ends the book; see [PageTurnView.turn].
     */
    private var bound = false

    /** Whether the picture of the page in front, see [hold], stands for the engine rather than for a page being turned. */
    private var held = false

    /**
     * The engine is about to show nothing for a moment (ReaderHost.hold): the page as it is seen
     * stands in front of it meanwhile, and then [then] is called.
     */
    fun hold(then: () -> Unit) {
        // A page being turned stands in front already.
        if (turning != 0) return then()
        held = true
        sheet.cover(window, ::picture, place(), then)
    }

    /** What [hold] put in front of the page may go (ReaderHost.release). */
    fun release() {
        if (!held) return
        held = false
        // Once the page the engine shows again has been drawn.
        content.postDelayed(dropHeld, SETTLE_MS)
    }

    private val dropHeld = Runnable { if (turning == 0) sheet.drop() }

    /** The engine had no other page to go to, or took too long over it. */
    private val dropTurn = Runnable { stop() }

    /** Stops any turn of the page, which is then shown as it is. */
    fun stop() {
        content.removeCallbacks(dropHeld)
        held = false
        content.removeCallbacks(startTurn)
        content.removeCallbacks(dropTurn)
        turning = 0
        sheet.drop()
    }

    /** Goes to the next page, or with a [direction] of -1 to the one before, turning it like paper if that is asked for. */
    fun turn(direction: Int) {
        val e = engine() ?: return
        stop()
        val go = { if (direction > 0) e.next() else e.prev() }
        // From the last page the book shuts over it, as it does with the pages turned like paper or not,
        // which needs the page as it is seen to shrink into the cover (see takeTurn).
        if ((s.slidePages && !s.vertical) || (direction > 0 && e.hasCover && e.onLastPage)) {
            // The page being left stays in view until the engine has the other one, see
            // onPosition: it is only turned once it is covered.
            turning = direction
            bound = s.spread && content.width > content.height && !(direction > 0 && e.hasCover && e.onLastPage)
            content.postDelayed(dropTurn, WAIT_MS)
            sheet.cover(window, ::picture, place(), go)
        } else {
            go()
        }
    }

    /**
     * Takes over the turn to the next page, which the engine has just shown, for it to be shown
     * otherwise: the page being left stays in front, still, until [stop], or [turnTaken] turns it
     * after all. Its picture; null, and nothing taken, if no page is being turned to the next one.
     */
    fun takeTurn(): Bitmap? {
        if (turning <= 0) return null
        val page = sheet.still ?: return null
        content.removeCallbacks(startTurn)
        content.removeCallbacks(dropTurn)
        turning = 0
        return page
    }

    /** Does [action] once the page being turned, if any, is turned, and the engine's page is seen. */
    fun afterTurn(action: () -> Unit) = sheet.whenDropped(action)

    /** Turns the page taken by [takeTurn] over like paper after all. */
    fun turnTaken() = sheet.turn(true, ::picture, place())

    /**
     * The engine tells where the page it shows is (ReaderHost.onPosition): a page being turned to
     * is there, and a moment later, once it is drawn too, the sheet turns over to it.
     */
    fun onPosition(chapter: Int, fraction: Float) {
        if (turning != 0) {
            content.removeCallbacks(dropTurn)
            content.removeCallbacks(startTurn)
            content.postDelayed(startTurn, SETTLE_MS)
        }
        this.chapter = chapter
        this.fraction = fraction
    }

    /**
     * A tap at ([x], [y]) on the page, where the engine may have something to follow, such as a
     * link; else a tap on a side turns the page, if that is asked for, and any other [elsewhere].
     */
    fun tap(x: Float, y: Float, elsewhere: () -> Unit) {
        val e = engine() ?: return
        val w = content.width
        e.tap(x - content.paddingLeft, y - content.paddingTop) { handled ->
            if (!handled) when {
                // The sides of a page that is enlarged are not the sides of the screen.
                !s.tapToTurn || e.zoomed -> elsewhere()
                x > w * 0.7f -> turn(1)
                x < w * 0.3f -> turn(-1)
                else -> elsewhere()
            }
        }
    }

    /** A swipe of the finger towards [direction], +1 the right: turns the page the way the reader asked. */
    fun swipe(direction: Int) = turn(if ((direction < 0) != s.reverseSwipe) 1 else -1)

    /**
     * Where in the book the page shown is, and how it is laid out, for a picture of it to be shown
     * again only for the same page looking the same (PageTurnView); null for a page enlarged by
     * pinching, which is not shown so when it is come back to.
     */
    private fun place(): String? {
        if (engine()?.zoomed == true) return null
        return listOf(chapter, fraction, s.look, content.width, content.height).joinToString(":")
    }

    /**
     * Draws the page as it is seen into [reuse], or a new bitmap: what the engine shows, with what
     * is shown around it, as the page turned over shows them, see [PageTurnView.cover].
     */
    private fun picture(reuse: Bitmap?): Bitmap? {
        val bmp = content.picture(reuse) ?: return null
        info.draw(Canvas(bmp))
        return bmp
    }

    private companion object {
        /** How long the engine has to come up with the page asked for, and to draw it once it has. */
        const val WAIT_MS = 1500L
        const val SETTLE_MS = 60L
    }
}
