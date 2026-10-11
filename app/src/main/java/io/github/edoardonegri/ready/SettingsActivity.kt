package io.github.edoardonegri.ready

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import kotlin.math.roundToInt

/** The reader settings, reachable from the bookshelf without opening a book, with a sample page that follows them. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var s: ReaderSettings
    private lateinit var sample: TextView
    private lateinit var warmth: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = ReaderSettings(this)
        setContentView(R.layout.activity_settings)
        sample = findViewById(R.id.sample)
        warmth = findViewById(R.id.warmth)
        val bar = findViewById<View>(R.id.bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, 0, i.right, i.bottom)
            bar.setPadding(0, i.top, 0, 0)
            insets
        }
        findViewById<TextView>(R.id.barTitle).apply {
            setText(R.string.settings)
            background = PlaqueDrawable(resources.displayMetrics.density, ornate = true)
        }
        for (id in intArrayOf(R.id.search, R.id.more)) findViewById<View>(id).visibility = View.INVISIBLE
        findViewById<View>(R.id.up).apply {
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }
        OptionsPanel(findViewById(R.id.options), s, allLive = true)
            .build(epub = true, ::applyBrightness, ::applyWarmth, ::applyPage)
        applyBrightness()
        applyWarmth()
        applyPage()
    }

    private fun applyBrightness() = s.light(window, mayStayOn = false)

    private fun applyWarmth() = s.warm(warmth)

    /** Lays the sample out the way EpubEngine styles a chapter: 16px text scaled by the zoom, CSS line height. */
    private fun applyPage() {
        val d = resources.displayMetrics.density
        val size = 16f * s.fontSize / 100
        // A book's own fonts are unknown here; a serif face stands in for "Original".
        val family = ReaderSettings.FONTS[s.font.coerceIn(0, ReaderSettings.FONTS.lastIndex)].second
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
