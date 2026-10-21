package io.github.edoardonegri.ready

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What a tap on a plaque of the bookshelf opens: the details of a book or a shelf ([show]), or,
 * while things are being chosen, what changes them ([edit]): the name, the author and the cover of
 * a book, the name of a shelf. [spec] is how the shelves are laid out, for the picture of a shelf;
 * [changed] is told whenever something was changed, for the shelf to be shown anew, and [rename]
 * renames a book or a shelf. Made with the activity [act], before it starts.
 */
class ShelfDetails(
    private val act: AppCompatActivity,
    private val spec: () -> ShelfSpec,
    private val changed: () -> Unit,
    private val rename: (File, String) -> Unit,
) {

    /** Shows what there is to say about the book or the shelf [f]. */
    fun show(f: File) {
        if (f.isFolder) {
            val app = act.applicationContext
            Library.io.execute {
                val kids = Library.shelf(app, f)
                val folders = kids.count { it.isFolder }
                // The books of the shelves inside count too, in the books that are finished.
                val books = Library.books(f).toList()
                val finished = books.count { Library.isFinished(app, it) }
                // The counts down the left, and where the shelf is on the right, however long that is.
                val rows = ArrayList<Pair<String, String>>()
                rows += act.getString(R.string.info_books) to "${kids.size - folders}"
                rows += act.getString(R.string.info_folders) to "$folders"
                rows += act.getString(R.string.finished) to "$finished"
                rows += act.getString(R.string.info_location) to place(f)
                onShelf { ShelfDialog(act).details(rows, byColumn = true).uncrossed().title(f.name).show() }
            }
            return
        }
        Library.io.execute {
            val meta = Library.meta(act.applicationContext, f)
            onShelf { showBook(f, meta) }
        }
    }

    /** Lets the name of [f] be changed, and the author and the cover of a book. */
    fun edit(f: File) {
        if (f.isFolder) return editShelf(f)
        val app = act.applicationContext
        Library.io.execute {
            val meta = Library.meta(app, f)
            onShelf { editBook(f, meta) }
        }
    }

    /** The shelf [dir] as it stands on the shelf, beside its name, which can be changed there. */
    private fun editShelf(dir: File) {
        val dialog = ShelfDialog(act).uncrossed().title(dir.name)
        val name = dialog.field(R.string.field_name, dir.name)
        showShelfPicture(dialog, dir)
        dialog.onClose { renamed(dir, dir.name, name) }
        dialog.show()
    }

    /** Shows the shelf [dir] in [dialog] the way its miniature shows it on the shelf. */
    private fun showShelfPicture(dialog: ShelfDialog, dir: File) {
        val spec = spec()
        if (spec.width <= 0) return
        dialog.picture.apply {
            cased = true
            scaleType = ImageView.ScaleType.FIT_XY
            ratio = (spec.coverH + spec.miniRise).toFloat() / spec.miniW
        }
        val app = act.applicationContext
        Library.io.execute {
            val picture = Library.folderPreview(app, dir, spec)
            act.runOnUiThread { dialog.picture.setImageBitmap(picture) }
        }
    }

    /**
     * The book [f], described by [m], by its cover, beside its name, its author and what its
     * cover is: its first page, or a picture it was given, or a new one to be chosen.
     */
    private fun editBook(f: File, m: BookMeta) {
        val dialog = ShelfDialog(act).uncrossed().title(m.title).plaque(Library.isFavorite(act, f), Library.isFinished(act, f))
        val name = dialog.field(R.string.field_name, m.title)
        val author = dialog.field(R.string.field_author, m.author)
        // What the cover can be, as the menu offers it: the first page only for a book of pages, the
        // picture the reader gave only once there is one, and a new one always last.
        var kinds = listOf<CoverKind>()
        lateinit var aspect: BrassDropdown
        aspect = dialog.choice(R.string.field_aspect) { index ->
            when (kinds.getOrNull(index)) {
                CoverKind.BOOK -> setCoverMode(f, Library.CoverMode.OWN)
                CoverKind.FIRST -> setCoverMode(f, Library.CoverMode.FIRST_PAGE)
                CoverKind.MADE -> setCoverMode(f, Library.CoverMode.MADE, Library.tone(act, f))
                CoverKind.NEW -> {
                    coverFor = f
                    pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                // The picture the book has already stays as it is.
                else -> Unit
            }
        }
        // Which leather, shown only for a cover made for the book.
        val (toneName, toneMenu) = dialog.namedChoice(R.string.field_tone) { setCoverMode(f, Library.CoverMode.MADE, it) }
        dialog.picture.apply {
            binding = true
            ratio = ShelfSpec.BOOK
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ShelfArt.LEATHER)
        }
        editing = f to { cover, custom ->
            dialog.picture.setImageBitmap(cover)
            val mode = Library.coverMode(act, f)
            kinds = buildList {
                add(CoverKind.BOOK)
                if (!Library.isFlowing(f)) add(CoverKind.FIRST)
                add(CoverKind.MADE)
                if (custom) add(CoverKind.PICTURE)
                add(CoverKind.NEW)
            }
            val now = when {
                custom -> CoverKind.PICTURE
                mode == Library.CoverMode.FIRST_PAGE -> CoverKind.FIRST
                mode == Library.CoverMode.MADE -> CoverKind.MADE
                else -> CoverKind.BOOK
            }
            aspect.show(kinds.map { act.getString(it.label) }, kinds.indexOf(now))
            val made = now == CoverKind.MADE
            toneName.visibility = if (made) View.VISIBLE else View.GONE
            toneMenu.visibility = toneName.visibility
            toneMenu.show(Library.TONES.map { act.getString(it.first) }, Library.tone(act, f))
        }
        showCover(f)
        dialog.onClose {
            editing = null
            // The author first, under the name the book has until it is renamed.
            val by = author.text.toString().trim()
            if (by != m.author) Library.setAuthor(act, f, by)
            renamed(f, m.title, name)
            changed()
        }
        dialog.show()
    }

    /** Renames [f] to what [field] holds, if that is not empty and not still [old]. */
    private fun renamed(f: File, old: String, field: EditText) {
        val typed = field.text.toString().trim()
        if (typed.isNotEmpty() && typed != old) rename(f, typed)
    }

    /** The book a picture is being chosen for, to be its cover; kept with the activity's state while it is. */
    var coverFor: File? = null

    /** The book whose editor is open, and what shows its cover there and whether it was given one. */
    private var editing: Pair<File, (Bitmap?, Boolean) -> Unit>? = null

    private val pickPicture = act.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val book = coverFor ?: return@registerForActivityResult
        coverFor = null
        // None chosen: the editor shows the cover the book has, as it did before.
        if (uri != null) setCover(book, uri) else showCover(book)
    }

    /** Shows the cover of [f] in its editor, if that is open. */
    private fun showCover(f: File) {
        val app = act.applicationContext
        Library.io.execute {
            val cover = Library.cover(app, f)
            val custom = Library.hasCustomCover(app, f)
            act.runOnUiThread { editing?.takeIf { it.first == f }?.second?.invoke(cover, custom) }
        }
    }

    /** What the cover of a book can be, by the name the menu of the editor gives it. */
    private enum class CoverKind(val label: Int) {
        BOOK(R.string.cover_book), FIRST(R.string.cover_first), MADE(R.string.cover_made),
        PICTURE(R.string.cover_own), NEW(R.string.cover_new),
    }

    /** Says what the cover of [f] is, see [Library.setCoverMode], and shows it in the editor and on the shelf. */
    private fun setCoverMode(f: File, mode: Library.CoverMode, tone: Int? = null) {
        val app = act.applicationContext
        Library.io.execute {
            Library.setCoverMode(app, f, mode, tone)
            act.runOnUiThread {
                if (act.isDestroyed) return@runOnUiThread
                showCover(f)
                changed()
            }
        }
    }

    /** Gives the book [f] the picture [uri] as its cover, or its own back if that is null. */
    private fun setCover(f: File, uri: Uri?) {
        val app = act.applicationContext
        Library.io.execute {
            val done = Library.setCover(app, f, uri)
            act.runOnUiThread {
                if (act.isDestroyed) return@runOnUiThread
                if (!done) Toast.makeText(act, R.string.cannot_read_picture, Toast.LENGTH_SHORT).show()
                showCover(f)
                changed()
            }
        }
    }

    /** Where [f] is, starting from the library folder rather than from the root of the storage. */
    private fun place(f: File) = f.path.removePrefix(Library.root().parent.orEmpty()).trimStart('/')

    private fun showBook(f: File, m: BookMeta) {
        val st = Library.state(act, f)
        // A book marked as read has been, whether or not it was opened here.
        val started = st.lastRead > 0 || st.percent > 0f
        // EPUBs have no fixed pages; the count is estimated from the amount of text.
        val page = when {
            !started -> 0
            Library.isFlowing(f) -> 1 + (st.percent * (m.pages - 1)).roundToInt()
            else -> (st.chapter + 1).coerceAtMost(m.pages)
        }
        // First what the book is, then how far it is read, then the file it is and where that is kept.
        val rows = ArrayList<Pair<String, String>>()
        rows += act.getString(R.string.info_author) to m.author.ifBlank { act.getString(R.string.unknown) }
        rows += act.getString(R.string.info_published) to published(m.published)
        rows += act.getString(R.string.info_pages) to act.getString(R.string.info_pages_value, page, m.pages, (st.percent * 100).roundToInt())
        rows += act.getString(R.string.info_last_read) to
            if (st.lastRead > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(st.lastRead)) else act.getString(R.string.never)
        rows += act.getString(R.string.info_format) to f.extension.uppercase()
        f.parentFile?.let { rows += act.getString(R.string.info_folder) to place(it) }

        ShelfDialog(act).details(rows).uncrossed().title(m.title).plaque(Library.isFavorite(act, f), Library.isFinished(act, f)).show()
    }

    /** The date [BookMeta.published] gives, with no more of it than is known. */
    private fun published(date: String): String {
        val parts = date.split('-').mapNotNull(String::toIntOrNull)
        if (parts.size < 2) return parts.firstOrNull()?.toString() ?: act.getString(R.string.unknown)
        val day = Calendar.getInstance().apply {
            clear()
            set(parts[0], parts[1] - 1, parts.getOrElse(2) { 1 })
        }.time
        return if (parts.size > 2) DateFormat.getDateInstance(DateFormat.MEDIUM).format(day)
        else SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMy"), Locale.getDefault())
            .format(day)
    }

    /** Does [action] on the thread that draws the shelf, unless the shelf is gone by then. */
    private fun onShelf(action: () -> Unit) = act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed) action() }
}
