package io.github.edoardonegri.ready

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    /** Draws a book binding (shaded spine hinge) over the image. */
    var binding by changes(false, ::invalidate)

    /** Draws the depth of a bookcase (posts and the shadows inside it) over the image. */
    var cased by changes(false, ::invalidate)

    /** The wood of the posts of a bookcase, see [cased]. */
    var wood by changes(Wood.WALNUT, ::invalidate)

    private val shade = Paint()
    private val area = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, if (tall > 0) tall else (w * ratio).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        if (binding) ShelfArt.drawBinding(canvas, area, resources.displayMetrics.density, shade)
        if (cased) {
            shade.colorFilter = wood.filter
            ShelfArt.drawDepth(canvas, area, resources.displayMetrics.density, shade)
            shade.colorFilter = null
        }
    }
}

/**
 * The chapters of a book as marks across the line of the seek bar that lies under this view, each
 * where its chapter begins: like a ruler's, long and numbered every so many chapters and short
 * and faint between, the one being read brighter and numbered too. Sliding a finger along the
 * numbers shows the name of the chapter under it first, and lifting it opens that chapter.
 * Meanwhile, and while the knob of the seek bar is dragged, a lens lies over the numbers and the
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

    /** Where the knob of the seek bar is being dragged, 0..1 along the line, or NaN: the lens shows it meanwhile. */
    var seeking = Float.NaN
        set(value) {
            if (field.isNaN() && value.isNaN() || field == value) return
            field = value
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
    private val glass = Path()

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
        val half = (if (here) 1.75f else if (numbered) 1.25f else 0.6f) * d * scale
        val reach = (if (here) 7f else if (numbered) 5f else 3f) * d * scale
        paint.color = if (here) BRIGHT else if (numbered) BRASS else FAINT_BRASS
        shape.set(x - half, line - reach, x + half, line + reach)
        canvas.drawRoundRect(shape, half, half, paint)
    }

    override fun onDraw(canvas: Canvas) {
        if (marks.isEmpty()) return
        val line = height + lineBelow
        val every = step()
        val at = if (!finger.isNaN()) along(finger) else seeking
        paint.textSize = sp(NUMBER_SP)
        // The chapter being read is numbered too, whichever it is; a number of the ruler that
        // would run into it gives way.
        paint.typeface = BOLD
        val reading = marks.getOrNull(current)?.let { x(it) } ?: Float.NaN
        val readingHalf = if (current >= 0) paint.measureText((current + 1).toString()) / 2 else 0f
        marks.forEachIndexed { i, mark ->
            val here = i == current
            val x = x(mark)
            mark(canvas, i, x, line, 1f, here)
            // Its number above it. The lens is laid over whatever it covers of them.
            if ((i + 1) % every != 0 && !here) return@forEachIndexed
            if (!here && abs(x - reading) < readingHalf + paint.measureText((i + 1).toString()) / 2 + 4 * d) {
                return@forEachIndexed
            }
            paint.typeface = if (here) BOLD else PLAIN
            // The numbers are lighter than their marks, to be read against the dark wood.
            paint.color = if (here) BRIGHT else CREAM
            canvas.drawText((i + 1).toString(), x, baseline, paint)
        }
        if (every > 1 && !at.isNaN()) drawLens(canvas, at)
    }

    /** The lens over the place [at], 0..1 along the line; see the class. */
    private fun drawLens(canvas: Canvas, at: Float) {
        val needle = x(at)
        val wide = (LENS_DP * d).coerceAtMost(width.toFloat())
        val left = (needle - wide / 2).coerceIn(0f, (width - wide).coerceAtLeast(0f))
        paint.textSize = sp(LENS_NUMBER_SP)
        paint.typeface = BOLD
        lens.set(left, baseline + paint.ascent() - 1.5f * d, left + wide, baseline + 11 * d)
        // As many times larger as lets the chapters be told apart where they lie as close as on average.
        val apart = (x(marks.last()) - x(marks.first())) / (marks.size - 1).coerceAtLeast(1)
        val zoom = (MIN_APART_DP * d / apart.coerceAtLeast(0.1f)).coerceIn(1.5f, MAX_ZOOM)
        fun inLens(x: Float) = lens.centerX() + (x - needle) * zoom
        val round = lens.height() / 2
        glass.rewind()
        glass.addRoundRect(lens, round, round, Path.Direction.CW)

        paint.style = Paint.Style.FILL
        paint.color = GLASS
        paint.setShadowLayer(5 * d, 0f, 1.5f * d, 0x99000000.toInt())
        canvas.drawPath(glass, paint)
        paint.clearShadowLayer()
        canvas.withClip(glass) {
            // The line enlarged, as far as the book goes, with its marks across it.
            val inLine = lens.bottom - 5 * d
            paint.color = FAINT_BRASS
            drawRect(
                inLens(x(0f)).coerceAtLeast(lens.left), inLine - 0.5f * d,
                inLens(x(1f)).coerceAtMost(lens.right), inLine + 0.5f * d, paint,
            )
            val chapter = chapterAt(at)
            marks.forEachIndexed { i, mark ->
                val x = inLens(x(mark))
                if (x in lens.left - 4 * d..lens.right + 4 * d) mark(this, i, x, inLine, 0.6f, i == chapter)
            }
            // Only the chapter the place is in is numbered, over its mark, or at the edge of the
            // glass where its mark lies beyond it.
            val number = (chapter + 1).toString()
            val half = paint.measureText(number) / 2 + LENS_PAD_DP * d
            paint.color = BRIGHT
            drawText(number, inLens(x(marks[chapter])).coerceIn(lens.left + half, lens.right - half), baseline, paint)
            // The place itself, which moves on smoothly between the chapters.
            paint.color = NEEDLE
            drawRect(lens.centerX() - 0.75f * d, baseline + 2 * d, lens.centerX() + 0.75f * d, lens.bottom - 2 * d, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.25f * d
        paint.color = BRASS
        canvas.drawPath(glass, paint)
        paint.style = Paint.Style.FILL
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
        const val MIN_APART_DP = 16f

        /** The most the lens enlarges the line. */
        const val MAX_ZOOM = 12f
        const val BRIGHT = 0xFFF6E27A.toInt()
        const val BRASS = 0xFFB8922A.toInt()
        const val FAINT_BRASS = 0x80B8922A.toInt()
        const val CREAM = 0xFFD8C7A0.toInt()
        const val GLASS = 0xFF45301E.toInt()
        const val NEEDLE = 0xFFFFF4C2.toInt()
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
