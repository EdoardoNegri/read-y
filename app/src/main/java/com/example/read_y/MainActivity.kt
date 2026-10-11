package com.example.read_y

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.TreeSet
import kotlin.math.roundToInt

/**
 * The bookshelf: mirrors the "Read-y" folder, one shelf screen per folder. Back leaves a sub-folder.
 * Books and shelves are picked up with a long press and dragged: onto another place to put the
 * shelf in a new order, onto a shelf to put them inside it, onto the top rail to take them out to
 * the shelf this one stands on.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var current: File
    private lateinit var shelf: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var grant: View
    private lateinit var ghost: ImageView
    private lateinit var barTitle: TextView
    private lateinit var up: View
    private lateinit var searchRow: View
    private lateinit var selectRow: View
    private lateinit var undoBar: View
    private lateinit var undoText: TextView
    private lateinit var query: EditText
    private var columns = 3
    private val adapter = ShelfAdapter(::open, ::showInfo, ::startDrag, ::spec)

    /** The books found for what is in the search field; shown instead of the shelf while there is any. */
    private var found: List<File>? = null

    /** Counts the searches started, so that a slow one cannot overwrite a later one. */
    private var searches = 0

    private val searching get() = searchRow.visibility == View.VISIBLE

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = when {
            picked != null -> stopPicking()
            searching -> closeSearch()
            else -> goUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        shelf = findViewById(R.id.shelf)
        ghost = findViewById(R.id.ghost)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.emptyText)
        grant = findViewById(R.id.grant)
        barTitle = findViewById(R.id.barTitle)
        up = findViewById(R.id.up)
        searchRow = findViewById(R.id.searchRow)
        selectRow = findViewById(R.id.selectRow)
        for (id in intArrayOf(R.id.moveSelected, R.id.deleteSelected)) {
            findViewById<View>(id).background = PlaqueDrawable(resources.displayMetrics.density)
        }
        findViewById<View>(R.id.closeSelect).setOnClickListener { stopPicking() }
        findViewById<View>(R.id.moveSelected).setOnClickListener { askMoveTo() }
        findViewById<View>(R.id.deleteSelected).setOnClickListener { askDeleteAll() }
        undoBar = findViewById(R.id.undoBar)
        undoText = findViewById(R.id.undoText)
        findViewById<View>(R.id.undo).background = PlaqueDrawable(resources.displayMetrics.density)
        query = findViewById(R.id.query)
        val bar = findViewById<View>(R.id.bar)
        barTitle.background = PlaqueDrawable(resources.displayMetrics.density, ornate = true)
        grant.background = PlaqueDrawable(resources.displayMetrics.density)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            // The bookcase runs edge to edge: its top rail fills the status bar area, and the rows
            // can scroll clear of the navigation bar.
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, 0, i.right, 0)
            bar.setPadding(0, i.top, 0, 0)
            shelf.setPadding(shelf.paddingLeft, 0, shelf.paddingRight, i.bottom)
            (undoBar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                i.bottom + (16 * resources.displayMetrics.density).toInt()
            insets
        }

        columns = (resources.configuration.screenWidthDp / 118).coerceAtLeast(3)
        shelf.layoutManager = GridLayoutManager(this, columns)
        shelf.addItemDecoration(ShelfDecoration(this, columns))
        // Leave room for the bookcase's side posts.
        ShelfSpec(0, columns, resources.displayMetrics.density).let {
            shelf.setPadding(it.post, shelf.paddingTop, it.post, shelf.paddingBottom)
        }
        shelf.adapter = adapter
        shelf.setOnDragListener { _, e -> onShelfDrag(e) }
        bar.setOnDragListener { _, e -> onRailDrag(e) }

        current = savedInstanceState?.getString(KEY_DIR)?.let(::File) ?: Library.root()
        grant.setOnClickListener { requestAccess() }
        up.setOnClickListener { back.handleOnBackPressed() }
        findViewById<View>(R.id.more).setOnClickListener { showMenu() }
        findViewById<View>(R.id.search).setOnClickListener { if (searching) closeSearch() else openSearch() }
        findViewById<View>(R.id.closeSearch).setOnClickListener { closeSearch() }
        query.doAfterTextChanged { search(it?.toString().orEmpty()) }
        onBackPressedDispatcher.addCallback(this, back)

        // What was deleted when the app was last closed is gone for good by now.
        if (savedInstanceState == null) applicationContext.let { app -> Library.io.execute { Library.emptyBin(app) } }

        // A fresh start returns to the book that was being read when the app was closed, if any.
        if (savedInstanceState == null) Library.openBook(this)?.let {
            if (it.isFile && Library.hasAccess(this)) open(it) else Library.setOpenBook(this, null)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DIR, current.path)
    }

    private fun refresh() {
        val root = Library.root()
        if (!Library.hasAccess(this)) {
            back.isEnabled = false
            up.visibility = View.INVISIBLE
            barTitle.setText(R.string.app_name)
            adapter.submit(emptyList())
            empty.visibility = View.VISIBLE
            grant.visibility = View.VISIBLE
            emptyText.text = getString(R.string.need_access, root.path)
            return
        }
        root.mkdirs()
        if (!Library.isShelf(current) || !current.path.startsWith(root.path)) current = root
        val atRoot = current == root
        val favorites = current == Library.favoritesShelf()
        val results = found
        val books = if (results != null) Library.arrange(this, null, results) else Library.shelf(this, current)
        // The favorites stand first in the library, as a shelf of their own.
        val items =
            if (results == null && atRoot && books.isNotEmpty()) listOf(Library.favoritesShelf()) + books else books
        back.isEnabled = !atRoot || searching || picked != null
        adapter.picked = picked
        findViewById<TextView>(R.id.selectCount).text = getString(R.string.picked, picked?.size ?: 0)
        up.visibility = if (back.isEnabled) View.VISIBLE else View.INVISIBLE
        barTitle.text = shelfName(current)
        // Books found on several shelves each say which one they stand on.
        adapter.showPlaces = results != null
        adapter.submit(items)
        grant.visibility = View.GONE
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = when {
            results != null -> getString(R.string.no_results)
            favorites -> getString(R.string.widget_empty)
            else -> getString(R.string.empty_folder, current.path)
        }
    }

    private fun goUp() {
        val left = current
        val snapshot = snapshotShelf()
        current = current.parentFile ?: Library.root()
        refresh()
        val index = adapter.indexOf(left)
        if (snapshot == null || index < 0) return
        // Keep showing the shelf being left until the parent is laid out and its miniature can be found.
        shelf.animate().cancel()
        ghost.animate().cancel()
        ghost.scaleX = 1f
        ghost.scaleY = 1f
        ghost.alpha = 1f
        ghost.translationZ = 1f
        ghost.setImageBitmap(snapshot)
        ghost.visibility = View.VISIBLE
        shelf.scrollToPosition(index)
        shelf.doOnPreDraw {
            val thumbnail = shelf.findViewHolderForAdapterPosition(index)?.itemView?.findViewById<View>(R.id.cover)
            if (thumbnail != null) zoom(locate(thumbnail), snapshot, opening = false) else hideGhost()
        }
    }

    private fun open(f: File, from: View? = null) {
        if (picked != null) return pick(f)
        if (Library.isShelf(f)) {
            val miniature = from?.findViewById<View>(R.id.cover)?.let(::locate)
            val snapshot = if (miniature != null) snapshotShelf() else null
            current = f
            refresh()
            shelf.scrollToPosition(0)
            // Start only once the folder's books are laid out, so no frame shows the old shelf shrunk.
            if (miniature != null && snapshot != null) {
                shelf.doOnPreDraw { zoom(miniature, snapshot, opening = true) }
            }
        } else {
            startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, f.path))
        }
    }

    private fun shelfName(f: File): String = when (f) {
        Library.favoritesShelf() -> getString(R.string.favorites)
        Library.root() -> getString(R.string.app_name)
        else -> f.name
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- menu: new shelf, sorting, settings ----

    private fun showMenu() {
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (Library.hasAccess(this) && current != Library.favoritesShelf()) {
            choices += getString(R.string.new_shelf) to ::askNewShelf
        }
        if (Library.hasAccess(this) && current != Library.favoritesShelf() && found == null) {
            choices += getString(R.string.select) to ::startPicking
        }
        choices += getString(R.string.sort_by) to ::askSort
        if (Library.hasAccess(this)) choices += getString(R.string.backup) to ::askBackup
        choices += getString(R.string.settings) to { startActivity(Intent(this, SettingsActivity::class.java)) }
        ShelfDialog(this).title(shelfName(current)).options(choices).show()
    }

    // ---- backup ----

    private fun askBackup() {
        val file = Library.backupFile()
        val saved = if (file.isFile) {
            getString(R.string.backup_from, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.lastModified())))
        } else {
            getString(R.string.backup_none)
        }
        val choices = ArrayList<Pair<String, () -> Unit>>()
        choices += getString(R.string.backup_save) to { toast(if (Library.saveBackup(this)) R.string.backup_saved else R.string.backup_failed) }
        if (file.isFile) choices += getString(R.string.backup_restore) to {
            toast(if (Library.restoreBackup(this)) R.string.backup_restored else R.string.backup_failed)
            refresh()
        }
        ShelfDialog(this).title(getString(R.string.backup)).back(::showMenu)
            .message(getString(R.string.backup_about, place(file)) + "\n\n" + saved)
            .options(choices).show()
    }

    // ---- choosing several books and shelves, to move or delete them together ----

    /** What has been chosen so far; null when nothing is being chosen. */
    private var picked: MutableSet<File>? = null

    private fun startPicking() {
        picked = LinkedHashSet()
        selectRow.visibility = View.VISIBLE
        refresh()
    }

    private fun stopPicking() {
        picked = null
        selectRow.visibility = View.GONE
        refresh()
    }

    /** Adds [f] to what is chosen, or takes it out again. The favorites are no folder and cannot be chosen. */
    private fun pick(f: File) {
        val set = picked ?: return
        if (f == Library.favoritesShelf()) return
        if (!set.remove(f)) set.add(f)
        refresh()
    }

    /** Asks which shelf the chosen things go to: any but the one they are on, themselves, or one inside them. */
    private fun askMoveTo() {
        val chosen = picked?.toList().orEmpty()
        if (chosen.isEmpty()) return
        val shelves = Library.root().walkTopDown()
            .onEnter { !it.name.startsWith(".") && it !in chosen }
            .filter { it.isDirectory && it != current }.toList()
        if (shelves.isEmpty()) {
            toast(R.string.nowhere_to_move)
            return
        }
        ShelfDialog(this).title(getString(R.string.move_to))
            .options(shelves.map { shelf -> place(shelf) to { moveAll(chosen, shelf) } }).show()
    }

    private fun moveAll(chosen: List<File>, to: File) {
        if (chosen.count { !Library.move(this, it, to) } > 0) toast(R.string.cannot_move)
        stopPicking()
    }

    private fun askDeleteAll() {
        val chosen = picked?.toList().orEmpty()
        if (chosen.isEmpty()) return
        ShelfDialog(this).title(getString(R.string.delete))
            .message(resources.getQuantityString(R.plurals.delete_many, chosen.size, chosen.size))
            .primary(R.string.delete) {
                val gone = chosen.mapNotNull { f -> Library.delete(this, f)?.let { it to f } }
                if (gone.size < chosen.size) toast(R.string.cannot_delete)
                if (gone.isNotEmpty()) offerUndo(resources.getQuantityString(R.plurals.things, gone.size, gone.size), gone)
                stopPicking()
            }
            .show()
    }

    private fun askNewShelf() {
        val dialog = ShelfDialog(this).title(getString(R.string.new_shelf)).back(::showMenu).input(R.string.shelf_name)
        dialog.primary(R.string.create) {
            if (Library.createShelf(this, current, dialog.text)) refresh() else toast(R.string.name_taken)
        }.show()
    }

    private fun askSort() {
        val sorts = Sort.values()
        ShelfDialog(this).title(getString(R.string.sort_by)).back(::showMenu)
            .pick(sorts.map { getString(it.label) }, Library.sort(this).ordinal) {
                // The shelf behind the dialog is put in the new order straight away.
                Library.setSort(this, sorts[it])
                refresh()
            }
            .show()
    }

    // ---- search ----

    private fun openSearch() {
        searchRow.visibility = View.VISIBLE
        query.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(query, 0)
        refresh()
    }

    private fun closeSearch() {
        searchRow.visibility = View.GONE
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        // Clearing the field ends the search, see search().
        if (query.text.isEmpty()) search("") else query.text.clear()
    }

    /** Looks for [text] on the current shelf and every shelf inside it; nothing typed shows the shelf again. */
    private fun search(text: String) {
        val run = ++searches
        if (text.isBlank()) {
            found = null
            refresh()
            return
        }
        val dir = current
        Library.io.execute {
            val hits = Library.search(applicationContext, dir, text)
            runOnUiThread {
                if (run == searches && !isDestroyed) {
                    found = hits
                    refresh()
                }
            }
        }
    }

    // ---- moving books and shelves by dragging ----

    /** What is being dragged, from the long press until it is dropped. */
    private var dragged: File? = null

    /** The shelf the dragged item is held over and would be put into, with its view on screen. */
    private var into: File? = null
    private var intoView: View? = null

    /** When the dragged item last changed place, to keep it from flickering between two places. */
    private var lastMove = 0L

    /** -1 or 1 while the drag is held near the top or the bottom edge and the shelf scrolls along. */
    private var scrolling = 0
    private val scroller = object : Runnable {
        override fun run() {
            if (scrolling == 0 || dragged == null) return
            shelf.scrollBy(0, scrolling * spec().px(9))
            shelf.postOnAnimation(this)
        }
    }

    /** Picks [f] up. Search results and the favorites are gathered from several shelves and stay put. */
    private fun startDrag(f: File, from: View) {
        if (picked != null) return pick(f)
        val fixed = Library.favoritesShelf()
        if (found != null || f == fixed || current == fixed) {
            showInfo(f)
            return
        }
        dragged = f
        if (from.startDragAndDrop(null, View.DragShadowBuilder(from), null, 0)) adapter.dragging = f else dragged = null
    }

    private fun onShelfDrag(e: DragEvent): Boolean {
        val f = dragged ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> hover(f, e.x, e.y)
            DragEvent.ACTION_DRAG_EXITED -> {
                hold(null, null)
                scroll(0)
            }
            DragEvent.ACTION_DROP -> {
                val target = into
                if (target != null) {
                    if (!Library.move(this, f, target)) toast(R.string.cannot_move)
                } else {
                    // The shelf keeps the order it was just given, whatever it was sorted by before.
                    val fixed = Library.favoritesShelf()
                    Library.setOrder(this, current, adapter.files.filter { it != fixed }.map { it.name })
                    Library.setSort(this, Sort.MANUAL)
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    /** The top rail takes what is dropped on it out of this shelf, to the one it stands on. */
    private fun onRailDrag(e: DragEvent): Boolean {
        val f = dragged ?: return false
        val outside = current.parentFile
        if (current == Library.root() || outside == null) return false
        when (e.action) {
            DragEvent.ACTION_DRAG_ENTERED -> up.animate().scaleX(1.5f).scaleY(1.5f).setDuration(120)
            DragEvent.ACTION_DRAG_EXITED -> up.animate().scaleX(1f).scaleY(1f).setDuration(120)
            DragEvent.ACTION_DROP -> if (!Library.move(this, f, outside)) toast(R.string.cannot_move)
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    /** The drag is held at ([x], [y]) of the shelf: over the middle of a shelf to go into it, else to take a place. */
    private fun hover(f: File, x: Float, y: Float) {
        val edge = spec().px(72)
        scroll(if (y < edge) -1 else if (y > shelf.height - edge) 1 else 0)
        val child = shelf.findChildViewUnder(x, y)
        val position = if (child == null) RecyclerView.NO_POSITION else shelf.getChildAdapterPosition(child)
        val target = adapter.files.getOrNull(position)
        if (child == null || target == null || target == f) {
            hold(null, null)
            return
        }
        val across = (x - child.left) / child.width
        if (target.isDirectory && across in 0.25f..0.75f) {
            hold(target, child)
            return
        }
        hold(null, null)
        // The favorites keep the first place.
        if (target == Library.favoritesShelf()) return
        val from = adapter.indexOf(f)
        val to = if (across > 0.5f) position.coerceAtLeast(from) else position.coerceAtMost(from)
        val now = SystemClock.uptimeMillis()
        if (from < 0 || to == from || now - lastMove < MOVE_MS) return
        lastMove = now
        adapter.move(from, to)
    }

    /** Shows [view], the shelf [target], as the one the dragged item would go into; null for none. */
    private fun hold(target: File?, view: View?) {
        if (view == intoView) return
        intoView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)
        view?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(120)
        into = target
        intoView = view
    }

    private fun scroll(direction: Int) {
        if (direction == scrolling) return
        scrolling = direction
        shelf.removeCallbacks(scroller)
        if (direction != 0) shelf.postOnAnimation(scroller)
    }

    /** Both the shelf and the rail are told that the drag is over; whichever is first tidies up. */
    private fun endDrag() {
        if (dragged == null) return
        dragged = null
        adapter.dragging = null
        hold(null, null)
        scroll(0)
        up.animate().scaleX(1f).scaleY(1f).setDuration(120)
        // Shows the shelf as it now is, which is as before if nothing was dropped anywhere.
        refresh()
    }

    private fun spec() = ShelfSpec(shelf.width, columns, resources.displayMetrics.density)

    private fun snapshotShelf(): Bitmap? {
        if (shelf.width == 0 || shelf.height == 0) return null
        return try {
            Bitmap.createBitmap(shelf.width, shelf.height, Bitmap.Config.RGB_565).also { shelf.draw(Canvas(it)) }
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Where a folder's miniature sits on the shelf: the scale and pivot that map the full shelf onto it. */
    private class Miniature(val px: Float, val py: Float, val scale: Float)

    private fun locate(thumbnail: View): Miniature {
        val at = IntArray(2)
        val origin = IntArray(2)
        thumbnail.getLocationInWindow(at)
        shelf.getLocationInWindow(origin)
        val spec = spec()
        // The picture is as tall as its place and centred in it. It starts with the top rail,
        // which on the real case is the bar above the shelf.
        val drawn = thumbnail.height.toFloat() * spec.width / spec.miniH
        val scale = (drawn / shelf.width).coerceIn(0.05f, 0.95f)
        val left = at[0] - origin[0] + (thumbnail.width - drawn) / 2
        val top = at[1] - origin[1] + spec.rail * scale
        return Miniature(left / (1 - scale), top / (1 - scale), scale)
    }

    /**
     * Folder transition: a pure change of size. A miniature is its folder's case drawn small, from
     * the top rail down to one of the planks, so the folder's shelf (always the upper, opaque layer) starts exactly on it, cropped to
     * the same portion, and is enlarged until it fills the screen while the crop opens up. The
     * parent shelf zooms in step underneath. Closing plays the same thing backwards.
     * [snapshot] is the shelf being left; the live list shows the one being entered.
     */
    private fun zoom(m: Miniature, snapshot: Bitmap, opening: Boolean) {
        shelf.animate().cancel()
        ghost.animate().cancel()
        ghost.setImageBitmap(snapshot)
        ghost.visibility = View.VISIBLE
        for (v in arrayOf(shelf, ghost)) {
            v.pivotX = m.px
            v.pivotY = m.py
            v.alpha = 1f
            v.clipBounds = null
        }
        // Opening: the list is the folder (on top), the snapshot is the parent (below). Closing: the reverse.
        val folder: View = if (opening) shelf else ghost
        val w = shelf.width
        val h = shelf.height
        val inset = shelf.paddingTop
        val cropped = spec().let { inset + it.miniRows * it.rowH }.coerceAtMost(h)
        val crop = if (opening) Rect(0, inset, w, cropped) else Rect(0, 0, w, h)
        folder.clipBounds = crop
        ghost.translationZ = if (opening) -1f else 1f
        ghost.scaleX = 1f
        ghost.scaleY = 1f
        shelf.scaleX = if (opening) m.scale else 1 / m.scale
        shelf.scaleY = shelf.scaleX

        val curve = AccelerateDecelerateInterpolator()
        val ghostTo = if (opening) 1 / m.scale else m.scale
        val shelfMove = shelf.animate().scaleX(1f).scaleY(1f)
        val ghostMove = ghost.animate().scaleX(ghostTo).scaleY(ghostTo)
        for (move in arrayOf(shelfMove, ghostMove)) {
            move.setDuration(ZOOM_MS).setInterpolator(curve).setUpdateListener(null).withLayer()
        }
        (if (opening) shelfMove else ghostMove).setUpdateListener { a ->
            val t = if (opening) a.animatedFraction else 1 - a.animatedFraction
            crop.top = (inset * (1 - t)).toInt()
            crop.bottom = (cropped + (h - cropped) * t).toInt()
            folder.clipBounds = crop
        }
        ghostMove.withEndAction {
            shelf.clipBounds = null
            ghost.clipBounds = null
            hideGhost()
        }
        shelfMove.start()
        ghostMove.start()
    }

    private fun hideGhost() {
        ghost.visibility = View.GONE
        ghost.setImageBitmap(null)
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    // ---- details, shown by tapping a plaque ----

    private fun showInfo(f: File) {
        if (picked != null) return pick(f)
        if (Library.isShelf(f)) {
            val kids = Library.shelf(this, f)
            val folders = kids.count { it.isDirectory }
            val rows = ArrayList<Pair<String, String>>()
            if (f == Library.favoritesShelf()) {
                rows += "Books" to "${kids.size}"
            } else {
                rows += "Folders" to "$folders"
                rows += "Books" to "${kids.size - folders}"
                rows += "Location" to place(f)
            }
            val dialog = ShelfDialog(this).title(shelfName(f)).details(rows).arrowCloses()
            if (f != Library.favoritesShelf()) {
                dialog.name(f.name) { rename(f, it) }
                dialog.icon(R.drawable.ic_delete, R.string.delete) {
                    dialog.abandon()
                    askDelete(f)
                }
            }
            dialog.show()
            return
        }
        Library.io.execute {
            val meta = Library.meta(applicationContext, f)
            val cover = Library.cover(applicationContext, f)
            runOnUiThread { if (!isFinishing && !isDestroyed) showBookInfo(f, meta, cover) }
        }
    }

    private fun rename(f: File, name: String) {
        if (Library.rename(this, f, name) == null) toast(R.string.name_taken)
        refresh()
    }

    /** Deleting takes the files off the phone, so it is asked about first. */
    private fun askDelete(f: File) {
        val warning = if (f.isDirectory) {
            val inside = f.walkTopDown().filter { it != f && !it.name.startsWith(".") }.toList()
            getString(R.string.delete_shelf, f.name, inside.count(Library::isBook), inside.count { it.isDirectory })
        } else {
            getString(R.string.delete_book, Library.cachedMeta(this, f)?.title ?: f.nameWithoutExtension)
        }
        ShelfDialog(this).title(getString(R.string.delete)).back { showInfo(f) }.message(warning)
            .primary(R.string.delete) {
                val name = if (f.isDirectory) f.name else Library.cachedMeta(this, f)?.title ?: f.nameWithoutExtension
                val binned = Library.delete(this, f)
                if (binned == null) toast(R.string.cannot_delete) else offerUndo(name, binned, f)
                refresh()
            }
            .show()
    }

    /** What was deleted last and can still be put back: where each thing lies and where it was. */
    private var binned: List<Pair<File, File>> = emptyList()
    private val undoOver = Runnable { settleDelete() }

    /** Shows for a few seconds that [name] was deleted, with a button that puts it back where it was. */
    private fun offerUndo(name: String, binned: File, to: File) = offerUndo(name, listOf(binned to to))

    private fun offerUndo(name: String, gone: List<Pair<File, File>>) {
        settleDelete()
        binned = gone
        undoText.text = getString(R.string.deleted, name)
        undoBar.visibility = View.VISIBLE
        findViewById<View>(R.id.undo).setOnClickListener {
            undoBar.removeCallbacks(undoOver)
            undoBar.visibility = View.GONE
            binned = emptyList()
            if (gone.count { (from, to) -> !Library.restore(this, from, to) } > 0) toast(R.string.cannot_move)
            refresh()
        }
        undoBar.postDelayed(undoOver, UNDO_MS)
    }

    /** The time to undo the last delete is over: it is deleted for good. */
    private fun settleDelete() {
        undoBar.removeCallbacks(undoOver)
        undoBar.visibility = View.GONE
        val gone = binned
        binned = emptyList()
        val app = applicationContext
        if (gone.isNotEmpty()) Library.io.execute { for ((from, _) in gone) Library.purge(app, from) }
    }

    /** Where [f] is, starting from the library folder rather than from the root of the storage. */
    private fun place(f: File) = f.path.removePrefix(Library.root().parent.orEmpty()).trimStart('/')

    private fun showBookInfo(f: File, m: BookMeta, cover: Bitmap?) {
        val st = Library.state(this, f)
        val started = st.lastRead > 0
        val rows = ArrayList<Pair<String, String>>()
        rows += "Author" to m.author.ifBlank { "Unknown" }
        rows += "Format" to f.extension.uppercase()
        rows += "Read" to "${(st.percent * 100).roundToInt()}%"
        if (Library.isEpub(f)) {
            // EPUBs have no fixed pages; the count is estimated from the amount of text.
            rows += "Pages" to "about ${(st.percent * m.pages).roundToInt()} of ${m.pages}"
            rows += "Chapter" to "${if (started) (st.chapter + 1).coerceAtMost(m.chapters) else 0} of ${m.chapters}"
        } else {
            rows += "Pages" to "${if (started) (st.chapter + 1).coerceAtMost(m.pages) else 0} of ${m.pages}"
        }
        rows += "Size" to Formatter.formatShortFileSize(this, f.length())
        rows += "Last read" to
            if (started) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(st.lastRead))
            else "never"
        f.parentFile?.let { rows += "Folder" to place(it) }

        // The plate holds the name, which can be changed there; the star and the bin are beside it.
        val dialog = ShelfDialog(this).cover(cover).details(rows).arrowCloses()
        dialog.name(m.title) { rename(f, it) }
            .switch(Library.isFavorite(this, f), R.drawable.ic_star, R.drawable.ic_star_outline, R.string.favorite) { on ->
                Library.setFavorite(this, f, on)
                refresh()
            }
            .icon(R.drawable.ic_delete, R.string.delete) {
                dialog.abandon()
                askDelete(f)
            }
            .primarySwitch(Library.isFinished(this, f), R.string.finished, R.string.mark_finished) { on ->
                Library.setFinished(this, f, on)
                refresh()
            }
            .show()
    }

    companion object {
        private const val KEY_DIR = "dir"
        private const val ZOOM_MS = 560L
        private const val MOVE_MS = 180L
        private const val UNDO_MS = 10000L
    }
}

/**
 * The places on a shelf. Tapping one opens it, tapping its plaque shows its details, and a long
 * press picks it up.
 */
class ShelfAdapter(
    private val onOpen: (File, View) -> Unit,
    private val onInfo: (File) -> Unit,
    private val onPickUp: (File, View) -> Unit,
    private val spec: () -> ShelfSpec,
) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

    private var items: MutableList<File> = ArrayList()

    /** What stands on the shelf, in the order shown. */
    val files: List<File> get() = items

    /** While several things are being chosen: those chosen so far, which stand out from the rest. */
    var picked: Set<File>? = null

    /** Shows on each book the shelf it stands on, for books gathered from several shelves. */
    var showPlaces = false

    /** The item being dragged; its place on the shelf stays empty while it is carried about. */
    var dragging: File? = null
        set(value) {
            val changed = listOfNotNull(field, value)
            field = value
            // Said to be a small change, so that the place is not faded over into a new view.
            for (f in changed) indexOf(f).let { if (it >= 0) notifyItemChanged(it, "drag") }
        }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val cover: RatioImageView = v.findViewById(R.id.cover)
        val gap: View = v.findViewById(R.id.gap)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val place: TextView = v.findViewById(R.id.place)
        val plaque: TextView = v.findViewById(R.id.plaque)
        var file: File? = null
    }

    fun indexOf(f: File) = items.indexOf(f)

    fun submit(list: List<File>) {
        items = ArrayList(list)
        notifyDataSetChanged()
    }

    /** Takes the item at [from] to the place [to], the others closing up behind it. */
    fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)).also {
            it.plaque.background = PlaqueDrawable(parent.resources.displayMetrics.density)
        }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val f = items[position]
        val ctx = h.itemView.context.applicationContext
        h.file = f
        h.itemView.setOnClickListener { onOpen(f, it) }
        h.plaque.setOnClickListener { onInfo(f) }
        for (v in arrayOf(h.itemView, h.plaque)) v.setOnLongClickListener {
            onPickUp(f, h.itemView)
            true
        }
        // What is carried leaves its place empty. Its parts are hidden rather than the place as a
        // whole, which the list makes visible again whenever it moves it.
        val carried = f == dragging
        val place = h.itemView as ViewGroup
        val faint = picked.let { it != null && f !in it }
        for (i in 0 until place.childCount) place.getChildAt(i).apply {
            visibility = if (carried) View.INVISIBLE else View.VISIBLE
            alpha = if (faint) 0.35f else 1f
        }
        // A recycled view may have been left enlarged as the shelf something was dragged over.
        h.itemView.scaleX = 1f
        h.itemView.scaleY = 1f
        h.place.visibility = if (showPlaces && !Library.isShelf(f)) View.VISIBLE else View.GONE
        if (showPlaces) h.place.text = f.parentFile.let {
            if (it == null || it == Library.root()) ctx.getString(R.string.app_name) else it.name
        }
        val shelfSpec = spec()
        h.cover.binding = !Library.isShelf(f)
        // A book fills its place; a case is narrower, stands in the middle of it and rises above
        // the books into the gap under the shelf above, which the row's top padding gives up.
        val rise = if (Library.isShelf(f) && shelfSpec.width > 0) shelfSpec.miniRise else 0
        h.cover.scaleType = if (Library.isShelf(f)) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        h.cover.setBackgroundColor(if (Library.isShelf(f)) 0 else 0xFF24160D.toInt())
        h.cover.ratio = 1.5f + if (rise > 0) rise.toFloat() / shelfSpec.coverW else 0f
        h.gap.layoutParams.height = shelfSpec.px(16) - rise
        h.gap.requestLayout()
        val plate = h.plaque.background as PlaqueDrawable
        if (Library.isShelf(f)) {
            h.plaque.text = if (f == Library.favoritesShelf()) ctx.getString(R.string.favorites) else f.name
            // The shelf of the favorites wears their plate.
            plate.favorite = f == Library.favoritesShelf()
            plate.finished = false
            h.progress.visibility = View.GONE
            if (shelfSpec.width <= 0) return
            val preview = Library.cachedFolderPreview(f, shelfSpec)
            h.cover.setImageBitmap(preview)
            if (preview == null) Library.io.execute {
                val bmp = Library.folderPreview(ctx, f, shelfSpec)
                Library.main.post { if (h.file == f) h.cover.setImageBitmap(bmp) }
            }
            return
        }
        val st = Library.state(ctx, f)
        // A favorite is told by its plate, which leaves the cover free.
        plate.favorite = Library.isFavorite(ctx, f)
        plate.finished = Library.isFinished(ctx, f)
        h.progress.visibility = if (st.percent > 0f) View.VISIBLE else View.GONE
        h.progress.progress = (st.percent * 100).roundToInt()
        h.plaque.text = Library.cachedMeta(ctx, f)?.title ?: f.nameWithoutExtension
        val cached = Library.cachedCover(f)
        h.cover.setImageBitmap(cached)
        if (cached == null) Library.io.execute {
            val meta = Library.meta(ctx, f)
            val bmp = Library.cover(ctx, f)
            Library.main.post {
                if (h.file == f) {
                    h.cover.setImageBitmap(bmp)
                    h.plaque.text = meta.title
                }
            }
        }
    }
}

