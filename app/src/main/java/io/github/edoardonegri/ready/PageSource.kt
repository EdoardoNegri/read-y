package io.github.edoardonegri.ready

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A book of fixed pages, each a picture: PageEngine shows it. Apart from [count] and
 * [firstRatio] it must be used from one thread only.
 */
interface PageSource : Closeable {
    val count: Int

    /** Height over width of the first page, which the others are taken to share until they are drawn. */
    val firstRatio: Float

    /** Whether the pages are print on white paper, which dark mode may turn round; pictures are left as they are. */
    val print: Boolean

    /**
     * Draws the page [i] as large as fits into [w] by [h], or as wide as [w] if [h] is 0; if
     * [trim], printed pages without the white margins around their print, which leaves them
     * larger. Null if it cannot be drawn.
     */
    fun render(i: Int, w: Int, h: Int, trim: Boolean = false): Bitmap?

    companion object {
        /** The endings of the files that can be read as fixed pages. */
        val EXTENSIONS = setOf("pdf", "cbz")

        /** No page is drawn with more dots than this, however large the screen. */
        private const val MAX_PIXELS = 12_000_000f

        /** All of a page, as a share of it. */
        private val WHOLE = RectF(0f, 0f, 1f, 1f)

        /** How wide the picture of a page is that its print is looked for on, in dots. */
        private const val SCAN_W = 200

        /** A dot is white, and no print, if each of its colours is at least this bright, of 255. */
        private const val INK = 225

        /** How much of the white around the print is kept, as a share of the width of the page on each side. */
        private const val SPARE = 0.02f

        /** Print that takes more of the page than this, as a share of its area, is shown with its margins. */
        private const val KEEP = 0.9f

        /** How many pages, spread through a book, its margins are worked out from (PdfPages.bookPrint). */
        private const val SAMPLES = 16

        fun open(file: File): PageSource = if (file.extension.equals("pdf", true)) PdfPages(file) else CbzPages(file)

        /** How much a page of [pw] by [ph] is enlarged to fit, see [render]. */
        private fun scale(pw: Int, ph: Int, w: Int, h: Int): Float {
            val scale = if (h <= 0) w.toFloat() / pw else min(w.toFloat() / pw, h.toFloat() / ph)
            val pixels = pw * scale * ph * scale
            return if (pixels > MAX_PIXELS) scale * sqrt(MAX_PIXELS / pixels) else scale
        }
    }

    private class PdfPages(file: File) : PageSource {
        private val renderer = PdfRenderer(Store.descriptor(file))
        override val count = renderer.pageCount
        override val firstRatio: Float
        override val print get() = true

        init {
            if (count == 0) {
                renderer.close()
                throw IOException("PDF has no pages")
            }
            firstRatio = renderer.openPage(0).use { it.height.toFloat() / it.width }
        }

        /** The part of each page its print is in, as a share of the page, by page, once it has been looked for (see [print]). */
        private val prints = HashMap<Int, RectF>()

        /**
         * The part of its pages the book has its print in, as a share of a page: the print of
         * pages spread through the book, all together, see [trimmed]. Null until it is looked for.
         */
        private var bookPrint: RectF? = null

        /**
         * The part of the page [i] shown without its margins: the same for every page whose print
         * lies within that of the book ([bookPrint]), so that the print does not move or change size
         * from one page to the next, as it would cut to each page's own; a page that has more, as
         * much more as it has; one with no margins worth leaving out, all of it.
         */
        private fun trimmed(i: Int): RectF {
            val own = printOf(i)
            if (own === WHOLE) return WHOLE
            return RectF(bookPrint()).apply { union(own) }
        }

        private fun printOf(i: Int) = prints.getOrPut(i) { renderer.openPage(i).use(::print) }

        private fun bookPrint(): RectF = bookPrint ?: run {
            // Pages spread evenly through the book, past the first, which is often its cover.
            val box = RectF()
            val n = min(count, SAMPLES)
            for (k in 0 until n) {
                val i = if (count <= SAMPLES || n < 2) k else (1 + k.toLong() * (count - 2) / (n - 1)).toInt()
                // Pages with no margins worth leaving out have no say in those of the others.
                val part = printOf(i)
                if (part !== WHOLE) box.union(part)
            }
            (if (box.isEmpty) WHOLE else box).also { bookPrint = it }
        }

        override fun render(i: Int, w: Int, h: Int, trim: Boolean): Bitmap {
            // Looked for before the page is opened to be drawn: only one page can be open at a time.
            val part = if (trim) trimmed(i) else WHOLE
            return draw(i, w, h, part)
        }

