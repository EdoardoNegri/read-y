package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
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
    file: File,
    private val s: ReaderSettings,
    start: BookState,
    private val host: ReaderHost,
) : ReaderEngine {

    private val source = PageSource.open(file)
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

    private fun report() = host.onPosition(page, 0f, (page + 1f) / count, "Page ${page + 1}/$count", null)

    /** Draws page [i] for the current view size in the background and delivers it when done. */
    private fun request(i: Int) {
        if (i !in 0 until count || cache.get(i) != null) return
        val w = frame.width
        val h = if (s.vertical) 0 else frame.height
        if (w == 0 || frame.height == 0 || !pending.add(i)) return
        val gen = generation
        exec.execute {
            // The page stays blank if it cannot be drawn.
            val bmp = try {
                if (closed) null else source.render(i, w, h)
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
                    if (i == page) single.setImageBitmap(result)
                } else {
                    (list.findViewHolderForAdapterPosition(i) as? Holder)?.let {
                        it.img.ratio = ratios[i]
                        it.img.setImageBitmap(result)
                    }
                }
            }
        }
    }

    private fun showSingle() {
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
        frame.setBackgroundColor(s.palette.bg)
        // Dark mode shows print with its colours turned round; pictures stay as they are.
        filter = if (s.darkMode && source.print) INVERT else null
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
        page = (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()
        if (s.vertical) {
            lm.scrollToPositionWithOffset(page, 0)
            report()
        } else {
            showSingle()
        }
    }

    override fun close() {
        closed = true
        exec.execute { source.close() }
        exec.shutdown()
    }

    private companion object {
        val INVERT = ColorMatrixColorFilter(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}
