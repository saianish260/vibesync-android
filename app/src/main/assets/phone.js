/* VibeSync phone app only: how the home spotlight (hero) responds to your finger.
 *  - Swipe: the titles sit side by side in a strip that moves with your finger. Let go and it keeps gliding with
 *    the speed of your swipe, slowing down by itself (like scrolling a row of posters): a gentle swipe moves one
 *    title, a fast flick glides past several. It always settles exactly on a title.
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

  const HOLD = 360, SLOP = 8, EASE_OUT = "cubic-bezier(.16,1,.3,1)", GLIDE = "cubic-bezier(.22,.61,.36,1)";
  const slides = () => $$("#slides .slide");
  const hero = () => $("#hero");
  const cur = () => { const all = slides(), i = all.findIndex(x => x.classList.contains("on")); return i < 0 ? 0 : i; };
  const go2 = i => { const n = slides().length, d = $$("#bars i")[((i % n) + n) % n]; if (d) d.click(); };   // the page's bar tap
  // pause the bars while a finger is down; afterwards, only un-pause if it wasn't paused before (off screen, a page open)
  let wasPaused = false;
  const pause = () => { const h = hero(); if (!h) return; wasPaused = h.classList.contains("paused"); h.classList.add("paused"); };
  const resume = () => { const h = hero(); if (h && !wasPaused) h.classList.remove("paused"); };
  // a title that has left the screen: hide it at once (the page would otherwise fade it out where it stands)
  const hideNow = el => {
    if (!el || el.classList.contains("on")) return clear(el);
    el.classList.remove("peek");
    el.style.transition = "none"; el.style.opacity = "0"; el.style.visibility = "hidden"; el.style.transform = ""; el.style.willChange = "";
    clearTimeout(el._vsH); el._vsH = setTimeout(() => { if (!el.classList.contains("peek")) { el.style.transition = ""; el.style.opacity = ""; el.style.visibility = ""; } }, 700);
  };
  const clear = el => { if (el) { el.style.transform = ""; el.style.opacity = ""; el.style.transition = ""; el.style.willChange = ""; el.style.visibility = ""; clearTimeout(el._vsH); el.classList.remove("peek"); } };
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
    nb.classList.add("peek"); clearTimeout(nb._vsH); nb.style.visibility = "";
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
      go2(at + dir);
      requestAnimationFrame(() => { hideNow(cs); hideNow(nb); busy = false; res(); });
    }, dur));
  }

  // the strip: every title placed side by side around the starting one, shifted by X pixels
  function layout(at, X, w) {
    const all = slides(), n = all.length, fwd = X <= 0;
    for (let m = fwd ? -1 : -(n - 2); m <= (fwd ? n - 2 : 1); m++) {
      const el = all[((at + m) % n + n) % n], t = m * w + X, seen = t > -w * 1.02 && t < w * 1.02;
      if (seen || el.classList.contains("on")) {
        if (!el.classList.contains("on")) el.classList.add("peek");
        clearTimeout(el._vsH); el.style.visibility = "";
        el.style.transition = "none"; el.style.willChange = "transform"; el.style.opacity = 1;
        el.style.transform = `translate3d(${t}px,0,0)`;
      } else hideNow(el);
    }
  }

  // let go: keep moving with the finger's speed and slow down smoothly, settling on a whole title
  function glide(at, dx, v, w) {
    const n = slides().length, maxK = Math.max(1, Math.min(3, n - 2));
    const dir = dx < 0 ? 1 : -1;
    let k = Math.round(-(dx + v * 320) / w);                       // where the throw would carry the strip
    k = Math.max(-maxK, Math.min(maxK, k));
    if (k === 0 && (Math.abs(dx) > w * .25 || (Math.abs(v) > .3 && Math.sign(v) === -dir))) k = dir;
    const X0 = dx, X1 = -k * w, dist = Math.abs(X1 - X0), speed = Math.abs(v);
    // an ease-out whose starting speed matches the finger's, so the hand-off is seamless
    const D = k === 0 ? 360 : Math.round(Math.min(1300, Math.max(380, speed > .3 ? 3 * dist / speed : 320 + dist * .7)));
    if (k > 0) fillBar(at, Math.min(D, 600));
    busy = true;
    const t0 = performance.now();
    const frame = now => {
      const p = Math.min(1, (now - t0) / D), e = 1 - Math.pow(1 - p, 3);
      layout(at, X0 + (X1 - X0) * e, w);
      if (p < 1) return requestAnimationFrame(frame);
      if (k) go2(at + k);
      requestAnimationFrame(() => { slides().forEach(hideNow); busy = false; resume(); });
    };
    requestAnimationFrame(frame);
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
         at: cur(), anchor: 0, target: 0};
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
    layout(s.at, dx, s.w);
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
    glide(c.at, c.dx, c.v, c.w);
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
