package io.github.edoardonegri.ready

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Palette(val bg: Int, val fg: Int, val link: Int)

/** Reader preferences, shared by all books. */
class ReaderSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private fun int(def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getInt(property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            p.edit { putInt(property.name, value) }
    }

    private fun bool(def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getBoolean(property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            p.edit { putBoolean(property.name, value) }
    }

    /** 1..100, or -1 to follow the system brightness. */
    var brightness by int(-1)

    /** 0..100 strength of the warm (orange) light filter. */
    var warmth by int(0)

    /** Light lettering on dark pages. */
    var darkMode by bool(false)

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

    val palette get() = if (darkMode) DARK else LIGHT

    /**
     * Gives [window] the brightness chosen for reading, and keeps the screen on if [mayStayOn] and
     * the reader asked for that.
     */
    fun light(window: Window, mayStayOn: Boolean) {
        val lp = window.attributes
        lp.screenBrightness =
            if (brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else (brightness / 100f).coerceIn(0.01f, 1f)
        window.attributes = lp
        if (mayStayOn && keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Tints [overlay], a view lying over the page, with the warm light chosen. Without any it is
     * taken away altogether, so that the screen is not blended with an invisible layer on every frame.
     */
    fun warm(overlay: View) {
        overlay.visibility = if (warmth > 0) View.VISIBLE else View.GONE
        overlay.setBackgroundColor(Color.argb((warmth * 1.4f).toInt(), 255, 140, 30))
    }

    companion object {
        private val LIGHT = Palette(0xFFFFFFFF.toInt(), 0xFF1A1A1A.toInt(), 0xFF1A5FB4.toInt())

        // True black: on the screens that light every dot by itself, black dots use no power.
        private val DARK = Palette(0xFF000000.toInt(), 0xFFC8C8C8.toInt(), 0xFF8AB4F8.toInt())

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
