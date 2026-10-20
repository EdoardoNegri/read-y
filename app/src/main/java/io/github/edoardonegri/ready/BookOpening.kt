package io.github.edoardonegri.ready

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.get
import androidx.core.graphics.withClip
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Opening a book from the shelf: its cover swings open on its spine, and the page behind it grows
 * until it fills the screen, where the reader takes over without a seam. Closing plays the same
 * backwards, starting from a picture of the page that was being read ([Left]). All of it is drawn
 * in a layer over the whole of [act], which holds the book at the size of the screen and shows it
 * as small as its place on the shelf; [attach] puts the layer in a window of its own rather than
 * in the activity's (see [Detached]).
 */
class BookOpening(private val act: Activity, attach: ((View) -> Unit)? = null) {

    /** Whether the layer is in a window of its own, see [attach]. */
    private val detached = attach != null

    /** The page a book was at when the reader left it for the shelf, for the shelf to close the book from. */
    class Left(val file: File, val page: Bitmap, val at: Long = SystemClock.uptimeMillis())

    /**
     * Where a cover stands on the screen, and what it looks like: [picture], laid over the cover
     * by [fit] if given, else stretched over it, with the [binding] of a book on the shelf over it.
     */
    class Spot(val x: Int, val y: Int, val w: Int, val h: Int, val picture: Bitmap, val fit: Matrix? = null, val binding: Boolean = false)

