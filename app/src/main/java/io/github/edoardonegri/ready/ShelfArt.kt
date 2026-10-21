package io.github.edoardonegri.ready

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.withTranslation
import kotlin.math.cos
import kotlin.math.sin

/**
 * The brass title plaque: a bevelled plate with an engraved border, a soft shadow underneath and a
 * slotted screw at each end. Used as the background of the real plaques and drawn straight onto the
 * folder miniatures, so both look the same. [d] is the display density. The [ornate] plate is the
 * one that names the case on its top rail: its corners are scooped out and the engraved border
 * follows them. A [favorite] has stars and scooped corners. A [finished]
 * book has the plate it would have anyway, of bronze instead of brass.
 */
class PlaqueDrawable(private val d: Float, private val ornate: Boolean = false) : Drawable() {

    /**
     * The plate of a favorite book: its corners are scooped out like the ornate plate's, only
     * less, and it is held by two stars instead of screws.
     */
    var favorite by changes(false, ::invalidateSelf)

    /** The plain plate as a button that is held down: it turns to bronze, as the plate of a finished book is. */
    var sunk by changes(false, ::invalidateSelf)

    /**
     * The plain plate of a drop-down menu, which has an arrow engraved in place of each screw,
     * a little further in: pointing down, 1, while its choices are closed, and up, -1, while they
     * are open; 0 for the screws.
     */
    var arrows by changes(0, ::invalidateSelf)

    /** The plate of a book read to the end: the same plate, of bronze instead of brass. */
    var finished by changes(false, ::invalidateSelf)

    private val metal get() = if (finished || sunk) BRONZE else BRASS

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val star = Path()
    private var notch = 0f
    private val r = RectF()
    private val face = Path()

