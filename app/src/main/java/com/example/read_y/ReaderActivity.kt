package com.example.read_y

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private lateinit var seek: SeekBar
    private lateinit var options: LinearLayout
    private var seeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = ReaderSettings(this)
        setContentView(R.layout.activity_reader)
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        menu = findViewById(R.id.menu)
        warmth = findViewById(R.id.warmth)
        position = findViewById(R.id.position)
        seek = findViewById(R.id.seek)
        options = findViewById(R.id.options)

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
            finish()
            return
        }
        state = Library.state(this, file)
        val opened = try {
            if (Library.isEpub(file)) EpubEngine(this, file, s, state, this)
            else PdfEngine(this, file, s, state, this)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.book_unreadable, file.name), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        engine = opened
        content.addView(opened.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.listener = this

        findViewById<TextView>(R.id.bookTitle).text =
            Library.cachedMeta(this, file)?.title ?: file.nameWithoutExtension
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {}
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
                if (menu.visibility == View.VISIBLE) menu.visibility = View.GONE else finish()
            }
        })

        buildOptions()
        applyBrightness()
        applyWarmth()
        applyLayout()

        if (!s.hintShown) {
            s.hintShown = true
            Toast.makeText(this, R.string.reader_hint, Toast.LENGTH_LONG).show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
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
        position.text = getString(R.string.position_format, label, (percent * 100).roundToInt())
        if (!seeking) seek.progress = (percent * 1000).roundToInt()
    }

    // ---- settings panel ----

    private fun buildOptions() {
        slider("Brightness", 0, 100, 1, s.brightness.coerceAtLeast(0), true, { if (it == 0) "system" else "$it%" }) {
            s.brightness = if (it == 0) -1 else it
            applyBrightness()
        }
        slider("Light warmth", 0, 100, 1, s.warmth, true, { "$it%" }) {
            s.warmth = it
            applyWarmth()
        }
        choice("Colors", ReaderSettings.THEME_NAMES, s.theme) {
            s.theme = it
            applyLayout()
        }
        choice("Reading direction", listOf("Horizontal (pages)", "Vertical (scroll)"), if (s.vertical) 1 else 0) {
            s.vertical = it == 1
            applyLayout()
        }
        choice("Screen rotation", listOf("Auto", "Portrait", "Landscape"), s.orientation) {
            s.orientation = it
            applyLayout()
        }
        if (Library.isEpub(file)) {
            val fonts = ReaderSettings.FONTS
            choice("Font", fonts.map { it.first }, s.font, fonts.map { Typeface.create(it.second, Typeface.NORMAL) }) {
                s.font = it
                applyLayout()
            }
            slider("Font size", 50, 300, 5, s.fontSize, false, { "$it%" }) {
                s.fontSize = it
                applyLayout()
            }
            slider("Line spacing", 10, 30, 1, s.lineSpacing, false, { "%.1f".format(it / 10f) }) {
                s.lineSpacing = it
                applyLayout()
            }
            slider("Left / right spacing", 0, 80, 2, s.marginH, false, { "$it" }) {
                s.marginH = it
                applyLayout()
            }
            slider("Top / bottom spacing", 0, 80, 2, s.marginV, false, { "$it" }) {
                s.marginV = it
                applyLayout()
            }
        }
        toggle("Reverse swipe direction", s.reverseSwipe) { s.reverseSwipe = it }
        toggle("Volume up for next page", s.volumeUpNext) { s.volumeUpNext = it }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(14), 0, dp(2))
        options.addView(this)
    }

    /** [live] sliders apply while dragging; the others only when released, since they re-flow the book. */
    private fun slider(
        name: String, min: Int, max: Int, step: Int, value: Int, live: Boolean,
        format: (Int) -> String, onChange: (Int) -> Unit,
    ) {
        val title = label("$name: ${format(value)}")
        val bar = SeekBar(this)
        bar.max = (max - min) / step
        bar.progress = (value.coerceIn(min, max) - min) / step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + progress * step
                title.text = "$name: ${format(v)}"
                if (live) onChange(v)
            }

            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {
                if (!live) onChange(min + b.progress * step)
            }
        })
        options.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
    }

    private fun choice(
        name: String, items: List<String>, selected: Int,
        faces: List<Typeface>? = null, onChange: (Int) -> Unit,
    ) {
        label(name)
        val group = MaterialButtonToggleGroup(this)
        group.isSingleSelection = true
        group.isSelectionRequired = true
        val ids = items.mapIndexed { i, text ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.id = View.generateViewId()
            b.text = text
            b.isAllCaps = false
            faces?.let { b.typeface = it[i] }
            group.addView(b)
            b.id
        }
        group.check(ids[selected.coerceIn(0, ids.lastIndex)])
        group.addOnButtonCheckedListener { _, id, checked -> if (checked) onChange(ids.indexOf(id)) }
        val scroller = HorizontalScrollView(this)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(group)
        options.addView(scroller)
    }

    private fun toggle(name: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = SwitchMaterial(this)
        sw.text = name
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        options.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
