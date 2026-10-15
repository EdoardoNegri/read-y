package io.github.edoardonegri.ready

import java.io.File

/**
 * Reads the title, the author and the date a PDF carries in its information dictionary. Android's PdfRenderer
 * gives no access to them, so the file is searched for the dictionary directly. That finds it in
 * most files; where it is packed away in a compressed stream, or the file is encrypted, nothing is
 * found and the book goes by its file name.
 */
object PdfInfo {
    private const val CHUNK = 1 shl 20
    private const val OVERLAP = 256
    private const val MAX_SIZE = 300L shl 20

    /** Title, author and the date the file was made, any of which may be empty; null if the file gives none. */
    fun read(f: File): Triple<String, String, String>? = try {
        if (Store.length(f) > MAX_SIZE) null
        else Store.open(f).use { file ->
            val info = last(file, Regex("/Info\\s+(\\d+)\\s+(\\d+)\\s+R"))
            val text = when {
                info == null -> null
                last(file, Regex("/Encrypt\\s")) != null -> null
                else -> {
                    val (number, generation) = info.second.destructured
                    last(file, Regex("(?<![0-9])$number\\s+$generation\\s+obj"))?.let { at ->
                        text(file, at.first, 16 shl 10).substringBefore("endobj")
                    }
                }
            }
            val title = text?.let { value(it, "Title") }.orEmpty().trim()
            val author = text?.let { value(it, "Author") }.orEmpty().trim()
            // Many files carry the name of the program's working file rather than a title.
            val real = title.takeUnless {
                it.equals("untitled", true) || it.startsWith("Microsoft Word -") || it.endsWith(".doc", true) ||
                    it.endsWith(".docx", true) || it.endsWith(".tex", true) || it.endsWith(".dvi", true)
            }.orEmpty()
            val date = text?.let { value(it, "CreationDate") }.orEmpty().trim()
            if (real.isEmpty() && author.isEmpty() && date.isEmpty()) null else Triple(real, author, date)
        }
    } catch (e: Exception) {
        null
    }

    /** The bytes of the file from [at], as text with one character per byte. */
    private fun text(file: BookFile, at: Long, length: Int): String {
        val bytes = ByteArray(minOf(length.toLong(), file.length() - at).toInt().coerceAtLeast(0))
        file.seek(at)
        file.readFully(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }

    /** Where in the file [pattern] matches last, with the match. */
    private fun last(file: BookFile, pattern: Regex): Pair<Long, MatchResult>? {
        var found: Pair<Long, MatchResult>? = null
        var at = 0L
        while (at < file.length()) {
            // The chunks overlap, so that a match lying across their border is not missed.
            pattern.findAll(text(file, at, CHUNK + OVERLAP)).lastOrNull()?.let { found = at + it.range.first to it }
            at += CHUNK
        }
        return found
    }

    /** The text string that the dictionary [dict] gives for [key], or null. */
    private fun value(dict: String, key: String): String? {
        var i = Regex("/$key(?![A-Za-z])").find(dict)?.range?.last?.plus(1) ?: return null
        while (i < dict.length && dict[i].isWhitespace()) i++
        if (i >= dict.length) return null
        val bytes = ArrayList<Byte>()
        if (dict[i] == '(') {
            var depth = 1
            i++
            while (i < dict.length && depth > 0) {
                val c = dict[i++]
                when {
                    c == '\\' && i < dict.length -> {
                        when (val e = dict[i++]) {
                            'n' -> bytes.add('\n'.code.toByte())
                            'r' -> bytes.add('\r'.code.toByte())
                            't' -> bytes.add('\t'.code.toByte())
                            'b', 'f' -> {}
                            // A line break after a backslash only continues the string.
                            '\n', '\r' -> {}
                            in '0'..'7' -> {
                                var code = e - '0'
                                var digits = 1
                                while (digits < 3 && i < dict.length && dict[i] in '0'..'7') {
                                    code = code * 8 + (dict[i++] - '0')
                                    digits++
                                }
                                bytes.add(code.toByte())
                            }
                            else -> bytes.add(e.code.toByte())
                        }
                    }
                    c == '(' -> {
                        depth++
                        bytes.add(c.code.toByte())
                    }
                    c == ')' -> if (--depth > 0) bytes.add(c.code.toByte())
                    else -> bytes.add(c.code.toByte())
                }
            }
        } else if (dict[i] == '<' && dict.getOrNull(i + 1) != '<') {
            val hex = dict.substring(i + 1).substringBefore('>').filter { it.isLetterOrDigit() }
            for (pair in hex.chunked(2)) bytes.add(pair.padEnd(2, '0').toInt(16).toByte())
        } else {
            return null
        }
        val raw = bytes.toByteArray()
        return when {
            raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte() -> String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
            raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte() ->
                String(raw, 3, raw.size - 3, Charsets.UTF_8)
            else -> String(raw, Charsets.ISO_8859_1)
        }
    }
}
