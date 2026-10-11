package io.github.edoardonegri.ready

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
 * folder miniatures, so both look the same. [d] is the display density. The [ornate] plate is the
 * one that names the case on its top rail: its corners are scooped out and the engraved border
 * follows them.
 */
class PlaqueDrawable(private val d: Float, private val ornate: Boolean = false) : Drawable() {

    /**
     * The plate of a favorite book: its corners are scooped out like the ornate plate's, only
     * less, and it is held by two stars instead of screws.
     */
    var favorite = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val star = Path()
    private var notch = 0f
    private val r = RectF()

    override fun draw(c: Canvas) {
        if (ornate || favorite) {
            drawScooped(c)
            return
        }
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
        if (finished) {
            tick(c, left + SCREW_X_DP * d, cy)
            tick(c, right - SCREW_X_DP * d, cy)
        } else {
            screw(c, left + SCREW_X_DP * d, cy, 0.6f)
            screw(c, right - SCREW_X_DP * d, cy, 2.3f)
        }
    }

    /** The plate of a book read to the end: a tick takes the place of a screw, or of the second star. */
    var finished = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private fun drawScooped(c: Canvas) {
        notch = (if (ornate) NOTCH_DP else FAVORITE_NOTCH_DP) * d
        val b = bounds
        val shadow = SHADOW_DP * d
        val left = b.left.toFloat()
        val top = b.top.toFloat()
        val right = b.right.toFloat()
        val bottom = b.bottom - shadow
        val cy = (top + bottom) / 2

        // Shadow, dark rim, then the plate, as on the plain plaque.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0x70000000
        c.drawPath(scooped(left + d, top + shadow, right - d, bottom + shadow), paint)
        paint.color = 0xFF5E450C.toInt()
        c.drawPath(scooped(left, top, right, bottom), paint)
        paint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xFFFFF3B4.toInt(), 0xFFE6C24C.toInt(), 0xFFCDA233.toInt(), 0xFF9C7418.toInt()),
            floatArrayOf(0f, 0.3f, 0.72f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(scooped(left + d, top + d, right - d, bottom - d), paint)
        paint.shader = null

        // Bevel along the straight top and bottom edges.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = 0xB0FFFBDC.toInt()
        c.drawLine(left + notch + 2 * d, top + 1.5f * d, right - notch - 2 * d, top + 1.5f * d, paint)
        paint.color = 0x70402C00
        c.drawLine(left + notch + 2 * d, bottom - 1.5f * d, right - notch - 2 * d, bottom - 1.5f * d, paint)

        // Engraved border that follows the scooped corners.
        paint.strokeWidth = 0.75f * d
        scooped(left + 3.5f * d, top + 3.5f * d, right - 3.5f * d, bottom - 3.5f * d)
        paint.color = 0x66FFF6C0
        c.save()
        c.translate(0.7f * d, 0.7f * d)
        c.drawPath(path, paint)
        c.restore()
        paint.color = 0x80503800.toInt()
        c.drawPath(path, paint)

        if (ornate) {
            screw(c, left + ORNATE_SCREW_X_DP * d, cy, 0.6f)
            screw(c, right - ORNATE_SCREW_X_DP * d, cy, 2.3f)
        } else {
            star(c, left + STAR_X_DP * d, cy)
            if (finished) tick(c, right - STAR_X_DP * d, cy) else star(c, right - STAR_X_DP * d, cy)
        }
    }

    /** A domed stud like a screw head, a little larger, with a tick cut into it. */
    private fun tick(c: Canvas, x: Float, y: Float) {
        val radius = 3.9f * d
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
        star.rewind()
        star.moveTo(x - 2f * d, y + 0.2f * d)
        star.lineTo(x - 0.6f * d, y + 1.6f * d)
        star.lineTo(x + 2.1f * d, y - 1.5f * d)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * d
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF3E2C06.toInt()
        c.drawPath(star, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }

    /** A small five-pointed stud, domed like the screws. */
    private fun star(c: Canvas, x: Float, y: Float) {
        val outer = 4.2f * d
        val inner = 1.8f * d
        star.rewind()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.PI * i / 5 - Math.PI / 2
            val px = x + (cos(a) * r).toFloat()
            val py = y + (sin(a) * r).toFloat()
            if (i == 0) star.moveTo(px, py) else star.lineTo(px, py)
        }
        star.close()
        paint.style = Paint.Style.FILL
        paint.color = 0x80302000.toInt()
        c.save()
        c.translate(0.4f * d, 0.6f * d)
        c.drawPath(star, paint)
        c.restore()
        paint.shader = RadialGradient(
            x - outer * 0.3f, y - outer * 0.35f, outer * 1.4f,
            intArrayOf(0xFFFFFBE0.toInt(), 0xFFE0B840.toInt(), 0xFF7A5A12.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(star, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.6f * d
        paint.color = 0xFF3E2C06.toInt()
        c.drawPath(star, paint)
        paint.style = Paint.Style.FILL
    }

    /** Sets [path] to a rectangle whose four corners are cut away by quarter circles of radius [notch]. */
    private fun scooped(l: Float, t: Float, r: Float, b: Float): Path {
        val n = notch
        path.rewind()
        path.moveTo(l + n, t)
        path.lineTo(r - n, t)
        path.arcTo(r - n, t - n, r + n, t + n, 180f, -90f, false)
        path.lineTo(r, b - n)
        path.arcTo(r - n, b - n, r + n, b + n, 270f, -90f, false)
        path.lineTo(l + n, b)
        path.arcTo(l - n, b - n, l + n, b + n, 0f, -90f, false)
        path.lineTo(l, t + n)
        path.arcTo(l - n, t - n, l + n, t + n, 90f, -90f, false)
        path.close()
        return path
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

        /** The ornate plate: radius of its scooped corners, and where its screws sit. */
        const val NOTCH_DP = 8f
        const val ORNATE_SCREW_X_DP = 10f

        /** The favorite's plate: radius of its scooped corners, and where its stars sit. */
        const val FAVORITE_NOTCH_DP = 5f
        const val STAR_X_DP = 8f
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
