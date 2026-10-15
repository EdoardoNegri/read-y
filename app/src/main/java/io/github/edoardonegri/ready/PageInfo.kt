package io.github.edoardonegri.ready

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.edit
import java.io.File
import java.util.Date
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The places around the text of a page where something can be shown (see [InfoItem]), in a line above it and one below it. */
object InfoPlace {
    const val HIDDEN = 0
    const val TOP_LEFT = 1
    const val TOP_MIDDLE = 2
    const val TOP_RIGHT = 3
    const val BOTTOM_LEFT = 4
    const val BOTTOM_MIDDLE = 5
    const val BOTTOM_RIGHT = 6
    const val COUNT = 7

    /** The names of the places, in order, as the settings offer them. */
    val NAMES = intArrayOf(
        R.string.place_hidden, R.string.place_top_left, R.string.place_top_middle, R.string.place_top_right,
        R.string.place_bottom_left, R.string.place_bottom_middle, R.string.place_bottom_right,
    )
}

/**
 * What can be shown around the text of a page: kept under [key] in the settings, called [label]
 * there, and shown at [default] (an [InfoPlace]) until it is put somewhere else.
 */
enum class InfoItem(val key: String, val label: Int, val default: Int) {
    TIME("time", R.string.info_time, InfoPlace.TOP_RIGHT),
    BOOK_PAGE("bookPage", R.string.info_book_page, InfoPlace.BOTTOM_MIDDLE),
    CHAPTER_LEFT("chapterLeft", R.string.info_chapter_left, InfoPlace.HIDDEN),
    BOOK_LEFT("bookLeft", R.string.info_book_left, InfoPlace.HIDDEN),
    CHAPTER("chapter", R.string.info_chapter, InfoPlace.HIDDEN),
    CHAPTER_PAGE("chapterPage", R.string.info_chapter_page, InfoPlace.HIDDEN),
}

/**
 * Where the page being read is, as far as the information around the text can tell: the page of
 * the book and of its chapter, with how many each has; the name of the chapter; and how much of
 * the book, 0..1, is left to read in the chapter and in all of it. Null for what is not known.
 */
class PagePlace(
    val bookPage: Pair<Int, Int>? = null,
    val chapterPage: Pair<Int, Int>? = null,
    val chapter: String? = null,
    val chapterLeft: Float? = null,
    val bookLeft: Float? = null,
)

/**
 * What is shown at each place of the page, by [InfoPlace], for the page at [at], as [s] has it:
 * the items put at the same place one after the other. [pages] is how many pages the book has,
 * counted as [pace] counts them.
 */
fun infoTexts(ctx: Context, s: ReaderSettings, at: PagePlace, pace: ReadingPace, pages: Int): Array<String?> {
    val texts = arrayOfNulls<String>(InfoPlace.COUNT)
    if (!s.showPage) return texts
    fun left(share: Float?, label: Int) =
        if (share == null || pages <= 0) null else ctx.getString(label, duration(ctx, pace.minutes(share * pages)))
    for (item in InfoItem.entries) {
        val place = s.infoAt(item).takeIf { it in 1 until InfoPlace.COUNT } ?: continue
        val text = when (item) {
            InfoItem.TIME -> DateFormat.getTimeFormat(ctx).format(Date())
            InfoItem.BOOK_PAGE -> at.bookPage?.let { ctx.getString(R.string.page_of, it.first, it.second) }
            InfoItem.CHAPTER_PAGE -> at.chapterPage?.let { ctx.getString(R.string.info_page_in_chapter, it.first, it.second) }
            InfoItem.CHAPTER -> at.chapter?.takeIf { it.isNotBlank() }
            InfoItem.CHAPTER_LEFT -> left(at.chapterLeft, R.string.info_left_in_chapter)
            InfoItem.BOOK_LEFT -> left(at.bookLeft, R.string.info_left_in_book)
        } ?: continue
        texts[place] = texts[place]?.let { "$it · $text" } ?: text
    }
    return texts
}

/** Which places, by [InfoPlace], something is shown at. */
fun ReaderSettings.infoUsed(): BooleanArray {
    val used = BooleanArray(InfoPlace.COUNT)
    if (showPage) for (item in InfoItem.entries) used[infoAt(item).coerceIn(0, InfoPlace.COUNT - 1)] = true
    used[InfoPlace.HIDDEN] = false
    return used
}

