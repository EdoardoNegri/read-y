package io.github.edoardonegri.ready

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.core.text.htmlEncode
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A line of a book's table of contents: where it leads and how deep it is nested. */
class TocEntry(val title: String, val path: String, val anchor: String?, val depth: Int)

/**
 * A book of flowing text, which is laid out anew for every screen and size of letters: a row of
 * documents that a WebView can show, and whatever they refer to. FlowEngine reads it.
 */
interface FlowBook : Closeable {
    val title: String
    val author: String

    /** When the book was published, as the file says it; empty if it does not. */
    val published: String

    /** The language the book is written in, as the file says it (such as "en-GB"); empty if it does not. */
    val language: String get() = ""

    /** The paths of the documents, in reading order. */
    val spine: List<String>

    /** The size of each document of the [spine], used to estimate progress through the book. */
    val weights: LongArray

    /** About how much of [weights] a printed page holds. */
    val pageWeight: Int

    /** The table of contents, leading only to documents of the [spine]; empty if the book has none. */
    val toc: List<TocEntry>

    fun mimeOf(path: String): String

    /** What is kept under [path]: a document of the [spine], or a picture or the like it refers to. */
    fun stream(path: String): InputStream?

    /** The picture on the cover, no wider than [maxWidth], if the book has one. */
    fun coverBitmap(maxWidth: Int): Bitmap?

    companion object {
        /** The endings of the files that can be read as flowing text. */
        val EXTENSIONS = setOf("epub", "txt", "mobi", "azw", "prc")

        fun open(file: File): FlowBook = when (file.extension.lowercase()) {
            "epub" -> EpubBook.open(file)
            "txt" -> TxtBook(file)
            else -> MobiBook(file)
        }

        /** The book [file], as it was kept open or read ahead (BookPrep), else read now. Blocking. */
        fun take(file: File): FlowBook = BookPrep.take(file, FlowBook::class.java) ?: open(file)

        /** Decodes the picture in [bytes] no wider than [maxWidth], reading no more of it than that takes. */
        fun decodeImage(bytes: ByteArray, maxWidth: Int): Bitmap? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return null
            opts.inSampleSize = (opts.outWidth / maxWidth).coerceAtLeast(1)
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            if (bmp.width <= maxWidth) return bmp
            return bmp.scale(maxWidth, (maxWidth.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1))
        }

        /** [body] as a document of its own. */
        fun page(body: CharSequence) =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>$STYLE</style></head><body>$body</body></html>"

        private const val STYLE = "p{margin:0 0 .7em}h2{margin:1.2em 0 .8em;text-align:center}"
    }
}

/**
 * A book of flowing text that the app cuts into parts of its own, out of one long text: each
 * part a document of the [spine], named in the table of contents as it is added.
 */
abstract class PartedBook : FlowBook {
    final override val spine = ArrayList<String>()
    final override val toc = ArrayList<TocEntry>()
    private val parts = ArrayList<ByteArray>()
    private val names = ArrayList<String?>()

    /** The size of each part; asked for only once all are added. */
    final override val weights by lazy { LongArray(parts.size) { parts[it].size.toLong() } }

    /** Adds [body] as the next part, named [name] in the table of contents. */
    protected fun addPart(body: CharSequence, name: String?) {
        spine.add("part${parts.size}.html")
        parts.add(FlowBook.page(body).toByteArray())
        names.add(name)
    }

    /** Lists the parts in the table of contents, once all are added; a book of one part has none. */
    protected fun listParts() {
        if (spine.size > 1) spine.mapIndexedTo(toc) { i, path -> TocEntry(names[i].orEmpty(), path, null, 0) }
    }

    override fun mimeOf(path: String) = "text/html"
    override fun stream(path: String): InputStream? = parts.getOrNull(spine.indexOf(path))?.inputStream()
}

/**
 * A plain text file as a book. Its paragraphs are told apart by the empty lines between them,
 * or line by line where there are none, and gathered into parts that are quick to lay out; a
 * line that names a chapter starts a new one.
 */
class TxtBook(file: File) : PartedBook() {
    override val title: String = file.nameWithoutExtension
    override val author = ""
    override val published = ""
    override val pageWeight get() = 1900

    init {
        val part = StringBuilder()
        var name: String? = null
        var size = 0
        fun end() {
            if (part.isEmpty()) return
            addPart(part, name)
            part.setLength(0)
            name = null
            size = 0
        }
        for (p in paragraphs(decode(Store.readBytes(file)))) {
            val heading = p.length < 80 && HEADING.containsMatchIn(p)
            if (size > PART || heading && size > PART / 16) end()
            // The first chapter heading to come names the part, though a title or two may stand before it.
            if (heading && name == null) name = p
            val tag = if (heading) "h2" else "p"
            part.append('<').append(tag).append('>').append(p.htmlEncode()).append("</").append(tag).append('>')
            size += p.length
        }
        end()
        // An empty file is a book of one empty page.
        if (spine.isEmpty()) addPart("", null)
        listParts()
    }

    override fun coverBitmap(maxWidth: Int): Bitmap? = null
    override fun close() {}

    private companion object {
        /** About as many letters as a part holds. */
        const val PART = 24_000

        /**
         * A line that names a chapter: the word for one with its number after it, or a word such
         * as "Prologue" that stands for one alone. A line that merely begins with one of the
         * words, such as "Part of the problem is", is none.
         */
        val HEADING = Regex(
            """^\s*(?:(?:prologue|epilogue|prologo|epilogo|prolog|epilog)\b|""" +
                """(?:chapter|part|book|capitolo|parte|libro|kapitel|teil|chapitre|cap[ií]tulo)\s+""" +
                """(?:\d+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|""" +
                """uno|due|tre|quattro|cinque|sei|sette|otto|nove|dieci|primo|secondo|terzo|""" +
                """eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|""" +
                """un|deux|trois|quatre|cinq|sept|huit|neuf|dix|dos|cuatro|cinco|seis|siete|ocho|nueve|diez)\b)""",
            RegexOption.IGNORE_CASE,
        )

        /** The text in [bytes], which says by its first bytes how it is written, or else is tried as UTF-8. */
        fun decode(bytes: ByteArray): String {
            fun starts(vararg mark: Int) = bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it].toByte() }
            return when {
                starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                starts(0xFF, 0xFE) || starts(0xFE, 0xFF) -> String(bytes, Charsets.UTF_16)
                else -> try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                } catch (e: CharacterCodingException) {
                    String(bytes, Charset.forName("windows-1252"))
                }
            }
        }

        fun paragraphs(text: String): Sequence<String> {
            val plain = text.replace("\r\n", "\n").replace('\r', '\n')
            // With empty lines between the paragraphs, the lines of one belong together.
            val blocks = if ("\n\n" in plain) plain.splitToSequence(Regex("\n\\s*\n")) else plain.lineSequence()
            return blocks.map { it.trim().replace(Regex("\\s*\n\\s*"), " ") }.filter { it.isNotEmpty() }
        }
    }
}
