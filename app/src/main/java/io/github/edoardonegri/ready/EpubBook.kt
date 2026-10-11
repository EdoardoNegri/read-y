package io.github.edoardonegri.ready

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

/** A line of a book's table of contents: where it leads and how deep it is nested. */
class TocEntry(val title: String, val path: String, val anchor: String?, val depth: Int)

/** Minimal EPUB container reader: metadata, reading order (spine), contents, cover and resource streams. */
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

    /** The table of contents, leading only to documents of the [spine]; empty if the book has none. */
    val toc = ArrayList<TocEntry>()

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
        var ncx: String? = null
        var nav: String? = null

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
                                if (mimes[path] == "application/x-dtbncx+xml") ncx = path
                                if (x.getAttributeValue(null, "properties")?.split(' ')?.contains("nav") == true) nav = path
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
        // Older books list their chapters in an NCX file, newer ones in a navigation document;
        // the first is the simpler to read, so it is tried first.
        for ((path, read) in listOf(ncx to ::readNcx, nav to ::readNav)) {
            if (path == null || toc.isNotEmpty()) continue
            try {
                stream(path)?.use { read(it, path.substringBeforeLast('/', "")) }
            } catch (e: Exception) {
                toc.clear()
            }
            toc.retainAll { it.path in spine }
        }
        // A book that lists no chapters can still be gone through document by document.
        if (toc.isEmpty() && spine.size > 1) spine.forEachIndexed { i, path -> toc.add(TocEntry("Part ${i + 1}", path, null, 0)) }
        if (coverPath == null) coverPath = coverId?.let { manifest[it] }
        if (coverPath == null) {
            coverPath = manifest.values.firstOrNull {
                mimeOf(it).startsWith("image/") && it.substringAfterLast('/').contains("cover", true)
            }
        }
    }

    private fun readNcx(ins: InputStream, base: String) {
        val x = Xml.newPullParser()
        x.setInput(ins, null)
        var depth = 0
        var label = ""
        var ev = x.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val name = if (ev == XmlPullParser.START_TAG || ev == XmlPullParser.END_TAG) x.name.substringAfter(':') else ""
            if (ev == XmlPullParser.START_TAG) when (name) {
                "navPoint" -> {
                    depth++
                    label = ""
                }
                "text" -> if (depth > 0 && label.isEmpty()) label = text(x)
                "content" -> x.getAttributeValue(null, "src")?.let { if (depth > 0) addToc(label, base, it, depth - 1) }
            } else if (ev == XmlPullParser.END_TAG && name == "navPoint") {
                depth--
            }
            ev = x.next()
        }
    }

    private fun readNav(ins: InputStream, base: String) {
        val x = Xml.newPullParser()
        x.setInput(ins, null)
        var inToc = false
        var depth = 0
        var ev = x.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val name = if (ev == XmlPullParser.START_TAG || ev == XmlPullParser.END_TAG) x.name.substringAfter(':') else ""
            if (ev == XmlPullParser.START_TAG) when (name) {
                "nav" -> inToc = (0 until x.attributeCount).any {
                    x.getAttributeName(it).substringAfter(':') == "type" && "toc" in x.getAttributeValue(it).split(' ')
                }
                "ol" -> if (inToc) depth++
                "a" -> if (inToc) {
                    val href = x.getAttributeValue(null, "href")
                    // The title may be wrapped in further tags.
                    val title = StringBuilder()
                    var open = 1
                    while (open > 0) when (x.next()) {
                        XmlPullParser.TEXT -> title.append(x.text)
                        XmlPullParser.START_TAG -> open++
                        XmlPullParser.END_TAG -> open--
                        XmlPullParser.END_DOCUMENT -> return
                    }
                    if (href != null) addToc(title.toString(), base, href, (depth - 1).coerceAtLeast(0))
                }
            } else if (ev == XmlPullParser.END_TAG) when (name) {
                "ol" -> if (inToc) depth--
                "nav" -> if (inToc) return
            }
            ev = x.next()
        }
    }

    private fun addToc(title: String, base: String, href: String, depth: Int) {
        val clean = title.trim().replace(Regex("\\s+"), " ")
        if (clean.isNotEmpty()) toc.add(TocEntry(clean, resolve(base, href), href.substringAfter('#', "").ifEmpty { null }, depth))
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