/**
 * How far from the sides of the page what is shown around the text keeps, in pixels: as far as
 * the text, in a book of flowing text, and never right at the edge.
 */
fun infoSide(s: ReaderSettings, flowing: Boolean, res: Resources): Int =
    (max(if (flowing) s.marginH.toFloat() else 0f, MIN_SIDE_DP) * res.displayMetrics.density).roundToInt()

private const val MIN_SIDE_DP = 12f

/** How long until the clock shows the next minute, in ms. */
fun untilMinute() = 60_000L - System.currentTimeMillis() % 60_000L + 50

/** [minutes] as the time left to read is told: in whole minutes, and hours past the first. */
private fun duration(ctx: Context, minutes: Float): String {
    val all = ceil(minutes.coerceAtLeast(0f)).toInt().coerceAtLeast(1)
    return when {
        all < 60 -> ctx.getString(R.string.duration_min, all)
        all % 60 == 0 -> ctx.getString(R.string.duration_h, all / 60)
        else -> ctx.getString(R.string.duration_h_min, all / 60, all % 60)
    }
}

/**
 * The parts of the screen a camera takes from it: how far in from each edge the page keeps clear
 * of them ([insets]), the bounds of each ([holes]) and, where the phone tells it, their outline
 * ([path]), in the coordinates of the page.
 */
class Cutout(val insets: Rect = Rect(), val holes: List<Rect> = emptyList(), val path: Path? = null) {
    /** The same cutout on the screen turned a quarter to the left, as for landscape, the upright screen being [w] wide. */
    fun turned(w: Int): Cutout {
        // A point (x, y) of the upright screen comes to (y, w - x).
        val turn = Matrix().apply { setValues(floatArrayOf(0f, 1f, 0f, -1f, 0f, w.toFloat(), 0f, 0f, 1f)) }
        return Cutout(
            Rect(insets.top, insets.right, insets.bottom, insets.left),
            holes.map { Rect(it.top, w - it.right, it.bottom, w - it.left) },
            path?.let { Path(it).apply { transform(turn) } },
        )
    }
}

/**
 * What is shown around the text of the page, in small print, where the reader put each item
 * (ReaderSettings.infoAt): in a line above the text and one below it. Where the phone's camera
 * cuts into the top of the screen, the line above runs through the strip beside it, which the
 * text leaves free; something in the middle that the camera would cover goes under it instead.
 * It lies over the whole page; [arrange] works out where everything goes, and how much room the
 * text must leave for it.
 */
