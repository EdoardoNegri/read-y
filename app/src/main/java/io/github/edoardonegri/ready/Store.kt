package io.github.edoardonegri.ready

import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

/** Lets [Store] reach the phone's documents from anywhere in the app, the widget included. */
class ReadyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.app = this
        Library.warmUp(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // A book kept open in case it is read again, or read ahead for the reader, is let go of when
        // memory runs short, but not merely because the app was left.
        @Suppress("DEPRECATION")
        if (level != TRIM_MEMORY_UI_HIDDEN) {
            KeptBook.drop()
            BookPrep.drop()
            BookOpening.forget()
            FlowEngine.release()
        }
        Library.trim(level)
    }
}

/** Whether [this] is a folder of the library: a shelf. */
val File.isFolder: Boolean get() = Store.isFolder(this)

/** Whether [this] is a file of the library, e.g. a book. */
val File.isDoc: Boolean get() = Store.isDoc(this)

/**
 * The library folder, "Read_y", which the reader lets the app have through Android's folder
 * picker; the app is given nothing else of the phone's storage. Things in it are still named by
 * the paths they have on the phone, as a [File], and turned into documents of the folder here.
 * That needs the documents to be named by their paths, which those of the phone's own storage are.
 */
object Store {
    private const val TAG = "Store"
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val TREE = "tree"
    private const val ROOT = "root"
    const val FOLDER = "Read_y"

    /** The application, which lives as long as the app does: keeping it is no leak. */
    lateinit var app: Application

    private fun prefs() = app.getSharedPreferences("store", Context.MODE_PRIVATE)

    /** The folder the reader granted, as the picker gave it; null until then. */
    private val tree: Uri? get() = prefs().getString(TREE, null)?.let(Uri::parse)

