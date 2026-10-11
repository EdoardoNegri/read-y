package com.example.read_y

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

interface ReaderHost {
    /** [percent] is progress through the whole book, 0..1. */
    fun onPosition(chapter: Int, fraction: Float, percent: Float, label: String)
}

interface ReaderEngine {
    val view: View
    fun next()
    fun prev()

    /** Re-reads [ReaderSettings] and lays the book out again, keeping the position. */
    fun applySettings()

    /** Jumps to a position in the whole book, 0..1. */
    fun seekTo(fraction: Float)

    /** Gives the content a chance to handle a tap (e.g. a link) before it turns the page. */
    fun tap(x: Float, y: Float, done: (handled: Boolean) -> Unit) = done(false)
    fun close()
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
