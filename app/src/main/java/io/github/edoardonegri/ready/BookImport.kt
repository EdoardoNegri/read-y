package io.github.edoardonegri.ready

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Books that other apps hand over, or that are picked from a folder of the phone, as the library
 * takes them in: copied onto a shelf, unless the same book is in the library already.
 */
object BookImport {
    private const val TAG = "BookImport"

    /**
     * A book another app handed over, as it now stands in the library. [moved] says that the
     * file it came from is to go, so that the book is kept only once: it was copied, or the same
     * book was found in the library and the file is known to lie elsewhere.
     */
    class Imported(val book: File, val moved: Boolean, val duplicate: Boolean = false)

    /**
     * Copies the book another app handed over as [uri] onto the shelf [dir], by default the
     * first shelf of the library, and returns it there; a book that already is in the library is
     * returned as it is. Null if it is not a book of a kind that can be read. [mime] is what the
     * other app said it is. Blocking.
     */
    fun import(ctx: Context, uri: Uri, mime: String?, dir: File = Library.root()): Imported? {
        val from = if (uri.scheme == "file") uri.path?.let(::File) else Store.locate(uri)
        // A book of the library opened from elsewhere, e.g. a file manager, is read where it stands.
        if (from != null && from.path.startsWith(Library.root().path + File.separator)) return if (Library.isBook(from)) Imported(from, false) else null
        // The name the other app gives it is only a nicety.
        val (given, declared) = if (uri.scheme == "content") Store.describe(uri) else (null to -1L)
        val name = given ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        Store.mkdirs(dir)
        // An app that says how big the file is lets it be copied onto the shelf in one go, rather than through a copy of its own.
        if (declared > 0) {
            try {
                return importDirect(ctx, uri, mime, dir, name, declared, from)
            } catch (e: SizeMismatch) {
                Log.w(TAG, "The size of $name is not what it was said to be", e)
            }
        }
        val temp = File(ctx.cacheDir, "import")
        (ctx.contentResolver.openInputStream(uri) ?: return null).use { ins ->
            FileOutputStream(temp).use { ins.copyTo(it) }
        }
        val ext = kindOf(headOf(temp.inputStream()), name, mime)
        if (ext == null) {
            temp.delete()
            return null
        }
        val base = baseOf(name)
        // The same book under another name is not added a second time either.
        twin(temp.length(), ext) { digest(temp.inputStream()) }?.let { existing ->
            temp.delete()
            return Imported(existing, from != null, duplicate = true)
        }
        // The same book handed over again is not added a second time.
        val target = placeFor(dir, base, ext, temp.length())
        val fresh = !Store.exists(target)
        try {
            if (fresh) temp.inputStream().use { ins -> Store.outputStream(target).use { ins.copyTo(it) } }
            // Only a whole copy lets the file it came from go.
            if (fresh && Store.length(target) != temp.length()) throw IOException("Incomplete copy")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot keep the book $name", e)
            Store.delete(target)
            return null
        } finally {
            temp.delete()
        }
        Library.touched(target)
        // A book found in the library already may be the very file handed over, under another
        // name for it; that is only let go where it is known to lie elsewhere.
        return Imported(target, fresh || from != null, duplicate = !fresh)
    }

    /**
     * What [importFolder] did: the shelf the books went onto, how many it added, how many were in
     * the library already, and how many it could not read; or [inLibrary], that the folder is part
     * of the library already, and nothing was done.
     */
    class FolderImport(val shelf: File?, val added: Int, val duplicates: Int, val unreadable: Int, val inLibrary: Boolean = false)

