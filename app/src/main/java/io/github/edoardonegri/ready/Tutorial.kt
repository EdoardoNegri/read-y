package io.github.edoardonegri.ready

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * A tour of the app that is shown once, the first time it is used: the screen goes dim, a wooden
 * board at the foot of the screen says what to do next, and the thing it is about is left lit.
 * Most steps wait for the reader to do what the board asks, such as opening the menu or holding a
 * book, which the screen tells the tour of ([happened]); touches reach only what is lit, so the
 * reader cannot lose the way. The board has Skip on its left and Next on its right, always: Next
 * goes on without waiting, Skip ends the tour for good, and so does the last step.
 *
 * The board is a window of its own, made anew for each step, so that it is always the topmost:
 * over the menu and the dialogs the tour leads to, and over the keyboard.
 */
class Tutorial private constructor(
    private val act: ComponentActivity,
    private val key: String,
    private val steps: List<Step>,
    private val onFinished: () -> Unit,
) {

    /** Things the reader does that a step may wait for. */
    enum class Event { MENU_OPENED, MENU_CLOSED, SEARCH_OPENED, SEARCH_CLOSED, PICKED, LET_GO, ENTERED, LEFT_SHELF, PAGE_FORWARD, PAGE_BACK, BOOK_OPENED, BOOK_RESUMED, READER_LEFT, READER_MENU_OPENED, READER_MENU_CLOSED, DETAILS_OPENED, DETAILS_CLOSED, SETTINGS_OPENED }

    /** What a touch outside the board does: nothing, or reaches the screen only on what is lit, or anywhere. */
    enum class Touch { BLOCK, LIT, ALL }

    /**
     * One thing to say, with [text]. [target] is what to light, in the window, and may say nothing if what it
     * lights is not there; the step [awaits] an event, or only has the reader tap Next. [ready] says whether the
     * screen is where the step belongs, e.g. whether the menu is up for the step that talks about it; if
     * not, by default when something else is on top of the app, the tour goes out of sight until it is.
     * [available] says whether there is anything to do at all, e.g. whether there is a book to hold: if not,
     * the step is left out. [perform] does what the step asks for the reader, when they tap Next instead, so that
     * the next step finds the screen where it belongs, e.g. opens the menu for the step that talks about it.
     * [hopeful] says that a step which is not available yet may be in a moment, e.g. a book that is on the shelf
     * but not drawn yet: it is waited for a little, all the steps in a row together, before they are left out.
     * [next] words the button that goes on, if not Next (or Got it, for the last step); [jump] says which step an
     * event other than the one waited for takes the tour to, e.g. when the reader finds the menu before it is asked for.
     * The board stands at the top of the screen if the step is [top], where the screen has something at its foot the
     * reader needs to see. [glow] gives a view, in a window above the tour's, that is ringed in place of a lit part.
     * [prepare] makes what the step is about, if it is not there yet, e.g. the book it has the reader open, as the
     * step comes rather than when the tour starts; it says whether that is still being made, which the step waits
     * for however long it takes, before it is waited for as a [hopeful] one is.
     */
    class Step(
        val text: Int,
        val target: (() -> Rect?)? = null,
        val awaits: Event? = null,
        val touch: Touch = Touch.BLOCK,
        val ready: (() -> Boolean)? = null,
        val available: () -> Boolean = { true },
        val perform: (() -> Unit)? = null,
        val hopeful: (() -> Boolean)? = null,
        val next: Int? = null,
        val jump: Map<Event, Int>? = null,
        val top: Boolean = false,
        val glow: (() -> View?)? = null,
        val prepare: (() -> Boolean)? = null,
    )

    private val d = act.resources.displayMetrics.density
    private val overlay = Overlay(act)
    private val panel = LinearLayout(act)
    private val text = TextView(act)
    private val next = TextView(act, null, 0, R.style.BrassButton)
    private val skip = TextView(act)
    private var board: Dialog? = null
    private var at = -1
    private var done = false

    /** The tour is still going on. */
    val active get() = !done

    /** The tour is out of sight, because the reader is somewhere that is not where the step belongs. */
    private var hidden = false

    /** The tour is on its way to the next step, which is not shown yet. */
    private var waiting = false

    /** How much of the foot of the window the keyboard takes, which the board stands above. */
    private var imeBottom = 0

    private fun dp(v: Int) = (v * d).toInt()

    /** How far the board stands from the foot of the window. */
    private fun lift() = if (onTop) topInset() + dp(BOARD_LIFT_DP) else imeBottom + dp(BOARD_LIFT_DP)

    /** Whether the board stands at the top of the screen, as the step now shown has it. */
    private var onTop = false

    /** How much of the top of the window the status bar and the camera take. */
    private fun topInset() = ViewCompat.getRootWindowInsets(overlay)
        ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: dp(24)

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.background = ContextCompat.getDrawable(act, R.drawable.panel_wood)
        panel.clipToOutline = true
        panel.setPadding(dp(20), dp(18), dp(20), dp(14))
        text.apply {
            typeface = Typeface.SERIF
            setTextColor(ContextCompat.getColor(act, R.color.cream))
            textSize = 16f
            setLineSpacing(3 * d, 1f)
        }
        next.apply {
            background = PlaqueDrawable(d)
            setPadding(dp(24), 0, dp(24), dp(2))
            setOnClickListener { advance() }
        }
        skip.apply {
            typeface = Typeface.SERIF
            setText(R.string.tutorial_skip)
            setTextColor(ContextCompat.getColor(act, R.color.gold))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(14), dp(8))
            setOnClickListener { finish() }
        }
        // Skip on the left and Next on the right, whatever the step.
        val buttons = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(skip)
            addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))
            addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        }
        panel.addView(text)
        panel.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime != imeBottom) {
                imeBottom = ime
                board?.window?.let { it.attributes = it.attributes.also { lp -> lp.y = lift() } }
            }
            insets
        }
    }

    /**
     * While the app is not on the screen nothing is looked at, and the tour is looked at again when it is.
     * The tour is not kept past its end.
     */
    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            overlay.removeCallbacks(watcher)
            if (!done) overlay.postDelayed(watcher, WATCH_MS)
        }

        override fun onStop(owner: LifecycleOwner) {
            overlay.removeCallbacks(watcher)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            done = true
            board?.dismiss()
            lowerHalo()
        }
    }

    /** Back ends the tour as Skip does, rather than reaching what is under it. */
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = finish()
    }

    private fun show() {
        act.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        act.onBackPressedDispatcher.addCallback(act, back)
        go(0, soon = false)
        act.lifecycle.addObserver(lifecycle)
    }

    /**
     * Next: does for the reader what the step asks, if it can, and goes on to the next step, which then
     * finds the screen where it belongs. What is done tells the tour itself, a moment later at most.
     */
    private fun advance() {
        val step = steps.getOrNull(at) ?: return
        val before = at
        // The screen changes meanwhile, which is not the reader going somewhere else.
        waiting = true
        step.perform?.invoke()
        if (done || at != before) return
        if (step.perform != null && step.awaits != null) {
            overlay.postDelayed({ if (!done && at == before) go(before + 1, soon = true) }, PERFORM_WAIT_MS)
        } else {
            go(before + 1, soon = step.perform != null)
        }
    }

    private fun isReady(index: Int) = steps[index].ready?.invoke() ?: act.hasWindowFocus()

    /**
     * Keeps an eye on where the reader is. Away from where the step belongs, e.g. in a menu or a
     * screen the tour did not lead to, it goes out of sight; and when the reader comes back to where
     * the step before it belongs, which is where they were taken from, it goes back to that.
     */
    private val watcher = object : Runnable {
        override fun run() {
            if (done) return
            check()
            overlay.postDelayed(this, WATCH_MS)
        }
    }

    private fun check() {
        if (waiting || at !in steps.indices) return
        if (isReady(at)) {
            // What is lit may have moved since, as a shelf settles after an animation: lit where it is now.
            if (hidden) arrive(at) else light(steps[at])
            return
        }
        conceal()
        // Back only to a step that asks for something to be done, and only a few steps: not to wherever is ready.
        val before = (at - 1 downTo maxOf(0, at - RETREAT)).firstOrNull { steps[it].awaits != null && steps[it].available() && isReady(it) }
        if (before != null) {
            at = before
            arrive(before)
        }
    }

    private fun conceal() {
        if (hidden) return
        hidden = true
        back.isEnabled = false
        overlay.visibility = View.GONE
        board?.dismiss()
        board = null
        lowerHalo()
    }

    /** Something the reader does: if the step now shown waits for it, the tour goes on. */
    fun happened(event: Event) {
        if (done) return
        val step = steps.getOrNull(at) ?: return
        if (step.awaits == event) go(at + 1, soon = true) else step.jump?.get(event)?.let { go(it, soon = true) }
    }

    /**
     * Goes on to the step [from], or the first after it that there is anything to do for. After
     * something the reader did the screen is given a moment to change first, with the board away.
     */
    private fun go(from: Int, soon: Boolean) {
        if (done) return
        // Past the last step: over at once, whether or not the screen the tour was on is still there.
        if (from >= steps.size) return finish()
        at = from
        waiting = true
        hidden = false
        overlay.visibility = View.VISIBLE
        overlay.lit = null
        overlay.touch = Touch.BLOCK
        board?.dismiss()
        board = null
        lowerHalo()
        overlay.postDelayed({ arrive(from) }, if (soon) SETTLE_MS else 0)
    }

    private fun arrive(from: Int, waited: Int = 0) {
        if (done || at != from || act.isFinishing || act.isDestroyed) return
        val first = steps.getOrNull(from) ?: return finish()
        val making = first.prepare?.invoke() == true
        if (!first.available()) {
            if (making || first.hopeful?.invoke() == true && waited < PATIENCE) {
                overlay.postDelayed({ arrive(from, if (making) waited else waited + 1) }, PATIENCE_MS)
                return
            }
            // Nothing to do for this one: on to the next.
            at = from + 1
            return arrive(from + 1, waited)
        }
        waiting = false
        hidden = false
        back.isEnabled = true
        overlay.visibility = View.VISIBLE
        var i = from
        while (i < steps.size && !steps[i].available()) i++
        if (i >= steps.size) return finish()
        at = i
        val step = steps[i]
        text.setText(step.text)
        next.setText(step.next ?: if (i == steps.lastIndex) R.string.tutorial_done else R.string.tutorial_next)
        // The last step ends the tour, as Skip would.
        skip.visibility = if (i == steps.lastIndex && step.next == null) View.INVISIBLE else View.VISIBLE
        // Reached while the reader is elsewhere, it waits out of sight until they are where it belongs.
        if (!isReady(i)) return conceal()
        val width = minOf(overlay.width - dp(40), dp(BOARD_MAX_DP))
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        onTop = step.top
        if (onTop) {
            boardFrom = lift()
            boardTo = lift() + panel.measuredHeight
        } else {
            boardFrom = overlay.height - lift() - panel.measuredHeight
            boardTo = overlay.height - lift()
        }
        light(step)
        raise(width)
        step.glow?.invoke()?.let(::ring)
    }

    /** Where the board stands, from the top of the window, which what is lit leaves out. */
    private var boardFrom = 0
    private var boardTo = 0

    /** Lights [step]'s target, leaving out what the board would hide. */
    private fun light(step: Step) {
        val room = IntArray(2).also(overlay::getLocationInWindow)
        val pad = dp(6)
        val lit = step.target?.invoke()?.let {
            RectF(
                (it.left - room[0] - pad).toFloat(), (it.top - room[1] - pad).toFloat(),
                (it.right - room[0] + pad).toFloat(), (it.bottom - room[1] + pad).toFloat(),
            )
        }
        if (lit != null) {
            // What stands at the edge of the screen is lit up to the edge, not past it, and not under the board.
            if (onTop) {
                val shown = (boardTo + dp(10)).toFloat()
                if (lit.top < shown && lit.bottom > shown + dp(40)) lit.top = shown
            } else {
                val shown = (boardFrom - dp(10)).toFloat()
                if (lit.bottom > shown && lit.top < shown - dp(40)) lit.bottom = shown
            }
            lit.intersect(dp(3).toFloat(), dp(3).toFloat(), (overlay.width - dp(3)).toFloat(), (overlay.height - dp(3)).toFloat())
        }
        // Nothing is drawn again for what has not moved.
        if (lit != overlay.lit) {
            overlay.lit = lit
            // A touch passes where the thing itself is, not the space round it that is lit for show.
            overlay.hit = lit?.let { RectF(it).apply { inset(pad.toFloat(), pad.toFloat()) } }
        }
        val touch = if (lit == null && step.touch == Touch.LIT) Touch.BLOCK else step.touch
        if (touch != overlay.touch) overlay.touch = touch
    }

    /** Puts the board up, in a window made now, which is the newest and so on top of the others. */
    private fun raise(width: Int) {
        (panel.parent as? ViewGroup)?.removeView(panel)
        val window = Dialog(act)
        window.requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setCancelable(false)
        window.setContentView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        window.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Touches beside the board are for what is under it.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            setGravity((if (onTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL)
        }
        window.show()
        window.window?.apply {
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.also { it.y = lift() }
        }
        board = window
    }

    /** The ring round a view of a window above the tour's, which is a window of its own, a child of that one. */
    private var halo: View? = null

    /**
     * Rings [view], which stands in a window above the tour's, where the tour cannot light it: the ring is a
     * window over that one, with nothing in it but the ring, and touches go through it.
     */
    private fun ring(view: View) {
        val token = view.windowToken ?: return
        val at = IntArray(2).also(view::getLocationOnScreen)
        val pad = dp(6)
        val around = RectF(
            (at[0] - pad).toFloat(), (at[1] - pad).toFloat(),
            (at[0] + view.width + pad).toFloat(), (at[1] + view.height + pad).toFloat(),
        )
        val ring = Halo(act, around)
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { this.token = token }
        try {
            act.windowManager.addView(ring, params)
            halo = ring
        } catch (e: WindowManager.BadTokenException) {
            // The window it was to stand over is gone already.
        }
    }

    private fun lowerHalo() {
        val ring = halo ?: return
        halo = null
        // Gone already, with the window it stood over.
        try {
            act.windowManager.removeViewImmediate(ring)
        } catch (e: IllegalArgumentException) {
        }
    }

    /** A rounded ring that breathes at [around], given in the coordinates of the screen. */
    private class Halo(ctx: Context, private val around: RectF) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ContextCompat.getColor(ctx, R.color.gold_light)
            strokeWidth = 3 * resources.displayMetrics.density
        }
        private val breathe = ValueAnimator.ofInt(110, 255).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                paint.alpha = it.animatedValue as Int
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            breathe.start()
        }

        override fun onDetachedFromWindow() {
            breathe.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            // The window may not start at the corner of the screen.
            val at = IntArray(2).also(::getLocationOnScreen)
            canvas.translate(-at[0].toFloat(), -at[1].toFloat())
            val round = 14 * resources.displayMetrics.density
            canvas.drawRoundRect(around, round, round, paint)
        }
    }

    private fun finish() {
        if (done) return
        done = true
        overlay.removeCallbacks(watcher)
        act.lifecycle.removeObserver(lifecycle)
        back.remove()
        board?.dismiss()
        board = null
        lowerHalo()
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        tourDone(act, key)
        onFinished()
    }

    /** The dim screen, with a hole round what is being talked about, which lets touches through. */
    private class Overlay(ctx: Context) : FrameLayout(ctx) {
        var lit: RectF? = null
            set(value) {
                field = value
                breathing()
                invalidate()
            }

        var touch = Touch.BLOCK
            set(value) {
                field = value
                invalidate()
            }

        /** Where a touch passes, when it does so only where it is lit. */
        var hit: RectF? = null

        private val dim = Paint().apply { color = Color.argb(0xCC, 0, 0, 0) }
        private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ContextCompat.getColor(ctx, R.color.gold_light)
            strokeWidth = 2.5f * resources.displayMetrics.density
        }

        /** The ring round what is lit breathes, to show that it is what is to be touched: 0..1. */
        private var glow = 0f
        private val breathe = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                glow = it.animatedValue as Float
                if (lit != null) invalidate()
            }
        }

        init {
            setWillNotDraw(false)
            // The hole is cut out of the dimming, which needs a layer of its own.
            setLayerType(LAYER_TYPE_HARDWARE, null)
            isClickable = true
        }

        /** The ring breathes only while it is lit and on the screen: no frame is asked for otherwise. */
        private fun breathing() {
            if (lit != null && isShown) {
                if (!breathe.isStarted) breathe.start()
            } else if (breathe.isStarted) {
                breathe.cancel()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            breathing()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            breathing()
        }

        override fun onDetachedFromWindow() {
            breathe.cancel()
            super.onDetachedFromWindow()
        }

        /** Whether the touch that begins at [e] is for what is under the tour: where it is lit, or anywhere, as the step says. */
        private fun passes(e: MotionEvent) = when (touch) {
            Touch.ALL -> true
            Touch.LIT -> hit?.contains(e.x, e.y) == true
            Touch.BLOCK -> false
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            // Not taking the touch lets the views under this one take it, all the way through.
            if (e.actionMasked == MotionEvent.ACTION_DOWN && passes(e)) return false
            return super.dispatchTouchEvent(e)
        }

        override fun onDraw(canvas: Canvas) {
            val r = lit
            // Where a tap anywhere is what is asked for, nothing is dimmed.
            if (r == null && touch == Touch.ALL) return
            canvas.drawPaint(dim)
            if (r == null) return
            val round = 14 * resources.displayMetrics.density
            canvas.drawRoundRect(r, round, round, hole)
            ring.alpha = (110 + 145 * glow).toInt()
            canvas.drawRoundRect(r, round, round, ring)
        }
    }

    companion object {
        /** The tour of the bookshelf, and of the reader, see [seen]. */
        const val SHELF = "seen"
        const val READER = "seen_reader"
        const val SETTINGS = "seen_settings"

        /** How long the screen is given to change after something the reader did, in ms. */
        private const val SETTLE_MS = 350L

        /** How often the tour looks at where the reader is, in ms. */
        private const val WATCH_MS = 300L

        /** How many steps back the tour goes, at most, when the reader comes back to where an earlier one belongs. */
        private const val RETREAT = 3

        /** How long what Next does is given to tell the tour, before it goes on by itself, in ms. */
        private const val PERFORM_WAIT_MS = 700L

        /** How many times, and how far apart in ms, a step that may be available soon is looked at before it is left out. */
        private const val PATIENCE = 10
        private const val PATIENCE_MS = 150L

        /** The widest the board is, and how far it stands from the foot of the screen, in dp. */
        private const val BOARD_MAX_DP = 420
        private const val BOARD_LIFT_DP = 24

        /** Where [view] is in its window, or null if it is not on the screen. */
        fun rectOf(view: View?): Rect? {
            if (view == null || !view.isShown || view.width == 0) return null
            val at = IntArray(2).also(view::getLocationInWindow)
            if (view is RailButton) {
                // Its picture is off the middle of the button (see top_bar.xml): what is lit is round the picture.
                val half = (view.resources.displayMetrics.density * 20).toInt()
                val x = at[0] + (view.paddingLeft + view.width - view.paddingRight) / 2
                val y = at[1] + (view.paddingTop + view.height - view.paddingBottom) / 2
                return Rect(x - half, y - half, x + half, y + half)
            }
            return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
        }

        /** The part of [view] between [from] and [to] of its width, 0..1, e.g. the right side of the page. */
        fun sliceOf(view: View, from: Float, to: Float): Rect? =
            rectOf(view)?.let { Rect(it.left + (it.width() * from).toInt(), it.top, it.left + (it.width() * to).toInt(), it.bottom) }

        /**
         * Shows the tour of [steps] in [act], once: nothing happens, and null is given, if it was shown before.
         * [onFinished] is told when it ends, for it to be let go of.
         */
        fun start(act: ComponentActivity, key: String, steps: List<Step>, onFinished: () -> Unit): Tutorial? {
            if (tourSeen(act, key)) return null
            return Tutorial(act, key, steps, onFinished).also { it.show() }
        }
    }
}
