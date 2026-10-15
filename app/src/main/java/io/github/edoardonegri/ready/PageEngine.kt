package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Shows a book of fixed pages, a PDF or a comic, one page at a time (horizontal mode) or as a
 * continuous column of pages (vertical mode).
 */
class PageEngine(
    act: Activity,
    private val file: File,
    private val s: ReaderSettings,
    start: BookState,
    private val host: ReaderHost,
) : ReaderEngine {

    private val source = BookPrep.take(file, PageSource::class.java) ?: PageSource.open(file)
    private val count = source.count

    // A source is not thread-safe: after construction it is only touched on this thread.
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<Int>()
    private val ratios = FloatArray(count) { source.firstRatio }
    private var generation = 0
    private var page = start.chapter.coerceIn(0, count - 1)

    /** A book of one page has been taken to its end, by the seek bar or by being marked as read. */
    private var atEnd = start.percent >= Library.FINISHED_AT

    /** Whether the host was told the first page is shown (ReaderHost.shown). */
    private var told = false
    private var filter: ColorFilter? = null

    @Volatile
    private var closed = false

    private val frame = FrameLayout(act)
    private val single = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val list = RecyclerView(act)
    private val lm = LinearLayoutManager(act)
    private val gap = (4 * act.resources.displayMetrics.density).toInt()
    override val view: View get() = frame

    private class Holder(val img: RatioImageView) : RecyclerView.ViewHolder(img)

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val img = RatioImageView(parent.context)
            img.scaleType = ImageView.ScaleType.FIT_XY
            img.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = gap }
            return Holder(img)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.img.ratio = ratios[position]
            holder.img.colorFilter = filter
            holder.img.setImageBitmap(cache.get(position))
            request(position)
        }
    }

    init {
        single.pivotX = 0f
        single.pivotY = 0f
        list.pivotX = 0f
        list.pivotY = 0f
        list.layoutManager = lm
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val firstVisible = lm.findFirstVisibleItemPosition()
                if (firstVisible < 0) return
                // The page that covers the middle of the screen is the current one.
                val v = lm.findViewByPosition(firstVisible)
                val current =
                    if (v != null && v.bottom < frame.height / 2 && firstVisible + 1 < count) firstVisible + 1
                    else firstVisible
                if (current != page) {
                    page = current
                    report()
                }
            }
        })
        frame.addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        frame.addView(single, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) frame.post { if (!closed) configure() }
        }
    }

    /** The first page is none of the book read and the last all of it; a book of one page is read once at its end. */
    private fun report() {
        val read = if (count > 1) page.toFloat() / (count - 1) else if (atEnd) 1f else 0f
        host.onPosition(page, 0f, read, frame.context.getString(R.string.page_n_of_cap, page + 1, count), null)
    }

    /** Draws page [i] for the current view size in the background and delivers it when done. */
    private fun request(i: Int) {
        if (i !in 0 until count || cache.get(i) != null) return
        val w = if (s.vertical) list.width.takeIf { it > 0 } ?: frame.width else frame.width
        val h = if (s.vertical) 0 else frame.height
        if (w == 0 || frame.height == 0 || !pending.add(i)) return
        val gen = generation
        val trim = s.trimMargins
        exec.execute {
            // The page stays blank if it cannot be drawn.
            val bmp = try {
                if (closed) null else source.render(i, w, h, trim)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (closed || gen != generation) return@post
                pending.remove(i)
                val result = bmp ?: return@post
                cache.put(i, result)
                ratios[i] = result.height.toFloat() / result.width
                if (!s.vertical) {
                    // A page that is enlarged shows the drawing made for its size, see [zoomEnd].
                    if (i == page && zoom == 1f) single.setImageBitmap(result)
                } else {
                    (list.findViewHolderForAdapterPosition(i) as? Holder)?.let {
                        it.img.ratio = ratios[i]
                        it.img.setImageBitmap(result)
                    }
                }
                if (!told && (s.vertical || i == page)) {
                    told = true
                    host.shown()
                }
            }
        }
    }

    private fun showSingle() {
        resetZoom()
        single.setImageBitmap(cache.get(page))
        request(page)
        request(page + 1)
        report()
    }

    // Every page is drawn anew, for another size or another mode.
    @SuppressLint("NotifyDataSetChanged")
    private fun configure() {
        generation++
        pending.clear()
        cache.evictAll()
        resetZoom()
        frame.setBackgroundColor(s.litPalette.bg)
        // Dark mode shows print with its colours turned round; pictures stay as they are. Either
        // lies in the warm light.
        filter = light(s.darkMode && source.print, s.lamp)
        if (s.vertical) {
            single.visibility = View.GONE
            single.setImageBitmap(null)
            list.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
            lm.scrollToPositionWithOffset(page, 0)
            report()
        } else {
            list.visibility = View.GONE
            single.visibility = View.VISIBLE
            single.colorFilter = filter
            showSingle()
        }
    }

    private fun step(direction: Int) {
        if (s.vertical) {
            val by = direction * (frame.height * 0.88f).toInt()
            if (s.slidePages) list.smoothScrollBy(0, by) else list.scrollBy(0, by)
        } else if (page + direction in 0 until count) {
            page += direction
            showSingle()
        }
    }

    override fun next() = step(1)
    override fun prev() = step(-1)
    override fun applySettings() = configure()

    override fun seekTo(fraction: Float) {
        atEnd = fraction >= 1f
        page = (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()
        if (s.vertical) {
            lm.scrollToPositionWithOffset(page, 0)
            report()
        } else {
            showSingle()
        }
    }

    // ---- enlarging the page by pinching ----

    /**
     * How many times larger than the screen the page is drawn. A page turned one by one is drawn once and
     * enlarged on the screen, then drawn again for its size once the fingers are lifted. In the
     * column of pages that scroll the pages themselves grow wider, which is done at the end too,
     * and until then the column is only enlarged on the screen, about [committed] times too small for [zoom].
     */
    private var zoom = 1f
    private var committed = 1f

    /** Where the enlarged page starts, from the top left of the screen: never positive. */
    private var tx = 0f
    private var ty = 0f

    override val zoomable get() = true
    override val zoomed get() = zoom > 1f

    private fun resetZoom() {
        for (v in arrayOf(single, list)) {
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
        }
        zoom = 1f
        tx = 0f
        ty = 0f
        if (committed != 1f) {
            committed = 1f
            list.layoutParams = list.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        place()
    }

    /** Shows the page where [tx] and [ty] and the size say it is. */
    private fun place() {
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        tx = tx.coerceIn(w * (1 - zoom), 0f)
        ty = if (s.vertical) ty.coerceAtMost(0f) else ty.coerceIn(h * (1 - zoom), 0f)
        val view = if (s.vertical) list else single
        val visual = zoom / committed
        view.scaleX = visual
        view.scaleY = visual
        view.translationX = tx
        view.translationY = if (s.vertical && visual == 1f) 0f else ty
    }

    override fun zoomBy(factor: Float, x: Float, y: Float) {
        val to = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val by = to / zoom
        tx = x - (x - tx) * by
        ty = y - (y - ty) * by
        zoom = to
        place()
    }

    override fun panBy(dx: Float, dy: Float) {
        tx += dx
        // The column of pages scrolls itself up and down.
        if (!s.vertical) ty += dy
        place()
    }

    override fun zoomEnd() {
        if (zoom < SNAP_ZOOM) {
            val bigger = committed != 1f
            val page = page
            zoom = 1f
            if (s.vertical) commit() else {
                resetZoom()
                single.setImageBitmap(cache.get(page))
            }
            if (bigger) report()
            return
        }
        if (s.vertical) {
            commit()
            return
        }
        val w = (frame.width * zoom).toInt()
        val h = (frame.height * zoom).toInt()
        val shown = page
        val gen = generation
        val trim = s.trimMargins
        exec.execute {
            val bmp = try {
                if (closed) null else source.render(shown, w, h, trim)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (!closed && bmp != null && gen == generation && shown == page && zoom > 1f) single.setImageBitmap(bmp)
            }
        }
    }

    /** The column of pages is made as wide as [zoom] says, drawn anew, and left standing where it was on the screen. */
    private fun commit() {
        val visual = zoom / committed
        if (visual == 1f) return place()
        val at = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val top = lm.findViewByPosition(at)?.top ?: 0
        val offset = (ty + top * visual).toInt()
        committed = zoom
        tx = tx.coerceIn(frame.width * (1 - zoom), 0f)
        ty = 0f
        list.layoutParams = list.layoutParams.apply { width = (frame.width * committed).toInt() }
        generation++
        pending.clear()
        cache.evictAll()
        place()
        adapter.notifyDataSetChanged()
        lm.scrollToPositionWithOffset(at, offset)
    }

    override fun close() {
        closed = true
        // The book is kept open a while, in case it is opened again soon, once nothing is drawn from it any more.
        exec.execute { KeptBook.keep(file, source) }
        exec.shutdown()
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f

        /** A page pinched to less than this is let go back to the size of the screen. */
        const val SNAP_ZOOM = 1.1f

        /**
         * What the page is drawn through: its colours turned round if [inverted], and in the
         * light [lamp], which tints what is white to its own colour; null if neither changes them.
         */
        fun light(inverted: Boolean, lamp: Int): ColorFilter? {
            if (!inverted && lamp == Color.WHITE) return null
            val r = Color.red(lamp) / 255f
            val g = Color.green(lamp) / 255f
            val b = Color.blue(lamp) / 255f
            val sign = if (inverted) -1f else 1f
            val add = if (inverted) 255f else 0f
            return ColorMatrixColorFilter(
                floatArrayOf(
                    sign * r, 0f, 0f, 0f, add * r,
                    0f, sign * g, 0f, 0f, add * g,
                    0f, 0f, sign * b, 0f, add * b,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }
    }
}
