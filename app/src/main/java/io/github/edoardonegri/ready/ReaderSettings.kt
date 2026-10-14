package io.github.edoardonegri.ready

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.Window
import android.view.WindowManager
import androidx.core.content.edit
import java.io.File
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Palette(val bg: Int, val fg: Int, val link: Int)

/** Reader preferences, shared by all books. */
class ReaderSettings(ctx: Context) {
    private val app = ctx.applicationContext
    private val p = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private fun int(def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getInt(property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            p.edit { putInt(property.name, value) }
    }

    private fun string(def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getString(property.name, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            p.edit { putString(property.name, value) }
    }

    private fun bool(def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getBoolean(property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            p.edit { putBoolean(property.name, value) }
    }

    /** 1..100, or -1 to follow the system brightness. */
    var brightness by int(-1)

    /** 0..100: how warm the light on the page is, from white daylight to the amber of a small lamp. See [lamp]. */
    var warmth by int(0)

    /** Light lettering on dark pages. */
    var darkMode by bool(false)

    /** Shows the page being read in small print at the foot of the page. */
    var showPage by bool(false)

    /** true = continuous vertical scrolling, false = horizontal pages. */
    var vertical by bool(false)

    /** 0 = follow sensor, 1 = portrait, 2 = landscape. */
    var orientation by int(0)

    /** Index into [FONTS]; see [fontFile]. */
    var font by int(0)

    /** The name of the font among [importedFonts] the text is written in, in place of [font]; empty for none. */
    var fontFile by string("")

    /** The font imported from the phone that the text is written in, if one was chosen and is still there. */
    val customFont: File? get() = fontFile.takeIf { it.isNotEmpty() }?.let { File(fontsDir(app), it) }?.takeIf { it.isFile }

    /** The typeface the text is written in, where the book's own fonts cannot stand in for "Original". */
    fun typeface(): Typeface {
        customFont?.let { f -> fontFace(f)?.let { return it } }
        return Typeface.create(FONTS[font.coerceIn(0, FONTS.lastIndex)].second.ifEmpty { "serif" }, Typeface.NORMAL)
    }

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

    /**
     * Pages are turned like paper, instead of changing at once; while scrolling, the text glides
     * on. The name is that of the sliding pages it once stood for.
     */
    var slidePages by bool(true)

    /** A tap on the left or right part of the page turns it; off, every tap brings up the menu. */
    var tapToTurn by bool(true)

    /** Keeps the screen from dimming and switching off while a book is open. */
    var keepScreenOn by bool(false)
    var hintShown by bool(false)

    /** The colours of the page in white light. */
    val palette: Palette get() = if (darkMode) DARK else LIGHT

    /**
     * The colour of the light on the page at the [warmth] chosen, as on a reader with a warm
     * front light: everything on the page is seen in it, paper, print and pictures alike
     * ([LampView] lays it over them). It follows the colours of a glowing body from daylight
     * down to the amber of a candle, in steps that look equal, so the red stays while first the
     * blue and then some of the green goes.
     */
    val lamp: Int
        get() {
            val w = warmth.coerceIn(0, 100) / 100f
            if (w == 0f) return Color.WHITE
            // Colour temperatures are evenly spaced by their inverse.
            val t = 10_000f / (COOL + w * (WARM - COOL))
            val green = ((99.4708f * ln(t) - 161.1196f) / 255.6f).coerceIn(0f, 1f)
            val blue = if (t <= 19f) 0f else ((138.5177f * ln(t - 10f) - 305.0448f) / 252.5f).coerceIn(0f, 1f)
            return Color.rgb(255, (green * 255).roundToInt(), (blue * 255).roundToInt())
        }

    /** [color] as it looks in the light of the [lamp]. */
    fun lit(color: Int): Int = lamp.let {
        Color.rgb(
            Color.red(color) * Color.red(it) / 255, Color.green(color) * Color.green(it) / 255,
            Color.blue(color) * Color.blue(it) / 255,
        )
    }

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

    companion object {
        /** Where the fonts imported from the phone are kept. */
        fun fontsDir(ctx: Context) = File(ctx.filesDir, "fonts")

        /** The fonts imported from the phone, by name. */
        fun importedFonts(ctx: Context): List<File> =
            fontsDir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()

        /** The typeface in the font file [f]; null if it holds none that can be used. */
        fun fontFace(f: File): Typeface? = try {
            if (Build.VERSION.SDK_INT >= 26) Typeface.Builder(f).build() else Typeface.createFromFile(f)
        } catch (e: RuntimeException) {
            null
        }

        /**
         * Copies the font [uri] among the [importedFonts] and returns it there; null if it is not a
         * TrueType or OpenType font. Blocking.
         */
        fun importFont(ctx: Context, uri: Uri): File? = try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val head = bytes?.take(4)?.toByteArray()?.let { String(it, Charsets.ISO_8859_1) }
            val kind = when (head) {
                "OTTO" -> "otf"
                "\u0000\u0001\u0000\u0000", "true", "ttcf" -> "ttf"
                else -> null
            }
            if (bytes == null || kind == null) null else {
                var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.let { shown -> name = shown }
                }
                val base = name.substringBeforeLast('.').filter { it.isLetterOrDigit() || it in " -_" }.trim().ifEmpty { "Font" }
                val dir = fontsDir(ctx).apply { mkdirs() }
                val f = File(dir, "$base.$kind")
                f.writeBytes(bytes)
                if (fontFace(f) != null) f else null.also { f.delete() }
            }
        } catch (e: Exception) {
            null
        }

        /** A million over the colour temperature of the light at no warmth, 6600 K, and at all of it, 2400 K. */
        private const val COOL = 151.5f
        private const val WARM = 416.7f

        private val LIGHT = Palette(0xFFFFFFFF.toInt(), 0xFF1A1A1A.toInt(), 0xFF1A5FB4.toInt())

        // True black: on the screens that light every dot by itself, black dots use no power.
        private val DARK = Palette(0xFF000000.toInt(), 0xFFC8C8C8.toInt(), 0xFF8AB4F8.toInt())

        /** Display name to system font family; an empty family keeps the book's own fonts. */
        val FONTS = listOf(
            R.string.font_original to "",
            R.string.font_serif to "serif",
            R.string.font_sans to "sans-serif",
            R.string.font_condensed to "sans-serif-condensed",
            R.string.font_mono to "monospace",
            R.string.font_casual to "casual",
            R.string.font_cursive to "cursive",
        )
    }
}