    override fun draw(c: Canvas) {
        if (ornate || favorite) {
            drawShaped(c)
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
        val metal = metal
        paint.color = metal.rim
        r.set(left, top, right, bottom)
        c.drawRoundRect(r, 3 * d, 3 * d, paint)
        paint.shader = LinearGradient(0f, top, 0f, bottom, metal.plate, PLATE_STOPS, Shader.TileMode.CLAMP)
        r.inset(d, d)
        c.drawRoundRect(r, 2 * d, 2 * d, paint)
        face.rewind()
        face.addRoundRect(r, 2 * d, 2 * d, Path.Direction.CW)
        gloss(c, face, r.left, r.top, r.right, r.bottom)

        // Bevel: a lit upper edge and a shaded lower edge just inside the rim.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = metal.lit
        c.drawLine(left + 3 * d, top + 1.5f * d, right - 3 * d, top + 1.5f * d, paint)
        paint.color = metal.shade
        c.drawLine(left + 3 * d, bottom - 1.5f * d, right - 3 * d, bottom - 1.5f * d, paint)

        // Engraved border: a dark groove with a lighter lip just below and right of it.
        paint.strokeWidth = 0.75f * d
        r.set(left + 3.5f * d, top + 3.5f * d, right - 3.5f * d, bottom - 3.5f * d)
        r.offset(0.7f * d, 0.7f * d)
        paint.color = metal.lip
        c.drawRoundRect(r, 1.5f * d, 1.5f * d, paint)
        r.offset(-0.7f * d, -0.7f * d)
        paint.color = metal.groove
        c.drawRoundRect(r, 1.5f * d, 1.5f * d, paint)

        val cy = (top + bottom) / 2
        if (arrows != 0) {
            arrow(c, left + ARROW_X_DP * d, cy)
            arrow(c, right - ARROW_X_DP * d, cy)
            return
        }
        screw(c, left + SCREW_X_DP * d, cy, 0.6f)
        screw(c, right - SCREW_X_DP * d, cy, 2.3f)
    }

    /** A small triangle engraved in the plate at ([x], [y]), pointing the way [arrows] says. */
    private fun arrow(c: Canvas, x: Float, y: Float) {
        val half = 4.5f * d
        val tip = 2.9f * d * arrows
        star.rewind()
        star.moveTo(x - half, y - tip)
        star.lineTo(x + half, y - tip)
        star.lineTo(x, y + tip)
        star.close()
        // Cut in like the border: a lighter lip below and right of a dark groove.
        paint.style = Paint.Style.FILL
        paint.color = metal.lip
        c.withTranslation(0.7f * d, 0.7f * d) { c.drawPath(star, paint) }
        paint.color = 0xF0382600.toInt()
        c.drawPath(star, paint)
    }

    private fun drawShaped(c: Canvas) {
        notch = (if (ornate) NOTCH_DP else FAVORITE_NOTCH_DP) * d
        val b = bounds
        val shadow = SHADOW_DP * d
        val left = b.left.toFloat()
        val top = b.top.toFloat()
        val right = b.right.toFloat()
        val bottom = b.bottom - shadow
        val cy = (top + bottom) / 2
        val metal = metal

        // Shadow, dark rim, then the plate, as on the plain plaque.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0x70000000
        c.drawPath(scooped(left + d, top + shadow, right - d, bottom + shadow), paint)
        paint.color = metal.rim
        c.drawPath(scooped(left, top, right, bottom), paint)
        paint.shader = LinearGradient(0f, top, 0f, bottom, metal.plate, PLATE_STOPS, Shader.TileMode.CLAMP)
        c.drawPath(scooped(left + d, top + d, right - d, bottom - d), paint)
        paint.shader = null
        face.set(path)
        gloss(c, face, left + d, top + d, right - d, bottom - d)

        // Bevel along the straight top and bottom edges.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = metal.lit
        c.drawLine(left + notch + 2 * d, top + 1.5f * d, right - notch - 2 * d, top + 1.5f * d, paint)
        paint.color = metal.shade
        c.drawLine(left + notch + 2 * d, bottom - 1.5f * d, right - notch - 2 * d, bottom - 1.5f * d, paint)

        // Engraved border that follows the scooped corners.
        paint.strokeWidth = 0.75f * d
        scooped(left + 3.5f * d, top + 3.5f * d, right - 3.5f * d, bottom - 3.5f * d)
        paint.color = metal.lip
        c.withTranslation(0.7f * d, 0.7f * d) { c.drawPath(path, paint) }
        paint.color = metal.groove
        c.drawPath(path, paint)

        // An ornate plate is held by screws, or by stars where it names a favorite.
        if (!favorite) {
            screw(c, left + ORNATE_SCREW_X_DP * d, cy, 0.6f)
            screw(c, right - ORNATE_SCREW_X_DP * d, cy, 2.3f)
        } else {
            val x = (if (ornate) ORNATE_SCREW_X_DP else STAR_X_DP) * d
            star(c, left + x, cy)
            star(c, right - x, cy)
        }
    }

    /**
     * The roundness of a polished plate, which bulges a little towards the light: a soft highlight that
     * is strongest a little under the top and dies away smoothly before the middle, the lower part
     * growing slightly darker, and the two ends a little darker too. Nothing in it has an edge.
     * [outline] is the face of the plate, in the box [l], [t], [r], [b].
     */
    private fun gloss(c: Canvas, outline: Path, l: Float, t: Float, r: Float, b: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, t, 0f, b, GLOSS_COLORS, GLOSS_STOPS, Shader.TileMode.CLAMP)
        c.drawPath(outline, paint)
        paint.shader = LinearGradient(l, 0f, r, 0f, SIDE_COLORS, SIDE_STOPS, Shader.TileMode.CLAMP)
        c.drawPath(outline, paint)
        paint.shader = null
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
        c.withTranslation(0.4f * d, 0.6f * d) { c.drawPath(star, paint) }
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

        /** Distance of the arrows of a drop-down menu from the ends, a little further in than the screws. */
        const val ARROW_X_DP = 14f

        /** The ornate plate: radius of its scooped corners, and where its screws sit. */
        const val NOTCH_DP = 8f
        const val ORNATE_SCREW_X_DP = 10f

        /** The favorite's plate: radius of its scooped corners, and where its stars sit. */
        const val FAVORITE_NOTCH_DP = 5f
        const val STAR_X_DP = 8f

        /** Where the colours of [Metal.plate] lie, from the top of the plate to its bottom. */
        private val PLATE_STOPS = floatArrayOf(0f, 0.3f, 0.72f, 1f)

        /** The shine over the plate, from its top to its bottom, and the shade at its two ends; see [gloss]. */
        private val GLOSS_COLORS = intArrayOf(0x44FFFFFF, 0x52FFFFFF, 0x24FFFFFF, 0x00FFFFFF, 0x00000000, 0x1C000000)
        private val GLOSS_STOPS = floatArrayOf(0f, 0.16f, 0.4f, 0.62f, 0.7f, 1f)
        private val SIDE_COLORS = intArrayOf(0x1A000000, 0x00000000, 0x00000000, 0x1A000000)
        private val SIDE_STOPS = floatArrayOf(0f, 0.14f, 0.86f, 1f)

        private val BRASS = Metal(
            rim = 0xFF5E450C.toInt(),
            plate = intArrayOf(0xFFFFF3B4.toInt(), 0xFFE6C24C.toInt(), 0xFFCDA233.toInt(), 0xFF9C7418.toInt()),
            lit = 0xB0FFFBDC.toInt(), shade = 0x70402C00, lip = 0x66FFF6C0, groove = 0x80503800.toInt(),
        )

        /** The metal of a plate whose book is read to the end. */
        private val BRONZE = Metal(
            rim = 0xFF3D2008.toInt(),
            plate = intArrayOf(0xFFF3C79C.toInt(), 0xFFCB8A52.toInt(), 0xFFA3622F.toInt(), 0xFF6E3F18.toInt()),
            lit = 0xB0FFE6CC.toInt(), shade = 0x70301400, lip = 0x66FFE0C0, groove = 0x80401C00.toInt(),
        )
    }