    private val layer = FrameLayout(act)
    private val book = FrameLayout(act)
    private val paper = View(act)
    private val page = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_XY }

    /**
     * The cover as it swings open on its spine: its front until it stands edge-on to the eye,
     * where it can no longer be seen; past that, its board shows its inside ([Edge]).
     */
    private val cover = Cover(act).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        // A cover cropped to its place does not spill out of it.
        cropToPadding = true
    }

    /**
     * A cover with the binding the shelf draws over it (ShelfArt.drawBinding), which goes with it
     * as it swings open: drawn [by] times as large as on the shelf, which the book is made as
     * small as again; none for 0.
     */
    private class Cover(ctx: Activity) : AppCompatImageView(ctx) {
        private val paint = Paint()
        private val area = RectF()
        var by = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (by <= 0f) return
            area.set(0f, 0f, width.toFloat(), height.toFloat())
            ShelfArt.drawBinding(canvas, area, resources.displayMetrics.density * by, paint)
        }
    }

    /** The board of the cover, which shows its edge as it swings, and its inside once past upright. */
    private val edge = Edge(act)
    private var animator: ValueAnimator? = null

    /** Whether the page has its print, see [print]. */
    private var printed = false

    /** Whether the page opening has no book around it, see [open]. */
    private var bare = false

    /**
     * Whether the book is only enlarged out of its place on the shelf, its cover as it is, without
     * opening: it ends as the cover that ends a book read to its end, over the whole screen. The
     * book is as large as the screen, and is made as small, [growScale], as the cover on the shelf;
     * [growFrom] is where the cover is in it, which the book is cut down to, as far as it grows.
     */
    private var grown = false
    private var growScale = 1f
    private var growX = 0f
    private var growY = 0f
    private val growFrom = Rect()
    private val clip = Rect()

    /**
     * The menu of the reader as it stands over the cover that ends the book, pictured by the reader,
     * which a cover that only grows raises from the foot of the screen as it grows, to be where the
     * reader has it when the reader takes over; and how high it is, which the cover leaves room for.
     */
    private val menuView = ImageView(act).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = View.GONE
    }
    private var menuHeight = 0

    /** Where the cover is on the shelf, within the layer. */
    private var x = 0f
    private var y = 0f
    private var w = 1f
    private var h = 1f

    /** What the layer covers: the activity's content, or the whole of the window it has to itself. */
    private val frame: View get() = layer.parent as? View ?: layer

    init {
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        for (v in arrayOf(paper, page, edge, cover)) book.addView(v, fill, fill)
        // The open cover lies beside the book.
        for (group in arrayOf(layer, book)) {
            group.clipChildren = false
            group.clipToPadding = false
        }
        layer.addView(book, fill, fill)
        layer.addView(menuView, FrameLayout.LayoutParams(fill, 0, Gravity.BOTTOM))
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
     * Opens the book whose cover is shown by [from], on paper of the colour [paperColor] with
     * [print] on it, a picture of the page as the reader shows it, if there is one; [then] is
     * called when the page fills the screen, which it goes on doing until [clear]. A [bare] page,
     * with no book around it, only grows out of the middle of [from] until it fills the screen.
     */
    fun open(from: View, paperColor: Int, print: Bitmap?, bare: Boolean, grown: Boolean = false, menu: Bitmap? = null, then: () -> Unit) {
        open(spot(from) ?: return then(), paperColor, print, bare, grown, menu, then)
    }

    /**
     * Opens the book whose cover stands at [spot], as [open] does. A [grown] book is a cover that
     * only grows, to fill the screen as the cover that ends the book, and shows no page; it
     * raises the [menu] of the reader, pictured, from the foot of the screen as it grows, and stops
     * above it.
     */
    fun open(spot: Spot, paperColor: Int, print: Bitmap?, bare: Boolean, grown: Boolean = false, menu: Bitmap? = null, then: () -> Unit) {
        animator?.cancel()
        this.bare = bare
        this.grown = grown
        book.clipBounds = null
        menuHeight = if (grown) menu?.height ?: 0 else 0
        menuView.setImageBitmap(if (grown) menu else null)
        menuView.visibility = if (menuHeight > 0) View.VISIBLE else View.GONE
        menuView.layoutParams = (menuView.layoutParams as FrameLayout.LayoutParams).apply { height = menuHeight }
        // A cover that only grows ends on the dark wood of the shelf, as the cover that ends a book lies on it.
        paper.setBackgroundColor(if (grown) act.getColor(R.color.wood_dark) else paperColor)
        page.animate().cancel()
        page.alpha = 1f
        // A cover that grows has no page; the print the reader sends later is not wanted.
        page.setImageBitmap(if (grown) null else print)
        printed = print != null || grown
        cover.visibility = if (bare) View.GONE else View.VISIBLE
        edge.visibility = if (grown) View.GONE else cover.visibility
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
     * into the book, which closes: the opening played backwards. Without a cover to go to, the
     * page just fades.
     */
    fun close(left: Bitmap, paperColor: Int, grown: Boolean = false, find: () -> View?) {
        animator?.cancel()
        paper.setBackgroundColor(if (grown) act.getColor(R.color.wood_dark) else paperColor)
        page.setImageBitmap(left)
        page.alpha = 1f
        bare = false
        // A book left on its cover, at its end, only shrinks into its place: the opening of a grown book played backwards.
        this.grown = grown
        book.clipBounds = null
        menuHeight = 0
        menuView.visibility = View.GONE
        cover.visibility = View.INVISIBLE
        edge.visibility = View.INVISIBLE
        pose(1f)
        show()
        // Once the shelf under the layer is laid out, and before it is drawn.
        layer.doOnPreDraw {
            val spot = find()?.let(::spot)
            if (spot != null && place(spot)) {
                cover.visibility = if (grown) View.INVISIBLE else View.VISIBLE
                edge.visibility = if (grown) View.INVISIBLE else View.VISIBLE
                pose(1f)
                run(1f, 0f, ::clear)
            } else {
                layer.animate().alpha(0f).setDuration(FADE_MS).withEndAction(::clear)
            }
        }
    }

    /**
     * The reader has pictured its page, [print], while the book opens on blank paper: the print
     * comes onto the page. A page that has its print already keeps it.
     */
    fun print(print: Bitmap) {
        if (printed || layer.visibility != View.VISIBLE) return
        printed = true
        page.setImageBitmap(print)
        page.alpha = 0f
        page.animate().alpha(1f).setDuration(PRINT_FADE_MS)
    }

    /** Takes the layer away, at once. */
    fun clear() {
        animator?.let {
            animator = null
            it.cancel()
        }
        layer.animate().cancel()
        page.animate().cancel()
        steady(false)
        book.clipBounds = null
        menuView.visibility = View.GONE
        menuView.setImageBitmap(null)
        layer.visibility = View.GONE
        page.setImageBitmap(null)
        cover.setImageDrawable(null)
    }

    private fun show() {
        layer.alpha = 1f
        layer.visibility = View.VISIBLE
        steady(true)
    }

    /**
     * Has the screen drawn as often a second as it can while the book opens or closes ([on]), or
     * as the app otherwise asks. A screen left to change its speed by itself may do so in the
     * middle of the book opening, which shows as a flicker, and makes it run unevenly.
     */
    private fun steady(on: Boolean) {
        val mode = if (on) ReaderSettings.fastestMode(act) else 0
        val lp = (if (detached) layer.layoutParams as? WindowManager.LayoutParams else act.window.attributes) ?: return
        if (lp.preferredDisplayModeId == mode) return
        lp.preferredDisplayModeId = mode
        if (!detached) {
            act.window.attributes = lp
            return
        }
        try {
            act.windowManager.updateViewLayout(layer, lp)
        } catch (e: Exception) {
            // The window has gone already.
        }
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
        // A bare page grows out of nothing, in the middle of where it comes from.
        if (bare) {
            x += w / 2
            y += h / 2
            w = 0f
            h = 0f
        }
        cover.setImageBitmap(spot.picture)
        cover.setPadding(0, 0, 0, 0)
        if (grown) {
            // The cover as it is, whole, where the cover that ends the book is: in the middle of the
            // room above the menu, if there is one to be raised under it.
            cover.scaleType = ImageView.ScaleType.FIT_CENTER
            cover.setPadding(0, 0, 0, menuHeight)
            cover.by = 0f
            cover.rotationY = 0f
            val room = (f.height - menuHeight).toFloat()
            val shape = spot.picture.width.toFloat() / spot.picture.height
            var coverW = f.width.toFloat()
            var coverH = coverW / shape
            if (coverH > room) {
                coverH = room
                coverW = coverH * shape
            }
            val left = (f.width - coverW) / 2f
            val top = (room - coverH) / 2f
            growFrom.set(left.toInt(), top.toInt(), (left + coverW).toInt(), (top + coverH).toInt())
            // Made as wide as the cover on the shelf, and set down on it by the middle.
            growScale = w / coverW
            growX = x + w / 2f - f.width / 2f * growScale
            growY = y + h / 2f - room / 2f * growScale
            book.pivotX = 0f
            book.pivotY = 0f
            cover.invalidate()
            return true
        }
        // The cover is as large as the screen, and made as small as its place on the shelf.
        val fit = spot.fit
        if (fit == null) {
            cover.scaleType = ImageView.ScaleType.FIT_XY
        } else {
            cover.scaleType = ImageView.ScaleType.MATRIX
            cover.imageMatrix = Matrix(fit).apply { postScale(f.width / spot.w.toFloat(), f.height / spot.h.toFloat()) }
        }
        // The cover turns on its spine, seen from far enough for it not to loom over the shelf.
        cover.pivotX = 0f
        cover.pivotY = f.height / 2f
        cover.cameraDistance = f.width * CAMERA * act.resources.displayMetrics.density
        cover.by = if (spot.binding) f.width / spot.w.toFloat() else 0f
        cover.invalidate()
        edge.board(foreEdge(spot), cover.cameraDistance, f.height * THICKNESS)
        book.pivotX = 0f
        book.pivotY = 0f
        return true
    }

    /**
     * Plays the book from [from] to [to] (see [pose]), always over the same time, so that closing
     * is opening played backwards; [then] is called at the end.
     */
    private fun run(from: Float, to: Float, then: () -> Unit) {
        animator?.cancel()
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = OPEN_MS
            // Evenly: pose eases each part of the way itself. Eased twice, the book would hang at
            // either end and leap through the middle, a long way from one frame to the next.
            interpolator = null
            addUpdateListener { pose(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    steady(false)
                    then()
                }
            })
        }
        animator = a
        afterFrame { if (animator === a) a.start() }
    }

    /**
     * Does [action] once the frame being drawn now is on the screen. A window's first frame can be
     * long in coming, and an animation timed from before it would leap past its start.
     */
    private fun afterFrame(action: () -> Unit) {
        var done = false
        val once = Runnable {
            if (!done) {
                done = true
                action()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) layer.viewTreeObserver.registerFrameCommitCallback(once) else layer.doOnPreDraw { layer.post(once) }
        // Not later than this all the same, should no frame be drawn.
        layer.postDelayed(once, FRAME_WAIT_MS)
        layer.invalidate()
    }

    /** Shows the book [t] of the way from shut on the shelf, 0, to its page filling the screen, 1. */
    private fun pose(t: Float) {
        val f = frame
        if (grown) {
            // Only enlarged: the paper comes about the cover as it grows, which is all there is to see of the book at first.
            val e = EASE.getInterpolation(t)
            val scale = lerp(growScale, 1f, e)
            book.scaleX = scale
            book.scaleY = scale
            book.translationX = lerp(growX, 0f, e)
            book.translationY = lerp(growY, 0f, e)
            // Only the cover is seen until it is nearly as large as it goes; the paper then comes about it.
            val comes = ((e - PAPER_FROM) / (1 - PAPER_FROM)).coerceIn(0f, 1f)
            clip.set(
                lerp(growFrom.left.toFloat(), 0f, comes).toInt(), lerp(growFrom.top.toFloat(), 0f, comes).toInt(),
                lerp(growFrom.right.toFloat(), f.width.toFloat(), comes).toInt(), lerp(growFrom.bottom.toFloat(), f.height.toFloat(), comes).toInt(),
            )
            book.clipBounds = clip
            // The menu comes up as the cover grows, and is in its place when the cover is.
            if (menuHeight > 0) menuView.translationY = lerp(menuHeight.toFloat(), 0f, e)
            return
        }
        // A bare page grows all the way through; a book's only once its cover has begun to open.
        val zoom = if (bare) EASE.getInterpolation(t) else zoom(t)
        val angle = angle(t)
        // Past the upright, it is the inside of the board that shows, and its front stands edge-on, unseen.
        cover.rotationY = -angle.coerceAtMost(90f)
        edge.turn(-angle)
        // A shelf that was never shown has no size yet to make the book smaller by: it comes back
        // under a book that was opened elsewhere, whose page fills it whatever its size will be.
        val sized = f.width > 0 && f.height > 0
        book.scaleX = if (sized) lerp(w / f.width, 1f, zoom) else 1f
        book.scaleY = if (sized) lerp(h / f.height, 1f, zoom) else 1f
        book.translationX = lerp(x, 0f, zoom)
        book.translationY = lerp(y, 0f, zoom)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * The board of a cover, seen as the cover swings on its spine: its edge, the band between the
     * front of the board at its outer edge and its back, which lies [thick] further from the eye;
     * and once the cover is past upright, its back, the inside of the cover, which faces the eye
     * from there. All of it is seen as the cover itself is (View.rotationY), so the band stays on
     * the cover's edge; it is hidden under the cover while the book is shut.
     */
    private class Edge(ctx: Activity) : View(ctx) {
        private val band = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val inside = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dpi = ctx.resources.displayMetrics.densityDpi.toFloat()
        private var turned = 0f
        private var thick = 0f

        /** How far the eye is from the cover, in pixels. */
        private var eye = 1f

        /** Makes the board [thick] and of the colour [color], seen from as far as a view with that [distance] is. */
        fun board(color: Int, distance: Float, thick: Float) {
            paint.color = color
            this.thick = thick
            // As View.setCameraDistance has it, in inches, which the camera counts 72 points to.
            eye = abs(distance) / dpi * 72f
            invalidate()
        }

        /** Turns the board on its spine as View.rotationY does. */
        fun turn(degrees: Float) {
            if (turned == degrees) return
            turned = degrees
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (width == 0) return
            val a = Math.toRadians(turned.toDouble())
            val cos = cos(a).toFloat()
            val sin = sin(a).toFloat()
            val w = width.toFloat()
            // The outer edge of the front of the board, and of its back, turned on the spine.
            val frontX = w * cos
            val frontZ = w * sin
            val backX = w * cos - thick * sin
            val backZ = w * sin + thick * cos
            band.rewind()
            corner(frontX, frontZ, -1f, true)
            corner(frontX, frontZ, 1f, false)
            corner(backX, backZ, 1f, false)
            corner(backX, backZ, -1f, false)
            band.close()
            canvas.drawPath(band, paint)
            if (turned >= -90f) return
            // The inside, from the back of the board at the spine to its outer edge, lit the more
            // the more it faces the eye. It lies beside the page, never over it: what of it would,
            // so near the spine, is the board's thickness, which the page hides.
            inside.color = shade(INSIDE, INSIDE_DARK + (1 - INSIDE_DARK) * -cos)
            band.rewind()
            corner(-thick * sin, thick * cos, -1f, true)
            corner(backX, backZ, -1f, false)
            corner(backX, backZ, 1f, false)
            corner(-thick * sin, thick * cos, 1f, false)
            band.close()
            canvas.withClip(-SPREAD * w, -SPREAD * height, 0f, SPREAD * height) { drawPath(band, inside) }
        }

        /** Adds to the band the point [x] along and [z] away from the spine, at its top (-1) or bottom ([end] 1), as the eye sees it. */
        private fun corner(x: Float, z: Float, end: Float, first: Boolean) {
            val near = eye / (eye + z)
            val half = height / 2f
            val px = x * near
            val py = half + end * half * near
            if (first) band.moveTo(px, py) else band.lineTo(px, py)
        }
    }

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

        /**
         * What is to be done once the book shown lies open, until it does; only touched on the
         * opening thread. An opening taken away before then, as when the shelf goes out of sight
         * meanwhile, still has it done: the reader waiting unseen for the book to open would
         * otherwise never show itself.
         */
        private var pending: (() -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        /**
         * Opens the book [file], whose cover is shown by [from], at once, while the reader starts
         * behind it ([handoff]): on the page as the book was last left, if that is kept and looks
         * the same ([look]), else on paper that the page the reader sets out meanwhile comes onto
         * as soon as the reader has pictured it.
         */
        fun open(from: View, paperColor: Int, file: File, look: String, handoff: Handoff, bare: Boolean = false, grown: Boolean = false) {
            if (grown) {
                // A cover that only grows raises the menu of the reader as it does: it waits a moment for the reader to picture it.
                handoff.whenMenu(MENU_WAIT_MS) { menu -> open(from, paperColor, null, bare, true, menu, handoff::opened) }
            } else {
                open(from, paperColor, kept(file, look), bare, false, null, handoff::opened)
            }
            handoff.whenPictured { print -> if (print != null) thread.post { opening?.print(print) } }
        }

        /** Opens the book whose cover is shown by [from], as [BookOpening.open] does; [then] is called on the main thread. */
        fun open(from: View, paperColor: Int, print: Bitmap?, bare: Boolean, grown: Boolean = false, menu: Bitmap? = null, then: () -> Unit) {
            val spot = spot(from) ?: return then()
            val token = act.window.decorView.windowToken ?: return then()
            thread.post {
                remove()
                pending = then
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
                    o.open(spot, paperColor, print, bare, grown, menu) {
                        pending = null
                        main.post(then)
                    }
                } catch (e: Exception) {
                    // The shelf has no window to put one over any more.
                    remove()
                }
            }
        }

        /** Takes the opening away. */
        fun clear() = thread.post(::remove)

        private fun remove() {
            pending?.let {
                pending = null
                main.post(it)
            }
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

        /** The reader's page as it pictured it, once it has; see [pictured]. */
        private var print: Bitmap? = null
        private var pictured = false
        private var onPicture: ((Bitmap?) -> Unit)? = null

        /** The reader has set out its page and pictured it as it shows it, [print], or could not: null. */
        fun pictured(print: Bitmap?) {
            if (pictured) return
            pictured = true
            this.print = print
            onPicture?.let {
                onPicture = null
                it(print)
            }
        }

        /** Calls [then] with the picture of the reader's page once it has one, see [pictured]; at once if it has. */
        fun whenPictured(then: (Bitmap?) -> Unit) {
            if (pictured) then(print) else onPicture = then
        }

        /**
         * The reader's menu as it stands over the cover that ends a book, pictured, or null if it
         * could not be; see [menuPictured]. It is as high as the room it takes at the foot of the screen.
         */
        private var menu: Bitmap? = null
        private var menuKnown = false
        private var onMenu: ((Bitmap?) -> Unit)? = null

        /** The reader has pictured its menu as it is over the cover that ends the book, or could not: null. */
        fun menuPictured(menu: Bitmap?) {
            if (menuKnown) return
            menuKnown = true
            this.menu = menu
            onMenu?.let {
                onMenu = null
                it(menu)
            }
        }

        /**
         * Calls [then] with the picture of the menu once the reader has one, see [menuPictured]; at
         * once if it has, and with null if it has none after [wait] milliseconds: the book opens without it.
         */
        fun whenMenu(wait: Long, then: (Bitmap?) -> Unit) {
            if (menuKnown) return then(menu)
            var called = false
            val once = { picture: Bitmap? ->
                if (!called) {
                    called = true
                    onMenu = null
                    then(picture)
                }
            }
            onMenu = once
            Handler(Looper.getMainLooper()).postDelayed({ once(null) }, wait)
        }

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

        /**
         * The page the book last closed was left at, the book, and how the page looked ([look]),
         * for the book to open on again at once, while it is the last one closed.
         */
        private var kept: Triple<String, String, Bitmap>? = null

        /** Keeps [print], the page [file] was left at as it looked ([look]), see [kept]. */
        fun keep(file: File, look: String, print: Bitmap) {
            kept = Triple(file.path, look, print)
        }

        /** The page [file] was last left at, if it is kept and looked as it would now ([look]). */
        fun kept(file: File, look: String): Bitmap? = kept?.takeIf { it.first == file.path && it.second == look }?.third

        /** Lets go of the page kept, which is as large as the screen, e.g. as the phone runs short of memory. */
        fun forget() {
            kept = null
        }

        /** How long the print takes to come onto a page that opened before the reader had pictured it. */
        private const val PRINT_FADE_MS = 150L

        /** The longest the shelf waits for a reader started behind a book opening on it, see [Handoff.waiting]. */
        private const val WAIT_MS = 5000L

        private const val OPEN_MS = 760L

        /** The longest a cover that only grows waits for the reader to picture its menu before it grows without. */
        private const val MENU_WAIT_MS = 450L
        private const val FADE_MS = 180L

        /** The part of the time the cover takes to swing open, and the part after which the page starts to grow. */
        private const val SWING = 0.62f
        private const val ZOOM_FROM = 0.3f
        private const val OPEN_ANGLE = 172f

        /** The part of the way after which the paper comes about a cover that only grows. */
        private const val PAPER_FROM = 0.6f

        /** How many widths of the screen away the cover is seen from as it swings towards the eye. */
        private const val CAMERA = 5f

        /** The endpaper on the inside of the cover. */
        private const val INSIDE = 0xFFD9CDB4.toInt()

        /** How thick the board of a cover is, a part of the height of the book. */
        private const val THICKNESS = 0.03f

        /** How dark the inside of the cover is as it stands edge-on, against facing the eye. */
        private const val INSIDE_DARK = 0.7f

        /** How far beyond the book, in its sizes, the open cover may reach. */
        private const val SPREAD = 4f

        /** [color] with its light made [by] times as much. */
        private fun shade(color: Int, by: Float) =
            Color.rgb((Color.red(color) * by).toInt(), (Color.green(color) * by).toInt(), (Color.blue(color) * by).toInt())

        /** The longest a book waits for its first frame to be shown before it starts to open or close all the same. */
        private const val FRAME_WAIT_MS = 250L

        /** How much darker the edge of the board is than the cover, turned a little away from the light. */
        private const val EDGE_SHADE = 0.85f

        /**
         * The edge of the board of the cover at [spot]: the colour of the cover along its outer
         * edge as it shows on the shelf, which wraps round the board, a little in shade.
         */
        private fun foreEdge(spot: Spot): Int {
            var r = 0
            var g = 0
            var b = 0
            try {
                // A picture the drawing hardware keeps cannot be looked into, but a copy of it can.
                val picture = if (Build.VERSION.SDK_INT >= 26 && spot.picture.config == Bitmap.Config.HARDWARE) {
                    spot.picture.copy(Bitmap.Config.ARGB_8888, false) ?: return EDGE
                } else {
                    spot.picture
                }
                // Where the outer edge of the cover on the shelf is in its picture.
                val back = Matrix()
                spot.fit?.invert(back)
                val at = FloatArray(2)
                for (i in 0 until SAMPLES) {
                    at[0] = spot.w - 1.5f
                    at[1] = spot.h * (i + 0.5f) / SAMPLES
                    if (spot.fit != null) {
                        back.mapPoints(at)
                    } else {
                        at[0] *= picture.width / spot.w.toFloat()
                        at[1] *= picture.height / spot.h.toFloat()
                    }
                    val c = picture[at[0].toInt().coerceIn(0, picture.width - 1), at[1].toInt().coerceIn(0, picture.height - 1)]
                    r += Color.red(c)
                    g += Color.green(c)
                    b += Color.blue(c)
                }
                if (picture !== spot.picture) picture.recycle()
            } catch (e: Exception) {
                return EDGE
            }
            return shade(Color.rgb(r / SAMPLES, g / SAMPLES, b / SAMPLES), EDGE_SHADE)
        }

        private const val SAMPLES = 9

        /** The edge of a board whose cover cannot be looked into. */
        private const val EDGE = 0xFF3A2A1E.toInt()
        private val EASE = AccelerateDecelerateInterpolator()

        /**
         * Where [from] is on the screen and what it looks like; null if it has no size or picture.
         * A cover is shown by its own picture rather than a snapshot: the covers are pictures the
         * drawing hardware keeps, and some phones snapshot those as nothing at all.
         */
        private fun spot(from: View): Spot? {
            if (from.width == 0 || from.height == 0) return null
            val at = IntArray(2)
            from.getLocationOnScreen(at)
            val image = from as? ImageView
            val own = (image?.drawable as? BitmapDrawable)?.bitmap
            if (image != null && own != null) {
                val fit = Matrix()
                if (image.scaleType == ImageView.ScaleType.FIT_XY) {
                    val inner = RectF(0f, 0f, (image.width - image.paddingLeft - image.paddingRight).toFloat(), (image.height - image.paddingTop - image.paddingBottom).toFloat())
                    fit.setRectToRect(RectF(0f, 0f, own.width.toFloat(), own.height.toFloat()), inner, Matrix.ScaleToFit.FILL)
                } else {
                    fit.set(image.imageMatrix)
                }
                fit.postTranslate(image.paddingLeft.toFloat(), image.paddingTop.toFloat())
                return Spot(at[0], at[1], from.width, from.height, own, fit, (from as? RatioImageView)?.binding == true)
            }
            return Spot(at[0], at[1], from.width, from.height, from.snapshot() ?: return null)
        }
    }
}
