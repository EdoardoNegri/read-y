package com.example.read_y

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity(), ReaderHost, GestureFrame.Listener {

    private lateinit var s: ReaderSettings
    private lateinit var file: File
    private var state = BookState()
    private var engine: ReaderEngine? = null

    private lateinit var root: View
    private lateinit var content: GestureFrame
    private lateinit var menu: View
    private lateinit var warmth: View
    private lateinit var position: TextView
    private lateinit var pageNumber: TextView
    private lateinit var seek: SeekBar
    private lateinit var chapters: ChapterMarks
    private var seeking = false

    /** The settings screen was opened from here, so they may have changed by the time it is left. */
    private var settingsOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = ReaderSettings(this)
        setContentView(R.layout.activity_reader)
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        menu = findViewById(R.id.menu)
        warmth = findViewById(R.id.warmth)
        position = findViewById(R.id.position)
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
        if (!file.isFile) {
            Toast.makeText(this, R.string.book_missing, Toast.LENGTH_LONG).show()
            leave()
            return
        }
        state = Library.state(this, file)
        val opened = try {
            if (Library.isEpub(file)) EpubEngine(this, file, s, state, this)
            else PdfEngine(this, file, s, state, this)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.book_unreadable, file.name), Toast.LENGTH_LONG).show()
            leave()
            return
        }
        engine = opened
        Library.setOpenBook(this, file)
        content.addView(opened.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.listener = this

        val title = findViewById<TextView>(R.id.bookTitle)
        val known = Library.cachedMeta(this, file)
        title.text = known?.title ?: file.nameWithoutExtension
        // A book opened before the shelf has looked at it, e.g. one just handed over by another app.
        if (known == null) Library.io.execute {
            val meta = Library.meta(applicationContext, file)
            runOnUiThread { title.text = meta.title }
        }
        findViewById<ImageButton>(R.id.back).setOnClickListener { leave() }
        setUpChapters(opened)
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            settingsOpened = true
            menu.visibility = View.GONE
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                // While the knob is dragged, the chapter it is over is named.
                val e = engine ?: return
                val mark = chapters.marks.indexOfLast { it <= progress / 1000f + 0.0005f }
                if (fromUser && mark >= 0) position.text = chapterName(e, mark)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                engine?.seekTo(bar.progress / 1000f)
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (menu.visibility == View.VISIBLE) menu.visibility = View.GONE else leave()
            }
        })

        applyBrightness()
        applyWarmth()
        applyLayout()

        if (!s.hintShown) {
            s.hintShown = true
            Toast.makeText(this, R.string.reader_hint, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (settingsOpened) {
            settingsOpened = false
            applyBrightness()
            applyWarmth()
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
        chapters.inset = seek.paddingLeft
        // The line runs through the middle of the seek bar.
        chapters.lineBelow = seek.layoutParams.height / 2f
        chapters.marks = marked.map { places[it] }
        chapters.onPick = { mark -> e.openContent(marked[mark]) }
        // The name of the chapter a finger is on takes the place of the position until it is let go.
        chapters.onPreview = { mark -> position.text = if (mark < 0) shown else chapterName(e, mark) }
    }

    /** The number and name of the chapter with the mark [mark]. */
    private fun chapterName(e: ReaderEngine, mark: Int) =
        getString(R.string.chapter_name, mark + 1, e.contents[marked[mark]].first)

    /** The position as last reported, to go back to after the name of a chapter was shown instead. */
    private var shown = ""

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
     * Back to the shelf. Only this forgets the open book: when the app is closed in any other way
     * the next start goes straight back into it (see MainActivity).
     */
    private fun leave() {
        Library.setOpenBook(this, null)
        finish()
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- applying settings ----

    private fun applyBrightness() {
        val lp = window.attributes
        lp.screenBrightness =
            if (s.brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else (s.brightness / 100f).coerceIn(0.01f, 1f)
        window.attributes = lp
        if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyWarmth() {
        warmth.setBackgroundColor(Color.argb((s.warmth * 1.4f).toInt(), 255, 140, 30))
    }

    private fun applyLayout() {
        requestedOrientation = when (s.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        root.setBackgroundColor(s.palette.bg)
        pageNumber.visibility = if (s.showPage) View.VISIBLE else View.GONE
        // In the colour of the text, but fainter.
        pageNumber.setTextColor(s.palette.fg and 0xFFFFFF or 0x99000000.toInt())
        // In paged mode page turns are ours; in scroll mode the content scrolls itself.
        content.consumeAll = !s.vertical
        engine?.applySettings()
    }

    // ---- input ----

    override fun onTap(x: Float, y: Float) {
        if (menu.visibility == View.VISIBLE) {
            menu.visibility = View.GONE
            return
        }
        val e = engine ?: return
        val w = content.width
        e.tap(x - content.paddingLeft, y - content.paddingTop) { handled ->
            if (!handled) when {
                !s.tapToTurn -> menu.visibility = View.VISIBLE
                x > w * 0.7f -> e.next()
                x < w * 0.3f -> e.prev()
                else -> menu.visibility = View.VISIBLE
            }
        }
    }

    override fun onSwipe(direction: Int) {
        if (menu.visibility == View.VISIBLE) return
        if ((direction < 0) != s.reverseSwipe) engine?.next() else engine?.prev()
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
            if (forward) engine?.next() else engine?.prev()
        }
        return true
    }

    override fun onPosition(chapter: Int, fraction: Float, percent: Float, label: String) {
        state.chapter = chapter
        state.fraction = fraction
        state.percent = percent
        state.lastRead = System.currentTimeMillis()
        Library.saveState(this, file, state)
        // Reaching the end marks the book as finished; taking the mark off again is up to the reader.
        if (percent >= 0.999f && !Library.isFinished(this, file)) Library.setFinished(this, file, true)
        shown = getString(R.string.position_format, label, (percent * 100).roundToInt())
        position.text = shown
        pageNumber.text = label
        if (!seeking) seek.progress = ((engine?.place ?: percent) * 1000).roundToInt()
        chapters.current = marked.indexOfLast { it <= (engine?.currentContent ?: -1) }
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
