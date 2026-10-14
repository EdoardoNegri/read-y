package io.github.edoardonegri.ready

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.withTranslation
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/**
 * Draws the bookcase: a wooden plank under every row of books, empty shelves below the last row,
 * the shadows the cases standing on the shelves and the top rail cast on the wall behind them, and on top of
 * everything the case's side posts, which stay put while the shelves scroll inside it. All of it
 * is drawn again for every step the shelves scroll, so nothing is made anew for it: the gradients
 * are kept ([Planks], [Posts]) and moved to where they are needed.
 */
class ShelfDecoration(ctx: Context, private val columns: Int) : RecyclerView.ItemDecoration() {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint()

    /** The wood the case is made of: the shelf being shown. */
    var wood = Wood.WALNUT
        set(value) {
            field = value
            paint.colorFilter = value.filter
        }

    init {
        prepare(ctx)
    }

    /** Where the rows on screen end, each once, and which rows of the shelf they are. */
    private var bottoms = IntArray(8)
    private var numbers = IntArray(8)

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        var rows = 0
        var lastNumber = -1
        var row = ShelfSpec(parent.width, columns, d).rowH.coerceAtLeast(1)
        var last = parent.paddingTop
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            if (v.height > 0) row = v.height
            // Where the row is, not where a book sliding to another place happens to be on its way.
            val bottom = v.bottom
            if ((0 until rows).any { bottoms[it] == bottom }) continue
            if (rows == bottoms.size) {
                bottoms = bottoms.copyOf(rows * 2)
                numbers = numbers.copyOf(rows * 2)
            }
            numbers[rows] = parent.getChildAdapterPosition(v).coerceAtLeast(0) / columns
            bottoms[rows++] = bottom
            if (rows == 1 || bottom > last) {
                last = bottom
                lastNumber = numbers[rows - 1]
            }
        }
        val w = parent.width.toFloat()
        for (i in 0 until rows) drawPlank(c, w, bottoms[i].toFloat(), d, paint, numbers[i])
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            val h = parent.getChildViewHolder(v) as? ShelfAdapter.Holder ?: continue
            // What is carried about has left its place, shadow and all.
            if (h.file?.isDirectory != true || !h.cover.isVisible) continue
            val x = v.left + v.translationX + h.frame.left
            val y = v.top + v.translationY + h.frame.top
            ShelfArt.drawCastShadow(c, x, y, x + h.frame.width, y + h.frame.height, d, paint)
        }
        var b = last + row
        while (b - (PLANK + LIP) * d < parent.height) {
            drawPlank(c, w, b.toFloat(), d, paint, ++lastNumber)
            b += row
        }
        // The shadow the top rail casts into the case. Like those of the planks it falls on the
        // wall, behind what stands on the shelves and the bars that hang from the rail.
        drawShade(c, w, d, paint)
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        drawCase(c, parent.width.toFloat(), parent.height.toFloat(), ShelfSpec(parent.width, columns, d), paint)
    }

    /** The gradients of a plank whose face starts at 0, for a screen of the density [d]. */
    private class Planks(val d: Float) {
        val lip = vertical(-LIP * d, 0f, 0xFF4A2E1A.toInt(), 0xFFA9743F.toInt())
        val face = vertical(0f, PLANK * d, 0xFF8B5A2B.toInt(), 0xFF5C3A1E.toInt())
        val shade = vertical(0f, SHADE * d, 0xAA000000.toInt(), 0x00000000)

        private fun vertical(top: Float, bottom: Float, from: Int, to: Int) =
            LinearGradient(0f, top, 0f, bottom, from, to, Shader.TileMode.CLAMP)
    }

    /** The gradients of the side posts of a case [w] wide, and of the shadows they cast, for posts [post] thick. */
    private class Posts(val w: Float, val post: Float, shade: Float) {
        private val wood = intArrayOf(0xFF4A2E1A.toInt(), 0xFF9A6836.toInt(), 0xFF5C3A1E.toInt())
        val left = LinearGradient(0f, 0f, post, 0f, wood, null, Shader.TileMode.CLAMP)
        val right = LinearGradient(w - post, 0f, w, 0f, wood, null, Shader.TileMode.CLAMP)
        val leftShade = LinearGradient(post, 0f, post + shade, 0f, 0x88000000.toInt(), 0, Shader.TileMode.CLAMP)
        val rightShade = LinearGradient(w - post - shade, 0f, w - post, 0f, 0, 0x88000000.toInt(), Shader.TileMode.CLAMP)
    }

    /**
     * The grain of the wood, along a board and up a post, to lay over the colour of either. The
     * pictures are drawn dot for dot on every screen, as the drawables do that use them
     * (shelf_plank.xml), and repeat after [w] by [h] dots along a board.
     */
    private class Grain(ctx: Context) {
        // The very pictures the drawables of the bookcase show, which the resources have decoded
        // once for all of them, rather than copies decoded anew.
        private val board = picture(ctx, R.drawable.wood_grain)
        val w = board.width
        val h = board.height
        val along = BitmapShader(board, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val upright = BitmapShader(picture(ctx, R.drawable.wood_grain_upright), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

        private fun picture(ctx: Context, id: Int): Bitmap =
            (ContextCompat.getDrawable(ctx, id) as? BitmapDrawable)?.bitmap ?: BitmapFactory.decodeResource(ctx.resources, id)
    }

    companion object {
        private const val PLANK = 44
        private const val LIP = 5
        private const val SHADE = 10

        /** How far along and across the picture of the grain the next plank is cut from, in dots. */
        private const val GRAIN_X = 397
        private const val GRAIN_Y = 179

        // The miniatures are drawn in the background with the same gradients, which are never changed once made.
        @Volatile
        private var planks: Planks? = null

        @Volatile
        private var posts: Posts? = null

        @Volatile
        private var grain: Grain? = null

        /** Gets the grain of the wood ready, without which the case is drawn plain. */
        fun prepare(ctx: Context) {
            if (grain == null) grain = Grain(ctx.applicationContext)
        }

        /** The grain of the boards of the back wall, [w] by [h], which stand upright (shelf_back.xml). */
        fun drawWallGrain(c: Canvas, w: Float, h: Float, paint: Paint) {
            paint.alpha = 255
            paint.shader = grain?.upright ?: return
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        private fun planks(d: Float) = planks?.takeIf { it.d == d } ?: Planks(d).also { planks = it }

        /**
         * One plank whose front face ends at [bottom]; [d] is the display density. It is the plank
         * of the row [row] of its shelf: every board is cut from another stretch of the wood, and
         * keeps its grain wherever the shelf is scrolled to.
         */
        fun drawPlank(c: Canvas, w: Float, bottom: Float, d: Float, paint: Paint, row: Int = 0) {
            val p = planks(d)
            val face = PLANK * d
            c.withTranslation(0f, bottom - face) {
                // A shader is still multiplied by the paint alpha, which an earlier translucent colour may have lowered.
                paint.alpha = 255
                paint.shader = p.lip
                drawRect(0f, -LIP * d, w, 0f, paint)
                paint.shader = p.face
                drawRect(0f, 0f, w, face, paint)
                grain?.let {
                    paint.shader = it.along
                    val dx = (row * GRAIN_X % it.w).toFloat()
                    val dy = (row * GRAIN_Y % it.h).toFloat()
                    withTranslation(-dx, -dy) { drawRect(dx, dy - LIP * d, dx + w, dy + face, paint) }
                }
                // A lit front edge and a dark underside give the board some thickness.
                paint.shader = null
                paint.color = 0x40FFE2B0
                drawRect(0f, 0f, w, d, paint)
                paint.color = 0x66000000
                drawRect(0f, face - 1.5f * d, w, face, paint)
            }
            c.withTranslation(0f, bottom) { drawShade(this, w, d, paint) }
        }

        /** The shadow a board casts on the wall under it, from 0 down; the top rail casts the same. */
        fun drawShade(c: Canvas, w: Float, d: Float, paint: Paint) {
            paint.alpha = 255
            paint.shader = planks(d).shade
            c.drawRect(0f, 0f, w, SHADE * d, paint)
            paint.shader = null
        }

        /** The case around a shelf area of [w] by [h]: two side posts and the shadows they cast. */
        fun drawCase(c: Canvas, w: Float, h: Float, spec: ShelfSpec, paint: Paint) {
            val post = spec.post.toFloat()
            val shade = 6 * spec.density
            val p = posts?.takeIf { it.w == w && it.post == post } ?: Posts(w, post, shade).also { posts = it }
            paint.alpha = 255
            paint.shader = p.left
            c.drawRect(0f, 0f, post, h, paint)
            paint.shader = p.right
            c.drawRect(w - post, 0f, w, h, paint)
            grain?.let {
                paint.shader = it.upright
                c.drawRect(0f, 0f, post, h, paint)
                c.drawRect(w - post, 0f, w, h, paint)
            }
            // Shadows the posts cast on the shelves beside them.
            paint.shader = p.leftShade
            c.drawRect(post, 0f, post + shade, h, paint)
            paint.shader = p.rightShade
            c.drawRect(w - post - shade, 0f, w - post, h, paint)
            paint.shader = null
        }
    }
}