    /**
     * Copies the books in the folder [tree], which the reader picked in the system's folder picker,
     * onto a shelf named after it on the shelf [dir], one it has already if there is one, and the
     * books in the folders inside it onto shelves inside that one, named after them: a shelf only
     * for a folder that has books. The files themselves stay where they are. Null if the folder
     * cannot be read. Blocking.
     */
    fun importFolder(ctx: Context, tree: Uri, dir: File): FolderImport? = try {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val from = Store.locate(rootDoc)
        if (from != null && (from == Library.root() || from.path.startsWith(Library.root().path + File.separator))) {
            FolderImport(null, 0, 0, 0, inLibrary = true)
        } else {
            val name = Store.describe(rootDoc).first
            val shelf = File(dir, name?.let(::shelfNamed) ?: "Books")
            val counts = IntArray(3)
            importInto(ctx, tree, rootId, shelf, counts, 0)
            FolderImport(shelf, counts[0], counts[1], counts[2])
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the folder $tree", e)
        null
    }

    /**
     * Copies the books in the folder [id] of [tree] onto the shelf [shelf], which is made once
     * there is a book for it, and those in the folders in it onto shelves in it, [depth] down,
     * counting into [counts] the books added, those in the library already and those that cannot be read.
     */
    private fun importInto(ctx: Context, tree: Uri, id: String, shelf: File, counts: IntArray, depth: Int) {
        val listed = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val children = ctx.contentResolver.query(listed, columns, null, null, null)?.use { c ->
            List(c.count) { c.moveToPosition(it); Triple(c.getString(0), c.getString(1).orEmpty(), c.getString(2)) }
        }.orEmpty()
        for ((child, name, mime) in children) {
            if (name.startsWith(".")) continue
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val inner = shelfNamed(name) ?: continue
                if (depth < MAX_FOLDER_DEPTH) importInto(ctx, tree, child, File(shelf, inner), counts, depth + 1)
                continue
            }
            // Only what is named as a book is read: anything else in the folder would be copied first to be looked at.
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in FlowBook.EXTENSIONS && ext !in PageSource.EXTENSIONS) continue
            val imported = try {
                import(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, child), mime, shelf)
            } catch (e: Exception) {
                null
            }
            when {
                imported == null -> counts[2]++
                imported.duplicate -> counts[1]++
                else -> counts[0]++
            }
        }
    }

    /** How many folders down, inside one being added, books are looked for. */
    private const val MAX_FOLDER_DEPTH = 8

    /** The file handed over is not as big as the other app said it was. */
    private class SizeMismatch : IOException()

    /**
     * [import] for a file whose size is known: it is read once, straight onto the shelf. Only when
     * a book of the same size is already there is the file read through first, to see if it is the
     * same book. [SizeMismatch] if what was copied is not as big as it was said to be.
     */
    private fun importDirect(ctx: Context, uri: Uri, mime: String?, dir: File, name: String, size: Long, from: File?): Imported? {
        val ext = kindOf(headOf(ctx.contentResolver.openInputStream(uri) ?: return null), name, mime) ?: return null
        val base = baseOf(name)
        twin(size, ext) { digest(ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")) }?.let {
            return Imported(it, from != null, duplicate = true)
        }
        val target = placeFor(dir, base, ext, size)
        val fresh = !Store.exists(target)
        if (fresh) {
            try {
                val copied = (ctx.contentResolver.openInputStream(uri) ?: return null).use { ins ->
                    Store.outputStream(target).use { ins.copyTo(it) }
                }
                // Only a whole copy lets the file it came from go.
                if (copied != size || Store.length(target) != size) throw SizeMismatch()
            } catch (e: SizeMismatch) {
                Store.delete(target)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot keep the book $name", e)
                Store.delete(target)
                return null
            }
        }
        Library.touched(target)
        return Imported(target, fresh || from != null, duplicate = !fresh)
    }

    /** What a book handed over as [name] is called in the library, without its ending. */
    private fun baseOf(name: String) = (Library.cleanName(name.substringBeforeLast('.', name)) ?: "Book").take(120)

    /** What a shelf made for the folder [name] is called, or null if it cannot be named so. */
    private fun shelfNamed(name: String) = Library.cleanName(name)?.take(120)

    /**
     * Where a book called [base] with the ending [ext] and [size] bytes goes on the shelf [dir]: under
     * its own name, or one numbered after it if that is taken by a book of another size.
     */
    private fun placeFor(dir: File, base: String, ext: String, size: Long): File {
        var target = File(dir, "$base.$ext")
        var n = 2
        while (Store.exists(target) && Store.length(target) != size) target = File(dir, "$base (${n++}).$ext")
        return target
    }

    /** The first bytes of [ins], which it is closed after. */
    private fun headOf(ins: InputStream): ByteArray = ins.use {
        val head = ByteArray(68)
        var n = 0
        while (n < head.size) {
            val r = it.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        head.copyOf(n)
    }

    /** What kind of book a file is, by what it starts with and, where that is not enough, by its [name] and the [mime] the other app gave it; null if it is none. */
    private fun kindOf(head: ByteArray, name: String, mime: String?): String? {
        val start = String(head, Charsets.ISO_8859_1)
        val zipped = start.startsWith("PK")
        return when {
            start.startsWith("%PDF") -> "pdf"
            zipped && (name.endsWith(".cbz", true) || mime?.contains("cbz") == true || mime?.contains("comicbook") == true) -> "cbz"
            zipped && (name.endsWith(".epub", true) || mime == "application/epub+zip" || mime == "application/octet-stream" || mime == null) -> "epub"
            start.startsWith("BOOKMOBI", 60) -> "mobi"
            name.endsWith(".txt", true) || mime == "text/plain" -> "txt"
            else -> null
        }
    }

    /**
     * A book of the library that has exactly the content of a file of [size] bytes, whatever it is
     * called, if there is one; [mine] reads that file through, which only happens if a book of the
     * same size is there, so that this is quick.
     */
    private fun twin(size: Long, ext: String, mine: () -> ByteArray): File? {
        val sameSize = Library.books(fresh = false).filter { it.extension.equals(ext, true) && Store.length(it) == size }.toList()
        if (sameSize.isEmpty()) return null
        val wanted = mine()
        return sameSize.firstOrNull { f ->
            try {
                digest(Store.inputStream(f)).contentEquals(wanted)
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun digest(ins: InputStream): ByteArray = ins.use {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            sha.update(buffer, 0, n)
        }
        sha.digest()
    }
}
