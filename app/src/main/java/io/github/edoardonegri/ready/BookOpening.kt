package io.github.edoardonegri.ready

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import java.io.File

/**
 * Opening a book from the shelf: its cover swings open on its spine, and the page behind it grows
 * until it fills the screen, where the reader takes over without a seam. Closing plays the same
 * backwards, starting from a picture of the page that was being read ([Left]). All of it is drawn
 * in a layer over the whole of [act], which holds the book at the size of the screen and shows it
 * as small as its place on the shelf; [attach] puts the layer in a window of its own rather than
 * in the activity's (see [Detached]).
 */
class BookOpening(private val act: Activity, attach: ((View) -> Unit)? = null) {

    /** The page a book was at when the reader left it for the shelf, for the shelf to close the book from. */
    class Left(val file: File, val page: Bitmap, val at: Long = SystemClock.uptimeMillis())

    /** Where a cover stands on the screen, and what it looks like. */
    class Spot(val x: Int, val y: Int, val w: Int, val h: Int, val picture: Bitmap)

    private val layer = FrameLayout(act)
    private val book = FrameLayout(act)
    private val paper = View(act)
    private val page = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_XY }

    /**
     * The cover as it swings open on its spine: its front until it stands edge-on to the eye,
     * where it can no longer be seen, and from there its [inside], which takes over edge-on.
     */
    private val cover = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val inside = View(act).apply { setBackgroundColor(INSIDE) }
    private var animator: ValueAnimator? = null

    /** Where the cover is on the shelf, within the layer. */
    private var x = 0f
    private var y = 0f
    private var w = 1f
    private var h = 1f

    /** What the layer covers: the activity's content, or the whole of the window it has to itself. */
    private val frame: View get() = layer.parent as? View ?: layer

    init {
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        for (v in arrayOf(paper, page, inside, cover)) book.addView(v, fill, fill)
        // The open cover lies beside the book.
        for (group in arrayOf(layer, book)) {
            group.clipChildren = false
            group.clipToPadding = false
        }
        layer.addView(book, fill, fill)
        // Nothing under the layer is touched while it shows.
        layer.isClickable = true
        if (attach != null) {
            attach(layer)
        } else {
            layer.visibility = View.GONE
            act.addContentView(layer, ViewGroup.LayoutParams(fill, fill))
        }
    }

    /**
     * Opens the book whose cover is shown by [from], on paper of the colour [paperColor]; [then]
     * is called when the page fills the screen, which it goes on doing until [clear].
     */
    fun open(from: View, paperColor: Int, then: () -> Unit) {
        open(spot(from) ?: return then(), paperColor, then)
    }

    /** Opens the book whose cover stands at [spot], as [open] does. */
    fun open(spot: Spot, paperColor: Int, then: () -> Unit) {
        animator?.cancel()
        paper.setBackgroundColor(paperColor)
        page.setImageBitmap(null)
        cover.visibility = View.VISIBLE
        inside.visibility = View.VISIBLE
        sized {
            if (!place(spot)) return@sized then()
            pose(0f)
            show()
            run(0f, 1f, then)
        }
    }

    /**
     * Shows [left], the page a book was left at, over the whole screen. Once the
     * shelf is laid out, [find] gives the cover of that book on it, and the page shrinks back
     * into the book, which closes; without a cover to go to, the page just fades.
     */
    fun close(left: Bitmap, paperColor: Int, find: () -> View?) {
        animator?.cancel()
        paper.setBackgroundColor(paperColor)
        page.setImageBitmap(left)
        page.alpha = 1f
        cover.visibility = View.INVISIBLE
        inside.visibility = View.INVISIBLE
        pose(1f)
        show()
        // Once the shelf under the layer is laid out, and before it is drawn.
        layer.doOnPreDraw {
            val spot = find()?.let(::spot)
            if (spot != null && place(spot)) {
                cover.visibility = View.VISIBLE
                inside.visibility = View.VISIBLE
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
    }

    private fun show() {
        layer.alpha = 1f
        layer.visibility = View.VISIBLE
    }

    /** Does [action] once what the layer covers has a size, at once if it has. */
    private fun sized(action: () -> Unit) {
        val f = frame
        if (f.width > 0 && f.height > 0) action() else f.doOnLayout { action() }
    }

    /** Notes where the cover at [spot] is, within the layer, and what it looks like. False if that cannot be told. */
    private fun place(spot: Spot): Boolean {
        val f = frame
        if (f.width == 0 || f.height == 0) return false
        val origin = IntArray(2)
        f.getLocationOnScreen(origin)
        x = (spot.x - origin[0]).toFloat()
        y = (spot.y - origin[1]).toFloat()
        w = spot.w.toFloat()
        h = spot.h.toFloat()
        cover.setImageBitmap(spot.picture)
        // The cover turns on its spine, seen from far enough for it not to loom over the shelf.
        for (v in arrayOf(cover, inside)) {
            v.pivotX = 0f
            v.pivotY = f.height / 2f
            v.cameraDistance = f.width * CAMERA * act.resources.displayMetrics.density
        }
        book.pivotX = 0f
        book.pivotY = 0f
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
        val f = frame
        val zoom = zoom(t)
        val angle = angle(t)
        // Past the upright, it is the inside of the cover that shows; either stands edge-on, unseen, while the other shows.
        cover.rotationY = -angle.coerceAtMost(90f)
        inside.rotationY = -angle.coerceAtLeast(90f)
        // A shelf that was never shown has no size yet to make the book smaller by: it comes back
        // under a book that was opened elsewhere, whose page fills it whatever its size will be.
        val sized = f.width > 0 && f.height > 0
        book.scaleX = if (sized) lerp(w / f.width, 1f, zoom) else 1f
        book.scaleY = if (sized) lerp(h / f.height, 1f, zoom) else 1f
        book.translationX = lerp(x, 0f, zoom)
        book.translationY = lerp(y, 0f, zoom)
        // The print is too small to read long before the page is back in the book.
        page.alpha = (zoom * 2.5f).coerceAtMost(1f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** How far, in degrees, the cover has swung open [t] of the way: it is open before the page has grown far. */
    private fun angle(t: Float) = EASE.getInterpolation((t / SWING).coerceIn(0f, 1f)) * OPEN_ANGLE

    /** How far the page has grown towards filling the screen [t] of the way, 0 to 1. */
    private fun zoom(t: Float) = EASE.getInterpolation(((t - ZOOM_FROM) / (1 - ZOOM_FROM)).coerceIn(0f, 1f))

    /**
     * Opens books over [act] in a window of their own, which a thread of its own makes and
     * moves: the reader starts while a book opens (see [Handoff]), and keeps the app's main
     * thread busy for a good part of it, which would stall an opening played there.
     */
    class Detached(private val act: Activity) {
        /** The opening shown now; only touched on the opening thread. */
        private var opening: BookOpening? = null
        private var shown: View? = null

        /** Opens the book whose cover is shown by [from], as [BookOpening.open] does; [then] is called on the main thread. */
        fun open(from: View, paperColor: Int, then: () -> Unit) {
            val spot = spot(from) ?: return then()
            val token = act.window.decorView.windowToken ?: return then()
            val main = Handler(Looper.getMainLooper())
            thread.post {
                remove()
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    this.token = token
                    windowAnimations = 0
                    if (Build.VERSION.SDK_INT >= 30) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                }
                try {
                    val o = BookOpening(act) {
                        act.windowManager.addView(it, params)
                        shown = it
                    }
                    opening = o
                    o.open(spot, paperColor) { main.post(then) }
                } catch (e: Exception) {
                    // The shelf has no window to put one over any more.
                    remove()
                    main.post(then)
                }
            }
        }

        /** Takes the opening away. */
        fun clear() = thread.post(::remove)

        private fun remove() {
            opening?.clear()
            opening = null
            shown?.let {
                shown = null
                try {
                    act.windowManager.removeViewImmediate(it)
                } catch (e: Exception) {
                    // Gone with the shelf's window already.
                }
            }
        }

        private companion object {
            val thread: Handler by lazy { Handler(HandlerThread("BookOpening").apply { start() }.looper) }
        }
    }

    /**
     * A reader started for a book the moment it is tapped on the shelf, unseen over it, while the
     * book opens there: the page is set out meanwhile rather than after. The reader shows itself
     * once the book is open and its page has been drawn, whichever comes last.
     */
    class Handoff {
        private var show: (() -> Unit)? = null
        private var open = false
        private val began = SystemClock.uptimeMillis()

        /**
         * Whether the reader is still to show itself: until then a tap meant for its page would
         * reach the shelf under it. Not for longer than a reader takes to start at most, in
         * case it never does.
         */
        val waiting get() = handoff === this && SystemClock.uptimeMillis() - began < WAIT_MS

        /** The reader has drawn its page behind the shelf; [show] makes it seen. */
        fun ready(show: () -> Unit) {
            if (open) done(show) else this.show = show
        }

        /** The book is open on the shelf. */
        fun opened() {
            open = true
            show?.let(::done)
        }

        private fun done(show: () -> Unit) {
            this.show = null
            if (handoff === this) handoff = null
            show()
        }
    }

    companion object {
        /** Set by the reader as it leaves for the shelf, and taken by the shelf as it comes back. */
        var left: Left? = null

        /** The reader being started behind the book opening on the shelf, if one is; see [Handoff]. */
        var handoff: Handoff? = null

        /** How long a page that was left stays worth closing the book from. */
        const val LEFT_MS = 3000L

        /** The longest the shelf waits for a reader started behind a book opening on it, see [Handoff.waiting]. */
        private const val WAIT_MS = 5000L

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

        /** Where [from] is on the screen and what it looks like; null if it has no size or picture. */
        private fun spot(from: View): Spot? {
            if (from.width == 0 || from.height == 0) return null
            val at = IntArray(2)
            from.getLocationOnScreen(at)
            return Spot(at[0], at[1], from.width, from.height, from.snapshot() ?: return null)
        }
    }
}
