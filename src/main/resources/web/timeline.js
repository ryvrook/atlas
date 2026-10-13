/* Atlas timeline. Daily snapshots of each world replayed with a date slider, the events of each day on the map
   and in a feed, and small charts over the days the server holds. Loaded after panel.js. */
(function () {
  "use strict";

  const A = window.Atlas;
  if (!A || A.noLeaflet || !A.map) return;
  const { $, esc, num, state } = A;
  const map = A.map;
  const DAY = 86400000;

  // ---- dates --------------------------------------------------------------
  // Timeline dates are server local ISO dates. They are handled as UTC midnights so no time zone moves a day.
  const parseIso = (s) => { const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(s || "")); return m ? Date.UTC(+m[1], +m[2] - 1, +m[3]) : NaN; };
  const isoOf = (t) => new Date(t).toISOString().slice(0, 10);
  const dayName = (s, weekday) => {
    const t = parseIso(s);
    if (isNaN(t)) return String(s || "");
    const d = new Date(t);
    return d.toLocaleDateString([], { timeZone: "UTC", weekday: weekday ? "short" : undefined, month: "short", day: "numeric", year: d.getUTCFullYear() !== new Date().getFullYear() ? "numeric" : undefined });
  };

  A.dayName = dayName;

  // ---- state --------------------------------------------------------------
  // index is the slot on the slider, -1 while the live map is shown. A slot is one calendar day from the first
  // day held to today. Its kind is snap (a snapshot), events (events only, the map of the snapshot before it),
  // pruned (removed by retention, a gap) or missing (nothing was kept that day, also a gap).
  const SPEEDS = [500, 1000, 2000];
  const T = A.tl = {
    world: null, info: null, error: null, slots: [], index: -1, playing: false, timer: null,
    speed: SPEEDS.includes(A.store.get("tlSpeed", 1000)) ? A.store.get("tlSpeed", 1000) : 1000,
    changedOn: A.store.get("tlChanged", true), barOpen: A.store.get("tlBar", false),
    stats: new Map(), events: new Map(), changed: new Map(), live: null, liveAt: 0, liveKey: "",
    want: null, off: new Set(A.store.get("tlOff", [])), seq: 0, statsWanted: false
  };
  const selectable = (s) => !!s && (s.kind === "snap" || s.kind === "events");
  const cur = () => (T.index >= 0 && T.index < T.slots.length ? T.slots[T.index] : null);
  const hasSnaps = () => T.slots.some((s) => s.kind === "snap");
  const available = () => A.layerOn("timeline") && !!T.info && T.info.enabled !== false && hasSnaps();
  const barShown = () => available() && (T.barOpen || !!state.tlDate);
  const key = (date) => (state.world || "") + "|" + date;
  const base = () => "api/timeline/" + A.W();

  function buildSlots(info) {
    const days = (Array.isArray(info.days) ? info.days : []).filter((d) => d && !isNaN(parseIso(d.date)));
    const by = new Map(days.map((d) => [d.date, d]));
    const ends = days.map((d) => parseIso(d.date));
    const today = parseIso(info.today);
    if (!ends.length) return [];
    let first = Math.min.apply(null, ends);
    const last = Math.max(Math.max.apply(null, ends), isNaN(today) ? -Infinity : today);
    // A very long gap would make every day a sliver, so the slider keeps the newest 400 days at most.
    first = Math.max(first, last - 399 * DAY);
    const slots = [];
    let lastSnap = null;
    for (let t = first; t <= last; t += DAY) {
      const date = isoOf(t);
      const day = by.get(date) || null;
      const kind = !day ? "missing" : day.pruned ? "pruned" : day.snapshot ? "snap" : "events";
      if (kind === "snap") lastSnap = date;
      slots.push({ date, day, kind, tiles: kind === "snap" ? date : kind === "events" ? lastSnap : null });
    }
    return slots;
  }

  // ---- map layers ---------------------------------------------------------
  const changedGroup = L.layerGroup().addTo(map);
  const marks = L.layerGroup().addTo(map);

  // ---- loading ------------------------------------------------------------
  function reset(world) {
    stop();
    T.world = world;
    T.info = null; T.error = null; T.slots = []; T.live = null; T.liveAt = 0; T.liveKey = "";
    T.stats.clear(); T.events.clear(); T.changed.clear();
    statsQueue = [];
    if (state.tlDate) goLive(true);
    T.index = -1;
  }
  A.on("world", (name) => { if (name !== T.world) { reset(name); sync(); } });

  A.loadTimeline = () => {
    const world = state.world;
    if (world !== T.world) reset(world);
    if (!world || !A.layerOn("timeline")) {
      T.info = null; T.error = null; T.slots = [];
      if (state.tlDate) goLive(true);
      sync();
      return Promise.resolve();
    }
    const seq = ++T.seq;
    return A.api(base()).then((d) => {
      if (seq !== T.seq || world !== state.world) return;
      T.info = d || {};
      T.error = null;
      const keep = state.tlDate;
      T.slots = buildSlots(T.info);
      const i = keep ? T.slots.findIndex((s) => s.date === keep) : -1;
      if (keep && selectable(T.slots[i])) T.index = i;
      else if (keep) goLive(true);
      if (T.want) {
        const w = T.want;
        T.want = null;
        const j = T.slots.findIndex((s) => s.date === w);
        if (selectable(T.slots[j])) { T.barOpen = true; select(j); }
        else A.toast("The timeline of " + A.worldLabel(world) + " has nothing for " + dayName(w));
      }
      if (T.statsWanted) queueStats();
      sync();
    }).catch((e) => {
      if (seq !== T.seq) return;
      T.info = null; T.error = e; T.slots = [];
      if (state.tlDate) goLive(true);
      sync();
    });
  };
  // A shared link names a day before the days of its world are known, so it waits for them.
  A.timelineWant = (date) => {
    if (isNaN(parseIso(date))) return;
    const j = T.info && state.world === T.world ? T.slots.findIndex((s) => s.date === date) : -1;
    if (selectable(T.slots[j])) { T.barOpen = true; select(j); } else T.want = date;
  };
  A.on("timelineDay", (d) => {
    if (!d || d.world !== state.world || !A.layerOn("timeline")) return;
    const shown = barShown();
    A.loadTimeline().then(() => { if (shown && d.date) A.toast("A snapshot of " + dayName(d.date) + " was added to the timeline", "good"); });
  });

  // ---- selecting a day ------------------------------------------------------
  function select(i) {
    const s = T.slots[i];
    if (!selectable(s)) return;
    const was = state.tlDate;
    T.index = i;
    state.tlDate = s.date;
    state.tlTiles = s.tiles;
    document.body.classList.add("tl-past");
    A.swapDayTiles();
    loadChanged(s);
    loadDay(s);
    wantStats(s);
    const nx = nextIndex(i, 1);
    if (nx != null && nx >= 0 && T.slots[nx].tiles) A.preloadDay(T.slots[nx].tiles);
    if (!was) A.closeCtx();
    saveUrlSoon();
    sync();
  }
  function goLive(quiet) {
    stop();
    const was = !!state.tlDate;
    T.index = -1;
    state.tlDate = null;
    state.tlTiles = null;
    document.body.classList.remove("tl-past");
    changedGroup.clearLayers();
    marks.clearLayers();
    if (was) { A.applyOverlays(); A.saveUrl(); }
    if (!quiet) sync();
  }
  // The slot one step away in that direction. -1 means the live map, null means there is nowhere to go.
  function nextIndex(from, dir) {
    const n = T.slots.length;
    if (from < 0 && dir > 0) return null;
    for (let i = (from < 0 ? n : from) + dir; i >= 0 && i < n; i += dir) if (selectable(T.slots[i])) return i;
    return dir > 0 ? -1 : null;
  }
  function step(dir) {
    if (!available()) return;
    stop();
    const i = nextIndex(T.index, dir);
    if (i == null) return;
    if (i < 0) goLive(); else select(i);
  }
  const saveUrlSoon = A.throttle(() => A.saveUrl(), 400);

  // ---- playing --------------------------------------------------------------
  // Play runs from the selected day to the newest one, one day per step, and stops there. From the live map or
  // the newest day it starts again at the first day held.
  function play() {
    if (!available()) return;
    if (T.playing) { stop(); return; }
    if (T.index < 0 || nextIndex(T.index, 1) === -1) {
      const first = T.slots.findIndex(selectable);
      if (first < 0) return;
      select(first);
    }
    T.playing = true;
    schedule();
    sync();
  }
  function schedule() {
    clearTimeout(T.timer);
    T.timer = setTimeout(() => {
      if (!T.playing) return;
      const i = nextIndex(T.index, 1);
      if (i == null || i < 0) { stop(); return; }
      select(i);
      if (nextIndex(i, 1) === -1) { stop(); return; }
      schedule();
    }, T.speed);
  }
  function stop() {
    if (!T.playing && !T.timer) return;
    T.playing = false;
    clearTimeout(T.timer);
    T.timer = null;
    syncPlay();
  }
  function setSpeed(v) {
    v = Number(v);
    if (!SPEEDS.includes(v)) return;
    T.speed = v;
    A.store.set("tlSpeed", v);
    $("tl-speed").value = String(v);
    const ts = $("tl-t-speed");
    if (ts) ts.value = String(v);
    if (T.playing) schedule();
  }
  function setChanged(on) {
    T.changedOn = !!on;
    A.store.set("tlChanged", T.changedOn);
    const s = cur();
    if (T.changedOn && s) loadChanged(s); else changedGroup.clearLayers();
    sync();
  }
  function openBar() { T.barOpen = true; A.store.set("tlBar", true); sync(); }
  function closeBar() { T.barOpen = false; A.store.set("tlBar", false); goLive(true); sync(); }

  // ---- changed tiles --------------------------------------------------------
  // The tiles whose image differs from the snapshot before, at the zoom on screen. The first day held lists
  // every tile, so it gets no tint.
  function loadChanged(s) {
    if (!s || s.kind !== "snap" || !T.changedOn || !A.layerOn("terrain")) { changedGroup.clearLayers(); return; }
    const k = key(s.date);
    if (T.changed.has(k)) { drawChanged(); return; }
    changedGroup.clearLayers();
    A.api(base() + "/" + encodeURIComponent(s.date) + "/changed").then((d) => {
      T.changed.set(k, d && Array.isArray(d.tiles) ? d : { first: true, tiles: [] });
      if (cur() === s) { drawChanged(); drawTabSoon(); }
    }).catch(() => { /* the day shows without the tint */ });
  }
  function drawChanged() {
    changedGroup.clearLayers();
    const s = cur();
    if (!s || s.kind !== "snap" || !T.changedOn) return;
    const d = T.changed.get(key(s.date));
    if (!d || d.first) return;
    const l = Math.max(A.geo.minNative, Math.min(A.geo.maxNative, map.getZoom()));
    const z = A.geo.lToZ(l);
    const size = 256 / Math.pow(2, l);
    let n = 0;
    for (const r of d.tiles) {
      if (!Array.isArray(r) || Number(r[0]) !== z) continue;
      if (++n > 4000) break;
      const x = Number(r[1]) * size, y = Number(r[2]) * size;
      changedGroup.addLayer(L.rectangle([A.toLatLng(x, y), A.toLatLng(x + size, y + size)], { renderer: A.renderers.tlChanged, interactive: false, className: "tl-changed", weight: 1.5, fillOpacity: 1 }));
    }
  }
  map.on("zoomend", () => { if (state.tlDate) drawChanged(); });

  // ---- events ---------------------------------------------------------------
  // How each event kind is drawn, as a symbol, a colour and a short name. A kind not listed here is matched by
  // its first word, and anything else is a plain line.
  const KINDS = {
    join: ["user", "#2f8d58", "Joined"], leave: ["logout", "#6d7680", "Left"], death: ["skull", "#7d3a33", "Death"], kill: ["swords", "#b0352c", "Kill"],
    "worldevent.start": ["bolt", "#b4532a", "World event"], "worldevent.end": ["portal", "#8a6a3a", "World event over"], worldevent: ["bolt", "#b4532a", "World event"],
    "feats.unlock": ["trophy", "#a87a17", "Feat"], "feats.milestone": ["crown", "#a87a17", "Milestone"], "feats.first": ["spawn", "#c7951c", "Server first"], feats: ["trophy", "#a87a17", "Feats"],
    dominion: ["shield", "#4f6d8a", "Factions"], "marker.add": ["pin", "#4a8ea4", "Marker added"], "marker.remove": ["pin", "#6d7680", "Marker removed"], marker: ["pin", "#4a8ea4", "Marker"],
    snapshot: ["history", "#5f6f86", "Snapshot"], dropped: ["alert", "#9a6614", "Limit reached"]
  };
  const kindLook = (k) => {
    k = String(k || "").toLowerCase();
    return KINDS[k] || KINDS[k.split(".")[0]] || ["dot", "#6d7680", A.pretty(k.split(".").join(" ")).replace(/^./, (c) => c.toUpperCase()) || "Event"];
  };
  // The filter chips of the feed. Each group matches kinds by prefix, and Other takes everything else.
  const GROUPS = [["players", "Joins", ["join", "leave"]], ["kills", "Deaths", ["death", "kill"]], ["worldevent", "World events", ["worldevent"]],
    ["feats", "Feats", ["feats"]], ["dominion", "Factions", ["dominion"]], ["marker", "Markers", ["marker"]], ["other", "Other", []]];
  const groupOf = (k) => {
    k = String(k || "").toLowerCase();
    for (const g of GROUPS) if (g[2].some((p) => k === p || k.startsWith(p + "."))) return g[0];
    return "other";
  };
  const shownEvent = (e) => e && !T.off.has(groupOf(e.kind));

  function loadDay(s) {
    const k = key(s.date);
    if (T.events.has(k)) { drawMarks(); drawTabSoon(); return; }
    marks.clearLayers();
    if (!s.day || !s.day.events) { T.events.set(k, { items: [], dropped: false }); drawMarks(); drawTabSoon(); return; }
    T.evError = null;
    A.api(base() + "/" + encodeURIComponent(s.date) + "/events").then((d) => {
      T.events.set(k, { items: d && Array.isArray(d.items) ? d.items.filter((e) => e && typeof e === "object") : [], dropped: !!(d && d.dropped) });
      if (cur() === s) { drawMarks(); drawTabSoon(); }
    }).catch((e) => {
      if (e.status === 404) T.events.set(k, { items: [], dropped: false });
      else T.evError = e;
      if (cur() === s) drawTabSoon();
    });
  }
  const placedEv = (e) => e.x != null && e.z != null && isFinite(Number(e.x)) && isFinite(Number(e.z));
  // The events of the shown day as markers in a pane of their own, so they stay bright over the dimmed live map.
  function drawMarks() {
    marks.clearLayers();
    const s = cur();
    const d = s && T.events.get(key(s.date));
    if (!d) return;
    let list = d.items.filter((e) => shownEvent(e) && placedEv(e) && A.inLimit(e.x, e.z));
    if (list.length > 500) list = list.slice(-500);
    for (const e of list) {
      const k = kindLook(e.kind);
      const m = A.marker(A.toLatLng(Number(e.x), Number(e.z)), {
        pane: "tlMarks", icon: A.symIcon(k[0], k[1], { size: "small" }), title: "", riseOnHover: true,
        label: (e.text || k[2]) + ", " + k[2].toLowerCase() + " at " + A.clock(e.at)
      });
      A.tip(m, A.card(esc(k[2]) + " <span class='tc-thin'>" + esc(A.clock(e.at)) + "</span>", esc(e.text || ""), { x: e.x, z: e.z }), { offset: [0, -12] });
      m._ev = e;
      marks.addLayer(m);
    }
  }

  // The live feed is the last 24 hours from the range route. With Other switched off the server filters by
  // kind, otherwise the page does.
  function loadLive(force) {
    if (!A.layerOn("timeline") || !state.world || T.error) return;
    const on = GROUPS.filter((g) => !T.off.has(g[0]));
    const kinds = T.off.has("other") ? on.flatMap((g) => g[2]) : [];
    const k = state.world + "|" + kinds.join(",");
    if (!force && T.liveKey === k && Date.now() - T.liveAt < 30000) return;
    T.liveKey = k;
    T.liveAt = Date.now();
    const world = state.world;
    if (T.off.has("other") && !kinds.length) { T.live = { items: [], truncated: false }; drawTabSoon(); return; }
    A.api(base() + "/events?from=" + (Date.now() - DAY) + "&limit=500" + (kinds.length ? "&kind=" + encodeURIComponent(kinds.join(",")) : "")).then((d) => {
      if (world !== state.world) return;
      T.live = { items: d && Array.isArray(d.items) ? d.items : [], truncated: !!(d && d.truncated) };
      drawTabSoon();
    }).catch((e) => { T.liveAt = 0; if (world === state.world) { T.live = { items: [], truncated: false, error: e }; drawTabSoon(); } });
  }

  // ---- stats ----------------------------------------------------------------
  // Stats are one small request per day. They load lazily once the tab is opened, the newest day first, two
  // at a time with a short pause between, and a selected day jumps the queue.
  let statsQueue = [];
  let statsRunning = 0;
  const statsInflight = new Set();
  function queueStats() {
    T.statsWanted = true;
    const todo = T.slots.filter((s) => s.day && s.day.stats && !s.day.pruned && !T.stats.has(key(s.date)) && !statsInflight.has(key(s.date))).reverse();
    statsQueue = todo.map((s) => s.date);
    pumpStats();
  }
  function wantStats(s) {
    if (!s.day || !s.day.stats || T.stats.has(key(s.date)) || statsInflight.has(key(s.date))) return;
    statsQueue = [s.date].concat(statsQueue.filter((d) => d !== s.date));
    pumpStats();
  }
  function pumpStats() {
    while (statsRunning < 2 && statsQueue.length) {
      const date = statsQueue.shift();
      const k = key(date);
      if (T.stats.has(k) || statsInflight.has(k)) continue;
      statsRunning++;
      statsInflight.add(k);
      const done = () => { statsRunning--; statsInflight.delete(k); setTimeout(pumpStats, 120); sparksSoon(); drawTabSoon(); };
      A.api(base() + "/" + encodeURIComponent(date) + "/stats").then((d) => { T.stats.set(k, d && typeof d === "object" ? d : null); done(); })
        .catch((e) => { if (e.status === 404 || e.status === 403 || e.status === 400) T.stats.set(k, null); done(); });
    }
  }

  // ---- canvas drawing ---------------------------------------------------------
  const cssVar = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim() || "#888";
  function fitCanvas(c) {
    const w = c.clientWidth, h = c.clientHeight;
    if (!w || !h) return null;
    const dpr = window.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
    const g = c.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    return { g, w, h };
  }
  function hatch(g, x0, x1, y0, y1, color) {
    g.save();
    g.beginPath(); g.rect(x0, y0, x1 - x0, y1 - y0); g.clip();
    g.strokeStyle = color; g.lineWidth = 1; g.globalAlpha = 0.55;
    g.beginPath();
    for (let x = x0 - (y1 - y0); x < x1; x += 4) { g.moveTo(x, y1); g.lineTo(x + (y1 - y0), y0); }
    g.stroke();
    g.restore();
  }
  // The day strip behind the slider. A snapshot day is a bar as tall as its count of changed tiles, an events
  // only day a short tick, a pruned day a hatched gap and a day with nothing an empty gap. The thumb width in
  // style.css is THUMB, so each column sits under the thumb position of its day.
  const THUMB = 16;
  function drawStrip() {
    const strip = $("tl-strip");
    if (!strip || $("tl-bar").hidden) return;
    const f = fitCanvas(strip);
    if (!f) return;
    const { g, w, h } = f;
    const n = T.slots.length;
    if (!n) return;
    const stepW = (w - THUMB) / n;
    const x = (i) => THUMB / 2 + i * stepW;
    const accent = cssVar("--accent"), faint = cssVar("--faint"), line = cssVar("--border-strong"), good = cssVar("--good");
    const firstSnap = T.slots.findIndex((s) => s.kind === "snap");
    let max = 1;
    T.slots.forEach((s, i) => { if (s.kind === "snap" && i !== firstSnap) max = Math.max(max, Number(s.day.changed) || 0); });
    // Bars are scaled to the busiest day, with a floor so a quiet snapshot still shows.
    const colW = Math.max(2, Math.min(10, stepW * 0.62));
    g.fillStyle = line;
    g.fillRect(THUMB / 2, h - 2, w - THUMB, 1);
    T.slots.forEach((s, i) => {
      const cx = x(i);
      const sel = i === T.index;
      if (s.kind === "pruned") { hatch(g, cx - stepW / 2 + 1, cx + stepW / 2 - 1, 5, h - 3, faint); return; }
      if (s.kind === "missing") return;
      if (s.kind === "events") { g.fillStyle = sel ? accent : faint; g.globalAlpha = sel ? 1 : 0.7; g.fillRect(cx - colW / 2, h - 7, colW, 4); g.globalAlpha = 1; return; }
      const share = i === firstSnap ? 0.5 : (Number(s.day.changed) || 0) / max;
      const bh = 3 + (h - 9) * share;
      g.fillStyle = i === firstSnap && !sel ? faint : accent;
      g.globalAlpha = sel ? 1 : 0.5;
      g.fillRect(cx - colW / 2, h - 3 - bh, colW, bh);
      g.globalAlpha = 1;
    });
    // The live end of the slider.
    g.fillStyle = good;
    g.beginPath(); g.arc(x(n), h - 6, 3.5, 0, Math.PI * 2); g.fill();
  }
  // One small line chart over every slot. Days without a value break the line, pruned days are hatched.
  function drawSpark(c, metric) {
    const f = fitCanvas(c);
    if (!f) return;
    const { g, w, h } = f;
    const n = T.slots.length;
    if (!n) return;
    const pad = 4;
    const x = (i) => n === 1 ? w / 2 : pad + i * (w - 2 * pad) / (n - 1);
    const vals = T.slots.map((s) => valueOf(s, metric));
    const got = vals.filter((v) => v != null);
    const accent = cssVar("--accent"), faint = cssVar("--faint"), text = cssVar("--text");
    T.slots.forEach((s, i) => { if (s.kind === "pruned") hatch(g, x(i) - 3, x(i) + 3, 2, h - 2, faint); });
    if (got.length) {
      let lo = Math.min.apply(null, got), hi = Math.max.apply(null, got);
      if (hi === lo) { hi += 1; lo = Math.max(0, lo - 1); }
      const y = (v) => h - 4 - (v - lo) / (hi - lo) * (h - 10);
      g.strokeStyle = accent; g.lineWidth = 1.6; g.lineJoin = "round";
      let open = false;
      g.beginPath();
      vals.forEach((v, i) => {
        if (v == null) { open = false; return; }
        if (!open) { g.moveTo(x(i), y(v)); open = true; } else g.lineTo(x(i), y(v));
      });
      g.stroke();
      g.fillStyle = accent;
      vals.forEach((v, i) => {
        const lone = v != null && (i === 0 || vals[i - 1] == null) && (i === n - 1 || vals[i + 1] == null);
        if (lone) { g.beginPath(); g.arc(x(i), y(v), 1.8, 0, Math.PI * 2); g.fill(); }
      });
      const s = cur();
      if (s && vals[T.index] != null) {
        g.strokeStyle = text; g.globalAlpha = 0.35; g.lineWidth = 1;
        g.beginPath(); g.moveTo(x(T.index), 1); g.lineTo(x(T.index), h - 1); g.stroke();
        g.globalAlpha = 1;
        g.beginPath(); g.arc(x(T.index), y(vals[T.index]), 3, 0, Math.PI * 2); g.fill();
      }
    } else {
      g.fillStyle = faint;
      g.fillRect(pad, h / 2, w - 2 * pad, 1);
    }
  }
  const METRICS = [["exploredChunks", "Explored chunks"], ["peakOnline", "Peak online"], ["kills", "Kills"], ["featsPoints", "Feats points"]];
  function valueOf(s, metric) {
    if (!s || s.kind !== "snap") return null;
    const st = T.stats.get(key(s.date));
    if (!st) return null;
    const v = metric === "featsPoints" ? (st.feats ? st.feats.totalPoints : null) : st[metric];
    return v == null || !isFinite(Number(v)) ? null : Number(v);
  }
  function drawSparks() {
    const box = $("tl-spark");
    if (!box || !box.isConnected) return;
    const feats = T.slots.some((s) => valueOf(s, "featsPoints") != null);
    box.querySelectorAll(".spark").forEach((row) => {
      const m = row.dataset.m;
      row.hidden = m === "featsPoints" && !feats;
      if (row.hidden) return;
      // The figure next to the label is the shown day's, or the newest day's while the map is live.
      let v = null, at = null;
      const s = cur();
      if (s) { v = valueOf(s, m); at = s.date; } else for (let i = T.slots.length - 1; i >= 0; i--) { const x = valueOf(T.slots[i], m); if (x != null) { v = x; at = T.slots[i].date; break; } }
      const b = row.querySelector("b");
      const t = v == null ? "none" : num(v, 0);
      if (b.textContent !== t) b.textContent = t;
      b.title = at ? dayName(at) : "";
      drawSpark(row.querySelector("canvas"), m);
    });
    const loading = T.slots.some((s) => s.day && s.day.stats && !T.stats.has(key(s.date)));
    const note = $("tl-spark-note");
    if (note) note.hidden = !loading;
  }
  const sparksSoon = A.throttle(drawSparks, 250);

  // ---- bar and chip -----------------------------------------------------------
  const bar = $("tl-bar");
  const range = $("tl-range");
  function drawBar() {
    if (bar.hidden) return;
    const n = T.slots.length;
    const s = cur();
    range.max = String(n);
    range.value = String(T.index < 0 ? n : T.index);
    const label = s ? dayName(s.date, true) : "Live";
    range.setAttribute("aria-valuetext", s ? label + (s.kind === "events" ? ", events only" : "") : "Live map");
    $("tl-label").textContent = label;
    $("tl-label").classList.toggle("past", !!s);
    const first = T.slots.find(selectable);
    $("tl-first").textContent = first ? dayName(first.date) : "";
    $("tl-speed").value = String(T.speed);
    const ch = $("tl-changed");
    ch.setAttribute("aria-pressed", String(T.changedOn));
    syncPlay();
    drawStrip();
  }
  function syncPlay() {
    for (const id of ["tl-play", "tl-t-play"]) {
      const b = $(id);
      if (!b) continue;
      const lbl = T.playing ? "Pause the timeline" : "Play the timeline";
      b.setAttribute("aria-label", lbl);
      b.setAttribute("aria-pressed", String(T.playing));
      const u = b.querySelector("use");
      if (u) u.setAttribute("href", T.playing ? "#i-pause" : "#i-play");
      const t = b.querySelector(".lbl");
      if (t) t.textContent = T.playing ? "Pause" : "Play";
    }
  }
  function drawChip() {
    const chip = $("tl-chip");
    const s = cur();
    if (!s) { chip.hidden = true; return; }
    if (!$("tl-chip-txt")) {
      chip.innerHTML = A.icon("history", "sm") + "<span id='tl-chip-txt'></span><button class='btn small' id='tl-live'>Return to live</button>";
      $("tl-live").addEventListener("click", () => goLive());
    }
    const note = s.kind === "events" ? (s.tiles ? "map of " + dayName(s.tiles) : "no map that day") : s.day && s.day.partial ? "some tiles from the day before" : "";
    const h = "Viewing <b>" + esc(dayName(s.date, true)) + "</b>" + (note ? " <span class='muted'>" + esc(note) + "</span>" : "");
    const t = $("tl-chip-txt");
    if (t._h !== h) { t.innerHTML = h; t._h = h; }
    chip.hidden = false;
  }
  function layout() {
    const on = barShown();
    bar.hidden = !on;
    if (document.body.classList.contains("tl-open") !== on) {
      document.body.classList.toggle("tl-open", on);
      setTimeout(() => { map.invalidateSize(); drawStrip(); }, 40);
    }
  }
  function sync() {
    const btn = $("timeline-btn");
    btn.hidden = !available();
    btn.setAttribute("aria-pressed", String(barShown()));
    btn.setAttribute("aria-label", barShown() ? "Close the timeline" : "Open the timeline");
    layout();
    drawBar();
    drawChip();
    drawTabSoon();
  }

  $("tl-play").addEventListener("click", play);
  $("tl-speed").addEventListener("change", (e) => setSpeed(e.target.value));
  $("tl-changed").addEventListener("click", () => setChanged(!T.changedOn));
  $("tl-close").addEventListener("click", closeBar);
  $("timeline-btn").addEventListener("click", () => { if (barShown()) closeBar(); else openBar(); });
  // Dragging onto a gap moves on to the nearest day in the direction of travel, so a pruned day is never shown.
  range.addEventListener("input", () => {
    stop();
    const n = T.slots.length;
    let v = Number(range.value);
    if (v >= n) { goLive(); return; }
    if (!selectable(T.slots[v])) {
      const from = T.index < 0 ? n : T.index;
      const dir = v > from ? 1 : -1;
      let j = null;
      for (const d of [dir, -dir]) for (let i = v + d; j == null && i >= 0 && i < n; i += d) if (selectable(T.slots[i])) j = i;
      if (j == null) { range.value = String(from); return; }
      v = j;
    }
    if (v !== T.index) select(v); else drawBar();
  });

  // ---- keys -----------------------------------------------------------------
  // While the bar is shown, left and right step a day, space plays and pauses and Home returns to the live map.
  // They are taken before the map sees them, so the arrows do not also pan.
  document.addEventListener("keydown", (e) => {
    if (!barShown() || e.metaKey || e.ctrlKey || e.altKey) return;
    const t = e.target;
    if (document.querySelector("dialog[open]")) return;
    const typing = t && (t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable || (t.tagName === "INPUT" && t.id !== "tl-range"));
    if (typing || (t && t.closest && t.closest("#tabs, .popover"))) return;
    if (e.key === "ArrowLeft" || e.key === "ArrowRight") step(e.key === "ArrowRight" ? 1 : -1);
    else if (e.key === " " || e.key === "Spacebar") { if (t && (t.tagName === "BUTTON" || (t.getAttribute && t.getAttribute("role") === "button"))) return; play(); }
    else if (e.key === "Home") goLive();
    else return;
    e.preventDefault();
    e.stopPropagation();
  }, true);
  A.timelineEsc = () => { if (!state.tlDate) return false; goLive(); return true; };
  // The exports stop the playback before they draw the days.
  A.tlStop = stop;
  A.tlSelectable = selectable;

  // ---- the Timeline tab ---------------------------------------------------------
  let tabBody = null;
  function tabOn() { return tabBody && tabBody.dataset.view === "timeline" && state.tab === "timeline" && !state.detail; }
  A.renderTimeline = (body, live) => {
    tabBody = body;
    T.statsWanted = true;
    if (available() && !T.barOpen) { T.barOpen = true; layout(); drawBar(); }
    if (body.dataset.view !== "timeline") {
      body.dataset.view = "timeline";
      const speeds = SPEEDS.map((v) => "<option value='" + v + "'>" + (v / 1000) + " s a day</option>").join("");
      body.innerHTML = A.head("Timeline", "<span class='badge' id='tl-badge'></span>") + "<div id='tl-msg'></div>" +
        "<div id='tl-main'><div class='tl-ctl'>" +
        "<button class='icon-btn' id='tl-t-prev' aria-label='Previous day' title='Previous day (left arrow)'>" + A.icon("back") + "</button>" +
        "<button class='btn small primary' id='tl-t-play' aria-pressed='false'><svg class='ic sm' aria-hidden='true'><use href='#i-play'/></svg><span class='lbl'>Play</span></button>" +
        "<button class='icon-btn' id='tl-t-next' aria-label='Next day' title='Next day (right arrow)'>" + A.icon("next") + "</button>" +
        "<select id='tl-t-speed' class='tl-select' aria-label='Playback speed'>" + speeds + "</select>" +
        "<button class='fchip' id='tl-t-changed'><span class='sw tl-sw'></span>Changed tiles</button>" +
        "<button class='btn small' id='tl-t-live'>" + A.icon("history", "sm") + "Live</button></div>" +
        "<div id='tl-sum'></div>" +
        "<h3 class='sub'>Over the days held <span class='muted' id='tl-spark-note' hidden>loading</span></h3><div id='tl-spark' class='tl-spark'>" +
        METRICS.map((m) => "<div class='spark' data-m='" + m[0] + "'><div class='spark-head'><span>" + m[1] + "</span><b></b></div><canvas aria-hidden='true'></canvas></div>").join("") + "</div>" +
        "<div id='tl-export'></div></div>" +
        "<h3 class='sub' id='tl-feed-h'></h3><div class='chips' id='tl-chips' style='padding:0 0 10px'></div><div id='tl-feed-msg'></div><ul class='feed' id='tl-feed'></ul>";
      $("tl-t-prev").addEventListener("click", () => step(-1));
      $("tl-t-next").addEventListener("click", () => step(1));
      $("tl-t-play").addEventListener("click", play);
      $("tl-t-speed").addEventListener("change", (e) => setSpeed(e.target.value));
      $("tl-t-changed").addEventListener("click", () => setChanged(!T.changedOn));
      $("tl-t-live").addEventListener("click", () => goLive());
      $("tl-sum").addEventListener("click", (e) => {
        const b = e.target.closest("[data-isod]");
        if (b) openIsoDay.apply(null, JSON.parse(b.dataset.isod));
      });
      $("tl-spark").addEventListener("click", (e) => {
        const c = e.target.closest("canvas");
        if (!c || !T.slots.length) return;
        const r = c.getBoundingClientRect();
        const n = T.slots.length;
        const i = n === 1 ? 0 : Math.round((e.clientX - r.left - 4) / (r.width - 8) * (n - 1));
        let best = null;
        for (let d = 0; d < n && best == null; d++) for (const j of [i - d, i + d]) if (best == null && selectable(T.slots[j])) best = j;
        if (best != null) { stop(); select(best); }
      });
      $("tl-chips").addEventListener("click", (e) => {
        const b = e.target.closest("[data-g]");
        if (!b) return;
        const g = b.dataset.g;
        if (T.off.has(g)) T.off.delete(g); else T.off.add(g);
        A.store.set("tlOff", Array.from(T.off));
        drawMarks();
        if (!state.tlDate) loadLive(true);
        drawTab();
      });
      $("tl-feed").addEventListener("click", (e) => {
        const b = e.target.closest("[data-ev]");
        if (!b) return;
        const [x, z, at] = b.dataset.ev.split("|").map(Number);
        map.setView(A.toLatLng(x, z), Math.max(map.getZoom(), A.geo.maxNative - 1));
        setTimeout(() => { for (const m of marks.getLayers()) if (m._ev && m._ev.at === at && Number(m._ev.x) === x && Number(m._ev.z) === z) { m.openTooltip(); break; } }, 380);
        if (window.matchMedia("(max-width: 720px)").matches) A.setPanel(false);
      });
    }
    if (T.statsWanted && T.info) queueStats();
    if (!state.tlDate) loadLive();
    drawTab();
    return live;
  };

  function firstRunText(info) {
    const hh = String(info && info.hour != null ? info.hour : 4).padStart(2, "0") + ":00";
    let when = "tonight";
    const at = info && info.nextRunAt;
    if (at) {
      const d = new Date(at), tmr = new Date(Date.now() + DAY);
      const isTomorrow = d.getFullYear() === tmr.getFullYear() && d.getMonth() === tmr.getMonth() && d.getDate() === tmr.getDate();
      if (A.isToday(at)) when = d.getHours() >= 18 ? "tonight" : "today";
      else if (isTomorrow) when = d.getHours() < 6 ? "tonight" : "tomorrow";
      else when = "on " + A.date(at);
    }
    return "First snapshot " + when + " at " + hh;
  }
  function adminSnapHtml() {
    if (!A.isAdmin()) return "";
    const cmd = "/atlas snapshot " + (state.world || "");
    return "<div class='tl-admin'><button class='btn small' id='tl-snap' aria-expanded='false' aria-controls='tl-snap-note'>" + A.icon("history", "sm") + "Take a snapshot now</button>" +
      "<div class='note' id='tl-snap-note' hidden>Snapshots are taken by the server, never from this page. Run this in game or in the server console." +
      "<div class='tl-cmd'><code>" + esc(cmd) + "</code><button class='btn small' id='tl-snap-copy' data-cmd='" + esc(cmd) + "'>" + A.icon("copy", "sm") + "Copy</button></div></div></div>";
  }
  function wireAdmin(root) {
    const b = root.querySelector("#tl-snap");
    if (!b || b._wired) return;
    b._wired = true;
    b.addEventListener("click", () => { const n = $("tl-snap-note"); n.hidden = !n.hidden; b.setAttribute("aria-expanded", String(!n.hidden)); });
    $("tl-snap-copy").addEventListener("click", (e) => A.copy(e.currentTarget.dataset.cmd, "The snapshot command"));
  }

  function summaryHtml() {
    const s = cur();
    const info = T.info || {};
    if (!s) {
      const last = T.slots.slice().reverse().find((x) => x.kind === "snap");
      let h = "<div class='note'><span class='badge good'>live</span> The map as it is now.";
      if (last) h += " The newest snapshot is of " + esc(dayName(last.date)) + (last.day.takenAt ? ", taken " + esc(A.stamp(last.day.takenAt)) : "") + ".";
      if (info.nextRunAt) h += " The next one is due " + esc(A.stamp(info.nextRunAt)) + ".";
      h += " Pick a day on the slider or press play.</div>";
      const withViews = T.slots.slice().reverse().find((x) => x.kind === "snap" && isoViewsOf(x).length);
      return h + (withViews ? isoHtml(withViews, true) : "");
    }
    const d = s.day || {};
    const st = T.stats.get(key(s.date));
    const ch = T.changed.get(key(s.date));
    let h = "<div class='tiles'><div class='tile'><b>" + (s.kind === "snap" ? num(d.tiles, 0) : "none") + "</b><span>Tiles</span></div>" +
      "<div class='tile'><b>" + (s.kind === "snap" ? (ch && ch.first ? "first" : num(d.changed, 0)) : "none") + "</b><span>Changed</span></div>" +
      "<div class='tile'><b>" + num(d.events || 0, 0) + "</b><span>Events</span></div></div>";
    if (s.kind === "events") h += "<div class='note'>No snapshot was taken that day. " + (s.tiles ? "The map shows the snapshot of " + esc(dayName(s.tiles)) + "." : "There is no earlier map to show.") + "</div>";
    if (d.partial) h += "<div class='note'><span class='badge warn'>partial</span> Some tiles were being redrawn during the snapshot and show the day before.</div>";
    if (st) {
      const kv = [["Taken", st.takenAt ? A.stamp(st.takenAt) : null], ["Explored chunks", num(st.exploredChunks, 0)], ["Players seen", num(st.playersSeen, 0)], ["Peak online", num(st.peakOnline, 0)], ["Online at the snapshot", num(st.onlineNow, 0)],
        ["Kills", num(st.kills, 0)], ["Deaths", num(st.deaths, 0)], ["Blocks broken", num(st.blocksBroken, 0)], ["Blocks placed", num(st.blocksPlaced, 0)], ["World events", num(st.worldEvents, 0)]];
      if (st.feats) kv.push(["Feats points", num(st.feats.totalPoints, 0)], ["Feats unlocked", num(st.feats.totalFeats, 0)]);
      h += "<dl class='kv'>" + kv.filter((x) => x[1] != null && x[1] !== "none").map((x) => "<dt>" + esc(x[0]) + "</dt><dd>" + esc(x[1]) + "</dd>").join("") + "</dl>";
      if (st.feats && Array.isArray(st.feats.top) && st.feats.top.length) {
        h += "<details class='fold'><summary>" + A.icon("chevron", "sm") + "Feats top players that day</summary>" + st.feats.top.slice(0, 10).map((r, i) =>
          "<div class='row'><span class='rank'>" + (Number(r.rank) || i + 1) + "</span><div class='grow'><div class='title'><b>" + esc(r.name) + "</b></div><div class='sub'>" + esc(A.plural(r.unlocked, "feat")) + "</div></div><span class='muted num'>" + num(r.points, 0) + "</span></div>").join("") + "</details>";
      }
    } else if (d.stats) h += "<div class='skeleton' style='width:70%'></div>";
    else if (s.kind === "snap") h += "<p class='muted' style='font-size:13px'>No stats were kept for this day.</p>";
    return h + isoHtml(s, false);
  }

  // ---- daily 3D views ------------------------------------------------------------
  // Views drawn with a day's snapshot, as thumbnails that open in the 3D lightbox. The images answer 403 unless
  // the viewer may see terrain and iso, so they are only shown then. A past day's image never changes.
  const isoShown = () => A.layerOn("terrain") && state.layers.iso !== false;
  const isoViewsOf = (s) => isoShown() && s && s.day && !s.day.pruned && Array.isArray(s.day.isoViews) ? s.day.isoViews.filter((v) => v && v.label && v.file) : [];
  // Admin markers are drawn as marker-<id>, named after the marker when the admin layer is on this page.
  function isoLabel(label) {
    const m = /^marker-(.+)$/.exec(label);
    const rec = m && A.mlayers && A.mlayers.get("atlas:admin");
    const mk = rec && (rec.def.markers || []).find((x) => String(x.id) === m[1]);
    const t = mk && mk.label ? String(mk.label) : A.pretty(m ? "marker " + m[1] : label).replace(/-/g, " ");
    return t.charAt(0).toUpperCase() + t.slice(1);
  }
  function isoHtml(s, latest) {
    const views = isoViewsOf(s);
    if (!views.length) return "";
    return "<h3 class='sub'>" + esc(latest ? "3D views of " + dayName(s.date) : "3D views of the day") + "</h3><div class='iso-thumbs'>" + views.map((v) =>
      "<button class='iso-thumb' data-isod='" + esc(JSON.stringify([s.date, v.label])) + "' aria-label='" + esc("Open the 3D view " + isoLabel(v.label) + " of " + dayName(s.date)) + "'>" +
      "<img src='" + esc(v.file) + "' alt='' loading='lazy' decoding='async'><span>" + esc(isoLabel(v.label)) + "</span></button>").join("") + "</div>";
  }
  function openIsoDay(date, label) {
    const s = T.slots.find((x) => x.date === date);
    const v = isoViewsOf(s).find((x) => x.label === label);
    if (!v || !A.openIsoDay) return;
    const days = T.slots.filter((x) => x.date !== date).map((x) => { const o = isoViewsOf(x).find((y) => y.label === label); return o ? { date: x.date, file: o.file } : null; }).filter(Boolean);
    A.openIsoDay({ world: state.world, label, date, file: v.file, x: v.x, z: v.z, radius: v.radius, title: isoLabel(label), days });
  }

  function feedRow(e, past) {
    const k = kindLook(e.kind);
    const show = placedEv(e) ? " <button class='btn small' data-ev='" + Number(e.x) + "|" + Number(e.z) + "|" + Number(e.at) + "' aria-label='" + esc("Show " + (e.text || k[2]) + " on the map") + "'>Show</button>" : "";
    const when = past ? "<time>" + esc(A.clock(e.at)) + "</time>" : A.when(e.at);
    return "<li class='tl-ev'><span class='ico' style='background:" + k[1] + ";color:" + A.inkOn(k[1]) + "'>" + A.icon(k[0]) + "</span><div class='body'><span class='tl-kind'>" + esc(k[2]) + "</span> " +
      esc(e.text || "") + (e.world && e.world !== state.world ? " <span class='badge'>" + esc(A.worldLabel(e.world)) + "</span>" : "") + show + when + "</div></li>";
  }
  function drawFeed() {
    const s = cur();
    const past = !!s;
    const d = s ? T.events.get(key(s.date)) : T.live;
    const hEl = $("tl-feed-h");
    const ht = s ? "Events of " + dayName(s.date, true) : "Events in the last 24 hours";
    if (hEl.textContent !== ht) hEl.textContent = ht;
    const all = d && Array.isArray(d.items) ? d.items : [];
    // Chips for the groups this list holds, plus any that are switched off, so a group can always be switched back.
    const present = GROUPS.filter((g) => T.off.has(g[0]) || all.some((e) => groupOf(e.kind) === g[0]));
    const chips = $("tl-chips");
    const ck = present.map((g) => g[0] + (T.off.has(g[0]) ? "0" : "1")).join();
    if (chips._k !== ck) {
      chips._k = ck;
      chips.innerHTML = present.length > 1 || T.off.size ? present.map((g) => "<button class='fchip' data-g='" + g[0] + "' aria-pressed='" + !T.off.has(g[0]) + "'>" + esc(g[1]) + "</button>").join("") : "";
    }
    chips.hidden = !chips.innerHTML;
    const list = all.filter(shownEvent).slice(-300).reverse();
    const err = s ? (!d && T.evError) : d && d.error;
    let m = "";
    if (err) m = A.failState(err, "history", "The events", "are", T.liveFn);
    else if (!d) m = "<div class='skeleton' style='width:60%'></div><div class='skeleton'></div>";
    else if (!all.length) m = A.emptyState("history", s ? "Nothing happened that day" : "Quiet so far", s ? "No events were kept for this day." : "Joins, deaths, world events and feats of this world show up here.");
    else if (!list.length) m = "<p class='muted'>Every kind is switched off above.</p>";
    if (d && d.dropped) m += "<div class='note'><span class='badge warn'>limit reached</span> This day hit its event limit, so later events were not kept.</div>";
    if (d && d.truncated) m += "<p class='muted' style='font-size:13px'>Showing the newest events only.</p>";
    const msg = $("tl-feed-msg");
    if (msg._h !== m) { msg.innerHTML = m; msg._h = m; }
    const rows = list.map((e) => feedRow(e, past)).join("");
    const feed = $("tl-feed");
    if (feed._h !== rows) { feed.innerHTML = rows; feed._h = rows; }
  }

  function drawTab() {
    if (!tabOn()) return;
    const msg = $("tl-msg"), main = $("tl-main");
    const badge = $("tl-badge");
    const s = cur();
    const bt = s ? dayName(s.date) : available() ? "Live" : "";
    if (badge.textContent !== bt) badge.textContent = bt;
    badge.hidden = !bt;
    badge.classList.toggle("accent", !!s);
    let m = "";
    if (T.error) {
      m = T.error.status === 404 ? A.emptyState("history", "There is no timeline for this world", T.error.serverText || "Daily snapshots are not kept for it.")
        : A.failState(T.error, "history", "The timeline", "is", T.liveFn);
    } else if (!T.info) {
      m = A.layerOn("timeline") ? A.loading() : "";
    } else if (T.info.enabled === false) {
      m = A.emptyState("history", "The timeline is switched off", "This server does not keep daily snapshots of the map.");
    } else if (!hasSnaps()) {
      const at = T.info.nextRunAt;
      m = "<div class='empty'>" + A.icon("history") + "<p class='lead'>" + esc(firstRunText(T.info)) + "</p><p>" +
        esc("Atlas keeps a picture of the map, its stats and its events once a day, so you can scrub back through time and watch builds appear.") + "</p>" +
        (at ? "<p class='faint'>" + esc("That is " + A.stamp(at) + " on your clock.") + "</p>" : "") + "</div>" + adminSnapHtml();
    }
    if (msg._h !== m) { msg.innerHTML = m; msg._h = m; wireAdmin(msg); }
    const ready = available();
    main.hidden = !ready;
    const feedOk = !T.error && !!T.info && T.info.enabled !== false;
    for (const id of ["tl-feed-h", "tl-feed-msg", "tl-feed"]) $(id).hidden = !feedOk;
    if (!feedOk) $("tl-chips").hidden = true;
    if (ready) {
      $("tl-t-prev").disabled = nextIndex(T.index, -1) == null;
      $("tl-t-next").disabled = nextIndex(T.index, 1) == null;
      $("tl-t-live").hidden = !s;
      $("tl-t-speed").value = String(T.speed);
      $("tl-t-changed").setAttribute("aria-pressed", String(T.changedOn));
      syncPlay();
      const sum = summaryHtml();
      const se = $("tl-sum");
      if (se._h !== sum) { se.innerHTML = sum; se._h = sum; }
      drawSparks();
      if (A.drawTlExport) A.drawTlExport($("tl-export"));
    }
    if (feedOk) drawFeed();
  }
  const drawTabSoon = A.throttle(() => drawTab(), 150);

  // ---- redraws ----------------------------------------------------------------
  const redrawCanvas = A.throttle(() => { drawStrip(); drawSparks(); }, 200);
  window.addEventListener("resize", redrawCanvas);
  // The charts read their colours from the theme, so a theme switch draws them again.
  new MutationObserver(redrawCanvas).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  // The panel opening or closing moves the bar on desktop.
  new MutationObserver(() => { if (barShown()) setTimeout(() => { map.invalidateSize(); drawStrip(); }, 260); }).observe(document.body, { attributes: true, attributeFilter: ["class"] });
})();
