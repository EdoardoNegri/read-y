package io.github.edoardonegri.ready

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.text.htmlEncode
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.pow

/**
 * The page of a book as the reader would show it, in the settings, to see what they do: a sample
 * book laid out by the reader's own engine, as wide as the reader's screen is in the turn the
 * reader would give it, with what is shown around the text, and the room the phone's camera takes
 * from the screen left where it would be. It is as tall as there is room for, and shown smaller where the
 * screen is wider than that room, as it is turned on its side. Its pages turn, or scroll, as the
 * reader's do.
 */
class PagePreview @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    FrameLayout(ctx, attrs), ReaderHost, GestureFrame.Listener {

    /** The reader's screen, at its own size, which it is shown smaller than. */
    private val screen = FrameLayout(ctx)
    private val content = GestureFrame(ctx)
    private val info = PageInfoView(ctx)
    private val sheet = PageTurnView(ctx)

    private lateinit var act: Activity
    private lateinit var s: ReaderSettings
    private lateinit var turner: PageTurner
    private lateinit var pace: ReadingPace
    private var engine: FlowEngine? = null

    /** The camera's cutout of the screen held upright, as the window of the settings has it. */
    var cutout = Cutout()
        set(value) {
            field = value
            requestLayout()
        }

    /** How much smaller than the reader's screen it is shown, and the part of that screen it shows. */
    private var scale = 1f
    private var screenW = 0
    private var screenH = 0

    /**
     * The brightness chosen for reading (ReaderSettings.brightness), 1..100, which the page is shown
     * in: as much darker as the screen would be than [surroundings], while the rest of the settings
     * stay as bright as they are, as a window's brightness cannot be had for a part of it. -1
     * follows the phone.
     */
    var brightness by changes(-1, ::invalidate)

    /** How bright the screen the settings are on is, 0..1, as the window brightness is told: what the page can be dimmed from. */
    var surroundings by changes(1f, ::invalidate)
    private val dimmer = Paint()

