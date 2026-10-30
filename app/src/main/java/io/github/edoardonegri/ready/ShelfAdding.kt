package io.github.edoardonegri.ready

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * Adding to the shelf shown, [shelf]: books picked anywhere on the phone, moved or copied, an
 * empty shelf, or a shelf with the books of a folder of the phone. [added] is told once something
 * was, for the shelf to be shown anew; the dialogs lead [back] to the menu they were opened from.
 * Made with the activity [act], before it starts.
 */
class ShelfAdding(
    private val act: ComponentActivity,
    private val shelf: () -> File,
    private val added: () -> Unit,
    private val back: () -> Unit,
) {

    /**
     * Books picked anywhere on the phone in Android's picker, which are moved onto the shelf being
     * shown, or copied there, as the reader chose ([moving]): Android lets the files picked there be
     * deleted, so that moved ones are kept only once.
     */
    private val pickBooks = act.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val uris = data.clipData?.let { c -> List(c.itemCount) { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        if (uris.isEmpty()) return@registerForActivityResult
        val app = act.applicationContext
        val dir = shelf()
        val move = moving
        Library.io.execute {
            var unreadable = 0
            var kept = 0
            var duplicates = 0
            for (uri in uris) {
                val imported = try {
                    BookImport.import(app, uri, app.contentResolver.getType(uri), dir)
                } catch (e: Exception) {
                    null
                }
                if (imported == null) unreadable++ else {
                    if (imported.duplicate) duplicates++
                    if (move && imported.moved && !Store.removeOriginal(uri)) kept++
                }
            }
            act.whileOpen {
                added()
                if (unreadable > 0) act.toast(R.string.cannot_import)
                else if (kept > 0) act.toast(R.string.cannot_delete)
                else if (duplicates > 0) {
                    act.toast(act.resources.getQuantityString(R.plurals.already_here, duplicates, duplicates), long = true)
                }
            }
        }
    }

    /**
     * Whether the books being picked are to be moved onto the shelf, rather than copied, see
     * [addBooks]. Should the bookshelf be made anew meanwhile, the choice is forgotten, and the books
     * are only copied: nothing is deleted that the reader did not say could be.
     */
    private var moving = false

    /** Asks whether the books are to be moved onto the shelf or copied there, and then lets them be picked. */
    fun addBooks() {
        ShelfDialog(act).title(act.getString(R.string.add_books)).back(back)
            .options(listOf(act.getString(R.string.add_move) to { pickBooks(move = true) }, act.getString(R.string.add_copy) to { pickBooks(move = false) }))
            .show()
    }

    private fun pickBooks(move: Boolean) {
        moving = move
        try {
            pickBooks.launch(Store.pickIntent(several = true))
        } catch (e: Exception) {
            act.toast(R.string.cannot_import)
        }
    }

    /** A shelf is added empty, to be named, or with the books of a folder the reader picks, see [addFolder]. */
    fun askAddShelf() {
        ShelfDialog(act).title(act.getString(R.string.add_shelf)).back(back).options(
            listOf(act.getString(R.string.empty_shelf) to ::askNewShelf, act.getString(R.string.shelf_from_folder) to ::addFolder),
        ).show()
    }

    private fun askNewShelf() {
        val dialog = ShelfDialog(act).title(act.getString(R.string.new_shelf)).back(::askAddShelf).input(R.string.shelf_name)
        dialog.primary(R.string.create) {
            if (Library.createShelf(shelf(), dialog.text)) added() else act.toast(R.string.name_taken)
        }.show()
    }

    /**
     * Adds every book in a folder the reader picks, onto a shelf named after it on the shelf shown,
     * and those in its folders onto shelves inside that one (BookImport.importFolder). The files stay
     * where they are: a whole folder is copied, not moved, as books picked one by one are.
     */
    private fun addFolder() {
        try {
            pickBooksFolder.launch(null)
        } catch (e: Exception) {
            act.toast(R.string.cannot_read_folder)
        }
    }

    private val pickBooksFolder = act.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@registerForActivityResult
        val app = act.applicationContext
        val dir = shelf()
        val name = Store.describe(tree).first ?: tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/').orEmpty()
        act.toast(act.getString(R.string.adding_folder, name))
        Library.io.execute {
            val done = BookImport.importFolder(app, tree, dir)
            act.whileOpen {
                added()
                when {
                    done == null -> act.toast(R.string.cannot_read_folder)
                    done.inLibrary -> act.toast(R.string.folder_in_library)
                    done.added == 0 && done.unreadable > 0 -> act.toast(R.string.cannot_import)
                    done.added == 0 && done.duplicates > 0 ->
                        act.toast(act.resources.getQuantityString(R.plurals.already_here, done.duplicates, done.duplicates), long = true)
                    done.added == 0 -> act.toast(R.string.folder_empty)
                    else -> act.toast(
                        act.resources.getQuantityString(R.plurals.folder_added, done.added, done.added, done.shelf?.name.orEmpty()), long = true,
                    )
                }
            }
        }
    }

}