class PageInfoView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val d = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SERIF
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, resources.displayMetrics)
    }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val hollow = RectF()

    /** The colour of the print. */
    var color: Int
        get() = paint.color
        set(value) {
            if (paint.color == value) return
            paint.color = value
            invalidate()
        }

    /**
     * The camera's cutout, drawn in black where the page stands for a screen (PagePreview); none
     * on the screen itself, which has it.
     */
    var shownCutout: Cutout? = null
        set(value) {
            field = value
            invalidate()
        }

    /** What is shown at each place, by [InfoPlace]. */
    private val texts = arrayOfNulls<String>(InfoPlace.COUNT)

    // Where each place is, as [arrange] worked out: the baseline of its line, the stretch of it its
    // text has, and which end of that the text keeps to: -1 the left, 0 the middle, 1 the right.
    private val baselines = FloatArray(InfoPlace.COUNT)
    private val starts = FloatArray(InfoPlace.COUNT)
    private val ends = FloatArray(InfoPlace.COUNT)
    private val keeps = IntArray(InfoPlace.COUNT)

    /**
     * Works out where the places [used] (by [InfoPlace]) go on a page [w] by [h] on a screen with
     * [cutout], the text keeping [side] from the sides of the page and leaving [inner] free at its
     * top and bottom by itself. Returns how much more room the text must leave at its top and at
     * its bottom, for the lines there.
     */
    fun arrange(w: Int, h: Int, cutout: Cutout, used: BooleanArray, side: Int, inner: Int): Pair<Int, Int> {
        val fm = paint.fontMetrics
        val tall = fm.descent - fm.ascent
        val gap = GAP_DP * d
        val strip = (tall + 2 * gap).roundToInt()
        val left = cutout.insets.left + side.toFloat()
        val right = w - cutout.insets.right - side.toFloat()
        var top = 0
        var bottom = 0
        val band = cutout.insets.top
        if (used[InfoPlace.TOP_LEFT] || used[InfoPlace.TOP_MIDDLE] || used[InfoPlace.TOP_RIGHT]) {
            val underBand = band - fm.ascent + gap
            var middleUnder = false
            if (band >= tall + BAND_SPARE_DP * d) {
                // The strip beside the camera: what of it the holes leave, kept clear of the
                // rounded corners of the screen.
                val line = band / 2f - (fm.ascent + fm.descent) / 2
                val edge = max(side.toFloat(), CORNER_DP * d)
                val free = free(edge, w - edge, cutout.holes.filter { it.top < band }, gap)
                val first = free.first()
                val last = free.last()
                val centre = w / 2f
                val middle = free.firstOrNull { centre - it.first >= w / 6f && it.second - centre >= w / 6f }
                if (used[InfoPlace.TOP_MIDDLE] && middle == null) middleUnder = true
                val places = listOf(InfoPlace.TOP_LEFT to first, InfoPlace.TOP_MIDDLE to middle, InfoPlace.TOP_RIGHT to last)
                for (span in free) {
                    val here = places.filter { (place, at) -> at === span && used[place] && !(place == InfoPlace.TOP_MIDDLE && middleUnder) }
                    share(span.first, span.second, line, here.map { it.first }, gap)
                }
            } else {
                share(left, right, band - fm.ascent + gap, (InfoPlace.TOP_LEFT..InfoPlace.TOP_RIGHT).filter { used[it] }, gap)
                top = max(0, strip - inner)
            }
            if (middleUnder) {
                share(left, right, underBand, listOf(InfoPlace.TOP_MIDDLE), gap, third = true)
                top = max(0, strip - inner)
            }
        }
        if (used[InfoPlace.BOTTOM_LEFT] || used[InfoPlace.BOTTOM_MIDDLE] || used[InfoPlace.BOTTOM_RIGHT]) {
            val line = h - cutout.insets.bottom - gap - fm.descent
            share(left, right, line, (InfoPlace.BOTTOM_LEFT..InfoPlace.BOTTOM_RIGHT).filter { used[it] }, gap)
            bottom = max(0, strip - inner)
        }
        invalidate()
        return top to bottom
    }

    /** The stretches of [from]..[to] that [holes], with [gap] kept from each, leave free, from the left; the whole if none do. */
    private fun free(from: Float, to: Float, holes: List<Rect>, gap: Float): List<Pair<Float, Float>> {
        val spans = ArrayList<Pair<Float, Float>>()
        var at = from
        for (hole in holes.sortedBy { it.left }) {
            if (hole.left - gap > at) spans.add(at to min(to, hole.left - gap))
            at = max(at, hole.right + gap)
        }
        if (at < to) spans.add(at to to)
        return spans.ifEmpty { listOf(from to to) }
    }

    /**
     * Shares the stretch [from]..[to] of the line at [line] among [places]: one at the left, one in
     * the middle and one at the right, each as much of it as the others leave, or the middle
     * [third] of it for one in the middle alone.
     */
    private fun share(from: Float, to: Float, line: Float, places: List<Int>, gap: Float, third: Boolean = false) {
        val wide = to - from
        val hasMiddle = places.any { it % 3 == 2 }
        val hasSides = places.any { it % 3 != 2 }
        for (place in places) {
            baselines[place] = line
            when (place % 3) {
                1 -> {
                    starts[place] = from
                    ends[place] = if (hasMiddle) from + wide / 3 - gap else if (places.size > 1) from + wide / 2 - gap / 2 else to
                    keeps[place] = -1
                }
                2 -> {
                    starts[place] = if (hasSides || third) from + wide / 3 else from
                    ends[place] = if (hasSides || third) to - wide / 3 else to
                    keeps[place] = 0
                }
                else -> {
                    starts[place] = if (hasMiddle) to - wide / 3 + gap else if (places.size > 1) from + wide / 2 + gap / 2 else from
                    ends[place] = to
                    keeps[place] = 1
                }
            }
        }
    }

    /** Shows [shown] at the places, by [InfoPlace]; nothing is drawn anew if it is all as it was. */
    fun show(shown: Array<String?>) {
        if (shown.contentEquals(texts)) return
        shown.copyInto(texts)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        shownCutout?.let { c ->
            val outline = c.path
            if (outline != null) canvas.drawPath(outline, black)
            else for (hole in c.holes) {
                val round = min(hole.width(), hole.height()) / 2f
                hollow.set(hole)
                canvas.drawRoundRect(hollow, round, round, black)
            }
        }
        for (place in 1 until InfoPlace.COUNT) {
            val text = texts[place] ?: continue
            val room = ends[place] - starts[place]
            if (room <= 0) continue
            val shown = TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END).toString()
            val wide = paint.measureText(shown)
            val x = when (keeps[place]) {
                -1 -> starts[place]
                1 -> ends[place] - wide
                else -> (starts[place] + ends[place] - wide) / 2
            }
            canvas.drawText(shown, x, baselines[place], paint)
        }
    }

    private companion object {
        const val TEXT_SP = 12f

        /** How far the print keeps from the text and the edges of its line, in dp. */
        const val GAP_DP = 5f

        /** How much taller than the print the strip beside the camera must be for the print to go in it, in dp. */
        const val BAND_SPARE_DP = 2f

        /** How far the print in the strip beside the camera keeps from the sides of the screen, whose corners are round, in dp. */
        const val CORNER_DP = 20f
    }
}