    /** The frame of brass around the screen. */
    private val rim = Paint().apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(ctx, R.color.gold_dark)
        strokeWidth = RIM_DP * resources.displayMetrics.density
    }

    init {
        for (v in arrayOf(content, info, sheet)) screen.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        sheet.visibility = INVISIBLE
        screen.pivotX = 0f
        screen.pivotY = 0f
        addView(screen)
        setWillNotDraw(false)
        contentDescription = ctx.getString(R.string.preview_page)
    }

    /** Opens the sample book in [act], laid out as [s] says. */
    fun open(act: Activity, s: ReaderSettings) {
        this.act = act
        this.s = s
        turner = PageTurner(act.window, s, content, sheet, info) { engine }
        pace = ReadingPace(act, ReadingPace.FLOWING)
        content.listener = this
        start()
        apply()
    }

    private fun start() {
        val sample = SampleBook(act)
        pages = sample.printedPages
        val e = FlowEngine(act, File(SAMPLE), s, BookState(), this, sample)
        engine = e
        content.addView(e.view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The settings have changed: the page follows them. */
    fun apply() {
        if (!::s.isInitialized) return
        screen.setBackgroundColor(s.litPalette.bg)
        // The back of the sheet lies in the warm light like the page, which it is drawn over.
        sheet.paper = s.lit(s.palette.bg)
        info.color = s.litPalette.fg and 0xFFFFFF or 0x99000000.toInt()
        // In paged mode page turns are ours; in scroll mode the content scrolls itself.
        content.consumeAll = !s.vertical
        turner.stop()
        engine?.applySettings()
        // The screen may be turned on its side now, which is the only way the settings change its size;
        // what is around the text may be elsewhere all the same.
        if (s.orientation != shownTurn) {
            shownTurn = s.orientation
            requestLayout()
        }
        arrange()
        showInfo()
        clock()
    }

    fun pause() {
        turner.stop()
        sheet.release()
        engine?.pause()
        removeCallbacks(tick)
    }

    fun resume() {
        engine?.resume()
        clock()
    }

    fun close() {
        pause()
        engine?.close()
        engine = null
    }

    /** The size of the reader's screen in the turn the reader gives it: on its side if it is to be read in landscape. */
    private fun screenSize(): Pair<Int, Int> {
        val (a, b) = if (Build.VERSION.SDK_INT >= 30) {
            act.windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            DisplayMetrics().also { @Suppress("DEPRECATION") act.windowManager.defaultDisplay.getRealMetrics(it) }
                .let { it.widthPixels to it.heightPixels }
        }
        return if (landscape) max(a, b) to min(a, b) else min(a, b) to max(a, b)
    }

    private val landscape get() = s.orientation == LANDSCAPE

    /** The cutout as it is on the screen in the turn the reader gives it. */
    private fun shownCutout() = if (landscape) cutout.turned(screenSize().second) else cutout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (!::s.isInitialized || w == 0 || h == 0) return
        val (sw, sh) = screenSize()
        val room = RIM_DP * 2 * resources.displayMetrics.density
        // As wide as the screen, shown smaller if there is not room for that; all of it if it
        // fits so, else as much of it as there is room for, from the top.
        scale = min(1f, (w - room) / sw)
        screenW = sw
        screenH = if (sh * scale <= h - room) sh else ((h - room) / scale).toInt()
        screen.measure(MeasureSpec.makeMeasureSpec(screenW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.EXACTLY))
    }

    /** The turn of the screen, ReaderSettings.orientation, it was last measured for. */
    private var shownTurn = -1

    /** The size [arrange] last arranged what is around the text for. */
    private var arranged = ""

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!::s.isInitialized) return
        screen.layout(0, 0, screenW, screenH)
        screen.scaleX = scale
        screen.scaleY = scale
        screen.translationX = (width - screenW * scale) / 2
        screen.translationY = (height - screenH * scale) / 2
        // The page has another size: what is around the text goes elsewhere, in the next frame.
        val size = "${screenW}x$screenH"
        if (size != arranged) post { arrange() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // The reader's screen is only ever as bright as the one it is shown on, or dimmer.
        val relative = if (brightness > 0) (ReaderSettings.lightOf(brightness) / surroundings.coerceAtLeast(MIN_LIGHT)).coerceAtMost(1f) else 1f
        if (relative < 1f) {
            // The colours on the screen are the light they give to the power of 2.2, about.
            dimmer.color = Color.argb(((1 - relative.pow(1 / SCREEN_GAMMA)) * 255).roundToInt(), 0, 0, 0)
            canvas.drawRect(screen.translationX, screen.translationY, screen.translationX + screenW * scale, screen.translationY + screenH * scale, dimmer)
        }
        val half = rim.strokeWidth / 2
        canvas.drawRect(
            screen.translationX - half, screen.translationY - half,
            screen.translationX + screenW * scale + half, screen.translationY + screenH * scale + half, rim,
        )
    }

    /** Works out where what is shown around the text goes, and leaves the text the room for it. */
    private fun arrange() {
        if (screenW == 0 || screenH == 0) return
        arranged = "${screenW}x$screenH"
        val c = shownCutout()
        val (top, bottom) = info.arrange(screenW, screenH, c, s.infoUsed(), infoSide(s, true, resources), engine?.innerMargin ?: 0)
        content.setPadding(c.insets.left, c.insets.top + top, c.insets.right, c.insets.bottom + bottom)
    }

    /** Where the page shown is, as what is shown around the text tells it. */
    private var at = PagePlace()

    /** How many pages a printed copy of the sample would have. */
    private var pages = 1

    private fun showInfo() {
        if (::pace.isInitialized) info.show(infoTexts(act, s, at, pace, pages))
    }

    /** Shows the time anew on the minute, while it is shown. */
    private val tick = object : Runnable {
        override fun run() {
            showInfo()
            postDelayed(this, untilMinute())
        }
    }

    private fun clock() {
        removeCallbacks(tick)
        if (s.showPage && s.infoAt(InfoItem.TIME) != InfoPlace.HIDDEN) postDelayed(tick, untilMinute())
    }

    // ---- the reader's part ----

    override fun onPosition(chapter: Int, fraction: Float, percent: Float, pageOfChapter: Pair<Int, Int>?, pageOfBook: Pair<Int, Int>?) {
        turner.onPosition(chapter, fraction)
        val e = engine ?: return
        // Scrolled, it is as far into the pages a printed copy would have as into its text.
        val book = pageOfBook ?: (1 + (percent * (pages - 1)).roundToInt()).coerceIn(1, pages) to pages
        at = PagePlace(
            book, pageOfChapter, e.contents.getOrNull(e.currentContent)?.first,
            e.chapterEnd.let { (it - percent).coerceAtLeast(0f) }, 1 - percent,
        )
        showInfo()
    }

    override fun hold(then: () -> Unit) = turner.hold(then)

    override fun release() = turner.release()

    /** When the page last had to be opened anew; it is not again soon after, see [reopen]. */
    private var reopened = Long.MIN_VALUE / 2

    override fun reopen() {
        val gone = engine ?: return
        engine = null
        content.removeView(gone.view)
        gone.close()
        val now = SystemClock.elapsedRealtime()
        if (now - reopened < REOPEN_MS) return
        reopened = now
        start()
        apply()
    }

    override fun onTap(x: Float, y: Float) = turner.tap(x, y) {}

    override fun onSwipe(direction: Int) = turner.swipe(direction)

    private companion object {
        /** What the sample book is called, as a file it is not read from. */
        const val SAMPLE = "sample"

        /** ReaderSettings.orientation for landscape. */
        const val LANDSCAPE = 2

        /** The dimmest a screen is taken to be, as the dimmest a window can be told to be (ReaderSettings.light). */
        const val MIN_LIGHT = 0.01f

        /** What a screen's colours are raised to, to make the light they give. */
        const val SCREEN_GAMMA = 2.2f

        /** How wide the brass frame around the screen is, in dp. */
        const val RIM_DP = 2f

        /** A page that cannot be shown again so soon after it was opened anew is left blank, in ms. */
        const val REOPEN_MS = 10_000L
    }
}

