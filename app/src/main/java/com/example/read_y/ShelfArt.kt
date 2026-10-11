package com.example.read_y

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * The brass title plaque: a bevelled plate with an engraved border, a soft shadow underneath and a
 * slotted screw at each end. Used as the background of the real plaques and drawn straight onto the
 * folder miniatures, so both look the same. [d] is the display density.
 */
class PlaqueDrawable(private val d: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        val shadow = SHADOW_DP * d
        val left = b.left.toFloat()
        val top = b.top.toFloat()
        val right = b.right.toFloat()
        val bottom = b.bottom - shadow

        // Shadow the plate casts on the plank.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0x70000000
        r.set(left + d, top + shadow, right - d, bottom + shadow)
        c.drawRoundRect(r, 3 * d, 3 * d, paint)

        // Dark rim, then the plate: bright where the light hits the top, darker towards the bottom.
        paint.color = 0xFF5E450C.toInt()
        r.set(left, top, right, bottom)
        c.drawRoundRect(r, 3 * d, 3 * d, paint)
        paint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xFFFFF3B4.toInt(), 0xFFE6C24C.toInt(), 0xFFCDA233.toInt(), 0xFF9C7418.toInt()),
            floatArrayOf(0f, 0.3f, 0.72f, 1f), Shader.TileMode.CLAMP,
        )
        r.inset(d, d)
        c.drawRoundRect(r, 2 * d, 2 * d, paint)
        paint.shader = null

        // Bevel: a lit upper edge and a shaded lower edge just inside the rim.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = 0xB0FFFBDC.toInt()
        c.drawLine(left + 3 * d, top + 1.5f * d, right - 3 * d, top + 1.5f * d, paint)
        paint.color = 0x70402C00
        c.drawLine(left + 3 * d, bottom - 1.5f * d, right - 3 * d, bottom - 1.5f * d, paint)

        // Engraved border: a dark groove with a lighter lip just below and right of it.
        paint.strokeWidth = 0.75f * d
        r.set(left + 3.5f * d, top + 3.5f * d, right - 3.5f * d, bottom - 3.5f * d)
        r.offset(0.7f * d, 0.7f * d)
        paint.color = 0x66FFF6C0
        c.drawRoundRect(r, 1.5f * d, 1.5f * d, paint)
        r.offset(-0.7f * d, -0.7f * d)
        paint.color = 0x80503800.toInt()
        c.drawRoundRect(r, 1.5f * d, 1.5f * d, paint)

        val cy = (top + bottom) / 2
        screw(c, left + SCREW_X_DP * d, cy, 0.6f)
        screw(c, right - SCREW_X_DP * d, cy, 2.3f)
    }

    /** A small domed screw head whose slot is turned to [angle] radians. */
    private fun screw(c: Canvas, x: Float, y: Float, angle: Float) {
        val radius = 2.7f * d
        paint.style = Paint.Style.FILL
        paint.color = 0x80302000.toInt()
        c.drawCircle(x + 0.4f * d, y + 0.6f * d, radius + 0.5f * d, paint)
        paint.shader = RadialGradient(
            x - radius * 0.35f, y - radius * 0.4f, radius * 1.5f,
            intArrayOf(0xFFFFF8D0.toInt(), 0xFFC9A038.toInt(), 0xFF6E5210.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(x, y, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.9f * d
        paint.color = 0xFF3E2C06.toInt()
        val dx = cos(angle) * radius * 0.78f
        val dy = sin(angle) * radius * 0.78f
        c.drawLine(x - dx, y - dy, x + dx, y + dy, paint)
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Height at the bottom of the bounds left for the shadow. */
        const val SHADOW_DP = 2

        /** Distance of the screws from the ends, and the space the title must leave for them. */
        const val SCREW_X_DP = 7f
        const val TEXT_INSET_DP = 13
    }
}

object ShelfArt {
    /** Gives a flat cover the look of a bound book: a shaded hinge along the spine and a lit edge. */
    fun drawBinding(c: Canvas, cover: RectF, d: Float, paint: Paint) {
        val hinge = 5 * d
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = LinearGradient(
            cover.left, 0f, cover.left + hinge, 0f,
            intArrayOf(0x66000000, 0x1F000000, 0x40000000), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(cover.left, cover.top, cover.left + hinge, cover.bottom, paint)
        paint.shader = LinearGradient(
            cover.left + hinge, 0f, cover.left + hinge + 2 * d, 0f,
            0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        c.drawRect(cover.left + hinge, cover.top, cover.left + hinge + 2 * d, cover.bottom, paint)
        // Pages catch a little shadow along the far edge and the foot.
        paint.shader = LinearGradient(
            cover.right - 3 * d, 0f, cover.right, 0f, 0x00000000, 0x33000000, Shader.TileMode.CLAMP,
        )
        c.drawRect(cover.right - 3 * d, cover.top, cover.right, cover.bottom, paint)
        paint.shader = null
    }
}
