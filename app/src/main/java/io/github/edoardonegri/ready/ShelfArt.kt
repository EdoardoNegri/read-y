package io.github.edoardonegri.ready

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withTranslation
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.max
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

    /**
     * The gradients of the plate, of its shine and of the shade at its ends, made for the bounds
     * and the metal they were last drawn with ([shadedFor]), rather than anew whenever the plate is
     * drawn: the shelf draws one for every book that scrolls into sight.
     */
    private var plateShader: Shader? = null
    private var glossShader: Shader? = null
    private var sideShader: Shader? = null
    private var shadedFor: Metal? = null

    /** The domes of a screw and of a star, about their middles: they are drawn moved to where they stand. */
    private val screwDome = (SCREW_RADIUS * d).let {
        RadialGradient(
            -it * 0.35f, -it * 0.4f, it * 1.5f,
            intArrayOf(0xFFFFF8D0.toInt(), 0xFFC9A038.toInt(), 0xFF6E5210.toInt()), DOME_STOPS, Shader.TileMode.CLAMP,
        )
    }
    private val starDome = (STAR_RADIUS * d).let {
        RadialGradient(
            -it * 0.3f, -it * 0.35f, it * 1.4f,
            intArrayOf(0xFFFFFBE0.toInt(), 0xFFE0B840.toInt(), 0xFF7A5A12.toInt()), DOME_STOPS, Shader.TileMode.CLAMP,
        )
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        shadedFor = null
    }

    /** Makes the gradients of the plate anew if its bounds or its [metal] have changed since they were made. */
    private fun shade(metal: Metal) {
        if (shadedFor === metal) return
        shadedFor = metal
        val b = bounds
        val top = b.top.toFloat()
        val bottom = b.bottom - SHADOW_DP * d
        plateShader = LinearGradient(0f, top, 0f, bottom, metal.plate, PLATE_STOPS, Shader.TileMode.CLAMP)
        // The face of the plate, inside its rim.
        glossShader = LinearGradient(0f, top + d, 0f, bottom - d, GLOSS_COLORS, GLOSS_STOPS, Shader.TileMode.CLAMP)
        sideShader = LinearGradient(b.left + d, 0f, b.right - d, 0f, SIDE_COLORS, SIDE_STOPS, Shader.TileMode.CLAMP)
    }

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
        shade(metal)
        paint.color = metal.rim
        r.set(left, top, right, bottom)
        c.drawRoundRect(r, 3 * d, 3 * d, paint)
        paint.shader = plateShader
        r.inset(d, d)
        c.drawRoundRect(r, 2 * d, 2 * d, paint)
        face.rewind()
        face.addRoundRect(r, 2 * d, 2 * d, Path.Direction.CW)
        gloss(c, face)

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
        shade(metal)

        // Shadow, dark rim, then the plate, as on the plain plaque.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0x70000000
        c.drawPath(scooped(left + d, top + shadow, right - d, bottom + shadow), paint)
        paint.color = metal.rim
        c.drawPath(scooped(left, top, right, bottom), paint)
        paint.shader = plateShader
        c.drawPath(scooped(left + d, top + d, right - d, bottom - d), paint)
        paint.shader = null
        face.set(path)
        gloss(c, face)

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
     * [outline] is the face of the plate, which the gradients made by [shade] are made for.
     */
    private fun gloss(c: Canvas, outline: Path) {
        paint.style = Paint.Style.FILL
        paint.shader = glossShader
        c.drawPath(outline, paint)
        paint.shader = sideShader
        c.drawPath(outline, paint)
        paint.shader = null
    }

    /** A small five-pointed stud, domed like the screws. */
    private fun star(c: Canvas, x: Float, y: Float) {
        val outer = STAR_RADIUS * d
        val inner = 1.8f * d
        // About its middle, which is moved to where it stands, as its dome is.
        star.rewind()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.PI * i / 5 - Math.PI / 2
            val px = (cos(a) * r).toFloat()
            val py = (sin(a) * r).toFloat()
            if (i == 0) star.moveTo(px, py) else star.lineTo(px, py)
        }
        star.close()
        c.withTranslation(x, y) {
            paint.style = Paint.Style.FILL
            paint.color = 0x80302000.toInt()
            withTranslation(0.4f * d, 0.6f * d) { drawPath(star, paint) }
            paint.shader = starDome
            drawPath(star, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.6f * d
            paint.color = 0xFF3E2C06.toInt()
            drawPath(star, paint)
            paint.style = Paint.Style.FILL
        }
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
        val radius = SCREW_RADIUS * d
        paint.style = Paint.Style.FILL
        paint.color = 0x80302000.toInt()
        c.drawCircle(x + 0.4f * d, y + 0.6f * d, radius + 0.5f * d, paint)
        paint.shader = screwDome
        c.withTranslation(x, y) { drawCircle(0f, 0f, radius, paint) }
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

        /** How large a screw and a star are, in dp, and where the colours of their domes lie, from the light to the rim. */
        private const val SCREW_RADIUS = 2.7f
        private const val STAR_RADIUS = 4.2f
        private val DOME_STOPS = floatArrayOf(0f, 0.55f, 1f)

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
     * Draws the picture [b] as a cover in [cover]: cut to its shape about its middle, as the shelf
     * shows a cover, and starting at the crease, which has the colours of the edge it meets
     * ([edge]), so that nothing of the picture is hidden by it. With the shading of the binding
     * ([drawBinding]) over it, unless it is to be [shaded] by someone else, as the reader does.
     */
    fun drawBound(c: Canvas, b: Bitmap, cover: RectF, paint: Paint, shaded: Boolean = true) {
        val seen = cut(b.width, b.height, cover.width(), cover.height(), RectF())
        edge(b, seen)?.let { drawCrease(c, cover, it, paint) }
        val src = Rect(seen.left.toInt(), seen.top.toInt(), seen.right.toInt(), seen.bottom.toInt())
        c.drawBitmap(b, src, RectF(cover.left + HINGE * cover.width(), cover.top, cover.right, cover.bottom), paint)
        if (shaded) drawBinding(c, cover, paint)
    }

    /**
     * The cover that ends a book as the reader shows it, but for the shading ([drawBinding]) that it
     * draws over it: [b] cut to the shape of a book, as large as it is, with the crease in place.
     */
    fun bound(b: Bitmap): Bitmap {
        val w = minOf(b.width.toFloat(), b.height / ShelfSpec.BOOK).toInt().coerceAtLeast(1)
        val h = (w * ShelfSpec.BOOK).toInt()
        val out = createBitmap(w, h)
        drawBound(Canvas(out), b, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG), shaded = false)
        return out
    }

    /**
     * Begins to draw a picture that fits [cover], one an ImageView draws: puts the crease, and
     * moves the drawing over to the part of [cover] that starts at it; ends with [endBound], given
     * what this gives back, which is -1 where there is no [edge] to make the crease of, and the
     * picture is then drawn as it is.
     */
    fun beginBound(c: Canvas, cover: RectF, edge: Bitmap?, paint: Paint): Int {
        if (edge == null) return -1
        drawCrease(c, cover, edge, paint)
        val saved = c.save()
        c.translate(cover.left + HINGE * cover.width(), 0f)
        c.scale(1f - HINGE, 1f)
        c.translate(-cover.left, 0f)
        return saved
    }

    /** Ends what [beginBound] began, with the shading of the binding over it. */
    fun endBound(c: Canvas, saved: Int, cover: RectF, paint: Paint) {
        if (saved >= 0) c.restoreToCount(saved)
        drawBinding(c, cover, paint)
    }

    /** Paints the crease of [cover], where its hinge is, from [edge]; the picture comes over the rest. */
    private fun drawCrease(c: Canvas, cover: RectF, edge: Bitmap, paint: Paint) {
        val filtering = paint.isFilterBitmap
        paint.isFilterBitmap = true
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = null
        // A little under the picture, which is drawn over it, to leave no seam.
        c.drawBitmap(edge, null, RectF(cover.left, cover.top, cover.left + HINGE * cover.width() + 1f, cover.bottom), paint)
        paint.isFilterBitmap = filtering
    }

    /** Sets [out] to the part of a picture [w] by [h] that a cover [coverW] by [coverH] shows, as with centerCrop. */
    fun cut(w: Int, h: Int, coverW: Float, coverH: Float, out: RectF): RectF {
        val scale = max(coverW / w, coverH / h)
        val sw = coverW / scale
        val sh = coverH / scale
        out.set((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2)
        return out
    }

    /** What is known of the colours along the left edge of [seen], a part of a picture, as a strip that is [EDGE_ROWS] high. */
    private class Known(val seen: RectF, val strip: Bitmap)

    private val known = WeakHashMap<Bitmap, MutableList<Known>>()

    /**
     * The colours along the left edge of [seen], the part of the picture [b] that a cover shows, from
     * top to bottom as a strip a pixel wide: each the average of a little of the edge over a stretch
     * of its height, [EDGE_ROWS] of them, so that a busy edge makes a smooth crease. Kept for the
     * picture, which is looked at once for each part of it that is shown. Null if it cannot be looked into.
     */
    fun edge(b: Bitmap, seen: RectF): Bitmap? {
        if (b.isRecycled || seen.isEmpty) return null
        val slack = EDGE_SLACK * max(b.width, b.height)
        synchronized(known) {
            known[b]?.firstOrNull { it.seen.isNear(seen, slack) }?.let { return it.strip }
        }
        val strip = read(b, seen) ?: return null
        remember(b, seen, strip)
        return strip
    }

    /**
     * Keeps for [to], the picture [from] as the drawing hardware keeps it, which cannot be looked
     * into, the edge it has as a cover cut to the shape of a book, as it is shown nearly everywhere.
     */
    fun keepBookEdge(from: Bitmap, to: Bitmap) {
        val seen = cut(from.width, from.height, 1f, ShelfSpec.BOOK, RectF())
        edge(from, seen)?.let { remember(to, seen, it) }
    }

    private fun remember(b: Bitmap, seen: RectF, strip: Bitmap) {
        synchronized(known) {
            val list = known.getOrPut(b) { mutableListOf() }
            if (list.size >= EDGE_KEPT) list.removeAt(0)
            list.add(Known(RectF(seen), strip))
        }
    }

    private fun RectF.isNear(o: RectF, slack: Float) =
        Math.abs(left - o.left) <= slack && Math.abs(top - o.top) <= slack &&
            Math.abs(right - o.right) <= slack && Math.abs(bottom - o.bottom) <= slack

    private fun read(b: Bitmap, seen: RectF): Bitmap? {
        // A picture the drawing hardware keeps cannot be looked into, but a copy of it can.
        val readable = if (Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE) {
            try {
                b.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } catch (e: OutOfMemoryError) {
                return null
            }
        } else {
            b
        }
        try {
            val colors = IntArray(EDGE_ROWS)
            for (row in 0 until EDGE_ROWS) {
                var r = 0
                var g = 0
                var bl = 0
                for (j in 0 until EDGE_DOWN) {
                    val y = (seen.top + seen.height() * (row + (j + 0.5f) / EDGE_DOWN) / EDGE_ROWS).toInt().coerceIn(0, readable.height - 1)
                    for (i in 0 until EDGE_ACROSS) {
                        val x = (seen.left + seen.width() * EDGE_PART * (i + 0.5f) / EDGE_ACROSS).toInt().coerceIn(0, readable.width - 1)
                        val p = readable[x, y]
                        r += Color.red(p)
                        g += Color.green(p)
                        bl += Color.blue(p)
                    }
                }
                val n = EDGE_DOWN * EDGE_ACROSS
                colors[row] = Color.rgb(r / n, g / n, bl / n)
            }
            return Bitmap.createBitmap(colors, 1, EDGE_ROWS, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            return null
        } finally {
            if (readable !== b) readable.recycle()
        }
    }

    /**
     * How the crease of a cover is made of the edge of its picture: the colours are those of the
     * outer [EDGE_PART] of the picture's width, in [EDGE_ROWS] bands from the top to the foot (each
     * about 1.5% of the height), found from [EDGE_DOWN] by [EDGE_ACROSS] places in each band. Tried
     * from none up to a band of 9%: 1.5% is as smooth as it can be before the edge of a band of
     * colour that meets the crease begins to run into it. Two parts of a picture whose sides are
     * as near as [EDGE_SLACK] of its size share a strip, and [EDGE_KEPT] parts are kept for each picture.
     */
    private const val EDGE_ROWS = 64
    private const val EDGE_PART = 0.02f
    private const val EDGE_DOWN = 6
    private const val EDGE_ACROSS = 3
    private const val EDGE_SLACK = 0.01f
    private const val EDGE_KEPT = 4

    /**
     * Gives a flat cover the look of a bound book: a shaded hinge along the spine, the crease, and
     * a lit edge beside it. All of it is as wide as the cover makes it ([HINGE]), so that a cover
     * looks the same however large it is drawn: on the shelf, as it grows off it, and as the cover
     * that ends a book. The shading goes over a cover that starts at the crease ([drawBound]).
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

    /**
     * How wide the crease of a cover is, a part of its width: about 4dp on the shelf. The joint of
     * a hardback sits some 6 to 8mm in from the spine on a book 13 to 15cm wide, a little over 4% of it.
     */
    const val HINGE = 0.04f

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

/**
 * The binding of a cover that an ImageView draws: [begin] before the view draws its picture, and
 * [end] after it. The picture is drawn as the view lays it out, but from the crease on, and the
 * crease has the colours of the edge of the picture that is shown there (see [ShelfArt.drawBound]).
 * Where the view shows no picture it can look into, only the shading of the binding is drawn.
 */
class ViewBinding(private val view: ImageView) {
    private val paint = Paint()
    private val inverse = Matrix()
    private val seen = RectF()
    private var saved = -1

    fun begin(canvas: Canvas, area: RectF) {
        saved = ShelfArt.beginBound(canvas, area, edge(), paint)
    }

    fun end(canvas: Canvas, area: RectF) {
        ShelfArt.endBound(canvas, saved, area, paint)
        saved = -1
    }

    /** The edge of the part of the picture the view shows, within its padding; null if it shows none that can be looked into. */
    private fun edge(): Bitmap? {
        val drawable = view.drawable ?: return null
        val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return null
        val w = (view.width - view.paddingLeft - view.paddingRight).toFloat()
        val h = (view.height - view.paddingTop - view.paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return null
        if (view.scaleType == ImageView.ScaleType.FIT_XY) {
            seen.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        } else {
            // Where the view's own place is in the picture, which the view lays out as it is told to.
            if (!view.imageMatrix.invert(inverse)) return null
            seen.set(0f, 0f, w, h)
            inverse.mapRect(seen)
            // The drawable is as large as its picture, unless the picture is made for another density.
            val sx = bmp.width.toFloat() / drawable.intrinsicWidth.coerceAtLeast(1)
            val sy = bmp.height.toFloat() / drawable.intrinsicHeight.coerceAtLeast(1)
            seen.set(seen.left * sx, seen.top * sy, seen.right * sx, seen.bottom * sy)
            if (!seen.intersect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())) return null
        }
        return ShelfArt.edge(bmp, seen)
    }
}
