package io.github.edoardonegri.ready

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File

/**
 * Takes a book handed over by another app ("Open with" or "Share"): moves it onto the first
 * shelf of the library and opens it. It is moved, not copied, so that the book is kept only
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
        val uri = intent.data ?: shared()
        if (uri == null || !Library.hasAccess()) {
            // Without access to the library folder the book cannot be kept; the shelf asks for it.
            if (uri != null) Toast.makeText(this, R.string.import_needs_access, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val type = intent.type
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
                if (imported?.duplicate == true) Toast.makeText(this, R.string.already_on_shelf, Toast.LENGTH_SHORT).show()
                when {
                    imported == null -> {
                        Toast.makeText(this, R.string.cannot_import, Toast.LENGTH_LONG).show()
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
                Toast.makeText(
                    this,
                    if (removed) R.string.original_removed else if (same) R.string.cannot_delete else R.string.original_other,
                    Toast.LENGTH_LONG,
                ).show()
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

    private fun shared(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private companion object {
        const val PICK = 1
        const val KEY_BOOK = "book"
        const val KEY_NAME = "name"
        const val KEY_SIZE = "size"
    }
}
