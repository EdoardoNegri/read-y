package io.github.edoardonegri.ready

import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * A Mobipocket book (.mobi, and the .azw and .prc files of the same make) without copy protection:
 * one long HTML text kept in compressed records, followed by its pictures. The text is cut into
 * parts where the book asks for a new page. Books in the later KF8 form alone, and those packed
 * with the rarer Huffman scheme, cannot be read.
 */
class MobiBook(file: File) : PartedBook() {
    private val data = Store.open(file)

    /** Where each record starts, and after the last one where the file ends. */
    private val offsets: IntArray
    private var firstImage = -1
    private var cover = -1
    override var title: String = file.nameWithoutExtension
        private set
    override var author = ""
    override var published = ""
        private set
    override val pageWeight get() = 2400

    init {
        try {
            val head = ByteArray(78)
            data.readFully(head)
            if (String(head, 60, 8, Charsets.ISO_8859_1) != "BOOKMOBI") throw IOException("Not a Mobipocket book")
            val count = u16(head, 76)
            val table = ByteArray(count * 8)
            data.readFully(table)
            offsets = IntArray(count + 1) { if (it < count) u32(table, it * 8) else data.length().toInt() }

            val first = record(0)
            val compression = u16(first, 0)
            val textRecords = u16(first, 8)
            if (u16(first, 12) != 0) throw IOException("The book is copy-protected")
            if (compression != 1 && compression != 2) throw IOException("Unknown compression $compression")
            val mobi = first.size >= 132 && String(first, 16, 4, Charsets.ISO_8859_1) == "MOBI"
            val headerLength = if (mobi) u32(first, 20) else 0
            val charset = if (mobi && u32(first, 28) == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
            var trailing = 0
            if (mobi) {
                firstImage = u32(first, 108)
                val nameAt = u32(first, 84)
                val nameLength = u32(first, 88)
                if (nameLength > 0 && nameAt + nameLength <= first.size) title = String(first, nameAt, nameLength, charset)
                if (headerLength >= 228 && first.size >= 244) trailing = u16(first, 242)
                if (u32(first, 128) and 0x40 != 0) readExth(first, 16 + headerLength, charset)
            }

            val text = Bytes(textRecords * 4096)
            for (i in 1..textRecords.coerceAtMost(count - 1)) {
                val r = record(i)
                val size = r.size - trailingSize(r, trailing)
                if (compression == 2) inflate(r, size, text) else text.add(r, 0, size)
            }
            split(String(text.array, 0, text.size, charset))
        } catch (e: Exception) {
            data.close()
            throw e as? IOException ?: IOException("Cannot read the book", e)
        }
    }

    /** Reads the details kept after the header: who wrote the book and when it was published, its full title, which picture is its cover. */
    private fun readExth(r: ByteArray, at: Int, charset: Charset) {
        if (at + 12 > r.size || String(r, at, 4, Charsets.ISO_8859_1) != "EXTH") return
        val authors = ArrayList<String>()
        var p = at + 12
        repeat(u32(r, at + 8)) {
            if (p + 8 > r.size) return@repeat
            val length = u32(r, p + 4)
            if (length < 8 || p + length > r.size) return@repeat
            when (u32(r, p)) {
                100 -> authors.add(String(r, p + 8, length - 8, charset).trim())
                106 -> published = String(r, p + 8, length - 8, charset).trim()
                503 -> title = String(r, p + 8, length - 8, charset).trim()
                201 -> if (length >= 12) cover = u32(r, p + 8)
            }
            p += length
        }
        author = authors.joinToString(", ")
    }

    /** Cuts the text into parts where the book starts a new page, and long parts once more where a paragraph ends. */
    private fun split(html: String) {
        val body = html.replace(SHELL, "").replace(IMAGE) { "src=\"img/${it.groupValues[1]}\"" }
        for (page in body.split(BREAK)) {
            var rest = page
            while (rest.isNotBlank()) {
                val cut = if (rest.length <= LONG) -1 else rest.indexOf("</p>", PART, ignoreCase = true)
                val piece = if (cut < 0) rest else rest.substring(0, cut + 4)
                rest = if (cut < 0) "" else rest.substring(cut + 4)
                // A page of nothing but tags, such as an empty title page, is left out.
                if (!piece.contains("<img", true) && piece.replace(TAG, "").isBlank()) continue
                addPart(piece, HEADING.find(piece.take(1500))?.groupValues?.get(1)?.replace(TAG, "")?.trim()?.ifEmpty { null })
            }
        }
        if (spine.isEmpty()) throw IOException("The book has no text")
        listParts()
    }

    @Synchronized
    private fun record(i: Int): ByteArray {
        if (i < 0 || i + 1 >= offsets.size || offsets[i + 1] < offsets[i]) throw IOException("No record $i")
        val bytes = ByteArray(offsets[i + 1] - offsets[i])
        data.seek(offsets[i].toLong())
        data.readFully(bytes)
        return bytes
    }

    override fun mimeOf(path: String) = if (path.startsWith("img/")) "image/jpeg" else super.mimeOf(path)

    override fun stream(path: String): InputStream? = try {
        if (path.startsWith("img/")) record(firstImage + path.substring(4).toInt() - 1).inputStream() else super.stream(path)
    } catch (e: Exception) {
        null
    }

    override fun coverBitmap(maxWidth: Int): Bitmap? = try {
        if (firstImage < 0) null else FlowBook.decodeImage(record(firstImage + cover.coerceAtLeast(0)), maxWidth)
    } catch (e: Exception) {
        null
    }

    @Synchronized
    override fun close() = data.close()

    /** Bytes written one after the other, which can be read again while more are added. */
    private class Bytes(capacity: Int) {
        var array = ByteArray(capacity.coerceAtLeast(64))
        var size = 0

        private fun room(more: Int) {
            if (size + more > array.size) array = array.copyOf(maxOf(array.size * 2, size + more))
        }

        fun add(b: Int) {
            room(1)
            array[size++] = b.toByte()
        }

        fun add(from: ByteArray, start: Int, length: Int) {
            room(length)
            System.arraycopy(from, start, array, size, length)
            size += length
        }
    }

    private companion object {
        /** Parts longer than [LONG] letters are cut after about [PART]. */
        const val PART = 60_000
        const val LONG = 120_000
        val BREAK = Regex("<\\s*mbp:pagebreak[^>]*>(\\s*</\\s*mbp:pagebreak\\s*>)?", RegexOption.IGNORE_CASE)

        /** What wraps the whole text, which every part gets anew. */
        val SHELL = Regex("</?\\s*(html|body)[^>]*>|<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        /** Pictures are named by their number among the records. */
        val IMAGE = Regex("recindex\\s*=\\s*[\"']?0*(\\d+)[\"']?", RegexOption.IGNORE_CASE)
        val TAG = Regex("<[^>]*>")
        val HEADING = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF shl 8) or (b[at + 1].toInt() and 0xFF)
        fun u32(b: ByteArray, at: Int) = (u16(b, at) shl 16) or u16(b, at + 2)

        /** How many bytes at the end of the text record [r] are not text, by the [flags] of the book. */
        fun trailingSize(r: ByteArray, flags: Int): Int {
            var extra = 0
            var bits = flags shr 1
            while (bits != 0) {
                // Each entry ends with its own length, written backwards seven bits at a time.
                if (bits and 1 != 0) {
                    var at = r.size - extra
                    var length = 0
                    var shift = 0
                    while (at > 0) {
                        val v = r[--at].toInt()
                        length = length or (v and 0x7F shl shift)
                        shift += 7
                        if (v and 0x80 != 0 || shift >= 28) break
                    }
                    extra += length
                }
                bits = bits shr 1
            }
            // The rest of a letter that was cut in two by the end of the record.
            if (flags and 1 != 0 && r.size - extra > 0) extra += (r[r.size - extra - 1].toInt() and 3) + 1
            return extra.coerceIn(0, r.size)
        }

        /** Unpacks the first [size] bytes of [r], packed the PalmDOC way, onto the end of [out]. */
        fun inflate(r: ByteArray, size: Int, out: Bytes) {
            val start = out.size
            var i = 0
            while (i < size) {
                val c = r[i++].toInt() and 0xFF
                when {
                    c in 1..8 -> {
                        val n = c.coerceAtMost(size - i)
                        out.add(r, i, n)
                        i += n
                    }
                    c < 0x80 -> out.add(c)
                    c >= 0xC0 -> {
                        out.add(' '.code)
                        out.add(c xor 0x80)
                    }
                    i < size -> {
                        // A stretch of what was written a little earlier, written once more.
                        val pair = (c shl 8 or (r[i++].toInt() and 0xFF)) and 0x3FFF
                        val back = pair shr 3
                        if (back in 1..out.size - start) repeat((pair and 7) + 3) { out.add(out.array[out.size - back].toInt()) }
                    }
                }
            }
        }
    }
}
