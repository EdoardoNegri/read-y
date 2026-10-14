package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Renders a book of flowing text (see [FlowBook]), one chapter at a time, in a WebView. Horizontal mode lays the chapter out in
 * screen-sized CSS columns and moves between them; vertical mode is a normal scrolling page.
 */
@SuppressLint("SetJavaScriptEnabled")
class FlowEngine(
    private val act: Activity,
    file: File,
    private val s: ReaderSettings,
    start: BookState,
    private val host: ReaderHost,
) : ReaderEngine {

    private val book = FlowBook.open(file)
    private val total: Long
    private val web = WebView(act)
    override val view: View get() = web

    private var chapter = 0
    private var pendingFraction = 0f
    private var pendingAnchor: String? = null
    private var injected = false
    private var ready = false
    private var closed = false

    init {
        if (book.spine.isEmpty()) {
            book.close()
            throw IOException("The book has no readable content")
        }
        total = book.weights.sum().coerceAtLeast(1)
        chapter = start.chapter.coerceIn(0, book.spine.lastIndex)
        pendingFraction = start.fraction

        web.settings.javaScriptEnabled = true
        // A book is read from its file alone; nothing in it may wake the radio.
        web.settings.blockNetworkLoads = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.textZoom = s.fontSize
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
            val stream = if (url.host == HOST && path != null) book.stream(path) else null
            if (stream == null || path == null) {
                return WebResourceResponse(
                    "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)),
                )
            }
            val mime = book.mimeOf(path)
            val text = mime.startsWith("text/") || mime.endsWith("xml")
            return WebResourceResponse(mime, if (text) "utf-8" else null, stream)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            openLink(request.url.toString())
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (injected || closed) return
            injected = true
            view.evaluateJavascript(SCRIPT, null)
            val anchor = pendingAnchor?.let { JSONObject.quote(it) } ?: "null"
            view.evaluateJavascript("__r.apply(${config()},$pendingFraction,$anchor)", null)
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
                reveal()
                val n = book.spine.size
                val inChapter = if (s.vertical) "" else act.getString(R.string.page_n_of, page + 1, pages)
                val label = listOf(act.getString(R.string.chapter_of, chapter + 1, n), inChapter).filter { it.isNotEmpty() }.joinToString(" · ")
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
                host.onPosition(
                    chapter, fraction.toFloat(), bookPercent(shown), label, inChapter,
                    if (s.vertical) null else page + 1 to pages,
                    if (s.vertical) null else inBook(page),
                )
            }
        }

        @JavascriptInterface
        fun onLink(url: String) {
            act.runOnUiThread { if (!closed) openLink(url) }
        }
    }

    private fun reveal() {
        ready = true
        web.alpha = 1f
    }

    private fun load() {
        ready = false
        injected = false
        web.alpha = 0f
        web.setBackgroundColor(s.palette.bg)
        web.isVerticalScrollBarEnabled = s.vertical
        web.loadUrl("https://$HOST/" + Uri.encode(book.spine[chapter], "/"))
    }

    private fun css(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    private fun config(): String {
        val p = s.palette
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
            .toString()
    }

    private fun bookPercent(progress: Float): Float {
        var before = 0L
        for (i in 0 until chapter) before += book.weights[i]
        return ((before + progress * book.weights[chapter]) / total).coerceIn(0f, 1f)
    }

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
        web.evaluateJavascript("window.__r?__r.step($direction,${s.slidePages && s.vertical}):null") { moved ->
            val target = chapter + direction
            if (moved == "false" && !closed && target in book.spine.indices) {
                chapter = target
                pendingFraction = if (direction < 0) 1f else 0f
                load()
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
        counted[chapter] = pages
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
        val (index, into) = place(fraction)
        val pages = chapterPages()[index]
        val page = (into * pages).toInt().coerceIn(0, pages - 1)
        return (page + 1 to pages) to inBook(page, index)
    }

    /** The chapter that the place [fraction] of the whole book, 0..1, lies in, and how far into it, 0..1. */
    private fun place(fraction: Float): Pair<Int, Float> {
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

    override val contentPositions: List<Float>
        get() = book.toc.map { entry ->
            var before = 0L
            for (i in 0 until book.spine.indexOf(entry.path)) before += book.weights[i]
            before.toFloat() / total
        }

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

    /** Stops the page's scripts and drawing while the reader is not on screen. */
    override fun pause() = web.onPause()

    override fun resume() = web.onResume()

    override fun next() = step(1)
    override fun prev() = step(-1)

    override fun applySettings() {
        web.settings.textZoom = s.fontSize
        web.setBackgroundColor(s.palette.bg)
        web.isVerticalScrollBarEnabled = s.vertical
        if (!ready) return
        relayout()
        // Text zoom reflows asynchronously; measure once more when it has settled.
        web.postDelayed({ if (!closed && ready) relayout() }, 350)
    }

    private fun relayout() = web.evaluateJavascript("window.__r&&__r.apply(${config()},null,null)", null)

    override fun seekTo(fraction: Float) {
        atEnd = fraction >= 1f
        val (index, inChapter) = place(fraction)
        if (index == chapter && ready) {
            web.evaluateJavascript("__r.apply(${config()},$inChapter,null)", null)
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
        val d = act.resources.displayMetrics.density
        val ask = if (s.vertical) "linked" else "tap"
        web.evaluateJavascript("window.__r?__r.$ask(${x / d},${y / d}):false") { done(it == "true") }
    }

    override fun close() {
        closed = true
        web.stopLoading()
        web.destroy()
        book.close()
    }

    companion object {
        private const val HOST = "epub.local"

        /** Where on [HOST] the font imported from the phone is served, and the name it goes by there (as in SCRIPT). */
        private const val FONTS_PATH = ".fonts/"
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
    if (c.fontUrl) {
      s += "@font-face{font-family:'ReadyFont';src:url('" + c.fontUrl + "');}";
    }
    if (c.font) {
      s += 'body,body *:not(pre):not(code):not(tt):not(kbd){font-family:' + c.font + '!important;}';
    }
    s += 'img,svg,video,canvas{max-width:100%!important;height:auto;object-fit:contain;break-inside:avoid;'
      + (c.vertical ? '' : 'max-height:' + ih + 'px!important;') + '}';
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
    st.textContent = css(c, R.W, R.H);
    edge.style.left = '0';
    shift(0);
    window.scrollTo(0, 0);
    var ran = false;
    var done = function () {
      if (ran) return;
      ran = true;
      if (c.vertical) {
        window.scrollTo(0, Math.round(frac * maxScroll()));
        R.page = 0;
        R.pages = 1;
        if (anchor && find(anchor)) R.anchor(anchor); else report();
      } else {
        var w = Math.max(document.documentElement.scrollWidth, document.body.scrollWidth);
        R.pages = Math.max(1, Math.ceil((w - 2) / R.W));
        edge.style.left = (R.pages * R.W - 1) + 'px';
        var p = frac >= 1 ? R.pages - 1 : Math.floor(frac * R.pages + 0.001);
        if (anchor && find(anchor)) R.anchor(anchor); else R.go(p);
      }
    };
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
