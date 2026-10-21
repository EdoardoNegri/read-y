package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders a book of flowing text (see [FlowBook]), one chapter at a time, in a WebView. Horizontal mode lays the chapter out in
 * screen-sized CSS columns and moves between them; vertical mode is a normal scrolling page.
 */
@SuppressLint("SetJavaScriptEnabled")
class FlowEngine(
    private val act: Activity,
    private val file: File,
    private val s: ReaderSettings,
    start: BookState,
    private val host: ReaderHost,
    /** The book to show, if it is not read from [file]: the sample in the settings (PagePreview). */
    private val given: FlowBook? = null,
) : ReaderEngine {

    private val book = given ?: FlowBook.take(file)
    private val total: Long
    private val web = takeWebView(act)
    override val view: View get() = web

    private var chapter = 0
    private var pendingFraction = 0f
    private var pendingAnchor: String? = null
    private var injected = false
    private var ready = false
    private var closed = false

    /**
     * Whether the cover that ends the book is shown over its last page, as if the book were
     * closed; whether the book is to open on it once its last page is laid out; and the page last
     * told, which the cover goes over, and which is where the book goes back to from it.
     */
    private var atCover = false
    private var coverWanted = false
    private var lastPage = 0
    private var lastPages = 1
    private var lastFraction = 0f

    /** The cover as a picture to serve to the page, made once it is asked for. */
    private var coverJpeg: ByteArray? = null

    /**
     * The settings, or the size of the page, changed while the chapter was being laid out by the
     * ones before: it is laid out again before it is shown, see Bridge.onLayout.
     */
    private var stale = false

    init {
        if (book.spine.isEmpty()) {
            book.close()
            throw IOException("The book has no readable content")
        }
        total = book.weights.sum().coerceAtLeast(1)
        // A place written down while the page of the cover was still the first of the spine is one further on in it then.
        chapter = (start.chapter - if (start.version < 2) book.skipped else 0).coerceIn(0, book.spine.lastIndex)
        pendingFraction = start.fraction
        // A book read to its end opens on its cover, which is where the pages that turn end.
        coverWanted = hasCover && start.percent >= Library.FINISHED_AT

        with(web.settings) {
            javaScriptEnabled = true
            // A book is read from its file alone; nothing in it may wake the radio.
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = s.fontSize
        }
        preRaster()
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isHorizontalScrollBarEnabled = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = Client()
        web.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) web.post { if (!closed) applySettings() }
        }
        load()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            // Everything is served from the book itself; nothing ever reaches the network.
            val url = request.url
            val path = url.path?.removePrefix("/")
            if (url.host == HOST && path != null && path.startsWith(FONTS_PATH)) {
                val font = s.customFont?.takeIf { it.name == path.removePrefix(FONTS_PATH) }
                if (font != null) return WebResourceResponse("font/${font.extension}", null, font.inputStream())
            }
            if (url.host == HOST && path == COVER_PATH) {
                val bytes = coverJpeg ?: coverBytes().also { coverJpeg = it }
                return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
            }
            val stream = if (url.host == HOST && path != null) book.stream(path) else null
            if (stream == null || path == null) {
                return WebResourceResponse(
                    "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)),
                )
            }
            val mime = book.mimeOf(path)
            if (mime in MOVING) return still(stream).let { (data, type) -> WebResourceResponse(type ?: mime, null, data) }
            val text = mime.startsWith("text/") || mime.endsWith("xml")
            return WebResourceResponse(mime, if (text) "utf-8" else null, stream)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            openLink(request.url.toString())
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The process the pages were drawn in has ended: the phone let go of it for its memory,
            // or something in the book made it fail. The web view cannot be used any more, and the
            // app would end along with it unless that is seen to here; the reader opens the book anew.
            if (!closed) host.reopen()
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (injected || closed) return
            injected = true
            view.evaluateJavascript(SCRIPT, null)
            val anchor = pendingAnchor?.let { JSONObject.quote(it) } ?: "null"
            view.evaluateJavascript("__r.apply(${laidOut()},$pendingFraction,$anchor)", null)
            pendingAnchor = null
            // If the chapter could not be scripted (e.g. malformed XHTML) still show what loaded.
            view.postDelayed({ if (!closed && !ready) reveal() }, 1500)
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onLayout(page: Int, pages: Int, fraction: Double, progress: Double) {
            act.runOnUiThread {
                if (closed) return@runOnUiThread
                if (stale) {
                    stale = false
                    relayout()
                    return@runOnUiThread
                }
                // A book that opens on its cover has it over the last page before the page is seen at all.
                if (coverWanted && hasCover && chapter == book.spine.lastIndex) cover(true)
                reveal()
                // How much is read is where the page being shown begins, so the first page is
                // none of the book. In the last chapter the pages are spread up to the very end of
                // the line, so that the last page of the book is all of it. A book that is all one
                // page is read once it has been taken to its end, see [atEnd].
                val last = chapter == book.spine.lastIndex
                val onePage = if (s.vertical) fraction == 0.0 && progress >= 1.0 else pages == 1
                shown = when {
                    !last -> fraction.toFloat()
                    onePage -> if (book.spine.size > 1 || atEnd) 1f else 0f
                    s.vertical -> fraction.toFloat()
                    else -> page.toFloat() / (pages - 1)
                }
                if (!s.vertical) measure(pages)
                lastPage = page
                lastPages = pages
                lastFraction = fraction.toFloat()
                if (hasCover && chapter == book.spine.lastIndex) {
                    // The cover goes over the last page, and stays there however the page behind it is laid out again.
                    if (atCover || coverWanted) {
                        coverWanted = false
                        return@runOnUiThread showCover(true)
                    }
                    // Otherwise it is made ready, unseen, for the page after the last to be the cover at once.
                    cover(false)
                }
                host.onPosition(
                    chapter, fraction.toFloat(), (if (s.vertical) null else pagePercent(page)) ?: bookPercent(shown),
                    if (s.vertical) null else page + 1 to pages,
                    if (s.vertical) null else inBook(page),
                )
            }
        }

        @JavascriptInterface
        fun onLink(url: String) {
            act.runOnUiThread { if (!closed) openLink(url) }
        }

        /** The picture of the cover has come into the page, or cannot. */
        @JavascriptInterface
        fun onCover() {
            act.runOnUiThread {
                if (closed) return@runOnUiThread
                coverLoaded = true
                if (ready) tellShown()
            }
        }
    }

    /** Whether the host was told the first page is shown (ReaderHost.shown). */
    private var told = false

    /** Whether the picture of the cover has come into the page, which is told as shown, if it opens on its cover, only once it has. */
    private var coverLoaded = false

    private fun reveal() {
        if (ready) return
        ready = true
        web.alpha = 1f
        // The first page, once all of it is there to be drawn: a book that opens on its cover once
        // the picture of it is there too, which the page fetches only once it shows it.
        if (!(atCover || coverWanted) || coverLoaded) tellShown()
        // The page left, which stood in front while this one was laid out (see load), can go.
        host.release()
    }

    /** Tells the host the first page is shown, once all of it is there to be drawn (ReaderHost.shown). */
    private fun tellShown() {
        if (told) return
        told = true
        web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (!closed) host.shown()
            }
        })
    }

    /**
     * Shows the chapter [chapter]. The web view shows nothing until it is laid out, so a page
     * that was being read stays in front meanwhile (ReaderHost.hold), rather than the screen
     * going blank for a moment.
     */
    private fun load() {
        if (!ready) return show()
        ready = false
        host.hold { if (!closed) show() }
    }

    private fun show() {
        resetZoom()
        // Another chapter is shown without the cover over it, unless it is the last one, to be opened on its cover.
        if (!coverWanted) atCover = false
        ready = false
        injected = false
        stale = false
        applied = ""
        web.alpha = 0f
        web.setBackgroundColor(s.litPalette.bg)
        web.isVerticalScrollBarEnabled = s.vertical
        web.loadUrl("https://$HOST/" + Uri.encode(book.spine[chapter], "/"))
    }

    private fun css(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    private fun config(): String {
        // In the warm light; pictures keep their own colours.
        val p = s.litPalette
        // The page size is passed in rather than read from window.innerWidth, which the WebView
        // widens to the content width once the columns overflow.
        val dm = act.resources.displayMetrics
        val w = if (web.width > 0) web.width else dm.widthPixels
        val h = if (web.height > 0) web.height else dm.heightPixels
        return JSONObject()
            .put("w", (w / dm.density).toInt())
            .put("h", (h / dm.density).toInt())
            .put("bg", css(p.bg))
            .put("fg", css(p.fg))
            .put("link", css(p.link))
            .put("themed", s.darkMode)
            .put("lh", s.lineSpacing / 10.0)
            .put("font", if (s.customFont != null) "'$CUSTOM_FONT'" else ReaderSettings.FONTS[s.font.coerceIn(0, ReaderSettings.FONTS.lastIndex)].second)
            // A font imported from the phone is served alongside the book, see Client.
            .put("fontUrl", s.customFont?.let { "https://$HOST/$FONTS_PATH" + Uri.encode(it.name) } ?: "")
            .put("mh", s.marginH)
            .put("mv", s.marginV)
            .put("vertical", s.vertical)
            // Not read by the script, which the web view's text zoom enlarges the text for: for
            // the page to be laid out anew when it changes, see applied.
            .put("zoom", s.fontSize)
            .put("hy", HYPHENATION.getOrElse(s.hyphenation) { "" })
            // The pictures lie in the warm light too: how much of red, green and blue it has.
            .put("lamp", s.lamp.let { if (it == Color.WHITE) "" else "%.4f %.4f %.4f".format(Locale.US, Color.red(it) / 255f, Color.green(it) / 255f, Color.blue(it) / 255f) })
            // Words are broken by the rules of the language the book is in; one that does not say
            // is taken to be in that of the phone.
            .put("lang", book.language.ifEmpty { Locale.getDefault().toLanguageTag() })
            .toString()
    }

    /**
     * Scrolling, the text around the screen is drawn before it comes into view, so that a quick
     * scroll does not find it blank; pages that turn are drawn one at a time.
     */
    private fun preRaster() {
        web.settings.offscreenPreRaster = s.vertical
    }

    override val innerMargin get() = if (s.vertical) 0 else (s.marginV * act.resources.displayMetrics.density).roundToInt()

    /** How much of the book lies before the chapter [index], in [FlowBook.weights]. */
    private fun before(index: Int) = (0 until index).sumOf { book.weights[it] }

    /**
     * Whether places in the book are counted in pages, as the pages of the book are numbered: not
     * while it scrolls, and not before the pages of any chapter have been counted.
     */
    private val byPages get() = !s.vertical && counted.isNotEmpty()

    override val chapterEnd: Float
        get() {
            if (!byPages) return (before(chapter + 1).toFloat() / total).coerceIn(0f, 1f)
            val pages = chapterPages()
            val n = pages.sum()
            return (pages.take(chapter + 1).sum().toFloat() / n.coerceAtLeast(1)).coerceIn(0f, 1f)
        }

    override var positionsVersion = 0
        private set

    /**
     * How much of the book lies before page [page] of the chapter being shown, counted in pages: the
     * first page of the book is none of it, each page after it is as much as the pages before it
     * make of all of them, and the cover that ends the book is all of it.
     */
    private fun pagePercent(page: Int): Float? {
        val pages = chapterPages()
        val n = pages.sum()
        if (n < 1) return null
        return ((pages.take(chapter).sum() + page).toFloat() / n).coerceIn(0f, 1f)
    }

    /** Where in the whole book, 0..1, the chapter [index] begins. */
    private fun position(index: Int): Float {
        if (!byPages) return before(index.coerceAtLeast(0)).toFloat() / total
        val pages = chapterPages()
        val n = pages.sum()
        return (pages.take(index.coerceAtLeast(0)).sum().toFloat() / n.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    // ---- the cover that ends the book ----

    /** Whether the book ends on its cover, shown over the last page: not while it scrolls, and not for the sample of the settings. */
    override val hasCover get() = given == null && !s.vertical

    /** The cover, as a picture to serve to the page: the screen's width, and no wider than a phone's. */
    private fun coverBytes(): ByteArray {
        val width = minOf(act.resources.displayMetrics.widthPixels, 1080)
        val cover = Library.endCover(act.applicationContext, file, width) { book.coverBitmap(width) }
        return ByteArrayOutputStream().also { cover.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Where the cover stands, in pixels, see [coverAt]; empty for the whole page. */
    private val coverBox = Rect()

    override fun coverAt(box: Rect) {
        coverBox.set(box)
        if (atCover) cover(true)
    }

    /**
     * Tells the page to show the cover over the last page, or to hide it again: it fills its box,
     * and round it the page shows nothing, for the wall of the case behind the web view to be seen.
     */
    private fun cover(on: Boolean) {
        web.setBackgroundColor(if (on) Color.TRANSPARENT else s.litPalette.bg)
        val d = act.resources.displayMetrics.density
        val r = coverBox
        val room = if (r.isEmpty) "null" else "[${r.left / d},${r.top / d},${r.width() / d},${r.height() / d}]"
        web.evaluateJavascript("window.__r&&__r.cover($on,$room)", null)
    }

    /** Shows the cover over the last page, as the book closed, or takes it away again to show the page it was over. */
    private fun showCover(on: Boolean) {
        atCover = on
        cover(on)
        if (on) host.onPosition(chapter, lastFraction, 1f, null, null) else reportPage()
    }

    /** Tells the host the page last laid out, as it is when the cover is not over it. */
    private fun reportPage() = host.onPosition(
        chapter, lastFraction, pagePercent(lastPage) ?: bookPercent(0f), lastPage + 1 to lastPages, inBook(lastPage),
    )

    /** Whether the place [fraction] of the whole book, 0..1, is that of the cover, which comes after the last page. */
    private fun isCover(fraction: Float): Boolean {
        if (!byPages) return false
        val n = chapterPages().sum()
        return (fraction.coerceIn(0f, 1f) * n).roundToInt() >= n
    }

    override fun snap(fraction: Float): Float {
        if (!byPages) return fraction
        val n = chapterPages().sum().coerceAtLeast(1)
        return ((fraction.coerceIn(0f, 1f) * n).roundToInt().coerceIn(0, n)).toFloat() / n
    }

    private fun bookPercent(progress: Float) = ((before(chapter) + progress * book.weights[chapter]) / total).coerceIn(0f, 1f)

    private fun openLink(url: String) {
        val uri = url.toUri()
        if (uri.host == HOST) {
            val index = book.spine.indexOf(uri.path?.removePrefix("/"))
            if (index >= 0) {
                chapter = index
                pendingFraction = 0f
                pendingAnchor = uri.fragment
                load()
            }
        } else if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: Exception) {
                // No app can open the link.
            }
        }
    }

    private fun step(direction: Int) {
        if (!ready) return
        // The cover is the last of the pages: back from it is the last page of the text, and nothing is after it.
        if (atCover) {
            if (direction < 0) showCover(false)
            return
        }
        resetZoom()
        web.evaluateJavascript("window.__r?__r.step($direction,${s.slidePages && s.vertical}):null") { moved ->
            val target = chapter + direction
            if (moved == "false" && !closed) {
                if (target in book.spine.indices) {
                    chapter = target
                    pendingFraction = if (direction < 0) 1f else 0f
                    load()
                } else if (direction > 0 && hasCover) {
                    showCover(true)
                }
            }
        }
    }

    // A part with no name of its own is called after its number.
    override val contents get() = book.toc.mapIndexed { i, it -> it.title.ifBlank { act.getString(R.string.part_n, i + 1) } to it.depth }

    /**
     * The pages of the chapters shown so far, as the text is laid out now: [laidOut] says for
     * which size of text, margins and screen they were counted, and they are forgotten when
     * that changes.
     */
    private val counted = HashMap<Int, Int>()
    private var laidOut = ""

    /** Notes that the chapter being shown has [pages] pages. */
    private fun measure(pages: Int) {
        val layout = "${config()}:${s.fontSize}:${web.width}x${web.height}"
        if (layout != laidOut) counted.clear()
        laidOut = layout
        if (counted[chapter] != pages) {
            counted[chapter] = pages
            positionsVersion++
        }
    }

    /**
     * Which page of the whole book the page [page] of the chapter [chapter] is, and how many the book
     * has, with the text as large as it is now. The chapters shown so far are known by their
     * count; the others are taken to hold as many pages for their length as those do.
     */
    private fun inBook(page: Int, chapter: Int = this.chapter): Pair<Int, Int> {
        val pages = chapterPages()
        return pages.take(chapter).sum() + page + 1 to pages.sum()
    }

    /** How many pages each chapter has: as counted, or as many for its length as those counted have. */
    private fun chapterPages(): List<Int> {
        val perByte = counted.values.sum().toDouble() / counted.keys.sumOf { book.weights[it] }.coerceAtLeast(1)
        return book.spine.indices.map { counted[it] ?: (book.weights[it] * perByte).roundToInt().coerceAtLeast(1) }
    }

    override fun pagesAt(fraction: Float): Pair<Pair<Int, Int>?, Pair<Int, Int>?> {
        // Scrolled, the book has no pages but those of the printed book the host counts itself.
        if (s.vertical || counted.isEmpty()) return null to null
        // The cover is no page of the text.
        if (isCover(fraction)) return null to null
        val (index, into) = place(fraction)
        val pages = chapterPages()[index]
        val page = (into * pages).toInt().coerceIn(0, pages - 1)
        return (page + 1 to pages) to inBook(page, index)
    }

    /** The chapter that the place [fraction] of the whole book, 0..1, lies in, and how far into it, 0..1. */
    private fun place(fraction: Float): Pair<Int, Float> {
        if (byPages) {
            // Counted in pages, the place is the page the fraction is nearest, and lands in the middle of its first part.
            val pages = chapterPages()
            val n = pages.sum()
            if (n >= 1) {
                val page = (fraction.coerceIn(0f, 1f) * n).roundToInt().coerceIn(0, n - 1)
                var first = 0
                for (i in pages.indices) {
                    if (page < first + pages[i]) return i to ((page - first + 0.25f) / pages[i]).coerceIn(0f, 0.999f)
                    first += pages[i]
                }
            }
        }
        val target = fraction.coerceIn(0f, 1f) * total
        var before = 0L
        var index = book.spine.lastIndex
        for (i in book.spine.indices) {
            if (target < before + book.weights[i]) {
                index = i
                break
            }
            before += book.weights[i]
        }
        return index to ((target - before) / book.weights[index]).coerceIn(0f, 0.999f)
    }

    /** How far into the chapter, 0..1, the page being shown begins; 1 for the last page of the book. */
    private var shown = 0f

    /** A book that is all one page has been taken to its end, by the seek bar or by being marked as read. */
    private var atEnd = start.percent >= Library.FINISHED_AT

    override val contentPositions get() = book.toc.map { position(book.spine.indexOf(it.path)) }

    override val currentContent get() = book.toc.indexOfLast { book.spine.indexOf(it.path) <= chapter }

    override fun openContent(index: Int) {
        val entry = book.toc.getOrNull(index) ?: return
        val target = book.spine.indexOf(entry.path)
        if (target < 0) return
        chapter = target
        pendingFraction = 0f
        pendingAnchor = entry.anchor
        load()
    }

    /**
     * Stops the page's drawing while the reader is not on screen, and the timers of scripts,
     * which onPause leaves running (they stop for every web view of the app, and start again
     * with the next reader to come on screen).
     */
    override fun pause() {
        web.onPause()
        web.pauseTimers()
    }

    override fun resume() {
        web.resumeTimers()
        web.onResume()
    }

    override fun next() = step(1)
    override fun prev() = step(-1)

    override fun applySettings() {
        if (web.settings.textZoom != s.fontSize) web.settings.textZoom = s.fontSize
        preRaster()
        if (!atCover) web.setBackgroundColor(s.litPalette.bg)
        web.isVerticalScrollBarEnabled = s.vertical
        if (!ready) {
            // A chapter still loading is laid out by the settings it finds then.
            stale = injected
            return
        }
        // Nothing the page is laid out by has changed, as when the settings were looked at and
        // left as they were: it stays as it is, rather than being laid out anew to the same.
        if (config() == applied) return
        resetZoom()
        relayout()
    }

    /**
     * What the chapter shown was last laid out by (see [config]), or nothing while it is still
     * to be: the page is laid out anew only when that changes, see [applySettings].
     */
    private var applied = ""

    /** [config], noted as what the page is laid out by from now on. */
    private fun laidOut() = config().also { applied = it }

    /**
     * Lays the chapter out anew as the settings say, keeping to the place in it that is being read.
     * The script does so in one go, before the page is drawn again; should the text change size
     * a moment later still, as the web view's text zoom has it do, the script follows that too.
     */
    private fun relayout() = web.evaluateJavascript("window.__r&&__r.apply(${laidOut()},null,null)", null)

    // ---- enlarging the page by pinching ----

    /**
     * How many times larger than the screen the page is shown, as the fingers made it: all of it
     * alike, as if looked at closer, the text laid out as it was. Fingers move it about; turning
     * the page, or laying it out anew, shows it whole again.
     */
    private var zoom = 1f

    /** Where the enlarged page starts, from the top left of the view: never positive. */
    private var tx = 0f
    private var ty = 0f

    override val zoomable get() = true
    override val zoomed get() = zoom > 1f

    override fun zoomBy(factor: Float, x: Float, y: Float) {
        val to = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val by = to / zoom
        tx = x - (x - tx) * by
        ty = y - (y - ty) * by
        zoom = to
        placeZoom()
    }

    override fun panBy(dx: Float, dy: Float) {
        tx += dx
        ty += dy
        placeZoom()
    }

    override fun zoomEnd() {
        if (zoom < SNAP_ZOOM) resetZoom()
    }

    /** Shows the page where [tx] and [ty] and the size say it is, kept on the screen. */
    private fun placeZoom() {
        tx = tx.coerceIn(web.width * (1 - zoom), 0f)
        ty = ty.coerceIn(web.height * (1 - zoom), 0f)
        web.pivotX = 0f
        web.pivotY = 0f
        web.scaleX = zoom
        web.scaleY = zoom
        web.translationX = tx
        web.translationY = ty
    }

    /** Shows the page whole again. */
    private fun resetZoom() {
        zoom = 1f
        tx = 0f
        ty = 0f
        placeZoom()
    }

    override fun seekTo(fraction: Float) {
        resetZoom()
        if (hasCover && isCover(fraction)) {
            // The cover goes over the last page, which is laid out first unless it is shown already.
            val last = book.spine.lastIndex
            if (chapter == last && ready) {
                if (!atCover) showCover(true)
            } else {
                coverWanted = true
                chapter = last
                pendingFraction = 1f
                load()
            }
            return
        }
        // From the cover to another page: the cover is taken away, and the page is gone to.
        if (atCover) {
            atCover = false
            cover(false)
        }
        coverWanted = false
        atEnd = fraction >= 1f
        val (index, inChapter) = place(fraction)
        if (index == chapter && ready) {
            web.evaluateJavascript("__r.apply(${laidOut()},$inChapter,null)", null)
        } else {
            chapter = index
            pendingFraction = inChapter
            load()
        }
    }

    override fun tap(x: Float, y: Float, done: (handled: Boolean) -> Unit) {
        if (!ready) {
            done(false)
            return
        }
        // In vertical mode the WebView receives the touch itself and follows links natively; all
        // that is asked here is whether there is a link, which is not also a tap for the page or the menu.
        // A page enlarged is asked about the place of its own that was tapped.
        val d = act.resources.displayMetrics.density * zoom
        val ask = if (s.vertical) "linked" else "tap"
        web.evaluateJavascript("window.__r?__r.$ask(${(x - tx) / d},${(y - ty) / d}):false") { done(it == "true") }
    }

    override fun close() {
        closed = true
        web.stopLoading()
        web.destroy()
        // The book is kept open a while, in case it is opened again soon; one given is not read again.
        if (given != null) book.close() else KeptBook.keep(file, book)
    }

    companion object {
        private const val HOST = "epub.local"

        /**
         * A web view made ahead, while the shelf has nothing else to do, with an empty page
         * loaded: the first one the app makes takes the better part of a second to start, and
         * its first page as long again, on the thread that draws the screen. It is made on the
         * application, which it is moved over from to the reader that takes it.
         */
        @SuppressLint("StaticFieldLeak")
        private var spare: WebView? = null

        /** Makes [spare] ready for the next book to be opened, unless it is. */
        fun prepare(ctx: Context) {
            if (spare != null) return
            spare = WebView(MutableContextWrapper(ctx.applicationContext)).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        // Its page has gone, as the phone let go of the process it was drawn in;
                        // it cannot be used any more, and another is made when the shelf is next shown.
                        if (spare === view) spare = null
                        view.destroy()
                        return true
                    }
                }
                loadDataWithBaseURL("https://$HOST/", "<html></html>", "text/html", "utf-8", null)
            }
        }

        /** Lets go of the [spare] web view, as the phone asks for its memory back; another is made when the shelf is next shown. */
        fun release() {
            spare?.destroy()
            spare = null
        }

        /** The [spare] web view for the reader [act], or a new one if there is none. */
        private fun takeWebView(act: Activity): WebView {
            val ready = spare ?: return WebView(act)
            spare = null
            (ready.context as MutableContextWrapper).baseContext = act
            return ready
        }

        /** The kinds of picture that can move, which are shown still (see [still]). */
        private val MOVING = setOf("image/gif", "image/webp", "image/png", "image/apng")

        /**
         * The picture in [ins], kept still: one that moves, an animated GIF, WebP or PNG, is
         * turned into its first frame, as a PNG, the type returned along with it; any other is
         * passed on as it is, with no type. A picture that moves would keep the screen drawing,
         * and the battery draining, for as long as its page is open.
         */
        private fun still(ins: InputStream): Pair<InputStream, String?> {
            val bytes = ins.use { it.readBytes() }
            if (!moves(bytes)) return ByteArrayInputStream(bytes) to null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ByteArrayInputStream(bytes) to null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return ByteArrayInputStream(out.toByteArray()) to "image/png"
        }

        /** Whether the picture in [b] has frames to move through. */
        private fun moves(b: ByteArray): Boolean {
            fun at(i: Int, s: String) = i >= 0 && i + s.length <= b.size && s.indices.all { b[i + it] == s[it].code.toByte() }
            fun find(s: String, until: Int = b.size): Int = (0..minOf(b.size, until) - s.length).firstOrNull { at(it, s) } ?: -1
            return when {
                // A GIF that loops says so; one of frames that play once has a control block for each.
                at(0, "GIF8") -> find("NETSCAPE2.0") >= 0 ||
                    (0 until b.size - 2).count { b[it] == 0x21.toByte() && b[it + 1] == 0xF9.toByte() && b[it + 2] == 4.toByte() } > 1
                // An extended WebP says in its flags whether it is animated.
                at(0, "RIFF") && at(8, "WEBP") -> at(12, "VP8X") && b.size > 20 && b[20].toInt() and 0x02 != 0
                // An animated PNG tells so in a chunk before its picture data.
                at(1, "PNG") -> find("acTL", find("IDAT").let { if (it < 0) b.size else it }) >= 0
                else -> false
            }
        }

        /** The most a pinch enlarges the page, and the least it is left enlarged by rather than shown whole. */
        private const val MAX_ZOOM = 4f
        private const val SNAP_ZOOM = 1.1f

        /** The sizes the text can have, in percent, as the slider in the settings gives them (OptionsPanel). */
        const val MIN_TEXT = 50
        const val MAX_TEXT = 300
        const val TEXT_STEP = 5

        /**
         * What a word must have to be broken at the end of a line, by step of
         * ReaderSettings.hyphenation: so many letters at least, and so many before and after the
         * break (CSS hyphenate-limit-chars); none at the first step, where words are not broken.
         */
        private val HYPHENATION = listOf("", "10 4 4", "8 3 3", "6 3 2", "5 2 2")

        /** Where on [HOST] the font imported from the phone is served, and the name it goes by there (as in SCRIPT). */
        private const val FONTS_PATH = ".fonts/"

        /** Where on [HOST] the cover that ends the book is served (as in SCRIPT). */
        private const val COVER_PATH = "__cover.jpg"
        private const val CUSTOM_FONT = "ReadyFont"

        // Injected into every chapter. All sizes are whole CSS pixels so that pages never drift.
        private const val SCRIPT = """
(function () {
  if (window.__r) return;
  var st = document.createElement('style');
  (document.head || document.documentElement).appendChild(st);
  // The last column ends one margin short of a full page, so without this marker at the far
  // edge the view could not scroll all the way to the last page.
  var edge = document.createElement('div');
  edge.style.cssText = 'position:absolute;top:0;left:0;width:1px;height:1px;visibility:hidden;';
  document.body.appendChild(edge);
  // Where the text ends, which moves as the text is laid out otherwise (see extent).
  var tail = document.createElement('div');
  tail.style.cssText = 'height:0;margin:0;padding:0;border:0;';
  document.body.appendChild(tail);
  var R = window.__r = { page: 0, pages: 1, frac: 0, cfg: null, W: 0, H: 0 };

  function css(c, W, H) {
    var ih = H - 2 * c.mv, iw = W - 2 * c.mh;
    var s = 'html{margin:0!important;padding:0!important;background:' + c.bg + '!important;}'
      + 'body{background:' + c.bg + '!important;color:' + c.fg + '!important;margin:0!important;'
      + 'box-sizing:border-box!important;max-width:none!important;'
      + 'padding:' + c.mv + 'px ' + c.mh + 'px!important;overflow-wrap:break-word;}';
    if (c.themed) {
      s += 'body *{color:' + c.fg + '!important;background-color:transparent!important;'
        + 'border-color:' + c.fg + '!important;}a,a *{color:' + c.link + '!important;}';
    }
    s += 'body,body *{line-height:' + c.lh + '!important;}';
    // The pictures are seen in the warm light, as the page is (see lamp).
    if (c.lamp) s += 'img,svg:not(#readyLamp),video,canvas{filter:url(#readyLampLight)!important;}';
    // Words are broken at the ends of the lines as readily as the reader asked: hy says how many
    // letters a word must have, and keep before and after the break; none, and they are broken
    // only where the book itself marks it.
    s += c.hy
      ? 'body,body *{-webkit-hyphens:auto!important;hyphens:auto!important;hyphenate-limit-chars:' + c.hy + '!important;}'
      : 'body,body *{-webkit-hyphens:manual!important;hyphens:manual!important;}';
    // Nothing on the page moves by itself, which would keep the screen drawing (pictures that
    // move are made still as they are loaded).
    s += '*,*::before,*::after{animation:none!important;transition:none!important;}';
    if (c.fontUrl) {
      s += "@font-face{font-family:'ReadyFont';src:url('" + c.fontUrl + "');}";
    }
    if (c.font) {
      s += 'body,body *:not(pre):not(code):not(tt):not(kbd){font-family:' + c.font + '!important;}';
    }
    s += 'img,svg,video,canvas{max-width:100%!important;height:auto;object-fit:contain;break-inside:avoid;'
      + (c.vertical ? '' : 'max-height:' + ih + 'px!important;') + '}';
    // A box that holds nothing but a picture, such as the cover of a book from Project Gutenberg,
    // shows the page through it: its own colour, made to fill the page, would stand out below the
    // picture, which keeps its shape.
    s += '*:has(> img:only-child),*:has(> svg:only-child){background:transparent!important;}';
    if (c.vertical) {
      s += 'html{overflow-x:hidden!important;overflow-y:auto!important;}'
        + 'body{width:auto!important;height:auto!important;min-height:' + H + 'px;}';
    } else {
      s += 'html{width:' + W + 'px!important;height:' + H + 'px!important;overflow:hidden!important;}'
        + 'body{width:' + W + 'px!important;height:' + H + 'px!important;'
        + 'column-width:' + iw + 'px!important;column-gap:' + (2 * c.mh) + 'px!important;'
        + 'column-fill:auto!important;overflow:visible!important;}';
    }
    return s;
  }

  // The warm light on the pictures: a filter that leaves as much of each of their colours as the
  // light has of it, made once and changed with the light. It is drawn into a picture once, as the
  // picture is, and costs nothing as the page stands or turns.
  var lampSvg = null;
  function lamp(rgb) {
    if (!rgb) return;
    var ns = 'http://www.w3.org/2000/svg';
    if (!lampSvg) {
      lampSvg = document.createElementNS(ns, 'svg');
      lampSvg.setAttribute('id', 'readyLamp');
      lampSvg.setAttribute('style', 'position:absolute;top:0;left:0;width:0;height:0;overflow:hidden;');
      var f = document.createElementNS(ns, 'filter');
      f.setAttribute('id', 'readyLampLight');
      f.setAttribute('color-interpolation-filters', 'sRGB');
      f.appendChild(document.createElementNS(ns, 'feColorMatrix')).setAttribute('type', 'matrix');
      lampSvg.appendChild(f);
      document.body.appendChild(lampSvg);
    }
    var v = rgb.split(' ');
    lampSvg.getElementsByTagNameNS(ns, 'feColorMatrix')[0].setAttribute('values',
      v[0] + ' 0 0 0 0 0 ' + v[1] + ' 0 0 0 0 0 ' + v[2] + ' 0 0 0 0 0 1 0');
  }

  // The pages sit side by side in columns; showing one moves the text rather than scrolling the
  // window, because the WebView may widen the window to the whole width of the columns, which
  // leaves nothing to scroll.
  function shift(page) {
    document.body.style.setProperty('transform', page ? 'translateX(' + (-page * R.W) + 'px)' : 'none', 'important');
  }

  function maxScroll() {
    return Math.max(0, document.documentElement.scrollHeight - R.H);
  }

  function find(id) {
    return document.getElementById(id) || document.getElementsByName(id)[0] || null;
  }

  // How many pages the chapter takes, as it is laid out now; shown from the first page meanwhile.
  function count() {
    edge.style.left = '0';
    R.page = 0;
    shift(0);
    var w = Math.max(document.documentElement.scrollWidth, document.body.scrollWidth);
    var pages = Math.max(1, Math.ceil((w - 2) / R.W));
    edge.style.left = (pages * R.W - 1) + 'px';
    return pages;
  }

  // How far the text goes: down the page scrolled, or where on which page it ends.
  function extent() {
    if (R.cfg.vertical) return document.documentElement.scrollHeight;
    var r = tail.getBoundingClientRect();
    return Math.round(r.left + R.page * R.W) + ':' + Math.round(r.top);
  }

  // For a while after the page is laid out, the text may still change size by itself: the web
  // view's text zoom comes into force a moment later, a font or a picture comes in. Each frame
  // until then the pages are counted again, and the place being read is kept, before it is drawn.
  var watching = 0;
  function settle() {
    var id = ++watching, frames = 0, was = extent();
    var look = function () {
      if (id !== watching) return;
      var now = extent();
      if (now !== was) {
        was = now;
        if (R.cfg.vertical) {
          window.scrollTo(0, Math.round(R.frac * maxScroll()));
          report();
        } else {
          var frac = R.frac;
          R.pages = count();
          R.go(Math.floor(frac * R.pages + 0.001));
          was = extent();
        }
      }
      if (++frames < 45) requestAnimationFrame(look);
    };
    requestAnimationFrame(look);
  }

  function report() {
    if (R.cfg.vertical) {
      var max = maxScroll(), y = window.scrollY;
      R.frac = max > 0 ? Math.min(1, y / max) : 0;
      Android.onLayout(0, 1, R.frac, max > 0 ? Math.min(1, (y + R.H) / (max + R.H)) : 1);
    } else {
      R.frac = R.page / R.pages;
      Android.onLayout(R.page, R.pages, R.frac, (R.page + 1) / R.pages);
    }
  }

  R.go = function (p, slide) {
    R.page = Math.max(0, Math.min(R.pages - 1, p));
    if (R.cfg.vertical) {
      window.scrollTo({ left: R.page * R.W, top: 0, behavior: slide ? 'smooth' : 'auto' });
    } else {
      shift(R.page);
    }
    report();
  };

  R.anchor = function (id) {
    var el = find(id);
    if (!el) return;
    var r = el.getBoundingClientRect();
    if (R.cfg.vertical) {
      window.scrollTo(0, Math.max(0, r.top + window.scrollY - R.cfg.mv));
      report();
    } else {
      R.go(Math.floor((r.left + R.page * R.W) / R.W));
    }
  };

  function layout(frac, anchor) {
    var c = R.cfg;
    R.W = c.w;
    R.H = c.h;
    var root = document.documentElement;
    if (c.lang && !root.getAttribute('lang') && !root.getAttribute('xml:lang')) root.setAttribute('lang', c.lang);
    st.textContent = css(c, R.W, R.H);
    lamp(c.lamp);
    // Drawings that move are stopped too.
    var svgs = document.getElementsByTagName('svg');
    for (var i = 0; i < svgs.length; i++) if (svgs[i].pauseAnimations) svgs[i].pauseAnimations();
    var ran = false;
    // The text is set out from its start and the page being read is gone to in one go, so that
    // nothing in between, such as the first page of the chapter, is ever drawn.
    var done = function () {
      if (ran) return;
      ran = true;
      if (c.vertical) {
        edge.style.left = '0';
        shift(0);
        window.scrollTo(0, 0);
        window.scrollTo(0, Math.round(frac * maxScroll()));
        R.page = 0;
        R.pages = 1;
        if (anchor && find(anchor)) R.anchor(anchor); else report();
      } else {
        window.scrollTo(0, 0);
        R.pages = count();
        var p = frac >= 1 ? R.pages - 1 : Math.floor(frac * R.pages + 0.001);
        if (anchor && find(anchor)) R.anchor(anchor); else R.go(p);
      }
      R.laid = true;
      settle();
    };
    // A chapter laid out before is laid out anew at once; a new one once it has been drawn, with
    // its pictures and fonts in place.
    if (R.laid) { done(); return; }
    requestAnimationFrame(function () { requestAnimationFrame(done); });
    setTimeout(done, 300);
  }

  R.apply = function (c, frac, anchor) {
    R.cfg = c;
    layout(frac == null ? R.frac : frac, anchor);
  };

  R.step = function (d, slide) {
    if (R.cfg.vertical) {
      var max = maxScroll(), y = window.scrollY;
      if ((d > 0 && y >= max - 2) || (d < 0 && y <= 2)) return false;
      window.scrollTo({ left: 0, top: Math.max(0, Math.min(max, y + d * Math.round(R.H * 0.88))),
        behavior: slide ? 'smooth' : 'auto' });
      return true;
    }
    var p = R.page + d;
    if (p < 0 || p >= R.pages) return false;
    R.go(p, slide);
    return true;
  };

  // The end of the book: its cover, as if it were closed, over the last page. It lies outside the
  // text, which moves by the page, and is made once, unseen, for the picture to be there when it is shown.
  var shut = null, shutImg = null;
  R.cover = function (on, room) {
    if (!shut) {
      shut = document.createElement('div');
      shut.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;z-index:2147483647;display:none;';
      shutImg = document.createElement('img');
      // The page that opens on the cover is told shown once the picture is there to be seen.
      shutImg.onload = shutImg.onerror = function () { Android.onCover(); };
      shutImg.src = '/__cover.jpg';
      shutImg.setAttribute('style', 'position:absolute;left:0;top:0;width:100%;height:100%;');
      // Cut to the shape of its box about its middle, as the shelf shows a cover.
      shutImg.style.setProperty('object-fit', 'cover', 'important');
      shutImg.style.setProperty('object-position', '50% 50%', 'important');
      shutImg.style.setProperty('max-width', 'none', 'important');
      shutImg.style.setProperty('max-height', 'none', 'important');
      shut.appendChild(shutImg);
      document.documentElement.appendChild(shut);
    }
    // The cover fills its box, standing on the menu while that is over the foot of the screen.
    var at = room || [0, 0, window.innerWidth, window.innerHeight];
    shutImg.style.setProperty('left', at[0] + 'px', 'important');
    shutImg.style.setProperty('top', at[1] + 'px', 'important');
    shutImg.style.setProperty('width', at[2] + 'px', 'important');
    shutImg.style.setProperty('height', at[3] + 'px', 'important');
    // Round it the page is hidden and shows nothing, for the wall of the case behind it to be seen.
    // Set so as to win over the page's own rules, which give the page its paper.
    shut.style.setProperty('background-color', 'transparent', 'important');
    var html = document.documentElement.style, body = document.body ? document.body.style : null;
    if (on) {
      html.setProperty('background', 'transparent', 'important');
      if (body) {
        body.setProperty('background', 'transparent', 'important');
        body.setProperty('visibility', 'hidden', 'important');
      }
    } else {
      html.removeProperty('background');
      if (body) {
        body.removeProperty('background');
        body.removeProperty('visibility');
      }
    }
    shut.style.display = on ? 'block' : 'none';
  };

  R.linked = function (x, y) {
    var el = document.elementFromPoint(x, y);
    while (el && el.nodeType === 1) {
      if ((el.localName || '').toLowerCase() === 'a' && (el.getAttribute('href') || el.getAttribute('xlink:href'))) return true;
      el = el.parentNode;
    }
    return false;
  };

  R.tap = function (x, y) {
    var el = document.elementFromPoint(x, y);
    while (el && el.nodeType === 1) {
      if ((el.localName || '').toLowerCase() === 'a') {
        var href = el.getAttribute('href') || el.getAttribute('xlink:href');
        if (!href) return false;
        var u = new URL(href, location.href);
        if (u.host === location.host && u.pathname === location.pathname && u.hash) {
          R.anchor(decodeURIComponent(u.hash.substring(1)));
        } else {
          Android.onLink(u.href);
        }
        return true;
      }
      el = el.parentNode;
    }
    return false;
  };

  var scrollTimer = null;
  window.addEventListener('scroll', function () {
    if (!R.cfg || !R.cfg.vertical || scrollTimer) return;
    scrollTimer = setTimeout(function () { scrollTimer = null; report(); }, 200);
  });
})();
"""
    }
}
