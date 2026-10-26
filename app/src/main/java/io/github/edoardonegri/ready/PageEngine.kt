package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
import androidx.core.graphics.createBitmap
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
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

    /** The pages that could not be drawn once, see [request]. */
    private val failed = HashSet<Int>()
    private val ratios = FloatArray(count) { source.firstRatio }
    private var generation = 0
    /**
     * A book whose cover is its first page is read from the second: the first is the cover, which
     * is seen at the end, so the pages that are read are one fewer. Only where pages turn.
     */
    private val coverFirst = Library.coverMode(act.applicationContext, file) == Library.CoverMode.FIRST_PAGE && count > 1
    private val skip get() = if (coverFirst && !s.vertical) 1 else 0

    /**
     * How many pages are on the screen at once: two side by side, as the two of an open book, on a
     * screen on its side if that is asked for (ReaderSettings.spread), from the first page read; else one.
     * The two are kept in [cache] as one picture, under the first of them.
     */
    private val per get() = if (s.spread && frame.width > frame.height) 2 else 1

    /** The first of the pages on the screen with page [i]; the cover that ends the book is the cover. */
    private fun firstOf(i: Int) = if (i >= count) count else skip + (i - skip).coerceAtLeast(0) / per * per

    // A book read to its end opens on its cover, where the pages that turn end: the page after the last one.
    private var page = if (!s.vertical && start.percent >= Library.FINISHED_AT) count else start.chapter.coerceIn(if (coverFirst && !s.vertical) 1 else 0, count - 1)

    /** A book of one page has been taken to its end, by the seek bar or by being marked as read. */
    private var atEnd = start.percent >= Library.FINISHED_AT

    /** Whether the host was told the first page is shown (ReaderHost.shown). */
    private var told = false
    private var filter: ColorFilter? = null

    @Volatile
    private var closed = false

    private val frame = FrameLayout(act)
    private val single = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

    /** The crease between two pages side by side, over the middle of [single], which they meet in; enlarged along with them. */
    private val crease = SpineCrease(act)
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
        for (v in arrayOf(single, list, crease)) {
            v.pivotX = 0f
            v.pivotY = 0f
        }
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
        frame.addView(crease, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        crease.visibility = View.GONE
        frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) frame.post { if (!closed) configure() }
        }
    }

    /**
     * Turning pages, the first page is none of the book read, and each one after it is as much of it
     * as the pages before it, up to the cover that ends the book, which is all of it. Scrolling, the
     * first page is none and the last all of it; a book of one page is read once at its end.
     */
    private fun report() {
        when {
            s.vertical -> host.onPosition(page, 0f, if (count > 1) page.toFloat() / (count - 1) else if (atEnd) 1f else 0f)
            page >= count -> host.onPosition(count - 1, 0f, 1f)
            else -> host.onPosition(page, 0f, (page - skip).toFloat() / (count - skip), null, page - skip + 1 to count - skip)
        }
    }

    /** Draws page [i] for the current view size in the background and delivers it when done. */
    private fun request(i: Int) {
        if (i == count && !s.vertical) return requestCover()
        if (i !in 0 until count || cache.get(i) != null) return
        val w = if (s.vertical) list.width.takeIf { it > 0 } ?: frame.width else frame.width
        val h = if (s.vertical) 0 else frame.height
        if (w == 0 || frame.height == 0 || !pending.add(i)) return
        val gen = generation
        val trim = s.trimMargins
        val two = per == 2
        exec.execute {
            // The page stays blank if it cannot be drawn.
            val bmp = draw(i, w, h, trim, two)
            main.post {
                if (closed || gen != generation) return@post
                pending.remove(i)
                // A page that could not be drawn is tried once more, rather than left blank until it is turned away from and back to.
                val result = bmp ?: return@post run { if (failed.add(i)) main.postDelayed({ if (!closed && gen == generation) request(i) }, RETRY_MS) }
                failed.remove(i)
                cache.put(i, result)
                if (!two) ratios[i] = result.height.toFloat() / result.width
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

    /** Draws page [i] as [PageSource.render] does, on [exec]; null if the book is closed or the page cannot be drawn. */
    private fun render(i: Int, w: Int, h: Int, trim: Boolean): Bitmap? = try {
        if (closed) null else source.render(i, w, h, trim)
    } catch (e: Throwable) {
        null
    }

    /**
     * Draws page [i] as [render] does, or if [two], it and the page after it side by side into [w]
     * by [h], each into half of it, meeting in the middle as the pages of an open book; the last page,
     * if it has none after it, stands where the first of two would.
     */
    private fun draw(i: Int, w: Int, h: Int, trim: Boolean, two: Boolean): Bitmap? {
        if (!two) return render(i, w, h, trim)
        val left = render(i, w / 2, h, trim) ?: return null
        val right = if (i + 1 < count) render(i + 1, w / 2, h, trim) else null
        if (right == null && i + 1 < count) {
            left.recycle()
            return null
        }
        return try {
            // As wide on both sides of the middle, where the crease is drawn (see [crease]).
            val half = max(left.width, right?.width ?: 0)
            createBitmap(2 * half, max(left.height, right?.height ?: 0)).also { out ->
                Canvas(out).apply {
                    drawBitmap(left, (half - left.width).toFloat(), (out.height - left.height) / 2f, null)
                    if (right != null) drawBitmap(right, half.toFloat(), (out.height - right.height) / 2f, null)
                }
            }
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            left.recycle()
            right?.recycle()
        }
    }

    /** Draws the cover that ends the book, to the size of the screen, in the background, and delivers it when done. */
    private fun requestCover() {
        if (cache.get(count) != null) return
        val w = frame.width
        val h = frame.height
        if (w == 0 || h == 0 || !pending.add(count)) return
        val gen = generation
        val app = frame.context.applicationContext
        exec.execute {
            val bmp = try {
                // In the shape of a book, with its crease in place: the cover is shown as it is, over the page.
                ShelfArt.bound(Library.endCover(app, file, w) { source.render(0, w, h, false) })
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (closed || gen != generation) return@post
                pending.remove(count)
                val result = bmp ?: return@post
                cache.put(count, result)
                if (!s.vertical && page == count && zoom == 1f) {
                    single.setImageBitmap(result)
                    placeCover()
                }
                if (!told && !s.vertical && page == count) {
                    told = true
                    host.shown()
                }
            }
        }
    }

    private fun showSingle() {
        resetZoom()
        // The cover is a picture of its own, whose colours are not turned round with those of print,
        // nor lie in the warm light, and it stands against the wall of the case the book was taken
        // from, not on paper.
        val onCover = page >= count
        if (onCover) frame.background = null else frame.setBackgroundColor(s.litPalette.bg)
        single.colorFilter = if (onCover) null else filter
        single.setImageBitmap(cache.get(page))
        crease.visibility = if (per == 2 && !onCover) View.VISIBLE else View.GONE
        placeCover()
        request(page)
        request(minOf(page + per, count))
        // Back from the cover is the last page, to be there when it is turned to rather than drawn after.
        if (onCover) request(firstOf(count - 1))
        report()
    }

    /** What the pages in [cache] were drawn for, see [applySettings]. */
    private var drawnFor = ""

    private fun drawing() = "${s.vertical}:${s.trimMargins}:$per:${frame.width}x${frame.height}"

    // Every page is drawn anew, for another size or another mode.
    @SuppressLint("NotifyDataSetChanged")
    private fun configure() {
        drawnFor = drawing()
        generation++
        pending.clear()
        cache.evictAll()
        resetZoom()
        frame.setBackgroundColor(s.litPalette.bg)
        // Dark mode shows print with its colours turned round; pictures stay as they are. Either
        // lies in the warm light.
        filter = light(s.darkMode && source.print, s.lamp)
        crease.color = creaseColor()
        if (s.vertical) {
            // The column of pages has no cover after its last page.
            page = page.coerceAtMost(count - 1)
            single.visibility = View.GONE
            crease.visibility = View.GONE
            single.setImageBitmap(null)
            list.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
            lm.scrollToPositionWithOffset(page, 0)
            report()
        } else {
            list.visibility = View.GONE
            single.visibility = View.VISIBLE
            single.colorFilter = filter
            // Coming from scrolling, where the first page is read too, and where the cover is not;
            // or from one page on the screen to two, which start with the first page read.
            page = firstOf(page.coerceIn(skip, count))
            showSingle()
        }
    }

    private fun step(direction: Int) {
        if (s.vertical) {
            val by = direction * (frame.height * 0.88f).toInt()
            if (s.slidePages) list.smoothScrollBy(0, by) else list.scrollBy(0, by)
        } else {
            val to = if (direction > 0) minOf(page + per, count) else firstOf(page - 1)
            if (to == page || to !in skip..count) return
            page = to
            showSingle()
        }
    }

    override fun next() = step(1)
    override fun prev() = step(-1)
    override fun applySettings() {
        // Only the light changed, as it does while the warmth is chosen: the pages drawn are shown in
        // it as they are, rather than every one of them being drawn anew.
        if (drawnFor == drawing()) relight() else configure()
    }

    /** The colour of the [crease]: a shadow, or on print turned round in dark mode, the faint light of its paper. */
    private fun creaseColor() = if (s.darkMode && source.print) s.litPalette.fg else Color.BLACK

    /** Shows the pages already drawn in the light the settings say. */
    @SuppressLint("NotifyDataSetChanged")
    private fun relight() {
        filter = light(s.darkMode && source.print, s.lamp)
        crease.color = creaseColor()
        if (s.vertical) {
            frame.setBackgroundColor(s.litPalette.bg)
            // The pages on screen take the filter as they are bound again; the bitmaps are in the cache.
            adapter.notifyDataSetChanged()
        } else {
            val onCover = page >= count
            if (!onCover) frame.setBackgroundColor(s.litPalette.bg)
            single.colorFilter = if (onCover) null else filter
        }
    }

    override fun seekTo(fraction: Float) {
        if (s.vertical) {
            atEnd = fraction >= 1f
            page = (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()
            lm.scrollToPositionWithOffset(page, 0)
            report()
        } else {
            page = firstOf(skip + (fraction.coerceIn(0f, 1f) * (count - skip)).roundToInt().coerceIn(0, count - skip))
            showSingle()
        }
    }

    override val hasCover get() = !s.vertical

    override val onLastPage get() = !s.vertical && page < count && page + per >= count

    /** Where the cover that ends the book stands, see [coverAt]; empty for the whole page. */
    private val coverBox = Rect()

    override fun coverAt(box: Rect) {
        coverBox.set(box)
        placeCover()
    }

    /** Has the picture fill the place of the cover, cut to its shape, if it is the cover; a page is fitted into the whole of the screen. */
    private fun placeCover() {
        val onCover = page >= count && cache.get(count) != null && !coverBox.isEmpty
        single.scaleType = if (onCover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        single.cropToPadding = onCover
        if (!onCover) return single.setPadding(0, 0, 0, 0)
        single.setPadding(coverBox.left, coverBox.top, frame.width - coverBox.right, frame.height - coverBox.bottom)
    }

    override fun snap(fraction: Float): Float {
        if (s.vertical) return fraction
        val n = count - skip
        return placeOf(fraction).toFloat() / n
    }

    override fun pagesAt(fraction: Float): Pair<Pair<Int, Int>?, Pair<Int, Int>?> {
        if (s.vertical) return null to null
        val n = count - skip
        val place = placeOf(fraction)
        return if (place >= n) null to null else null to (place + 1 to n)
    }

    /** Which of the pages read, from 0, the place [fraction] of the whole book shows first: the cover after the last. */
    private fun placeOf(fraction: Float): Int {
        val n = count - skip
        return firstOf(skip + (fraction.coerceIn(0f, 1f) * n).roundToInt().coerceIn(0, n)) - skip
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
        for (v in arrayOf(single, list, crease)) {
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
        val visual = zoom / committed
        for (view in if (s.vertical) arrayOf(list) else arrayOf(single, crease)) {
            view.scaleX = visual
            view.scaleY = visual
            view.translationX = tx
            view.translationY = if (s.vertical && visual == 1f) 0f else ty
        }
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
        // The cover that ends the book is drawn once, for the size of the screen.
        if (page >= count) return
        val w = (frame.width * zoom).toInt()
        val h = (frame.height * zoom).toInt()
        val shown = page
        val gen = generation
        val trim = s.trimMargins
        val two = per == 2
        exec.execute {
            val bmp = draw(shown, w, h, trim, two)
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
        /** How long a page that could not be drawn waits to be tried again, in ms. */
        const val RETRY_MS = 400L

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
