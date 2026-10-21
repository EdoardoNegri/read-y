package io.github.edoardonegri.ready

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import kotlin.math.roundToInt

open class ReaderActivity : AppCompatActivity(), ReaderHost, GestureFrame.Listener {

    private lateinit var s: ReaderSettings
    private lateinit var file: File
    private var state = BookState()
    private var engine: ReaderEngine? = null

    private lateinit var root: View
    private lateinit var content: GestureFrame
    private lateinit var menu: View

    /** The crease along the spine of the cover that ends the book, see [coverRoom]. */
    private lateinit var crease: CoverCrease

    /** The name of the book, on the menu. */
    private lateinit var title: TextView

    /** The back wall of a case, in the warm light, which the cover that ends the book stands against. */
    private lateinit var wall: Drawable
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
        val tdbg = android.os.SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        s = ReaderSettings(this)
        setContentView(R.layout.activity_reader)
        // The page covers the whole window, so the window need not paint anything under it.
        window.setBackgroundDrawable(null)
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        menu = findViewById(R.id.menu)
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
            cutout = Cutout.of(insets)
            cutout.insets.let { menu.setPadding(it.left, it.top, it.right, it.bottom) }
            arrangeInfo()
            insets
        }
        // Turned, the page has another shape, and what is around the text other places.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) arrangeInfo() }
        immersive()

        file = File(intent.getStringExtra(EXTRA_PATH) ?: "")
        if (!file.isDoc) {
            Toast.makeText(this, R.string.book_missing, Toast.LENGTH_LONG).show()
            leave()
            return
        }
        wall = ShelfArt.wall(this, s.lamp)
        state = Library.state(this, file)
        finished = state.percent >= Library.FINISHED_AT
        pace = ReadingPace(this, ReadingPace.kindOf(file))
        android.util.Log.d("TDBG", "before-engine " + (android.os.SystemClock.uptimeMillis() - tdbg))
        val opened = try {
            if (Library.isFlowing(file)) FlowEngine(this, file, s, state, this)
            else PageEngine(this, file, s, state, this)
        } catch (e: Exception) {
            unreadable()
            return
        }
        android.util.Log.d("TDBG", "after-engine " + (android.os.SystemClock.uptimeMillis() - tdbg))
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
        android.util.Log.d("TDBG", "before-chapters " + (android.os.SystemClock.uptimeMillis() - tdbg))
        setUpChapters(opened)
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            settingsOpened = true
            menu.visibility = View.GONE
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
                if (menu.isVisible && !onCover) menu.visibility = View.GONE else leave()
            }
        })

        applyBrightness()
        android.util.Log.d("TDBG", "before-layout " + (android.os.SystemClock.uptimeMillis() - tdbg))
        applyLayout()
        // Once the page is set out and drawn, unseen, the book opens on the shelf onto a picture of
        // it (see shown), and the reader shows itself when the book is open; not later than this
        // all the same, should the page never be set out.
        if (handoff != null) root.postDelayed({ pictured(null) }, SHOW_WAIT_MS)
        // Opening on its cover, a book has the menu picture ready for the cover that grows on the shelf.
        android.util.Log.d("TDBG", "end " + (android.os.SystemClock.uptimeMillis() - tdbg))
        if (handoff != null && finished && opened.hasCover) pictureEndMenu(handoff, opened)
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
        content.removeCallbacks(tick)
        // The settings opened from the reader keep the phone as quiet as the book did.
        if (!settingsOpened) DoNotDisturb.stop(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        engine?.resume()
        clock()
        if (s.doNotDisturb) DoNotDisturb.start(this) else DoNotDisturb.stop(this)
        if (settingsOpened) {
            settingsOpened = false
            applyBrightness()
            applyLayout()
        }
        // Whatever was done meanwhile, the menu is shown over the cover that ends the book.
        if (onCover) menu.visibility = View.VISIBLE
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
        val placed: Float
        if (e.hasCover) {
            // The place of the page nearest, which is told as the pages before it make of all of them; after the last it is the cover.
            placed = e.snap(at)
            // The cover is no page of a chapter: its place is left empty while the knob is over it, as when it is shown.
            val ended = placed >= 1f
            inChapter.visibility = if (!ended && ofChapter != null) View.VISIBLE else View.INVISIBLE
            if (ended) {
                bookPage.text = getString(R.string.the_end)
                inBook.visibility = View.VISIBLE
            } else {
                if (ofChapter != null) chapterPage.text = getString(R.string.page_of, ofChapter.first, ofChapter.second)
                if (ofBook != null) bookPage.text = getString(R.string.page_of, ofBook.first, ofBook.second)
                inBook.visibility = if (ofBook != null) View.VISIBLE else View.INVISIBLE
            }
        } else {
            if (ofChapter != null) chapterPage.text = getString(R.string.page_of, ofChapter.first, ofChapter.second)
            val book = ofBook ?: bookPages.takeIf { it > 0 }?.let { (1 + (at * (it - 1)).roundToInt()).coerceIn(1, it) to it }
            if (book != null) bookPage.text = getString(R.string.page_of, book.first, book.second)
            // Scrolled, where the page begins, as the first page is 0 and the last 100.
            placed = book?.takeIf { it.second > 1 }?.let { (it.first - 1f) / (it.second - 1) } ?: at
        }
        percentRead.text = getString(R.string.percent_read, (placed * 100).roundToInt())
        return placed
    }

    /** The place of the page the knob of the seek bar is on while it is dragged, 0..1; else -1. */
    private var dragAt = -1f

    /**
     * Whether the cover that ends the book is shown: the menu is over the foot of the screen then,
     * and cannot be taken away, and the cover is in the room above it. Whether the menu was shown
     * before, which it goes back to once the cover is left; and whether it was the knob of the seek bar that left it.
     */
    private var onCover = false
    private var menuBeforeCover = false
    private var keepMenu = false

    /**
     * How much room the plank of the menu takes at the foot of the screen: the menu is as large as
     * the screen, with an empty place above its plank, and the room of the camera over that.
     */
    private fun menuRoom(m: View) = m.height - m.findViewById<View>(R.id.menuPlank).top

    private fun coverChanged(on: Boolean) {
        onCover = on
        // The cover stands against the wall of the case, which shows round it; a page lies on its paper.
        if (on) root.background = wall else root.setBackgroundColor(s.litPalette.bg)
        if (!on) crease.box = null
        val e = engine ?: return
        if (on) {
            menuBeforeCover = menu.isVisible
            behindMenu = null
            // The menu comes at once, as it does for a tap on the page. Turned to from the last page,
            // the book shuts over that page, which stays in front meanwhile, see shut. Unseen, as when
            // the book opens on its cover, the menu is only set out, to be pictured for the opening,
            // see pictureEndMenu.
            val left = if (!hidden && !menuBeforeCover) turner.takeTurn() else null
            menu.visibility = View.VISIBLE
            menu.doOnLayout { m ->
                if (!onCover) {
                    if (left != null) turner.stop()
                    return@doOnLayout
                }
                coverRoom(e, menuRoom(m))
                if (left != null) shut(left)
            }
        } else {
            // The menu goes, unless it was there before, or it was the knob of the seek bar that left the
            // cover: once the page turned to from the cover is seen, the menu saying where it is meanwhile,
            // over the picture of the cover being turned away, menu and all.
            coverRoom(e, 0)
            if (!menuBeforeCover && !keepMenu) {
                val asked = menuAsked
                turner.afterTurn { if (!onCover && menuAsked == asked) menu.visibility = View.GONE }
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

    /** Reads the cover that ends the book in the background, see [endPicture]. */
    private fun readEndPicture() {
        endPictureAsked = true
        val app = applicationContext
        val width = resources.displayMetrics.widthPixels
        Library.io.execute {
            val picture = try {
                Library.bigCover(app, file, width).uploaded()
            } catch (e: Throwable) {
                null
            }
            runOnUiThread { if (!isDestroyed) endPicture = picture }
        }
    }

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
        shutting.shut(spot, left, s.litPalette.bg, s.lamp) { shuttingNow = false }
        // The layer of the book takes the place of the page in front, in the same frame.
        turner.stop()
    }

    /**
     * A book that opens on its cover has its menu set out at once, as it is over the cover, 100% read
     * and at the end, and pictured for the book opening on the shelf, which raises it as the cover grows.
     */
    private fun pictureEndMenu(h: BookOpening.Handoff, e: ReaderEngine) {
        showPosition(state.chapter, 1f, e is PageEngine, null, null)
        menu.doOnLayout { h.menuPictured(pictureMenu()?.let(::endMenu)) }
    }

    /**
     * The menu as it stands over the cover that ends the book, from [picture] of its plank, for the
     * book opening on its cover on the shelf or closing from it: with where the plaque with the name
     * of the book is on the plank, which the plaque of the book on the shelf becomes.
     */
    private fun endMenu(picture: Bitmap): BookOpening.EndMenu {
        // Where it is in the window, as its own place would tell it less the way its single line is scrolled.
        val at = IntArray(2)
        title.getLocationInWindow(at)
        val plank = IntArray(2)
        menu.findViewById<View>(R.id.menuPlank).getLocationInWindow(plank)
        val left = (at[0] - plank[0]).toFloat()
        val top = (at[1] - plank[1]).toFloat()
        // The plaque pictured as it is drawn, name and all.
        val plaque = try {
            createBitmap(title.width.coerceAtLeast(1), title.height.coerceAtLeast(1)).also { title.draw(Canvas(it)) }
        } catch (e: OutOfMemoryError) {
            null
        }
        val plate = RectF(left, top, left + title.width, top + title.height)
        val known = BookOpening.EndMenu(root.width, root.height, picture.height, plate, title.textSize, title.paddingStart.toFloat())
        // Where it stands is kept, for the book to open on its cover at once the next time (BookOpening.Detached).
        Library.setEndMenu(this, file, known)
        return BookOpening.EndMenu(
            known.screenW, known.screenH, known.height, known.title, known.titleText, known.titleInset, picture.uploaded(), plaque?.uploaded(),
        )
    }

    /**
     * The plank of the menu, pictured as it is, without the plaque with the name of the book: on the
     * shelf the plaque of the book stands for it, which becomes it. Null if it cannot be pictured.
     */
    private fun pictureMenu(): Bitmap? {
        val room = menuRoom(menu)
        if (menu.width == 0 || room <= 0) return null
        return try {
            createBitmap(menu.width, room).also { bmp ->
                val canvas = Canvas(bmp)
                canvas.translate(0f, -(menu.height - room).toFloat())
                title.alpha = 0f
                menu.draw(canvas)
                title.alpha = 1f
            }
        } catch (x: OutOfMemoryError) {
            title.alpha = 1f
            null
        }
    }

    /** Which [ReaderEngine.positionsVersion] the marks of the chapters along the seek bar were made for. */
    private var positionsSeen = -1

    /** Shows [text] as the name of a chapter, brighter if it is [other] than the one being read. */
    private fun name(text: String, other: Boolean) {
        chapterName.text = text
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

    override fun shown() {
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
        val box = crease.box
        val cut = if (print != null && box != null && Rect(0, 0, print.width, print.height).contains(box)) {
            try {
                Bitmap.createBitmap(print, box.left, box.top, box.width(), box.height())
            } catch (e: OutOfMemoryError) {
                print
            }
        } else {
            print
        }
        h.pictured(cut?.uploaded())
        h.ready(::reveal)
    }

    /**
     * A copy of the window as it is drawn now, for [then]: copied from the screen, which shows
     * what the web view draws as no drawing of the page in memory does, and is as cheap as it
     * gets. Null if one cannot be had.
     */
    private fun capture(then: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 26) return then(content.picture())
        if (root.width == 0 || root.height == 0) return then(null)
        val bmp = try {
            createBitmap(root.width, root.height)
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
        if (!hidden) s.refresh(this)
    }

    /** Shows the reader, once its book has opened on the shelf, and makes it a solid window again. */
    private fun reveal() {
        if (!hidden || isFinishing) return
        hidden = false
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
        // Left while the book shuts over its last page, it is shut at once, and left once that is seen.
        if (shuttingNow) {
            shuttingNow = false
            shutting.clear()
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
        // Otherwise the cover is seen whole.
        engine?.let { if (onCover) coverRoom(it, 0) }
        if (!menu.isVisible) return capture(::upright)
        menu.visibility = View.GONE
        twoFramesOn { capture(::upright) }
    }

    /**
     * Goes back to the shelf with [page], turned upright if it was read sideways: the shelf stands
     * upright, and the page is shown on it as it was seen in the hand, turned as the screen was.
     */
    private fun upright(page: Bitmap?) {
        if (page == null || page.width < page.height) return goBack(page)
        @Suppress("DEPRECATION")
        val rotation = if (Build.VERSION.SDK_INT >= 30) display?.rotation else windowManager.defaultDisplay.rotation
        val turn = Matrix().apply { setRotate(if (rotation == Surface.ROTATION_270) -90f else 90f) }
        val turned = try {
            Bitmap.createBitmap(page, 0, 0, page.width, page.height, turn, true)
        } catch (e: OutOfMemoryError) {
            null
        }
        // Only a page read upright is opened on again, which looks as it would be read.
        goBack(turned, keep = false)
    }

    /** Goes back to the shelf with [page], the cover that ends the book standing on the menu, and the menu pictured. */
    private fun closeOnMenu(page: Bitmap?) {
        val picture = pictureMenu() ?: return goBack(page)
        // It is not opened on again: a book read to its end opens on its cover anyway.
        goBack(page, keep = false, endMenu(picture))
    }

    /** The reader is on its way back to the shelf, see [leave]. */
    private var leaving = false

    /** Goes back to the shelf, which closes the book from [page], the page it was left at as it was seen, if there is one. */
    private fun goBack(page: Bitmap?, keep: Boolean = true, menu: BookOpening.EndMenu? = null) {
        if (page != null) {
            BookOpening.left = BookOpening.Left(file, page.uploaded(), menu = menu)
            // Opened again next, the book opens on it at once.
            if (keep) BookOpening.keep(file, s.look, page)
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

    private fun applyLayout() {
        requestedOrientation = when (s.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // The page is shown in the warm light, see ReaderSettings.litPalette, and so is the wall
        // the cover that ends the book stands against.
        wall = ShelfArt.wall(this, s.lamp)
        if (onCover) root.background = wall else root.setBackgroundColor(s.litPalette.bg)
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
        clock()
    }

    /** How the book was last laid out, see [applyLayout]. */
    private var laidOutAs = ""

    /** Works out where what is shown around the text goes, and leaves the text the room for it. */
    private fun arrangeInfo() {
        if (root.width == 0 || root.height == 0) return
        val e = engine
        val (top, bottom) = info.arrange(root.width, root.height, cutout, s.infoUsed(), infoSide(s, e is FlowEngine, resources), e?.innerMargin ?: 0)
        val c = cutout.insets
        content.setPadding(c.left, c.top + top, c.right, c.bottom + bottom)
        // The cover that ends the book keeps its place on the screen however the margins of the page change.
        if (onCover && e != null) coverRoom(e, if (menu.isVisible) menuRoom(menu) else 0)
    }

    /** Where the page shown is, as what is shown around the text tells it; see [showPosition]. */
    private var at = PagePlace()

    /** Shows around the text what the reader asked for, of the page at [at]. */
    private fun showInfo() {
        if (::pace.isInitialized) info.show(infoTexts(this, s, at, pace, bookPages))
    }

    /** Shows the time anew on the minute, while it is shown. */
    private val tick = object : Runnable {
        override fun run() {
            showInfo()
            content.postDelayed(this, untilMinute())
        }
    }

    /** Starts showing the time anew on the minute, if it is shown around the text, or stops it if not. */
    private fun clock() {
        content.removeCallbacks(tick)
        if (s.showPage && s.infoAt(InfoItem.TIME) != InfoPlace.HIDDEN) content.postDelayed(tick, untilMinute())
    }

    // ---- turning pages ----

    override fun hold(then: () -> Unit) = turner.hold(then)

    override fun release() = turner.release()

    // ---- input ----

    override fun onTap(x: Float, y: Float) {
        if (menu.isVisible && !onCover) {
            menu.visibility = View.GONE
            return
        }
        // On the cover that ends the book the menu is shown already, and cannot be taken away.
        turner.tap(x, y) { if (!onCover) openMenu() }
    }

    override fun canZoom() = engine?.zoomable == true && !menu.isVisible
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
        if (menu.isVisible && !onCover) return
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
            turner.turn(if (forward) 1 else -1)
        }
        return true
    }

    override fun onPosition(chapter: Int, fraction: Float, percent: Float, pageOfChapter: Pair<Int, Int>?, pageOfBook: Pair<Int, Int>?) {
        // A book of fixed pages is at the page it shows, which it counts as its chapter.
        val paged = engine is PageEngine
        turner.onPosition(chapter, fraction)
        // Near the end, the cover that ends the book is read, for the book to shut over its last page.
        if (!endPictureAsked && percent >= END_NEAR && engine?.hasCover == true) readEndPicture()
        // How long each page is read for tells how fast the reader reads, and how long the rest will take.
        if (bookPages > 0 && !seeking) pace.at(percent * bookPages, SystemClock.elapsedRealtime())
        state.chapter = chapter
        state.fraction = fraction
        state.percent = percent
        state.lastRead = System.currentTimeMillis()
        // Scrolling reports its place several times a second; writing each one down would keep
        // the storage busy for nothing. It is saved now and then, and whenever the reader leaves.
        unsaved = true
        if (state.lastRead - savedAt > SAVE_EVERY_MS) savePosition()
        // The last page finishes the book and leaving it unfinishes it again: the shelf shows that
        // from what is saved, so a change is written down at once.
        if ((percent >= Library.FINISHED_AT) != finished) {
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
        if (!menu.isVisible) {
            behindMenu = { showPosition(chapter, percent, paged, pageOfChapter, pageOfBook) }
            return
        }
        behindMenu = null
        // What is not known, or does not apply, leaves its place empty, so that the rest stays put.
        inChapter.visibility = if (pageOfChapter == null) View.INVISIBLE else View.VISIBLE
        if (pageOfChapter != null) chapterPage.text = getString(R.string.page_of, pageOfChapter.first, pageOfChapter.second)
        inBook.visibility = if (book == null && !ended) View.INVISIBLE else View.VISIBLE
        if (book != null) bookPage.text = getString(R.string.page_of, book.first, book.second)
        if (ended) bookPage.text = getString(R.string.the_end)
        percentRead.text = getString(R.string.percent_read, (percent * 100).roundToInt())
        name(reading, other = false)
        if (!seeking) seek.progress = (percent * 1000).roundToInt()
        chapters.current = mark
        chapters.place = percent
    }

    /** The place last told while the menu was closed, which it shows once it opens; see [openMenu]. */
    private var behindMenu: (() -> Unit)? = null

    /** Counts the times the menu was asked for, so that one asked for while a page turns from the cover is not taken away after. */
    private var menuAsked = 0

    private fun openMenu() {
        menuAsked++
        menu.visibility = View.VISIBLE
        behindMenu?.invoke()
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

        /** How far into the book the cover that ends it is read, see endPicture. */
        private const val END_NEAR = 0.8f

        /** The longest the book opening on the shelf waits for the reader's page, see [pictured]. */
        private const val SHOW_WAIT_MS = 2500L

        /** How near the place the knob was let go the book must tell it is, and how long it has to get there. */
        private const val SEEK_NEAR = 0.03f
        private const val SEEK_WAIT_MS = 1500L

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
