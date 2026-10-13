package io.github.edoardonegri.ready

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.doOnPreDraw
import java.io.File

/**
 * Opening a book from the shelf: its cover swings open on its spine, and the page behind it grows
 * until it fills the screen, where the reader takes over without a seam. Closing plays the same
 * backwards, starting from a picture of the page that was being read ([Left]). All of it is drawn
 * in a layer over the whole of [act], which holds the book at the size of the screen and shows it
 * as small as its place on the shelf.
 */
class BookOpening(private val act: Activity) {

    /** The page a book was at when the reader left it for the shelf, for the shelf to close the book from. */
    class Left(val file: File, val page: Bitmap, val at: Long = SystemClock.uptimeMillis())

    private val layer = FrameLayout(act)
    private val book = FrameLayout(act)
    private val paper = View(act)
    private val page = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val cover = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private var front: Bitmap? = null

    /** Whether the cover shows its inside rather than its [front]; null while it shows neither. */
    private var insideShown: Boolean? = null
    private var animator: ValueAnimator? = null

    /** Where the cover is on the shelf, within the layer. */
    private var x = 0f
    private var y = 0f
    private var w = 1f
    private var h = 1f

    init {
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        for (v in arrayOf(paper, page, cover)) book.addView(v, fill, fill)
        // The open cover lies beside the book.
        for (group in arrayOf(layer, book)) {
            group.clipChildren = false
            group.clipToPadding = false
        }
        layer.addView(book, fill, fill)
        // Nothing under the layer is touched while it shows.
        layer.isClickable = true
        layer.visibility = View.GONE
        act.addContentView(layer, ViewGroup.LayoutParams(fill, fill))
    }

    /**
     * Opens the book whose cover is shown by [from], on paper of the colour [paperColor]; [then]
     * is called when the page fills the screen, which it goes on doing until [clear].
     */
    fun open(from: View, paperColor: Int, then: () -> Unit) {
        if (!place(from)) return then()
        paper.setBackgroundColor(paperColor)
        page.setImageBitmap(null)
        cover.visibility = View.VISIBLE
        pose(0f)
        show()
        run(0f, 1f, then)
    }

    /**
     * Shows [left], the page a book was left at, over the whole screen, lit by [lamp]. Once the
     * shelf is laid out, [find] gives the cover of that book on it, and the page shrinks back
     * into the book, which closes; without a cover to go to, the page just fades.
     */
    fun close(left: Bitmap, paperColor: Int, lamp: Int, find: () -> View?) {
        animator?.cancel()
        paper.setBackgroundColor(paperColor)
        page.setImageBitmap(left)
        page.setColorFilter(lamp, PorterDuff.Mode.MULTIPLY)
        page.alpha = 1f
        cover.visibility = View.INVISIBLE
        pose(1f)
        show()
        // Once the shelf under the layer is laid out, and before it is drawn.
        layer.doOnPreDraw {
            val from = find()
            if (from != null && place(from)) {
                cover.visibility = View.VISIBLE
                pose(1f)
                run(1f, 0f, ::clear)
            } else {
                layer.animate().alpha(0f).setDuration(FADE_MS).withEndAction(::clear)
            }
        }
    }

    /** Takes the layer away, at once. */
    fun clear() {
        animator?.let {
            animator = null
            it.cancel()
        }
        layer.animate().cancel()
        layer.visibility = View.GONE
        page.setImageBitmap(null)
        cover.setImageDrawable(null)
        front = null
        insideShown = null
    }

    private fun show() {
        layer.alpha = 1f
        layer.visibility = View.VISIBLE
    }

    /** Notes where [from] is and what it looks like. False if that cannot be told. */
    private fun place(from: View): Boolean {
        val parent = layer.parent as? View ?: return false
        if (from.width == 0 || from.height == 0 || parent.width == 0 || parent.height == 0) return false
        val at = IntArray(2)
        val origin = IntArray(2)
        from.getLocationInWindow(at)
        parent.getLocationInWindow(origin)
        x = (at[0] - origin[0]).toFloat()
        y = (at[1] - origin[1]).toFloat()
        w = from.width.toFloat()
        h = from.height.toFloat()
        front = from.picture() ?: return false
        // The cover turns on its spine, seen from far enough for it not to loom over the shelf.
        cover.pivotX = 0f
        cover.pivotY = parent.height / 2f
        cover.cameraDistance = parent.width * CAMERA * act.resources.displayMetrics.density
        book.pivotX = 0f
        book.pivotY = 0f
        insideShown = null
        return true
    }

    private fun run(from: Float, to: Float, then: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = OPEN_MS
            addUpdateListener { pose(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) then()
                }
            })
            start()
        }
    }

    /** Shows the book [t] of the way from shut on the shelf, 0, to its page filling the screen, 1. */
    private fun pose(t: Float) {
        val parent = layer.parent as? View ?: return
        // The cover has swung open before the page has grown far.
        val swing = EASE.getInterpolation((t / SWING).coerceIn(0f, 1f))
        val zoom = EASE.getInterpolation(((t - ZOOM_FROM) / (1 - ZOOM_FROM)).coerceIn(0f, 1f))
        val angle = swing * OPEN_ANGLE
        cover.rotationY = -angle
        // Past the upright, it is the inside of the cover that shows.
        val inside = angle > 90f
        if (inside != insideShown) {
            insideShown = inside
            if (inside) cover.setImageDrawable(INSIDE.toDrawable()) else cover.setImageBitmap(front)
        }
        // A shelf that was never shown has no size yet to make the book smaller by: it comes back
        // under a book that was opened elsewhere, whose page fills it whatever its size will be.
        val sized = parent.width > 0 && parent.height > 0
        book.scaleX = if (sized) lerp(w / parent.width, 1f, zoom) else 1f
        book.scaleY = if (sized) lerp(h / parent.height, 1f, zoom) else 1f
        book.translationX = lerp(x, 0f, zoom)
        book.translationY = lerp(y, 0f, zoom)
        // The print is too small to read long before the page is back in the book.
        page.alpha = (zoom * 2.5f).coerceAtMost(1f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        /** Set by the reader as it leaves for the shelf, and taken by the shelf as it comes back. */
        var left: Left? = null

        /** How long a page that was left stays worth closing the book from. */
        const val LEFT_MS = 3000L

        private const val OPEN_MS = 760L
        private const val FADE_MS = 180L

        /** The part of the time the cover takes to swing open, and the part after which the page starts to grow. */
        private const val SWING = 0.62f
        private const val ZOOM_FROM = 0.3f
        private const val OPEN_ANGLE = 172f

        /** How many widths of the screen away the cover is seen from as it swings towards the eye. */
        private const val CAMERA = 5f

        /** The endpaper on the inside of the cover. */
        private const val INSIDE = 0xFFD9CDB4.toInt()
        private val EASE = AccelerateDecelerateInterpolator()
    }
}