    /** Where the library is looked for when the reader is first asked for it: Documents/Read_y. */
    private fun suggested() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)

    /**
     * Where a library folder may already be on the phone: Documents/Read_y of the phone's own
     * storage and of any card in it. Android lets an app see nothing else without being given
     * the whole phone, so these are the places looked in.
     */
    private fun candidates(): List<File> {
        val volumes = LinkedHashSet<File>()
        volumes += storage()
        try {
            for (dir in app.getExternalFilesDirs(null)) {
                // /storage/XXXX-XXXX/Android/data/<package>/files: the volume is what lies before Android.
                dir?.path?.substringBefore("/Android/data")?.takeIf { it != dir.path }?.let { volumes += File(it) }
            }
        } catch (e: Exception) {
            // The phone's own storage is still looked in.
        }
        return volumes.flatMap { listOf(File(File(it, Environment.DIRECTORY_DOCUMENTS), FOLDER), File(it, "Read-y")) }
    }

    /** A library folder that is already on the phone, if there is one. */
    private fun existing(): File? = candidates().firstOrNull { it.isDirectory }

    /** The library folder, by the path it has on the phone. */
    fun root(): File = tree?.let { pathOf(DocumentsContract.getTreeDocumentId(it)) } ?: suggested()

    /** The phone's own storage, which the library folder is in. */
    fun storage(): File = Environment.getExternalStorageDirectory()

    /** The id of the document at [f], which is the volume it is on and its path there: primary:Documents/Read_y. */
    private fun idOf(f: File?): String {
        val path = f?.path ?: return "primary:"
        val primary = storage().path
        return if (path == primary || path.startsWith(primary + File.separator)) "primary:" + path.removePrefix(primary).trimStart('/')
        else path.removePrefix("/storage/").let { it.substringBefore('/') + ":" + it.substringAfter('/', "") }
    }

    /** The path of the document [id], which is a volume and a path on it: primary:Documents/Read_y. */
    private fun pathOf(id: String): File {
        val volume = id.substringBefore(':')
        val base = if (volume == "primary") storage() else File("/storage", volume)
        return File(base, id.substringAfter(':'))
    }

    fun hasAccess(): Boolean {
        val t = tree ?: return false
        return app.contentResolver.persistedUriPermissions.any { it.uri == t && it.isReadPermission && it.isWritePermission }
    }

    /**
     * Whether the library the reader granted is gone from where it was, because it was moved or
     * renamed. Only a folder that the phone says is not there is lost: a failure to ask, or a card
     * that is not in the phone, is not. Blocking.
     */
    fun isLost(): Boolean {
        val t = tree ?: return false
        if (!hasAccess()) return true
        val u = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
        return try {
            // The provider answers a folder that is not there with no cursor at all.
            app.contentResolver.query(u, COLUMNS, null, null, null)?.use { !it.moveToFirst() } ?: volumeIsThere()
        } catch (e: FileNotFoundException) {
            true
        } catch (e: Exception) {
            // The provider does not say it is gone; a card that was taken out does not count as lost either.
            false
        }
    }

    /** Whether the volume the library folder is on, the phone's storage or a card, is in the phone. */
    private fun volumeIsThere(): Boolean {
        val path = root().path
        val primary = storage().path
        val volume = if (path.startsWith(primary + File.separator)) primary else path.split('/').take(3).joinToString("/")
        return File(volume).isDirectory
    }

    /** Lets go of the library that is lost, so that it is looked for anew; what is remembered of its books is kept. */
    fun drop() {
        val t = tree ?: return
        try {
            app.contentResolver.releasePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // It was let go of already.
        }
        prefs().edit { remove(TREE) }
        entries.clear()
        listings.clear()
    }

    /**
     * What asks the reader for the library folder. It opens on the Read_y folder that is already
     * on the phone, or else on Documents/Read_y, which is made first where the phone lets the app
     * make it; elsewhere the reader can make it in the picker.
     */
    fun askIntent(): Intent {
        val found = existing()
        if (found == null) try {
            suggested().mkdirs()
        } catch (e: Exception) {
            // The picker can make it too.
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        if (Build.VERSION.SDK_INT >= 26) {
            val dir = found ?: suggested()
            val start = if (dir.isDirectory) dir else dir.parentFile
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, idOf(start)))
        }
        return i
    }

    /**
     * Keeps the folder [uri] the picker gave as the library, for good. Returns the folder the
     * library was in before, or null if [uri] cannot be one: a folder of a cloud drive, say.
     */
    fun grant(uri: Uri): File? {
        if (uri.authority != AUTHORITY) return null
        val old = prefs().getString(ROOT, null)?.let(::File) ?: File(storage(), "Read-y")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot keep $uri", e)
            return null
        }
        // Only one folder is held on to.
        val before = tree
        prefs().edit { putString(TREE, uri.toString()) }
        if (before != null && before != uri) try {
            app.contentResolver.releasePersistableUriPermission(before, flags)
        } catch (e: SecurityException) {
            // It was let go of already.
        }
        prefs().edit { putString(ROOT, root().path) }
        entries.clear()
        listings.clear()
        return old
    }

    // ---- documents ----

    private class Entry(val folder: Boolean, val size: Long, val modified: Long)

    private val NONE = Entry(false, -1, 0)

    /**
     * What is known of the things in the library, by path, as the folders were last listed: so
     * that the shelf need not ask the documents provider about every book it shows. [NONE] is
     * what is known not to be there.
     */
    private val entries = ConcurrentHashMap<String, Entry>()

    private val COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    )

    private fun docId(f: File): String? {
        val t = tree ?: return null
        val base = root().path
        val rel = when {
            f.path == base -> ""
            f.path.startsWith(base + File.separator) -> f.path.removePrefix(base)
            else -> return null
        }
        return DocumentsContract.getTreeDocumentId(t) + rel
    }

    private fun uri(f: File): Uri? = tree?.let { t -> docId(f)?.let { DocumentsContract.buildDocumentUriUsingTree(t, it) } }

    private fun entry(c: Cursor) = Entry(
        c.getString(1) == Document.MIME_TYPE_DIR,
        if (c.isNull(2)) 0 else c.getLong(2),
        if (c.isNull(3)) 0 else c.getLong(3),
    )

    private fun stat(f: File): Entry {
        entries[f.path]?.let { return it }
        val e = try {
            uri(f)?.let { u -> app.contentResolver.query(u, COLUMNS, null, null, null)?.use { c -> if (c.moveToFirst()) entry(c) else null } }
        } catch (e: Exception) {
            // Not there: the provider says so by failing.
            null
        } ?: NONE
        entries[f.path] = e
        return e
    }

    fun isFolder(f: File) = stat(f).folder
    fun isDoc(f: File) = stat(f).let { it !== NONE && !it.folder }
    fun exists(f: File) = stat(f) !== NONE
    fun length(f: File) = stat(f).size.coerceAtLeast(0)
    fun lastModified(f: File) = stat(f).modified

    /** The folders being listed just now, by their paths, so that a second one asking for the same waits for the first. */
    private val listing = ConcurrentHashMap<String, FutureTask<List<File>>>()

    /**
     * Whatever is in the folder [dir], hidden things included, as it is now. A folder that is being
     * listed already, e.g. by the shelf as it is shown and by the work that readies it as the app
     * starts, is not listed twice at once: the second waits for the first.
     */
    fun list(dir: File): List<File> {
        val mine = FutureTask { listNow(dir) }
        val running = listing.putIfAbsent(dir.path, mine)
        if (running != null) return try {
            running.get()
        } catch (e: Exception) {
            emptyList()
        }
        try {
            mine.run()
            return mine.get()
        } finally {
            listing.remove(dir.path)
        }
    }

    private fun listNow(dir: File): List<File> {
        val t = tree ?: return emptyList()
        val id = docId(dir) ?: return emptyList()
        val kids = ArrayList<File>()
        try {
            app.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(t, id), COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val f = pathOf(c.getString(0))
                    entries[f.path] = entry(c)
                    kids += f
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list $dir", e)
            return emptyList()
        }
        // What is gone since is known to be gone.
        val prefix = dir.path + File.separator
        val names = kids.mapTo(HashSet()) { it.path }
        for (key in entries.keys) if (key.startsWith(prefix) && key.indexOf(File.separatorChar, prefix.length) < 0 && key !in names) entries[key] = NONE
        listings[dir.path] = kids
        // What can be listed is a folder that is there, which need not be asked about again.
        entries.compute(dir.path) { _, known -> if (known == null || known === NONE) Entry(true, 0, 0) else known }
        return kids
    }

    /**
     * What [list] last found in each folder, by its path, kept up to date with what the app
     * changes itself: so that a shelf can be shown at once, while it is listed anew in the
     * background for what other apps changed. A folder of hundreds of books takes the documents
     * provider a good part of a second to list.
     */
    private val listings = ConcurrentHashMap<String, List<File>>()

    /** What was last found in the folder [dir], if it was ever listed; see [listings]. */
    fun known(dir: File): List<File>? = listings[dir.path]

    /** [f] has come into its folder, or left it if not [there]. */
    private fun relist(f: File, there: Boolean) {
        val parent = f.parentFile?.path ?: return
        listings.computeIfPresent(parent) { _, old -> old.filter { it != f }.let { if (there) it + f else it } }
    }

    /**
     * [dir] and everything in it, folders before what they hold, like [File.walkTopDown]; [enter]
     * says which folders to look into. Unless [fresh], a folder listed before is taken as it was
     * found then (see [listings]), which spares the documents provider being asked again.
     */
    fun walk(dir: File, fresh: Boolean = true, enter: (File) -> Boolean = { true }): Sequence<File> = sequence {
        yield(dir)
        if (isFolder(dir) && enter(dir)) for (f in (if (fresh) null else known(dir)) ?: list(dir)) yieldAll(walk(f, fresh, enter))
    }

    /** Forgets what is known of [f] and all it holds, after it changed. */
    private fun forget(f: File) {
        val prefix = f.path + File.separator
        entries.keys.removeAll { it == f.path || it.startsWith(prefix) }
        listings.keys.removeAll { it == f.path || it.startsWith(prefix) }
    }

    /** Makes the folder [dir], whose parent must be there. False if it cannot, or something of its name is there. */
    fun mkdir(dir: File): Boolean = create(dir, Document.MIME_TYPE_DIR) != null

    /** Makes the folder [dir] and those it is in. */
    fun mkdirs(dir: File): Boolean {
        if (isFolder(dir)) return true
        val parent = dir.parentFile ?: return false
        return docId(parent) != null && mkdirs(parent) && mkdir(dir)
    }

    /** Makes the empty file or folder [f]; null if it cannot, or something of its name is there. */
    private fun create(f: File, mime: String): Uri? {
        val parent = uri(f.parentFile ?: return null) ?: return null
        if (exists(f)) return null
        val made = try {
            DocumentsContract.createDocument(app.contentResolver, parent, mime, f.name)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot make $f", e)
            null
        } ?: return null
        forget(f)
        // The provider makes up a name of its own for one it cannot use.
        if (pathOf(DocumentsContract.getDocumentId(made)) != f) {
            DocumentsContract.deleteDocument(app.contentResolver, made)
            return null
        }
        relist(f, true)
        return made
    }

    /** Gives [f] the name [name] in the same folder. */
    fun rename(f: File, name: String): Boolean {
        val u = uri(f) ?: return false
        val target = File(f.parentFile, name)
        return try {
            val done = DocumentsContract.renameDocument(app.contentResolver, u, name)
            forget(f)
            forget(target)
            val at = done?.let { pathOf(DocumentsContract.getDocumentId(it)) } ?: target
            if (at == target) {
                relist(f, false)
                relist(target, true)
            } else {
                // The provider makes up a name of its own for one it cannot use: that is put back.
                DocumentsContract.renameDocument(app.contentResolver, uri(at)!!, f.name)
            }
            at == target
        } catch (e: Exception) {
            Log.w(TAG, "Cannot rename $f", e)
            false
        }
    }

    /** Moves [f], with all it holds, into the folder [dest]. */
    fun move(f: File, dest: File): Boolean {
        val u = uri(f) ?: return false
        val from = uri(f.parentFile ?: return false) ?: return false
        val to = uri(dest) ?: return false
        return try {
            DocumentsContract.moveDocument(app.contentResolver, u, from, to) != null
        } catch (e: Exception) {
            Log.w(TAG, "Cannot move $f", e)
            false
        }.also { moved ->
            forget(f)
            forget(File(dest, f.name))
            if (moved) {
                relist(f, false)
                relist(File(dest, f.name), true)
            }
        }
    }

    /** Deletes [f], with all it holds. */
    fun delete(f: File): Boolean {
        val u = uri(f) ?: return false
        return try {
            DocumentsContract.deleteDocument(app.contentResolver, u)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot delete $f", e)
            false
        }.also { gone ->
            forget(f)
            if (gone) relist(f, false)
        }
    }

    fun inputStream(f: File): InputStream =
        uri(f)?.let { app.contentResolver.openInputStream(it) } ?: throw FileNotFoundException(f.path)

    /** Writes [f] from the start, making it if it is not there. */
    fun outputStream(f: File): OutputStream {
        val u = (if (exists(f)) uri(f) else create(f, "application/octet-stream")) ?: throw FileNotFoundException(f.path)
        forget(f)
        return app.contentResolver.openOutputStream(u, "wt") ?: throw FileNotFoundException(f.path)
    }

    fun readBytes(f: File): ByteArray = inputStream(f).use { it.readBytes() }
    fun readText(f: File): String = String(readBytes(f))
    fun writeText(f: File, text: String) = outputStream(f).use { it.write(text.toByteArray()) }

    /** [f] open for reading, as the system gives it out to be read at any place. */
    fun descriptor(f: File): ParcelFileDescriptor =
        uri(f)?.let { app.contentResolver.openFileDescriptor(it, "r") } ?: throw FileNotFoundException(f.path)

    // ---- files handed over by other apps ----

    /**
     * Where on the phone the file [uri] another app handed over lies, as far as that can be told:
     * for the documents of the phone's storage and for the files Android keeps track of; null
     * for those of other apps.
     */
    fun locate(uri: Uri): File? = try {
        when {
            uri.authority == AUTHORITY && DocumentsContract.isDocumentUri(app, uri) -> pathOf(DocumentsContract.getDocumentId(uri))
            uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= 29 ->
                app.contentResolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
                )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) File(File(storage(), c.getString(0)), c.getString(1)) else null }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** The name and the size of the file [uri] another app handed over, as far as it says; -1 for an unknown size. */
    fun describe(uri: Uri): Pair<String?, Long> = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) to (if (c.isNull(1)) -1L else c.getLong(1)) else null
        } ?: (null to -1L)
    } catch (e: Exception) {
        null to -1L
    }

    /**
     * What lets the reader pick files in Android's own picker, opening on the Download folder,
     * where books handed over by other apps are usually kept. A file picked there may be deleted
     * by the app, which is how books are moved into the library rather than copied.
     */
    fun pickIntent(several: Boolean): Intent {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, several)
        if (Build.VERSION.SDK_INT >= 26) {
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, "primary:${Environment.DIRECTORY_DOWNLOADS}"))
        }
        return i
    }

    /**
     * Deletes the file [uri] another app handed over, now that the library holds it. Android
     * allows that only where the other app gave leave to change the file; false if it did not.
     */
    fun removeOriginal(uri: Uri): Boolean {
        try {
            if (DocumentsContract.isDocumentUri(app, uri) && DocumentsContract.deleteDocument(app.contentResolver, uri)) return true
        } catch (e: Exception) {
            // No leave to; perhaps the other app lets it be deleted the plain way.
        }
        return try {
            app.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /** [f] open to be read at any place. */
    fun open(f: File) = BookFile(descriptor(f))
}
