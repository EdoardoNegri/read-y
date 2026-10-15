package io.github.edoardonegri.ready

import android.app.Activity
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import kotlin.math.abs

/**
 * The reader settings, built into [options] in sections, each a wooden panel under a heading:
 * light, page, text and turning pages. Within a section the sliders come first, then the choices,
 * then the switches.
 */
class OptionsPanel(
    private val options: LinearLayout,
    private val s: ReaderSettings,
    /**
     * Lets the reader pick a font file and imports it, then tells what it is given the font
     * imported, or null if none was; without it, no font can be imported.
     */
    private val importFont: (((File?) -> Unit) -> Unit)? = null,
) {
    private val ctx = options.context

    private fun t(id: Int) = ctx.getString(id)

    private fun percent(value: Int) = ctx.getString(R.string.percent_read, value)

    /** The panel of the section being filled. */
    private var section: LinearLayout = options

    /**
     * Adds a control for every setting; [epub] includes the ones that only affect re-flowable text.
     * The callbacks run after the matching setting was saved, so an open book can follow it.
     */
    fun build(epub: Boolean, onBrightness: () -> Unit = {}, onLayout: () -> Unit = {}) {
        section(t(R.string.opt_light))
        slider(t(R.string.opt_brightness), 0, 100, 1, s.brightness.coerceAtLeast(0), true, { if (it == 0) t(R.string.opt_brightness_system) else percent(it) }) {
            s.brightness = if (it == 0) -1 else it
            onBrightness()
        }
        slider(t(R.string.opt_warmth), 0, 100, 1, s.warmth, true, ::percent) {
            s.warmth = it
            onLayout()
        }
        toggle(t(R.string.opt_dark_mode), s.darkMode) {
            s.darkMode = it
            onLayout()
        }
        toggle(t(R.string.opt_keep_screen_on), s.keepScreenOn) {
            s.keepScreenOn = it
            onBrightness()
        }

        section(t(R.string.opt_page))
        buttons(t(R.string.opt_direction), listOf(t(R.string.opt_direction_pages), t(R.string.opt_direction_scroll)), if (s.vertical) 1 else 0) {
            s.vertical = it == 1
            onLayout()
        }
        buttons(t(R.string.opt_rotation), listOf(t(R.string.opt_rotation_auto), t(R.string.opt_rotation_portrait), t(R.string.opt_rotation_landscape)), s.orientation) {
            s.orientation = it
            onLayout()
        }
        // What is shown around the text, each where the reader puts it, once it is shown at all.
        val around = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun showAround() {
            around.visibility = if (s.showPage) View.VISIBLE else View.GONE
        }
        toggle(t(R.string.opt_show_page), s.showPage) {
            s.showPage = it
            showAround()
            onLayout()
        }
        showAround()
        section.addView(around)
        for (item in InfoItem.entries) place(around, item, onLayout)
        toggle(t(R.string.opt_trim_margins), s.trimMargins) {
            s.trimMargins = it
            onLayout()
        }

        if (epub) {
            section(t(R.string.opt_text))
            slider(t(R.string.opt_size), FlowEngine.MIN_TEXT, FlowEngine.MAX_TEXT, FlowEngine.TEXT_STEP, s.fontSize, false, ::percent) {
                s.fontSize = it
                onLayout()
            }
            slider(t(R.string.opt_line_spacing), 10, 30, 1, s.lineSpacing, false, { "%.1f".format(it / 10f) }) {
                s.lineSpacing = it
                onLayout()
            }
            slider(t(R.string.opt_side_margins), 0, 80, 2, s.marginH, false, { "$it" }) {
                s.marginH = it
                onLayout()
            }
            slider(t(R.string.opt_top_bottom_margins), 0, 80, 2, s.marginV, false, { "$it" }) {
                s.marginV = it
                onLayout()
            }
            label(t(R.string.opt_font))
            fonts = BrassDropdown(ctx) { pickFont(it, onLayout) }
            section.addView(fonts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(4) })
            showFonts()
            slider(t(R.string.opt_hyphenation), 0, ReaderSettings.HYPHENATION_MAX, 1, s.hyphenation, false, { t(HYPHENATION[it]) }) {
                s.hyphenation = it
                onLayout()
            }
        }

        section(t(R.string.opt_turning))
        toggle(t(R.string.opt_like_paper), s.slidePages) { s.slidePages = it }
        toggle(t(R.string.opt_tap_sides), s.tapToTurn) { s.tapToTurn = it }
        toggle(t(R.string.opt_reverse_swipe), s.reverseSwipe) { s.reverseSwipe = it }
        toggle(t(R.string.opt_volume_up), s.volumeUpNext) { s.volumeUpNext = it }
        // Only where the screen can be drawn at more than one rate.
        val act = ctx as? Activity
        val rates = act?.let { ReaderSettings.refreshRates(it) }.orEmpty()
        if (act != null && rates.size > 1) {
            val values = listOf(0) + rates
            val labels = listOf(t(R.string.refresh_auto)) + rates.map { ctx.getString(R.string.refresh_hz, it) }
            val now = values.indexOf(s.refreshRate).takeIf { it >= 0 } ?: values.indices.minBy { abs(values[it] - s.refreshRate) }
            choice(t(R.string.opt_refresh_rate), labels, now) {
                s.refreshRate = values[it]
                s.refresh(act)
            }
        }
    }

    /** A line of [into] for [item], with where on the page it is shown to choose. */
    private fun place(into: LinearLayout, item: InfoItem, onLayout: () -> Unit) {
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, 0)
        row.addView(
            TextView(ctx).apply {
                text = t(item.label)
                textSize = 15f
                typeface = Typeface.SERIF
                setTextColor(color(R.color.cream))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val where = BrassDropdown(ctx) {
            s.setInfoAt(item, it)
            onLayout()
        }
        row.addView(where, LinearLayout.LayoutParams(dp(PLACE_DP), dp(40)).apply { marginStart = dp(10) })
        where.show(InfoPlace.NAMES.map(::t), s.infoAt(item))
        into.addView(row)
    }

    /** [items] to choose one of: brass buttons side by side where there are few, else a menu. */
    private fun choice(name: String, items: List<String>, selected: Int, onChange: (Int) -> Unit) {
        if (items.size <= MAX_BUTTONS) return buttons(name, items, selected, onChange)
        label(name)
        val menu = BrassDropdown(ctx, onChange)
        section.addView(menu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(4) })
        menu.show(items, selected)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(ctx, id)

    /** Starts a section: a wooden panel with [name] as its heading over a brass line. */
    private fun section(name: String) {
        section = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_wood)
            // The grain of the board ends at its round corners.
            clipToOutline = true
            setPadding(dp(16), dp(12), dp(16), dp(10))
            // The knobs of the sliders reach a little into the padding.
            clipToPadding = false
        }
        options.addView(
            section,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) },
        )
        section.addView(TextView(ctx).apply {
            text = name
            textSize = 17f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(color(R.color.gold_light))
        })
        section.addView(
            View(ctx).apply { setBackgroundColor(color(R.color.gold_dark)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6) },
        )
    }

    /** The name of a setting on the left and, for a slider, what it is set to on the right. */
    private fun label(name: String, value: String = ""): TextView {
        val row = LinearLayout(ctx)
        row.setPadding(0, dp(12), 0, dp(2))
        row.addView(
            TextView(ctx).apply {
                text = name
                textSize = 15f
                typeface = Typeface.SERIF
                setTextColor(color(R.color.cream))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val shown = TextView(ctx).apply {
            text = value
            textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(color(R.color.gold_light))
        }
        row.addView(shown)
        section.addView(row)
        return shown
    }

    /** [live] sliders apply while dragging; the others only when released, since they re-flow the book. */
    private fun slider(
        name: String, min: Int, max: Int, step: Int, value: Int, live: Boolean,
        format: (Int) -> String, onChange: (Int) -> Unit,
    ) {
        val shown = label(name, format(value))
        val bar = SeekBar(ctx)
        bar.max = (max - min) / step
        bar.progress = (value.coerceIn(min, max) - min) / step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + progress * step
                shown.text = format(v)
                if (live) onChange(v)
            }

            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {
                if (!live) onChange(min + b.progress * step)
            }
        })
        // The line of the bar starts and ends under the label.
        section.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))
                .apply { setMargins(dp(4) - bar.paddingLeft, 0, dp(4) - bar.paddingRight, 0) },
        )
    }

    /**
     * Brass buttons side by side, one for each of [items], of which the one chosen is held down,
     * as the pair that turns the order of the shelves round is. Pressing another tells [onChange].
     */
    private fun buttons(name: String, items: List<String>, selected: Int, onChange: (Int) -> Unit) {
        label(name)
        val row = LinearLayout(ctx)
        row.setPadding(0, dp(4), 0, dp(2))
        val all = items.mapIndexed { i, text ->
            TextView(ContextThemeWrapper(ctx, R.style.BrassButton), null, 0, R.style.BrassButton).apply {
                this.text = text
                background = PlaqueDrawable(ctx.resources.displayMetrics.density)
                // Three to a row leave less room between the screws of each.
                if (items.size > 2) textSize = 13f
                row.addView(
                    this,
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) marginStart = dp(10) },
                )
            }
        }
        fun hold(chosen: Int) = all.forEachIndexed { i, b -> b.holdBrass(i == chosen) }
        hold(selected.coerceIn(0, all.lastIndex))
        all.forEachIndexed { i, b ->
            b.setOnClickListener {
                hold(i)
                onChange(i)
            }
        }
        section.addView(row)
    }

    /** The menu of the fonts: those the phone has, those imported into the app, and a way to import another. */
    private var fonts: BrassDropdown? = null

    /** Shows the fonts in [fonts], with the one the text is written in picked. */
    private fun showFonts() {
        val builtIn = ReaderSettings.FONTS
        val imported = ReaderSettings.importedFonts(ctx)
        val labels = builtIn.map { t(it.first) } + imported.map { it.nameWithoutExtension } +
            listOfNotNull(if (importFont != null) t(R.string.font_import) else null)
        val faces = builtIn.map { Typeface.create(it.second.ifEmpty { "serif" }, Typeface.NORMAL) } +
            imported.map { ReaderSettings.fontFace(it) } + listOf(null)
        val custom = s.customFont?.let { f -> imported.indexOfFirst { it.name == f.name } } ?: -1
        fonts?.show(labels, if (custom >= 0) builtIn.size + custom else s.font.coerceIn(0, builtIn.lastIndex), faces)
    }

    /** The font [index] of [fonts] was picked: one to write the text in, or the way to import another. */
    private fun pickFont(index: Int, onLayout: () -> Unit) {
        val builtIn = ReaderSettings.FONTS
        val imported = ReaderSettings.importedFonts(ctx)
        when {
            index < builtIn.size -> {
                s.font = index
                s.fontFile = ""
            }
            index < builtIn.size + imported.size -> s.fontFile = imported[index - builtIn.size].name
            else -> {
                // Until a font is imported, the one the text is written in stays picked.
                showFonts()
                importFont?.invoke { font ->
                    if (font != null) s.fontFile = font.name
                    showFonts()
                    onLayout()
                }
                return
            }
        }
        onLayout()
    }

    private fun toggle(name: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = SwitchMaterial(ctx)
        sw.text = name
        sw.textSize = 15f
        sw.typeface = Typeface.SERIF
        sw.setTextColor(color(R.color.cream))
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        section.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(4) })
    }

    private companion object {
        /** The names of the steps of ReaderSettings.hyphenation. */
        val HYPHENATION = intArrayOf(
            R.string.hyphenation_0, R.string.hyphenation_1, R.string.hyphenation_2, R.string.hyphenation_3, R.string.hyphenation_4,
        )

        /** How wide the menu of the place of something shown around the text is, in dp. */
        const val PLACE_DP = 176

        /** The most choices shown as buttons side by side, rather than in a menu. */
        const val MAX_BUTTONS = 4
    }
}
