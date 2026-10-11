package io.github.edoardonegri.ready

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Palette(val bg: Int, val fg: Int, val link: Int)

/** Reader preferences, shared by all books. */
class ReaderSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private fun int(def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getInt(property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            p.edit().putInt(property.name, value).apply()
    }

    private fun bool(def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getBoolean(property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            p.edit().putBoolean(property.name, value).apply()
    }

    /** 1..100, or -1 to follow the system brightness. */
    var brightness by int(-1)

    /** 0..100 strength of the warm (orange) light filter. */
    var warmth by int(0)

    /** Light lettering on dark pages. */
    var darkMode by bool(false)

    /** Index into [THEMES]: light pages, or dark ones in [darkMode]. */
    val theme get() = if (darkMode) 2 else 0

    /** Shows the page being read in small print at the foot of the page. */
    var showPage by bool(false)

    /** true = continuous vertical scrolling, false = horizontal pages. */
    var vertical by bool(false)

    /** 0 = follow sensor, 1 = portrait, 2 = landscape. */
    var orientation by int(0)

    /** Index into [FONTS]. */
    var font by int(0)

    /** Text zoom in percent. */
    var fontSize by int(100)

    /** Line height multiplied by 10. */
    var lineSpacing by int(15)

    /** Left/right and top/bottom page margins in dp. */
    var marginH by int(24)
    var marginV by int(24)

    /** Off: dragging the page from right to left goes forward. On: the other way round. */
    var reverseSwipe by bool(false)
    var volumeUpNext by bool(true)

    /** Pages slide across when turned, instead of changing at once. */
    var slidePages by bool(false)

    /** A tap on the left or right part of the page turns it; off, every tap brings up the menu. */
    var tapToTurn by bool(true)

    /** Keeps the screen from dimming and switching off while a book is open. */
    var keepScreenOn by bool(false)
    var hintShown by bool(false)

    val palette get() = THEMES[theme.coerceIn(0, THEMES.lastIndex)]

    companion object {
        val THEMES = arrayOf(
            Palette(0xFFFFFFFF.toInt(), 0xFF1A1A1A.toInt(), 0xFF1A5FB4.toInt()),
            Palette(0xFFF4ECD8.toInt(), 0xFF3B2F1E.toInt(), 0xFF8A4B08.toInt()),
            Palette(0xFF121212.toInt(), 0xFFC8C8C8.toInt(), 0xFF8AB4F8.toInt()),
        )

        /** Display name to system font family; an empty family keeps the book's own fonts. */
        val FONTS = listOf(
            "Original" to "",
            "Serif" to "serif",
            "Sans" to "sans-serif",
            "Condensed" to "sans-serif-condensed",
            "Mono" to "monospace",
            "Casual" to "casual",
            "Cursive" to "cursive",
        )
    }
}
