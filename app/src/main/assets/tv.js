/* VibeSync TV: remote (D-pad) navigation, added by the TV app to the VibeSync page.
   Arrows move the highlight to the nearest thing that way, OK selects, Back closes the top-most panel.
   The website itself is unchanged; this only runs inside VibeSync TV. */
(function () {
  if (window.__vsTV) return;
  window.__vsTV = 1;
  document.documentElement.classList.add("tv");
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
    var prev = anims.get(sc); if (prev) cancelAnimationFrame(prev.raf);
    var a = {from: sc[key], to: target, t0: performance.now(), dur: 420};
    function step(now) {
      var k = Math.min(1, (now - a.t0) / a.dur), e = 1 - Math.pow(1 - k, 4);
      sc[key] = a.from + (a.to - a.from) * e;
      if (k < 1) a.raf = requestAnimationFrame(step); else anims.delete(sc);
    }
    a.raf = requestAnimationFrame(step); anims.set(sc, a);
  }
  function glide(el) {
    var r = el.getBoundingClientRect(), sx = scrollerOf(el, "x");
    if (sx) {
      var b = sx.getBoundingClientRect(), pad = Math.min(140, b.width * 0.12), shift = aimOf(sx, "scrollLeft") - sx.scrollLeft;
      var left = r.left - shift, right = r.right - shift;
      if (left < b.left + pad) easeTo(sx, "x", aimOf(sx, "scrollLeft") - (b.left + pad - left));
      else if (right > b.right - pad) easeTo(sx, "x", aimOf(sx, "scrollLeft") + (right - (b.right - pad)));
    }
    var sy = scrollerOf(el, "y");
    if (sy) {
      var H = innerHeight, sh = aimOf(sy, "scrollTop") - sy.scrollTop, t = r.top - sh, bo = r.bottom - sh;
      if (t < H * 0.22 || bo > H * 0.82) easeTo(sy, "y", aimOf(sy, "scrollTop") + (t + r.height / 2 - H * 0.5));
    }
  }

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
    var order = !fixed(cur) ? [flow, bars] : side ? [bars, flow] : [flow, bars];
    for (var i = 0; i < order.length; i++) { var n = best(cur, dir, order[i]); if (n) { mark(n); return; } }
  }
  function back() {
    var l = top();
    if (l && l[2] && l[2]()) {
      setTimeout(function () { if (!valid(document.activeElement)) mark(firstVisible(items())); }, 420);
      return true;
    }
    return false;
  }

  document.addEventListener("keydown", function (e) {
    var t = e.target, typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA") && !/checkbox|radio|range/.test(t.type);
    if (/^Arrow/.test(e.key)) {
      if (typing && (e.key === "ArrowLeft" || e.key === "ArrowRight")) return;
      e.preventDefault(); e.stopPropagation(); move(e.key);
    } else if (e.key === "Enter" && t && t.matches && t.matches("[tabindex]:not(button):not(a):not(input)")) {
      e.preventDefault(); t.click();
    }
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
