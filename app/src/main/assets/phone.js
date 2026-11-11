/* VibeSync phone app only: the home spotlight (hero) as a real swipeable row.
 *  - Swipe: the titles sit side by side in a native scrolling row, exactly like the poster rows below, so the
 *    phone itself does the gliding and settling (same smoothness). It stops at the first and the last title.
 *  - Press and hold, then move your finger: browse the titles one after another, gently.
 * Added to the page by the phone app (MainActivity); the website keeps its own spotlight.
 * The page's code is private, so the title is changed the way tapping a progress bar does (the page's own
 * showSlide), which keeps the bars, backdrop and colours in sync. */
(() => {
  if (window.__vsPhone) return;
  window.__vsPhone = 1;
  const $ = (q, r = document) => r.querySelector(q), $$ = (q, r = document) => [...r.querySelectorAll(q)];
  const st = document.createElement("style");
  st.textContent = `
#hero{touch-action:auto!important;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
#hero img{-webkit-user-drag:none;-webkit-touch-callout:none}
#slides{display:flex!important;overflow-x:auto;overflow-y:hidden;scroll-snap-type:x mandatory;scrollbar-width:none;
  overscroll-behavior-x:contain;touch-action:auto!important}
#slides::-webkit-scrollbar{display:none}
#slides .slide{flex:0 0 100%;width:100%;min-width:0;box-sizing:border-box;scroll-snap-align:center;scroll-snap-stop:normal;
  opacity:1!important;visibility:visible!important;pointer-events:auto!important;transition:none!important}
#slides .slide .hp,#slides .slide .ht{transform:none!important;opacity:1!important;transition:none!important}
#hero.vsscrub #slides .slide .hp{transform:scale(.95)!important;transition:transform .3s cubic-bezier(.16,1,.3,1)!important}
#vsscrubhint{position:absolute;z-index:5;left:50%;top:78px;transform:translate(-50%,-6px);opacity:0;pointer-events:none;
  padding:6px 14px;border-radius:999px;font-size:12px;font-weight:700;letter-spacing:.02em;white-space:nowrap;
  background:color-mix(in srgb,var(--bg,#0d0a12) 80%,transparent);color:var(--text,#fff);box-shadow:0 6px 20px rgba(0,0,0,.35);
  transition:opacity .25s ease,transform .25s cubic-bezier(.16,1,.3,1)}
#hero.vsscrub #vsscrubhint{opacity:1;transform:translate(-50%,0)}`;
  document.head.appendChild(st);

  const HOLD = 360, SLOP = 8;
  const box = () => $("#slides");
  const slides = () => $$("#slides .slide");
  const hero = () => $("#hero");
  const cur = () => { const i = slides().findIndex(x => x.classList.contains("on")); return i < 0 ? 0 : i; };
  const go2 = i => { const d = $$("#bars i")[i]; if (d) d.click(); };      // the page's bar tap (showSlide)
  const width = () => { const b = box(); return b ? b.clientWidth || 1 : 1; };
  const idxAt = () => { const b = box(); return b ? Math.max(0, Math.min(slides().length - 1, Math.round(b.scrollLeft / width()))) : 0; };
  const ensureHint = h => { if (!$("#vsscrubhint", h)) { const d = document.createElement("div"); d.id = "vsscrubhint"; d.textContent = "‹  Slide to browse  ›"; h.appendChild(d); } };

  // the progress bars wait while a finger is down or the row is moving; afterwards they carry on
  // (unless they were already paused: spotlight off screen, a page open)
  let touching = false, scrolling = false, scrubbing = false, mine = false, wasPaused = false;
  const pause = () => { const h = hero(); if (!h || mine) return; wasPaused = h.classList.contains("paused"); h.classList.add("paused"); mine = true; };
  const resume = () => { const h = hero(); if (!mine || touching || scrolling || scrubbing) return; mine = false; if (h && !wasPaused) h.classList.remove("paused"); };

  // the row came to rest on a title: make it the spotlight's title
  function settle() {
    scrolling = false;
    if (!box()) return;
    const i = idxAt(); if (i !== cur()) go2(i);
    resume();
  }

  // the page moved to another title by itself (timer, a bar tapped): glide the row there
  function follow() {
    const b = box(); if (!b || touching || scrolling || scrubbing) return;
    const i = cur(); if (i !== idxAt()) b.scrollTo({left: i * width(), behavior: "smooth"});
  }

  function bind() {
    const b = box(); if (!b || b.__vs) return;
    b.__vs = 1;
    b.scrollLeft = cur() * width();
    let t = 0;
    b.addEventListener("scroll", () => {
      if (!scrolling) { scrolling = true; pause(); }
      clearTimeout(t); t = setTimeout(settle, 140);
    }, {passive: true});
    b.addEventListener("scrollend", () => { clearTimeout(t); settle(); });
    new MutationObserver(() => requestAnimationFrame(follow)).observe(b, {subtree: true, attributes: true, attributeFilter: ["class"]});
  }
  bind();
  setInterval(bind, 800);                                 // the page can rebuild the spotlight
  addEventListener("resize", () => { const b = box(); if (b) b.scrollLeft = cur() * width(); });

  // press and hold: browse mode
  let s = null, swallow = false;
  const inHero = e => { const h = e.target.closest && e.target.closest("#hero"); return h && !e.target.closest("[data-dot]") ? h : null; };

  // document, capture phase: runs after the page's own capture listeners (tap preloading etc.), and
  // stopPropagation keeps the website's own spotlight swipe (a bubble listener) out of it
  document.addEventListener("pointerdown", e => {
    const h = inHero(e); if (!h) return;
    e.stopPropagation();
    if (e.button > 0 || slides().length < 2) return;
    bind(); ensureHint(h);
    touching = true; pause();
    s = {x: e.clientX, y: e.clientY, mode: "wait", h, start: idxAt(), done: 0, target: 0};
    s.timer = setTimeout(() => {
      if (!s || s.mode !== "wait") return;
      s.mode = "scrub"; scrubbing = true; h.classList.add("vsscrub");
    }, HOLD);
  }, true);

  document.addEventListener("pointermove", e => {
    if (!s) return;
    const dx = e.clientX - s.x, dy = e.clientY - s.y;
    if (s.mode === "wait") {
      if (Math.abs(dx) > SLOP || Math.abs(dy) > SLOP) { clearTimeout(s.timer); s.mode = "native"; }   // a swipe or a scroll: the phone handles it
      return;
    }
    if (s.mode === "scrub") {
      const unit = Math.max(56, width() * .17), n = slides().length;
      s.target = Math.max(-s.start, Math.min(n - 1 - s.start, Math.trunc(dx / -unit)));   // finger left = forward
      pump();
    }
  }, {capture: true, passive: true});

  // browse mode: one title at a time, each step a calm glide of the row
  let stepping = false;
  function pump() {
    if (!s || s.mode !== "scrub" || stepping) return;
    if (s.target === s.done) return;
    s.done += s.target > s.done ? 1 : -1;
    const b = box(); if (!b) return;
    stepping = true;
    b.scrollTo({left: (s.start + s.done) * width(), behavior: "smooth"});
    setTimeout(() => { stepping = false; pump(); }, 420);
  }

  // stop the page or the row moving under the finger while browsing
  window.addEventListener("touchmove", e => { if (s && s.mode === "scrub") e.preventDefault(); }, {passive: false, capture: true});
  window.addEventListener("contextmenu", e => { if (inHero(e)) e.preventDefault(); }, true);

  const end = () => {
    if (!s) return;
    const c = s; s = null; clearTimeout(c.timer);
    touching = false;
    if (c.mode === "scrub") {
      swallow = true; setTimeout(() => swallow = false, 120);
      c.h.classList.remove("vsscrub");
      const wait = () => { if (stepping) return setTimeout(wait, 60); scrubbing = false; if (!scrolling) settle(); };
      wait();
      return;
    }
    if (!scrolling) resume();
  };
  document.addEventListener("pointerup", end, true);
  document.addEventListener("pointercancel", end, true);
  document.addEventListener("click", e => { if (swallow && e.isTrusted && e.target.closest && e.target.closest("#hero")) { e.stopPropagation(); e.preventDefault(); } }, true);
})();


