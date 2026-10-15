package io.github.edoardonegri.ready

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity(), ReaderHost, GestureFrame.Listener {

    private lateinit var s: ReaderSettings
    private lateinit var file: File
    private var state = BookState()
    private var engine: ReaderEngine? = null

    private lateinit var root: View
    private lateinit var content: GestureFrame
    private lateinit var menu: View
    private lateinit var pageTurn: PageTurnView
    private lateinit var lamp: LampView
    private lateinit var inChapter: View
    private lateinit var inBook: View
    private lateinit var percentRead: TextView
    private lateinit var chapterPage: TextView
    private lateinit var bookPage: TextView
    private lateinit var chapterName: TextView
    private lateinit var pageNumber: TextView
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
        // A page of text stands still; a screen that can refresh faster than 60 times a second
        // gains nothing from doing so here and only uses more power.
        if (Build.VERSION.SDK_INT >= 30) window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        menu = findViewById(R.id.menu)
        pageTurn = findViewById(R.id.pageTurn)
        lamp = findViewById(R.id.lamp)
        inChapter = findViewById(R.id.inChapter)
        inBook = findViewById(R.id.inBook)
        // Come from the shelf, the book was opened there, and is closed there when it is left.
        fromShelf = intent.getBooleanExtra(EXTRA_FROM_SHELF, false)
        fromWidget = intent.getBooleanExtra(EXTRA_FROM_WIDGET, false)
        if (fromShelf && Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        if (fromWidget) {
            // The reader joins the bookshelf's task from the widget's own, so the system switches
            // tasks and may play its transition for that whatever the intent flags say; the
            // opened book is already there to take over from without one.
            window.setWindowAnimations(0)
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
        percentRead = findViewById(R.id.percent)
        chapterPage = findViewById(R.id.chapterPage)
        bookPage = findViewById(R.id.bookPage)
        chapterName = findViewById(R.id.chapterName)
        pageNumber = findViewById(R.id.pageNumber)
        seek = findViewById(R.id.seek)
        chapters = findViewById(R.id.chapters)

        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Draw behind the (hidden) system bars but keep text clear of a notch or camera hole.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            content.setPadding(c.left, c.top, c.right, c.bottom)
            menu.setPadding(c.left, c.top, c.right, c.bottom)
            insets
        }
        immersive()

        file = File(intent.getStringExtra(EXTRA_PATH) ?: "")
        if (!file.isDoc) {
            Toast.makeText(this, R.string.book_missing, Toast.LENGTH_LONG).show()
            leave()
            return
        }
        state = Library.state(this, file)
        finished = state.percent >= Library.FINISHED_AT
        val opened = try {
            if (Library.isFlowing(file)) FlowEngine(this, file, s, state, this)
            else PageEngine(this, file, s, state, this)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.book_unreadable, file.name), Toast.LENGTH_LONG).show()
            leave()
            return
        }
        engine = opened
        content.addView(opened.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.listener = this

        val title = findViewById<TextView>(R.id.bookTitle)
        val known = Library.cachedMeta(this, file)
        title.text = known?.title ?: file.nameWithoutExtension
        bookPages = known?.pages ?: 0
        // A book opened before the shelf has looked at it, e.g. one just handed over by another app.
        if (known == null) Library.io.execute {
            val meta = Library.meta(applicationContext, file)
            runOnUiThread {
                title.text = meta.title
                bookPages = meta.pages
            }
        }
        findViewById<ImageButton>(R.id.back).setOnClickListener { leave() }
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
                val at = progress / 1000f
                val mark = chapters.marks.indexOfLast { it <= at + 0.0005f }
                if (mark >= 0) name(chapterName(e, mark), other = true)
                chapters.seeking = at
                showPlace(e, at)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                // The line stays as it was left until the book has been taken there, see onPosition.
                seekTarget = bar.progress / 1000f
                content.removeCallbacks(seekSettled)
                content.postDelayed(seekSettled, SEEK_WAIT_MS)
                engine?.seekTo(seekTarget)
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (menu.isVisible) menu.visibility = View.GONE else leave()
            }
        })

        applyBrightness()
        applyLayout()
        if (!s.hintShown) {
            s.hintShown = true
            Toast.makeText(this, R.string.reader_hint, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        // The place in the book is written down when the reader leaves, however briefly.
        savePosition()
        Library.backUp(this)
        stopTurn()
        pageTurn.release()
        engine?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        engine?.resume()
        if (settingsOpened) {
            settingsOpened = false
            applyBrightness()
            applyLayout()
        }
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
        chapters.onPick = { mark -> e.openContent(marked[mark]) }
        // The name of the chapter a finger is on takes the place of the one being read until it is let go.
        chapters.onPreview = { mark -> if (mark < 0) name(reading, other = false) else name(chapterName(e, mark), other = true) }
    }

    /**
     * Shows how much of the book lies before the place [at], 0..1, and the pages it is on, while
     * the knob of the seek bar is dragged there; [onPosition] shows the place reached once it is let go.
     */
    private fun showPlace(e: ReaderEngine, at: Float) {
        val (ofChapter, ofBook) = e.pagesAt(at)
        if (ofChapter != null) chapterPage.text = getString(R.string.page_of, ofChapter.first, ofChapter.second)
        val book = ofBook ?: bookPages.takeIf { it > 0 }?.let { (1 + (at * (it - 1)).roundToInt()).coerceIn(1, it) to it }
        if (book != null) bookPage.text = getString(R.string.page_of, book.first, book.second)
        percentRead.text = getString(R.string.percent_read, (at * 100).roundToInt())
    }

    /** Shows [text] as the name of a chapter, brighter if it is [other] than the one being read. */
    private fun name(text: String, other: Boolean) {
        chapterName.text = text
        chapterName.setTextColor(ContextCompat.getColor(this, if (other) R.color.gold_light else R.color.cream))
    }

    /** The name of the chapter with the mark [mark]. */
    private fun chapterName(e: ReaderEngine, mark: Int) = e.contents[marked[mark]].first.trim()

    /** The pages the whole book has, for an EPUB about as many as a printed one would; 0 until known. See [onPosition]. */
    private var bookPages = 0

    /** The name of the chapter being read, to go back to after that of another was shown instead. */
    private var reading = ""

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }

    /** Back to the shelf. */
    private fun leave() {
        // The shelf closes the book from the page it was at. It stands upright, so a page read sideways does not fit it.
        // The picture is of the page alone, without the menu and in white light.
        if (fromShelf && content.width < content.height) content.picture()?.let { BookOpening.left = BookOpening.Left(file, it) }
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
        root.setBackgroundColor(s.palette.bg)
        lamp.color = s.lamp
        stopTurn()
        pageTurn.paper = s.palette.bg
        pageNumber.visibility = if (s.showPage) View.VISIBLE else View.GONE
        // In the colour of the text, but fainter.
        pageNumber.setTextColor(s.palette.fg and 0xFFFFFF or 0x99000000.toInt())
        // In paged mode page turns are ours; in scroll mode the content scrolls itself.
        content.consumeAll = !s.vertical
        engine?.applySettings()
    }

    // ---- turning pages ----

    /**
     * Which way a page is being turned, 1 or -1, from asking the engine for it until the engine
     * shows it; 0 otherwise.
     */
    private var turning = 0

    /** The engine has the new page: the sheet is turned over to it. */
    private val startTurn = Runnable {
        pageTurn.turn(turning > 0, content)
        turning = 0
    }

    /** The engine had no other page to go to, or took too long over it. */
    private val dropTurn = Runnable { stopTurn() }

    private fun stopTurn() {
        content.removeCallbacks(startTurn)
        content.removeCallbacks(dropTurn)
        turning = 0
        pageTurn.drop()
    }

    /** Goes to the next page, or with a [direction] of -1 to the one before, turning it like paper if that is asked for. */
    private fun turn(direction: Int) {
        val e = engine ?: return
        stopTurn()
        if (s.slidePages && !s.vertical) {
            // The page being left stays in view until the engine has the other one, see onPosition.
            pageTurn.cover(content)
            turning = direction
            content.postDelayed(dropTurn, TURN_WAIT_MS)
        }
        if (direction > 0) e.next() else e.prev()
    }

    // ---- input ----

    override fun onTap(x: Float, y: Float) {
        if (menu.isVisible) {
            menu.visibility = View.GONE
            return
        }
        val e = engine ?: return
        val w = content.width
        e.tap(x - content.paddingLeft, y - content.paddingTop) { handled ->
            if (!handled) when {
                // The sides of a page that is enlarged are not the sides of the screen.
                !s.tapToTurn || e.zoomed -> openMenu()
                x > w * 0.7f -> turn(1)
                x < w * 0.3f -> turn(-1)
                else -> openMenu()
            }
        }
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
        if (menu.isVisible) return
        turn(if ((direction < 0) != s.reverseSwipe) 1 else -1)
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
            turn(if (forward) 1 else -1)
        }
        return true
    }

    override fun onPosition(
        chapter: Int, fraction: Float, percent: Float, label: String, page: String?,
        pageOfChapter: Pair<Int, Int>?, pageOfBook: Pair<Int, Int>?,
    ) {
        if (turning != 0) {
            // The page is there; a moment later it is drawn too, and the sheet can turn over to it.
            content.removeCallbacks(dropTurn)
            content.removeCallbacks(startTurn)
            content.postDelayed(startTurn, TURN_SETTLE_MS)
        }
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
            savePosition()
        }
        // The page left behind may tell its place once more before the one the knob was taken
        // to is shown: the line does not go back to it meanwhile.
        if (seekTarget >= 0) {
            if (abs(percent - seekTarget) > SEEK_NEAR) {
                skipped = { showPosition(chapter, percent, label, page, pageOfChapter, pageOfBook) }
                return
            }
            settleSeek()
        }
        showPosition(chapter, percent, label, page, pageOfChapter, pageOfBook)
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

    /** Shows the place told by [onPosition] in the menu and at the foot of the page. */
    private fun showPosition(
        chapter: Int, percent: Float, label: String, page: String?,
        pageOfChapter: Pair<Int, Int>?, pageOfBook: Pair<Int, Int>?,
    ) {
        // The chapter is counted as on the line below, by the book's contents, where it has any:
        // the files a book is made of, which the engine counts, also hold its cover and the like.
        val mark = marked.indexOfLast { it <= (engine?.currentContent ?: -1) }
        val where = if (page == null || marked.size < 2) label else listOfNotNull(
            if (mark >= 0) getString(R.string.chapter_of, mark + 1, marked.size) else null,
            page.ifEmpty { null },
        ).joinToString(" · ")
        // Written only when it changes: a new text lays the screen out anew.
        if (pageNumber.text.toString() != where) pageNumber.text = where
        // The menu is closed most of the time. What it would show is written into it only when it
        // opens, so that a page that scrolls does not lay out a menu nobody sees.
        if (!menu.isVisible) {
            behindMenu = { showPosition(chapter, percent, label, page, pageOfChapter, pageOfBook) }
            return
        }
        behindMenu = null
        // What is not known, or does not apply, leaves its place empty, so that the rest stays put.
        inChapter.visibility = if (pageOfChapter == null) View.INVISIBLE else View.VISIBLE
        if (pageOfChapter != null) chapterPage.text = getString(R.string.page_of, pageOfChapter.first, pageOfChapter.second)
        // The pages of the book as the engine counts them, with the text as large as it is. A
        // PDF is at the page it shows; an EPUB that scrolls as far into the pages a printed
        // one would have as into its text.
        val book = pageOfBook ?: bookPages.takeIf { it > 0 }?.let {
            (if (page == null) chapter + 1 else 1 + (percent * (it - 1)).roundToInt()).coerceIn(1, it) to it
        }
        inBook.visibility = if (book == null) View.INVISIBLE else View.VISIBLE
        if (book != null) bookPage.text = getString(R.string.page_of, book.first, book.second)
        percentRead.text = getString(R.string.percent_read, (percent * 100).roundToInt())
        reading = engine?.let { if (mark >= 0) chapterName(it, mark) else "" }.orEmpty()
        name(reading, other = false)
        if (!seeking) seek.progress = (percent * 1000).roundToInt()
        chapters.current = mark
    }

    /** The place last told while the menu was closed, which it shows once it opens; see [openMenu]. */
    private var behindMenu: (() -> Unit)? = null

    private fun openMenu() {
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

        /** How long the engine has to come up with the page asked for, and to draw it once it has. */
        private const val TURN_WAIT_MS = 1500L
        private const val TURN_SETTLE_MS = 60L
        private const val SAVE_EVERY_MS = 30_000L

        /** How near the place the knob was let go the book must tell it is, and how long it has to get there. */
        private const val SEEK_NEAR = 0.03f
        private const val SEEK_WAIT_MS = 1500L
    }
}
