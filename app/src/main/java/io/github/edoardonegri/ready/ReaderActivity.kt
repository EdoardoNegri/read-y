package io.github.edoardonegri.ready

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

open class ReaderActivity : AppCompatActivity(), ReaderHost, GestureFrame.Listener {

    private lateinit var s: ReaderSettings
    private lateinit var file: File
    private var state = BookState()
    private var engine: ReaderEngine? = null

    private lateinit var root: FrameLayout
    private lateinit var content: GestureFrame
    private lateinit var menu: View

    /** The plank of the [menu], which slides up from the foot of the screen as it opens, and back down as it closes. */
    private lateinit var plank: ViewGroup

    /** The margins each row of the [plank] has of its own, before any is kept clear of the camera; see [clearCamera]. */
    private val rowMargins = HashMap<View, Pair<Int, Int>>()

    /** The crease along the spine of the cover that ends the book, see [coverRoom]. */
    private lateinit var crease: CoverCrease

    /** The name of the book, on the menu. */
    private lateinit var title: TextView

    /** The back wall of a case, which the cover that ends the book stands against. */
    private val wall: Drawable by lazy { ShelfArt.wall(this) }
    private lateinit var pageTurn: PageTurnView
    private lateinit var info: PageInfoView
    private lateinit var turner: PageTurner
    private lateinit var pace: ReadingPace

    /** What the phone's camera takes from the screen, which the text keeps clear of. */
    private var cutout = Cutout()
    private lateinit var inChapter: View
    private lateinit var inBook: View
    private lateinit var percentRead: TextView
    private lateinit var chapterPage: TextView
    private lateinit var bookPage: TextView
    private lateinit var chapterName: TextView
    private lateinit var seek: SeekBar
    private lateinit var chapters: ChapterMarks
    private var seeking = false

    /** The settings screen was opened from here, so they may have changed by the time it is left. */
    private var settingsOpened = false

