package io.github.edoardonegri.ready

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Display
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.properties.Delegates

/**
 * A picture of this view as it is drawn now, on [reuse] if that has the size for it. Null if the
 * view has no size yet, or there is no room for a picture of it.
 */
fun View.picture(reuse: Bitmap? = null, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
    if (width == 0 || height == 0) return null
    return try {
        val bmp = reuse?.takeIf { it.width == width && it.height == height } ?: createBitmap(width, height, config)
        draw(Canvas(bmp))
        bmp
    } catch (e: OutOfMemoryError) {
        null
    }
}

/**
 * A picture of this view as it is drawn now, which, unlike [picture], may show pictures that the
 * drawing hardware keeps (the covers on the shelf, Library.cover); it is kept there itself then,
 * to be shown but not drawn on. Null if the view has no size yet, or a picture cannot be had.
 */
fun View.snapshot(): Bitmap? {
    if (Build.VERSION.SDK_INT < 28) return picture()
    if (width == 0 || height == 0) return null
    return try {
        val recorded = Picture()
        draw(recorded.beginRecording(width, height))
        recorded.endRecording()
        Bitmap.createBitmap(recorded)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/**
 * This picture as the drawing hardware keeps it, made ready to be drawn now rather than the first
 * time it is drawn, which takes the frame that draws it a moment longer: one shown in the middle of
 * the book opening would hold it up. Itself where it cannot be.
 */
fun Bitmap.uploaded(cover: Boolean = false): Bitmap {
    if (Build.VERSION.SDK_INT < 26 || config == Bitmap.Config.HARDWARE) return this
    val up = copy(Bitmap.Config.HARDWARE, false) ?: return this
    // A cover is shown with its crease, whose colours are those of its edge, which cannot be looked into once it is kept there.
    if (cover) ShelfArt.keepBookEdge(this, up)
    return up
}

/** A property that calls [then] whenever it is set to something other than it was, e.g. to draw a view anew. */
fun <T> changes(initial: T, then: () -> Unit) = Delegates.observable(initial) { _, old, new -> if (old != new) then() }

/** Image view whose height is always [ratio] times its width. */
class RatioImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    AppCompatImageView(ctx, attrs) {

    var ratio by changes(1.5f, ::requestLayout)

    /**
     * The height to have whatever the width, if more than 0. The places of a shelf are not all
     * of quite the same width, and what stands in them is to end on one line all the same.
     */
    var tall by changes(0, ::requestLayout)

    /** Draws a book binding (the crease along the spine, shaded) with the image: it starts at the crease. */
    var binding by changes(false, ::invalidate)

    /** Draws the depth of a bookcase (posts and the shadows inside it) over the image. */
    var cased by changes(false, ::invalidate)

    private val shade = Paint()
    private val area = RectF()
    private val bound = ViewBinding(this)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, if (tall > 0) tall else (w * ratio).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        if (binding) bound.begin(canvas, area)
        super.onDraw(canvas)
        if (binding) bound.end(canvas, area)
        if (cased) ShelfArt.drawDepth(canvas, area, resources.displayMetrics.density, shade)
    }
}

/**
 * The chapters of a book as marks across the line of the seek bar that lies under this view, each
 * where its chapter begins: like a ruler's, long and numbered every so many chapters and short
 * and faint between. The number of the chapter being read stands on a plate over the knob of the
 * seek bar, the place being read, in front of the numbers of the ruler it passes. Sliding a finger along the
 * numbers shows the name of the chapter under it first, and lifting it opens that chapter.
 * Meanwhile, and while the knob of the seek bar is dragged, the plate grows into a lens over the numbers and the
 * line where the finger or the knob is, and shows that stretch of the line enlarged: its marks as
 * far apart as on the line, only more, the place itself as a needle that moves on between them,
 * and the number of the chapter it is in, level with the numbers outside. The marks and the lens
 * are drawn outside this view, so its parent must not clip it.
 */
class ChapterMarks @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** Where each mark stands, 0..1 along the line. */
    var marks: List<Float> = emptyList()
        set(value) {
            field = value
            step = 0
            invalidate()
        }

    /** The mark of the chapter being read, or -1. */
    var current by changes(-1, ::invalidate)

    /**
     * Whether the book ends on its cover, at the end of the line: the plate that stands over it says
     * so, rather than giving the number of the last chapter.
     */
    var hasEnd = false

    /** Where the place being read is, 0..1 along the line, or NaN: the number of its chapter stands over it. */
    var place by changes(Float.NaN, ::invalidate)

    /** Where the knob of the seek bar is being dragged, 0..1 along the line, or NaN: the lens shows it meanwhile. */
    var seeking = Float.NaN
        set(value) {
            if (field.isNaN() && value.isNaN() || field == value) return
            field = value
            followLens()
            invalidate()
        }

    /** How far the line of the seek bar starts and ends inside its view. */
    var inset = 0

    /** How far below this view the line of the seek bar runs. */
    var lineBelow = 0f

    var onPick: ((Int) -> Unit)? = null

    /** Told the mark a finger is on, or -1 when it has left without choosing one. */
    var onPreview: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val shape = RectF()
    private val lens = RectF()
    private val plate = RectF()
    private val frame = RectF()
    private val glass = Path()

    /** How far the plate of the chapter being read has grown into the lens, 0..1. */
    private var open = 0f

    /** Whether the lens is to be open, which [opening] takes [open] towards. */
    private var lensWanted = false

    private val opening = ValueAnimator().apply {
        duration = LENS_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            open = it.animatedValue as Float
            invalidate()
        }
    }

    /** Where the lens was last over, 0..1 along the line, which it closes from; NaN until it has opened. */
    private var lensAt = Float.NaN

    /** Every which chapter is numbered along the line; 0 until that is worked out for the marks and the width there are. */
    private var step = 0

    /** Where a finger slides along the numbers, which the lens then follows; NaN while none does. */
    private var finger = Float.NaN

    private fun x(mark: Float) = inset + mark * (width - 2 * inset)

    /** How far along the line, 0..1, the point [x] of the view is. */
    private fun along(x: Float) = ((x - inset) / (width - 2 * inset).coerceAtLeast(1)).coerceIn(0f, 1f)

    /** The chapter that the place [at], 0..1 along the line, lies in. */
    private fun chapterAt(at: Float) = marks.indexOfLast { it <= at + 0.0005f }.coerceAtLeast(0)

    private fun sp(size: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)

    /** Where the numbers stand, all on one line, in the lens as outside it. */
    private val baseline get() = height - 6 * d

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        step = 0
    }

    /**
     * Every which chapter can be numbered without the numbers running into each other: all of
     * them if there is room, else every fifth, tenth, fifteenth and so on, as on a ruler.
     */
    private fun step(): Int {
        if (step > 0) return step
        paint.typeface = BOLD
        paint.textSize = sp(NUMBER_SP)
        val gap = 5 * d
        // Past the number of chapters nothing is numbered, which always fits.
        step = generateSequence(1) { if (it == 1) 5 else it + 5 }.first { every ->
            var free = Float.NEGATIVE_INFINITY
            marks.indices.filter { (it + 1) % every == 0 }.all { i ->
                val wide = paint.measureText((i + 1).toString()) / 2
                val x = x(marks[i])
                (x - wide >= free).also { free = x + wide + gap }
            }
        }
        return step
    }

    /** The mark of the chapter [i] at [x], across the line at [line], [scale] times its size; brighter if [here]. */
    private fun mark(canvas: Canvas, i: Int, x: Float, line: Float, scale: Float, here: Boolean) {
        val numbered = (i + 1) % step() == 0
        val long = here || numbered
        val half = (if (long) 1.25f else 0.6f) * d * scale
        val reach = (if (long) 5f else 3f) * d * scale
        paint.color = if (here) BRIGHT else if (numbered) BRASS else FAINT_BRASS
        shape.set(x - half, line - reach, x + half, line + reach)
        canvas.drawRoundRect(shape, half, half, paint)
    }

    override fun onDraw(canvas: Canvas) {
        if (marks.isEmpty()) return
        val line = height + lineBelow
        val every = step()
        val at = if (!finger.isNaN()) along(finger) else seeking
        if (!at.isNaN()) lensAt = at
        paint.textSize = sp(NUMBER_SP)
        marks.forEachIndexed { i, mark ->
            val x = x(mark)
            mark(canvas, i, x, line, 1f, false)
            // Its number above it. The plate and the lens are laid over whatever they cover of them.
            if ((i + 1) % every != 0) return@forEachIndexed
            paint.typeface = PLAIN
            // The numbers are lighter than their marks, to be read against the dark wood.
            paint.color = CREAM
            canvas.drawText((i + 1).toString(), x, baseline, paint)
        }
        drawGlass(canvas)
    }

    /**
     * Opens the lens while a finger is on the numbers or the knob of the seek bar is dragged, and
     * closes it after, growing it out of the plate of the chapter being read and back into it.
     */
    private fun followLens() {
        val at = if (!finger.isNaN()) along(finger) else seeking
        val wanted = marks.isNotEmpty() && width > 0 && step() > 1 && !at.isNaN()
        if (wanted == lensWanted) return
        lensWanted = wanted
        opening.setFloatValues(open, if (wanted) 1f else 0f)
        opening.start()
    }

    override fun onDetachedFromWindow() {
        opening.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * The number of the chapter being read on a plate over the knob of the seek bar, or of the
     * one the knob is dragged over meanwhile, in front of the numbers of the ruler it passes; and
     * the lens it grows into, see the class.
     */
    private fun drawGlass(canvas: Canvas) {
        val chapter = if (!seeking.isNaN()) chapterAt(seeking) else current
        val where = if (!seeking.isNaN()) seeking else place
        val plated = chapter in marks.indices
        val number = if (hasEnd && !where.isNaN() && where >= END_AT) context.getString(R.string.the_end_short) else (chapter + 1).toString()
        paint.typeface = BOLD
        paint.textSize = sp(NUMBER_SP)
        if (plated) {
            val x = if (where.isNaN()) x(marks[chapter]) else x(where)
            val half = paint.measureText(number) / 2 + 4 * d
            val middle = x.coerceIn(half, (width - half).coerceAtLeast(half))
            plate.set(middle - half, baseline + paint.ascent() - 1 * d, middle + half, baseline + paint.descent() + 1 * d)
        }
        val lensed = open > 0f && !lensAt.isNaN()
        if (lensed) placeLens(lensAt)
        if (!plated && !lensed) return
        // The plate, the lens, or as far between them as the one has grown into the other.
        when {
            !lensed -> frame.set(plate)
            !plated -> frame.set(lens)
            else -> frame.set(
                lerp(plate.left, lens.left, open), lerp(plate.top, lens.top, open),
                lerp(plate.right, lens.right, open), lerp(plate.bottom, lens.bottom, open),
            )
        }
        val grown = if (lensed) open else 0f
        val round = frame.height() / 2
        glass.rewind()
        glass.addRoundRect(frame, round, round, Path.Direction.CW)

        paint.style = Paint.Style.FILL
        paint.color = GLASS
        paint.setShadowLayer(lerp(3f, 5f, grown) * d, 0f, lerp(1f, 1.5f, grown) * d, 0x99000000.toInt())
        canvas.drawPath(glass, paint)
        paint.clearShadowLayer()
        // What the lens shows comes into sight as it grows, and the number of the plate fades.
        if (lensed) canvas.withClip(glass) {
            if (open < 1f) saveLayerAlpha(frame, (open * 255).toInt())
            drawLens(this, lensAt)
            if (open < 1f) restore()
        }
        if (plated && grown < 1f) {
            paint.textSize = sp(NUMBER_SP)
            paint.color = NEEDLE_YELLOW
            paint.alpha = ((1 - grown) * 255).toInt()
            canvas.drawText(number, frame.centerX(), baseline, paint)
            paint.alpha = 255
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = lerp(1f, 1.25f, grown) * d
        paint.color = BRASS
        canvas.drawPath(glass, paint)
        paint.style = Paint.Style.FILL
    }

    private fun lerp(from: Float, to: Float, by: Float) = from + (to - from) * by

    /** Sets out [lens] over the place [at], 0..1 along the line. */
    private fun placeLens(at: Float) {
        val wide = (LENS_DP * d).coerceAtMost(width.toFloat())
        val left = (x(at) - wide / 2).coerceIn(0f, (width - wide).coerceAtLeast(0f))
        paint.textSize = sp(LENS_NUMBER_SP)
        lens.set(left, baseline + paint.ascent() - 1.5f * d, left + wide, baseline + 11 * d)
    }

    /** What the lens set out by [placeLens] over the place [at] shows; see the class. */
    private fun drawLens(canvas: Canvas, at: Float) {
        val needle = x(at)
        paint.textSize = sp(LENS_NUMBER_SP)
        paint.typeface = BOLD
        // As many times larger as lets the chapters be told apart where they lie as close as on average.
        val apart = (x(marks.last()) - x(marks.first())) / (marks.size - 1).coerceAtLeast(1)
        val zoom = (MIN_APART_DP * d / apart.coerceAtLeast(0.1f)).coerceIn(1.5f, MAX_ZOOM)
        fun inLens(x: Float) = lens.centerX() + (x - needle) * zoom
        // The line enlarged, as far as the book goes, with its marks across it.
        val inLine = lens.bottom - 5 * d
        paint.color = FAINT_BRASS
        canvas.drawRect(
            inLens(x(0f)).coerceAtLeast(lens.left), inLine - 0.5f * d,
            inLens(x(1f)).coerceAtMost(lens.right), inLine + 0.5f * d, paint,
        )
        val chapter = chapterAt(at)
        marks.forEachIndexed { i, mark ->
            val x = inLens(x(mark))
            if (x in lens.left - 4 * d..lens.right + 4 * d) mark(canvas, i, x, inLine, 0.6f, i == chapter)
        }
        // Only the chapter the place is in is numbered, over its mark, or at the edge of the
        // glass where its mark lies beyond it.
        val number = if (hasEnd && at >= END_AT) context.getString(R.string.the_end_short) else (chapter + 1).toString()
        val half = paint.measureText(number) / 2 + LENS_PAD_DP * d
        paint.color = BRIGHT
        canvas.drawText(number, inLens(x(marks[chapter])).coerceIn(lens.left + half, lens.right - half), baseline, paint)
        // The place itself, which moves on smoothly between the chapters.
        paint.color = NEEDLE
        canvas.drawRect(lens.centerX() - 0.75f * d, baseline + 2 * d, lens.centerX() + 0.75f * d, lens.bottom - 2 * d, paint)
    }

    /**
     * While a finger rests on the numbers or slides along them, [onPreview] is told the chapter
     * it is in, so that its name can be shown, and the lens follows it; lifting the finger opens
     * that chapter.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (marks.isEmpty()) return false
        val chosen = chapterAt(along(event.x))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                finger = event.x
                onPreview?.invoke(chosen)
            }
            MotionEvent.ACTION_UP -> {
                finger = Float.NaN
                performClick()
                onPick?.invoke(chosen)
            }
            MotionEvent.ACTION_CANCEL -> {
                finger = Float.NaN
                onPreview?.invoke(-1)
            }
        }
        followLens()
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        val PLAIN: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val BOLD: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        const val NUMBER_SP = 13f
        const val LENS_NUMBER_SP = 14f

        /** How wide the lens is, how far its number keeps from its rim, and how far apart it shows the chapters at least, in dp. */
        const val LENS_DP = 110f
        const val LENS_PAD_DP = 6f

        /** From how far along the line, 0..1, a place is the end of the book, which is the cover. */
        const val END_AT = 0.9995f
        const val MIN_APART_DP = 16f

        /** The most the lens enlarges the line. */
        const val MAX_ZOOM = 12f
        const val BRIGHT = 0xFFF6E27A.toInt()
        const val BRASS = 0xFFB8922A.toInt()
        const val FAINT_BRASS = 0x80B8922A.toInt()
        const val CREAM = 0xFFD8C7A0.toInt()
        const val GLASS = 0xFF45301E.toInt()
        const val NEEDLE = 0xFFFFF4C2.toInt()

        /** The knob of the seek bar, seek_needle.xml. */
        const val NEEDLE_YELLOW = 0xFFFFD60A.toInt()

        /** How long the plate takes to grow into the lens, or back. */
        const val LENS_MS = 220L
    }
}

