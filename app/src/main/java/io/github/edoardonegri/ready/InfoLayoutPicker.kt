package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable

/**
 * The reader's page in small, for choosing what is shown around its text, and where: the three
 * places along its top and the three along its bottom ([InfoPlace]) each name what is shown there.
 * A tap on a place opens a list of everything that can be shown, to tick what goes there; ticked
 * elsewhere already, it moves. [onChange] is told after each change, which [s] has by then.
 */
@SuppressLint("ViewConstructor")
class InfoLayoutPicker(ctx: Context, private val s: ReaderSettings, private val onChange: () -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density

    private val sheet = RectF()
    private val slots = Array(InfoPlace.COUNT) { RectF() }

    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = ContextCompat.getColor(ctx, R.color.gold)
    }
    private val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
        color = INK
        alpha = 34
    }
    private val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILLED }
    private val filledRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = ContextCompat.getColor(ctx, R.color.gold_dark)
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = INK
        alpha = 80
        pathEffect = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.gold)
        alpha = 90
    }
    private val names = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val plus = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        alpha = 110
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    /** The place being pressed, or opened in the list; -1 for none. */
    private var touched = -1
    private var list: PopupWindow? = null

    init {
        isClickable = true
        describe()
    }

    /** What is shown at [place], in the order the page shows it. */
    private fun at(place: Int) = InfoItem.entries.filter { s.infoAt(it) == place }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = rim.strokeWidth / 2
        sheet.set(inset, inset, w - inset, h - inset)
        val pad = 8f * d
        val gap = 6f * d
        val wide = (sheet.width() - 2 * pad - 2 * gap) / 3
        val tall = SLOT_DP * d
        for (p in 1 until InfoPlace.COUNT) {
            val column = (p - 1) % 3
            val left = sheet.left + pad + column * (wide + gap)
            val top = if (p <= InfoPlace.TOP_RIGHT) sheet.top + pad else sheet.bottom - pad - tall
            slots[p].set(left, top, left + wide, top + tall)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val corner = 6f * d
        canvas.drawRoundRect(sheet, corner, corner, paper)
        canvas.drawRoundRect(sheet, corner, corner, rim)
        // The text of the page, between the lines above and below it.
        val left = sheet.left + 18f * d
        val right = sheet.right - 18f * d
        val top = slots[InfoPlace.TOP_LEFT].bottom + 14f * d
        val bottom = slots[InfoPlace.BOTTOM_LEFT].top - 14f * d
        val count = ((bottom - top) / (12f * d)).toInt().coerceIn(2, 8)
        for (i in 0 until count) {
            val y = top + (bottom - top) * i / (count - 1)
            canvas.drawLine(left, y, if (i == count - 1) left + (right - left) * 0.55f else right, y, lines)
        }
        val round = 5f * d
        for (p in 1 until InfoPlace.COUNT) {
            val r = slots[p]
            val here = at(p)
            if (here.isEmpty()) {
                canvas.drawRoundRect(r, round, round, empty)
                canvas.drawText("+", r.centerX(), r.centerY() - (plus.ascent() + plus.descent()) / 2, plus)
            } else {
                canvas.drawRoundRect(r, round, round, filled)
                canvas.drawRoundRect(r, round, round, filledRim)
                label(canvas, r, here)
            }
            if (p == touched) canvas.drawRoundRect(r, round, round, pressedPaint)
        }
    }

    /** Writes the short names of [items] in [r]: one a line, as many as fit, the rest counted. */
    private fun label(canvas: Canvas, r: RectF, items: List<InfoItem>) {
        val room = r.width() - 8f * d
        val line = names.fontSpacing
        val fit = ((r.height() - 4f * d) / line).toInt().coerceAtLeast(1)
        val shown = if (items.size <= fit) items.map { context.getString(it.short) }
        else items.take(fit - 1).map { context.getString(it.short) } + "+${items.size - fit + 1}"
        val first = r.centerY() - line * (shown.size - 1) / 2 - (names.ascent() + names.descent()) / 2
        shown.forEachIndexed { i, text ->
            val t = TextUtils.ellipsize(text, names, room, TextUtils.TruncateAt.END).toString()
            canvas.drawText(t, r.centerX() - names.measureText(t) / 2, first + i * line, names)
        }
    }

    /** The place whose spot holds the point ([x], [y]), a little beyond its edges too; -1 for none. */
    private fun placeAt(x: Float, y: Float): Int {
        val reach = 6f * d
        return (1 until InfoPlace.COUNT).firstOrNull {
            val r = slots[it]
            x >= r.left - reach && x <= r.right + reach && y >= r.top - reach && y <= r.bottom + reach
        } ?: -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touched = placeAt(event.x, event.y)
                invalidate()
                if (touched < 0) return false
            }
            MotionEvent.ACTION_UP -> {
                val p = placeAt(event.x, event.y)
                if (p == touched && p > 0) {
                    performClick()
                    open(p)
                } else {
                    touched = -1
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                touched = -1
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Opens the list of what can be shown under the place [p], to tick what goes there. */
    private fun open(p: Int) {
        list?.dismiss()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }
        column.addView(TextView(context).apply {
            text = context.getString(InfoPlace.NAMES[p])
            textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.gold_light))
            setPadding(dp(16), dp(8), dp(16), dp(6))
        })
        column.addView(
            View(context).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.gold_dark)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(16), 0, dp(16), dp(4)) },
        )
        val rows = InfoItem.entries.map { item -> row(item, p).also(column::addView) }
        fun refresh() = InfoItem.entries.forEachIndexed { i, item -> fill(rows[i], item, p) }
        rows.forEachIndexed { i, row ->
            row.setOnClickListener {
                val item = InfoItem.entries[i]
                s.setInfoAt(item, if (s.infoAt(item) == p) InfoPlace.HIDDEN else p)
                refresh()
                changed()
            }
        }
        refresh()
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundResource(R.drawable.panel_wood)
            // The grain of the board ends at its round corners.
            clipToOutline = true
            addView(column)
        }
        val wide = dp(LIST_DP).coerceAtMost(width)
        scroll.measure(MeasureSpec.makeMeasureSpec(wide, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        // Under the place tapped, as far over as it is, kept within the page; over it where there
        // is no room under it, and otherwise as near as it can be seen whole, or as tall as the
        // screen leaves room for, and scrolled.
        val screen = Rect()
        getWindowVisibleDisplayFrame(screen)
        val margin = dp(8)
        val tall = scroll.measuredHeight.coerceAtMost(screen.height() - 2 * margin)
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        val r = slots[p]
        val x = origin[0] + (r.centerX() - wide / 2f).toInt().coerceIn(0, width - wide)
        val under = origin[1] + r.bottom.toInt() + dp(4)
        val over = origin[1] + r.top.toInt() - dp(4) - tall
        val y = when {
            under + tall <= screen.bottom - margin -> under
            over >= screen.top + margin -> over
            else -> under.coerceAtMost(screen.bottom - margin - tall).coerceAtLeast(screen.top + margin)
        }
        list = PopupWindow(scroll, wide, tall, true).apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            elevation = 8 * d
            isOutsideTouchable = true
            setOnDismissListener {
                list = null
                touched = -1
                invalidate()
            }
            showAtLocation(this@InfoLayoutPicker, Gravity.NO_GRAVITY, x, y)
        }
    }

    /** A line of the list for [item], under the place [p]. */
    private fun row(item: InfoItem, p: Int) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(7), dp(16), dp(7))
        addView(ImageView(context), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(TextView(context).apply {
            text = context.getString(item.label)
            textSize = 15f
            typeface = Typeface.SERIF
            setTextColor(ContextCompat.getColor(context, R.color.cream))
        })
        words.addView(TextView(context).apply {
            textSize = 12f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setTextColor(ContextCompat.getColor(context, R.color.gold_dark))
        })
        addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fill(this, item, p)
    }

    /** Shows in [row] whether [item] is at the place [p], and where it is if elsewhere. */
    private fun fill(row: LinearLayout, item: InfoItem, p: Int) {
        val now = s.infoAt(item)
        (row.getChildAt(0) as ImageView).apply {
            if (now == p) setImageResource(R.drawable.ic_check) else setImageDrawable(ring())
        }
        val elsewhere = ((row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView)
        elsewhere.text = if (now == p || now !in 1 until InfoPlace.COUNT) "" else context.getString(InfoPlace.NAMES[now])
        elsewhere.visibility = if (elsewhere.text.isEmpty()) GONE else VISIBLE
    }

    private fun changed() {
        describe()
        invalidate()
        onChange()
    }

    /** Says what is where, for a screen reader. */
    private fun describe() {
        contentDescription = (1 until InfoPlace.COUNT).mapNotNull { p ->
            at(p).takeIf { it.isNotEmpty() }?.let { items ->
                context.getString(InfoPlace.NAMES[p]) + ": " + items.joinToString { context.getString(it.label) }
            }
        }.joinToString("; ")
    }

    /** What a line of the list not ticked shows in place of the tick: the ring it would fill. */
    private fun ring() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(1).coerceAtLeast(2), ContextCompat.getColor(context, R.color.gold_dark))
        setSize(dp(19), dp(19))
    }

    private fun dp(v: Int) = (v * d).toInt()

    override fun onDetachedFromWindow() {
        list?.dismiss()
        super.onDetachedFromWindow()
    }

    companion object {
        /** How tall the page is, how tall each of its places, and how wide the list of what can go there, in dp. */
        const val HEIGHT_DP = 190
        private const val SLOT_DP = 40f
        private const val LIST_DP = 260

        private const val PAPER = 0xFFF8F0DE.toInt()
        private const val FILLED = 0xFFF0DFA8.toInt()
        private const val INK = 0xFF3B2A05.toInt()
    }
}
