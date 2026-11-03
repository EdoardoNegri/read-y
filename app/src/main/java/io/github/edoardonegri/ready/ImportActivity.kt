package io.github.edoardonegri.ready

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.File

/**
 * Takes a book handed over by another app ("Open with" or "Share"): moves it onto the first
 * shelf of the library and opens it. Several shared at once are all moved there, and the shelf is
 * shown ([importSeveral]). It is moved, not copied, so that the book is kept only
 * once. Where the other app does not let the file be deleted, the reader is asked to pick it in
 * Android's own picker, which does. It shows nothing of its own but that question.
 */
class ImportActivity : Activity() {

    /** The book in the library, while the file it came from is being asked about. */
    private var book: File? = null

    /** The name and size of the file it came from, which the file picked must have to be deleted. */
    private var originalName: String? = null
    private var originalSize = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back with the book already in the library, e.g. after the phone was turned while the picker was open.
        savedInstanceState?.getString(KEY_BOOK)?.let {
            book = File(it)
            originalName = savedInstanceState.getString(KEY_NAME)
            originalSize = savedInstanceState.getLong(KEY_SIZE, -1)
            return
        }
        val uris = intent.data?.let(::listOf) ?: shared()
        if (uris.isEmpty() || !Library.hasAccess()) {
            // Without access to the library folder the book cannot be kept; the shelf asks for it.
            if (uris.isNotEmpty()) toast(R.string.import_needs_access, long = true)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (uris.size > 1) return importSeveral(uris)
        val uri = uris.single()
        val type = if (intent.action == Intent.ACTION_SEND_MULTIPLE) contentResolver.getType(uri) else intent.type
        Library.io.execute {
            val (name, size) = Store.describe(uri)
            val imported = try {
                BookImport.import(applicationContext, uri, type)
            } catch (e: Exception) {
                null
            }
            // The file it came from goes, now that the library has the book.
            val kept = imported?.moved == true && !Store.removeOriginal(uri)
            runOnUiThread {
                if (imported?.duplicate == true) toast(R.string.already_on_shelf)
                when {
                    imported == null -> {
                        toast(R.string.cannot_import, long = true)
                        finish()
                    }
                    kept -> {
                        book = imported.book
                        originalName = name
                        originalSize = size
                        askForOriginal(imported.book)
                    }
                    else -> open(imported.book)
                }
            }
        }
    }

    /**
     * Takes several books shared at once, [uris], as one is taken, onto the first shelf, and shows
     * the shelf rather than opening any of them. The files they came from go where the app they
     * came from lets them; where it does not, the reader is told, rather than asked about each.
     */
    private fun importSeveral(uris: List<Uri>) {
        val app = applicationContext
        Library.io.execute {
            var added = 0
            var duplicates = 0
            var unreadable = 0
            var kept = 0
            for (uri in uris) {
                val imported = try {
                    BookImport.import(app, uri, app.contentResolver.getType(uri))
                } catch (e: Exception) {
                    null
                }
                if (imported == null) unreadable++ else {
                    if (imported.duplicate) duplicates++ else added++
                    if (imported.moved && !Store.removeOriginal(uri)) kept++
                }
            }
            runOnUiThread {
                // A toast shows two lines at most: what most needs telling is told.
                val res = app.resources
                app.toast(
                    when {
                        unreadable == uris.size -> res.getString(R.string.cannot_import)
                        unreadable > 0 -> res.getQuantityString(R.plurals.shared_unreadable, unreadable, unreadable, uris.size)
                        kept > 0 -> res.getQuantityString(R.plurals.shared_kept, kept, kept)
                        duplicates > 0 -> res.getQuantityString(R.plurals.already_here, duplicates, duplicates)
                        else -> res.getQuantityString(R.plurals.shared_added, added, added)
                    },
                    long = true,
                )
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                finish()
            }
        }
    }

    /**
     * The book is in the library, but the app it came from would not let its file be deleted:
     * the reader may pick that file in Android's picker, which lets it be.
     */
    private fun askForOriginal(book: File) {
        var picking = false
        val title = Library.cachedMeta(this, book)?.title ?: book.nameWithoutExtension
        ShelfDialog(this).title(getString(R.string.original_title))
            .message(getString(R.string.original_ask, title, originalName ?: book.name))
            .primary(R.string.original_choose) {
                picking = true
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Store.pickIntent(several = false), PICK)
                } catch (e: Exception) {
                    open(book)
                }
            }
            .onClose { if (!picking) open(book) }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val book = book ?: return finish()
        if (requestCode != PICK) return
        val picked = data?.data
        if (picked == null) return open(book)
        Library.io.execute {
            // Only the very file the book came from is deleted: one of the same name and size.
            val (name, size) = Store.describe(picked)
            val same = name != null && name == originalName && size >= 0 && size == Store.length(book)
            val removed = same && Store.removeOriginal(picked)
            runOnUiThread {
                toast(if (removed) R.string.original_removed else if (same) R.string.cannot_delete else R.string.original_other, long = true)
                open(book)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        book?.let {
            outState.putString(KEY_BOOK, it.path)
            outState.putString(KEY_NAME, originalName)
            outState.putLong(KEY_SIZE, originalSize)
        }
    }

    /** Opens [book] in the reader, with the shelf below it. */
    private fun open(book: File) {
        if (isFinishing) return
        startActivities(
            arrayOf(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, book.path),
            ),
        )
        finish()
    }

    /** The books shared: one, or with "Share" on several at once, all of them. */
    private fun shared(): List<Uri> = when {
        intent.action == Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ).orEmpty().filterNotNull()
        Build.VERSION.SDK_INT >= 33 -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        else -> listOfNotNull(@Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))
    }

    private companion object {
        const val PICK = 1
        const val KEY_BOOK = "book"
        const val KEY_NAME = "name"
        const val KEY_SIZE = "size"
    }
}