/** Container that reports taps and horizontal swipes, optionally hiding all touches from its children. */
class GestureFrame @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    FrameLayout(ctx, attrs) {

    interface Listener {
        fun onTap(x: Float, y: Float)

        /** [direction] is +1 when the finger moved to the right, -1 to the left. */
        fun onSwipe(direction: Int)

        /** Whether the page can be pinched larger, and whether it is, in which case fingers move it about. */
        fun canZoom(): Boolean = false
        fun zoomed(): Boolean = false

        /** A pinch changed the size of the page by [factor] around ([x], [y]), and has ended. */
        fun onZoom(factor: Float, x: Float, y: Float) {}
        fun onZoomEnd() {}

        /** A finger moved the enlarged page by ([dx], [dy]). */
        fun onPan(dx: Float, dy: Float) {}
    }

    var listener: Listener? = null

    /** When true the children never see touch events (paged mode). */
    var consumeAll = false

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val swipeMin = 48 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    /** Whether the page was pinched since the finger went down: nothing then is a tap or a swipe. */
    private var pinched = false

    private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinched = true
            moved = true
            // What lay under the fingers is not scrolled along with them.
            val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super@GestureFrame.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            listener?.onZoom(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            listener?.onZoomEnd()
        }
    }).apply { isQuickScaleEnabled = false }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val l = listener
        val zoomable = l != null && l.canZoom()
        if (zoomable) {
            pinch.onTouchEvent(ev)
            if (pinch.isInProgress) {
                lastX = ev.x
                lastY = ev.y
                return true
            }
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                moved = false
                pinched = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) moved = true
                if (zoomable && l.zoomed() && ev.pointerCount == 1 && moved) l.onPan(ev.x - lastX, ev.y - lastY)
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> moved = true
            MotionEvent.ACTION_POINTER_UP -> {
                // The finger left behind goes on from where it is, not from where the pinch was.
                val index = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(index)
                lastY = ev.getY(index)
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (pinched) {
                    // Nothing to do: the fingers were only moving the page.
                } else if (!moved && ev.eventTime - ev.downTime < 400) {
                    l?.onTap(ev.x, ev.y)
                } else if (ev.pointerCount == 1 && !(zoomable && l.zoomed()) && abs(dx) > swipeMin && abs(dx) > abs(dy) * 1.5f) {
                    l?.onSwipe(if (dx > 0) 1 else -1)
                }
            }
        }
        // An enlarged page is moved about by the fingers, not scrolled by itself under them.
        return consumeAll || zoomable && l?.zoomed() == true || super.dispatchTouchEvent(ev) || true
    }
}

