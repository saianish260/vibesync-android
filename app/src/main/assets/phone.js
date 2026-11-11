/* VibeSync phone app only: how the home spotlight (hero) responds to your finger.
 *  - Swipe: the title goes all the way off screen and its progress bar fills to the end before the next one
 *    takes over (no half-way states).
 *  - Press and hold, then move your finger: browse the titles one after another, gently, like scrubbing.
 *    Move further to keep going; move back to go back. Let go to stay on that title.
 * Added to the page by the phone app (MainActivity); the website keeps its own swipe.
 * The page's code is private, so this changes title the way tapping a progress bar does (the page's own
 * showSlide + timer restart), which keeps the bars, backdrop and colours in sync. */
(() => {
  if (window.__vsPhone) return;
  window.__vsPhone = 1;
  const $ = (q, r = document) => r.querySelector(q), $$ = (q, r = document) => [...r.querySelectorAll(q)];
  const st = document.createElement("style");
  st.textContent = `
#hero{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
#hero img{-webkit-user-drag:none;-webkit-touch-callout:none}
#hero .slides{transition:transform .35s cubic-bezier(.16,1,.3,1)}
#hero.vsscrub .slides{transform:scale(.965)}
#vsscrubhint{position:absolute;z-index:5;left:50%;top:78px;transform:translate(-50%,-6px);opacity:0;pointer-events:none;
  padding:6px 14px;border-radius:999px;font-size:12px;font-weight:700;letter-spacing:.02em;white-space:nowrap;
  background:color-mix(in srgb,var(--bg,#0d0a12) 80%,transparent);color:var(--text,#fff);box-shadow:0 6px 20px rgba(0,0,0,.35);
  transition:opacity .25s ease,transform .25s cubic-bezier(.16,1,.3,1)}
#hero.vsscrub #vsscrubhint{opacity:1;transform:translate(-50%,0)}`;
  document.head.appendChild(st);

  const HOLD = 360, SLOP = 8, EASE_OUT = "cubic-bezier(.16,1,.3,1)";
  const slides = () => $$("#slides .slide");
  const hero = () => $("#hero");
  const cur = () => { const all = slides(), i = all.findIndex(x => x.classList.contains("on")); return i < 0 ? 0 : i; };
  const go = i => { const n = slides().length, d = $$("#bars i")[((i % n) + n) % n]; if (d) d.click(); };   // the page's bar tap
  // pause the bars while a finger is down; afterwards, only un-pause if it wasn't paused before (off screen, a page open)
  let wasPaused = false;
  const pause = () => { const h = hero(); if (!h) return; wasPaused = h.classList.contains("paused"); h.classList.add("paused"); };
  const resume = () => { const h = hero(); if (h && !wasPaused) h.classList.remove("paused"); };
  const clear = el => { if (el) { el.style.transform = ""; el.style.opacity = ""; el.style.transition = ""; el.style.willChange = ""; el.classList.remove("peek"); } };
  const ensureHint = h => { if (!$("#vsscrubhint", h)) { const d = document.createElement("div"); d.id = "vsscrubhint"; d.textContent = "‹  Slide to browse  ›"; h.appendChild(d); } };

  // the current progress bar runs smoothly to its end (used when moving forward)
  function fillBar(i, dur) {
    const bar = $$("#bars i")[i], b = bar && $("b", bar); if (!b) return;
    const w = b.getBoundingClientRect().width;
    b.style.animation = "none"; b.style.width = w + "px"; void b.offsetWidth;
    b.style.transition = `width ${dur}ms ${EASE_OUT}`; b.style.width = "100%";
    setTimeout(() => { b.style.animation = ""; b.style.width = ""; b.style.transition = ""; }, dur + 40);
  }

  // one full step to the next / previous title: the current one leaves completely, the next one arrives
  let busy = false;
  function step(dir, dur, from) {
    const all = slides(), n = all.length; if (n < 2) return Promise.resolve();
    busy = true;
    const h = hero(), w = h ? h.clientWidth : innerWidth;
    const at = cur(), cs = all[at], nb = all[(at + dir + n) % n];
    if (dir > 0) fillBar(at, dur);
    nb.classList.add("peek");
    cs.style.willChange = nb.style.willChange = "transform,opacity";
    if (!from) {                                   // start the next title just off to the side
      cs.style.transition = nb.style.transition = "none";
      nb.style.transform = `translate3d(${dir * w * .85}px,0,0) scale(.94)`; nb.style.opacity = 0;
      void nb.offsetWidth;
    }
    cs.style.transition = nb.style.transition = `transform ${dur}ms ${EASE_OUT}, opacity ${dur}ms ${EASE_OUT}`;
    cs.style.transform = `translate3d(${-dir * w}px,0,0) scale(.92)`; cs.style.opacity = 0;
    nb.style.transform = "translate3d(0,0,0)"; nb.style.opacity = 1;
    return new Promise(res => setTimeout(() => {
      go(at + dir);
      requestAnimationFrame(() => { clear(cs); clear(nb); busy = false; res(); });
    }, dur));
  }

  let s = null, swallow = false;
  const inHero = e => { const h = e.target.closest && e.target.closest("#hero"); return h && !e.target.closest("[data-dot]") ? h : null; };

  // document, capture phase: runs after the page's own capture listeners (tap preloading etc.), and
  // stopPropagation keeps the website's hero swipe (a bubble listener) out of it
  document.addEventListener("pointerdown", e => {
    const h = inHero(e); if (!h) return;
    e.stopPropagation();
    if (e.button > 0 || busy || slides().length < 2) return;
    ensureHint(h);
    s = {x: e.clientX, y: e.clientY, dx: 0, v: 0, lx: e.clientX, lt: performance.now(), mode: "wait", w: h.clientWidth, h,
         cur: slides()[cur()], nb: null, dir: 0, anchor: 0, target: 0};
    pause();
    s.timer = setTimeout(() => {                  // held still: browse mode
      if (!s || s.mode !== "wait") return;
      s.mode = "scrub"; s.anchor = s.x; s.target = 0;
      h.classList.add("vsscrub");
    }, HOLD);
  }, true);

  document.addEventListener("pointermove", e => {
    if (!s) return;
    const dx = e.clientX - s.x, dy = e.clientY - s.y, now = performance.now();
    if (s.mode === "wait") {
      if (Math.abs(dy) > 12 && Math.abs(dy) > Math.abs(dx)) { clearTimeout(s.timer); s = null; resume(); return; }   // a scroll
      if (Math.abs(dx) < SLOP) return;
      clearTimeout(s.timer); s.mode = "drag";
    }
    if (s.mode === "scrub") { scrubTo(e.clientX); return; }
    // a normal swipe: the title follows the finger, the next one comes in alongside
    s.v = s.v * .6 + .4 * (e.clientX - s.lx) / Math.max(1, now - s.lt); s.lx = e.clientX; s.lt = now; s.dx = dx;
    const dir = dx < 0 ? 1 : -1, all = slides();
    if (dir !== s.dir) { clear(s.nb); s.dir = dir; s.nb = all[(cur() + dir + all.length) % all.length]; s.nb.classList.add("peek"); }
    const p = Math.min(1, Math.abs(dx) / s.w);
    s.cur.style.transition = s.nb.style.transition = "none"; s.cur.style.willChange = s.nb.style.willChange = "transform,opacity";
    s.cur.style.transform = `translate3d(${dx}px,0,0) scale(${1 - p * .06})`; s.cur.style.opacity = Math.max(0, 1 - p * 1.8);
    s.nb.style.transform = `translate3d(${dx + dir * s.w * .85}px,0,0) scale(${.94 + p * .06})`; s.nb.style.opacity = Math.min(1, p * 1.8);
  }, {capture: true, passive: true});

  // browse mode: every ~a sixth of the screen of finger travel is one title; steps play one after another, slowly
  function scrubTo(x) {
    const unit = Math.max(56, s.w * .17);
    s.target = Math.trunc((x - s.anchor) / -unit);    // finger left = forward, like swiping
    pump();
  }
  function pump() {
    if (!s || s.mode !== "scrub" || busy) return;
    const done = s.done || 0;
    if (s.target === done) return;
    const dir = s.target > done ? 1 : -1;
    s.done = done + dir;
    step(dir, 420).then(pump);
  }

  // stop the page scrolling while browsing (holding, then moving up or down a little)
  window.addEventListener("touchmove", e => { if (s && (s.mode === "scrub" || s.mode === "drag")) e.preventDefault(); }, {passive: false, capture: true});
  window.addEventListener("contextmenu", e => { if (inHero(e)) e.preventDefault(); }, true);

  const end = e => {
    if (!s) return;
    const c = s; s = null; clearTimeout(c.timer);
    if (c.mode === "wait") { resume(); return; }             // a tap: buttons and the poster still work
    swallow = true; setTimeout(() => swallow = false, 80);
    if (c.mode === "scrub") {
      c.h.classList.remove("vsscrub");
      const wait = () => busy ? setTimeout(wait, 60) : resume();
      wait();
      return;
    }
    // swipe let go: far enough or a quick flick carries on all the way; otherwise it springs back
    const {dx, v, w, cur, nb, dir} = c;
    if (!nb) { resume(); return; }
    const go = Math.abs(dx) > w * .2 || (Math.abs(v) > .35 && Math.sign(v) === -dir);
    if (go) {
      const dur = Math.round(Math.max(300, 520 - Math.abs(v) * 140));
      step(dir, dur, true).then(resume);
    } else {
      cur.style.transition = nb.style.transition = `transform 480ms ${EASE_OUT}, opacity 380ms ease`;
      cur.style.transform = ""; cur.style.opacity = ""; nb.style.transform = `translate3d(${dir * w * .85}px,0,0)`; nb.style.opacity = 0;
      setTimeout(() => clear(nb), 480);
      resume();
    }
  };
  document.addEventListener("pointerup", end, true);
  document.addEventListener("pointercancel", end, true);
  document.addEventListener("click", e => { if (swallow && e.isTrusted && e.target.closest && e.target.closest("#hero")) { e.stopPropagation(); e.preventDefault(); } }, true);
})();
