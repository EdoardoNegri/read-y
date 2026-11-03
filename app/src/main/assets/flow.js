// Injected by FlowEngine into every chapter of a flowing book (EPUB, MOBI, TXT).
// All sizes are whole CSS pixels so that pages never drift.
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
  // The crease between two pages side by side (see css): outside the text, which moves by the page,
  // and not in the way of a tap.
  var crease = document.createElement('div');
  crease.id = 'readyCrease';
  crease.style.cssText = 'position:fixed;top:0;height:100%;pointer-events:none;z-index:2147483646;display:none;';
  document.documentElement.appendChild(crease);
  var R = window.__r = { page: 0, pages: 1, frac: 0, cfg: null, W: 0, H: 0 };
  // Where in the chapter the reader last went, 0..1, which the page is kept at however often it is
  // laid out anew: the page shown then is the one this is on, which may begin earlier. Gone by the
  // start of that page instead, turning the screen to and fro would go back a page each time.
  R.held = 0;

  function css(c, W, H) {
    var ih = H - 2 * c.mv;
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
    // The crease between two pages side by side, down the middle of the screen.
    s += c.crease
      ? '#readyCrease{display:block!important;left:' + (W / 2 - c.creaseW) + 'px!important;width:' + (2 * c.creaseW)
        + 'px!important;background:' + c.crease + '!important;}'
      : '#readyCrease{display:none!important;}';
    if (c.vertical) {
      s += 'html{overflow-x:hidden!important;overflow-y:auto!important;}'
        + 'body{width:auto!important;height:auto!important;min-height:' + H + 'px;}';
    } else {
      // The screen holds cols columns, each as wide as a page of its share of it with its margins.
      var cw = W / (c.cols || 1) - 2 * c.mh;
      s += 'html{width:' + W + 'px!important;height:' + H + 'px!important;overflow:hidden!important;}'
        + 'body{width:' + W + 'px!important;height:' + H + 'px!important;'
        + 'column-width:' + cw + 'px!important;column-gap:' + (2 * c.mh) + 'px!important;'
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

  // How far the page scrolls, by the height of the window as it is: the web view is made another
  // size, as when the screen is turned, before the page is laid out for it (R.H), and its text
  // changes size meanwhile, which is followed (see settle); by the height it had, the place in it
  // would be told wrong, and kept so.
  function maxScroll() {
    return Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
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

  // After the page is laid out, the text may still change size by itself: the web view's text
  // zoom comes into force a moment later, a font or a picture comes in, however late. Whenever
  // anything in it changes size, the pages are counted again, and the place being read is kept,
  // before it is drawn; while nothing does, nothing is done, and the screen is left to rest.
  var watching = null;
  function settle() {
    if (watching) watching.disconnect();
    if (!window.ResizeObserver) return;
    var was = extent();
    watching = new ResizeObserver(function () {
      var now = extent();
      if (now === was) return;
      was = now;
      if (R.cfg.vertical) {
        window.scrollTo(0, Math.round(R.frac * maxScroll()));
        report();
      } else {
        R.pages = count();
        R.go(Math.floor(R.held * R.pages + 0.001));
        was = extent();
      }
    });
    // Each part of the chapter is watched, for the body itself keeps the size of the page as its
    // columns grow.
    watching.observe(document.body);
    for (var el = document.body.firstElementChild; el; el = el.nextElementSibling) watching.observe(el);
  }

  function report() {
    if (R.cfg.vertical) {
      var max = maxScroll(), y = window.scrollY;
      R.frac = R.held = max > 0 ? Math.min(1, y / max) : 0;
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
      R.held = R.frac;
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
        if (anchor && find(anchor)) R.anchor(anchor); else {
          R.go(p);
          R.held = frac;
        }
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
    layout(frac == null ? R.held : frac, anchor);
  };

  // Only the light has changed: the colours are given anew, and the text stays as it is laid out.
  R.tint = function (c) {
    R.cfg = c;
    st.textContent = css(c, R.W, R.H);
    lamp(c.lamp);
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
    R.held = R.frac;
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
      // Not in the warm light the pictures of the book lie in (see lamp): the cover that ends it is seen as it is.
      shutImg.style.setProperty('filter', 'none', 'important');
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
    // A closed book shows no crease between pages.
    crease.style.visibility = on ? 'hidden' : '';
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

  // The word whose letters are under the point, for it to be looked up; null between the lines,
  // in a margin or on a picture. A soft hyphen the book put in it is left out.
  R.word = function (x, y) {
    var at = document.caretRangeFromPoint && document.caretRangeFromPoint(x, y);
    if (!at || at.startContainer.nodeType !== 3) return null;
    var node = at.startContainer, text = node.data;
    // The caret stands between two letters: the one under the point is either before it or after.
    function under(i) {
      if (i < 0 || i >= text.length) return false;
      var r = document.createRange();
      r.setStart(node, i);
      r.setEnd(node, i + 1);
      var b = r.getBoundingClientRect();
      return x >= b.left && x <= b.right && y >= b.top && y <= b.bottom;
    }
    var i = under(at.startOffset) ? at.startOffset : under(at.startOffset - 1) ? at.startOffset - 1 : -1;
    if (i < 0) return null;
    var word = null, words = null;
    if (window.Intl && Intl.Segmenter) {
      // The language the book is in, as XHTML gives it, or as HTML does. A book may give one the
      // browser does not take, such as en_US, and the words are then told apart as by default.
      var html = document.documentElement;
      var lang = html.getAttributeNS('http://www.w3.org/XML/1998/namespace', 'lang') || html.lang || undefined;
      try {
        words = new Intl.Segmenter(lang, { granularity: 'word' });
      } catch (e) {
        words = new Intl.Segmenter(undefined, { granularity: 'word' });
      }
    }
    if (words) {
      var seg = words.segment(text).containing(i);
      if (seg && seg.isWordLike) word = seg.segment;
    } else {
      var letter = /[\p{L}\p{M}\p{N}\u00AD'’]/u, a = i, b = i + 1;
      if (letter.test(text[i])) {
        while (a > 0 && letter.test(text[a - 1])) a--;
        while (b < text.length && letter.test(text[b])) b++;
        word = text.substring(a, b);
      }
    }
    if (!word) return null;
    word = word.replace(/\u00AD/g, '').replace(/^['’]+|['’]+$/g, '');
    return word || null;
  };

  var scrollTimer = null;
  window.addEventListener('scroll', function () {
    if (!R.cfg || !R.cfg.vertical || scrollTimer) return;
    scrollTimer = setTimeout(function () { scrollTimer = null; report(); }, 200);
  });
})();
