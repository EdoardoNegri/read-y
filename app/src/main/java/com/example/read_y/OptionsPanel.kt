package com.example.read_y

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * The reader settings, built into [options] in sections, each a wooden panel under a heading:
 * light, page, text and turning pages. Within a section the sliders come first, then the choices,
 * then the switches.
 */
class OptionsPanel(
    private val options: LinearLayout,
    private val s: ReaderSettings,
    /** Apply every slider while it is dragged, for a page that is cheap to lay out again. */
    private val allLive: Boolean = false,
) {
    private val ctx = options.context

    /** The panel of the section being filled. */
    private var section: LinearLayout = options

    /**
     * Adds a control for every setting; [epub] includes the ones that only affect re-flowable text.
     * The callbacks run after the matching setting was saved, so an open book can follow it.
     */
    fun build(epub: Boolean, onBrightness: () -> Unit = {}, onWarmth: () -> Unit = {}, onLayout: () -> Unit = {}) {
        section("Light")
        slider("Brightness", 0, 100, 1, s.brightness.coerceAtLeast(0), true, { if (it == 0) "system" else "$it%" }) {
            s.brightness = if (it == 0) -1 else it
            onBrightness()
        }
        slider("Warmth", 0, 100, 1, s.warmth, true, { "$it%" }) {
            s.warmth = it
            onWarmth()
        }
        toggle("Dark mode", s.darkMode) {
            s.darkMode = it
            onLayout()
        }
        toggle("Keep the screen on", s.keepScreenOn) {
            s.keepScreenOn = it
            onBrightness()
        }

        section("Page")
        choice("Reading direction", listOf("Pages", "Scroll"), if (s.vertical) 1 else 0) {
            s.vertical = it == 1
            onLayout()
        }
        choice("Screen rotation", listOf("Auto", "Portrait", "Landscape"), s.orientation) {
            s.orientation = it
            onLayout()
        }
        toggle("Show the page number", s.showPage) {
            s.showPage = it
            onLayout()
        }

        if (epub) {
            section("Text")
            slider("Size", 50, 300, 5, s.fontSize, false, { "$it%" }) {
                s.fontSize = it
                onLayout()
            }
            slider("Line spacing", 10, 30, 1, s.lineSpacing, false, { "%.1f".format(it / 10f) }) {
                s.lineSpacing = it
                onLayout()
            }
            slider("Side margins", 0, 80, 2, s.marginH, false, { "$it" }) {
                s.marginH = it
                onLayout()
            }
            slider("Top and bottom margins", 0, 80, 2, s.marginV, false, { "$it" }) {
                s.marginV = it
                onLayout()
            }
            val fonts = ReaderSettings.FONTS
            choice("Font", fonts.map { it.first }, s.font, fonts.map { Typeface.create(it.second, Typeface.NORMAL) }) {
                s.font = it
                onLayout()
            }
        }

        section("Turning pages")
        toggle("Swipe the other way", s.reverseSwipe) { s.reverseSwipe = it }
        toggle("Volume up for the next page", s.volumeUpNext) { s.volumeUpNext = it }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(ctx, id)

    /** Starts a section: a wooden panel with [name] as its heading over a brass line. */
    private fun section(name: String) {
        section = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_wood)
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
                if (live || allLive) onChange(v)
            }

            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {
                if (!live && !allLive) onChange(min + b.progress * step)
            }
        })
        // The line of the bar starts and ends under the label.
        section.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))
                .apply { setMargins(dp(4) - bar.paddingLeft, 0, dp(4) - bar.paddingRight, 0) },
        )
    }

    private fun choice(
        name: String, items: List<String>, selected: Int,
        faces: List<Typeface>? = null, onChange: (Int) -> Unit,
    ) {
        label(name)
        val group = MaterialButtonToggleGroup(ctx)
        group.isSingleSelection = true
        group.isSelectionRequired = true
        val ids = items.mapIndexed { i, text ->
            val b = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.id = View.generateViewId()
            b.text = text
            b.isAllCaps = false
            b.typeface = faces?.get(i) ?: Typeface.SERIF
            group.addView(b)
            b.id
        }
        group.check(ids[selected.coerceIn(0, ids.lastIndex)])
        group.addOnButtonCheckedListener { _, id, checked -> if (checked) onChange(ids.indexOf(id)) }
        val scroller = HorizontalScrollView(ctx)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(group)
        section.addView(scroller)
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
}