/**
 * Pads the top rail of a bookcase (top_bar.xml) for a status bar [top] tall. The rail keeps its
 * height, and what is on it stands a little above the middle of the wood under the status bar.
 */
fun View.padRail(top: Int) {
    val lift = (6 * resources.displayMetrics.density).toInt().coerceAtMost(top)
    setPadding(0, top - lift, 0, lift)
}

/**
 * Runs the bookcase in [root] edge to edge: its top rail [bar] fills the status bar area, and the
 * rows of [shelf] can scroll clear of the navigation bar.
 */
fun fitBookcase(root: View, bar: View, shelf: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(i.left, 0, i.right, 0)
        bar.padRail(i.top)
        shelf.setPadding(shelf.paddingLeft, 0, shelf.paddingRight, i.bottom)
        insets
    }
}

/**
 * A button of the top rail, whose paddings put its picture off its middle (see top_bar.xml). The
 * ring that shows it being pressed is centred on the picture all the same.
 */
class RailButton(ctx: Context, attrs: AttributeSet?) : AppCompatImageButton(ctx, attrs) {
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val x = (paddingLeft + width - paddingRight) / 2
        val y = (paddingTop + height - paddingBottom) / 2
        val r = height / 2
        background?.setHotspotBounds(x - r, y - r, x + r, y + r)
    }
}