/* App only: a "Check for updates" chip, first in the Saved tab's tools row (before Which plan? / Sync).
   Asks the app to check right away; the app answers with a popup (newer version) or a short message. */
(() => {
  if (window.__vsUpdMenu) return;
  window.__vsUpdMenu = 1;
  const app = window.VibeSyncApp;
  if (!app || !app.checkUpdate) return;              // an older app without the manual check
  const st = document.createElement("style");
  st.textContent = `.chip.vsupd.spin{opacity:.6;pointer-events:none}`;
  document.head.appendChild(st);
  let ver = "";
  try { ver = app.appVersion ? app.appVersion() : ""; } catch (e) { }
  const add = () => document.querySelectorAll(".chips.qk").forEach(row => {
    if (row.querySelector(".vsupd")) return;
    const b = document.createElement("button");
    b.className = "chip vsupd";
    b.textContent = "⬆️ Check for updates" + (ver ? ` · v${ver}` : "");
    row.prepend(b);
  });
  add();
  setInterval(add, 1000);                           // the Saved tab is rebuilt as things change
  document.addEventListener("click", e => {
    const b = e.target.closest && e.target.closest(".vsupd"); if (!b) return;
    e.preventDefault(); e.stopPropagation();
    b.classList.add("spin"); setTimeout(() => b.classList.remove("spin"), 2500);
    try { app.checkUpdate(); } catch (err) { }
  }, true);
})();
