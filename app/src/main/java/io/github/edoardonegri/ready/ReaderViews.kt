package io.github.edoardonegri.ready

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** Image view whose height is always [ratio] times its width. */
class RatioImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    AppCompatImageView(ctx, attrs) {

    var ratio = 1.5f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /**
     * The height to have whatever the width, if more than 0. The places of a shelf are not all
     * of quite the same width, and what stands in them is to end on one line all the same.
     */
    var tall = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** Draws a book binding (shaded spine hinge) over the image. */
    var binding = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Draws the depth of a bookcase (posts and the shadows inside it) over the image. */
    var cased = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

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
        if (cased) ShelfArt.drawDepth(canvas, area, resources.displayMetrics.density, shade)
    }
}

/**
 * The warm light of the reader: whatever lies under this view is seen in the light of [color],
 * as paper is in the light of a lamp. White light changes nothing and is not drawn at all.
 */
class LampView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var color = Color.WHITE
        set(value) {
            field = value
            visibility = if (value == Color.WHITE) GONE else VISIBLE
            invalidate()
        }

    override fun onDraw(canvas: Canvas) = canvas.drawColor(color, PorterDuff.Mode.MULTIPLY)
}

/**
 * The chapters of a book as marks across the line of the seek bar that lies under this view, each
 * where its chapter begins and with its number above it, the one being read brighter. Tapping a
 * number opens its chapter, and holding a finger on the numbers shows the name of the chapter
 * under it first. The marks are drawn outside this view, so its parent must not clip it.
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
    var current = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The mark the knob of the seek bar is being dragged over, or -1: the lens goes there meanwhile. */
    var pointed = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
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

    /** Every which chapter is numbered along the line; 0 until that is worked out for the marks and the width there are. */
    private var step = 0

    /** Where a finger slides along the numbers, which the lens then follows; NaN while none does. */
    private var finger = Float.NaN

    /** The chapter a finger holds in the lens as it rests, or -1. */
    private var held = -1

    /** The lens as it was last drawn: its outline, the chapter in its first place and the width of a place. */
    private val lens = RectF()
    private var lensFirst = 0
    private var lensPlace = 1f

    private fun x(mark: Float) = inset + mark * (width - 2 * inset)

    private fun sp(size: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)

    private fun nearest(x: Float) = marks.indices.minByOrNull { abs(x(marks[it]) - x) } ?: -1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        step = 0
    }

    /**
     * Every which chapter can be numbered without the numbers running into each other: all of
     * them if there is room, else every second, fifth, tenth and so on, as on a ruler.
     */
    private fun step(): Int {
        if (step > 0) return step
        paint.typeface = BOLD
        paint.textSize = sp(NUMBER_SP)
        val gap = 5 * d
        step = STEPS.firstOrNull { every ->
            var free = Float.NEGATIVE_INFINITY
            marks.indices.filter { (it + 1) % every == 0 }.all { i ->
                val wide = paint.measureText((i + 1).toString()) / 2
                val x = x(marks[i])
                (x - wide >= free).also { free = x + wide + gap }
            }
        } ?: STEPS.last()
        return step
    }

    /**
     * The marks are drawn across the line of the seek bar, which runs below this view: like a
     * ruler's, long where a chapter is numbered and short and faint between. Where not every
     * chapter can be numbered, a lens stands over the one being read and shows it with those
     * beside it, drawn apart and joined to their marks, which stay where the chapters are. It
     * goes with the knob of the seek bar while that is dragged, and grows and follows a finger
     * that slides along the numbers.
     */
    override fun onDraw(canvas: Canvas) {
        if (marks.isEmpty()) return
        val line = height + lineBelow
        val every = step()
        val sliding = !finger.isNaN()
        val focus = (if (sliding) nearest(finger) else if (pointed >= 0) pointed else current).coerceAtMost(marks.lastIndex)
        val lensed = every > 1 && focus >= 0
        lens.setEmpty()
        if (lensed) {
            val beside = if (sliding) 3 else 2
            lensPlace = (if (sliding) 30f else 24f) * d
            lensFirst = (focus - beside).coerceAtLeast(0)
            val last = (focus + beside).coerceAtMost(marks.lastIndex)
            val wide = (last - lensFirst + 1) * lensPlace + 2 * LENS_PAD * d
            // The chapter in the middle stands over its mark, or under the finger, as far as the view has room.
            val over = if (sliding) finger else x(marks[focus])
            val left = (over - (focus - lensFirst + 0.5f) * lensPlace - LENS_PAD * d).coerceIn(0f, (width - wide).coerceAtLeast(0f))
            val bottom = height - 3 * d
            lens.set(left, bottom - (if (sliding) 32f else 23f) * d, left + wide, bottom)
        }

        marks.forEachIndexed { i, mark ->
            val here = i == current
            val numbered = (i + 1) % every == 0
            val half = (if (here) 1.75f else if (numbered) 1.25f else 0.6f) * d
            val reach = (if (here) 7f else if (numbered) 5f else 3f) * d
            val x = x(mark)
            paint.color = if (here) BRIGHT else if (numbered) BRASS else FAINT_BRASS
            shape.set(x - half, line - reach, x + half, line + reach)
            canvas.drawRoundRect(shape, half, half, paint)

            // Its number above it. Without a lens, the chapter being read is numbered too, whichever it is.
            if (!numbered && !(here && !lensed)) return@forEachIndexed
            val number = (i + 1).toString()
            paint.typeface = if (here) BOLD else PLAIN
            paint.textSize = sp(NUMBER_SP)
            val wide = paint.measureText(number) / 2 + 3 * d
            if (lensed && x + wide > lens.left && x - wide < lens.right) return@forEachIndexed
            // The numbers are lighter than their marks, to be read against the dark wood.
            if (!here) paint.color = CREAM
            canvas.drawText(number, x, height - 6 * d, paint)
        }
        if (lensed) drawLens(canvas, focus, line, sliding)
    }

    private fun drawLens(canvas: Canvas, focus: Int, line: Float, large: Boolean) {
        val last = lensFirst + ((lens.width() - 2 * LENS_PAD * d) / lensPlace).roundToInt() - 1
        fun middle(i: Int) = lens.left + LENS_PAD * d + (i - lensFirst + 0.5f) * lensPlace
        // Each number is joined to its mark on the line.
        paint.style = Paint.Style.STROKE
        for (i in lensFirst..last) {
            val chosen = i == focus || i == held
            paint.strokeWidth = (if (chosen) 1.25f else 0.75f) * d
            paint.color = if (chosen) BRIGHT else FAINT_BRASS
            canvas.drawLine(middle(i), lens.bottom, x(marks[i]), line - 5 * d, paint)
        }
        // The glass: a little lighter than the wood around it, in a brass ring.
        val round = lens.height() / 2
        paint.style = Paint.Style.FILL
        paint.color = GLASS
        canvas.drawRoundRect(lens, round, round, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.25f * d
        paint.color = BRASS
        canvas.drawRoundRect(lens, round, round, paint)
        paint.style = Paint.Style.FILL
        for (i in lensFirst..last) {
            val chosen = i == focus || i == held
            paint.typeface = if (chosen) BOLD else PLAIN
            paint.textSize = sp(if (large) (if (chosen) 18f else 14f) else (if (chosen) 15f else 12f))
            paint.color = if (chosen) BRIGHT else CREAM
            canvas.drawText((i + 1).toString(), middle(i), lens.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }

    /**
     * While a finger rests on the numbers or slides along them, [onPreview] is told the chapter
     * under it, so that its name can be shown; lifting the finger opens that chapter. A finger
     * put down on the lens chooses among the chapters it shows, which is the way to one of many
     * that lie close together; anywhere else it takes the lens along.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (marks.isEmpty()) return false
        val inLens = event.actionMasked == MotionEvent.ACTION_DOWN && !lens.isEmpty && event.x in lens.left..lens.right
        if (inLens || held >= 0 && event.actionMasked != MotionEvent.ACTION_DOWN) {
            val shown = ((lens.width() - 2 * LENS_PAD * d) / lensPlace).roundToInt()
            held = lensFirst + ((event.x - lens.left - LENS_PAD * d) / lensPlace).toInt().coerceIn(0, shown - 1)
        } else if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            finger = event.x
        }
        val chosen = if (held >= 0) held else nearest(event.x)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> onPreview?.invoke(chosen)
            MotionEvent.ACTION_UP -> {
                letGo()
                performClick()
                onPick?.invoke(chosen)
            }
            MotionEvent.ACTION_CANCEL -> {
                letGo()
                onPreview?.invoke(-1)
            }
        }
        invalidate()
        return true
    }

    private fun letGo() {
        finger = Float.NaN
        held = -1
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        val PLAIN: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val BOLD: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        /** The steps a ruler counts in. */
        val STEPS = intArrayOf(1, 2, 5, 10, 20, 50, 100)
        const val NUMBER_SP = 13f

        /** The room between the rim of the lens and the numbers in it, in dp. */
        const val LENS_PAD = 5f
        const val BRIGHT = 0xFFF6E27A.toInt()
        const val BRASS = 0xFFB8922A.toInt()
        const val FAINT_BRASS = 0x80B8922A.toInt()
        const val CREAM = 0xFFD8C7A0.toInt()
        const val GLASS = 0xFF45301E.toInt()
    }
}

/** Container that reports taps and horizontal swipes, optionally hiding all touches from its children. */
class GestureFrame @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    FrameLayout(ctx, attrs) {

    interface Listener {
        fun onTap(x: Float, y: Float)

        /** [direction] is +1 when the finger moved to the right, -1 to the left. */
        fun onSwipe(direction: Int)
    }

    var listener: Listener? = null

    /** When true the children never see touch events (paged mode). */
    var consumeAll = false

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val swipeMin = 48 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                moved = false
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) moved = true
            MotionEvent.ACTION_POINTER_DOWN -> moved = true
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!moved && ev.eventTime - ev.downTime < 400) {
                    listener?.onTap(ev.x, ev.y)
                } else if (ev.pointerCount == 1 && abs(dx) > swipeMin && abs(dx) > abs(dy) * 1.5f) {
                    listener?.onSwipe(if (dx > 0) 1 else -1)
                }
            }
        }
        return if (consumeAll) true else super.dispatchTouchEvent(ev) || true
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
