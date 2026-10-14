package io.github.edoardonegri.ready

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The bookshelf: mirrors the "Read-y" folder, one shelf screen per folder. Back leaves a sub-folder.
 * Books and shelves are picked up with a long press and dragged: onto another place to put the
 * shelf in a new order, onto a shelf to put them inside it, onto the arrow of the top rail to take
 * them out to the shelf this one stands on. Held over a shelf or the arrow for a while, they are
 * taken there to be put down wherever they should go. What is put down again where it was stays
 * chosen, and is carried along with whatever is picked up next. While anything is chosen, a small
 * board hangs under the name plate, with a star, a tick and a bin on it.
 * Tapping them, or dropping the books on them, makes favorites of the books, marks them as read
 * or deletes them, and its cross lets go of all that is chosen, as Back does. The arrow leads
 * out of the shelf with what is chosen still chosen, for more to be chosen out there, and what a
 * search finds can be chosen too: all of it is then carried, moved, marked or deleted together,
 * whichever shelf it stands on. Meanwhile a tap on a book or a shelf chooses it, or lets go of it,
 * and two taps on a shelf open it, to choose from what stands on it; a long press carries all
 * that is chosen. A tap on a plaque then opens what changes its book or shelf: the name, the
 * author and the cover of a book, the name and the wood of a shelf. Otherwise it shows the details.
 * Beside the arrow of the top rail is the open book that goes on with the book read last.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var current: File
    private lateinit var shelf: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var grant: View
    private lateinit var ghost: ImageView
    private lateinit var bar: View
    private lateinit var barTitle: TextView
    private lateinit var up: View
    private lateinit var bin: View
    private lateinit var pickRow: View
    private lateinit var pickStar: ImageButton
    private lateinit var pickRead: ImageButton
    private lateinit var searchRow: View
    private lateinit var star: ImageButton
    private lateinit var query: EditText
    private var columns = 3
    private val adapter = ShelfAdapter(::open, ::onPlaque, ::startDrag, ::spec)
    private lateinit var decoration: ShelfDecoration

    /** The books found for what is in the search field; shown instead of the shelf while there is any. */
    private var found: List<File>? = null

    /** The star beside the search field is on: only the favorites are shown and searched. */
    private var starred = false

    /** Counts the searches started, so that a slow one cannot overwrite a later one. */
    private var searches = 0

    private val searching get() = searchRow.isVisible

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = when {
            picked != null -> stopPicking()
            searching -> closeSearch()
            else -> goUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.upgrade(this)
        setContentView(R.layout.activity_main)
        shelf = findViewById(R.id.shelf)
        ghost = findViewById(R.id.ghost)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.emptyText)
        grant = findViewById(R.id.grant)
        barTitle = findViewById(R.id.barTitle)
        up = findViewById(R.id.up)
        searchRow = findViewById(R.id.searchRow)
        star = findViewById(R.id.starred)
        star.setOnClickListener { showStarred(!starred) }
        query = findViewById(R.id.query)
        bar = findViewById(R.id.bar)
        barTitle.background = PlaqueDrawable(resources.displayMetrics.density, ornate = true)
        pickRow = findViewById(R.id.pickRow)
        findViewById<View>(R.id.resume).apply {
            visibility = View.VISIBLE
            setOnClickListener { resume() }
        }
        pickRow.clipToOutline = true
        pickRow.setOnDragListener { _, e -> onBoardDrag(e) }
        pickStar = findViewById(R.id.pickStar)
        pickRead = findViewById(R.id.pickRead)
        bin = findViewById(R.id.bin)
        pickStar.setOnClickListener { markChosen(favorite = true) }
        pickRead.setOnClickListener { markChosen(favorite = false) }
        bin.setOnClickListener { chosen()?.let(::askDelete) }
        findViewById<View>(R.id.closePick).setOnClickListener { stopPicking() }
        grant.background = PlaqueDrawable(resources.displayMetrics.density)
        // The grain of the board ends at its round corners.
        empty.clipToOutline = true

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            // The bookcase runs edge to edge: its top rail fills the status bar area, and the rows
            // can scroll clear of the navigation bar.
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, 0, i.right, 0)
            bar.padRail(i.top)
            shelf.setPadding(shelf.paddingLeft, 0, shelf.paddingRight, i.bottom)
            insets
        }

        columns = (resources.configuration.screenWidthDp / 118).coerceAtLeast(3)
        shelf.layoutManager = GridLayoutManager(this, columns)
        decoration = ShelfDecoration(this, columns)
        shelf.addItemDecoration(decoration)
        // Leave room for the bookcase's side posts.
        ShelfSpec(0, columns, resources.displayMetrics.density).let {
            shelf.setPadding(it.side, shelf.paddingTop, it.side, shelf.paddingBottom)
        }
        shelf.adapter = adapter
        // The case fills the screen whatever stands in it, so a change on the shelves need not
        // measure it anew; and rows scrolled just out of sight are kept as they are, not drawn again.
        shelf.setHasFixedSize(true)
        shelf.setItemViewCacheSize(columns * 2)
        shelf.setOnDragListener { _, e -> onShelfDrag(e) }
        bar.setOnDragListener { _, e -> onRailDrag(e) }

        current = savedInstanceState?.getString(KEY_DIR)?.let(::File) ?: Library.root()
        coverFor = savedInstanceState?.getString(KEY_COVER)?.let(::File)
        grant.setOnClickListener { requestAccess() }
        // What is chosen stays chosen on the way out of a shelf; only Back lets go of it.
        up.setOnClickListener { if (picked != null && !searching && outside() != null) goUp() else back.handleOnBackPressed() }
        findViewById<View>(R.id.more).setOnClickListener { showMenu() }
        findViewById<View>(R.id.search).setOnClickListener { if (searching) closeSearch() else openSearch() }
        findViewById<View>(R.id.closeSearch).setOnClickListener { closeSearch() }
        query.doAfterTextChanged {
            // The library is gone through once the typing pauses, not for every letter. An emptied field ends the search at once.
            query.removeCallbacks(typed)
            if (it.isNullOrEmpty()) search("") else query.postDelayed(typed, TYPING_MS)
        }
        onBackPressedDispatcher.addCallback(this, back)

        // What was deleted when the app was last closed is gone for good by now.
        if (savedInstanceState == null) applicationContext.let { app -> Library.io.execute { Library.emptyBin(app) } }

        // A fresh start shows the first shelf, whatever was being read when the app was closed:
        // the open book of the top rail goes on with that. Only a book the widget opened leads
        // to the shelf it stands on.
        if (savedInstanceState == null) asked = asked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        asked(intent)?.let { asked = it }
    }

    override fun onResume() {
        super.onResume()
        val book = asked
        asked = null
        if (book == null || !showShelfOf(book)) refresh()
        val left = BookOpening.left
        BookOpening.left = null
        if (left != null && SystemClock.uptimeMillis() - left.at < BookOpening.LEFT_MS) closeBook(left) else opening.clear()
    }

    /**
     * A book that was opened on the home-screen widget, which the reader is now left from: the
     * shelf it stands on is the one to show, and to close the book on.
     */
    private var asked: File? = null

    /** The book [intent] asks for, which it is then no longer asked for. */
    private fun asked(intent: Intent): File? {
        val path = intent.getStringExtra(ReaderActivity.EXTRA_PATH) ?: return null
        intent.removeExtra(ReaderActivity.EXTRA_PATH)
        return File(path)
    }

    /** Goes to the shelf [book] stands on, with the book in view. False if there is no such book to go to. */
    private fun showShelfOf(book: File): Boolean {
        if (!book.isFile || !Library.hasAccess(this)) return false
        picked = null
        if (searching) closeSearch()
        current = book.parentFile ?: Library.root()
        refresh()
        adapter.indexOf(book).let { if (it >= 0) shelf.scrollToPosition(it) }
        return true
    }

    override fun onStop() {
        // The reader has taken over from the page the book was opened to.
        opening.clear()
        super.onStop()
    }

    override fun onPause() {
        Library.backUp(this)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DIR, current.path)
        coverFor?.let { outState.putString(KEY_COVER, it.path) }
    }

    private fun refresh() {
        val root = Library.root()
        if (!Library.hasAccess(this)) {
            showRail()
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
        // A backup that came with the folder, e.g. from another phone, is taken over before anything is shown.
        Library.adoptBackup(this)
        if (!current.isDirectory || !current.path.startsWith(root.path)) current = root
        val atRoot = current == root
        showWood()
        val results = found
        val books = if (results != null) Library.arrange(this, null, results) else Library.shelf(this, current)
        // What is carried has left its places. It is given one empty place on the shelf being
        // shown, for the finger to take to wherever it should stand.
        val held = dragged
        val items = if (held == null) books else books.filter { it !in adapter.carried } + held
        back.isEnabled = !atRoot || searching || picked != null
        adapter.picked = picked
        // The arrow leads out of a shelf, so the first one has none, whatever else Back would undo.
        up.visibility = if (atRoot) View.INVISIBLE else View.VISIBLE
        showRail()
        // Books found on several shelves each say which one they stand on. The favorites do not:
        // their plaques tell where they are to whoever asks.
        adapter.showPlaces = results != null && !starred
        adapter.submit(items)
        grant.visibility = View.GONE
        // The keyboard covers the middle of the screen while searching, where the answer would be.
        (empty.layoutParams as FrameLayout.LayoutParams).gravity =
            if (searching) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = when {
            results != null && starred && query.text.isBlank() -> getString(R.string.favorites_empty)
            results != null -> getString(R.string.no_results)
            else -> getString(R.string.empty_folder, current.path)
        }
    }

    /** Makes the case, its top rail and the search bar under it of the wood of the shelf shown. */
    private fun showWood() {
        val wood = Library.wood(this, current)
        if (decoration.wood == wood) return
        decoration.wood = wood
        for (v in arrayOf(shelf, bar, searchRow)) v.background?.mutate()?.colorFilter = wood.filter
        shelf.invalidate()
    }

    private fun goUp() {
        val left = current
        val snapshot = snapshotShelf()
        current = current.parentFile ?: Library.root()
        // What is carried out of the shelf is no longer in its miniature, which is drawn anew at
        // once for that: the shelf closes into it.
        if (dragged != null && shelf.width > 0) Library.folderPreview(this, left, spec())
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
        // While things are chosen a tap chooses more; a shelf is opened with two.
        if (picked != null) return if (f.isDirectory) tapShelf(f, from) else pick(f)
        if (f.isDirectory) enter(f, from) else read(f, from)
    }

    /** The shelf tapped once while things are chosen, which is chosen unless it is tapped again at once. */
    private var tapped: File? = null
    private val tappedOnce = Runnable { tapped?.let { tapped = null; pick(it) } }

    /** A shelf was tapped while things are chosen: once it is chosen, or let go of; twice it is opened. */
    private fun tapShelf(dir: File, from: View?) {
        shelf.removeCallbacks(tappedOnce)
        val before = tapped
        tapped = null
        if (before == dir) return enter(dir, from)
        before?.let(::pick)
        tapped = dir
        shelf.postDelayed(tappedOnce, ViewConfiguration.getDoubleTapTimeout().toLong())
    }

    /** Opens the book [f] in the reader, out of its place [from] on the shelf if that is given. */
    private fun read(f: File, from: View?) {
        val reader = Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, f.path)
        val cover = from?.findViewById<View>(R.id.cover) ?: return startActivity(reader)
        // The book opens on the shelf, and the reader takes over once its page fills the screen.
        val settings = ReaderSettings(this)
        opening.open(cover, settings.lit(settings.palette.bg)) {
            startActivity(reader.putExtra(ReaderActivity.EXTRA_FROM_SHELF, true).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }

    /**
     * Goes on with the book that was read last, wherever in the library it stands: out of its
     * place, if it happens to be in view.
     */
    private fun resume() {
        val app = applicationContext
        Library.io.execute {
            val last = Library.lastRead(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (last == null) return@runOnUiThread toast(R.string.nothing_read)
                read(last, shelf.findViewHolderForAdapterPosition(adapter.indexOf(last))?.itemView)
            }
        }
    }

    /** Opens a book on the shelf before the reader shows it, and closes it again afterwards. */
    private val opening by lazy { BookOpening(this) }

    /** The reader came back with [left], the page its book was at: the book closes from it, where it stands. */
    private fun closeBook(left: BookOpening.Left) {
        val settings = ReaderSettings(this)
        opening.close(left.page, settings.lit(settings.palette.bg), settings.lamp) {
            shelf.findViewHolderForAdapterPosition(adapter.indexOf(left.file))?.itemView?.findViewById(R.id.cover)
        }
    }

    /** Shows the shelf [dir], which grows out of its miniature in the place [from], if that is given. */
    private fun enter(dir: File, from: View?) {
        val miniature = from?.findViewById<View>(R.id.cover)?.let(::locate)
        val snapshot = if (miniature != null) snapshotShelf() else null
        current = dir
        refresh()
        shelf.scrollToPosition(0)
        // Start only once the folder's books are laid out, so no frame shows the old shelf shrunk.
        if (miniature != null && snapshot != null) {
            shelf.doOnPreDraw { zoom(miniature, snapshot, opening = true) }
        }
    }

    private fun shelfName(f: File): String = if (f == Library.root()) getString(R.string.app_name) else f.name

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- menu: new shelf, sorting, settings ----

    private fun showMenu() {
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (Library.hasAccess(this)) choices += getString(R.string.new_shelf) to ::askNewShelf
        choices += getString(R.string.sort_by) to ::askSort
        choices += getString(R.string.settings) to { settings.launch(Intent(this, SettingsActivity::class.java)) }
        // It is closed by a tap beside it; the dialogs it leads to have an arrow back to it.
        ShelfDialog(this).title(getString(R.string.menu)).uncrossed().options(choices).show()
    }

    /** The settings, which lead back to the menu if they are left by their arrow. */
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) showMenu()
    }

    // ---- choosing several books and shelves, to move or delete them together ----

    /**
     * What has been chosen so far, by picking it up with a long press; null when nothing is.
     * While there is any, a tap chooses more or lets go of what was chosen, and the next long
     * press carries all of it as one. Back lets go of everything.
     */
    private var picked: MutableSet<File>? = null

    private fun stopPicking() {
        shelf.removeCallbacks(tappedOnce)
        tapped = null
        picked = null
        refresh()
    }

    /** Adds [f] to what is chosen, or takes it out again. */
    private fun pick(f: File) {
        val set = picked ?: return
        if (!set.remove(f)) set.add(f)
        if (set.isEmpty()) stopPicking() else refresh()
    }

    /**
     * The name plate, and the small board that hangs under it while anything is chosen: it
     * holds the star and the tick, filled in if all the books chosen are
     * favorites, or are read, which a tap then undoes; the bin; and a cross that lets go of all
     * that is chosen. The miniatures of the shelves show what is chosen on them.
     */
    private fun showRail() {
        barTitle.text = shelfName(current)
        pickRow.visibility = if (picked != null) View.VISIBLE else View.GONE
        // While things are carried, the shelves show in full.
        Library.chosen = if (dragged == null) picked?.toSet().orEmpty() else emptySet()
        val books = picked?.filter { it.isFile } ?: return
        val favorites = books.isNotEmpty() && books.all { Library.isFavorite(this, it) }
        val read = books.isNotEmpty() && books.all { Library.isFinished(this, it) }
        pickStar.setImageResource(if (favorites) R.drawable.ic_star else R.drawable.ic_star_outline)
        pickRead.setImageResource(if (read) R.drawable.ic_check else R.drawable.ic_check_outline)
    }

    /**
     * Makes favorites of the books among [chosen], or marks them as read. If all of them already
     * are, they no longer are. The shelves among them are left as they are.
     */
    private fun mark(chosen: List<File>, favorite: Boolean) {
        val books = chosen.filter { it.isFile }
        val on = !books.all { if (favorite) Library.isFavorite(this, it) else Library.isFinished(this, it) }
        for (b in books) if (favorite) Library.setFavorite(this, b, on) else Library.setFinished(this, b, on)
    }

    /** The star or the tick was tapped: what is chosen is marked, and stays chosen for the other of the two. */
    private fun markChosen(favorite: Boolean) {
        mark(chosen() ?: return, favorite)
        if (found == null) return refresh()
        // What was found may no longer be what would be found, e.g. among the favorites.
        picked = null
        search(query.text.toString())
    }

    private fun moveAll(chosen: List<File>, to: File) {
        if (chosen.count { it.parentFile != to && !Library.move(this, it, to) } > 0) toast(R.string.cannot_move)
    }

    /**
     * What is chosen, on this shelf and on others; null when nothing is. What stands on a shelf
     * that is chosen too is left out: it goes wherever that shelf goes.
     */
    private fun chosen(): List<File>? = picked?.let { set ->
        set.filter { f -> generateSequence(f.parentFile) { it.parentFile }.none { it in set } }
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
            .dropdown(sorts.map { getString(it.label) }, Library.sort(this).ordinal) {
                // The shelf behind the dialog is put in the new order straight away.
                Library.setSort(this, sorts[it])
                refresh()
            }
            // Each order runs its usual way round until it is turned here.
            .either(R.string.sort_ascending, R.string.sort_descending, { !Library.descending(this) }) { ascending ->
                Library.setDescending(this, !ascending)
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
        starred = false
        star.setImageResource(R.drawable.ic_star_outline)
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        // Clearing the field ends the search, see search().
        if (query.text.isEmpty()) search("") else query.text.clear()
    }

    private val typed = Runnable { search(query.text.toString()) }

    /** Shows only the favorites, all there are in the library, or goes back to showing everything. */
    private fun showStarred(on: Boolean) {
        starred = on
        star.setImageResource(if (on) R.drawable.ic_star else R.drawable.ic_star_outline)
        search(query.text.toString())
    }

    /**
     * Looks for [text] on the current shelf and every shelf inside it, or among the favorites
     * while the star is on. Nothing typed shows the shelf again, or all the favorites.
     */
    private fun search(text: String) {
        val run = ++searches
        if (text.isBlank() && !starred) {
            found = null
            refresh()
            return
        }
        val dir = current
        val favorites = starred
        Library.io.execute {
            val hits = Library.search(applicationContext, dir, text, favorites)
            runOnUiThread {
                if (run == searches && !isDestroyed) {
                    found = hits
                    refresh()
                }
            }
        }
    }

    // ---- moving books and shelves by dragging ----

    /** What the finger is on while things are carried, from the long press until they are dropped. */
    private var dragged: File? = null

    /** The shelf the things were picked up from, and how it stood then. */
    private var origin: File? = null
    private var before: List<File> = emptyList()

    /** Whether what is carried has taken another place on the shelf. */
    private var reordered = false

    /** Whether the things were dropped somewhere, rather than put down again where they were. */
    private var acted = false


    /** The shelf the dragged item is held over and would be put into, with its view on screen. */
    private var into: File? = null
    private var intoView: View? = null

    /** The arrow of the top rail or a button of the board under it, while the things are held over it. */
    private var aimed: View? = null

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

    /**
     * What is carried was held over a shelf for a while: the shelf opens as it does when tapped,
     * to put it down inside.
     */
    private val opener = Runnable {
        val dir = into
        val place = intoView
        if (dir != null && place != null && dragged != null) {
            // Back to its own size at once, for the shelf to grow out of the miniature as it stands.
            place.animate().cancel()
            place.scaleX = 1f
            place.scaleY = 1f
            carry { enter(dir, place) }
        }
    }

    /**
     * What is carried was held over the arrow for a while: this shelf closes as it does on going
     * back, and the one it stands on is shown.
     */
    private val leaver = Runnable { if (outside() != null && dragged != null && aimed == up) carry(::goUp) }

    /**
     * Picks [f] up, along with whatever was chosen before: all of it is carried as one, in the
     * place of [f]. What a search found is gathered from several shelves and stays put: it is
     * only chosen, for the star and the tick of the top rail.
     */
    private fun startDrag(f: File, from: View) {
        val set = picked ?: LinkedHashSet<File>().also { picked = it }
        set.add(f)
        if (found != null) return refresh()
        dragged = f
        showRail()
        origin = current
        before = adapter.files.toList()
        reordered = false
        acted = false
        // While things are carried it is the other way round from choosing them: the shelf they
        // are carried over shows in full, and they themselves let it show through. Put down
        // again, they are bright once more and the rest faint.
        (from as? ViewGroup)?.let { for (i in 0 until it.childCount) it.getChildAt(i).alpha = 1f }
        val shadow = from.picture()?.let { InHand(it) } ?: View.DragShadowBuilder(from)
        adapter.moving = true
        // The miniatures of the shelves no longer show what is taken from them.
        Library.carried = set.toSet()
        adapter.choose(set)
        if (!from.startDragAndDrop(null, shadow, null, View.DRAG_FLAG_OPAQUE)) {
            dragged = null
            adapter.moving = false
            Library.carried = emptySet()
            refresh()
            return
        }
        adapter.carried = Library.carried
        adapter.remove(set - f)
    }

    /** What is carried as it is drawn under the finger: its [picture], which lets the shelf show through. */
    private class InHand(private val picture: Bitmap) : View.DragShadowBuilder() {
        private val paint = Paint().apply { alpha = CARRIED_ALPHA }

        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            size.set(picture.width, picture.height)
            touch.set(picture.width / 2, picture.height / 2)
        }

        override fun onDrawShadow(canvas: Canvas) = canvas.drawBitmap(picture, 0f, 0f, paint)
    }

    /** Goes to another shelf, as [go] does, while things are carried, so that they can be put down there. */
    private fun carry(go: () -> Unit) {
        hold(null, null)
        aim(null)
        lastMove = SystemClock.uptimeMillis()
        go()
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
                val all = carried(f)
                val target = into
                if (target != null) {
                    acted = true
                    moveAll(all, target)
                } else if (reordered || current != origin) {
                    acted = true
                    moveAll(all, current)
                    if (reordered) {
                        // All that is carried takes the place the finger was on, in the order it
                        // stood in. The shelf keeps the order it was just given, whatever it was
                        // sorted by before.
                        val group = before.filter { it in all } + all.filter { it !in before }
                        val order = adapter.files.mapTo(ArrayList()) { it.name }
                        val at = order.indexOf(f.name)
                        if (at >= 0) {
                            order.removeAt(at)
                            order.addAll(at, group.map { it.name })
                        }
                        Library.setOrder(this, current, order)
                        Library.setSort(this, Sort.MANUAL)
                    }
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    /** All that is carried: what was chosen, and [f], which the finger is on. */
    private fun carried(f: File): List<File> = chosen() ?: listOf(f)

    /** The shelf this one stands on, or null for the first shelf of the library. */
    private fun outside(): File? = if (current == Library.root()) null else current.parentFile

    /**
     * The top rail: what is dropped on the arrow goes out of this shelf, to the one it stands on.
     * Held over the arrow for a while, it is taken there without being put down.
     */
    private fun onRailDrag(e: DragEvent): Boolean {
        val f = dragged ?: return false
        val out = outside()
        val onArrow = out != null && e.x < up.right + spec().px(10)
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> aim(if (onArrow) up else null)
            DragEvent.ACTION_DRAG_EXITED -> aim(null)
            DragEvent.ACTION_DROP -> if (onArrow) {
                acted = true
                moveAll(carried(f), out)
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    /**
     * The board under the name plate: what is dropped on its star becomes a favorite, what is
     * dropped on its tick is marked as read, and what is dropped on its bin is deleted.
     */
    private fun onBoardDrag(e: DragEvent): Boolean {
        val f = dragged ?: return false
        val over = arrayOf<View>(pickStar, pickRead, bin).firstOrNull { e.x >= it.left && e.x < it.right }
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> aim(over)
            DragEvent.ACTION_DRAG_EXITED -> aim(null)
            DragEvent.ACTION_DROP -> if (over == bin) {
                acted = true
                askDelete(carried(f))
            } else if (over != null) {
                acted = true
                mark(carried(f), favorite = over == pickStar)
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    /** Shows [view], the arrow or a button of the board, as what the carried things would be dropped on; null for none. */
    private fun aim(view: View?) {
        if (view == aimed) return
        aimed?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)
        view?.animate()?.scaleX(1.5f)?.scaleY(1.5f)?.setDuration(120)
        aimed = view
        up.removeCallbacks(leaver)
        if (view == up) up.postDelayed(leaver, OPEN_MS)
    }

    /** The drag is held at ([x], [y]) of the shelf: over the middle of a shelf to go into it, else to take a place. */
    private fun hover(f: File, x: Float, y: Float) {
        // While a shelf opens or closes, nothing stands yet where it will.
        if (ghost.isVisible) return
        val edge = spec().px(72)
        scroll(if (y < edge) -1 else if (y > shelf.height - edge) 1 else 0)
        val child = shelf.findChildViewUnder(x, y)
        val position = if (child == null) RecyclerView.NO_POSITION else shelf.getChildAdapterPosition(child)
        val target = adapter.files.getOrNull(position)
        // The place of what is carried is empty, and nothing can be put into or before itself.
        if (child == null || target == null || target in adapter.carried) {
            hold(null, null)
            return
        }
        val across = (x - child.left) / child.width
        if (target.isDirectory && across in 0.25f..0.75f) {
            hold(target, child)
            return
        }
        hold(null, null)
        val from = adapter.indexOf(f)
        val to = if (across > 0.5f) position.coerceAtLeast(from) else position.coerceAtMost(from)
        val now = SystemClock.uptimeMillis()
        if (from < 0 || to == from || now - lastMove < MOVE_MS) return
        lastMove = now
        reordered = true
        adapter.move(from, to)
    }

    /**
     * Shows [view], the shelf [target], as the one the dragged item would go into; null for none.
     * It opens if the item is held there for a while.
     */
    private fun hold(target: File?, view: View?) {
        if (view == intoView) return
        intoView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)
        view?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(120)
        into = target
        intoView = view
        shelf.removeCallbacks(opener)
        if (target != null) shelf.postDelayed(opener, OPEN_MS)
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
        adapter.moving = false
        adapter.carried = emptySet()
        Library.carried = emptySet()
        hold(null, null)
        scroll(0)
        aim(null)
        // What was dropped somewhere is done with. What was only picked up and put down again
        // stays chosen, so that more can be chosen and carried with it. Either way the shelf is
        // shown as it now is.
        if (acted) stopPicking() else refresh()
    }

    private fun spec() = ShelfSpec(shelf.width, columns, resources.displayMetrics.density)

    // In full colour: with fewer, the soft shading of the back wall comes out in stripes.
    private fun snapshotShelf(): Bitmap? = shelf.picture()

    /** Where a folder's miniature sits on the shelf: the scale and pivot that map the full shelf onto it. */
    private class Miniature(val px: Float, val py: Float, val scale: Float)

    private fun locate(thumbnail: View): Miniature {
        val at = IntArray(2)
        val origin = IntArray(2)
        thumbnail.getLocationInWindow(at)
        shelf.getLocationInWindow(origin)
        // The picture fills its view, and starts where the shelf does: under the top rail.
        val scale = (thumbnail.width.toFloat() / shelf.width).coerceIn(0.05f, 0.95f)
        return Miniature((at[0] - origin[0]) / (1 - scale), (at[1] - origin[1]) / (1 - scale), scale)
    }

    /**
     * Folder transition: a pure change of size. A miniature is its folder's case drawn small, its
     * first rows from under the top rail down to where the books of the last one stand, so the folder's shelf (always the upper, opaque
     * layer) starts exactly on it, cropped to the same rows, and is enlarged until it fills the screen while the crop opens up. The
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
        val cropped = (inset + spec().miniH).coerceAtMost(h)
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
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()),
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    // ---- details, shown by tapping a plaque ----

    /**
     * The plaque of [f] was tapped: it shows the details, or, while things are being chosen, lets
     * the name of [f] be changed, and the cover of a book or the wood of a shelf.
     */
    private fun onPlaque(f: File) = if (picked != null) edit(f) else showInfo(f)

    private fun showInfo(f: File) {
        if (f.isDirectory) {
            val kids = Library.shelf(this, f)
            val folders = kids.count { it.isDirectory }
            // The counts down the left, and where the shelf is on the right, however long that is.
            val rows = ArrayList<Pair<String, String>>()
            rows += getString(R.string.info_books) to "${kids.size - folders}"
            rows += getString(R.string.info_folders) to "$folders"
            rows += getString(R.string.info_location) to place(f)
            ShelfDialog(this).details(rows, byColumn = true).uncrossed().title(f.name).show()
            return
        }
        Library.io.execute {
            val meta = Library.meta(applicationContext, f)
            runOnUiThread { if (!isFinishing && !isDestroyed) showBookInfo(f, meta) }
        }
    }

    private fun edit(f: File) {
        if (f.isDirectory) return editShelf(f)
        val app = applicationContext
        Library.io.execute {
            val meta = Library.meta(app, f)
            runOnUiThread { if (!isFinishing && !isDestroyed) editBook(f, meta) }
        }
    }

    /** The shelf [dir] as it stands on the shelf, beside its name and the wood it is made of, which can be changed there. */
    private fun editShelf(dir: File) {
        val woods = Wood.values()
        val dialog = ShelfDialog(this).uncrossed().title(dir.name)
        val name = dialog.field(R.string.field_name, dir.name)
        dialog.choice(R.string.field_aspect) {
            // Its miniature, in the dialog and behind it, is made of the new wood straight away.
            Library.setWood(this, dir, woods[it])
            showShelfPicture(dialog, dir)
            refresh()
        }.show(woods.map { getString(it.label) }, Library.wood(this, dir).ordinal)
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
            wood = Library.wood(this@MainActivity, dir)
            scaleType = ImageView.ScaleType.FIT_XY
            ratio = (spec.coverH + spec.miniRise).toFloat() / spec.miniW
        }
        val app = applicationContext
        Library.io.execute {
            val picture = Library.folderPreview(app, dir, spec)
            runOnUiThread { dialog.picture.setImageBitmap(picture) }
        }
    }

    /**
     * The book [f], described by [m], by its cover, beside its name, its author and what its
     * cover is: its first page, or a picture it was given, or a new one to be chosen.
     */
    private fun editBook(f: File, m: BookMeta) {
        val dialog = ShelfDialog(this).uncrossed().title(m.title)
        val name = dialog.field(R.string.field_name, m.title)
        val author = dialog.field(R.string.field_author, m.author)
        lateinit var aspect: BrassDropdown
        aspect = dialog.choice(R.string.field_aspect) {
            when (it) {
                0 -> setCover(f, null)
                // The last is always a new picture; one the book has already stays as it is.
                aspect.count - 1 -> {
                    coverFor = f
                    pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
        dialog.picture.apply {
            binding = true
            ratio = ShelfSpec.BOOK
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF24160D.toInt())
        }
        editing = f to { cover, custom ->
            dialog.picture.setImageBitmap(cover)
            val labels = listOfNotNull(R.string.cover_first, if (custom) R.string.cover_own else null, R.string.cover_new)
            aspect.show(labels.map(::getString), if (custom) 1 else 0)
        }
        showCover(f)
        dialog.onClose {
            editing = null
            // The author first, under the name the book has until it is renamed.
            val by = author.text.toString().trim()
            if (by != m.author) Library.setAuthor(this, f, by)
            renamed(f, m.title, name)
            refresh()
        }
        dialog.show()
    }

    /** Renames [f] to what [field] holds, if that is not empty and not still [old]. */
    private fun renamed(f: File, old: String, field: EditText) {
        val typed = field.text.toString().trim()
        if (typed.isNotEmpty() && typed != old) rename(f, typed)
    }

    /** The book a picture is being chosen for, to be its cover. */
    private var coverFor: File? = null

    /** The book whose editor is open, and what shows its cover there and whether it was given one. */
    private var editing: Pair<File, (Bitmap?, Boolean) -> Unit>? = null

    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val book = coverFor ?: return@registerForActivityResult
        coverFor = null
        // None chosen: the editor shows the cover the book has, as it did before.
        if (uri != null) setCover(book, uri) else showCover(book)
    }

    /** Shows the cover of [f] in its editor, if that is open. */
    private fun showCover(f: File) {
        val app = applicationContext
        Library.io.execute {
            val cover = Library.cover(app, f)
            val custom = Library.hasCustomCover(app, f)
            runOnUiThread { editing?.takeIf { it.first == f }?.second?.invoke(cover, custom) }
        }
    }

    /** Gives the book [f] the picture [uri] as its cover, or its own back if that is null. */
    private fun setCover(f: File, uri: Uri?) {
        val app = applicationContext
        Library.io.execute {
            val done = Library.setCover(app, f, uri)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!done) toast(R.string.cannot_read_picture)
                showCover(f)
                refresh()
            }
        }
    }

    private fun rename(f: File, name: String) {
        val renamed = Library.rename(this, f, name)
        if (renamed == null) toast(R.string.name_taken)
        // What is chosen stays chosen under its new name, as does what stands on a shelf renamed.
        else picked?.let { set ->
            val now = set.map { if (it == f || it.path.startsWith(f.path + File.separator)) File(renamed.path + it.path.removePrefix(f.path)) else it }
            set.clear()
            set.addAll(now)
        }
        refresh()
    }

    /** The name a book or a shelf goes by. */
    private fun nameOf(f: File) = if (f.isDirectory) f.name else Library.cachedMeta(this, f)?.title ?: f.nameWithoutExtension

    /** Deleting takes the files off the phone, so it is asked about first. */
    private fun askDelete(things: List<File>) {
        if (things.isEmpty()) return
        val only = things.singleOrNull()
        val warning = when {
            only == null -> resources.getQuantityString(R.plurals.delete_many, things.size, things.size)
            only.isDirectory -> {
                val inside = only.walkTopDown().filter { it != only && !it.name.startsWith(".") }.toList()
                getString(R.string.delete_shelf, only.name, inside.count(Library::isBook), inside.count { it.isDirectory })
            }
            else -> getString(R.string.delete_book, nameOf(only))
        }
        ShelfDialog(this).title(getString(R.string.delete)).message(warning)
            .primary(R.string.delete) {
                // They leave the shelf at once; the files themselves are deleted in the background.
                val gone = things.mapNotNull { Library.delete(this, it) }
                if (gone.size < things.size) toast(R.string.cannot_delete)
                val app = applicationContext
                Library.io.execute { for (f in gone) Library.purge(app, f) }
                stopPicking()
                // What a search found is looked for anew, without what is gone.
                if (found != null) search(query.text.toString())
            }
            .show()
    }

    /** Where [f] is, starting from the library folder rather than from the root of the storage. */
    private fun place(f: File) = f.path.removePrefix(Library.root().parent.orEmpty()).trimStart('/')

    private fun showBookInfo(f: File, m: BookMeta) {
        val st = Library.state(this, f)
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
        rows += getString(R.string.info_author) to m.author.ifBlank { getString(R.string.unknown) }
        rows += getString(R.string.info_published) to published(m.published)
        rows += getString(R.string.info_pages) to getString(R.string.info_pages_value, page, m.pages, (st.percent * 100).roundToInt())
        rows += getString(R.string.info_last_read) to
            if (st.lastRead > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(st.lastRead)) else getString(R.string.never)
        rows += getString(R.string.info_format) to f.extension.uppercase()
        f.parentFile?.let { rows += getString(R.string.info_folder) to place(it) }

        ShelfDialog(this).details(rows).uncrossed().title(m.title).show()
    }

    /** The date [BookMeta.published] gives, with no more of it than is known. */
    private fun published(date: String): String {
        val parts = date.split('-').mapNotNull(String::toIntOrNull)
        if (parts.size < 2) return parts.firstOrNull()?.toString() ?: getString(R.string.unknown)
        val day = Calendar.getInstance().apply {
            clear()
            set(parts[0], parts[1] - 1, parts.getOrElse(2) { 1 })
        }.time
        return if (parts.size > 2) DateFormat.getDateInstance(DateFormat.MEDIUM).format(day)
        else SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMy"), Locale.getDefault())
            .format(day)
    }

    companion object {
        private const val KEY_DIR = "dir"
        private const val KEY_COVER = "cover"
        private const val TYPING_MS = 200L
        private const val ZOOM_MS = 560L
        private const val MOVE_MS = 180L
        private const val OPEN_MS = 700L

        /** How much of what is carried shows over what lies under it, of 255. */
        private const val CARRIED_ALPHA = 120
    }
}