    /**
     * What a plate is made of: the dark [rim], the [plate] gradient from top to bottom, the [lit] and
     * [shade] edges of the bevel, and the [lip] and [groove] of the engraved border.
     */
    private class Metal(val rim: Int, val plate: IntArray, val lit: Int, val shade: Int, val lip: Int, val groove: Int)
}

object ShelfArt {
    /** The dark leather a book shows where its cover does not, as while the cover is still being read. */
    const val LEATHER = 0xFF24160D.toInt()

    /**
     * Gives a flat cover the look of a bound book: a shaded hinge along the spine, the crease, and
     * a lit edge beside it. All of it is as wide as the cover makes it ([HINGE]), so that a cover
     * looks the same however large it is drawn: on the shelf, as it grows off it, and as the cover
     * that ends a book.
     */
    fun drawBinding(c: Canvas, cover: RectF, paint: Paint) {
        val hinge = HINGE * cover.width()
        val d = hinge / 5
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

    /**
     * Gives the flat picture of a case some depth, the way the real case has it: stout side
     * posts with dark edges in place of the hair-thin ones of the picture, the shadows they and
     * the shelf above cast into it, and a darker foot where it stands on its plank.
     */
    fun drawDepth(c: Canvas, case: RectF, d: Float, paint: Paint) {
        val post = POST_DP * d
        val wood = intArrayOf(0xFF3E2513.toInt(), 0xFFA9743F.toInt(), 0xFF5C3A1E.toInt())
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        // The inside lies in the shadow of the shelf above and of its own posts.
        paint.shader = LinearGradient(0f, case.top, 0f, case.top + 10 * d, 0x66000000, 0, Shader.TileMode.CLAMP)
        c.drawRect(case.left, case.top, case.right, case.top + 10 * d, paint)
        paint.shader = LinearGradient(0f, case.bottom - 5 * d, 0f, case.bottom, 0, 0x38000000, Shader.TileMode.CLAMP)
        c.drawRect(case.left, case.bottom - 5 * d, case.right, case.bottom, paint)
        val reach = 5 * d
        paint.shader = LinearGradient(
            case.left + post, 0f, case.left + post + reach, 0f, 0x60000000, 0, Shader.TileMode.CLAMP,
        )
        c.drawRect(case.left + post, case.top, case.left + post + reach, case.bottom, paint)
        paint.shader = LinearGradient(
            case.right - post - reach, 0f, case.right - post, 0f, 0, 0x60000000, Shader.TileMode.CLAMP,
        )
        c.drawRect(case.right - post - reach, case.top, case.right - post, case.bottom, paint)
        for (left in floatArrayOf(case.left, case.right - post)) {
            paint.shader = LinearGradient(left, 0f, left + post, 0f, wood, null, Shader.TileMode.CLAMP)
            c.drawRect(left, case.top, left + post, case.bottom, paint)
            paint.shader = null
            // A lit front edge beside a dark one rounds the post off.
            paint.color = 0x50FFE2B0
            c.drawRect(left + 1.5f * d, case.top, left + 2.5f * d, case.bottom, paint)
            paint.color = 0x90000000.toInt()
            c.drawRect(left, case.top, left + 0.75f * d, case.bottom, paint)
            c.drawRect(left + post - 0.75f * d, case.top, left + post, case.bottom, paint)
        }
        paint.alpha = 255
    }

    /** The gradients of [drawCastShadow] for a screen of the density [d], kept because the shelf draws them at every step it scrolls. */
    private class Cast(val d: Float) {
        val reach = 4 * d
        val left = LinearGradient(-reach, 0f, 0f, 0f, 0, 0x50000000, Shader.TileMode.CLAMP)
        val right = LinearGradient(0f, 0f, reach, 0f, 0x50000000, 0, Shader.TileMode.CLAMP)
    }

    private var cast: Cast? = null

    /** The shadow a case standing between [left] and [right] casts on the wall behind it, at both sides. */
    fun drawCastShadow(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, d: Float, paint: Paint) {
        val s = cast?.takeIf { it.d == d } ?: Cast(d).also { cast = it }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = s.left
        c.withTranslation(left, 0f) { drawRect(-s.reach, top, 0f, bottom, paint) }
        paint.shader = s.right
        c.withTranslation(right, 0f) { drawRect(0f, top, s.reach, bottom, paint) }
        paint.shader = null
    }

    /** How wide the crease of a cover is, a part of its width: about 5dp on the shelf. */
    const val HINGE = 0.045f

    /** Width of the posts [drawDepth] gives a case. */
    private const val POST_DP = 5

    /**
     * Sets [out] to the room the cover that ends a book has on a screen [w] by [h], [d] the density:
     * it stands on the plank of the menu, [menu] high at the foot of the screen, as a book stands on
     * its shelf, with the wall of the case beside it and [top] free over it, as under the shelf above.
     */
    fun endCoverRoom(w: Int, h: Int, top: Int, menu: Int, d: Float, out: Rect) {
        val side = (END_SIDE_DP * d).toInt()
        out.set(side, top + (END_TOP_DP * d).toInt(), w - side, h - menu)
    }

    /** Sets [out] to a cover [shape] wide for every 1 high, as large as fits in [room], standing on its foot in the middle. */
    fun standIn(room: Rect, shape: Float, out: Rect) {
        var coverW = room.width().toFloat()
        var coverH = coverW / shape
        if (coverH > room.height()) {
            coverH = room.height().toFloat()
            coverW = coverH * shape
        }
        val left = room.left + (room.width() - coverW) / 2f
        out.set(left.toInt(), (room.bottom - coverH).toInt(), (left + coverW).toInt(), room.bottom)
    }

    /**
     * Sets [out] to where the cover that ends a book stands on a screen [w] by [h], see
     * [endCoverRoom]: in the shape it has on the shelf (ShelfSpec.BOOK), as large as fits.
     */
    fun endCover(w: Int, h: Int, top: Int, menu: Int, d: Float, out: Rect) {
        endCoverRoom(w, h, top, menu, d, out)
        standIn(out, 1f / ShelfSpec.BOOK, out)
    }

    /** The wall left beside the cover that ends a book, and over it; see [endCoverRoom]. */
    private const val END_SIDE_DP = 20
    private const val END_TOP_DP = 14

    /**
     * The back wall of a case, as the shelves show it (shelf_back.xml), where it stands behind the
     * cover that ends a book: a new one each time, as each is drawn at its own size.
     */
    fun wall(ctx: Context): Drawable = ContextCompat.getDrawable(ctx, R.drawable.shelf_back)!!.mutate()
}
