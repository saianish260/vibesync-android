/* VibeSync phone app only: the home spotlight as a Netflix-style poster row.
 *  - Up to 50 big posters, picked at random from the titles VibeSync shows (a fresh mix each time the app opens;
 *    more are added as the page loads more rows).
 *  - Native scrolling with no snapping, like Netflix's rows: it glides as far as the swipe carries it and stops
 *    wherever it ends. Tap a poster to open its details.
 *  - Press and hold, then move your finger: browse the posters one after another, gently.
 *  - The glow behind the row follows the poster in the middle when the row stops.
 * Added to the page by the phone app (MainActivity); the website keeps its own spotlight. */
(() => {
  if (window.__vsPhone) return;
  window.__vsPhone = 1;
  const $ = (q, r = document) => r.querySelector(q), $$ = (q, r = document) => [...r.querySelectorAll(q)];
  const st = document.createElement("style");
  st.textContent = `
#hero{min-height:0!important;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
#hero #slides,#hero #bars{display:none!important}
#hero img{-webkit-user-drag:none;-webkit-touch-callout:none}
#vsrow{position:relative;z-index:2;display:flex;gap:8px;overflow-x:auto;overflow-y:hidden;padding:22px 16px 26px;
  scroll-snap-type:none;scrollbar-width:none;overscroll-behavior-x:contain;touch-action:auto}
#vsrow::-webkit-scrollbar{display:none}
#vsrow .vsp{flex:0 0 44vw;max-width:250px;display:block;padding:0;border:0;background:none;text-align:left;-webkit-tap-highlight-color:transparent}
#vsrow .vsp .pw{aspect-ratio:2/3;border-radius:10px;box-shadow:0 10px 30px rgba(0,0,0,.45);transition:transform .25s cubic-bezier(.16,1,.3,1)}
#vsrow .vsp .pw img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
#vsrow .vsp:active .pw{transform:scale(.97)}
#hero.vsscrub #vsrow .vsp .pw{transform:scale(.95)}
#vsscrubhint{position:absolute;z-index:5;left:50%;top:70px;transform:translate(-50%,-6px);opacity:0;pointer-events:none;
  padding:6px 14px;border-radius:999px;font-size:12px;font-weight:700;letter-spacing:.02em;white-space:nowrap;
  background:color-mix(in srgb,var(--bg,#0d0a12) 80%,transparent);color:var(--text,#fff);box-shadow:0 6px 20px rgba(0,0,0,.35);
  transition:opacity .25s ease,transform .25s cubic-bezier(.16,1,.3,1)}
#hero.vsscrub #vsscrubhint{opacity:1;transform:translate(-50%,0)}`;
  document.head.appendChild(st);

  const MAX = 50, HOLD = 360, SLOP = 8;
  const hero = () => $("#hero");
  const row = () => $("#vsrow");
  const shuffle = a => { for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
  // a bigger, sharper copy of a poster image (JustWatch s332 -> s592, TMDB w342 -> w500)
  const big = src => src.replace(/\/s(166|276|332)\//, "/s592/").replace(/\/w(154|185|342)\//, "/w500/");

  // ── the titles: collected from the posters the page already shows, kept for this app session ──
  const picked = [], seen = new Set();
  function collect() {
    const found = [];
    $$("#hero .hp[data-open], .card[data-open], .t10[data-open]").forEach(el => {
      const id = el.dataset.open; if (!id || seen.has(id) || el.closest("#vsrow,#sheet")) return;
      const pw = $(".pw", el); if (!pw) return;
      const img = $("img", pw), art = $(".art", pw);
      seen.add(id);
      found.push({id, label: el.getAttribute("aria-label") || "", src: img ? img.getAttribute("src") : "", alt: img ? (img.dataset.alt || img.getAttribute("src")) : "", art: art ? art.outerHTML : ""});
    });
    return shuffle(found);
  }
  const card = t => {
    const b = document.createElement("button");
    b.className = "vsp"; b.dataset.open = t.id; b.setAttribute("aria-label", t.label.replace(/ details$/, ""));
    const img = t.src ? `<img src="${big(t.src)}" data-alt="${t.alt}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer">` : "";
    b.innerHTML = `<div class="pw">${t.art}${img}</div>`;
    return b;
  };
  function fill() {
    const r = row(); if (!r) return;
    if (picked.length < MAX) collect().slice(0, MAX - picked.length).forEach(t => picked.push(t));
    // add the ones this row doesn't have yet, at the end (what's on screen never reshuffles)
    const have = new Set($$(".vsp", r).map(b => b.dataset.open));
    picked.forEach(t => { if (!have.has(t.id)) r.appendChild(card(t)); });
  }

  function build() {
    const h = hero(); if (!h) return;
    if (!row()) {
      const r = document.createElement("div"); r.id = "vsrow"; r.setAttribute("aria-label", "Spotlight");
      const sl = $("#slides", h); if (sl) sl.after(r); else h.appendChild(r);
      bindRow(r);
    }
    if (!$("#vsscrubhint", h)) { const d = document.createElement("div"); d.id = "vsscrubhint"; d.textContent = "‹  Slide to browse  ›"; h.appendChild(d); }
    fill();
  }
  build();
  setInterval(build, 1200);                              // the page loads more rows / can rebuild the spotlight

  // ── the glow behind the row follows the poster in the middle once the row stops ──
  let glowFor = "";
  function glow() {
    const r = row(); if (!r) return;
    const mid = r.getBoundingClientRect().left + r.clientWidth / 2;
    let bestEl = null, bestD = 1e9;
    $$(".vsp", r).forEach(b => { const q = b.getBoundingClientRect(), d = Math.abs(q.left + q.width / 2 - mid); if (d < bestD) { bestD = d; bestEl = b; } });
    const img = bestEl && $("img", bestEl); if (!img || glowFor === bestEl.dataset.open) return;
    glowFor = bestEl.dataset.open;
    const on = $(".hbd.on") || $("#hbd0"), off = on && on.id === "hbd0" ? $("#hbd1") : $("#hbd0");
    if (!off) return;
    off.style.backgroundImage = `url('${img.currentSrc || img.src}')`;
    off.classList.add("on"); if (on && on !== off) on.classList.remove("on");
  }
  function bindRow(r) {
    let t = 0;
    r.addEventListener("scroll", () => { clearTimeout(t); t = setTimeout(glow, 160); }, {passive: true});
    r.addEventListener("scrollend", () => { clearTimeout(t); glow(); });
  }

  // ── press and hold, then slide: browse mode (one poster at a time) ──
  let s = null, swallow = false, stepping = false;
  const inRow = e => e.target.closest && e.target.closest("#vsrow");

  // document, capture phase: stopPropagation keeps the website's own spotlight swipe (a bubble listener) out of it;
  // the page's own capture listeners (tap preloading etc.) still run
  document.addEventListener("pointerdown", e => {
    if (!e.target.closest || !e.target.closest("#hero")) return;
    e.stopPropagation();
    const r = inRow(e); if (!r || e.button > 0) return;
    s = {x: e.clientX, y: e.clientY, mode: "wait", r, start: r.scrollLeft, done: 0, target: 0};
    s.timer = setTimeout(() => {
      if (!s || s.mode !== "wait") return;
      s.mode = "scrub"; const h = hero(); if (h) h.classList.add("vsscrub");
    }, HOLD);
  }, true);

  document.addEventListener("pointermove", e => {
    if (!s) return;
    const dx = e.clientX - s.x, dy = e.clientY - s.y;
    if (s.mode === "wait") { if (Math.abs(dx) > SLOP || Math.abs(dy) > SLOP) { clearTimeout(s.timer); s.mode = "native"; } return; }
    if (s.mode === "scrub") {
      const unit = Math.max(56, s.r.clientWidth * .17), n = $$(".vsp", s.r).length, stepPx = stepSize(s.r);
      const at = Math.round(s.start / stepPx);
      s.target = Math.max(-at, Math.min(n - 1 - at, Math.trunc(dx / -unit)));   // finger left = forward
      pump();
    }
  }, {capture: true, passive: true});

  const stepSize = r => { const a = $$(".vsp", r); return a.length > 1 ? a[1].offsetLeft - a[0].offsetLeft : r.clientWidth * .46; };
  function pump() {
    if (!s || s.mode !== "scrub" || stepping) return;
    if (s.target === s.done) return;
    s.done += s.target > s.done ? 1 : -1;
    stepping = true;
    const stepPx = stepSize(s.r), at = Math.round(s.start / stepPx);
    s.r.scrollTo({left: (at + s.done) * stepPx, behavior: "smooth"});
    setTimeout(() => { stepping = false; pump(); }, 420);
  }

  window.addEventListener("touchmove", e => { if (s && s.mode === "scrub") e.preventDefault(); }, {passive: false, capture: true});
  window.addEventListener("contextmenu", e => { if (e.target.closest && e.target.closest("#hero")) e.preventDefault(); }, true);

  const end = () => {
    if (!s) return;
    const c = s; s = null; clearTimeout(c.timer);
    if (c.mode === "scrub") {
      swallow = true; setTimeout(() => swallow = false, 120);
      const h = hero(); if (h) h.classList.remove("vsscrub");
      setTimeout(glow, 450);
    }
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

/* Phone app only: the Android Back button steps back inside VibeSync instead of leaving it.
   1) closes whatever is open on top (title details, Swipe mode / panels, search, mood mixer, theme page,
      sound settings, ticket booking, trailer), 2) else goes back to the first tab (For you),
   3) only then leaves the app. The app calls window.vsBack() and leaves only when it returns false. */
(() => {
  if (window.vsBack) return;
  const $ = q => document.querySelector(q);
  const on = s => { const e = $(s); return !!e && e.classList.contains("on"); };
  const click = e => { if (e) { e.click(); return true; } return false; };
  const LAYERS = [
    [() => on("#trv"), () => click($("#trv [data-trclose]"))],
    [() => on("#bk"), () => click($("#bk [data-bkclose]"))],
    [() => on("#sndp"), () => { $("#sndp").classList.remove("on"); return true; }],
    [() => on("#themes"), () => { $("#themes").classList.remove("on"); return true; }],
    [() => on("#veil") || on("#sheet"), () => click($("#sheet [data-close]")) || click($("#veil"))],
    [() => on("#xp"), () => click($("#xp [data-xpclose]"))],
    [() => on("#themer"), () => click($("#themer [data-closethemer]"))],
    [() => on("#mixer"), () => click($("#mixer [data-closemixer]"))],
    [() => on("#search"), () => click($("#search [data-closesearch]"))],
  ];
  window.vsBack = () => {
    for (const [open, close] of LAYERS) { try { if (open()) return !!close(); } catch (e) { } }
    // nothing open: from another tab, back to the first one
    const first = document.querySelector("#tabs .tab[data-tab]"), sel = document.querySelector('#tabs .tab[aria-selected="true"]');
    if (first && sel && sel !== first) { first.click(); return true; }
    return false;
  };
})();
