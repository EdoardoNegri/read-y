package io.github.edoardonegri.ready

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

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

    /** Draws a book binding (shaded spine hinge) over the image. */
    var binding = false
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
        setMeasuredDimension(w, (w * ratio).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (binding) {
            area.set(0f, 0f, width.toFloat(), height.toFloat())
            ShelfArt.drawBinding(canvas, area, resources.displayMetrics.density, shade)
        }
    }
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

    /** How far the line of the seek bar starts and ends inside its view. */
    var inset = 0

    /** How far below this view the line of the seek bar runs. */
    var lineBelow = 0f

    var onPick: ((Int) -> Unit)? = null

    /** Told the mark a finger is on, or -1 when it has left without choosing one. */
    var onPreview: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val shape = RectF()

    private fun x(mark: Float) = inset + mark * (width - 2 * inset)

    override fun onDraw(canvas: Canvas) {
        // The line of the seek bar runs below this view, which draws its marks across it.
        val line = height + lineBelow
        var free = Float.NEGATIVE_INFINITY
        marks.forEachIndexed { i, mark ->
            val here = i == current
            val half = (if (here) 1.75f else 1.25f) * d
            val reach = (if (here) 7f else 5f) * d
            val x = x(mark)
            paint.color = if (here) 0xFFF6E27A.toInt() else 0xFFB8922A.toInt()
            shape.set(x - half, line - reach, x + half, line + reach)
            canvas.drawRoundRect(shape, half, half, paint)

            // Its number above it, unless it would run into the one before; the chapter being
            // read is always numbered.
            val number = (i + 1).toString()
            val w = paint.measureText(number)
            if (here || x - w / 2 >= free) {
                paint.typeface = if (here) BOLD else PLAIN
                canvas.drawText(number, x, height - 5 * d, paint)
                free = x + w / 2 + 5 * d
            }
        }
    }

    /**
     * While a finger rests on the numbers or slides along them, [onPreview] is told the chapter
     * under it, so that its name can be shown; lifting the finger opens that chapter.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (marks.isEmpty()) return false
        val nearest = marks.indices.minByOrNull { abs(x(marks[it]) - event.x) } ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> onPreview?.invoke(nearest)
            MotionEvent.ACTION_UP -> {
                performClick()
                onPick?.invoke(nearest)
            }
            MotionEvent.ACTION_CANCEL -> onPreview?.invoke(-1)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        val PLAIN: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val BOLD: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
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
