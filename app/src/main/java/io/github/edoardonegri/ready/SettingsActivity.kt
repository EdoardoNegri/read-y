package io.github.edoardonegri.ready

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import kotlin.math.roundToInt

/**
 * The reader settings, reachable from the bookshelf without opening a book, with a sample page
 * that follows them. The cross closes them; the arrow leads back to the menu they were opened
 * from, which the one who opened them is told through the result (RESULT_OK).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var s: ReaderSettings
    private lateinit var sample: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = ReaderSettings(this)
        setContentView(R.layout.activity_settings)
        sample = findViewById(R.id.sample)
        val bar = findViewById<View>(R.id.bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, 0, i.right, i.bottom)
            bar.padRail(i.top)
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
        OptionsPanel(findViewById(R.id.options), s, allLive = true)
            .build(epub = true, ::applyBrightness, ::applyPage)
        applyBrightness()
        applyPage()
    }

    override fun onPause() {
        Library.backUp(this)
        super.onPause()
    }

    private fun applyBrightness() = s.light(window, mayStayOn = false)

    /** Lays the sample out the way FlowEngine styles a chapter: 16px text scaled by the zoom, CSS line height. */
    private fun applyPage() {
        val d = resources.displayMetrics.density
        val size = 16f * s.fontSize / 100
        // A book's own fonts are unknown here; a serif face stands in for "Original".
        val family = ReaderSettings.FONTS[s.font.coerceIn(0, ReaderSettings.FONTS.lastIndex)].second
        findViewById<LampView>(R.id.lamp).color = s.lamp
        sample.setBackgroundColor(s.palette.bg)
        sample.setTextColor(s.palette.fg)
        sample.typeface = Typeface.create(family.ifEmpty { "serif" }, Typeface.NORMAL)
        sample.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        TextViewCompat.setLineHeight(sample, (size * d * s.lineSpacing / 10f).roundToInt().coerceAtLeast(1))
        val h = (s.marginH * d).toInt()
        val v = (s.marginV * d).toInt()
        sample.setPadding(h, v, h, v)
    }
}
