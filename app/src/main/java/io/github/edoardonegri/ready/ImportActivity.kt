package io.github.edoardonegri.ready

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Takes a book handed over by another app ("Open with" or "Share"): copies it onto the first
 * shelf of the library and opens it. It shows nothing of its own.
 */
class ImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: shared()
        if (uri == null || !Library.hasAccess(this)) {
            // Without access to the library folder the book cannot be kept; the shelf asks for it.
            if (uri != null) Toast.makeText(this, R.string.import_needs_access, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val type = intent.type
        Library.io.execute {
            val book = try {
                Library.import(applicationContext, uri, type)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (book == null) {
                    Toast.makeText(this, R.string.cannot_import, Toast.LENGTH_LONG).show()
                } else {
                    // The shelf below, the book on top.
                    startActivities(
                        arrayOf(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, book.path),
                        ),
                    )
                }
                finish()
            }
        }
    }

    private fun shared(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
}
