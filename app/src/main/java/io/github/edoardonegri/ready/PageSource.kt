package io.github.edoardonegri.ready

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.min
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
     * Draws the page [i] as large as fits into [w] by [h], or as wide as [w] if [h] is 0. Null if
     * it cannot be drawn.
     */
    fun render(i: Int, w: Int, h: Int): Bitmap?

    companion object {
        /** The endings of the files that can be read as fixed pages. */
        val EXTENSIONS = setOf("pdf", "cbz")

        /** No page is drawn with more dots than this, however large the screen. */
        private const val MAX_PIXELS = 12_000_000f

        fun open(file: File): PageSource = if (file.extension.equals("pdf", true)) PdfPages(file) else CbzPages(file)

        /** How much a page of [pw] by [ph] is enlarged to fit, see [render]. */
        private fun scale(pw: Int, ph: Int, w: Int, h: Int): Float {
            val scale = if (h <= 0) w.toFloat() / pw else min(w.toFloat() / pw, h.toFloat() / ph)
            val pixels = pw * scale * ph * scale
            return if (pixels > MAX_PIXELS) scale * sqrt(MAX_PIXELS / pixels) else scale
        }
    }

    private class PdfPages(file: File) : PageSource {
        private val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
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

        override fun render(i: Int, w: Int, h: Int): Bitmap = renderer.openPage(i).use { p ->
            val scale = scale(p.width, p.height, w, h)
            val bmp = createBitmap(
                (p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
            )
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }

        override fun close() = renderer.close()
    }

    /** A comic book: a zip file of pictures, which are its pages in the order of their names. */
    private class CbzPages(file: File) : PageSource {
        private val zip = ZipFile(file)
        private val pages: List<ZipEntry> = zip.entries().asSequence()
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

        override fun render(i: Int, w: Int, h: Int): Bitmap? {
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