    /** The settings, which lead back to the menu if they are left by their arrow. */
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) openMenu()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = ReaderSettings(this)
        setContentView(R.layout.activity_reader)
        // The page covers the whole window, so the window need not paint anything under it.
        window.setBackgroundDrawable(null)
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        menu = findViewById(R.id.menu)
        plank = findViewById(R.id.menuPlank)
        crease = findViewById(R.id.crease)
        pageTurn = findViewById(R.id.pageTurn)
        info = findViewById(R.id.pageInfo)
        turner = PageTurner(window, s, content, pageTurn, info) { engine }
        inChapter = findViewById(R.id.inChapter)
        inBook = findViewById(R.id.inBook)
        // Come from the shelf, the book was opened there, and is closed there when it is left.
        fromShelf = intent.getBooleanExtra(EXTRA_FROM_SHELF, false)
        // Started behind the book opening on the shelf, the reader is not seen until the book is open.
        val handoff = if (behindOpening) BookOpening.handoff else null
        this.handoff = handoff
        if (handoff != null) {
            hidden = true
            window.attributes = window.attributes.apply { alpha = 0f }
            // Touches meanwhile are for the book opening under it, not for pages nobody sees.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
        // Read sideways, a reader behind a book opening on the upright shelf turns the screen only
        // once the book is open (see reveal): meanwhile it sets out its page on its side, as it will
        // be seen then, and shows it turned a quarter, as it will be read once the phone is turned
        // so. It fills the upright window that way, to be copied from the screen for the book
        // opening on the shelf (see shown). Not a book read to its end, which opens on the cover that
        // ends it, upright however the book is read (see orient), as the shelf grows it.
        val asked = File(intent.getStringExtra(EXTRA_PATH) ?: "")
        // Opened on the upright shelf, a book read sideways is turned the way it was last read, as
        // the shelf showed its page, else the usual way (SIDEWAYS); see orient.
        if (fromShelf && s.orientation == ReaderSettings.LANDSCAPE) {
            turnTo = BookOpening.keptTurned(asked, s.look).takeIf { it != 0f } ?: SIDEWAYS
        }
        turnLater = handoff != null && Build.VERSION.SDK_INT >= 30 && s.orientation == ReaderSettings.LANDSCAPE &&
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE &&
            !(!s.vertical && Library.isFinished(this, asked))
        if (turnLater && Build.VERSION.SDK_INT >= 30) {
            val screen = windowManager.currentWindowMetrics.bounds
            val long = max(screen.width(), screen.height())
            uprightWidth = min(screen.width(), screen.height())
            uprightHeight = long
            if (turnTo == 0f) turnTo = SIDEWAYS
            root.layoutParams = root.layoutParams.apply {
                width = long
                height = uprightWidth
            }
            root.rotation = turnTo
            root.translationX = (uprightWidth - long) / 2f
            root.translationY = (long - uprightWidth) / 2f
        }
        // Read sideways, the screen turns between the upright shelf and the reader with the page
        // filling it as it looks either way: it is not spun round, but just shown turned.
        if (fromShelf && s.orientation == ReaderSettings.LANDSCAPE) jumpCut()
        fromWidget = intent.getBooleanExtra(EXTRA_FROM_WIDGET, false)
        if (fromShelf && Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        if (fromWidget) {
            // The reader takes the bookshelf's task over from the widget's own, so the system
            // switches tasks and may play its transition for that whatever the intent flags say;
            // the opened book is already there to take over from without one.
            window.setWindowAnimations(0)
            appearAtOnce()
        }
        percentRead = findViewById(R.id.percent)
        chapterPage = findViewById(R.id.chapterPage)
        bookPage = findViewById(R.id.bookPage)
        chapterName = findViewById(R.id.chapterName)
        seek = findViewById(R.id.seek)
        chapters = findViewById(R.id.chapters)

        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Draw behind the (hidden) system bars but keep text clear of a notch or camera hole.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // Set out on its side while the window is upright, the page keeps clear of the camera where it will be then.
            cutout = Cutout.of(insets).let { if (turnLater) it.turned(uprightWidth, uprightHeight, left = turnTo > 0f) else it }
            // The wood of the menu runs from side to side, under a camera at either; see clearCamera.
            cutout.insets.let { menu.setPadding(0, it.top, 0, it.bottom) }
            clearCamera()
            arrangeInfo()
            insets
        }
        // Turned, the page has another shape, and what is around the text other places.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                // The bars coming and going change the height a little; only turned over is another shape.
                if ((r - l > b - t) != (or - ol > ob - ot)) turned()
                arrangeInfo()
                if (r - l < b - t) shutTurned()
            }
        }
        // A row of the plank that comes to another height may come to be beside the camera, or not.
        plank.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (t != ot || b != ob) clearCamera() }
        immersive()

        file = File(intent.getStringExtra(EXTRA_PATH) ?: "")
        if (!file.isDoc) {
            Toast.makeText(this, R.string.book_missing, Toast.LENGTH_LONG).show()
            leave()
            return
        }
        state = Library.state(this, file)
        finished = state.percent >= Library.FINISHED_AT
        pace = ReadingPace(this, ReadingPace.kindOf(file))
        val opened = try {
            if (Library.isFlowing(file)) FlowEngine(this, file, s, state, this)
            else PageEngine(this, file, s, state, this)
        } catch (e: Exception) {
            unreadable()
            return
        }
        engine = opened
        content.addView(opened.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.listener = this

        title = findViewById(R.id.bookTitle)
        // On a plaque as the book has it on the shelf: of bronze once it is read to the end, with stars for a favorite.
        title.background = PlaqueDrawable(resources.displayMetrics.density).apply {
            favorite = Library.isFavorite(this@ReaderActivity, file)
            finished = this@ReaderActivity.finished
        }
        val known = Library.cachedMeta(this, file)
        title.text = known?.title ?: file.nameWithoutExtension
        bookPages = known?.pages ?: 0
        author = known?.author.orEmpty().trim()
        // A book opened before the shelf has looked at it, e.g. one just handed over by another app.
        if (known == null) Library.io.execute {
            val meta = Library.meta(applicationContext, file)
            runOnUiThread {
                title.text = meta.title
                bookPages = meta.pages
                author = meta.author.trim()
            }
        }
        findViewById<ImageButton>(R.id.back).setOnClickListener { leave() }
        setUpChapters(opened)
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            settingsOpened = true
            hideMenu()
            settings.launch(Intent(this, SettingsActivity::class.java))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                // While the knob is dragged, the chapter it is over is named, the lens shows where
                // it is, and the figures under the line say where it would open.
                val e = engine ?: return
                if (!fromUser) return
                // The line is let go on a page, never between two: the figures and the knob change as the page does.
                val at = showPlace(e, progress / 1000f)
                dragAt = at
                val snapped = (at * 1000).roundToInt()
                if (snapped != progress) bar.progress = snapped
                val mark = chapters.marks.indexOfLast { it <= at + 0.0005f }
                // The cover that ends the book is in no chapter: it names whoever wrote the book, if that is known.
                if (e.hasCover && at >= 1f) name(author, other = true)
                else if (mark >= 0) name(chapterName(e, mark), other = true)
                chapters.seeking = at
            }
            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
                dragAt = -1f
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                // The line stays as it was left until the book has been taken there, see onPosition.
                seekTarget = if (dragAt >= 0) dragAt else bar.progress / 1000f
                dragAt = -1f
                // The menu stays if it was the knob that took the book from its cover.
                keepMenu = onCover
                content.removeCallbacks(seekSettled)
                content.postDelayed(seekSettled, SEEK_WAIT_MS)
                engine?.seekTo(seekTarget)
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // The menu stays over the cover that ends the book, which Back then leaves.
                if (menuOpen && !onCover) closeMenu() else leave()
            }
        })

        applyBrightness()
        applyLayout()
        // Once the page is set out and drawn, unseen, the book opens on the shelf onto a picture of
        // it (see shown), and the reader shows itself when the book is open; not later than this
        // all the same, should the page never be set out.
        if (handoff != null) root.postDelayed({ pictured(null) }, SHOW_WAIT_MS)
        // Opening on its cover, a book has the menu picture ready for the cover that grows on the shelf.
        if (handoff != null && finished && opened.hasCover) pictureEndMenu(handoff, opened)
        // Opened on the upright shelf onto the page it was left at, a book read sideways shows that
        // page, as it was read, while the screen turns and until its own is set out. Opened on its
        // cover, it stays upright.
        val kept = if (fromShelf && handoff == null && s.orientation == ReaderSettings.LANDSCAPE && !(finished && opened.hasCover)) BookOpening.kept(file, s.look) else null
        if (kept != null) showSidewaysPage(kept, BookOpening.keptTurned(file, s.look))
        if (!s.hintShown) {
            s.hintShown = true
            Toast.makeText(this, R.string.reader_hint, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        // The place in the book is written down when the reader leaves, however briefly, and the
        // shortcut on the app's icon goes on with this book from then on.
        savePosition()
        val app = applicationContext
        Library.io.execute { ContinueShortcut.refresh(app) }
        Library.backUp(this)
        turner.stop()
        pageTurn.release()
        engine?.pause()
        if (::pace.isInitialized) pace.pause()
        clock.stop()
        // The settings opened from the reader keep the phone as quiet as the book did.
        if (!settingsOpened) DoNotDisturb.stop(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        engine?.resume()
        clock.start(s)
        if (s.doNotDisturb) DoNotDisturb.start(this) else DoNotDisturb.stop(this)
        if (settingsOpened) {
            settingsOpened = false
            applyBrightness()
            applyLayout()
        }
        // Whatever was done meanwhile, the menu is shown over the cover that ends the book.
        if (onCover) showMenu()
    }

    /** The lines of the contents of the book that have a mark of their own on the seek line. */
    private var marked: List<Int> = emptyList()

    /**
     * Marks the chapters along the seek line. Lines of the contents that lead to the same place,
     * such as the sections of one chapter, share the mark of the first.
     */
    private fun setUpChapters(e: ReaderEngine) {
        val places = e.contentPositions
        marked = places.indices.filter { it == 0 || places[it] - places[it - 1] > 0.0005f }
        chapters.visibility = if (marked.size > 1) View.VISIBLE else View.GONE
        chapterName.visibility = chapters.visibility
        chapters.inset = seek.paddingLeft
        // The line runs through the middle of the seek bar.
        chapters.lineBelow = seek.layoutParams.height / 2f
        chapters.marks = marked.map { places[it] }
        chapters.hasEnd = e.hasCover
        chapters.onPick = { mark -> e.openContent(marked[mark]) }
        // The name of the chapter a finger is on takes the place of the one being read until it is let go.
        chapters.onPreview = { mark -> if (mark < 0) name(reading, other = false) else name(chapterName(e, mark), other = true) }
    }

    /**
     * Shows how much of the book lies before the place [at], 0..1, and the pages it is on, while
     * the knob of the seek bar is dragged there; [onPosition] shows the place reached once it is let go.
     */
    private fun showPlace(e: ReaderEngine, at: Float): Float {
        val (ofChapter, ofBook) = e.pagesAt(at)
        if (e.hasCover) {
            // The place of the page nearest, which is told as the pages before it make of all of them; after the last it is the cover.
            val placed = e.snap(at)
            // The cover is no page of a chapter: its place is left empty while the knob is over it, as when it is shown.
            showFigures(ofChapter, ofBook, ended = placed >= 1f, placed)
            return placed
        }
        if (ofChapter != null) chapterPage.showText(pageOf(ofChapter))
        val book = ofBook ?: bookPages.takeIf { it > 0 }?.let { (1 + (at * (it - 1)).roundToInt()).coerceIn(1, it) to it }
        if (book != null) bookPage.showText(pageOf(book))
        // Scrolled, where the page begins, as the first page is 0 and the last 100.
        val placed = book?.takeIf { it.second > 1 }?.let { (it.first - 1f) / (it.second - 1) } ?: at
        percentRead.showText(getString(R.string.percent_read, (placed * 100).roundToInt()))
        return placed
    }

    /**
     * Writes under the seek line the page [ofChapter] of the chapter and [ofBook] of the book, or the
     * end of the book if it has [ended], and how much of it is read, [percent]. What is not known, or
     * does not apply, leaves its place empty, so that the rest stays put.
     */
    private fun showFigures(ofChapter: Pair<Int, Int>?, ofBook: Pair<Int, Int>?, ended: Boolean, percent: Float) {
        inChapter.visibility = if (!ended && ofChapter != null) View.VISIBLE else View.INVISIBLE
        if (ofChapter != null) chapterPage.showText(pageOf(ofChapter))
        inBook.visibility = if (ended || ofBook != null) View.VISIBLE else View.INVISIBLE
        if (ended) bookPage.showText(getString(R.string.the_end))
        else if (ofBook != null) bookPage.showText(pageOf(ofBook))
        percentRead.showText(getString(R.string.percent_read, (percent * 100).roundToInt()))
    }

    /**
     * Writes [text] here unless it is written so already. Given text, the same or not, a TextView as
     * wide as its text has the menu laid out anew, which would be done for every step of the knob
     * of the seek bar dragged, though the page it is over changes far less often.
     */
    private fun TextView.showText(text: CharSequence) {
        if (!TextUtils.equals(this.text, text)) this.text = text
    }

    /** Page [page].first of [page].second. */
    private fun pageOf(page: Pair<Int, Int>) = getString(R.string.page_of, page.first, page.second)

    /** The place of the page the knob of the seek bar is on while it is dragged, 0..1; else -1. */
    private var dragAt = -1f

    /**
     * Whether the cover that ends the book is shown: the menu is over the foot of the screen then,
     * and cannot be taken away, and the cover is in the room above it. Whether it was the knob of
     * the seek bar that left it, which keeps the menu.
     */
    private var onCover = false
    private var keepMenu = false

    private fun coverChanged(on: Boolean) {
        onCover = on
        // Before the book shuts over its last page: the screen is as quick as the phone makes it for that.
        paceScreen()
        paintBackground()
        if (!on) crease.box = null
        if (!on) turnedLeft = null
        val e = engine
        if (e == null) return orient()
        if (on) {
            behindMenu = null
            // The menu comes at once, as it does for a tap on the page. Turned to from the last page,
            // the book shuts over that page, which stays in front meanwhile, see shut. Unseen, as when
            // the book opens on its cover, the menu is only set out, to be pictured for the opening,
            // see pictureEndMenu. Taken there with the menu open, by the knob of the seek bar, no
            // page was turned.
            val left = if (!hidden && !menuOpen) turner.takeTurn() else null
            showMenu()
            // Seen sideways, the screen turns upright for the cover, which stands as it does on the
            // shelf: the page left stays in front meanwhile, turned as it was seen, and once the
            // screen is upright the book shuts over it as it does read upright (see shutTurned).
            if (root.width > root.height) {
                val degrees = uprightTurn(root.display)
                val page = left?.let { turn(it, degrees) }
                if (page != null) {
                    turnedLeft = page
                    jumpCut()
                    showSidewaysPage(page, degrees)
                }
                turner.stop()
                keepMenu = false
                return orient()
            }
            orient()
            menu.doOnLayout { m ->
                if (!onCover) {
                    if (left != null) turner.stop()
                    return@doOnLayout
                }
                coverRoom(e, MenuPictures.room(m))
                if (left != null) shut(left)
            }
        } else {
            // Read sideways, the screen turns back on its side for the page: the cover stays in front
            // meanwhile, as it was seen, rather than the page being turned over it upright.
            if (s.orientation == ReaderSettings.LANDSCAPE && root.width < root.height) {
                turner.takeTurn(back = true)?.let {
                    jumpCut()
                    showSidewaysPage(it, null)
                    turner.stop()
                }
            }
            orient()
            // The menu goes, even if the knob of the seek bar brought the book to its cover with the menu
            // open, unless it is the knob that leaves the cover: once the page turned to from the cover is
            // seen, the menu saying where it is meanwhile, over the picture of the cover being turned away,
            // menu and all.
            coverRoom(e, 0)
            if (!keepMenu) {
                val asked = menuAsked
                turner.afterTurn { if (!onCover && menuAsked == asked) hideMenu(slide = true) }
            }
        }
        keepMenu = false
    }

    /**
     * Puts the cover that ends the book where it stands with [menu] pixels of the menu over the
     * foot of the screen (ShelfArt.endCover), where the book opening on the shelf puts it too.
     */
    private fun coverRoom(e: ReaderEngine, menu: Int) {
        ShelfArt.endCover(root.width, root.height, cutout.insets.top, menu, resources.displayMetrics.density, room)
        // With the crease along its spine, as on the shelf.
        crease.box = if (onCover) room else null
        // In the engine's own view, which lies inside the margins of the page.
        room.offset(-content.paddingLeft, -content.paddingTop)
        e.coverAt(room)
    }

    private val room = Rect()

    /**
     * The cover that ends the book, as large as the screen is wide, read once the end of the book is
     * near, for the book to [shut] with; null until then, or if it cannot be read.
     */
    private var endPicture: Bitmap? = null
    private var endPictureAsked = false

    /**
     * Near the end of the book, [percent] of it, reads the cover that ends it, for the book to shut
     * over its last page. Not for a book that opens on that cover, while it opens on the shelf,
     * unseen: the shelf reads the cover too, and the two would hold each other up.
     */
    private fun readEndPictureIfNear(percent: Float) {
        if (!endPictureAsked && (!hidden || percent < 1f) && percent >= END_NEAR && engine?.hasCover == true) readEndPicture()
    }

    /** Reads the cover that ends the book in the background, see [endPicture]. */
    private fun readEndPicture() {
        endPictureAsked = true
        val app = applicationContext
        val width = resources.displayMetrics.widthPixels
        Library.io.execute {
            val picture = try {
                Library.bigCover(app, file, width).uploaded(cover = true)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread { if (!isDestroyed) endPicture = picture }
        }
    }

    /** What the opening and the closing of the book on the shelf are given of the menu, see MenuPictures. */
    private val pictures by lazy { MenuPictures(this, root, menu, title, file) }

    /**
     * What shuts the book over its last page, as the shelf closes books, under the menu, which says
     * meanwhile that the book is at its end; and whether it does now.
     */
    private val shutting by lazy {
        BookOpening(this) {
            it.visibility = View.GONE
            (root as ViewGroup).addView(it, (root as ViewGroup).indexOfChild(menu), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
    private var shuttingNow = false

    /**
     * Shuts the book over [left], its last page as it was seen before the cover that ends it came
     * under it: the page shrinks into the cover where it stands on the menu, which swings shut over
     * it, as the book closes on the shelf. Without the picture of the cover, the page is turned like
     * paper instead.
     */
    private fun shut(left: Bitmap) {
        val box = crease.box
        val picture = endPicture
        if (box == null || box.isEmpty || picture == null) return turner.turnTaken()
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        // The cover fills its place cut to its shape about its middle, as the engine shows it.
        val scale = maxOf(box.width().toFloat() / picture.width, box.height().toFloat() / picture.height)
        val fit = Matrix().apply {
            setScale(scale, scale)
            postTranslate((box.width() - picture.width * scale) / 2, (box.height() - picture.height * scale) / 2)
        }
        val spot = BookOpening.Spot(at[0] + box.left, at[1] + box.top, box.width(), box.height(), picture, fit, binding = true)
        shuttingNow = true
        // Two pages side by side shut as an open book does, the left one over the right.
        shutting.shut(spot, left, s.litPalette.bg, spread = s.spread && root.width > root.height) {
            shuttingNow = false
            paceScreen()
        }
        // The layer of the book takes the place of the page in front, in the same frame.
        turner.stop()
    }

    /**
     * The last page as it was seen sideways before the cover that ends the book came under it,
     * turned upright, for the book to shut over once the screen has turned upright for the cover; see coverChanged.
     */
    private var turnedLeft: Bitmap? = null

    /** The screen is upright: the book shuts over the page left sideways, if any, which stood in front meanwhile. */
    private fun shutTurned() {
        val page = turnedLeft ?: return
        turnedLeft = null
        if (onCover) shut(page)
        dropSidewaysPage()
    }

    /** The screen turns from upright to sideways or back at once, with the page standing in front as it was seen: it is not spun round. */
    private fun jumpCut() {
        window.attributes = window.attributes.apply { rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT }
    }

    /** [page] turned [degrees]; null if there is no room for that. */
    private fun turn(page: Bitmap, degrees: Float) = try {
        Bitmap.createBitmap(page, 0, 0, page.width, page.height, Matrix().apply { setRotate(degrees) }, true)
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * A book that opens on its cover has its menu set out at once, as it is over the cover, 100% read
     * and at the end, and pictured for the book opening on the shelf, which raises it as the cover grows.
     */
    private fun pictureEndMenu(h: BookOpening.Handoff, e: ReaderEngine) {
        showPosition(state.chapter, 1f, e is PageEngine, null, null)
        // The shelf is upright: a menu laid out sideways, as the reader is turned, is none for it.
        menu.doOnLayout { h.menuPictured(if (root.width < root.height) pictures.plank()?.let(pictures::endMenu) else null) }
    }

    /** Which [ReaderEngine.positionsVersion] the marks of the chapters along the seek bar were made for. */
    private var positionsSeen = -1

    /** Shows [text] as the name of a chapter, brighter if it is [other] than the one being read. */
    private fun name(text: String, other: Boolean) {
        chapterName.showText(text)
        chapterName.setTextColor(ContextCompat.getColor(this, if (other) R.color.gold_light else R.color.cream))
    }

    /** The name of the chapter with the mark [mark]. */
    private fun chapterName(e: ReaderEngine, mark: Int) = e.contents[marked[mark]].first.trim()

    /** The pages the whole book has, for an EPUB about as many as a printed one would; 0 until known. See [onPosition]. */
    private var bookPages = 0

    /**
     * The name of the chapter being read, to go back to after that of another was shown instead; on
     * the cover that ends the book, which is in no chapter, the [author] instead.
     */
    private var reading = ""

    /** Who wrote the book, as far as it is known; empty if it is not. */
    private var author = ""

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }

    /**
     * Whether this reader is started behind the book opening on the shelf, see
     * [BookOpening.Handoff]; it is then see-through, and unseen, until it is [reveal]ed.
     */
    protected open val behindOpening get() = false

    /** Whether the reader is still waiting, unseen, for its book to open on the shelf. */
    private var hidden = false

    /** The book opening on the shelf that the reader waits for, unseen, until it has pictured its page for it. */
    private var handoff: BookOpening.Handoff? = null

    /** Whether the reader is to turn the screen on its side once its book is open on the upright shelf; see onCreate. */
    private var turnLater = false

    /** How wide and how high the screen is upright, which the page is set out on its side for while [turnLater]. */
    private var uprightWidth = 0
    private var uprightHeight = 0

    /**
     * How far the screen is to be turned for a book read sideways opened on the upright shelf, in
     * degrees, as the page left was turned to stand upright there (see upright): a quarter either
     * way. The screen is turned that way, rather than the way the phone happens to choose, until it
     * is on its side ([onConfigurationChanged]), and follows the phone from then on; 0 then, or for
     * a book that is not read sideways.
     */
    private var turnTo = 0f

    /** Whether the engine has shown its first page (ReaderHost.shown). */
    private var pageShown = false

    /** The page as it was copied from the screen, turned a quarter ([turnTo]), while [turnLater]; see shown. */
    private var turnedPrint: Bitmap? = null

    /** The page a book read sideways was left at, shown until the reader has set out its own; see [SidewaysPage]. */
    private var sidewaysPage: SidewaysPage? = null

    /**
     * Shows [kept], the page the book opened on on the upright shelf, turned [turned] degrees to stand
     * upright there, as it was read sideways, over the whole window while the screen turns and until
     * the reader's own page is seen; or, without one, the paper the book opened on. Null [turned]
     * for a picture seen upright, which stays as it was seen however the screen turns (SidewaysPage).
     */
    private fun showSidewaysPage(kept: Bitmap?, turned: Float?) {
        val page = SidewaysPage(this, kept, turned, s.lit(s.palette.bg))
        sidewaysPage = page
        addContentView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.postDelayed(::dropSidewaysPage, SHOW_WAIT_MS)
    }

    /** Takes [sidewaysPage] away, once the reader's own page is on the screen under it: two frames on. */
    private fun dropSidewaysPage() {
        val page = sidewaysPage ?: return
        sidewaysPage = null
        twoFramesOn { (page.parent as? ViewGroup)?.removeView(page) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        // Turned the way the book was last read, the screen follows the phone from now on.
        if (turnTo != 0f) {
            turnTo = 0f
            orient()
        }
        // On its side at last, the reader is as large as its window again: as large as it was set out
        // for already, unseen, so that its page is there to be seen at once.
        val lp = root.layoutParams
        if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            root.layoutParams = lp.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            root.rotation = 0f
            root.translationX = 0f
            root.translationY = 0f
        }
        if (pageShown) dropSidewaysPage()
    }

    override fun shown() {
        pageShown = true
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) dropSidewaysPage()
        if (handoff == null || !hidden) return
        // Two frames on, the page is on the screen to be copied.
        twoFramesOn { capture(::pictured) }
    }

    /** Hands [print], the page as it is seen, to the book opening on the shelf, after which the reader shows itself. */
    private fun pictured(print: Bitmap?) {
        val h = handoff ?: return
        handoff = null
        // On the cover that ends the book, the cover alone is wanted: the book that only grows
        // becomes it as it ends (BookOpening.print), and a smaller picture is sooner made ready.
        // Turned a quarter, the page is wanted whole: the crease stands elsewhere on it.
        val box = if (turnLater) null else crease.box
        val cut = if (print != null && box != null && Rect(0, 0, print.width, print.height).contains(box)) {
            try {
                Bitmap.createBitmap(print, box.left, box.top, box.width(), box.height())
            } catch (e: OutOfMemoryError) {
                print
            }
        } else {
            print
        }
        val up = cut?.uploaded()
        if (turnLater) turnedPrint = up
        h.pictured(up)
        h.ready(::reveal)
    }

    /**
     * A copy of the window as it is drawn now, for [then]: copied from the screen, which shows
     * what the web view draws as no drawing of the page in memory does, and is as cheap as it
     * gets. Null if one cannot be had.
     */
    private fun capture(then: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 26) return then(content.picture())
        // Turned a quarter, the page is as large as the window across, not as it is set out.
        val shown = if (turnLater) window.decorView else root
        if (shown.width == 0 || shown.height == 0) return then(null)
        val bmp = try {
            createBitmap(shown.width, shown.height)
        } catch (e: OutOfMemoryError) {
            return then(null)
        }
        try {
            PixelCopy.request(window, bmp, { then(if (it == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            // Nothing is drawn in the window yet.
            then(null)
        }
    }

    /**
     * A page of text stands still: a screen that can refresh faster than 60 times a second only
     * uses more power doing so, unless the reader asks for it. Not while the book still opens on
     * the shelf under the reader, unseen: the screen would change speed in the middle of it,
     * which shows as a flicker.
     */
    private fun paceScreen() {
        // The cover that ends the book is seen as it is, at the speed the phone chooses, as on the shelf.
        if (!hidden) s.refresh(this, normal = onCover)
    }

    /** Shows the reader, once its book has opened on the shelf, and makes it a solid window again. */
    private fun reveal() {
        if (!hidden || isFinishing) return
        hidden = false
        if (turnLater) {
            turnLater = false
            // The page the book opened on stands in front, as it was read, while the screen turns.
            val print = turnedPrint
            turnedPrint = null
            if (print != null) showSidewaysPage(print, turnTo) else showSidewaysPage(BookOpening.kept(file, s.look), BookOpening.keptTurned(file, s.look))
            orient()
        }
        readEndPictureIfNear(state.percent)
        paceScreen()
        window.attributes = window.attributes.apply { alpha = 1f }
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        // The shelf under it need no longer be drawn: once the reader is seen, two frames on. Made
        // solid at once, the shelf and the book opening on it would be hidden while the reader is
        // still unseen, and a frame would show neither.
        if (Build.VERSION.SDK_INT >= 30) twoFramesOn { if (!isFinishing) setTranslucent(false) }
    }

    override fun reopen() {
        val gone = engine ?: return
        // Its view is taken away and nothing more asked of it while the reader is made anew.
        engine = null
        content.removeView(gone.view)
        gone.close()
        val now = SystemClock.elapsedRealtime()
        if (now - reopened < REOPEN_MS) {
            // Again so soon, the book is what fails: it is left rather than opened again and again.
            unreadable()
        } else {
            reopened = now
            recreate()
        }
    }

    /** Says that the book cannot be read, and leaves it. */
    private fun unreadable() {
        Toast.makeText(this, getString(R.string.book_unreadable, file.name), Toast.LENGTH_LONG).show()
        leave()
    }

    /** Does [action] two frames on, once what is drawn now is on the screen. */
    private fun twoFramesOn(action: () -> Unit) = root.postOnAnimation { root.postOnAnimation { action() } }

    /** Back to the shelf. */
    private fun leave() {
        if (leaving) return
        leaving = true
        tour?.happened(Tutorial.Event.READER_LEFT)
        // Left while the book shuts over its last page, it is shut at once, and left once that is seen.
        if (shuttingNow) {
            stopShutting()
            return twoFramesOn {
                leaving = false
                leave()
            }
        }
        // Left before its book had opened on the shelf, the reader does not wait for that any more.
        if (hidden) BookOpening.handoff = null
        // The shelf closes the book from the page it was at, as it is seen, but for the menu.
        if (!fromShelf || hidden) return goBack(null)
        // The book closes on the shelf as smoothly as the screen can show it, which changes speed
        // for that now, on a page standing still, rather than once the book has begun to close.
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = ReaderSettings.fastestMode(this@ReaderActivity)
            preferredRefreshRate = 0f
        }
        // On the cover that ends the book the shelf closes the book as it opened it, from the menu
        // under the cover, which goes back into the row of the shelf: the screen is pictured as it is.
        if (onCover && menu.isVisible && root.width < root.height) return capture(::closeOnMenu)
        // Seen sideways, the cover would be shut into its place on the shelf lying on its side: the shelf is just shown.
        if (onCover && root.width > root.height) return goBack(null)
        // Otherwise the cover is seen whole.
        engine?.let { if (onCover) coverRoom(it, 0) }
        if (!menu.isVisible) return capture(::upright)
        hideMenu()
        twoFramesOn { capture(::upright) }
    }

    /**
     * Goes back to the shelf with [page], turned upright if it was read sideways: the shelf stands
     * upright, and the page is shown on it as it was seen in the hand, turned as the screen was.
     */
    private fun upright(page: Bitmap?) {
        if (page == null || page.width < page.height) return goBack(page)
        val degrees = uprightTurn(root.display)
        val turned = turn(page, degrees)
        // Only a page that is read sideways whenever the book is opened is opened on again,
        // turned upright on the shelf as here: it is then shown as it will be read.
        goBack(turned, keep = s.orientation == ReaderSettings.LANDSCAPE, turned = degrees)
    }

    /** Goes back to the shelf with [page], the cover that ends the book standing on the menu, and the menu pictured. */
    private fun closeOnMenu(page: Bitmap?) {
        val picture = pictures.plank() ?: return goBack(page)
        // It is not opened on again: a book read to its end opens on its cover anyway.
        goBack(page, keep = false, pictures.endMenu(picture))
    }

    /** The reader is on its way back to the shelf, see [leave]. */
    private var leaving = false

    /**
     * Goes back to the shelf, which closes the book from [page], the page it was left at as it was
     * seen, if there is one; turned [turned] degrees to stand upright there, if it was read sideways.
     */
    private fun goBack(page: Bitmap?, keep: Boolean = true, menu: BookOpening.EndMenu? = null, turned: Float = 0f) {
        if (page != null) {
            BookOpening.left = BookOpening.Left(file, page.uploaded(), menu = menu)
            // Opened again next, the book opens on it at once.
            if (keep) BookOpening.keep(file, s.look, page, turned)
        }
        // A book opened on the home screen is closed on the bookshelf all the same: on the shelf it
        // stands on, in place of whatever else was open there.
        if (fromWidget) startActivity(
            Intent(this, MainActivity::class.java).putExtra(EXTRA_PATH, file.path).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            ),
        )
        finish()
        @Suppress("DEPRECATION")
        if (fromShelf && Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0)
    }

    private var fromShelf = false
    private var fromWidget = false

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- applying settings ----

    private fun applyBrightness() = s.light(window, mayStayOn = true)

    /**
     * Turns the screen as the reader asks. The cover that ends the book stands upright, as on the
     * shelf, however the book is read: it is shown as it is when the book is read to its end, and
     * when it opens there.
     */
    private fun orient() {
        requestedOrientation = when {
            // Upright as the shelf under it, until the book has opened there; see reveal.
            turnLater -> ActivityInfo.SCREEN_ORIENTATION_BEHIND
            s.orientation == 1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            onCover || finished && engine?.hasCover == true -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // From the upright shelf, the way the book was last read (see turnTo); the phone keeps
            // the screen that way once it is, while the phone is held upright.
            s.orientation == 2 && turnTo > 0f -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            s.orientation == 2 && turnTo < 0f -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            s.orientation == 2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /**
     * The cover that ends the book stands against the wall of the case, which shows round it; a
     * page lies on its paper, in the warm light (ReaderSettings.litPalette), which the cover and
     * the wall are not shown in.
     */
    private fun paintBackground() {
        if (onCover) root.background = wall else root.setBackgroundColor(s.litPalette.bg)
    }

    private fun applyLayout() {
        orient()
        paintBackground()
        turner.stop()
        // The back of the sheet lies in the warm light like the page, which it is drawn over.
        pageTurn.paper = s.lit(s.palette.bg)
        // In the colour of the text, but fainter.
        info.color = s.litPalette.fg and 0xFFFFFF or 0x99000000.toInt()
        paceScreen()
        // In paged mode page turns are ours; in scroll mode the content scrolls itself.
        content.consumeAll = !s.vertical
        // The book is laid out anew only if it is to look otherwise: back from the settings with
        // those as they were, the page stays as it is, rather than being drawn again to the same.
        val look = "${s.look}:${s.trimMargins}"
        if (look != laidOutAs) {
            laidOutAs = look
            engine?.applySettings()
        }
        arrangeInfo()
        showInfo()
        clock.start(s)
    }

    /** How the book was last laid out, see [applyLayout]. */
    private var laidOutAs = ""

    /**
     * The page has another shape, as the phone was turned: a page turning, or a book shutting over
     * its last page, was pictured for the shape it had, and would be shown stretched, or stuck.
     */
    private fun turned() {
        turner.stop()
        if (shuttingNow) {
            stopShutting()
            paceScreen()
        }
    }

    /** Takes the book shutting over its last page away at once, see [shut]. */
    private fun stopShutting() {
        shuttingNow = false
        shutting.clear()
    }

    /** Works out where what is shown around the text goes, and leaves the text the room for it. */
    private fun arrangeInfo() {
        if (root.width == 0 || root.height == 0) return
        val e = engine
        val (top, bottom) = info.arrange(root.width, root.height, cutout, s.infoUsed(), infoSide(s, e is FlowEngine, resources), e?.innerMargin ?: 0)
        val c = cutout.insets
        content.setPadding(c.left, c.top + top, c.right, c.bottom + bottom)
        // The cover that ends the book keeps its place on the screen however the margins of the page change.
        if (onCover && e != null) coverRoom(e, if (menuOpen) MenuPictures.room(menu) else 0)
    }

    /** Where the page shown is, as what is shown around the text tells it; see [showPosition]. */
    private var at = PagePlace()

    /** Shows around the text what the reader asked for, of the page at [at]. */
    private fun showInfo() {
        if (::pace.isInitialized) info.show(infoTexts(this, s, at, pace, bookPages))
    }

    /** Shows the time anew on the minute, while it is shown. */
    private val clock by lazy { MinuteClock(content, ::showInfo) }

    // ---- turning pages ----

    override fun hold(then: () -> Unit) = turner.hold(then)

    override fun release() = turner.release()

    // ---- input ----

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A finger put down while the menu slides away is for the page: the plank goes at once,
        // rather than taking the touch itself, and is out of sight before the page is pictured to turn.
        if (menuClosing && ev.actionMasked == MotionEvent.ACTION_DOWN) hideMenu()
        return super.dispatchTouchEvent(ev)
    }

    override fun onTap(x: Float, y: Float) {
        if (menuOpen && !onCover) return closeMenu()
        // On the cover that ends the book the menu is shown already, and cannot be taken away.
        turner.tap(x, y) { if (!onCover) openMenu() }
    }

    override fun canZoom() = engine?.zoomable == true && !menuOpen
    override fun zoomed() = engine?.zoomed == true

    override fun onZoom(factor: Float, x: Float, y: Float) {
        engine?.zoomBy(factor, x - content.paddingLeft, y - content.paddingTop)
    }

    override fun onZoomEnd() {
        engine?.zoomEnd()
    }

    override fun onPan(dx: Float, dy: Float) {
        engine?.panBy(dx, dy)
    }

    override fun onSwipe(direction: Int) {
        if (menuOpen && !onCover) return
        turner.swipe(direction)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> s.volumeUpNext
            KeyEvent.KEYCODE_VOLUME_DOWN -> !s.volumeUpNext
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> true
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> false
            else -> return super.dispatchKeyEvent(event)
        }
        // Swallow the key-up too, otherwise the system still changes the volume.
        if (event.action == KeyEvent.ACTION_DOWN) {
            clearOfMenu { turner.turn(if (forward) 1 else -1) }
        }
        return true
    }

    override fun onPosition(chapter: Int, fraction: Float, percent: Float, pageOfChapter: Pair<Int, Int>?, pageOfBook: Pair<Int, Int>?) {
        // A book of fixed pages is at the page it shows, which it counts as its chapter.
        val paged = engine is PageEngine
        turner.onPosition(chapter, fraction)
        tourAt?.let { was ->
            if (was.first != chapter || was.second != fraction) {
                val forward = chapter > was.first || (chapter == was.first && fraction > was.second)
                tourAt = chapter to fraction
                tour?.happened(if (forward) Tutorial.Event.PAGE_FORWARD else Tutorial.Event.PAGE_BACK)
            }
        }
        // The first time a book is read, once it is on its page, the reader is shown around.
        if (tourDue && !toured && !tourQueued) {
            tourQueued = true
            content.postDelayed(::startTour, TOUR_WAIT_MS)
        }
        readEndPictureIfNear(percent)
        // How long each page is read for tells how fast the reader reads, and how long the rest will take.
        if (bookPages > 0 && !seeking) pace.at(percent * bookPages, SystemClock.elapsedRealtime())
        // Where pages turn, only the cover that ends the book is the end of it, whatever the last page makes of a long book.
        val placed = if (engine?.hasCover == true && percent < 1f) minOf(percent, Library.LAST_PAGE_AT) else percent
        state.chapter = chapter
        state.fraction = fraction
        state.percent = placed
        state.lastRead = System.currentTimeMillis()
        // Scrolling reports its place several times a second; writing each one down would keep
        // the storage busy for nothing. It is saved now and then, and whenever the reader leaves.
        unsaved = true
        if (state.lastRead - savedAt > SAVE_EVERY_MS) savePosition()
        // The last page finishes the book and leaving it unfinishes it again: the shelf shows that
        // from what is saved, so a change is written down at once.
        if ((placed >= Library.FINISHED_AT) != finished) {
            finished = !finished
            (title.background as PlaqueDrawable).finished = finished
            savePosition()
        }
        // The page left behind may tell its place once more before the one the knob was taken
        // to is shown: the line does not go back to it meanwhile.
        if (seekTarget >= 0) {
            if (abs(percent - seekTarget) > SEEK_NEAR) {
                skipped = { showPosition(chapter, percent, paged, pageOfChapter, pageOfBook) }
                return
            }
            settleSeek()
        }
        showPosition(chapter, percent, paged, pageOfChapter, pageOfBook)
    }

    /** Where the knob of the seek bar was let go, 0..1, until the book has been taken there; else -1. */
    private var seekTarget = -1f

    /** The place last told while the knob was let go, which is shown if the book is not taken there after all. */
    private var skipped: (() -> Unit)? = null

    private val seekSettled = Runnable {
        val last = skipped
        settleSeek()
        last?.invoke()
    }

    private fun settleSeek() {
        content.removeCallbacks(seekSettled)
        seekTarget = -1f
        skipped = null
        chapters.seeking = Float.NaN
    }

    /** Shows the place told by [onPosition] in the menu and at the foot of the page; [paged] for a book of fixed pages. */
    private fun showPosition(chapter: Int, percent: Float, paged: Boolean, pageOfChapter: Pair<Int, Int>?, pageOfBook: Pair<Int, Int>?) {
        // The chapters stand along the line where their first pages are, which moves as the pages of the chapters are counted.
        engine?.let { e ->
            if (e.positionsVersion != positionsSeen) {
                positionsSeen = e.positionsVersion
                setUpChapters(e)
            }
        }
        // The chapter is counted as on the line below, by the book's contents, where it has any:
        // the files a book is made of, which the engine counts, also hold its cover and the like.
        // On the cover that ends the book, there is no page, and the book is in its last chapter,
        // even before the engine has told it has gone there, as when the book opens on its cover.
        val ended = engine?.hasCover == true && percent >= 1f
        val mark = marked.indexOfLast { it <= (engine?.currentContent ?: -1) }.let { if (it < 0 && ended) marked.lastIndex else it }
        // The pages of the book as the engine counts them, with the text as large as it is. A
        // PDF is at the page it shows; an EPUB that scrolls as far into the pages a printed
        // one would have as into its text.
        if (ended != onCover) coverChanged(ended)
        val book = if (ended) null else pageOfBook ?: bookPages.takeIf { it > 0 }?.let {
            (if (paged) chapter + 1 else 1 + (percent * (it - 1)).roundToInt()).coerceIn(1, it) to it
        }
        reading = if (ended) author else engine?.let { if (mark >= 0) chapterName(it, mark) else "" }.orEmpty()
        at = PagePlace(
            book, pageOfChapter, reading.ifEmpty { null },
            engine?.chapterEnd?.let { (it - percent).coerceAtLeast(0f) }, 1 - percent,
        )
        showInfo()
        // The menu is closed most of the time. What it would show is written into it only when it
        // opens, so that a page that scrolls does not lay out a menu nobody sees.
        if (!menuOpen) {
            behindMenu = { showPosition(chapter, percent, paged, pageOfChapter, pageOfBook) }
            return
        }
        behindMenu = null
        showFigures(pageOfChapter, book, ended, percent)
        name(reading, other = false)
        if (!seeking) seek.progress = (percent * 1000).roundToInt()
        chapters.current = mark
        chapters.place = percent
    }

    /** The place last told while the menu was closed, which it shows once it opens; see [openMenu]. */
    private var behindMenu: (() -> Unit)? = null

    /** Counts the times the menu was asked for, so that one asked for while a page turns from the cover is not taken away after. */
    private var menuAsked = 0

    /** Closes the menu, sliding away, when the reader asks for that; see [hideMenu]. */
    private fun closeMenu() {
        hideMenu(slide = true)
        tour?.happened(Tutorial.Event.READER_MENU_CLOSED)
    }

    private fun openMenu() {
        menuAsked++
        showMenu(slide = true)
        behindMenu?.invoke()
        tour?.happened(Tutorial.Event.READER_MENU_OPENED)
    }

    /** The tour of the reader while it is up, and the place in the book it began at, which a page turned leaves. */
    private var tour: Tutorial? = null
    private var tourAt: Pair<Int, Float>? = null
    private var tourQueued = false

    /** Whether this book is the one to show the reader round: looked up once, not at every page. */
    private val tourDue by lazy { !toured && !tourSeen(this, Tutorial.READER) }

    /** Shows the first-time tour of the reader once the book has settled on its page. */
    private fun startTour() {
        tourQueued = false
        // Not on the cover that ends the book, where the menu is up already.
        if (isFinishing || isDestroyed || menuOpen || toured) return
        toured = true
        tourAt = state.chapter to state.fraction
        val lit = Tutorial.Touch.LIT
        // The first steps are for the page, the last two for the menu.
        val onPage = { hasWindowFocus() && !menuOpen }
        val inMenu = { hasWindowFocus() && menuOpen }
        // Where a tap turns the page, if that is asked for; else the page is only turned by swiping it, anywhere on it.
        val tapping = s.tapToTurn
        val forward = { Tutorial.sliceOf(content, if (tapping) 0.7f else 0f, 1f) }
        val backward = { Tutorial.sliceOf(content, 0f, if (tapping) 0.3f else 1f) }
        val middle = { Tutorial.sliceOf(content, 0.3f, 0.7f) }
        // Showing the menu before it is asked for, the reader has found their way: on to what it holds.
        val toMenu = mapOf(Tutorial.Event.READER_MENU_OPENED to 3)
        tour = Tutorial.start(
            this,
            Tutorial.READER,
            listOf(
                Tutorial.Step(if (tapping) R.string.tutorial_page_tap else R.string.tutorial_page_swipe, forward, Tutorial.Event.PAGE_FORWARD, lit, onPage, perform = { turner.swipe(-1) }, jump = toMenu, top = true),
                Tutorial.Step(if (tapping) R.string.tutorial_back_tap else R.string.tutorial_back_swipe, backward, Tutorial.Event.PAGE_BACK, lit, onPage, perform = { turner.swipe(1) }, jump = toMenu, top = true),
                Tutorial.Step(R.string.tutorial_menu_tap, middle, Tutorial.Event.READER_MENU_OPENED, lit, onPage, perform = { openMenu() }, top = true),
                Tutorial.Step(R.string.tutorial_reader_menu, { Tutorial.rectOf(findViewById(R.id.menuPlank)) }, touch = lit, ready = inMenu, top = true),
                Tutorial.Step(R.string.tutorial_leave_book, { Tutorial.rectOf(findViewById(R.id.back)) }, Tutorial.Event.READER_LEFT, lit, inMenu, perform = { leave() }, top = true),
            ),
        ) { tour = null; tourAt = null }
    }

    /**
     * Moves each row of the [plank] that a camera at the side of the screen cuts into in from that
     * side, until it is clear of it: the arrow and the gear stand beside the camera rather than
     * under it. A row the camera is above or below stays where it is, as the wood runs on under
     * the camera all the same. The top row goes by its buttons, which are lower than the row.
     */
    private fun clearCamera() {
        val w = root.width
        if (w == 0 || plank.height == 0) return
        val back = findViewById<View>(R.id.back)
        for (i in 0 until plank.childCount) {
            val row = plank.getChildAt(i)
            val lp = row.layoutParams as ViewGroup.MarginLayoutParams
            val (start, end) = rowMargins.getOrPut(row) { lp.leftMargin to lp.rightMargin }
            val shown = if (back.parent === row) back else row
            val top = plank.top + row.top + if (shown === row) 0 else shown.top
            val bottom = top + shown.height
            var left = 0
            var right = 0
            // Without the bounds of the camera, the rows keep clear of the whole strip it is in.
            if (cutout.holes.isEmpty()) {
                left = cutout.insets.left
                right = cutout.insets.right
            }
            for (hole in cutout.holes) {
                if (hole.top >= bottom || hole.bottom <= top) continue
                if (hole.centerX() < w / 2) left = max(left, hole.right) else right = max(right, w - hole.left)
            }
            if (lp.leftMargin != start + left || lp.rightMargin != end + right) {
                lp.leftMargin = start + left
                lp.rightMargin = end + right
                row.layoutParams = lp
            }
        }
    }

    /** Whether the menu is sliding away: still seen, but closed for all else; see [hideMenu]. */
    private var menuClosing = false

    /** Whether the menu is open, and not on its way out. */
    private val menuOpen get() = menu.isVisible && !menuClosing

    /**
     * Shows the menu: at once, or, to [slide], with its plank sliding up into place from the foot
     * of the screen, or from wherever it was sliding away to. The menu stays laid out while it is
     * closed, so the plank is drawn once onto a layer of its own and only moved after.
     */
    private fun showMenu(slide: Boolean = false) {
        plank.animate().cancel()
        menuClosing = false
        if (slide && !menu.isVisible) plank.translationY = plank.height.toFloat()
        menu.visibility = View.VISIBLE
        if (!slide || plank.translationY == 0f) {
            plank.translationY = 0f
            return
        }
        plank.animate().translationY(0f).setDuration(MENU_IN_MS).setInterpolator(MENU_IN).withLayer()
    }

    /**
     * Closes the menu: at once, or, to [slide], with its plank sliding down out of sight. It is
     * closed as soon as it starts to go: a tap, a swipe or a pinch meanwhile is for the page.
     */
    private fun hideMenu(slide: Boolean = false) {
        if (!menu.isVisible || slide && menuClosing) return
        plank.animate().cancel()
        if (!slide || plank.height == 0) return menuGone()
        menuClosing = true
        plank.animate().translationY(plank.height.toFloat()).setDuration(MENU_OUT_MS).setInterpolator(MENU_OUT).withLayer()
            .withEndAction(::menuGone)
    }

    /** The menu is closed and out of sight, its plank back in its place for the next time it opens. */
    private fun menuGone() {
        menuClosing = false
        menu.visibility = View.INVISIBLE
        plank.translationY = 0f
    }

    /**
     * Does [action], which pictures the page as it is seen to turn it, once the plank of a menu
     * sliding away is out of sight, as it is at once otherwise: it would be pictured with the page.
     * A touch takes the plank away as it begins (dispatchTouchEvent); this is for the keys.
     */
    private fun clearOfMenu(action: () -> Unit) {
        if (!menuClosing) return action()
        hideMenu()
        twoFramesOn(action)
    }

    /** The place in the book has changed since it was last written down, at [savedAt]. */
    private var unsaved = false
    private var savedAt = 0L

    /** The book stands on its last page, or did when it was opened. */
    private var finished = false

    private fun savePosition() {
        if (!unsaved) return
        unsaved = false
        savedAt = System.currentTimeMillis()
        Library.saveState(this, file, state)
    }

    companion object {
        const val EXTRA_PATH = "path"

        /** Set by the shelf when it has opened the book itself and will close it again. */
        const val EXTRA_FROM_SHELF = "fromShelf"

        /** Set when the book was opened on the home-screen widget, with no bookshelf to go back to. */
        const val EXTRA_FROM_WIDGET = "fromWidget"

        private const val SAVE_EVERY_MS = 30_000L

        /** Whether the reader was shown around since the app started, and how long after the first page it is, in ms. */
        private var toured = false
        private const val TOUR_WAIT_MS = 1500L

        /** How far into the book the cover that ends it is read, see endPicture. */
        private const val END_NEAR = 0.8f

        /** The longest the book opening on the shelf waits for the reader's page, see [pictured]. */
        private const val SHOW_WAIT_MS = 2500L

        /**
         * How far a page read sideways is turned to be seen upright, as the shelf shows it (see upright),
         * the usual way: a quarter to the right, as the screen is when the phone held upright is turned
         * sideways by the app. The other way, a quarter to the left, for a book last read so.
         */
        private const val SIDEWAYS = 90f

        /** How near the place the knob was let go the book must tell it is, and how long it has to get there. */
        private const val SEEK_NEAR = 0.03f
        private const val SEEK_WAIT_MS = 1500L

        /** How long the plank of the menu takes to slide up into place, and down out of sight; see showMenu. */
        private const val MENU_IN_MS = 160L
        private const val MENU_OUT_MS = 120L
        private val MENU_IN = DecelerateInterpolator(1.5f)
        private val MENU_OUT = AccelerateInterpolator(1.5f)

        /** When a reader last opened its book anew, see [reopen]; one failing again within [REOPEN_MS] is not opened again. */
        private var reopened = Long.MIN_VALUE / 2
        private const val REOPEN_MS = 10_000L
    }
}

/**
 * The reader as it is started the moment a book is tapped on the shelf, see [BookOpening.Handoff]:
 * with a see-through window (Theme.Ready.Reader.Opening), for the book to open on the shelf under
 * it, which the reader can only make solid again from Android 11 on.
 */
class OpeningReaderActivity : ReaderActivity() {
    override val behindOpening get() = true
}