/**
 * The sample book of the settings (PagePreview): the first chapters of a novel, from the app's
 * own words (strings.xml), each paragraph apart.
 */
private class SampleBook(ctx: Context) : FlowBook {
    override val title: String = ctx.getString(R.string.sample_title)
    override val author: String = ctx.getString(R.string.sample_author)
    override val published = "1813"
    override val language = "en"

    private val chapters = listOf(
        R.string.sample_chapter_1_title to R.string.sample_chapter_1,
        R.string.sample_chapter_2_title to R.string.sample_chapter_2,
    )
    private val documents = chapters.map { (title, text) ->
        val paragraphs = ctx.getString(text).split("\n\n").joinToString("") { "<p>${it.htmlEncode()}</p>" }
        FlowBook.page("<h2>${ctx.getString(title).htmlEncode()}</h2>$paragraphs").toByteArray()
    }
    override val spine = documents.indices.map { "chapter${it + 1}.html" }
    override val weights = LongArray(documents.size) { documents[it].size.toLong() }
    override val pageWeight get() = PAGE_WEIGHT
    override val toc = spine.mapIndexed { i, path -> TocEntry(ctx.getString(chapters[i].first), path, null, 0) }

    override fun mimeOf(path: String) = "text/html"
    override fun stream(path: String): InputStream? = documents.getOrNull(spine.indexOf(path))?.inputStream()
    override fun coverBitmap(maxWidth: Int): Bitmap? = null
    override fun close() {}

    /** How many pages a printed copy of the sample would have. */
    val printedPages = (weights.sum().toFloat() / PAGE_WEIGHT).roundToInt().coerceAtLeast(1)

    private companion object {
        /** About how much of the text a printed page holds, as for a plain text file (TxtBook). */
        const val PAGE_WEIGHT = 1900
    }
}