/**
 * How fast books are read, in pages a minute: learnt while they are, from how long each page is
 * read before the next, for each [kind] of book apart, since a page of a comic goes faster than one
 * of a paper. The pages are those ReaderActivity counts for the whole book: for a book of flowing
 * text those of the printed book, whatever size the letters are.
 */
class ReadingPace(ctx: Context, private val kind: String) {
    private val p = ctx.applicationContext.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** Pages a minute, as learnt so far; until then about what most read. */
    private var perMinute = p.getFloat(KEY + kind, DEFAULTS[kind] ?: 1f)

    /** The reading seen since the pace was last learnt from: for how long, and how many pages. */
    private var readMs = 0L
    private var readPages = 0f

    /** The page last read, from 0 and maybe part of the way into it, and when (SystemClock.elapsedRealtime); -1 for none. */
    private var lastPage = -1f
    private var lastAt = 0L

    /** The reader has come to [page] of the book, counted from 0 and maybe part of the way into it, at [now] (SystemClock.elapsedRealtime). */
    fun at(page: Float, now: Long) {
        val took = now - lastAt
        val went = page - lastPage
        // The time a page was open counts if the reader went on from it, not if they went back,
        // jumped far, left it lying, or only glanced at it on the way to another.
        val glanced = went / (took / 60_000f) > perMinute * SKIM
        if (lastPage >= 0 && took in 1..IDLE_MS && went > 0 && went <= JUMP && !glanced) {
            readMs += took
            readPages += went
            if (readMs >= SAMPLE_MS) learn()
        }
        lastPage = page
        lastAt = now
    }

    private fun learn() {
        val seen = readPages / (readMs / 60_000f)
        // Each stretch read moves the pace only part of the way, so that one hurried or slow
        // chapter does not decide it.
        perMinute = (perMinute * (1 - WEIGHT) + seen * WEIGHT).coerceIn(SLOWEST, FASTEST)
        readMs = 0
        readPages = 0f
        p.edit { putFloat(KEY + kind, perMinute) }
    }

    /** The reader stopped for now: the time until they read on is not reading. */
    fun pause() {
        lastPage = -1f
    }

    /** How many minutes [pages] take to read at this pace. */
    fun minutes(pages: Float) = pages / perMinute

    companion object {
        /** The kinds of book whose pace is learnt apart. */
        const val FLOWING = "flow"
        private const val PDF = "pdf"
        private const val COMIC = "comic"

        private const val KEY = "pace_"

        /** About how many pages a minute most read, of each kind of book. */
        private val DEFAULTS = mapOf(FLOWING to 0.8f, PDF to 0.5f, COMIC to 2f)

        /** A page open for longer than this was left lying rather than read, in ms. */
        private const val IDLE_MS = 10 * 60_000L

        /** More pages than this at once is a jump to another place rather than reading on. */
        private const val JUMP = 3f

        /** Pages turned this many times faster than the pace are leafed through rather than read. */
        private const val SKIM = 4f

        /** How long a stretch of reading the pace is learnt from at a time, in ms, and how far it moves the pace. */
        private const val SAMPLE_MS = 2 * 60_000L
        private const val WEIGHT = 0.25f

        private const val SLOWEST = 0.05f
        private const val FASTEST = 30f

        /** The kind of book [file] is, as its pace is learnt. */
        fun kindOf(file: File) = when {
            Library.isFlowing(file) -> FLOWING
            file.extension.equals("pdf", true) -> PDF
            else -> COMIC
        }
    }
}
