package com.example.read_y

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** Minimal EPUB container reader: metadata, reading order (spine), cover and resource streams. */
class EpubBook private constructor(private val zip: ZipFile) : Closeable {

    var title = ""
        private set
    var author = ""
        private set

    /** Zip entry paths of the content documents, in reading order. */
    val spine = ArrayList<String>()

    /** Uncompressed size of each spine document, used to estimate progress through the book. */
    var weights = LongArray(0)
        private set

    private val mimes = HashMap<String, String>()
    private var coverPath: String? = null

    companion object {
        fun open(file: File): EpubBook {
            val book = EpubBook(ZipFile(file))
            try {
                book.parse()
            } catch (e: Exception) {
                book.close()
                throw e
            }
            return book
        }
    }

    private fun parse() {
        val container = zip.getEntry("META-INF/container.xml") ?: throw IOException("Not an EPUB")
        val opf = zip.getInputStream(container).use(::findRootFile) ?: throw IOException("No rootfile")
        val opfEntry = zip.getEntry(opf) ?: throw IOException("Missing $opf")
        val base = opf.substringBeforeLast('/', "")

        val manifest = HashMap<String, String>()
        val spineIds = ArrayList<String>()
        val authors = ArrayList<String>()
        var coverId: String? = null

        zip.getInputStream(opfEntry).use { ins ->
            val x = Xml.newPullParser()
            x.setInput(ins, null)
            var ev = x.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (x.name.substringAfter(':')) {
                        "title" -> if (title.isEmpty()) title = text(x)
                        "creator" -> text(x).let { if (it.isNotEmpty()) authors.add(it) }
                        "meta" -> if (x.getAttributeValue(null, "name") == "cover") {
                            coverId = x.getAttributeValue(null, "content")
                        }
                        "item" -> {
                            val id = x.getAttributeValue(null, "id")
                            val href = x.getAttributeValue(null, "href")
                            if (id != null && href != null) {
                                val path = resolve(base, href)
                                manifest[id] = path
                                x.getAttributeValue(null, "media-type")?.let { mimes[path] = it }
                                if (x.getAttributeValue(null, "properties")?.contains("cover-image") == true) {
                                    coverPath = path
                                }
                            }
                        }
                        "itemref" -> x.getAttributeValue(null, "idref")?.let(spineIds::add)
                    }
                }
                ev = x.next()
            }
        }

        author = authors.joinToString(", ")
        for (id in spineIds) {
            val path = manifest[id] ?: continue
            if (zip.getEntry(path) != null) spine.add(path)
        }
        weights = LongArray(spine.size) { zip.getEntry(spine[it]).size.coerceAtLeast(1) }
        if (coverPath == null) coverPath = coverId?.let { manifest[it] }
        if (coverPath == null) {
            coverPath = manifest.values.firstOrNull {
                mimeOf(it).startsWith("image/") && it.substringAfterLast('/').contains("cover", true)
            }
        }
    }

    private fun findRootFile(ins: InputStream): String? {
        val x = Xml.newPullParser()
        x.setInput(ins, null)
        var ev = x.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && x.name.substringAfter(':') == "rootfile") {
                return x.getAttributeValue(null, "full-path")
            }
            ev = x.next()
        }
        return null
    }

    private fun text(x: XmlPullParser): String = try {
        x.nextText().trim()
    } catch (e: Exception) {
        ""
    }

    private fun resolve(base: String, href: String): String {
        val parts = ArrayList<String>()
        if (base.isNotEmpty()) parts.addAll(base.split('/'))
        for (s in Uri.decode(href.substringBefore('#')).split('/')) {
            when (s) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(s)
            }
        }
        return parts.joinToString("/")
    }

    fun mimeOf(path: String): String = mimes[path] ?: when (path.substringAfterLast('.').lowercase()) {
        "xhtml", "xml" -> "application/xhtml+xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    fun stream(path: String): InputStream? = try {
        zip.getEntry(path)?.let(zip::getInputStream)
    } catch (e: Exception) {
        null
    }

    fun coverBitmap(maxWidth: Int): Bitmap? {
        val bytes = stream(coverPath ?: return null)?.use { it.readBytes() } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0) return null
        opts.inSampleSize = (opts.outWidth / maxWidth).coerceAtLeast(1)
        opts.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bmp.width <= maxWidth) return bmp
        return Bitmap.createScaledBitmap(bmp, maxWidth, (maxWidth.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
    }

    override fun close() = zip.close()
}
