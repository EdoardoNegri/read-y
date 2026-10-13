package io.github.edoardonegri.ready

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import kotlin.math.max

/**
 * Turns the page like one of paper. It lies over the page of the reader, which changes at once
 * underneath: first [cover] hides that behind a picture of the page being left, then [turn] folds
 * the picture over along a line that crosses the screen, the back of the sheet showing beside it.
 * Going back, it is the page arrived at that is folded back over the one being left.
 */
class PageTurnView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** The colour of the paper, which the back of the sheet has. */
    var paper = Color.WHITE

    /** The page being left, and going back the page arrived at; both are kept to be drawn on again. */
    private var left: Bitmap? = null
    private var arrived: Bitmap? = null

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

    /** Hides the page of [source] behind a picture of it as it is now, until [turn] or [drop]. */
    fun cover(source: View) {
        animator?.cancel()
        left = source.picture(left) ?: return drop()
        state = STILL
        visibility = VISIBLE
        invalidate()
    }

    /**
     * Turns the sheet: [forward] folds the page being left away from the right, otherwise the
     * page [source] shows by now is folded back over it from the left.
     */
    fun turn(forward: Boolean, source: View) {
        if (state != STILL) return
        if (!forward) arrived = source.picture(arrived) ?: return drop()
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
        state = IDLE
        animator?.let {
            animator = null
            it.cancel()
        }
        visibility = GONE
    }

    /** Lets go of the pictures, which are as large as the screen. */
    fun release() {
        drop()
        left = null
        arrived = null
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