/**
 * Draws the bookcase: a wooden plank under every row of books, empty shelves below the last row,
 * and on top of everything the case's side posts, which stay put while the
 * shelves scroll inside it.
 */
class ShelfDecoration(ctx: Context, private val columns: Int) : RecyclerView.ItemDecoration() {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val bottoms = TreeSet<Int>()
        var row = ShelfSpec(parent.width, columns, d).rowH.coerceAtLeast(1)
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            // Where the row is, not where a book sliding to another place happens to be on its way.
            bottoms.add(v.bottom)
            if (v.height > 0) row = v.height
        }
        for (b in bottoms) drawPlank(c, parent.width.toFloat(), b.toFloat(), d, paint)
        var b = (if (bottoms.isEmpty()) parent.paddingTop else bottoms.last()) + row
        while (b - (PLANK + LIP) * d < parent.height) {
            drawPlank(c, parent.width.toFloat(), b.toFloat(), d, paint)
            b += row
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        drawCase(c, parent.width.toFloat(), parent.height.toFloat(), ShelfSpec(parent.width, columns, d), paint)
    }

    companion object {
        private const val PLANK = 44
        private const val LIP = 5
        private const val SHADE = 10

        /** One plank whose front face ends at [bottom]; [d] is the display density. */
        fun drawPlank(c: Canvas, w: Float, bottom: Float, d: Float, paint: Paint) {
            val top = bottom - PLANK * d
            gradient(c, w, top - LIP * d, top, 0xFF4A2E1A.toInt(), 0xFFA9743F.toInt(), paint)
            gradient(c, w, top, bottom, 0xFF8B5A2B.toInt(), 0xFF5C3A1E.toInt(), paint)
            // A lit front edge and a dark underside give the board some thickness.
            paint.shader = null
            paint.color = 0x40FFE2B0
            c.drawRect(0f, top, w, top + d, paint)
            paint.color = 0x66000000
            c.drawRect(0f, bottom - 1.5f * d, w, bottom, paint)
            paint.alpha = 255
            gradient(c, w, bottom, bottom + SHADE * d, 0xAA000000.toInt(), 0x00000000, paint)
        }

        /** The top rail of the case, [h] tall, and its shadow: the board of the app's top bar (shelf_plank.xml). */
        fun drawRail(c: Canvas, w: Float, h: Float, d: Float, paint: Paint) {
            gradient(c, w, 0f, h, 0xFF8B5A2B.toInt(), 0xFF5C3A1E.toInt(), paint)
            paint.shader = null
            paint.color = 0x66000000
            c.drawRect(0f, h - 1.5f * d, w, h, paint)
            paint.alpha = 255
            gradient(c, w, h, h + SHADE * d, 0xAA000000.toInt(), 0x00000000, paint)
            paint.shader = null
        }

        /** The case around a shelf area of [w] by [h]: two side posts and the shadows they cast. */
        fun drawCase(c: Canvas, w: Float, h: Float, spec: ShelfSpec, paint: Paint) {
            val post = spec.post.toFloat()
            val shade = 6 * spec.density
            val wood = intArrayOf(0xFF4A2E1A.toInt(), 0xFF9A6836.toInt(), 0xFF5C3A1E.toInt())
            paint.alpha = 255
            for (left in floatArrayOf(0f, w - post)) {
                paint.shader = LinearGradient(left, 0f, left + post, 0f, wood, null, Shader.TileMode.CLAMP)
                c.drawRect(left, 0f, left + post, h, paint)
            }
            // Shadows the posts cast on the shelves beside them.
            paint.shader = LinearGradient(post, 0f, post + shade, 0f, 0x88000000.toInt(), 0, Shader.TileMode.CLAMP)
            c.drawRect(post, 0f, post + shade, h, paint)
            paint.shader =
                LinearGradient(w - post - shade, 0f, w - post, 0f, 0, 0x88000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(w - post - shade, 0f, w - post, h, paint)
            paint.shader = null
        }

        private fun gradient(c: Canvas, w: Float, top: Float, bottom: Float, from: Int, to: Int, paint: Paint) {
            // A shader is still multiplied by the paint alpha, which an earlier translucent colour may have lowered.
            paint.alpha = 255
            paint.shader = LinearGradient(0f, top, 0f, bottom, from, to, Shader.TileMode.CLAMP)
            c.drawRect(0f, top, w, bottom, paint)
        }
    }
}
