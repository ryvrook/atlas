/* Atlas timeline. Daily snapshots of each world replayed with a date slider, the events of each day on the map
   and in a feed, and small charts over the days the server holds. Loaded after panel.js. */
(function () {
  "use strict";

  const A = window.Atlas;
  if (!A || A.noLeaflet || !A.map) return;
  const { $, esc, num, state } = A;
  const map = A.map;
  const DAY = 86400000;

  // ---- dates and slots ------------------------------------------------------
  // Timeline dates are server local ISO dates. They are handled as UTC midnights so no time zone moves a day.
  // A slot is a date and an hour such as 2026-10-13T16, one snapshot of the day. A plain date is a slot too, the
  // only one of a day taken by Atlas 1.1 or older. parseIso reads the date of either.
  const parseIso = (s) => { const m = /^(\d{4})-(\d{2})-(\d{2})(?:T\d{2})?$/.exec(String(s || "")); return m ? Date.UTC(+m[1], +m[2] - 1, +m[3]) : NaN; };
  const hourOf = (s) => { const m = /T(\d{2})$/.exec(String(s || "")); return m ? Number(m[1]) : null; };
  const hh = (h) => String(h).padStart(2, "0") + ":00";
  const isoOf = (t) => new Date(t).toISOString().slice(0, 10);
  const dayName = (s, weekday) => {
    const t = parseIso(s);
    if (isNaN(t)) return String(s || "");
    const d = new Date(t);
    return d.toLocaleDateString([], { timeZone: "UTC", weekday: weekday ? "short" : undefined, month: "short", day: "numeric", year: d.getUTCFullYear() !== new Date().getFullYear() ? "numeric" : undefined });
  };

  A.dayName = dayName;
  // The hour of a slot as 16:00, empty for a plain date.
  const hourName = A.slotHour = (s) => { const h = hourOf(s); return h == null ? "" : hh(h); };
  // A slot for people, the day and, when the day holds more than one slot, its hour.
  const slotName = A.slotName = (s, weekday) => dayName(s, weekday) + (hourOf(s) != null && perDayShown() ? ", " + hourName(s) : "");
  const perDayShown = () => !!(T.info && (Number(T.info.perDay) > 1 || T.slots.some((x, i) => i > 0 && x.cal === T.slots[i - 1].cal && x.kind !== "missing")));

  // ---- state --------------------------------------------------------------
  // index is the step on the slider, -1 while the live map is shown. A step is one slot of a day from the first
  // day held to today, date naming the slot and cal its calendar day. A day with no slot at all is one step. Its kind
  // is snap (a snapshot), events (events only, the map of the snapshot before it), pruned (removed by retention, a
  // gap) or missing (nothing was kept that day, also a gap).
  // Playback speeds in milliseconds a step. A stored speed that is not one of them falls back to one second.
  const SPEEDS = [500, 1000, 2000, 3000, 5000, 10000];
  const speedName = (v) => (v / 1000) + " sec/snapshot";
  const SPEED_TIP = "How long each snapshot stays on screen";
  const storedSpeed = Number(A.store.get("tlSpeed", 1000));
  // shownSeq counts the pictures asked for, so a late answer for a slot no longer wanted is ignored. shown is the
  // slot whose picture is on screen and waiting is true while the next one is still loading past its due time.
  const T = A.tl = {
    world: null, info: null, error: null, slots: [], index: -1, playing: false, timer: null, run: null, lastRun: "",
    shownSeq: 0, shown: null, waiting: false, waitTimer: 0, dwellMs: 0, dwellAt: 0,
    speed: SPEEDS.includes(storedSpeed) ? storedSpeed : 1000,
    changedOn: A.store.get("tlChanged", true), barOpen: A.store.get("tlBar", false),
    stats: new Map(), events: new Map(), changed: new Map(), live: null, liveAt: 0, liveKey: "",
    want: null, off: new Set(A.store.get("tlOff", [])), seq: 0, statsWanted: false
  };
  // Key places, see the Timeline tab below. az is the direction picked per place, shown the day a playing place is on.
  const KAZ = [[0, "SE", "south east"], [90, "SW", "south west"], [180, "NW", "north west"], [270, "NE", "north east"]];
  const K = { az: A.store.get("kpAz", {}), shown: {}, play: null, pick: A.store.get("kpPick", null) };
  if (!K.az || typeof K.az !== "object") K.az = {};
  const selectable = (s) => !!s && (s.kind === "snap" || s.kind === "events");
  const cur = () => (T.index >= 0 && T.index < T.slots.length ? T.slots[T.index] : null);
  const hasSnaps = () => T.slots.some((s) => s.kind === "snap");
  const available = () => A.layerOn("timeline") && !!T.info && T.info.enabled !== false && hasSnaps();
  const barShown = () => available() && (T.barOpen || !!state.tlDate);
  const key = (date) => (state.world || "") + "|" + date;
  const base = () => "api/timeline/" + A.W();

  function buildSlots(info) {
    const days = (Array.isArray(info.days) ? info.days : []).filter((d) => d && !isNaN(parseIso(d.date)));
    const by = new Map();
    for (const d of days) { if (!by.has(d.date)) by.set(d.date, []); by.get(d.date).push(d); }
    for (const l of by.values()) l.sort((a, b) => String(a.slot || a.date) < String(b.slot || b.date) ? -1 : 1);
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
      const list = by.get(date);
      if (!list) { slots.push({ date, cal: date, hour: null, day: null, kind: "missing", tiles: null }); continue; }
      for (const day of list) {
        const id = Slots.valid(day.slot) ? day.slot : date;
        const kind = day.pruned ? "pruned" : day.snapshot ? "snap" : "events";
        if (kind === "snap") lastSnap = id;
        slots.push({ date: id, cal: date, hour: day.hour != null ? Number(day.hour) : hourOf(id), day, kind, tiles: kind === "snap" ? id : kind === "events" ? lastSnap : null });
      }
    }
    return slots;
  }

  const Slots = { valid: (s) => !isNaN(parseIso(s)) };
  // The cache key of the day tiles of a snapshot, from its count of tiles and of tiles carried over from an earlier
  // snapshot. Both change when the server redraws the slot, so the browser asks again instead of keeping old answers.
  A.dayTileKey = (date) => {
    const s = T.slots.find((x) => x.date === date && x.kind === "snap");
    const d = s && s.day;
    return d ? "?v=" + (Number(d.tiles) || 0) + "." + (Number(d.carried) || 0) : "";
  };

  // ---- map layers ---------------------------------------------------------
  const changedGroup = L.layerGroup().addTo(map);
  const marks = L.layerGroup().addTo(map);

  // ---- loading ------------------------------------------------------------
  function reset(world) {
    stop();
    T.world = world;
    T.info = null; T.error = null; T.slots = []; T.live = null; T.liveAt = 0; T.liveKey = "";
    T.stats.clear(); T.events.clear(); T.changed.clear();
    T.hi = null;
    statsQueue = [];
    if (state.tlDate) goLive(true);
    T.index = -1;
    T.lastRun = "";
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
      else if (keep && T.run) finish();
      else if (keep) goLive(true);
      // A snapshot that lands during a run joins it, so the count in the chip stays right.
      if (T.run) {
        let total = T.run.step;
        for (let k = T.index + 1; k < T.slots.length; k++) if (selectable(T.slots[k])) total++;
        T.run.total = total;
      }
      if (T.want) {
        const w = T.want;
        T.want = null;
        const j = findSlot(w);
        if (selectable(T.slots[j])) { T.barOpen = true; select(j); }
        else A.toast("The timeline of " + A.worldLabel(world) + " has nothing for " + slotName(w));
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
  // The step of a slot, or for a plain date the last slot held that day, as links from Atlas 1.1 name a day.
  function findSlot(w) {
    let j = T.slots.findIndex((s) => s.date === w);
    if (j < 0 && hourOf(w) == null) for (let i = 0; i < T.slots.length; i++) if (T.slots[i].cal === w && selectable(T.slots[i])) j = i;
    return j;
  }
  // A shared link names a slot before the slots of its world are known, so it waits for them.
  A.timelineWant = (date) => {
    if (isNaN(parseIso(date))) return;
    const j = T.info && state.world === T.world ? findSlot(date) : -1;
    if (selectable(T.slots[j])) { T.barOpen = true; select(j); } else T.want = date;
  };
  A.on("timelineDay", (d) => {
    if (!d || d.world !== state.world || !A.layerOn("timeline")) return;
    const shown = barShown();
    A.loadTimeline().then(() => { if (shown && d.date) A.toast("A snapshot of " + slotName(d.slot || d.date) + " was added to the timeline", "good"); });
  });

  // ---- selecting a day ------------------------------------------------------
  // A slot is picked at once, so the label, the chip and the tab follow the slider. Its picture loads under the
  // one on screen and fades in when ready, and only then do its markers land and its dwell start.
  function select(i) {
    const s = T.slots[i];
    if (!selectable(s)) return;
    // The key places follow the slider again once a day is picked.
    kpStop();
    K.shown = {};
    const was = state.tlDate;
    T.index = i;
    state.tlDate = s.date;
    state.tlTiles = s.tiles;
    document.body.classList.add("tl-past");
    const seq = ++T.shownSeq;
    armWait(seq);
    A.swapDayTiles(() => shown(seq));
    loadChanged(s);
    loadDay(s);
    wantStats(s);
    if (!T.playing) {
      const nx = nextIndex(i, 1);
      if (nx != null && nx >= 0 && T.slots[nx].tiles) A.preloadDay(T.slots[nx].tiles);
    }
    if (!was) A.closeCtx();
    T.lastRun = "";
    saveUrlSoon();
    sync();
    if (T.playing) flash();
  }
  // A picture still loading past a short grace shows as loading while playing.
  function armWait(seq) {
    clearTimeout(T.waitTimer);
    T.waitTimer = setTimeout(() => { if (seq === T.shownSeq && T.playing) { T.waiting = true; syncMotion(); drawChip(); } }, 180);
  }
  // The picture of the slot is on screen. Its markers pop in and, while playing, its dwell starts now.
  function shown(seq) {
    if (seq !== T.shownSeq) return;
    clearTimeout(T.waitTimer);
    T.waiting = false;
    const s = cur();
    T.shown = s ? s.date : null;
    if (s) { drawMarks(true); drawChanged(); }
    if (T.playing && s) dwell(); else syncMotion();
    drawChip();
  }
  function goLive(quiet) {
    stop();
    kpStop();
    K.shown = {};
    const was = !!state.tlDate;
    T.index = -1;
    T.shown = null;
    T.shownSeq++;
    clearTimeout(T.waitTimer);
    T.waiting = false;
    state.tlDate = null;
    state.tlTiles = null;
    document.body.classList.remove("tl-past");
    changedGroup.clearLayers();
    marks.clearLayers();
    T.markOf = new Map();
    T.hi = null;
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
  // Play runs from the selected slot to the newest one, one slot per step. The newest slot stays up for one
  // step, then the live map returns and a toast says what was played. From the live map or the newest slot it
  // starts again at the first slot held. A slot with events only that shows the same map as the step before
  // passes in half the time and says so, so playback never looks stuck on it.
  // Each step stays on screen for the chosen time counted from the moment its picture is fully shown, and the
  // slot after it loads out of sight meanwhile, so the map never changes tile by tile.
  // Play needs two snapshots. A day with events only shows the map of the snapshot before it, so it adds no step of its own.
  const playable = () => T.slots.filter((s) => s.kind === "snap").length;
  // Why Play cannot run, or an empty string when it can.
  function playBlock() {
    if (!available()) return "The timeline has no snapshot yet";
    if (playable() >= 2) return "";
    const at = T.info && T.info.nextRunAt;
    return "Play needs at least two snapshots" + (at ? ", the next is taken " + (A.isToday(at) ? "at " + A.clock(at) : "on " + A.date(at) + " at " + A.clock(at)) : "");
  }
  // True for a slot with events only whose map is the one of the slot before it.
  function sameMap(i) {
    const s = T.slots[i];
    if (!s || s.kind !== "events") return false;
    const j = nextIndex(i, -1);
    return j != null && j >= 0 && T.slots[j].tiles === s.tiles;
  }
  const dwellOf = (i) => sameMap(i) ? Math.max(250, T.speed / 2) : T.speed;
  function tally(s) {
    const r = T.run;
    if (!r || !s) return;
    r.step++;
    r.to = s.date;
    if (s.kind === "snap") r.snaps++; else r.events++;
  }
  function play() {
    if (!available()) return;
    if (T.playing) { stop(); return; }
    const why = playBlock();
    if (why) { A.toast(why); syncPlay(); return; }
    let start = T.index;
    if (start < 0 || nextIndex(start, 1) === -1) start = T.slots.findIndex(selectable);
    let total = 0;
    for (let i = start; i >= 0 && i < T.slots.length; i++) if (selectable(T.slots[i])) total++;
    T.playing = true;
    T.run = { from: T.slots[start].date, to: null, step: 0, total, snaps: 0, events: 0 };
    tally(T.slots[start]);
    if (start !== T.index) select(start);
    else {
      // The slot on screen starts its dwell once its picture is in, which it usually already is.
      const seq = ++T.shownSeq;
      armWait(seq);
      A.swapDayTiles(() => shown(seq));
      sync();
      flash();
    }
  }
  // The time the shown slot stays up. The slot after it loads in the layer out of sight meanwhile.
  function dwell() {
    clearTimeout(T.timer);
    const ms = dwellOf(T.index);
    T.dwellMs = ms;
    T.dwellAt = Date.now();
    const nx = nextIndex(T.index, 1);
    if (nx != null && nx >= 0 && T.slots[nx].tiles) A.preloadDay(T.slots[nx].tiles, true);
    T.timer = setTimeout(advance, ms);
    syncMotion();
  }
  function advance() {
    T.timer = null;
    if (!T.playing) return;
    const i = nextIndex(T.index, 1);
    if (i == null || i < 0) { finish(); return; }
    tally(T.slots[i]);
    select(i);
  }
  // The end of a run. The live map comes back and the toast and the Timeline tab say what was played.
  function finish() {
    const r = T.run;
    goLive();
    if (!r) return;
    const parts = [];
    if (r.snaps) parts.push(A.plural(r.snaps, "snapshot"));
    if (r.events) parts.push(r.events === 1 ? "1 day of events only" : r.events + " days of events only");
    const to = r.to || r.from;
    const span = parseIso(to) === parseIso(r.from) ? " of " + dayName(r.from) + (to !== r.from && hourName(to) ? " up to " + hourName(to) : "")
      : " from " + slotName(r.from) + " to " + slotName(to);
    T.lastRun = "Played " + parts.join(" and ") + span + ".";
    A.toast(T.lastRun + " Back on the live map.");
    drawTabSoon();
  }
  function stop() {
    T.run = null;
    if (!T.playing && !T.timer) return;
    T.playing = false;
    clearTimeout(T.timer);
    T.timer = null;
    T.waiting = false;
    T.dwellMs = 0;
    if (A.releasePrimed) A.releasePrimed();
    syncPlay();
    syncMotion();
    drawChip();
  }
  // A short pulse on the label and the chip at each step, so a step shows even when the map barely changes.
  function flash() {
    for (const id of ["tl-label", "tl-chip", "tl-badge"]) {
      const el = $(id);
      if (!el) continue;
      el.classList.remove("tl-flash");
      void el.offsetWidth;
      el.classList.add("tl-flash");
    }
  }
  function setSpeed(v) {
    v = Number(v);
    if (!SPEEDS.includes(v)) return;
    T.speed = v;
    A.store.set("tlSpeed", v);
    $("tl-speed").value = String(v);
    const ts = $("tl-t-speed");
    if (ts) ts.value = String(v);
    // A step on screen starts its dwell again at the new speed.
    if (T.playing && T.timer) dwell();
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
  // The tint loads with the slot but is drawn only once the picture of the slot is on screen, like its markers.
  function loadChanged(s) {
    if (!s || !T.changedOn || !A.layerOn("terrain")) { changedGroup.clearLayers(); return; }
    if (s.kind !== "snap") return;
    const k = key(s.date);
    if (T.changed.has(k)) { drawChanged(); return; }
    A.api(base() + "/" + encodeURIComponent(s.date) + "/changed").then((d) => {
      T.changed.set(k, d && Array.isArray(d.tiles) ? d : { first: true, tiles: [] });
      if (cur() === s) { drawChanged(); drawTabSoon(); }
    }).catch(() => { /* the day shows without the tint */ });
  }
  function drawChanged() {
    const s = cur();
    if (s && T.shown !== s.date) return;
    changedGroup.clearLayers();
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

  // The events of a slot load when it is picked. Its markers wait until its picture is on screen, so they land
  // together with the map they belong to.
  function loadDay(s) {
    const k = key(s.date);
    const ready = () => { if (cur() === s && T.shown === s.date) drawMarks(true); drawTabSoon(); };
    if (T.events.has(k)) { ready(); return; }
    if (!s.day || !s.day.events) { T.events.set(k, { items: [], dropped: false }); ready(); return; }
    T.evError = null;
    A.api(base() + "/" + encodeURIComponent(s.date) + "/events").then((d) => {
      T.events.set(k, { items: d && Array.isArray(d.items) ? d.items.filter((e) => e && typeof e === "object") : [], dropped: !!(d && d.dropped) });
      if (cur() === s) ready();
    }).catch((e) => {
      if (e.status === 404) T.events.set(k, { items: [], dropped: false });
      else T.evError = e;
      if (cur() === s) drawTabSoon();
    });
  }
  const placedEv = (e) => e.x != null && e.z != null && isFinite(Number(e.x)) && isFinite(Number(e.z));
  const evKey = (e) => Number(e.at) + "|" + Number(e.x) + "|" + Number(e.z);
  // The events of the shown slot as markers in a pane of their own, so they stay bright over the dimmed live map.
  // Markers closer than CLUSTER pixels on screen share one badge with their count until the map is zoomed in
  // far enough. pop gives the markers of a step that just landed a short pulse.
  const CLUSTER = 34;
  function drawMarks(pop) {
    marks.clearLayers();
    T.markOf = new Map();
    const s = cur();
    const d = s && T.events.get(key(s.date));
    if (!d) return;
    let list = d.items.filter((e) => shownEvent(e) && placedEv(e) && A.inLimit(e.x, e.z));
    if (list.length > 500) list = list.slice(-500);
    const zoom = map.getZoom();
    const near = zoom > A.geo.maxNative;
    const buckets = new Map();
    for (const e of list) {
      const ll = A.toLatLng(Number(e.x), Number(e.z));
      let k = evKey(e);
      if (!near) { const p = map.project(ll, zoom); k = Math.floor(p.x / CLUSTER) + "," + Math.floor(p.y / CLUSTER); }
      if (!buckets.has(k)) buckets.set(k, []);
      buckets.get(k).push(e);
    }
    let n = 0;
    for (const group of buckets.values()) {
      const m = group.length === 1 ? eventMarker(group[0]) : clusterMarker(group);
      marks.addLayer(m);
      for (const e of group) T.markOf.set(evKey(e), m);
      // The first markers of a step pulse once. A very busy step only pulses its first few.
      const el = m.getElement && m.getElement();
      const inner = el && el.querySelector(".sym, .tl-clu");
      if (pop && inner && n < 60) { inner.style.animationDelay = Math.min(n * 18, 400) + "ms"; inner.classList.add("tl-pop"); }
      n++;
    }
    if (T.hi) hiMark(T.hi, true);
  }
  function eventMarker(e) {
    const k = kindLook(e.kind);
    const m = A.marker(A.toLatLng(Number(e.x), Number(e.z)), {
      pane: "tlMarks", icon: A.symIcon(k[0], k[1], { size: "small" }), title: "", riseOnHover: true,
      label: (e.text || k[2]) + ", " + k[2].toLowerCase() + " at " + A.clock(e.at)
    });
    A.tip(m, A.card(esc(k[2]) + " <span class='tc-thin'>" + esc(A.clock(e.at)) + "</span>", esc(e.text || ""), { x: e.x, z: e.z }), { offset: [0, -12] });
    m._ev = e;
    return m;
  }
  // Several events close together. The badge takes the colour of the kind most of them share, and a click zooms in on them.
  function clusterMarker(group) {
    let x = 0, z = 0;
    const kinds = new Map();
    for (const e of group) { x += Number(e.x); z += Number(e.z); const k = kindLook(e.kind); kinds.set(k, (kinds.get(k) || 0) + 1); }
    x /= group.length; z /= group.length;
    let top = null;
    for (const [k, c] of kinds) if (!top || c > kinds.get(top)) top = k;
    const n = group.length;
    const size = n > 99 ? 34 : 28;
    const box = touch ? 40 : 34;
    const icon = L.divIcon({
      className: "", iconSize: [box, box], iconAnchor: [box / 2, box / 2],
      html: "<div class='symwrap tl-cluwrap'><div class='tl-clu' style='width:" + size + "px;height:" + size + "px;background:" + top[1] + ";color:" + A.inkOn(top[1]) + "'>" + (n > 999 ? "999+" : n) + "</div></div>"
    });
    const m = A.marker(A.toLatLng(x, z), { pane: "tlMarks", icon, title: "", riseOnHover: true, label: A.plural(n, "event") + " close together, zoom in to tell them apart" });
    const lines = group.slice(-4).reverse().map((e) => "<div class='tl-clu-line'><b>" + esc(kindLook(e.kind)[2]) + "</b> " + esc(A.clock(e.at)) + (e.text ? " <span class='muted'>" + esc(e.text) + "</span>" : "") + "</div>").join("");
    A.tip(m, A.card(esc(A.plural(n, "event")), "Click to zoom in", null, lines + (n > 4 ? "<div class='muted tl-clu-line'>and " + (n - 4) + " more</div>" : "")), { offset: [0, -14] });
    m.on("click", () => {
      const b = L.latLngBounds(group.map((e) => A.toLatLng(Number(e.x), Number(e.z))));
      const zoom = Math.min(map.getBoundsZoom(b.pad(0.4)), A.geo.maxNative + 1);
      map.setView(b.getCenter(), Math.max(zoom, map.getZoom() + 1));
    });
    return m;
  }
  const touch = window.matchMedia && matchMedia("(hover: none), (pointer: coarse)").matches;
  // A feed row under the pointer or the keyboard lights up its marker, or the badge it is counted in.
  function hiMark(k, on) {
    const m = T.markOf && T.markOf.get(k);
    if (!on && T.hi !== k) return;
    if (!on) T.hi = null;
    if (!m) return;
    const el = m.getElement && m.getElement();
    if (el) el.classList.toggle("tl-hi", !!on);
    m.setZIndexOffset(on ? 2000 : 0);
    if (on) T.hi = k;
  }
  map.on("zoomend", () => { const s = cur(); if (s && T.shown === s.date) drawMarks(false); });

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
      b.title = at ? slotName(at) : "";
      drawSpark(row.querySelector("canvas"), m);
    });
    const loading = T.slots.some((s) => s.day && s.day.stats && !T.stats.has(key(s.date)));
    const note = $("tl-spark-note");
    if (note) note.hidden = !loading;
  }
  const sparksSoon = A.throttle(drawSparks, 250);

  // ---- the track --------------------------------------------------------------
  // The bar draws the days held as a time axis, the first day at the left and the live map at the right end. Each
  // slot sits at its date and hour. A snapshot is a small picture mark, a snapshot with 3D views a cube mark, a slot
  // with events only a short tick and a day removed by retention a hatched gap. A day with nothing kept stays empty.
  // Under the axis a thin row of bars shows how many events each slot holds, across the hours it covers.
  const HOUR = 3600000;
  const bar = $("tl-bar");
  const trackEl = $("tl-track"), rail = $("tl-rail"), marksBox = $("tl-marks"), head = $("tl-head"), bub = $("tl-bub"), fill = $("tl-fill"), tipEl = $("tl-tip");
  const reducedMotion = () => !!(window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches);
  const winOf = (s) => {
    const w = s.day && Array.isArray(s.day.window) ? s.day.window.map(Number) : null;
    return w && w.length === 2 && isFinite(w[0]) && isFinite(w[1]) && w[1] > w[0] ? [Math.max(0, w[0]), Math.min(24, w[1])] : [0, 24];
  };
  // The hour a slot is drawn at. A plain date from Atlas 1.1 sits at the snapshot hour of the server, a day with
  // events only and no hour in the middle of the hours it covers.
  const slotHourOf = (s) => {
    if (s.hour != null && isFinite(s.hour)) return Number(s.hour);
    if (s.kind === "snap" && T.info && T.info.hour != null && isFinite(Number(T.info.hour))) return Number(T.info.hour);
    const w = winOf(s);
    return (w[0] + w[1]) / 2;
  };
  function axis() {
    const n = T.slots.length;
    const t0 = parseIso(T.slots[0].cal), t1 = parseIso(T.slots[n - 1].cal) + DAY;
    return { t0, span: Math.max(HOUR, t1 - t0) };
  }
  T.ax = null;
  const fracAt = (t) => T.ax ? Math.max(0, Math.min(1, (t - T.ax.t0) / T.ax.span)) : 1;
  // The place of a step on the axis from 0 to 1. The live map is the right end.
  const posOf = (i) => {
    const s = i != null && i >= 0 ? T.slots[i] : null;
    return s ? fracAt(parseIso(s.cal) + slotHourOf(s) * HOUR) : 1;
  };
  const pc = (f) => (f * 100).toFixed(3) + "%";
  const shortDay = (iso, wide) => {
    const t = parseIso(iso);
    return new Date(t).toLocaleDateString([], { timeZone: "UTC", weekday: wide ? "short" : undefined, month: "short", day: "numeric" });
  };
  const viewCount = (s) => allViewsOf(s).length;
  function buildTrack() {
    const n = T.slots.length;
    const W = marksBox.clientWidth;
    if (!n || !W) { if (marksBox._sig) { marksBox.innerHTML = ""; marksBox._sig = ""; } return; }
    const sig = W + "|" + isoShown() + "|" + T.slots.map((s) => s.date + s.kind + (s.day ? (s.day.events || 0) + "." + viewCount(s) : "")).join(",");
    if (marksBox._sig === sig) return;
    marksBox._sig = sig;
    T.ax = axis();
    const days = Math.max(1, Math.round(T.ax.span / DAY));
    const dayW = W / days;
    // Day labels thin out on a long timeline, and the last one keeps clear of Live at the right end.
    const every = [1, 2, 3, 7, 14, 30, 61, 91, 182].find((k) => k * dayW >= 60) || 365;
    let h = "";
    for (let d = 0; d < days; d++) {
      const t = T.ax.t0 + d * DAY;
      const f = fracAt(t);
      h += "<i class='tl-tick' style='left:" + pc(f) + "'></i>";
      if (d % every === 0 && (d === 0 || (1 - f) * W > 70)) h += "<span class='tl-day' style='left:" + pc(f) + "'>" + esc(shortDay(isoOf(t), every === 1 && dayW >= 120)) + "</span>";
    }
    h += "<span class='tl-day tl-day-live'>Live</span>";
    let maxEv = 0;
    for (const s of T.slots) if (s.day && s.kind !== "pruned") maxEv = Math.max(maxEv, Number(s.day.events) || 0);
    // Marks closer than a glyph apart turn into thin bars.
    let minGap = Infinity, lastX = null;
    T.slots.forEach((s, i) => { if (!selectable(s)) return; const x = posOf(i) * W; if (lastX != null) minGap = Math.min(minGap, x - lastX); lastX = x; });
    marksBox.classList.toggle("dense", minGap < 17);
    let prevCal = null, prevEnd = 0;
    T.slots.forEach((s, i) => {
      if (s.kind === "missing") return;
      const d0 = parseIso(s.cal);
      const w = winOf(s);
      if (s.kind === "pruned") {
        h += "<i class='tl-cut' data-i='" + i + "' style='left:" + pc(fracAt(d0)) + ";width:" + pc(fracAt(d0 + DAY) - fracAt(d0)) + "'></i>";
        return;
      }
      const ev = Number(s.day && s.day.events) || 0;
      if (ev > 0) {
        // Slots of one day cover hours one after the other. An older slot that claims the whole day keeps only what is left.
        const a = Math.max(w[0], prevCal === s.cal ? prevEnd : 0);
        const b = Math.max(a, w[1]);
        const hpx = (1.5 + 6 * Math.sqrt(ev / Math.max(1, maxEv))).toFixed(1);
        const x0 = b > a ? fracAt(d0 + a * HOUR) : posOf(i) - 0.002, x1 = b > a ? fracAt(d0 + b * HOUR) : posOf(i) + 0.002;
        h += "<i class='tl-dn' data-i='" + i + "' style='left:" + pc(x0) + ";width:" + pc(Math.max(0, x1 - x0)) + ";height:" + hpx + "px'></i>";
      }
      prevEnd = Math.max(prevCal === s.cal ? prevEnd : 0, w[1]);
      prevCal = s.cal;
      const cls = s.kind === "snap" ? "snap" + (viewCount(s) ? " iso" : "") : "ev";
      h += "<i class='tl-g " + cls + "' data-i='" + i + "' style='left:" + pc(posOf(i)) + "'>" + (s.kind === "snap" ? A.icon(viewCount(s) ? "cube" : "image") : "") + "</i>";
    });
    h += "<i class='tl-g live' data-i='-1' style='left:100%'></i>";
    marksBox.innerHTML = h;
    marksBox._sel = null;
  }
  // The playhead and the fill of a run. While a step is on screen the head slides on to the next slot over the
  // dwell, so time is seen passing. Otherwise it sits on the shown slot.
  function moveHead(f, ms, linear) {
    const tr = ms > 0 ? (linear ? ms + "ms linear" : ms + "ms cubic-bezier(.2,.7,.2,1)") : "none";
    for (const el of [head, fill]) el.style.transition = tr === "none" ? "none" : ["left", "width", "transform"].map((p) => p + " " + tr).join(", ");
    head.style.left = pc(f);
    bub.style.transform = "translateX(" + (-f * 100).toFixed(1) + "%)";
    const r = T.playing && T.run;
    fill.hidden = !r;
    if (r) { const a = posOf(T.slots.findIndex((x) => x.date === r.from)); fill.style.left = pc(a); fill.style.width = pc(Math.max(0, f - a)); }
  }
  function placeHead(ms) {
    moveHead(posOf(T.index), ms == null ? 200 : ms, false);
  }
  // The motion of a run, the head sliding, the ring of the Play buttons filling and the bar in the chip.
  function syncMotion() {
    const r = T.playing && T.run;
    const inDwell = !!(r && T.timer && T.dwellMs);
    const left = inDwell ? Math.max(0, T.dwellMs - (Date.now() - T.dwellAt)) : 0;
    bar.classList.toggle("tl-running", !!r);
    bar.classList.toggle("tl-wait", !!(r && T.waiting));
    if (inDwell && !reducedMotion()) {
      const nx = nextIndex(T.index, 1);
      moveHead(posOf(T.index), 0);
      void head.offsetWidth;
      moveHead(posOf(nx == null ? T.index : nx), left, true);
    } else placeHead();
    for (const id of ["tl-play", "tl-t-play"]) {
      const b = $(id);
      if (!b) continue;
      b.classList.toggle("tl-wait", !!(r && T.waiting));
      b.classList.remove("tl-dwell");
      if (inDwell) { void b.offsetWidth; b.style.setProperty("--tl-dwell", left + "ms"); b.classList.add("tl-dwell"); }
    }
    const run = $("tl-run");
    if (run) {
      run.hidden = !r;
      if (r) {
        const done = Math.max(0, r.step - 1) / r.total;
        run.querySelector(".done").style.width = pc(done);
        const c = run.querySelector(".cur");
        c.style.left = pc(done);
        c.style.width = pc(1 / r.total);
        const b = c.querySelector("b");
        b.classList.remove("on");
        if (inDwell) { void b.offsetWidth; b.style.setProperty("--tl-dwell", left + "ms"); b.classList.add("on"); }
      }
    }
  }
  function drawBar() {
    if (bar.hidden) return;
    buildTrack();
    const s = cur();
    // The shown slot is marked on the track.
    const sel = marksBox.querySelector(".tl-g[data-i='" + T.index + "']");
    if (marksBox._sel !== sel) {
      if (marksBox._sel) marksBox._sel.classList.remove("sel");
      if (sel) sel.classList.add("sel");
      marksBox._sel = sel;
    }
    const steps = T.slots.reduce((a, x) => a + (selectable(x) ? 1 : 0), 0);
    let now = steps;
    if (s) { now = 0; for (let i = 0; i < T.index; i++) if (selectable(T.slots[i])) now++; }
    rail.setAttribute("aria-valuemax", String(steps));
    rail.setAttribute("aria-valuenow", String(now));
    const label = s ? dayName(s.date, true) : "Live";
    // The hour of the slot sits under the date while the server keeps more than one snapshot a day.
    const hour = s && perDayShown() ? hourName(s.date) : "";
    rail.setAttribute("aria-valuetext", s ? label + (hour ? ", " + hour : "") + (s.kind === "events" ? ", events only" : "") : "Live map");
    const sub = [hour, s && s.kind === "events" ? (T.playing && sameMap(T.index) ? "events only, same map" : "events only") : ""].filter(Boolean).join(", ");
    const lh = esc(label) + (sub ? "<small class='tl-hour'>" + esc(sub) + "</small>" : "");
    if ($("tl-label")._h !== lh) { $("tl-label").innerHTML = lh; $("tl-label")._h = lh; }
    $("tl-label").classList.toggle("past", !!s);
    const bt = s ? dayName(s.cal) + (hour ? " " + hour : "") : "Live";
    if (bub.textContent !== bt) bub.textContent = bt;
    bub.classList.toggle("live", !s);
    if (!(T.playing && T.timer)) placeHead();
    $("tl-speed").value = String(T.speed);
    const ch = $("tl-changed");
    ch.setAttribute("aria-pressed", String(T.changedOn));
    syncPlay();
    if (document.activeElement === rail && rail.matches(":focus-visible")) showTip(T.index);
  }

  // ---- the track card -----------------------------------------------------------
  // Hovering a mark, or moving along the track with the keyboard, shows a small card with what the slot holds.
  function tipHtml(i) {
    if (i < 0) return "<b>Live</b><div>The map as it is now</div>";
    const s = T.slots[i];
    const d = s.day || {};
    const hour = hourName(s.date);
    let h = "<b>" + esc(dayName(s.cal, true) + (hour ? ", " + hour : "")) + "</b>";
    if (s.kind === "pruned") return h + "<div class='muted'>Removed by the retention setting</div>";
    const first = T.slots.findIndex((x) => x.kind === "snap") === i;
    const bits = s.kind === "snap" ? [A.plural(d.tiles || 0, "tile"), first ? "first snapshot" : num(d.changed || 0, 0) + " changed"]
      : [s.tiles ? "events only, map of " + slotName(s.tiles) : "events only, no map"];
    h += "<div>" + esc(bits.join(", ")) + "</div><div>" + esc(A.plural(d.events || 0, "event")) + "</div>";
    const views = allViewsOf(s);
    if (views.length) h += "<img src='" + esc(views[0].file) + "' alt='' loading='lazy' decoding='async'><div class='muted'>" + esc(A.plural(views.length, "3D view")) + "</div>";
    return h;
  }
  function showTip(i, x) {
    clearTimeout(tipTimer);
    if (i == null || (i >= 0 && !T.slots[i])) { tipEl.hidden = true; return; }
    const h = tipHtml(i);
    if (tipEl._h !== h) { tipEl.innerHTML = h; tipEl._h = h; }
    tipEl.hidden = false;
    const tr = trackEl.getBoundingClientRect(), rr = rail.getBoundingClientRect();
    const at = x != null ? x : rr.left + posOf(i) * rr.width;
    const w = tipEl.offsetWidth;
    const left = Math.max(-tr.left + 8, Math.min(at - tr.left - w / 2, window.innerWidth - tr.left - w - 8));
    tipEl.style.left = left + "px";
  }
  const hideTip = () => { tipEl.hidden = true; };
  // The step nearest a point on the track, the live end included. any also finds slots that cannot be shown.
  function nearestAt(clientX, any) {
    const r = rail.getBoundingClientRect();
    const f = (clientX - r.left) / Math.max(1, r.width);
    let best = -1, bd = Math.abs(1 - f);
    T.slots.forEach((s, i) => {
      if (!(any ? s.kind !== "missing" : selectable(s))) return;
      const d = Math.abs(posOf(i) - f);
      if (d < bd) { bd = d; best = i; }
    });
    // A pointer inside a removed day names that day.
    if (any) T.slots.forEach((s, i) => { if (s.kind === "pruned") { const a = fracAt(parseIso(s.cal)), b = fracAt(parseIso(s.cal) + DAY); if (f >= a && f <= b) { best = i; bd = 0; } } });
    return { i: best, px: bd * r.width, x: r.left + (best < 0 ? 1 : posOf(best)) * r.width };
  }
  function pickAt(clientX) {
    const n = nearestAt(clientX, false);
    if (n.i < 0) { if (T.index >= 0) goLive(); }
    else if (n.i !== T.index) select(n.i);
  }
  // While dragging, the head, the bubble and the label follow the pointer at once, once a frame at most. The slot
  // itself is picked when the pointer rests for a moment or lets go, so a fast drag does not load every slot it crosses.
  const scrub = { x: 0, frame: 0, rest: 0, i: null };
  function previewAt(i) {
    scrub.i = i;
    const s = i >= 0 ? T.slots[i] : null;
    const hour = s && perDayShown() ? hourName(s.date) : "";
    bub.textContent = s ? dayName(s.cal) + (hour ? " " + hour : "") : "Live";
    bub.classList.toggle("live", !s);
    const label = s ? dayName(s.date, true) : "Live";
    const lh = esc(label) + (hour ? "<small class='tl-hour'>" + esc(hour) + "</small>" : "");
    if ($("tl-label")._h !== lh) { $("tl-label").innerHTML = lh; $("tl-label")._h = lh; }
    moveHead(posOf(i), 0);
    showTip(i);
  }
  function scrubTo(clientX) {
    scrub.x = clientX;
    if (!scrub.frame) scrub.frame = requestAnimationFrame(() => { scrub.frame = 0; const n = nearestAt(scrub.x, false); if (n.i !== scrub.i) previewAt(n.i); });
    clearTimeout(scrub.rest);
    scrub.rest = setTimeout(scrubSettle, 150);
  }
  function scrubSettle() {
    clearTimeout(scrub.rest);
    if (scrub.frame) { cancelAnimationFrame(scrub.frame); scrub.frame = 0; }
    const i = nearestAt(scrub.x, false).i;
    scrub.i = null;
    if (i < 0) { if (T.index >= 0) goLive(); else drawBar(); }
    else if (i !== T.index) select(i);
    else drawBar();
  }
  let dragging = false;
  trackEl.addEventListener("pointerdown", (e) => {
    if (e.button !== 0 || !T.slots.length) return;
    dragging = true;
    try { trackEl.setPointerCapture(e.pointerId); } catch (x) { /* an old browser scrubs without capture */ }
    stop();
    pickAt(e.clientX);
    if (e.pointerType !== "mouse") showTip(nearestAt(e.clientX, true).i);
  });
  trackEl.addEventListener("pointermove", (e) => {
    if (dragging) { scrubTo(e.clientX); return; }
    if (e.pointerType !== "mouse") return;
    const n = nearestAt(e.clientX, true);
    if (n.px <= 14) showTip(n.i, n.x); else hideTip();
  });
  let tipTimer = 0;
  const endDrag = (e) => {
    if (!dragging) return;
    dragging = false;
    if (scrub.i != null || scrub.rest) { scrub.x = e.clientX || scrub.x; scrubSettle(); }
    if (e.pointerType !== "mouse") { clearTimeout(tipTimer); tipTimer = setTimeout(hideTip, 1400); }
  };
  trackEl.addEventListener("pointerup", endDrag);
  trackEl.addEventListener("pointercancel", endDrag);
  trackEl.addEventListener("pointerleave", () => { if (!dragging) hideTip(); });
  rail.addEventListener("focus", () => { if (rail.matches(":focus-visible")) showTip(T.index); });
  rail.addEventListener("blur", hideTip);

  // The names of the marks, in the bar on wide screens and in the tab otherwise.
  const legendHtml = (cls) => "<div class='" + cls + "' role='group' aria-label='What the marks on the track mean'>" +
    "<span><i class='tl-g snap'>" + A.icon("image") + "</i>snapshot</span><span><i class='tl-g snap iso'>" + A.icon("cube") + "</i>3D views</span>" +
    "<span><i class='tl-g ev'></i>events only</span><span><i class='tl-cut'></i>removed</span><span><i class='tl-dn'></i>events</span></div>";
  $("tl-legend").outerHTML = legendHtml("tl-legend in-bar");

  // ---- bar and chip -----------------------------------------------------------
  function syncPlay() {
    // A blocked Play stays focusable and keeps its tooltip, and a press says why it cannot run.
    const why = T.playing ? "" : playBlock();
    for (const id of ["tl-play", "tl-t-play"]) {
      const b = $(id);
      if (!b) continue;
      const lbl = T.playing ? "Pause the timeline" : "Play the timeline";
      b.setAttribute("aria-label", why ? lbl + ". " + why : lbl);
      b.setAttribute("aria-pressed", String(T.playing));
      b.setAttribute("aria-disabled", String(!!why));
      b.title = why || (T.playing ? "Pause (space)" : "Play every snapshot up to now (space)");
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
      chip.innerHTML = A.icon("history", "sm") + "<span id='tl-chip-txt'></span><span class='tl-run' id='tl-run' aria-hidden='true' hidden><i class='done'></i><i class='cur'><b></b></i></span>" +
        "<button class='btn small' id='tl-live'>Return to live</button>";
      $("tl-live").addEventListener("click", () => goLive());
    }
    const r = T.playing && T.run;
    const note = s.kind === "events" ? (r && sameMap(T.index) ? "events only, same map" : s.tiles ? "events only, map of " + slotName(s.tiles) : "no map that day") : s.day && s.day.partial ? "some tiles from the snapshot before" : "";
    const h = (r ? "Playing" : "Viewing") + " <b>" + esc(slotName(s.date, true)) + "</b>" + (r ? " <span class='tl-step'>" + r.step + " of " + r.total + "</span>" : "") +
      (r && T.waiting ? " <span class='tl-loading'>loading</span>" : "") + (note ? " <span class='muted'>" + esc(note) + "</span>" : "");
    const t = $("tl-chip-txt");
    if (t._h !== h) { t.innerHTML = h; t._h = h; }
    chip.classList.toggle("playing", !!r);
    if (!r) $("tl-run").hidden = true;
    chip.hidden = false;
  }
  function layout() {
    const on = barShown();
    bar.hidden = !on;
    if (document.body.classList.contains("tl-open") !== on) {
      document.body.classList.toggle("tl-open", on);
      setTimeout(() => { map.invalidateSize(); drawBar(); }, 40);
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

  const speedOpts = () => SPEEDS.map((v) => "<option value='" + v + "'>" + speedName(v) + "</option>").join("");
  $("tl-speed").innerHTML = speedOpts();
  $("tl-speed").title = SPEED_TIP;
  $("tl-speed").setAttribute("aria-label", SPEED_TIP);
  $("tl-play").addEventListener("click", play);
  $("tl-speed").addEventListener("change", (e) => setSpeed(e.target.value));
  $("tl-changed").addEventListener("click", () => setChanged(!T.changedOn));
  $("tl-close").addEventListener("click", closeBar);
  $("timeline-btn").addEventListener("click", () => { if (barShown()) closeBar(); else openBar(); });

  // ---- keys -----------------------------------------------------------------
  // While the bar is shown, left and right step a slot, space plays and pauses and Home returns to the live map.
  // They are taken before the map sees them, so the arrows do not also pan. On the focused track up and down step
  // too, Page Up and Page Down move a day and End goes to the live map.
  function dayStep(dir) {
    if (!available()) return;
    stop();
    const n = T.slots.length;
    const from = T.index < 0 ? n : T.index;
    const cal = T.index < 0 ? null : T.slots[T.index].cal;
    if (dir < 0) {
      // The first slot of the nearest earlier day that holds one.
      let k = -1;
      for (let i = from - 1; i >= 0 && k < 0; i--) if (selectable(T.slots[i]) && T.slots[i].cal !== cal) k = i;
      if (k < 0) return;
      const day = T.slots[k].cal;
      select(T.slots.findIndex((x) => x.cal === day && selectable(x)));
      return;
    }
    if (T.index < 0) return;
    for (let i = from + 1; i < n; i++) if (selectable(T.slots[i]) && T.slots[i].cal !== cal) { select(i); return; }
    goLive();
  }
  document.addEventListener("keydown", (e) => {
    if (!barShown() || e.metaKey || e.ctrlKey || e.altKey) return;
    const t = e.target;
    if (document.querySelector("dialog[open]")) return;
    const typing = t && (t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable || t.tagName === "INPUT");
    if (typing || (t && t.closest && t.closest("#tabs, .popover"))) return;
    const onRail = t === rail;
    if (e.key === "ArrowLeft" || e.key === "ArrowRight") step(e.key === "ArrowRight" ? 1 : -1);
    else if (onRail && (e.key === "ArrowUp" || e.key === "ArrowDown")) step(e.key === "ArrowUp" ? 1 : -1);
    else if (onRail && (e.key === "PageUp" || e.key === "PageDown")) dayStep(e.key === "PageUp" ? 1 : -1);
    else if (onRail && e.key === "End") goLive();
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
      // The controls, the shown slot and its pictures come first, then the key places, the charts and the feed.
      body.innerHTML = A.head("Timeline", "<span class='badge' id='tl-badge'></span>") + "<div id='tl-msg'></div>" +
        "<div id='tl-main'><div class='tl-ctl'>" +
        "<button class='icon-btn' id='tl-t-prev' aria-label='One step back' title='One step back (left arrow)'>" + A.icon("back") + "</button>" +
        "<button class='icon-btn' id='tl-t-next' aria-label='One step forward' title='One step forward (right arrow)'>" + A.icon("next") + "</button>" +
        "<select id='tl-t-speed' class='tl-select tl-phone-only' aria-label='" + SPEED_TIP + "' title='" + SPEED_TIP + "'>" + speedOpts() + "</select>" +
        "<button class='fchip' id='tl-t-changed'><span class='sw tl-sw'></span>Changed tiles</button>" +
        "<button class='btn small' id='tl-t-live'>" + A.icon("history", "sm") + "Live</button></div>" +
        legendHtml("tl-legend in-tab") + "<div id='tl-sum'></div><div id='tl-keys' class='kp-list' hidden></div><div id='tl-sum2'></div>" +
        "<h3 class='sub'>Over the days held <span class='muted' id='tl-spark-note' hidden>loading</span></h3><div id='tl-spark' class='tl-spark'>" +
        METRICS.map((m) => "<div class='spark' data-m='" + m[0] + "'><div class='spark-head'><span>" + m[1] + "</span><b></b></div><canvas aria-hidden='true'></canvas></div>").join("") + "</div>" +
        "<div id='tl-export'></div></div>" +
        "<h3 class='sub' id='tl-feed-h'></h3><div class='chips' id='tl-chips' style='padding:0 0 10px'></div><div id='tl-feed-msg'></div><ul class='feed' id='tl-feed'></ul>";
      $("tl-t-prev").addEventListener("click", () => step(-1));
      $("tl-t-next").addEventListener("click", () => step(1));
      $("tl-t-speed").addEventListener("change", (e) => setSpeed(e.target.value));
      $("tl-t-changed").addEventListener("click", () => setChanged(!T.changedOn));
      $("tl-t-live").addEventListener("click", () => goLive());
      $("tl-keys").addEventListener("click", onKeys);
      const onSum = (e) => {
        const b = e.target.closest("[data-isod]");
        if (b) { openIsoDay.apply(null, JSON.parse(b.dataset.isod)); return; }
        const k = e.target.closest("[data-kpv]");
        if (k) { const [label, cal, az] = JSON.parse(k.dataset.kpv); const p = keyPlaces().find((x) => x.label === label); const d = p && p.days.find((x) => x.cal === cal); if (d) { kpStop(); kpOpen(p, false, d, az); } return; }
        if (e.target.closest("#tl-evtile")) { const h = $("tl-feed-h"); if (h) h.scrollIntoView({ behavior: reducedMotion() ? "auto" : "smooth", block: "start" }); }
      };
      $("tl-sum").addEventListener("click", onSum);
      $("tl-sum2").addEventListener("click", onSum);
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
      // A row with a place lights up its marker while it is under the pointer or has the focus.
      const hiRow = (e, on) => {
        const r = e.target.closest && e.target.closest("li[data-k]");
        if (!r || (!on && e.relatedTarget && r.contains(e.relatedTarget))) return;
        hiMark(r.dataset.k, on);
      };
      $("tl-feed").addEventListener("mouseover", (e) => hiRow(e, true));
      $("tl-feed").addEventListener("mouseout", (e) => hiRow(e, false));
      $("tl-feed").addEventListener("focusin", (e) => hiRow(e, true));
      $("tl-feed").addEventListener("focusout", (e) => hiRow(e, false));
      $("tl-feed").addEventListener("click", (e) => {
        const b = e.target.closest("[data-ev]");
        if (!b) return;
        const [x, z, at] = b.dataset.ev.split("|").map(Number);
        map.setView(A.toLatLng(x, z), Math.max(map.getZoom(), A.geo.maxNative - 1));
        setTimeout(() => { const m = T.markOf && T.markOf.get(at + "|" + x + "|" + z); if (m) m.openTooltip(); }, 380);
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
      if (last) h += " The newest snapshot is of " + esc(slotName(last.date)) + (last.day.takenAt ? ", taken " + esc(A.stamp(last.day.takenAt)) : "") + ".";
      if (info.nextRunAt) h += " The next one is due " + esc(A.stamp(info.nextRunAt)) + ".";
      h += " Pick a day on the slider or press play.</div>";
      if (T.lastRun) h += "<div class='note'>" + esc(T.lastRun) + "</div>";
      const withViews = T.slots.slice().reverse().find((x) => x.kind === "snap" && isoViewsOf(x).length);
      return [h, withViews ? isoHtml(withViews, true) : ""];
    }
    const d = s.day || {};
    const st = T.stats.get(key(s.date));
    const ch = T.changed.get(key(s.date));
    let h = "<div class='tiles'><div class='tile'><b>" + (s.kind === "snap" ? num(d.tiles, 0) : "none") + "</b><span>Tiles</span></div>" +
      "<div class='tile'><b>" + (s.kind === "snap" ? (ch && ch.first ? "first" : num(d.changed, 0)) : "none") + "</b><span>Changed</span></div>" +
      "<button class='tile tl-evtile' id='tl-evtile' aria-label='" + esc("Go to the " + A.plural(d.events || 0, "event") + " of this slot in the list below") + "'><b>" + num(d.events || 0, 0) + "</b><span>Events " + A.icon("chevron", "xs") + "</span></button></div>" + shotsHtml(s);
    if (s.kind === "events") h += "<div class='note'>No snapshot was taken that day. " + (s.tiles ? "The map shows the snapshot of " + esc(slotName(s.tiles)) + "." : "There is no earlier map to show.") + "</div>";
    if (d.partial) h += "<div class='note'><span class='badge warn'>partial</span> Some tiles were being redrawn during the snapshot and show the day before.</div>";
    // The figures and pictures of the slot sit above the key places, its stats below them.
    const top = h;
    h = "";
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
    return [top, h + isoHtml(s, false)];
  }

  // ---- daily 3D views ------------------------------------------------------------
  // Views drawn with a day's snapshot, as thumbnails that open in the 3D lightbox. The images answer 403 unless
  // the viewer may see terrain and iso, so they are only shown then. A past day's image never changes.
  const isoShown = () => A.layerOn("terrain") && state.layers.iso !== false;
  // Every view of a slot, key places included. The thumbnails leave key places out, they have a block of their own.
  const allViewsOf = (s) => isoShown() && s && s.day && !s.day.pruned && Array.isArray(s.day.isoViews) ? s.day.isoViews.filter((v) => v && v.label && v.file) : [];
  const isoViewsOf = (s) => allViewsOf(s).filter((v) => !v.keyPlace);
  // One step per day for views, since every slot of a day shows the views drawn with its first slot.
  const viewDays = () => { const seen = new Set(); return T.slots.filter((x) => x.kind === "snap" && !seen.has(x.cal) && (seen.add(x.cal), true)); };
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
    return "<h3 class='sub'>" + esc(latest ? "3D views of " + dayName(s.cal) : "3D views of the day") + "</h3><div class='iso-thumbs'>" + views.map((v) =>
      "<button class='iso-thumb' data-isod='" + esc(JSON.stringify([s.date, v.label])) + "' aria-label='" + esc("Open the 3D view " + isoLabel(v.label) + " of " + dayName(s.cal)) + "'>" +
      "<img src='" + esc(v.file) + "' alt='' loading='lazy' decoding='async'><span>" + esc(isoLabel(v.label)) + "</span></button>").join("") + "</div>";
  }
  // The pictures of the shown slot as a small strip under its figures. A key place shows once, from the direction
  // picked for it. Each opens the 3D lightbox.
  function shotsHtml(s) {
    const views = allViewsOf(s);
    if (!views.length) return "";
    const seen = new Set();
    const list = [];
    for (const v of views) {
      if (!v.keyPlace) { list.push(v); continue; }
      if (seen.has(v.label)) continue;
      const az = KAZ.some((a) => a[0] === Number(K.az[v.label])) ? Number(K.az[v.label]) : 0;
      const pick = views.find((x) => x.keyPlace && x.label === v.label && Number(x.azimuth) === az) || v;
      seen.add(v.label);
      list.push(pick);
    }
    return "<div class='tl-shots' role='group' aria-label='3D views of this snapshot'>" + list.map((v) => {
      const name = v.keyPlace ? (v.name && v.name !== v.label ? v.name : A.keyName ? A.keyName({ label: v.label }) : v.label) : isoLabel(v.label);
      const data = v.keyPlace ? " data-kpv='" + esc(JSON.stringify([v.label, s.cal, Number(v.azimuth) || 0])) + "'" : " data-isod='" + esc(JSON.stringify([s.date, v.label])) + "'";
      return "<button class='tl-shot'" + data + " aria-label='" + esc("Open the 3D view " + name + " of " + dayName(s.cal)) + "' title='" + esc(name) + "'>" +
        "<img src='" + esc(v.file) + "' alt='' loading='lazy' decoding='async'><span>" + esc(name) + "</span></button>";
    }).join("") + "</div>";
  }
  function openIsoDay(date, label) {
    const s = T.slots.find((x) => x.date === date);
    const v = isoViewsOf(s).find((x) => x.label === label);
    if (!v || !A.openIsoDay) return;
    const cal = s ? s.cal : date;
    const days = viewDays().filter((x) => x.cal !== cal).map((x) => { const o = isoViewsOf(x).find((y) => y.label === label); return o ? { date: x.cal, file: o.file } : null; }).filter(Boolean);
    A.openIsoDay({ world: state.world, label, date: cal, file: v.file, x: v.x, z: v.z, radius: v.radius, title: isoLabel(label), days });
  }

  // ---- key places --------------------------------------------------------------
  // The places the server draws from all four directions every day. Each shows the picked direction for the day on
  // the slider, or the newest day while the map is live, a strip of its four directions, and buttons to compare two
  // days with the divider, open the live 3D view and play the place through every day held.
  function keyPlaces() {
    const out = new Map();
    for (const d of viewDays()) {
      for (const v of allViewsOf(d)) {
        if (!v.keyPlace || !KAZ.some((a) => a[0] === Number(v.azimuth))) continue;
        let p = out.get(v.label);
        if (!p) { p = { label: v.label, days: [] }; out.set(v.label, p); }
        Object.assign(p, { name: v.name && v.name !== v.label ? v.name : A.keyName ? A.keyName({ label: v.label }) : v.label, x: v.x, z: v.z, radius: v.radius });
        let day = p.days.find((x) => x.cal === d.cal);
        if (!day) { day = { cal: d.cal, views: {} }; p.days.push(day); }
        day.views[Number(v.azimuth)] = v;
      }
    }
    return Array.from(out.values());
  }
  const kpAz = (p) => KAZ.some((a) => a[0] === Number(K.az[p.label])) ? Number(K.az[p.label]) : 0;
  function kpDay(p) {
    const want = K.shown[p.label];
    if (want) { const d = p.days.find((x) => x.cal === want); if (d) return d; }
    const s = cur();
    if (s) { let best = null; for (const d of p.days) if (d.cal <= s.cal) best = d; return best || p.days[0]; }
    return p.days[p.days.length - 1];
  }
  const kpDir = (az) => KAZ.find((a) => a[0] === az) || KAZ[0];
  // One block for every key place. Chips pick the place when there are several, then one picture from the picked
  // direction with the day on it, the four directions, a row of actions and a strip with a mark per day held.
  const kpPicked = () => kpList.find((p) => p.label === K.pick) || kpList[0] || null;
  function keysHtml(list) {
    if (!list.length) return "";
    const iso = A.isoOk && A.isoOk();
    return "<h3 class='sub'>" + A.icon("camera", "sm") + "Key places</h3><section class='kp1' aria-label='Key places'>" +
      (list.length > 1 ? "<div class='kp-picks' role='group' aria-label='Key place to show'>" + list.map((p) =>
        "<button class='fchip' data-kp-pick='" + esc(p.label) + "' aria-pressed='false'>" + esc(p.name) + "</button>").join("") + "</div>"
        : "<div class='kp-title'>" + esc(list[0].name) + "</div>") +
      "<div class='kp-stage'><button class='kp-big' data-kp-open><img data-kp-img alt='' decoding='async'><span class='kp-none' data-kp-none hidden>No picture from this side that day</span></button>" +
      "<div class='kp-over'><span class='badge kp-day' data-kp-day></span><span class='badge good kp-same' data-kp-same hidden></span></div></div>" +
      "<div class='kp-days' data-kp-days role='group' aria-label='Day to show'></div>" +
      "<div class='kp-ctl'><div class='kp-dirs' role='group' aria-label='Direction to look from'>" + KAZ.map((a) =>
        "<button data-kp-az='" + a[0] + "' aria-label='" + esc("Look from the " + a[2]) + "' title='" + esc("From the " + a[2]) + "'>" + a[1] + "</button>").join("") + "</div>" +
      "<div class='kp-acts'><button class='btn small kp-play' data-kp-play aria-pressed='false'><svg class='ic sm' aria-hidden='true'><use href='#i-play'/></svg><span class='lbl'>Play</span></button>" +
      "<button class='btn small' data-kp-cmp title='Compare two days with a divider'>" + A.icon("history", "sm") + "Compare</button>" +
      (iso ? "<button class='btn small' data-kp-live title='View live in 3D'>" + A.icon("cube", "sm") + "Live 3D</button>" : "") + "</div></div></section>";
  }
  // The picture changes only once the new image has loaded, so it never blinks while playing or turning.
  function setImg(img, url, onDone) {
    if (img._want === url) return;
    img._want = url;
    if (!url) { img.removeAttribute("src"); return; }
    const im = new Image();
    const put = () => { if (img._want === url) { img.src = url; if (onDone) onDone(); } };
    im.onload = put;
    im.onerror = put;
    im.src = url;
  }
  // The day the main slider stands on for a place, the newest while the map is live.
  function kpSliderDay(p) {
    const s = cur();
    if (!s) return p.days[p.days.length - 1];
    let best = null;
    for (const d of p.days) if (d.cal <= s.cal) best = d;
    return best || p.days[0];
  }
  function paintKp(box, p) {
    const d = kpDay(p);
    const az = kpAz(p);
    const v = d && d.views[az];
    const dir = kpDir(az);
    box.querySelectorAll("[data-kp-pick]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.kpPick === p.label)));
    const img = box.querySelector("[data-kp-img]");
    setImg(img, v ? new URL(v.file, location.href).href : "");
    img.hidden = !v;
    box.querySelector("[data-kp-none]").hidden = !!v;
    const big = box.querySelector("[data-kp-open]");
    big.disabled = !v;
    big.setAttribute("aria-label", "Open " + p.name + " from the " + dir[2] + (d ? " on " + dayName(d.cal) : "") + " in the 3D view");
    const db = box.querySelector("[data-kp-day]");
    const dt = d ? dayName(d.cal, true) + ", from the " + dir[1] : "";
    if (db.textContent !== dt) db.textContent = dt;
    const same = box.querySelector("[data-kp-same]");
    const sameDay = v && v.unchanged && v.sameAs && String(v.sameAs).slice(0, 10) !== d.cal ? String(v.sameAs).slice(0, 10) : "";
    same.hidden = !sameDay;
    if (sameDay) same.textContent = "unchanged since " + dayName(sameDay);
    box.querySelectorAll("[data-kp-az]").forEach((b) => {
      const a = Number(b.dataset.kpAz);
      b.setAttribute("aria-pressed", String(a === az));
      b.disabled = !(d && d.views[a]);
    });
    // The day strip, one mark per day held. The shown day is filled, a day whose picture did not change is muted
    // and a small tick under a mark says where the main slider is.
    const strip = box.querySelector("[data-kp-days]");
    const ssig = p.label + "|" + az + "|" + p.days.map((x) => x.cal + (x.views[az] ? (x.views[az].unchanged ? "u" : "v") : "n")).join(",");
    if (strip._sig !== ssig) {
      strip._sig = ssig;
      const n = p.days.length;
      strip.innerHTML = "<i class='kp-line'></i>" + p.days.map((x, i) => {
        const w = x.views[az];
        const label = i === 0 || i === n - 1 || n <= 8;
        return "<button class='kp-dm" + (w && w.unchanged ? " same" : "") + (w ? "" : " none") + "' data-kp-d='" + esc(x.cal) + "' aria-label='" + esc("Show " + dayName(x.cal) + (w && w.unchanged ? ", unchanged" : w ? "" : ", no picture from this side")) + "'>" +
          "<i></i>" + (label ? "<span>" + esc(shortDay(x.cal, false)) + "</span>" : "") + "</button>";
      }).join("");
    }
    const here = kpSliderDay(p);
    strip.querySelectorAll("[data-kp-d]").forEach((b) => {
      const on = !!d && b.dataset.kpD === d.cal;
      b.classList.toggle("sel", on);
      b.setAttribute("aria-pressed", String(on));
      b.classList.toggle("here", !!here && b.dataset.kpD === here.cal);
    });
    const play = box.querySelector("[data-kp-play]");
    const on = !!(K.play && K.play.label === p.label);
    play.setAttribute("aria-pressed", String(on));
    play.setAttribute("aria-label", (on ? "Pause " : "Play ") + p.name + " through the days held");
    play.title = on ? "Pause" : "Play " + p.name + " through the days held";
    play.querySelector("use").setAttribute("href", on ? "#i-pause" : "#i-play");
    play.querySelector(".lbl").textContent = on ? "Pause" : "Play";
    play.disabled = p.days.length < 2;
    if (!on) play.classList.remove("tl-dwell");
    box.querySelector("[data-kp-cmp]").disabled = p.days.filter((x) => x.views[az]).length < 2 || !v;
  }
  let kpList = [];
  function drawKeys() {
    const box = $("tl-keys");
    if (!box) return;
    kpList = available() ? keyPlaces() : [];
    const sig = kpList.map((p) => p.label + ":" + p.name).join("|") + "|" + (A.isoOk && A.isoOk());
    if (box._sig !== sig) { box._sig = sig; box.innerHTML = keysHtml(kpList); }
    box.hidden = !kpList.length;
    const p = kpPicked();
    if (p) paintKp(box, p);
  }
  function kpStop() {
    if (!K.play) return;
    clearTimeout(K.play.timer);
    K.play = null;
    drawKeys();
  }
  // The progress ring of the place's Play button fills over each day, like the main Play.
  function kpTick() {
    const b = document.querySelector("#tl-keys [data-kp-play]");
    if (!b) return;
    b.classList.remove("tl-dwell");
    if (!K.play) return;
    void b.offsetWidth;
    b.style.setProperty("--tl-dwell", T.speed + "ms");
    b.classList.add("tl-dwell");
  }
  // Play steps the place through every day held in the picked direction, from the day shown, and stops at the newest.
  function kpPlay(p) {
    if (K.play && K.play.label === p.label) { kpStop(); return; }
    kpStop();
    const days = p.days;
    let i = days.indexOf(kpDay(p));
    if (i < 0 || i >= days.length - 1) i = 0;
    K.shown[p.label] = days[i].cal;
    K.play = { label: p.label, timer: 0 };
    const pre = (j) => { const nx = days[j] && days[j].views[kpAz(p)]; if (nx) { const im = new Image(); im.src = nx.file; } };
    const stepKp = () => {
      if (!K.play || K.play.label !== p.label) return;
      i++;
      if (i >= days.length) { kpStop(); return; }
      K.shown[p.label] = days[i].cal;
      pre(i + 1);
      drawKeys();
      if (i >= days.length - 1) { kpStop(); return; }
      K.play.timer = setTimeout(stepKp, T.speed);
      kpTick();
    };
    pre(i + 1);
    drawKeys();
    K.play.timer = setTimeout(stepKp, T.speed);
    kpTick();
  }
  function kpOpen(p, compare, day, azimuth) {
    const d = day || kpDay(p);
    const az = azimuth != null ? azimuth : kpAz(p);
    const v = d && d.views[az];
    if (!v || !A.openIsoDay) return;
    const days = p.days.filter((x) => x !== d && x.views[az]).map((x) => ({ date: x.cal, file: x.views[az].file }));
    let cmp = null;
    if (compare && days.length) {
      const before = days.filter((x) => x.date < d.cal);
      cmp = (before.length ? before[before.length - 1] : days[0]).date;
    }
    A.openIsoDay({ world: state.world, label: p.label + "-" + az, date: d.cal, file: v.file, x: p.x, z: p.z, radius: p.radius, az,
      title: p.name + ", from the " + kpDir(az)[2], days, cmp });
  }
  function onKeys(e) {
    const b = e.target.closest("button");
    if (!b || b.disabled) return;
    if (b.dataset.kpPick != null) {
      // Another place shows no picture of the old one while its own image loads.
      if (b.dataset.kpPick !== (kpPicked() || {}).label) { const img = $("tl-keys").querySelector("[data-kp-img]"); if (img) { img._want = null; img.removeAttribute("src"); } }
      kpStop(); K.pick = b.dataset.kpPick; A.store.set("kpPick", K.pick); drawKeys(); return; }
    const p = kpPicked();
    if (!p) return;
    if (b.dataset.kpAz != null) { K.az[p.label] = Number(b.dataset.kpAz); A.store.set("kpAz", K.az); drawKeys(); drawTabSoon(); }
    else if (b.dataset.kpD != null) { kpStop(); K.shown[p.label] = b.dataset.kpD; drawKeys(); }
    else if (b.hasAttribute("data-kp-play")) kpPlay(p);
    else if (b.hasAttribute("data-kp-cmp")) { kpStop(); kpOpen(p, true); }
    else if (b.hasAttribute("data-kp-open")) { kpStop(); kpOpen(p, false); }
    else if (b.hasAttribute("data-kp-live") && A.openIso) A.openIso({ world: state.world, x: p.x, z: p.z, r: p.radius, exactR: true, az: kpAz(p), title: p.name });
  }

  function feedRow(e, past) {
    const k = kindLook(e.kind);
    const show = placedEv(e) ? " <button class='btn small' data-ev='" + Number(e.x) + "|" + Number(e.z) + "|" + Number(e.at) + "' aria-label='" + esc("Show " + (e.text || k[2]) + " on the map") + "'>Show</button>" : "";
    const when = past ? "<time>" + esc(A.clock(e.at)) + "</time>" : A.when(e.at);
    return "<li class='tl-ev'" + (placedEv(e) ? " data-k='" + evKey(e) + "'" : "") + "><span class='ico' style='background:" + k[1] + ";color:" + A.inkOn(k[1]) + "'>" + A.icon(k[0]) + "</span><div class='body'><span class='tl-kind'>" + esc(k[2]) + "</span> " +
      esc(e.text || "") + (e.world && e.world !== state.world ? " <span class='badge'>" + esc(A.worldLabel(e.world)) + "</span>" : "") + show + when + "</div></li>";
  }
  function drawFeed() {
    const s = cur();
    const past = !!s;
    const d = s ? T.events.get(key(s.date)) : T.live;
    const hEl = $("tl-feed-h");
    const w = s && s.day && Array.isArray(s.day.window) ? s.day.window : null;
    const part = w && (w[0] > 0 || w[1] < 24) ? ", " + hh(w[0]) + " to " + (w[1] >= 24 ? "midnight" : hh(w[1])) : "";
    const ht = s ? "Events of " + dayName(s.cal, true) + part : "Events in the last 24 hours";
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
    // The events of a slot sit under a heading per hour, newest first, so the time they took place reads at a glance.
    let rows = "";
    if (past) {
      const hourKey = (e) => { const t = new Date(Number(e.at)); return isNaN(t) ? "" : t.toDateString() + "|" + t.getHours(); };
      const counts = new Map();
      for (const e of list) { const k = hourKey(e); counts.set(k, (counts.get(k) || 0) + 1); }
      let last = null;
      for (const e of list) {
        const k = hourKey(e);
        if (k !== last) {
          last = k;
          const h = k ? Number(k.split("|")[1]) : null;
          rows += "<li class='tl-hr' aria-hidden='true'><span>" + (h == null ? "Unknown time" : esc(hh(h) + " to " + hh((h + 1) % 24))) + "</span><span class='muted'>" + esc(A.plural(counts.get(k), "event")) + "</span></li>";
        }
        rows += feedRow(e, past);
      }
    } else rows = list.map((e) => feedRow(e, past)).join("");
    const feed = $("tl-feed");
    if (feed._h !== rows) { if (T.hi) hiMark(T.hi, false); feed.innerHTML = rows; feed._h = rows; }
  }

  function drawTab() {
    if (!tabOn()) return;
    const msg = $("tl-msg"), main = $("tl-main");
    const badge = $("tl-badge");
    const s = cur();
    const bt = s ? slotName(s.date) : available() ? "Live" : "";
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
      [$("tl-sum"), $("tl-sum2")].forEach((se, i) => { if (se._h !== sum[i]) { se.innerHTML = sum[i]; se._h = sum[i]; } });
      drawSparks();
      if (A.drawTlExport) A.drawTlExport($("tl-export"));
    }
    if (ready) drawKeys(); else if ($("tl-keys")) $("tl-keys").hidden = true;
    if (feedOk) drawFeed();
  }
  const drawTabSoon = A.throttle(() => drawTab(), 150);

  // ---- redraws ----------------------------------------------------------------
  const redrawCanvas = A.throttle(() => { drawBar(); drawSparks(); }, 200);
  window.addEventListener("resize", redrawCanvas);
  // The charts read their colours from the theme, so a theme switch draws them again.
  new MutationObserver(redrawCanvas).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  // The panel opening or closing moves the bar on desktop.
  new MutationObserver(() => { if (barShown()) setTimeout(() => { map.invalidateSize(); drawBar(); }, 260); }).observe(document.body, { attributes: true, attributeFilter: ["class"] });
})();
