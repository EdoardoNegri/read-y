package io.github.edoardonegri.ready

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.isNotEmpty
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

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
 * author and the cover of a book, the name of a shelf. Otherwise it shows the details.
 * Beside the arrow of the top rail is the open book that goes on with the book read last.
 * What the plaques open is in ShelfDetails, what the menu adds in ShelfAdding, carrying books and
 * shelves in ShelfDragging, and going into a
 * shelf and out of it in ShelfZoom.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var current: File
    private lateinit var shelf: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var grant: View
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

    /** Going into a shelf and out of it, see ShelfZoom. */
    private lateinit var transition: ShelfZoom

    /** Picking books and shelves up and carrying them, see ShelfDragging. */
    private val dragging: ShelfDragging by lazy {
        ShelfDragging(
            this,
            object : ShelfDragging.Host {
                override val current: File get() = this@MainActivity.current
                override val found: Boolean get() = this@MainActivity.found != null
                override var picked: MutableSet<File>?
                    get() = this@MainActivity.picked
                    set(value) {
                        this@MainActivity.picked = value
                    }

                override fun refresh() = this@MainActivity.refresh()
                override fun showRail() = this@MainActivity.showRail()
                override fun enter(dir: File, from: View?) = this@MainActivity.enter(dir, from)
                override fun goUp() = this@MainActivity.goUp()
                override fun outside(): File? = this@MainActivity.outside()
                override fun moveAll(chosen: List<File>, to: File) = this@MainActivity.moveAll(chosen, to)
                override fun mark(chosen: List<File>, favorite: Boolean) = this@MainActivity.mark(chosen, favorite)
                override fun askDelete(things: List<File>) = this@MainActivity.askDelete(things)
                override fun chosen(): List<File>? = this@MainActivity.chosen()
                override fun stopPicking() = this@MainActivity.stopPicking()
                override fun spec(): ShelfSpec = this@MainActivity.spec()
            },
            shelf, adapter, transition, up, pickStar, pickRead, bin,
        )
    }

    private fun startDrag(f: File, from: View) {
        dragging.start(f, from)
    }

    /** The library folder the reader picked becomes the library, and the first shelf is shown. */
    private val access = FolderAccess(this) { granted ->
        if (granted) current = Library.root() else toast(R.string.cannot_use_folder)
        refresh()
    }

    /** Books and shelves added to the shelf shown, from the menu. */
    private val adding = ShelfAdding(this, { current }, { refresh() }, ::showMenu)

    /** What a tap on a plaque opens, see [onPlaque]. */
    private val details = ShelfDetails(this, ::spec, { refresh() }, ::rename)

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

    /**
     * Holds the back key while a book opens and its reader, started over the shelf, has not yet
     * taken the keys: the shelf would go up a shelf under the opening book, or close altogether.
     */
    private val whileOpening = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (BookOpening.handoff?.waiting == true) return
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // The bookcase covers the whole window, which need not paint its own background under it.
        window.setBackgroundDrawable(null)

        shelf = findViewById(R.id.shelf)
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
        pickRow =findViewById(R.id.pickRow)
        findViewById<View>(R.id.resume).apply {
            visibility = View.VISIBLE
            setOnClickListener { resume() }
        }
        pickRow.clipToOutline = true
        pickRow.setOnDragListener { _, e -> dragging.onBoard(e) }
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

        fitBookcase(findViewById(R.id.root), bar, shelf)

        columns = ShelfSpec.columns(resources)
        shelf.layoutManager = GridLayoutManager(this, columns)
        decoration = ShelfDecoration(this, columns)
        shelf.addItemDecoration(decoration)
        // Leave room for the bookcase's side posts.
        ShelfSpec(0, columns, resources.displayMetrics.density).let {
            shelf.setPadding(it.side, shelf.paddingTop, it.side, shelf.paddingBottom)
        }
        shelf.adapter = adapter
        transition = ShelfZoom(this, shelf, findViewById(R.id.ghost), adapter, ::spec)
        // The case fills the screen whatever stands in it, so a change on the shelves need not
        // measure it anew; and rows scrolled just out of sight are kept as they are, not drawn again.
        shelf.setHasFixedSize(true)
        shelf.setItemViewCacheSize(columns * 2)
        // As the shelf is scrolled, the covers of the screenful still to come that way are
        // loaded, for a fling to find them there.
        shelf.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                val grid = rv.layoutManager as GridLayoutManager
                val first = grid.findFirstVisibleItemPosition()
                val last = grid.findLastVisibleItemPosition()
                if (first < 0) return
                val screen = last - first + 1
                adapter.loadAhead(this@MainActivity, if (dy > 0) last + 1..last + screen else first - screen..<first)
            }
        })
        shelf.setOnDragListener { _, e -> dragging.onShelf(e) }
        bar.setOnDragListener { _, e -> dragging.onRail(e) }

        current = savedInstanceState?.getString(KEY_DIR)?.let(::File) ?: Library.root()
        details.coverFor = savedInstanceState?.getString(KEY_COVER)?.let(::File)
        grant.setOnClickListener { access.request() }
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
        onBackPressedDispatcher.addCallback(this, whileOpening)

        // What was deleted when the app was last closed is gone for good by now.
        if (savedInstanceState == null) applicationContext.let { app -> Library.io.execute { Library.emptyBin(app) } }

        // A fresh start shows the first shelf, whatever was being read when the app was closed:
        // the open book of the top rail goes on with that. Only a book the widget opened leads
        // to the shelf it stands on.
        if (savedInstanceState == null) asked = asked(intent)
    }

    // While a book opens and its reader gets ready unseen over the shelf, the shelf is not tapped:
    // a tap meant for the page would open another book.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        BookOpening.handoff?.waiting == true || super.dispatchTouchEvent(ev)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        asked(intent)?.let { asked = it }
    }

    override fun onResume() {
        super.onResume()
        // The brightness chosen for reading is that of the whole app, see ReaderSettings.light.
        ReaderSettings(this).light(window, mayStayOn = false)
        whileOpening.isEnabled = false
        warmUpReader()
        val book = asked
        asked = null
        if (book == null || !showShelfOf(book)) refresh()
        checkLibrary()
        val left = BookOpening.left
        BookOpening.left = null
        if (left != null && SystemClock.uptimeMillis() - left.at < BookOpening.LEFT_MS) closeBook(left) else opening.clear()
        detached.clear()
        val app = applicationContext
        Library.io.execute { ContinueShortcut.refresh(app) }
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
        if (!book.isDoc || !Library.hasAccess()) return false
        picked = null
        if (searching) closeSearch()
        current = book.parentFile ?: Library.root()
        refresh(quick = false)
        adapter.indexOf(book).let { if (it >= 0) shelf.scrollToPosition(it) }
        return true
    }

    override fun onStop() {
        // The reader has taken over from the page the book was opened to.
        opening.clear()
        detached.clear()
        super.onStop()
    }

    private var reported = false

    /**
     * Tells Android once every place in view shows its cover or miniature, which is when the app is
     * ready to use, for the startup times it keeps. Gives up waiting after a few seconds.
     */
    private fun reportWhenDrawn() {
        if (reported) return
        reported = true
        val until = SystemClock.uptimeMillis() + 10_000
        fun check() {
            if (isDestroyed) return
            val drawn = adapter.itemCount == 0 || (shelf.isNotEmpty() && adapter.allShown(shelf))
            if (drawn || SystemClock.uptimeMillis() >= until) reportFullyDrawn() else shelf.postOnAnimation { check() }
        }
        shelf.post { check() }
    }

    /** The shelf that is being listed in the background for the first time, and whether any shelf has been shown yet. */
    private var listing: File? = null
    private var shown = false

    /** Whether the folder has been asked for since the app opened, so that a turned-down question is not asked again at once. */
    private var askedForFolder = false

    /**
     * The library folder is looked for anew if it was moved or renamed since: the Read_y folder
     * that is on the phone is offered, or a new one is made, like when the app is first opened.
     */
    private fun checkLibrary() {
        if (!Library.hasAccess()) return
        Library.io.execute {
            if (!Store.isLost()) return@execute
            Store.drop()
            onShelf {
                askedForFolder = false
                current = Library.root()
                refresh()
            }
        }
    }

    override fun onPause() {
        Library.backUp(this)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DIR, current.path)
        details.coverFor?.let { outState.putString(KEY_COVER, it.path) }
    }

    /**
     * Shows the shelf being looked at, or what a search found. A shelf whose folder was listed
     * before is shown as it was found then, at once, and listed anew in the background, unless
     * [relist] is false: it is shown again if anything on it changed.
     */
    private fun refresh(relist: Boolean = true, quick: Boolean = true) {
        val root = Library.root()
        if (!Library.hasAccess()) {
            showRail()
            back.isEnabled = false
            up.visibility = View.INVISIBLE
            barTitle.setText(R.string.app_name)
            adapter.submit(emptyList())
            empty.visibility = View.VISIBLE
            grant.visibility = View.VISIBLE
            emptyText.text = getString(R.string.need_access, root.path.removePrefix(Store.storage().path).trimStart('/'))
            // The folder is asked for as the app opens; if the reader turns it down, the button stays.
            if (!askedForFolder) {
                askedForFolder = true
                shelf.post { if (!isFinishing && !Library.hasAccess()) access.request() }
            }
            return
        }
        Store.mkdirs(root)
        // A backup that came with the folder, e.g. from another phone, is taken over before anything is shown.
        Library.adoptBackup(this)
        if (!current.isFolder || !current.path.startsWith(root.path)) current = root
        val atRoot = current == root
        val results = found
        val known = if (results == null) Library.knownShelf(this, current) else null
        // The very first shelf is listed in the background: asking the documents provider is slow, and the
        // window would wait for it before showing anything. [quick] is false where the caller needs the
        // shelf at once, see showShelfOf.
        if (!shown && quick && results == null && known == null && listing != current) {
            listing = current
            val dir = current
            val app = applicationContext
            Library.io.execute {
                Library.shelf(app, dir)
                onShelf { refresh(relist = false) }
            }
            return
        }
        val books = when {
            results != null -> Library.arrange(this, null, results)
            known != null -> known
            else -> Library.shelf(this, current)
        }
        if (known != null && relist) relist(current, known)
        // What is carried has left its places. It is given one empty place on the shelf being
        // shown, for the finger to take to wherever it should stand.
        val held = dragging.dragged
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
        shown = true
        reportWhenDrawn()
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

    /**
     * Lists the folder of the shelf [dir] anew in the background, for what another app may have
     * changed in it since it was shown as [shown], and shows it again if anything did.
     */
    private fun relist(dir: File, shown: List<File>) {
        val app = applicationContext
        Library.io.execute {
            val now = Library.shelf(app, dir)
            Library.main.post {
                if (!isDestroyed && current == dir && found == null && now != shown) refresh(relist = false)
            }
        }
    }

    private fun goUp() {
        val left = current
        val snapshot = transition.snapshot()
        // The miniature it closes into shows no bars of how far its books are read; they go as it shrinks.
        val bare = if (snapshot != null) transition.snapshot(bars = false) else null
        current = current.parentFile ?: Library.root()
        // What is carried out of the shelf is no longer in its miniature, which is drawn anew at
        // once for that: the shelf closes into it.
        if (dragging.dragged != null && shelf.width > 0) Library.folderPreview(this, left, spec())
        refresh()
        val index = adapter.indexOf(left)
        if (snapshot == null || index < 0) return
        // Keep showing the shelf being left until the parent is laid out, its miniature can be
        // found, and its places show their covers.
        transition.cover(snapshot)
        shelf.scrollToPosition(index)
        shelf.doOnPreDraw {
            transition.whenShown {
                val thumbnail = shelf.findViewHolderForAdapterPosition(index)?.itemView?.findViewById<View>(R.id.cover)
                if (thumbnail != null) transition.zoom(transition.locate(thumbnail), snapshot, opening = false, bare) else transition.hide()
            }
        }
    }

    private fun open(f: File, from: View? = null) {
        // While things are chosen a tap chooses more; a shelf is opened with two.
        if (picked != null) return if (f.isFolder) tapShelf(f, from) else pick(f)
        if (f.isFolder) enter(f, from) else read(f, from?.findViewById(R.id.cover))
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

    /**
     * Opens the book [f] in the reader, out of [cover], its cover on the shelf, if that is given;
     * or, to [slide], with its page sliding in from the right, without the book opening.
     */
    private fun read(f: File, cover: View?, slide: Boolean = false) {
        // The book is read in the background meanwhile, rather than by the reader as it starts.
        BookPrep.prepare(f)
        val reader = Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, f.path)
        if (cover == null && !slide) return startActivity(reader)
        // The book opens on the shelf, and the reader takes over once its page fills the screen.
        reader.putExtra(ReaderActivity.EXTRA_FROM_SHELF, true).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val settings = ReaderSettings(this)
        val paper = settings.lit(settings.palette.bg)
        // A book read to its end opens on its cover, so it is only enlarged out of its place: that
        // cover is its last page, and stands against the wall of the case.
        val grown = !slide && !settings.vertical && Library.isFinished(this, f)
        if (Build.VERSION.SDK_INT >= 30) {
            // The reader starts at once, unseen over the shelf, and sets out the page while the
            // book opens; see BookOpening.Handoff.
            val handoff = BookOpening.Handoff()
            BookOpening.handoff = handoff
            whileOpening.isEnabled = true
            startActivity(reader.setClass(this, OpeningReaderActivity::class.java))
            // It opens in a window of its own, which the reader starting meanwhile does not hold up.
            // Where the menu stands over the cover is known from the last time the book was shown there.
            if (cover == null) return detached.slide(paper, f, settings.look, handoff)
            detached.open(cover, paper, f, settings.look, handoff, grown, if (grown) Library.endMenu(this, f) else null)
            if (grown) sharpen(f, detached::sharpen)
            return
        }
        if (cover == null) return opening.slideIn(paper, BookOpening.kept(f, settings.look)) { startActivity(reader) }
        opening.open(cover, paper, if (grown) null else BookOpening.kept(f, settings.look), grown) { startActivity(reader) }
        if (grown) sharpen(f) { runOnUiThread { opening.sharpen(it) } }
    }

    /**
     * Reads the cover of [f] as large as it grows when it is only enlarged out of the shelf, in the
     * background, and gives it to [then]: the one the shelf keeps is only as large as the shelf shows it.
     */
    private fun sharpen(f: File, then: (Bitmap) -> Unit) {
        val app = applicationContext
        val width = resources.displayMetrics.widthPixels
        Library.io.execute {
            val sharp = try {
                Library.bigCover(app, f, width).uploaded()
            } catch (e: Throwable) {
                // Not even the cover the shelf keeps can be read, or not as large: the book grows with that one.
                return@execute
            }
            then(sharp)
        }
    }

    /**
     * Goes on with the book that was read last, wherever in the library it stands: its page slides
     * in from the right. It is closed on its own shelf all the same, see closeBook.
     */
    private fun resume() {
        val app = applicationContext
        Library.io.execute {
            val last = Library.lastRead(app)
            onShelf {
                if (last == null) return@onShelf toast(R.string.nothing_read)
                read(last, null, slide = true)
            }
        }
    }

    /**
     * Gets ready, while nothing else happens on the shelf, the web view that lays out the next
     * book of flowing text to be opened (FlowEngine.prepare), which is slow to make. It waits a few
     * seconds first, so as not to take the phone's time from the covers as the shelf appears.
     */
    private fun warmUpReader() {
        shelf.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            Looper.myQueue().addIdleHandler {
                if (!isFinishing && !isDestroyed) FlowEngine.prepare(this)
                false
            }
        }, WARM_READER_MS)
    }

    /** Opens a book on the shelf before the reader shows it, and closes it again afterwards. */
    private val opening by lazy { BookOpening(this) }

    /** Opens a book while the reader starts behind it, see [read]. */
    private val detached by lazy { BookOpening.Detached(this) }

    /** The reader came back with [left], the page its book was at: the book closes from it, where it stands. */
    private fun closeBook(left: BookOpening.Left) {
        // Opened from elsewhere than its place in view, such as the button that goes on with the
        // last book read, it goes back to the shelf it stands on all the same.
        val at = adapter.indexOf(left.file)
        if (at < 0) showShelfOf(left.file) else shelf.scrollToPosition(at)
        val settings = ReaderSettings(this)
        // Left on its cover, at its end, a book only shrinks back into its place.
        opening.close(left.page, settings.lit(settings.palette.bg), grown = !settings.vertical && Library.isFinished(this, left.file), left.menu) {
            shelf.findViewHolderForAdapterPosition(adapter.indexOf(left.file))?.itemView?.findViewById(R.id.cover)
        }
    }

    /** Shows the shelf [dir], which grows out of its miniature in the place [from], if that is given. */
    private fun enter(dir: File, from: View?) {
        val miniature = from?.findViewById<View>(R.id.cover)?.let(transition::locate)
        val snapshot = if (miniature != null) transition.snapshot() else null
        current = dir
        refresh()
        shelf.scrollToPosition(0)
        // Start only once the folder's books are laid out and show their covers, so no frame shows
        // the old shelf shrunk or an empty place; the shelf being left stays in view until then.
        if (miniature != null && snapshot != null) {
            transition.cover(snapshot)
            shelf.doOnPreDraw { transition.whenShown { transition.zoom(miniature, snapshot, opening = true) } }
        }
    }

    private fun shelfName(f: File): String = if (f == Library.root()) getString(R.string.app_name) else f.name

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** Does [action] on the thread that draws the shelf, unless the shelf is gone by then. */
    private fun onShelf(action: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) action() }

    // ---- menu: adding books and shelves, sorting, settings ----

    private fun showMenu() {
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (Library.hasAccess()) {
            choices += getString(R.string.add_books) to adding::addBooks
            choices += getString(R.string.add_shelf) to adding::askAddShelf
        }
        choices += getString(R.string.sort_by) to ::askSort
        choices += getString(R.string.settings) to { settings.launch(Intent(this, SettingsActivity::class.java)) }
        choices += getString(R.string.about) to { AboutDialog(this, ::showMenu).show() }
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
        Library.chosen = if (dragging.dragged == null) picked?.toSet().orEmpty() else emptySet()
        val books = picked?.filter { it.isDoc } ?: return
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
        val books = chosen.filter { it.isDoc }
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

    private fun askSort() {
        val sorts = Sort.entries
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
        searchListed = false
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
        val listed = searchListed
        Library.io.execute {
            // Answered at once from the folders as they were last listed; the first search after the
            // search bar opens looks through them anew too, for what other apps have changed.
            val known = Library.search(applicationContext, dir, text, favorites, fresh = false)
            runOnUiThread { if (run == searches && !isDestroyed) show(known) }
            if (listed) return@execute
            val hits = Library.search(applicationContext, dir, text, favorites, fresh = true)
            runOnUiThread {
                if (run == searches && !isDestroyed) {
                    searchListed = true
                    if (hits != found) show(hits)
                }
            }
        }
    }

    /** Shows [hits] as what the search found. */
    private fun show(hits: List<File>) {
        found = hits
        refresh()
    }

    /** Whether the library has been listed anew since the search bar opened, see [search]. */
    private var searchListed = false

    /** The shelf this one stands on, or null for the first shelf of the library. */
    private fun outside(): File? = if (current == Library.root()) null else current.parentFile

    private fun spec() = ShelfSpec(shelf.width, columns, resources.displayMetrics.density)

    // ---- details, shown by tapping a plaque ----

    /**
     * The plaque of [f] was tapped: it shows the details, or, while things are being chosen, lets
     * the name of [f] be changed, and the cover of a book.
     */
    private fun onPlaque(f: File) = if (picked != null) details.edit(f) else details.show(f)

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
    private fun nameOf(f: File) = if (f.isFolder) f.name else Library.cachedMeta(this, f)?.title ?: f.nameWithoutExtension

    /** Deleting takes the files off the phone, so it is asked about first. */
    private fun askDelete(things: List<File>) {
        if (things.isEmpty()) return
        val only = things.singleOrNull()
        val warning = when {
            only == null -> resources.getQuantityString(R.plurals.delete_many, things.size, things.size)
            only.isFolder -> {
                val inside = Store.walk(only, fresh = false).filter { it != only && !it.name.startsWith(".") }.toList()
                getString(R.string.delete_shelf, only.name, inside.count(Library::isBook), inside.count { it.isFolder })
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

    companion object {
        private const val KEY_DIR = "dir"
        private const val KEY_COVER = "cover"
        private const val TYPING_MS = 200L

        /** How long the web view of the reader is left alone after the shelf is shown, see warmUpReader. */
        private const val WARM_READER_MS = 2500L
    }
}