/**
 * The shading the shelf draws along the spine of a cover (ShelfArt.drawBinding), over the cover that
 * ends a book where the reader shows it, so that it looks as it does on the shelf: [box] is where
 * the cover is, or null while it is not shown. The cover is made with its crease in place
 * (ShelfArt.bound), which only needs this shading over it.
 */
class CoverCrease @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint()
    private val area = RectF()

    var box: Rect? = null
        set(value) {
            if (field == value) return
            field = value?.let(::Rect)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        area.set(box ?: return)
        ShelfArt.drawBinding(canvas, area, paint)
    }
}

/**
 * The crease down the middle of two pages side by side (ReaderSettings.spread), where the paper of
 * an open book curves down into its binding: a shadow on either side, deepest in the middle. A
 * book of flowing text has its page draw the same, see [gradient].
 */
class SpineCrease(ctx: Context) : View(ctx) {
    /** The colour of the shadow: black, or on dark pages the colour of their print, a faint seam of light. */
    var color = Color.BLACK
        set(value) {
            if (field == value) return
            field = value
            paint.shader = null
            invalidate()
        }

    private val paint = Paint()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        val mid = width / 2f
        val reach = reach(mid)
        if (paint.shader == null) paint.shader = LinearGradient(mid - reach, 0f, mid + reach, 0f, colors(color), STOPS, Shader.TileMode.CLAMP)
        canvas.drawRect(mid - reach, 0f, mid + reach, height.toFloat(), paint)
    }

    companion object {
        /** How far the crease reaches into each page, a part of its width: as far as the hinge of a cover (ShelfArt.HINGE). */
        fun reach(page: Float) = ShelfArt.HINGE * page

        /** Across the crease, where the shadow is as dark as [ALPHAS] say. */
        private val STOPS = floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f)
        private val ALPHAS = intArrayOf(0, 0x12, 0x45, 0x12, 0)

        private fun colors(color: Int) = IntArray(ALPHAS.size) { color and 0xFFFFFF or (ALPHAS[it] shl 24) }

        /** The crease in [color] as a CSS background, across an element as wide as it reaches on both sides. */
        fun gradient(color: Int) = ALPHAS.indices.joinToString(",", "linear-gradient(to right,", ")") {
            "rgba(%d,%d,%d,%.3f) %.0f%%".format(Locale.US, Color.red(color), Color.green(color), Color.blue(color), ALPHAS[it] / 255f, STOPS[it] * 100)
        }
    }
}

