/* VibeSync TV: remote (D-pad) navigation, added by the TV app to the VibeSync page.
   Arrows move the highlight to the nearest thing that way, OK selects, Back closes the top-most panel.
   The website itself is unchanged; this only runs inside VibeSync TV. */
(function () {
  if (window.__vsTV) return;
  window.__vsTV = 1;

  /* TV light mode (chosen settings): weather and themes at the lightest effect quality, and world sounds
     (ambience + effects) silent; the official soundtrack, VibeSync score and every feature stay. The page
     reads these settings when it starts, so the first time they change VibeSync reloads once. */
  try {
    var LS = window.localStorage, rawSet = Storage.prototype.setItem;
    var fix = function (k, v) {
      if (k === "vs.ql") return "0";                                     // lightest effect quality, always
      if (k === "vs.sndmix") return "false";                             // no world sounds mixed under the soundtrack
      if (k === "vs.vol2") { var o = {}; try { o = JSON.parse(v) || {}; } catch (e) {} o.amb = 0; o.sfx = 0; return JSON.stringify(o); }
      return v;
    };
    Storage.prototype.setItem = function (k, v) { return rawSet.call(this, k, this === LS ? fix(k, String(v)) : v); };
    var changed = false;
    ["vs.ql", "vs.sndmix", "vs.vol2"].forEach(function (k) {
      var cur = LS.getItem(k), want = fix(k, cur == null ? "{}" : cur);
      if (cur !== want) { rawSet.call(LS, k, want); changed = true; }
    });
    if (changed && !sessionStorage.getItem("vs.tvlite")) { sessionStorage.setItem("vs.tvlite", "1"); location.reload(); return; }
  } catch (e) {}

  document.documentElement.classList.add("tv");

  /* Weather at 30 fps (chosen setting): the theme's weather loop draws every other frame. Its motion is
     time-based, so it moves at the same speed, just with fewer redraws. Everything else keeps 60 fps. */
  var rawRaf = window.requestAnimationFrame.bind(window), rawCaf = window.cancelAnimationFrame.bind(window);
  var halfIds = {}, halfNext = 1e9;
  window.requestAnimationFrame = function (cb) {
    if (typeof cb !== "function" || cb.name !== "step") return rawRaf(cb);   // the weather loop is the page's "step"
    var id = ++halfNext;
    halfIds[id] = rawRaf(function () { halfIds[id] = rawRaf(function (t) { delete halfIds[id]; cb(t); }); });
    return id;
  };
  window.cancelAnimationFrame = function (id) {
    if (Object.prototype.hasOwnProperty.call(halfIds, id)) { rawCaf(halfIds[id]); delete halfIds[id]; } else rawCaf(id);
  };
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var on = function (s) { var e = $(s); return !!e && e.classList.contains("on"); };
  var click = function (e) { if (e) { e.click(); return true; } return false; };
  var REDUCED = matchMedia("(prefers-reduced-motion: reduce)").matches;

  // top-most open panel first: [element, is it open, how to close it]
  var LAYERS = [
    ["#trv", function () { return on("#trv"); }, function () { return click($("#trv [data-trclose]")); }],
    ["#bk", function () { return on("#bk"); }, function () { return click($("#bk [data-bkclose]")); }],
    ["#sndp", function () { return on("#sndp"); }, function () { $("#sndp").classList.remove("on"); return true; }],
    ["#themes", function () { return on("#themes"); }, function () { $("#themes").classList.remove("on"); return true; }],
    ["#sheet", function () { return on("#veil"); }, function () { return click($("#sheet [data-close]")) || click($("#veil")); }],
    ["#xp", function () { return on("#xp"); }, function () { return click($("#xp [data-xpclose]")); }],
    ["#themer", function () { return on("#themer"); }, function () { return click($("#themer [data-closethemer]")); }],
    ["#mixer", function () { return on("#mixer"); }, function () { return click($("#mixer [data-closemixer]")); }],
    ["#search", function () { return on("#search"); }, function () { return click($("#search [data-closesearch]")); }]
  ];
  var OVERLAYS = LAYERS.map(function (l) { return l[0]; }).concat(["#veil", "#reel"]).join(",");
  function top() { for (var i = 0; i < LAYERS.length; i++) { try { if (LAYERS[i][1]()) return LAYERS[i]; } catch (e) {} } return null; }

  var SEL = 'button,a[href],input:not([type="hidden"]),select,textarea,[tabindex]:not([tabindex="-1"])';
  function vis(el) {
    if (el.disabled || el.closest('[hidden],[aria-hidden="true"]')) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    var yt = el.closest(".ytcard"); if (yt && yt.getBoundingClientRect().height < 24) return false;   // soundtrack card folded away
    if (!top() && !el.closest("#app,#bnav,.ytcard")) return false;                                        // nothing loose outside the page
    // (opacity isn't checked: rows below the screen fade in as they scroll into view)
    if (el.checkVisibility && !el.checkVisibility({visibilityProperty: true})) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== "hidden" && cs.display !== "none";
  }
  function items() {
    var l = top(), root = l ? $(l[0]) : document;
    var all = $$(SEL, root).filter(vis);
    if (!l) all = all.filter(function (el) { return !el.closest(OVERLAYS); });
    return all;
  }

  /* smooth scrolling: rows slide only when the highlight nears their edge; the page moves only when the
     highlight leaves the middle of the screen. Each glide is eased and retargeted, never restarted. */
  var anims = new Map();
  function scrollerOf(el, axis) {
    for (var n = el.parentElement; n && n !== document.documentElement; n = n.parentElement) {
      var cs = getComputedStyle(n), o = axis === "x" ? cs.overflowX : cs.overflowY;
      if (!/auto|scroll/.test(o)) continue;
      if (axis === "x" ? n.scrollWidth > n.clientWidth + 2
          : n.scrollHeight > n.clientHeight + 2 && n.clientHeight > innerHeight * 0.5) return n;   // rows aren't the page scroller
    }
    return axis === "y" ? (document.scrollingElement || document.documentElement) : null;
  }
  function aimOf(sc, key) { var a = anims.get(sc); return a ? a.to : sc[key]; }
  function easeTo(sc, axis, target) {
    var key = axis === "x" ? "scrollLeft" : "scrollTop";
    var max = axis === "x" ? sc.scrollWidth - sc.clientWidth : sc.scrollHeight - sc.clientHeight;
    target = Math.max(0, Math.min(max, target));
    if (REDUCED) { sc[key] = target; return; }
    var prev = anims.get(sc); if (prev) rawCaf(prev.raf);
    var a = {from: sc[key], to: target, t0: performance.now(), dur: 420};
    function glideFrame(now) {
      var k = Math.min(1, (now - a.t0) / a.dur), e = 1 - Math.pow(1 - k, 4);
      sc[key] = a.from + (a.to - a.from) * e;
      if (k < 1) a.raf = rawRaf(glideFrame); else anims.delete(sc);
    }
    a.raf = rawRaf(glideFrame); anims.set(sc, a);
  }
  function glide(el) {
    var r = el.getBoundingClientRect(), sx = scrollerOf(el, "x");
    if (sx) {
      var b = sx.getBoundingClientRect(), pad = Math.min(140, b.width * 0.12), shift = aimOf(sx, "scrollLeft") - sx.scrollLeft;
      var left = r.left - shift, right = r.right - shift;
      if (left < b.left + pad) easeTo(sx, "x", aimOf(sx, "scrollLeft") - (b.left + pad - left));
      else if (right > b.right - pad) easeTo(sx, "x", aimOf(sx, "scrollLeft") + (right - (b.right - pad)));
    }
    var sy = el.closest(".deckp") || fixed(el) ? null : scrollerOf(el, "y");          // Swipe mode fits the screen: no scrolling
    if (sy) {
      // Line the row up under the top bar with its own heading showing, instead of centring it (which hid
      // the heading behind the logo bar and showed the next row's heading underneath).
      var H = innerHeight, sh = aimOf(sy, "scrollTop") - sy.scrollTop;
      var blk = blockOf(el), br = blk.getBoundingClientRect();
      var topEdge = barBottom(sy, br.top), botEdge = navTop();
      var bt = br.top - sh, bb = br.bottom - sh, et = r.top - sh, eb = r.bottom - sh;
      var room = botEdge - topEdge - 24;
      if (br.height <= room) {
        if (bt < topEdge + 6 || bb > botEdge - 6) easeTo(sy, "y", aimOf(sy, "scrollTop") + (bt - (topEdge + 14)));
      } else if (et < topEdge + 6) easeTo(sy, "y", aimOf(sy, "scrollTop") + (et - (topEdge + 14)));       // tall block: just keep
      else if (eb > botEdge - 6) easeTo(sy, "y", aimOf(sy, "scrollTop") + (eb - (botEdge - 14)));      // the highlight in view
    }
  }
  /* the row (or block) the highlight is in, together with the heading that belongs to it: the nearest
     heading just above the row, whether it's the row's neighbour or sits a level or two up */
  function blockOf(el) {
    var row = el.closest(".row,.top10,.grid,[data-livebox],.tools,.xtools") || el;
    var rr = row.getBoundingClientRect(), head = null;
    for (var n = row, lvl = 0; n && n !== document.body && lvl < 4 && !head; n = n.parentElement, lvl++) {
      for (var p = n.previousElementSibling, hops = 0; p && hops < 3 && !head; p = p.previousElementSibling, hops++) {
        var h = p.matches("h2,h3,.sec,.h3") ? p : (p.querySelectorAll ? [].slice.call(p.querySelectorAll("h2,.sec")).pop() : null);
        if (!h) continue;
        var hr = h.getBoundingClientRect();
        if (hr.height && hr.bottom <= rr.top + 4 && rr.top - hr.bottom < 160) head = h;
        else if (hr.height) break;                                         // something else is in between: no heading
      }
      if (n.matches && n.matches("[data-livebox],section") && n !== row) { var own = n.querySelector("h2,.sec"); if (own && own.getBoundingClientRect().bottom <= rr.top + 4) head = head || own; }
    }
    if (!head) return row;
    return {getBoundingClientRect: function () {
      var a = head.getBoundingClientRect(), b = row.getBoundingClientRect();
      return {top: Math.min(a.top, b.top), bottom: Math.max(a.bottom, b.bottom), height: Math.max(a.bottom, b.bottom) - Math.min(a.top, b.top)};
    }};
  }
  /* bottom of whatever is pinned at the top of the screen (logo bar, tabs) */
  function barBottom(sc, blockTop) {
    var b = sc === document.scrollingElement || sc === document.documentElement ? 0 : Math.max(0, sc.getBoundingClientRect().top);
    // Pinned bars stack from the top: the logo bar (it slides away while scrolling down and comes back,
    // so always leave room for it) and, for anything below the tabs, the tabs once they pin under it.
    var top = $("#top"), tabs = $("#tabs");
    if (top && /fixed|sticky/.test(getComputedStyle(top).position)) b += top.offsetHeight;
    if (tabs && /sticky|fixed/.test(getComputedStyle(tabs).position) && (blockTop == null || blockTop >= tabs.getBoundingClientRect().top - 2)) b += tabs.offsetHeight;
    return b;
  }
  /* top of the bottom navigation bar */
  function navTop() { var n = $("#bnav"); var q = n && n.getBoundingClientRect(); return q && q.height && q.top > innerHeight * 0.5 ? q.top : innerHeight; }

  var last = null;
  function mark(el) {
    if (!el) return;
    if (last && last !== el) last.classList.remove("tvf");
    last = el; el.classList.add("tvf");
    try { el.focus({preventScroll: true}); } catch (e) { el.focus(); }
    glide(el);
  }
  function valid(el) { return el && el !== document.body && el.isConnected && items().indexOf(el) >= 0; }
  function firstVisible(list) {
    var H = innerHeight, W = innerWidth;
    var shown = list.filter(function (el) { var r = el.getBoundingClientRect(); return r.bottom > 0 && r.top < H && r.right > 0 && r.left < W; });
    var pool = (shown.length ? shown : list).slice();
    pool.sort(function (a, b) { var A = a.getBoundingClientRect(), B = b.getBoundingClientRect(); return (A.top - B.top) || (A.left - B.left); });
    return pool[0];
  }
  function gap(a1, a2, b1, b2) { return Math.max(0, Math.max(a1, b1) - Math.min(a2, b2)); }
  function best(cur, dir, list) {
    var c = cur.getBoundingClientRect(), cx = c.left + c.width / 2, cy = c.top + c.height / 2, win = null, ws = Infinity;
    list.forEach(function (el) {
      if (el === cur || cur.contains(el) || el.contains(cur)) return;
      var r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2, major, minor, off;
      if (dir === "ArrowRight") { if (x <= cx + 1 || r.right <= c.right - 2) return; major = Math.max(0, r.left - c.right); minor = gap(c.top, c.bottom, r.top, r.bottom); off = Math.abs(y - cy); }
      else if (dir === "ArrowLeft") { if (x >= cx - 1 || r.left >= c.left + 2) return; major = Math.max(0, c.left - r.right); minor = gap(c.top, c.bottom, r.top, r.bottom); off = Math.abs(y - cy); }
      else if (dir === "ArrowDown") { if (y <= cy + 1 || r.bottom <= c.bottom - 2) return; major = Math.max(0, r.top - c.bottom); minor = gap(c.left, c.right, r.left, r.right); off = Math.abs(x - cx); }
      else { if (y >= cy - 1 || r.top >= c.top + 2) return; major = Math.max(0, c.top - r.bottom); minor = gap(c.left, c.right, r.left, r.right); off = Math.abs(x - cx); }
      if ((dir === "ArrowLeft" || dir === "ArrowRight") && minor > 0) return;   // left / right stay on the same line
      var s = major + minor * 3 + off * 0.08;
      if (s < ws) { ws = s; win = el; }
    });
    return win;
  }
  function fixed(el) { for (var n = el; n && n !== document.body; n = n.parentElement) if (/fixed|sticky/.test(getComputedStyle(n).position)) return true; return false; }
  function move(dir) {
    var list = items(); if (!list.length) return;
    var cur = document.activeElement;
    if (!valid(cur)) { mark(firstVisible(list)); return; }
    var hero = cur.closest("#hero"), side = dir === "ArrowLeft" || dir === "ArrowRight";
    if (hero && side) {                       // the spotlight: past its last button, left / right changes the title
      var inHero = best(cur, dir, list.filter(function (el) { return hero.contains(el); }));
      if (inHero) { mark(inHero); return; }
      var dots = $$("#bars [data-dot]", hero), slides = $$(".slide", hero);
      var at = Math.max(0, slides.findIndex(function (s) { return s.classList.contains("on"); }));
      var to = dots[(at + (dir === "ArrowRight" ? 1 : -1) + dots.length) % dots.length];
      if (to) { to.click(); setTimeout(function () { var s = $(".slide.on", hero); var b = s && $$(SEL, s).filter(vis)[0]; if (b) mark(b); }, 80); }
      return;
    }
    var flow = list.filter(function (el) { return !fixed(el); }), bars = list.filter(fixed);
    // The tabs (For you, Anime, Movies…) are a stop of their own between the top of the page and the rows:
    // up from the first row, or down from above them, lands on the tabs (on the one you're on).
    var tabsEl = $("#tabs");
    if (tabsEl && !side && !top()) {
      var before = function (el) { return !!(tabsEl.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_PRECEDING); };
      var tabBtns = list.filter(function (el) { return tabsEl.contains(el); });
      if (tabsEl.contains(cur)) {
        // from the tabs: up only to what comes before them, down only to what comes after
        var ahead = flow.filter(function (el) { return dir === "ArrowUp" ? before(el) : !before(el); });
        var nt = best(cur, dir, ahead) || best(cur, dir, bars.filter(function (el) { return !tabsEl.contains(el); }));
        if (nt) mark(nt);
        return;
      }
      var nf = best(cur, dir, flow);
      if (tabBtns.length && (!nf || before(cur) !== before(nf))) {
        var sel = tabBtns.filter(function (el) { return el.getAttribute("aria-selected") === "true"; })[0] || best(cur, dir, tabBtns) || tabBtns[0];
        if (sel && (nf || dir === "ArrowUp" || before(cur))) { mark(sel); return; }
      }
    }
    // left / right never leave the line you're on (a row, the top bar, the bottom bar); up / down go to the
    // rows first and reach the pinned bars only at the edges
    var order = side ? [fixed(cur) ? bars : flow] : [flow, bars];
    for (var i = 0; i < order.length; i++) { var n = best(cur, dir, order[i]); if (n) { mark(n); return; } }
  }
  function back() {
    if (LOCK.on) { hint(); return true; }
    var l = top();
    if (l && l[2] && l[2]()) {
      setTimeout(function () { if (!valid(document.activeElement)) mark(firstVisible(items())); }, 420);
      return true;
    }
    return false;
  }

  /* ── Swipe lock: the remote drives the cards. ← skip, → save, ↑ seen; hold OK for 3 seconds to unlock ── */
  var LOCK = {on: false, t0: 0, raf: 0, hintT: 0, swallow: false, swT: 0};
  function deckOpen() { return on("#xp") && !!$("#xp #deck"); }
  function lockUI() {
    var x = $("#xp"); if (!x) return;
    var btn = $("#xp .tvlock");
    if (!btn && $("#xp .dbtns")) {
      btn = document.createElement("button");
      btn.className = "tvlock"; btn.type = "button"; btn.setAttribute("data-tvlock", "");
      btn.innerHTML = '<span aria-hidden="true">🔒</span> Lock remote to swipe';
      $("#xp .dbtns").insertAdjacentElement("afterend", btn);
      if (!document.activeElement || !document.activeElement.closest("#xp")) setTimeout(function () { mark(btn); }, 60);
    }
    var bar = $("#tvlockbar");
    if (!bar) {
      bar = document.createElement("div"); bar.id = "tvlockbar"; bar.setAttribute("role", "status");
      bar.innerHTML = '<i class="ring"><b></b></i><span class="tx"><b>🔒 Locked to swiping</b><small>← Skip &nbsp; ↑ Seen &nbsp; → Save &nbsp;·&nbsp; Hold OK 3 s to unlock</small></span>';
      document.body.appendChild(bar);
    }
  }
  function setLock(v) {
    LOCK.armed = false;
    LOCK.on = v; document.documentElement.classList.toggle("tvlocked", v);
    lockUI(); ringTo(0);
    if (!v) {
      // the remote keeps repeating OK while it's still held: ignore OK until it's let go, so holding
      // past 3 seconds can't press the Lock button again
      LOCK.swallow = true; clearTimeout(LOCK.swT); LOCK.swT = setTimeout(function () { LOCK.swallow = false; }, 60000);
      var b = $("#xp .tvlock"); if (b) mark(b);
    }
  }
  function ringTo(k) { var r = $("#tvlockbar .ring b"); if (r) r.style.setProperty("--k", k); var bar = $("#tvlockbar"); if (bar) bar.classList.toggle("holding", k > 0); }
  function hint() { var bar = $("#tvlockbar"); if (!bar) return; bar.classList.remove("nudge"); void bar.offsetWidth; bar.classList.add("nudge"); }
  function swipe(v) {
    var card = $('#deck .dcard[style*="--k:0"]') || $$("#deck .dcard").pop(), btn = $('#xp [data-dk="' + v + '"]');
    if (!btn) return;
    if (card) card.dataset.lean = v === 1 ? "like" : v === -1 ? "skip" : "seen";
    setTimeout(function () { btn.click(); }, 140);                       // the stamp shows, then the card flies
  }
  new MutationObserver(function () { if (deckOpen()) lockUI(); else if (LOCK.on) setLock(false); })
    .observe($("#xp") || document.body, {childList: true, subtree: true, attributes: true, attributeFilter: ["class"]});
  document.addEventListener("click", function (e) { if (e.target.closest && e.target.closest("[data-tvlock]")) { e.preventDefault(); setLock(true); } }, true);
  function lockKey(e) {
    var k = e.key;
    if (k === "Enter" || k === " ") {
      e.preventDefault(); e.stopPropagation();
      if (e.type === "keydown" && !LOCK.t0 && !(e.repeat && !LOCK.armed)) {
        LOCK.t0 = performance.now();
        var tick = function (now) {
          if (!LOCK.t0) return;
          var p = Math.min(1, (now - LOCK.t0) / 3000); ringTo(p);
          if (p >= 1) { LOCK.t0 = 0; setLock(false); return; }
          LOCK.raf = rawRaf(tick);
        };
        LOCK.raf = rawRaf(tick);
      } else if (e.type === "keyup") {
        LOCK.armed = true;
        if (LOCK.t0 && performance.now() - LOCK.t0 < 3000) { LOCK.t0 = 0; rawCaf(LOCK.raf); ringTo(0); hint(); }
      }
      return;
    }
    if (e.type !== "keydown") return;
    if (/^Arrow|GoBack|BrowserBack|Backspace|Escape/.test(k)) { e.preventDefault(); e.stopPropagation(); }
    if (e.repeat) return;
    if (k === "ArrowLeft") swipe(-1);
    else if (k === "ArrowRight") swipe(1);
    else if (k === "ArrowUp") swipe(0);
    else hint();
  }
  var isOk = function (e) { return e.key === "Enter" || e.key === " " || e.keyCode === 13 || e.keyCode === 23; };
  document.addEventListener("keyup", function (e) {
    if (LOCK.swallow && isOk(e)) { LOCK.swallow = false; clearTimeout(LOCK.swT); e.preventDefault(); e.stopPropagation(); return; }
    if (LOCK.on) lockKey(e);
  }, true);
  document.addEventListener("keypress", function (e) { if ((LOCK.on || LOCK.swallow) && isOk(e)) { e.preventDefault(); e.stopPropagation(); } }, true);

  document.addEventListener("keydown", function (e) {
    if (LOCK.swallow && isOk(e)) { e.preventDefault(); e.stopPropagation(); return; }
    if (LOCK.on) { lockKey(e); return; }
    var t = e.target, typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA") && !/checkbox|radio|range/.test(t.type);
    if (/^Arrow/.test(e.key)) {
      if (typing && (e.key === "ArrowLeft" || e.key === "ArrowRight")) return;
      e.preventDefault(); e.stopPropagation(); move(e.key);
    } else if (e.key === "Enter" && t && t.matches && t.matches("[tabindex]:not(button):not(a):not(input)")) {
      e.preventDefault(); t.click();
    }
  }, true);
  document.addEventListener("click", function (e) {
    var t = e.target.closest && e.target.closest("#tabs [data-tab]"); if (!t) return;
    var k = t.getAttribute("data-tab");
    [60, 260, 600].forEach(function (ms) { setTimeout(function () {
      var nt = $('#tabs [data-tab="' + k + '"]'); if (nt && document.activeElement !== nt) mark(nt);
    }, ms); });
  }, true);
  document.addEventListener("focusin", function (e) {
    if (e.target !== last && e.target.classList) { if (last) last.classList.remove("tvf"); last = e.target; last.classList.add("tvf"); }
  });

  // no Google sign-in on the TV: keep the sign-in screen closed whenever the page opens it
  function noAuth() { var g = $("#auth"); if (g && g.classList.contains("on")) g.classList.remove("on"); }
  noAuth();
  var g0 = $("#auth"); if (g0) new MutationObserver(noAuth).observe(g0, {attributes: true, attributeFilter: ["class"]});

  // the TV app calls these: Back from the remote, and putting the highlight on the page at launch
  window.vsBack = back;
  window.vsFocus = function () { try { window.focus(); } catch (e) {} if (!valid(document.activeElement)) mark(firstVisible(items())); };
})();