        private fun draw(i: Int, w: Int, h: Int, part: RectF): Bitmap = renderer.openPage(i).use { p ->
            val pw = (p.width * part.width()).roundToInt().coerceAtLeast(1)
            val ph = (p.height * part.height()).roundToInt().coerceAtLeast(1)
            val scale = scale(pw, ph, w, h)
            val bmp = createBitmap((pw * scale).toInt().coerceAtLeast(1), (ph * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            // The page is drawn as large, and moved for the part of it to fill the picture.
            val place = Matrix().apply {
                setScale(scale, scale)
                preTranslate(-part.left * p.width, -part.top * p.height)
            }
            p.render(bmp, null, place, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }

        /**
         * Where on the page [p] its print is, as a share of the page, found on a small picture of
         * it: all that is not white, and a little of the white around it. All of the page if it
         * is blank, if its paper is not white, as on a scan, or if its margins are too narrow to
         * be worth leaving out.
         */
        private fun print(p: PdfRenderer.Page): RectF {
            val w = SCAN_W
            val h = (w.toFloat() * p.height / p.width).roundToInt().coerceIn(1, w * 4)
            val small = createBitmap(w, h)
            small.eraseColor(Color.WHITE)
            p.render(small, null, Matrix().apply { setScale(w.toFloat() / p.width, h.toFloat() / p.height) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val dots = IntArray(w * h)
            small.getPixels(dots, 0, w, 0, 0, w, h)
            small.recycle()
            var left = w
            var top = h
            var right = -1
            var bottom = -1
            for (y in 0 until h) for (x in 0 until w) {
                val c = dots[y * w + x]
                if (Color.red(c) >= INK && Color.green(c) >= INK && Color.blue(c) >= INK) continue
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
            }
            if (right < 0) return WHOLE
            val spare = SPARE * w
            val part = RectF((left - spare) / w, (top - spare) / h, (right + 1 + spare) / w, (bottom + 1 + spare) / h)
            if (!part.intersect(WHOLE) || part.width() * part.height() > KEEP) return WHOLE
            return part
        }

        override fun close() = renderer.close()
    }

    /** A comic book: a zip file of pictures, which are its pages in the order of their names. */
    private class CbzPages(file: File) : PageSource {
        private val zip = ZipArchive(Store.open(file))
        private val pages: List<ZipArchive.Entry> = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGES && !it.name.substringAfterLast('/').startsWith(".") }
            .sortedWith { a, b -> compareNames(a.name, b.name) }
            .toList()
        override val count = pages.size
        override val firstRatio: Float
        override val print get() = false

        init {
            if (count == 0) {
                zip.close()
                throw IOException("The comic has no pages")
            }
            firstRatio = bounds(0).let { if (it.outWidth > 0) it.outHeight.toFloat() / it.outWidth else 1.5f }
        }

        private fun bounds(i: Int) = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            zip.getInputStream(pages[i]).use { BitmapFactory.decodeStream(it, null, this) }
        }

        // A picture is shown whole, whatever is around what it shows.
        override fun render(i: Int, w: Int, h: Int, trim: Boolean): Bitmap? {
            val size = bounds(i)
            if (size.outWidth <= 0) return null
            // Read at the smallest size that is still at least as large as it will be shown.
            val scale = scale(size.outWidth, size.outHeight, w, h)
            var sample = 1
            while (scale * sample * 2 <= 1f) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return zip.getInputStream(pages[i]).use { BitmapFactory.decodeStream(it, null, opts) }
        }

        override fun close() = zip.close()

        private companion object {
            val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

            /** Orders names the way people number files: "page 9" before "page 10". */
            fun compareNames(a: String, b: String): Int {
                var i = 0
                var j = 0
                while (i < a.length && j < b.length) {
                    if (a[i].isDigit() && b[j].isDigit()) {
                        val si = i
                        val sj = j
                        while (i < a.length && a[i].isDigit()) i++
                        while (j < b.length && b[j].isDigit()) j++
                        val x = a.substring(si, i).trimStart('0')
                        val y = b.substring(sj, j).trimStart('0')
                        if (x.length != y.length) return x.length - y.length
                        x.compareTo(y).let { if (it != 0) return it }
                    } else {
                        val x = a[i].lowercaseChar()
                        val y = b[j].lowercaseChar()
                        if (x != y) return x - y
                        i++
                        j++
                    }
                }
                return (a.length - i) - (b.length - j)
            }
        }
    }
}