/**
 * The page a book read sideways was left at, kept on the shelf turned [turned] degrees to stand
 * upright there (BookOpening.keep), shown over the reader as it was read until the reader has set
 * out its own: as the screen turns on its side the page is seen as it was, rather than blank.
 * Without one, it is the [paper] the book opened on. A [page] seen upright, with no [turned], is
 * turned as the screen is once it is on its side, to be seen as it was.
 */
class SidewaysPage(ctx: Context, private val page: Bitmap?, private val turned: Float?, paper: Int) : View(ctx) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val to = RectF()

    init {
        setBackgroundColor(paper)
    }

    override fun onDraw(canvas: Canvas) {
        val page = page ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        // Still upright, the screen shows the page as the shelf does.
        if (w <= h) {
            to.set(0f, 0f, w, h)
            canvas.drawBitmap(page, null, to, paint)
            return
        }
        // On its side, the page is turned back as it was read, about the middle of the screen.
        to.set((w - h) / 2f, (h - w) / 2f, (w + h) / 2f, (h + w) / 2f)
        canvas.withRotation(-(turned ?: uprightTurn(display)), w / 2f, h / 2f) { drawBitmap(page, null, to, paint) }
    }
}

/**
 * How far a picture of a screen seen sideways on [display] is turned to stand upright, as the
 * shelf does: a quarter, the way the screen is turned.
 */
fun uprightTurn(display: Display?) = if (display?.rotation == Surface.ROTATION_270) -90f else 90f
