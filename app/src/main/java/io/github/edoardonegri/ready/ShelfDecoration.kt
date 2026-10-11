package io.github.edoardonegri.ready

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.recyclerview.widget.RecyclerView
import java.util.TreeSet

/**
 * Draws the bookcase: a wooden plank under every row of books, empty shelves below the last row,
 * and on top of everything the case's side posts, which stay put while the
 * shelves scroll inside it.
 */
class ShelfDecoration(ctx: Context, private val columns: Int) : RecyclerView.ItemDecoration() {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val bottoms = TreeSet<Int>()
        var row = ShelfSpec(parent.width, columns, d).rowH.coerceAtLeast(1)
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            // Where the row is, not where a book sliding to another place happens to be on its way.
            bottoms.add(v.bottom)
            if (v.height > 0) row = v.height
        }
        for (b in bottoms) drawPlank(c, parent.width.toFloat(), b.toFloat(), d, paint)
        var b = (if (bottoms.isEmpty()) parent.paddingTop else bottoms.last()) + row
        while (b - (PLANK + LIP) * d < parent.height) {
            drawPlank(c, parent.width.toFloat(), b.toFloat(), d, paint)
            b += row
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        drawCase(c, parent.width.toFloat(), parent.height.toFloat(), ShelfSpec(parent.width, columns, d), paint)
    }

    companion object {
        private const val PLANK = 44
        private const val LIP = 5
        private const val SHADE = 10

        /** One plank whose front face ends at [bottom]; [d] is the display density. */
        fun drawPlank(c: Canvas, w: Float, bottom: Float, d: Float, paint: Paint) {
            val top = bottom - PLANK * d
            gradient(c, w, top - LIP * d, top, 0xFF4A2E1A.toInt(), 0xFFA9743F.toInt(), paint)
            gradient(c, w, top, bottom, 0xFF8B5A2B.toInt(), 0xFF5C3A1E.toInt(), paint)
            // A lit front edge and a dark underside give the board some thickness.
            paint.shader = null
            paint.color = 0x40FFE2B0
            c.drawRect(0f, top, w, top + d, paint)
            paint.color = 0x66000000
            c.drawRect(0f, bottom - 1.5f * d, w, bottom, paint)
            paint.alpha = 255
            gradient(c, w, bottom, bottom + SHADE * d, 0xAA000000.toInt(), 0x00000000, paint)
        }

        /** The top rail of the case, [h] tall, and its shadow: the board of the app's top bar (shelf_plank.xml). */
        fun drawRail(c: Canvas, w: Float, h: Float, d: Float, paint: Paint) {
            gradient(c, w, 0f, h, 0xFF8B5A2B.toInt(), 0xFF5C3A1E.toInt(), paint)
            paint.shader = null
            paint.color = 0x66000000
            c.drawRect(0f, h - 1.5f * d, w, h, paint)
            paint.alpha = 255
            gradient(c, w, h, h + SHADE * d, 0xAA000000.toInt(), 0x00000000, paint)
            paint.shader = null
        }

        /** The case around a shelf area of [w] by [h]: two side posts and the shadows they cast. */
        fun drawCase(c: Canvas, w: Float, h: Float, spec: ShelfSpec, paint: Paint) {
            val post = spec.post.toFloat()
            val shade = 6 * spec.density
            val wood = intArrayOf(0xFF4A2E1A.toInt(), 0xFF9A6836.toInt(), 0xFF5C3A1E.toInt())
            paint.alpha = 255
            for (left in floatArrayOf(0f, w - post)) {
                paint.shader = LinearGradient(left, 0f, left + post, 0f, wood, null, Shader.TileMode.CLAMP)
                c.drawRect(left, 0f, left + post, h, paint)
            }
            // Shadows the posts cast on the shelves beside them.
            paint.shader = LinearGradient(post, 0f, post + shade, 0f, 0x88000000.toInt(), 0, Shader.TileMode.CLAMP)
            c.drawRect(post, 0f, post + shade, h, paint)
            paint.shader =
                LinearGradient(w - post - shade, 0f, w - post, 0f, 0, 0x88000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(w - post - shade, 0f, w - post, h, paint)
            paint.shader = null
        }

        private fun gradient(c: Canvas, w: Float, top: Float, bottom: Float, from: Int, to: Int, paint: Paint) {
            // A shader is still multiplied by the paint alpha, which an earlier translucent colour may have lowered.
            paint.alpha = 255
            paint.shader = LinearGradient(0f, top, 0f, bottom, from, to, Shader.TileMode.CLAMP)
            c.drawRect(0f, top, w, bottom, paint)
        }
    }
}
