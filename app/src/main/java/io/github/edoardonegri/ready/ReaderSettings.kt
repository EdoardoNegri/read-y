package io.github.edoardonegri.ready

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Window
import android.view.WindowManager
import androidx.core.content.edit
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Palette(val bg: Int, val fg: Int, val link: Int)

/** Reader preferences, shared by all books. */
class ReaderSettings(ctx: Context) {
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("reader", Context.MODE_PRIVATE).also(::upgrade)

    /** A setting kept under [key], or the name of the property if there is none, read by [get] and written by [put]. */
    private fun <T> setting(
        def: T, key: String?, get: SharedPreferences.(String, T) -> T, put: SharedPreferences.Editor.(String, T) -> Unit,
    ) = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = get(p, key ?: property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = p.edit { put(this, key ?: property.name, value) }
    }

    private fun int(def: Int, key: String? = null) = setting(def, key, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })
    private fun string(def: String) = setting(def, null, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })
    private fun bool(def: Boolean) = setting(def, null, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    /** 1..100, or -1 to follow the system brightness. */
    var brightness by int(-1)

    /**
     * 0..100: how warm the light on the page is, from white daylight to the warm white of a lamp.
     * See [lamp]. Kept under a name of its own since its steps were made finer (see [upgrade]).
     */
    var warmth by int(0, WARMTH)

    /** Light lettering on dark pages. */
    var darkMode by bool(false)

    /**
     * Shows in small print around the text where the page being read is, the time and the like,
     * each where [infoAt] says. The name is that of the page number it once stood for.
     */
    var showPage by bool(false)

    /** Where on the page [item] is shown while [showPage] is on: one of the places of [InfoPlace]. */
    fun infoAt(item: InfoItem) = p.getInt(INFO + item.key, item.default)

    fun setInfoAt(item: InfoItem, place: Int) = p.edit { putInt(INFO + item.key, place) }

    /**
     * How a page looks, but for where in the book it is, as one word: a picture of a page is shown
     * again only for one that looks the same.
     */
    val look: String
        get() = listOf(
            fontSize, lineSpacing, marginH, marginV, font, fontFile, hyphenation, darkMode, warmth, infoLayout, orientation, vertical,
        ).joinToString(":")

    /** Where everything is shown around the text, as one word: a picture of a page is shown again only for one laid out the same. */
    val infoLayout: String get() = if (!showPage) "" else InfoItem.entries.joinToString("") { infoAt(it).toString() }

    /**
     * How readily words are broken at the ends of the lines, with a hyphen: 0 not at all (but
     * where the book itself marks it), up to [HYPHENATION_MAX], where words of a few letters are.
     */
    var hyphenation by int(0)

    /** Leaves out the white margins around the print of a PDF, which is then shown larger. */
    var trimMargins by bool(true)

    /**
     * How many times a second the screen is drawn while reading, where it can be drawn at more
     * than one rate (see [refreshRates]); 0 leaves that to the phone.
     */
    var refreshRate by int(60)

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

    /** Silences the phone while a book is open, see DoNotDisturb. */
    var doNotDisturb by bool(false)
    var hintShown by bool(false)

    /** The colours of the page in white light. */
    val palette: Palette get() = if (darkMode) DARK else LIGHT

    /**
     * The colour of the light on the page at the [warmth] chosen, as on a reader with a warm
     * front light: everything on the page is seen in it, paper, print and pictures alike
     * (see [litPalette]). It follows the colours of a glowing body from daylight
     * down to the warm white of a lamp, in steps that look equal, so the red stays while the blue
     * and some of the green go.
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

    /**
     * The colours of the page as they look in the light of the [lamp]: the reader shows them so,
     * rather than laying the light over the page, which would be one more layer to draw.
     */
    val litPalette: Palette get() = palette.let { Palette(lit(it.bg), lit(it.fg), lit(it.link)) }

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

    /**
     * Has the window of [act] drawn as many times a second as [refreshRate] asks for, as near as
     * the screen can; or as often as the phone sees fit, for 0.
     */
    fun refresh(act: Activity) {
        val lp = act.window.attributes
        val mode = if (refreshRate == 0) null else displayOf(act)?.let { d ->
            sameSize(d).minByOrNull { abs(it.refreshRate - refreshRate) }
        }
        lp.preferredDisplayModeId = mode?.modeId ?: 0
        lp.preferredRefreshRate = mode?.refreshRate ?: 0f
        act.window.attributes = lp
    }

    companion object {
        /** What [warmth] is kept under, and was before its steps were made finer, when it went as far as the light of a candle. */
        const val WARMTH = "lampWarmth"
        const val OLD_WARMTH = "warmth"

        /** How much of the old scale of [warmth] the new one goes as far as. */
        private const val OLD_SHARE = 0.6f

        /** A [warmth] on the old scale, on the new one. */
        fun warmthOf(old: Int) = (old / OLD_SHARE).roundToInt().coerceIn(0, 100)

        /** Brings settings kept by an older version of the app up to date. */
        private fun upgrade(p: SharedPreferences) {
            if (!p.contains(OLD_WARMTH)) return
            p.edit {
                if (!p.contains(WARMTH)) putInt(WARMTH, warmthOf(p.getInt(OLD_WARMTH, 0)))
                remove(OLD_WARMTH)
            }
        }

        private const val INFO = "info_"

        /** The highest step of [hyphenation]. */
        const val HYPHENATION_MAX = 4

        /** The screen [act] is shown on, if it can be told. */
        private fun displayOf(act: Activity): Display? =
            if (Build.VERSION.SDK_INT >= 30) act.display else @Suppress("DEPRECATION") act.windowManager.defaultDisplay

        /** The ways [d] can be drawn with as many dots as it is drawn with now, which differ in how often a second. */
        private fun sameSize(d: Display): List<Display.Mode> {
            val now = d.mode
            return d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
        }

        /** The way the screen of [act] is drawn most often a second, as [refresh] would choose it; 0 if none can be chosen. */
        fun fastestMode(act: Activity): Int =
            displayOf(act)?.let { d -> sameSize(d).maxByOrNull { it.refreshRate }?.modeId } ?: 0

        /** How many times a second the screen of [act] can be drawn, from the least; one rate or none if it cannot be chosen. */
        fun refreshRates(act: Activity): List<Int> =
            displayOf(act)?.let { d -> sameSize(d).map { it.refreshRate.roundToInt() }.distinct().sorted() }.orEmpty()

        /** How bright the phone has its screen, 0..1, as the brightness of a window is told; 1 if that cannot be told. */
        fun systemBrightness(ctx: Context): Float = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            1f
        }

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
                val name = Store.describe(uri).first ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
                val base = name.substringBeforeLast('.').filter { it.isLetterOrDigit() || it in " -_" }.trim().ifEmpty { "Font" }
                val dir = fontsDir(ctx).apply { mkdirs() }
                val f = File(dir, "$base.$kind")
                f.writeBytes(bytes)
                if (fontFace(f) != null) f else null.also { f.delete() }
            }
        } catch (e: Exception) {
            null
        }

        /**
         * A million over the colour temperature of the light at no warmth, 6600 K, and at all of
         * it, 3200 K: the warm white of a lamp, as far as most want the page to go, so that the
         * steps between are fine.
         */
        private const val COOL = 151.5f
        private const val WARM = 310.6f

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
