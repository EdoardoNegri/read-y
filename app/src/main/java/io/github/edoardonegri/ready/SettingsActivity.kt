package io.github.edoardonegri.ready

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * The reader settings, reachable from the bookshelf without opening a book, with a page of a
 * sample book that follows them, as the reader would show it (PagePreview). The cross closes
 * them; the arrow leads back to the menu they were opened from, which the one who opened them is
 * told through the result (RESULT_OK).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var s: ReaderSettings
    private lateinit var preview: PagePreview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = ReaderSettings(this)
        setContentView(R.layout.activity_settings)
        preview = findViewById(R.id.preview)
        val bar = findViewById<View>(R.id.bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, 0, i.right, i.bottom)
            bar.padRail(i.top)
            // The sample page leaves the room the camera takes where the reader's screen does.
            preview.cutout = Cutout.of(insets)
            insets
        }
        findViewById<TextView>(R.id.barTitle).apply {
            setText(R.string.settings)
            background = PlaqueDrawable(resources.displayMetrics.density, ornate = true)
        }
        findViewById<View>(R.id.search).visibility = View.INVISIBLE
        // The cross stands at the very right, where the bookshelf has its menu.
        findViewById<ImageButton>(R.id.more).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = getString(R.string.close)
            setOnClickListener { finish() }
        }
        findViewById<View>(R.id.up).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                setResult(RESULT_OK)
                finish()
            }
        }
        OptionsPanel(findViewById(R.id.options), s) { done ->
            imported = done
            pickFont.launch(FONT_TYPES)
        }.build(epub = true, ::applyBrightness, ::applyPage)
        preview.open(this, s)
        applyBrightness()
    }

    override fun onResume() {
        super.onResume()
        preview.resume()
    }

    override fun onPause() {
        preview.pause()
        Library.backUp(this)
        super.onPause()
    }

    override fun onDestroy() {
        preview.close()
        super.onDestroy()
    }

    /** Told the font imported from the file being picked, or null if none was. */
    private var imported: ((File?) -> Unit)? = null

    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = imported ?: return@registerForActivityResult
        imported = null
        if (uri == null) return@registerForActivityResult done(null)
        val app = applicationContext
        Library.io.execute {
            val font = ReaderSettings.importFont(app, uri)
            runOnUiThread {
                if (font == null) Toast.makeText(this, R.string.cannot_read_font, Toast.LENGTH_LONG).show()
                done(font)
            }
        }
    }

    /**
     * The brightness chosen for reading is shown on the sample page alone, see PagePreview.brightness,
     * as long as it is no brighter than the screen the settings are on. Chosen brighter, the settings
     * are as bright as the reader will be, as a page cannot be shown brighter than its screen.
     */
    private fun applyBrightness() {
        val system = ReaderSettings.systemBrightness(this).coerceAtLeast(0.01f)
        val chosen = if (s.brightness > 0) s.brightness / 100f else system
        wanted = if (chosen > system) chosen else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        preview.brightness = s.brightness
        preview.surroundings = maxOf(system, chosen)
        // Dragging the slider asks at every step, and each telling has the phone lay the window out
        // anew: it is told at most every so often, and always of the last brightness.
        val wait = BRIGHTNESS_EVERY_MS - (SystemClock.uptimeMillis() - toldAt)
        preview.removeCallbacks(tell)
        if (wait <= 0) tell.run() else preview.postDelayed(tell, wait)
    }

    /** The brightness the window is to have, see [applyBrightness], and when it was last told. */
    private var wanted = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var toldAt = 0L

    private val tell = Runnable {
        toldAt = SystemClock.uptimeMillis()
        if (window.attributes.screenBrightness != wanted) window.attributes = window.attributes.apply { screenBrightness = wanted }
    }

    private fun applyPage() = preview.apply()

    private companion object {
        /** How often the window is told another brightness while the slider is dragged, in ms. */
        const val BRIGHTNESS_EVERY_MS = 100L

        /** What a font file may say it is; some places call it nothing more than a file. */
        val FONT_TYPES = arrayOf(
            "font/*", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt",
            "application/vnd.ms-opentype", "application/octet-stream",
        )
    }
}
