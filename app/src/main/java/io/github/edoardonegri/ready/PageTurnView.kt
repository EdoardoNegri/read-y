package io.github.edoardonegri.ready

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns the page like one of paper. It lies over the page of the reader, which changes at once
 * underneath: first [cover] hides that behind a picture of the page being left, then [turn] folds
 * the picture over along a line that crosses the screen, the back of the sheet showing beside it.
 * Going back, it is the page arrived at that is folded back over the one being left. The pictures
 * are of the page as it is seen, its number and the warm light on it included, so this lies over
 * all of those.
 */
class PageTurnView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** The colour of the paper, which the back of the sheet has. */
    var paper = Color.WHITE

    /**
     * Two pictures of pages, each with the place in the book it shows, as [cover] was told: the
     * page being left, and the one left before it. A turn back mostly comes to that, which is then
     * shown as it was, rather than drawn again. Each is drawn on again for a later picture.
     */
    private val pictures = arrayOfNulls<Bitmap>(2)
    private val places = arrayOfNulls<String>(2)

    /** Which of [pictures] is the page being left; the other is, going back, the page arrived at. */
    private var shown = 0
    private val left get() = pictures[shown]
    private val arrived get() = pictures[1 - shown]

    /** What is being shown: nothing, the page being left lying still, or a sheet turning one way or the other. */
    private var state = IDLE

    /** Where the sheet is folded, from the left edge. */
    private var fold = 0f
    private var animator: ValueAnimator? = null
    private val faint = Paint().apply { alpha = 46 }
    private val d = resources.displayMetrics.density

    // The shadows the lifted sheet casts: on the page it uncovers, across its own back, and on the page it comes down on.
    private val uncovered = shade(GradientDrawable.Orientation.LEFT_RIGHT, 0x59000000, 0)
    private val back = shade(GradientDrawable.Orientation.LEFT_RIGHT, 0x12000000, 0x00000000, 0x38000000)
    private val landing = shade(GradientDrawable.Orientation.RIGHT_LEFT, 0x47000000, 0)

    private fun shade(orientation: GradientDrawable.Orientation, vararg colors: Int) = GradientDrawable(orientation, colors)

    /** Counts the pictures asked for by [cover], so that one that comes after it was given up on is not shown. */
    private var copies = 0
    private val main = Handler(Looper.getMainLooper())

    /**
     * Hides the page behind a picture of it as it is seen now, until [turn] or [drop], and then
     * calls [then]. The picture is copied from the screen, which takes the drawing hardware next
     * to nothing and this thread nothing at all, and enlarged to the size of this view where the
     * screen shows it smaller (PagePreview); where it cannot be (before Android 8, or with part
     * of it out of sight), [draw] draws the page anew into the bitmap it is given, or a new one.
     * [place] says where in the book the page is, and how it is laid out, see [turn]; null if a
     * picture of it is not to be shown again.
     */
    fun cover(window: Window, draw: (Bitmap?) -> Bitmap?, place: String?, then: () -> Unit) {
        animator?.cancel()
        val serial = ++copies
        // The picture of the page left before stays, for a turn back to it; this one is drawn
        // over the picture before that.
        val slot = 1 - shown
        places[slot] = null
        if (Build.VERSION.SDK_INT >= 26 && width > 0 && height > 0) {
            val bmp = pictures[slot]?.takeIf { it.width == width && it.height == height } ?: try {
                createBitmap(width, height)
            } catch (e: OutOfMemoryError) {
                null
            }
            // Where this view is in the window, as large as it is shown there.
            val seen = Rect()
            val whole = getGlobalVisibleRect(seen) &&
                abs(seen.width().toLong() * height - seen.height().toLong() * width) <= max(width, height)
            if (bmp != null && whole) {
                try {
                    PixelCopy.request(window, seen, bmp, { result ->
                        // The page is turned all the same if the picture could not be had; it just does not show.
                        if (serial == copies && result == PixelCopy.SUCCESS) still(slot, bmp, place)
                        then()
                    }, main)
                    return
                } catch (e: IllegalArgumentException) {
                    // The window has nothing drawn to copy yet.
                }
            }
        }
        draw(pictures[slot])?.let { still(slot, it, place) } ?: drop()
        then()
    }

    /** Shows [picture] of the page being left, at [place], lying still; it is kept as [slot] of [pictures]. */
    private fun still(slot: Int, picture: Bitmap, place: String?) {
        pictures[slot] = picture
        places[slot] = place
        shown = slot
        // It is handed to the drawing hardware now rather than in the first frame of the turn.
        picture.prepareToDraw()
        state = STILL
        visibility = VISIBLE
        invalidate()
    }

    /**
     * Turns the sheet: [forward] folds the page being left away from the right, otherwise the
     * page shown by now, at [place], is folded back over it from the left: as it was pictured
     * when it was last left, if that was there and laid out the same, or else as [draw] draws it,
     * as [cover] says.
     */
    fun turn(forward: Boolean, draw: (Bitmap?) -> Bitmap?, place: String?) {
        if (state != STILL) return
        if (!forward) {
            val back = 1 - shown
            val kept = pictures[back]?.takeIf { place != null && places[back] == place && it.width == width && it.height == height }
            if (kept == null) {
                pictures[back] = draw(pictures[back])?.also { it.prepareToDraw() } ?: return drop()
                places[back] = place
            }
        }
        state = if (forward) FORWARD else BACKWARD
        val w = width.toFloat()
        animator = ValueAnimator.ofFloat(if (forward) w else 0f, if (forward) 0f else w).apply {
            duration = TURN_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                fold = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = drop()
            })
            start()
        }
    }

    /** Shows the page of the reader again, at once. */
    fun drop() {
        copies++
        state = IDLE
        animator?.let {
            animator = null
            it.cancel()
        }
        // Laid out all the same, so that it has the size of the page for the next picture.
        visibility = INVISIBLE
    }

    /** Lets go of the pictures, which are as large as the screen. */
    fun release() {
        drop()
        pictures.fill(null)
        places.fill(null)
    }

    override fun onDraw(canvas: Canvas) {
        val under = left ?: return
        if (state == IDLE) return
        if (state != FORWARD) canvas.drawBitmap(under, 0f, 0f, null)
        if (state == STILL) return
        val sheet = (if (state == FORWARD) under else arrived) ?: return
        val w = width.toFloat()
        val h = height
        // The part of the sheet that still lies flat.
        canvas.withClip(0f, 0f, fold, h.toFloat()) { drawBitmap(sheet, 0f, 0f, null) }
        uncovered.setBounds(fold.toInt(), 0, (fold + 26 * d).toInt(), h)
        uncovered.draw(canvas)
        // The part that is folded over, seen from behind: paper with the print showing through.
        val edge = max(0f, 2 * fold - w)
        canvas.withClip(edge, 0f, fold, h.toFloat()) {
            drawColor(paper)
            withScale(-1f, 1f, fold, 0f) { drawBitmap(sheet, 0f, 0f, faint) }
            back.setBounds((2 * fold - w).toInt(), 0, fold.toInt(), h)
            back.draw(this)
        }
        landing.setBounds((edge - 18 * d).toInt(), 0, edge.toInt(), h)
        if (edge > 0f) landing.draw(canvas)
    }

    private companion object {
        const val IDLE = 0
        const val STILL = 1
        const val FORWARD = 2
        const val BACKWARD = 3
        const val TURN_MS = 420L
    }
}
