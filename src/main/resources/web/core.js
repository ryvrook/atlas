/* Atlas core. Shared state, helpers, the API wrapper, a tiny event bus and toasts. Loaded first after Leaflet. */
(function () {
  "use strict";

  const A = window.Atlas = {};
  const $ = A.$ = (id) => document.getElementById(id);

  A.CHUNK = 32;

  // ---- text and numbers ---------------------------------------------------
  const ESC = { "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" };
  A.esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ESC[c]);
  // Colours come from player input, so only plain hex colours ever reach a style attribute.
  A.col = (c, d) => /^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?([0-9a-fA-F]{2})?$/.test(String(c || "")) ? String(c) : (d || "#8f877b");
  A.num = (n, digits) => n == null || n === "" || Number.isNaN(Number(n)) ? "none"
    : Number(n).toLocaleString(undefined, { maximumFractionDigits: digits == null ? 1 : digits });
  A.int = (n) => n == null || Number.isNaN(Number(n)) ? "none" : Math.round(Number(n)).toLocaleString();
  A.plural = (n, one, many) => A.int(n) + " " + (Number(n) === 1 ? one : (many || one + "s"));
  A.pretty = (s) => String(s == null ? "" : s).replace(/_/g, " ").replace(/\s+/g, " ").trim();

  // A colour from a tag, for nations and factions that have none.
  A.hashColor = (s) => {
    let h = 0;
    for (const ch of String(s || "")) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
    return "hsl(" + (h % 360) + ", 52%, 46%)";
  };

  function rgbOf(c) {
    let m = /^#([0-9a-f]{3,8})$/i.exec(c || "");
    if (!m) {
      const h = /^hsl\((\d+),\s*(\d+)%,\s*(\d+)%\)$/.exec(c || "");
      if (!h) return [140, 135, 125];
      const H = +h[1] / 360, S = +h[2] / 100, L = +h[3] / 100;
      const q = L < .5 ? L * (1 + S) : L + S - L * S, p = 2 * L - q;
      const f = (t) => { t = (t + 1) % 1; return t < 1 / 6 ? p + (q - p) * 6 * t : t < .5 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p; };
      return [f(H + 1 / 3) * 255, f(H) * 255, f(H - 1 / 3) * 255];
    }
    let x = m[1];
    if (x.length === 3) x = x.split("").map((ch) => ch + ch).join("");
    return [parseInt(x.slice(0, 2), 16), parseInt(x.slice(2, 4), 16), parseInt(x.slice(4, 6), 16)];
  }
  const lum = (rgb) => {
    const [r, g, b] = rgb.map((v) => { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  A.luminance = (c) => lum(rgbOf(c));
  A.contrast = (a, b) => { const x = lum(rgbOf(a)), y = lum(rgbOf(b)); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); };
  // Text colour that reads on top of the given background. Both inks are tried and the stronger one wins.
  const DARK_INK = "#1d1812", LIGHT_INK = "#fbf6ee";
  A.inkOn = (c) => A.contrast(c, DARK_INK) >= A.contrast(c, LIGHT_INK) ? DARK_INK : LIGHT_INK;

  // A faction tag chip with its own colour as the background, readable on both themes.
  // Inside a row that is itself a button the chip stays clickable but is not a separate tab stop.
  // A standalone server has no factions, so it never shows a faction chip even if a stray tag arrives.
  A.facChip = (tag, color, plain) => {
    if (!tag || A.state.standalone) return "";
    const bg = A.col(color, A.hashColor(tag));
    return "<span class='fac' data-tag='" + A.esc(tag) + "'" + (plain ? "" : " role='button' tabindex='0' aria-label='Open faction " + A.esc(tag) + "'") + " style='background:" + bg + ";color:" + A.inkOn(bg) + "'>" + A.esc(tag) + "</span>";
  };

  A.icon = (name, cls) => "<svg class='ic " + (cls || "") + "' aria-hidden='true'><use href='#i-" + name + "'/></svg>";

  // ---- time ---------------------------------------------------------------
  A.fmtLeft = (ms) => {
    if (ms == null || Number.isNaN(ms)) return "";
    if (ms <= 0) return "ending";
    const s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
    if (d) return d + "d " + h + "h";
    return (h ? h + "h " : "") + (h || m ? m + "m " : "") + r + "s";
  };
  A.fmtUptime = (ms) => {
    if (ms == null) return "none";
    const h = Math.floor(ms / 3600000), d = Math.floor(h / 24), rh = h % 24, m = Math.floor((ms % 3600000) / 60000);
    if (d) return A.plural(d, "day") + " " + A.plural(rh, "hour");
    if (h) return A.plural(h, "hour") + " " + A.plural(m, "minute");
    return A.plural(m, "minute");
  };
  const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  A.isToday = (ms) => sameDay(new Date(ms), new Date());
  A.clock = (ms) => new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  A.date = (ms) => new Date(ms).toLocaleDateString([], { month: "short", day: "numeric", year: new Date(ms).getFullYear() === new Date().getFullYear() ? undefined : "numeric" });
  // Time of day for today, otherwise the date and time.
  A.stamp = (ms) => !ms ? "" : A.isToday(ms) ? A.clock(ms) : A.date(ms) + " " + A.clock(ms);
  A.rel = (ms) => {
    if (!ms) return "";
    const s = Math.round((Date.now() - ms) / 1000);
    if (s < 45) return "just now";
    const m = Math.round(s / 60);
    if (m < 60) return m + " min ago";
    const h = Math.round(m / 60);
    if (h < 24) return h + (h === 1 ? " hour ago" : " hours ago");
    const d = Math.round(h / 24);
    if (d === 1) return "yesterday";
    return d + " days ago";
  };
  // Relative time with the clock or date behind it, for timelines.
  A.when = (ms) => {
    if (!ms) return "";
    return "<time datetime='" + new Date(ms).toISOString() + "' title='" + A.esc(new Date(ms).toLocaleString()) + "' data-at='" + ms + "'>" +
      A.esc(A.rel(ms)) + " <span class='faint'>" + A.esc(A.stamp(ms)) + "</span></time>";
  };

  // ---- storage ------------------------------------------------------------
  A.store = {
    get(k, d) { try { const v = localStorage.getItem("atlas." + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem("atlas." + k, JSON.stringify(v)); } catch (e) { /* storage may be blocked */ } }
  };

  // ---- state --------------------------------------------------------------
  A.state = {
    title: "", server: null, standalone: true, worlds: [], worldsAt: 0, tile: null,
    viewer: null, layers: {}, asTag: "", world: null,
    players: new Map(), offline: [], playersHidden: false, interval: 1000, lastPlayersAt: 0,
    factions: [], nations: null, territories: null, points: null, markers: [], worldEvents: [], bosses: [],
    feed: [], chat: [], kills: [], timeline: [], leaderboard: null, health: null,
    follow: null, tileVersions: new Map(), conn: "connecting",
    hiddenKinds: new Set(A.store.get("hiddenKinds", [])),
    // Endpoints an older server does not have. A 404 marks them missing and the feature hides itself.
    missing: {}
  };

  A.layerOn = (k) => !!(A.state.layers && A.state.layers[k]);
  A.isAdmin = () => !!(A.state.viewer && A.state.viewer.admin);
  // Only a linked viewer who is not viewing as a faction is ever sent their own markers, homes and deaths.
  A.hasOwnKinds = () => !!(A.state.viewer && A.state.viewer.linked && !A.state.viewer.viewingAs);
  A.currentWorld = () => A.state.worlds.find((w) => w.name === A.state.world) || null;
  A.worldLabel = (name) => { const w = A.state.worlds.find((x) => x.name === name); return w ? (w.displayName || w.name) : (name || ""); };

  // ---- api ----------------------------------------------------------------
  // Every path is relative so the map also works under a sub path behind a reverse proxy.
  // Errors carry network (no answer) or status (an HTTP error or a body that is not JSON), so callers can tell
  // a server problem from a bug in the page. Identical GET requests that are still running share one fetch.
  const inflight = new Map();
  A.api = (path, opts) => {
    const sep = path.includes("?") ? "&" : "?";
    const url = A.state.asTag ? path + sep + "as=" + encodeURIComponent(A.state.asTag) : path;
    const get = !opts || !opts.method || opts.method === "GET";
    if (get && inflight.has(url)) return inflight.get(url);
    const p = fetch(url, Object.assign({ credentials: "same-origin", headers: { Accept: "application/json" } }, opts || {})).then((r) => {
      if (!r.ok) { const e = new Error("HTTP " + r.status); e.status = r.status; throw e; }
      return r.json().catch(() => { const e = new Error("Not JSON"); e.status = r.status; throw e; });
    }, (err) => { const e = new Error("Network " + (err && err.message)); e.network = true; throw e; });
    if (!get) return p;
    inflight.set(url, p);
    const done = () => { if (inflight.get(url) === p) inflight.delete(url); };
    p.then(done, done);
    return p;
  };
  A.isServerError = (e) => !!(e && (e.network || e.status));
  // A real bug is logged with its stack and shown once, so it is never mistaken for a server outage.
  let bugShown = false;
  A.reportBug = (e) => {
    if (window.console) console.error("Atlas error", e && e.stack ? e.stack : e);
    if (bugShown) return;
    bugShown = true;
    A.toast("Something on this page went wrong. The browser console has the details.", "bad");
  };
  // For endpoints newer than some servers. A 404 or 405 marks the feature missing and resolves to null.
  A.apiOptional = (feature, path) => {
    if (A.state.missing[feature]) return Promise.resolve(null);
    return A.api(path).catch((e) => {
      if (e.status === 404 || e.status === 405 || e.status === 501) { A.state.missing[feature] = true; A.emit("missing", feature); return null; }
      throw e;
    });
  };
  A.W = () => encodeURIComponent(A.state.world || "");

  // ---- events -------------------------------------------------------------
  const handlers = new Map();
  A.on = (name, fn) => { if (!handlers.has(name)) handlers.set(name, []); handlers.get(name).push(fn); };
  A.emit = (name, data) => {
    for (const fn of handlers.get(name) || []) {
      try { fn(data); } catch (e) { if (window.console) console.warn("Atlas handler for " + name + " failed", e); }
    }
  };

  // ---- toasts -------------------------------------------------------------
  A.toast = (msg, kind) => {
    const box = $("toasts");
    if (!box) return;
    const el = document.createElement("div");
    el.className = "toast " + (kind || "");
    const ic = kind === "good" ? "copy" : kind === "bad" ? "alert" : "clock";
    el.innerHTML = A.icon(kind === "good" && /link/i.test(msg) ? "link" : ic, "sm") + "<span class='grow'>" + A.esc(msg) + "</span>" +
      "<button class='icon-btn toast-x' aria-label='Dismiss'>" + A.icon("close", "sm") + "</button>";
    const close = () => { el.classList.add("out"); setTimeout(() => el.remove(), 300); };
    el.querySelector(".toast-x").addEventListener("click", close);
    box.appendChild(el);
    while (box.children.length > 3) box.removeChild(box.firstChild);
    setTimeout(close, kind === "bad" ? 5200 : 3200);
  };

  // ---- clipboard ----------------------------------------------------------
  // The async clipboard needs a secure page, and most game servers serve plain http, so a fallback is kept.
  A.copy = (text, label) => {
    const done = () => { A.toast((label || "Copied") + " to the clipboard", "good"); return true; };
    const fallback = () => {
      const ta = document.createElement("textarea");
      ta.value = text; ta.setAttribute("readonly", ""); ta.style.position = "fixed"; ta.style.opacity = "0";
      document.body.appendChild(ta); ta.select();
      let ok = false;
      try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
      ta.remove();
      if (ok) return done();
      A.toast("Copy did not work. " + text, "bad");
      return false;
    };
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text).then(done, fallback);
    return Promise.resolve(fallback());
  };

  // A small throttle for renders driven by live events.
  A.throttle = (fn, ms) => {
    let t = null, last = 0, pending = false;
    return function () {
      const now = Date.now();
      if (now - last >= ms && !t) { last = now; fn(); return; }
      pending = true;
      if (!t) t = setTimeout(() => { t = null; if (pending) { pending = false; last = Date.now(); fn(); } }, Math.max(0, ms - (now - last)));
    };
  };

  // ---- keyed lists ----------------------------------------------------------
  // A live list keeps its row elements and only creates, removes or moves the rows that changed. While the
  // pointer or the keyboard focus is inside the list, rows are only updated in place and the structural
  // changes wait until the pointer and focus leave, so taps and focus are never lost to a redraw.
  // One document listener ends a press for whichever list it started in, so lists that are thrown away leave nothing behind.
  let pressed = null;
  const release = () => { const g = pressed; pressed = null; if (g) { g.down = false; setTimeout(g.flush, 350); } };
  document.addEventListener("pointerup", release, true);
  document.addEventListener("pointercancel", release, true);
  A.listGuard = (el) => {
    if (el._guard) return el._guard;
    const g = { over: false, down: false, pending: null };
    g.busy = () => g.over || g.down || el.contains(document.activeElement);
    g.flush = () => { if (el.isConnected && !g.busy() && g.pending) { const f = g.pending; g.pending = null; f(); } };
    el.addEventListener("pointerenter", (e) => { if (e.pointerType === "mouse") g.over = true; });
    el.addEventListener("pointerleave", () => { g.over = false; setTimeout(g.flush, 0); });
    el.addEventListener("pointerdown", () => { g.down = true; pressed = g; });
    el.addEventListener("focusout", () => setTimeout(g.flush, 0));
    el._guard = g;
    return g;
  };
  // box holds only keyed rows. items is the wanted order. spec has key(item), create(item) returning an
  // element and update(el, item). redo runs again once the list is free when changes had to wait.
  A.keyed = (box, items, spec, redo) => {
    const g = A.listGuard(box);
    const have = new Map();
    for (const el of Array.from(box.children)) have.set(el.dataset.key, el);
    const busy = g.busy();
    const want = new Set();
    let waiting = false;
    let cursor = box.firstElementChild;
    for (const it of items) {
      const k = String(spec.key(it));
      if (want.has(k)) continue;
      want.add(k);
      let el = have.get(k);
      if (!el) {
        if (busy) { waiting = true; continue; }
        el = spec.create(it);
        el.dataset.key = k;
      }
      spec.update(el, it);
      if (busy) continue;
      if (el === cursor) cursor = cursor.nextElementSibling;
      else box.insertBefore(el, cursor);
    }
    for (const [k, el] of have) {
      if (want.has(k)) continue;
      if (busy) { waiting = true; continue; }
      el.remove();
    }
    if (busy && waiting && redo) g.pending = redo;
    return waiting;
  };

  // Elements that act as buttons without being one answer Enter and Space like a real button.
  document.addEventListener("keydown", (e) => {
    const t = e.target;
    if ((e.key !== "Enter" && e.key !== " ") || !t || !t.getAttribute || t.getAttribute("role") !== "button" || t.tagName === "BUTTON") return;
    if (e.defaultPrevented) return;
    e.preventDefault();
    t.click();
  });

  window.addEventListener("unhandledrejection", (e) => {
    // Every fetch has its own handler. This only keeps a stray rejection out of the console.
    if (e.reason && e.reason.status) e.preventDefault();
  });
})();
