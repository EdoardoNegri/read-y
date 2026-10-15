package io.github.edoardonegri.ready

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * A book file of the library, read at any place straight from what the system handed out for it:
 * the app may not open it again by a path, as RandomAccessFile and ZipFile would.
 */
class BookFile(pfd: ParcelFileDescriptor) : Closeable {
    private val channel: FileChannel = ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
    private var at = 0L

    fun length(): Long = channel.size()

    fun seek(pos: Long) {
        at = pos
    }

    /** Fills [bytes] from where the last read ended, or from where [seek] went. */
    fun readFully(bytes: ByteArray) {
        readAt(at, bytes, 0, bytes.size)
        at += bytes.size
    }

    /** Reads [len] bytes from [pos] into [into], from [off] on; it does not move what [readFully] reads next. */
    fun readAt(pos: Long, into: ByteArray, off: Int, len: Int) {
        val buf = ByteBuffer.wrap(into, off, len)
        while (buf.hasRemaining()) {
            if (channel.read(buf, pos + buf.position() - off) < 0) throw EOFException()
        }
    }

    /** [len] bytes from [pos] on, as a stream; several may be read at once. */
    fun stream(pos: Long, len: Long): InputStream = object : InputStream() {
        private var next = pos
        private val end = pos + len

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, n: Int): Int {
            if (next >= end) return -1
            val want = minOf(n.toLong(), end - next).toInt()
            val got = channel.read(ByteBuffer.wrap(b, off, want), next)
            if (got <= 0) return -1
            next += got
            return got
        }

        override fun available() = minOf(end - next, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() = channel.close()
}

/** A zip file, such as an EPUB or a comic, read from a [BookFile]: what [java.util.zip.ZipFile] does, for a file it cannot open. */
class ZipArchive(private val file: BookFile) : Closeable {
    class Entry(val name: String, val size: Long, internal val packed: Long, internal val method: Int, internal val header: Long) {
        val isDirectory get() = name.endsWith("/")
    }

    private val byName = LinkedHashMap<String, Entry>()

    init {
        try {
            read()
        } catch (e: Exception) {
            file.close()
            throw e
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
    private fun u64(b: ByteArray, i: Int) = u32(b, i) or (u32(b, i + 4) shl 32)

    private fun bytes(pos: Long, len: Int) = ByteArray(len).also { file.readAt(pos, it, 0, len) }

    /** Reads the list of what the file holds, kept at its end. */
    private fun read() {
        val length = file.length()
        val tailLen = minOf(length, 0xFFFFL + 22).toInt()
        val tail = bytes(length - tailLen, tailLen)
        var end = tailLen - 22
        while (end >= 0 && u32(tail, end) != 0x06054b50L) end--
        if (end < 0) throw IOException("Not a zip file")
        var count = u16(tail, end + 10).toLong()
        var dirLen = u32(tail, end + 12)
        var dirAt = u32(tail, end + 16)
        // A file too large for those fields gives them again, larger, in a record of its own.
        if (end >= 20 && u32(tail, end - 20) == 0x07064b50L) {
            val z = bytes(u64(tail, end - 20 + 8), 56)
            if (u32(z, 0) == 0x06064b50L) {
                count = u64(z, 32)
                dirLen = u64(z, 40)
                dirAt = u64(z, 48)
            }
        }
        if (dirLen > Int.MAX_VALUE || dirAt + dirLen > length) throw IOException("Broken zip file")
        val dir = bytes(dirAt, dirLen.toInt())
        var i = 0
        for (n in 0 until count) {
            if (i + 46 > dir.size || u32(dir, i) != 0x02014b50L) break
            val method = u16(dir, i + 10)
            var packed = u32(dir, i + 20)
            var size = u32(dir, i + 24)
            val nameLen = u16(dir, i + 28)
            val extraLen = u16(dir, i + 30)
            val commentLen = u16(dir, i + 32)
            var header = u32(dir, i + 42)
            val name = String(dir, i + 46, nameLen, Charsets.UTF_8)
            // Sizes and places too large for their fields are given in an extra field.
            var e = i + 46 + nameLen
            val extraEnd = e + extraLen
            while (e + 4 <= extraEnd) {
                val id = u16(dir, e)
                val len = u16(dir, e + 2)
                if (id == 1) {
                    var p = e + 4
                    if (size == 0xFFFFFFFFL) size = u64(dir, p).also { p += 8 }
                    if (packed == 0xFFFFFFFFL) packed = u64(dir, p).also { p += 8 }
                    if (header == 0xFFFFFFFFL) header = u64(dir, p)
                }
                e += 4 + len
            }
            byName.putIfAbsent(name, Entry(name, size, packed, method, header))
            i = extraEnd + commentLen
        }
    }

    fun entries(): List<Entry> = byName.values.toList()

    fun getEntry(name: String): Entry? = byName[name]

    fun getInputStream(e: Entry): InputStream {
        val local = bytes(e.header, 30)
        if (u32(local, 0) != 0x04034b50L) throw IOException("Broken zip entry ${e.name}")
        val raw = file.stream(e.header + 30 + u16(local, 26) + u16(local, 28), e.packed)
        return when (e.method) {
            0 -> raw
            // The inflater wants a byte more than the data to see that it ended.
            // Read in large pieces: each read of the file is a call to the system.
            8 -> object : InflaterInputStream(SequenceInputStream(raw, ByteArray(1).inputStream()), Inflater(true), INFLATE_BUFFER) {
                override fun close() {
                    super.close()
                    inf.end()
                }
            }
            else -> throw IOException("Unsupported compression in ${e.name}")
        }
    }

    override fun close() = file.close()
}

/** How much of a packed entry [ZipArchive] reads from its file at once. */
private const val INFLATE_BUFFER = 32 shl 10
