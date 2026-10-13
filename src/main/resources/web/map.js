/* Atlas map. Leaflet setup, tile geometry, every overlay, map tools, coordinates and the shareable URL. */
(function () {
  "use strict";

  const A = window.Atlas;
  const { $, esc, col, num, state, CHUNK } = A;

  if (!window.L) {
    A.noLeaflet = true;
    return;
  }

  // ---- map and geometry ---------------------------------------------------
  // CRS.Simple with one map unit per block. Leaflet zoom L draws 2^L pixels per block, so Atlas zoom 0
  // (half a pixel per block) is Leaflet zoom -1 and Atlas zoom z is Leaflet zoom -1 - z.
  const touch = window.matchMedia && matchMedia("(hover: none), (pointer: coarse)").matches;
  const map = A.map = L.map("map", {
    crs: L.CRS.Simple, minZoom: -5, maxZoom: 2, zoomSnap: 1, zoomControl: false, attributionControl: false,
    keyboard: true, worldCopyJump: false, inertia: true, preferCanvas: false
  });
  map.setView([0, 0], -1);
  const toLatLng = A.toLatLng = (x, z) => L.latLng(-z, x);
  const fromLatLng = A.fromLatLng = (ll) => ({ x: Math.floor(ll.lng), z: Math.floor(-ll.lat) });
  const geo = A.geo = { lToZ: (l) => -1 - l, zToL: (z) => -1 - z, maxNative: -1, minNative: -5 };

  const AtlasTiles = L.TileLayer.extend({
    getTileUrl: function (c) {
      const key = geo.lToZ(c.z) + "/" + c.x + "/" + c.y;
      const v = state.tileVersions.get(key) || 0;
      return "api/tiles/" + encodeURIComponent(state.world || "") + "/" + key + ".png" + (v ? "?v=" + v : "");
    }
  });
  const terrain = A.terrain = new AtlasTiles("", {
    tileSize: 256, minZoom: -5, maxZoom: 2, minNativeZoom: -5, maxNativeZoom: -1, noWrap: true, keepBuffer: 3,
    updateWhenZooming: false, className: "atlas-tiles"
  });
  // A missing tile is usually unexplored land, so it is not asked for again until a tiles event names it.
  terrain.on("tileerror", (e) => { e.tile.style.visibility = "hidden"; });
  terrain.on("tileload", (e) => { e.tile.style.visibility = ""; });
  const blank = L.layerGroup();

  // The tiles of one day on the timeline, with the same geometry as the live tiles. state.tlTiles names the
  // snapshot whose tiles are shown. A past day never changes, so the browser keeps its tiles for a year.
  const dayUrl = (date, c) => "api/timeline/" + encodeURIComponent(state.world || "") + "/" + encodeURIComponent(date || "") + "/tiles/" + geo.lToZ(c.z) + "/" + c.x + "/" + c.y + ".png";
  const DayTiles = L.TileLayer.extend({ getTileUrl: function (c) { return dayUrl(state.tlTiles, c); } });
  const dayTerrain = A.dayTerrain = new DayTiles("", {
    tileSize: 256, minZoom: -5, maxZoom: 2, minNativeZoom: -5, maxNativeZoom: -1, noWrap: true, keepBuffer: 3,
    updateWhenZooming: false, className: "atlas-tiles day-tiles"
  });
  // A tile that did not exist that day answers 404 and stays empty.
  dayTerrain.on("tileerror", (e) => { e.tile.style.visibility = "hidden"; });
  dayTerrain.on("tileload", (e) => { e.tile.style.visibility = ""; });
  // Moving to another day swaps each tile on screen once its new image has loaded, so the map never flashes
  // empty while the timeline plays. A late answer for a day that is no longer shown is ignored.
  let swapSeq = 0;
  A.swapDayTiles = () => {
    const had = map.hasLayer(dayTerrain);
    A.applyOverlays();
    if (!had || !map.hasLayer(dayTerrain)) return;
    const seq = ++swapSeq;
    for (const key in dayTerrain._tiles) {
      const t = dayTerrain._tiles[key];
      const url = dayTerrain.getTileUrl(t.coords);
      if (t.el._day === url) continue;
      t.el._day = url;
      const im = new Image();
      im.onload = () => { if (seq === swapSeq) { t.el.src = url; t.el.style.visibility = ""; } };
      im.onerror = () => { if (seq === swapSeq) t.el.style.visibility = "hidden"; };
      im.src = url;
    }
  };
  dayTerrain.on("tileloadstart", (e) => { e.tile._day = dayTerrain.getTileUrl(e.coords); });
  // Tile addresses for the exports, which draw the view onto a canvas. coords has x, y and the Leaflet zoom z.
  A.dayTileUrl = dayUrl;
  A.liveTileUrl = (c) => terrain.getTileUrl(c);
  // Warms the browser cache with the tiles on screen for the day that plays next.
  A.preloadDay = (date) => {
    if (!date || !map.hasLayer(dayTerrain)) return;
    for (const key in dayTerrain._tiles) { const im = new Image(); im.src = dayUrl(date, dayTerrain._tiles[key].coords); }
  };

  // Panes of their own. The tint of changed tiles sits right above the tiles, the edge of a render limit covers
  // the tiles and the tint, and the markers of a past day stay at full strength while the live overlays dim.
  map.createPane("tlChanged").style.zIndex = 250;
  map.createPane("limitMask").style.zIndex = 300;
  map.createPane("tlMarks").style.zIndex = 640;
  A.renderers = { tlChanged: L.svg({ pane: "tlChanged" }), limitMask: L.svg({ pane: "limitMask", padding: 0.5 }) };

  // Applies the tile geometry from api/worlds. The Leaflet zoom of each level comes from its pixels per block,
  // which matches -1 - z for every level the server lists.
  A.initGeometry = (tile) => {
    tile = tile || {};
    const levels = tile.levels && typeof tile.levels === "object" ? tile.levels : {};
    const zs = Object.keys(levels).map(Number).filter((n) => !Number.isNaN(n));
    let minZ = zs.length ? Math.min.apply(null, zs) : Number(tile.minZoom);
    let maxZ = zs.length ? Math.max.apply(null, zs) : Number(tile.maxZoom);
    if (!(minZ <= maxZ)) { minZ = -2; maxZ = 4; }
    const lOf = {};
    const zOf = {};
    for (let z = minZ; z <= maxZ; z++) {
      const lv = levels[String(z)];
      const l = lv && lv.pxPerBlock > 0 ? Math.round(Math.log2(lv.pxPerBlock)) : -1 - z;
      lOf[z] = l;
      zOf[l] = z;
    }
    geo.zToL = (z) => lOf[z] != null ? lOf[z] : -1 - z;
    geo.lToZ = (l) => zOf[l] != null ? zOf[l] : -1 - l;
    geo.maxNative = geo.zToL(minZ);
    geo.minNative = geo.zToL(maxZ);
    geo.maxZoom = geo.maxNative + 3;
    geo.minLevel = minZ;
    geo.maxLevel = maxZ;
    for (const t of [terrain, dayTerrain]) {
      t.options.minNativeZoom = geo.minNative;
      t.options.maxNativeZoom = geo.maxNative;
      t.options.minZoom = geo.minNative;
      t.options.maxZoom = geo.maxZoom;
    }
    map.setMinZoom(geo.minNative);
    map.setMaxZoom(geo.maxZoom);
    updateOverzoom();
  };
  function updateOverzoom() { map.getContainer().classList.toggle("overzoom", map.getZoom() > geo.maxNative); }
  map.on("zoomend", updateOverzoom);

  // A comfortable default zoom, one step out from the sharpest native level.
  A.defaultZoom = () => Math.max(geo.minNative, Math.min(geo.maxNative - 1, -1));
  // The Atlas zoom level of the tiles on screen. Zoomed in past the sharpest level it stays at that level.
  A.atlasZoom = () => geo.lToZ(Math.max(geo.minNative, Math.min(geo.maxNative, map.getZoom())));

  // ---- overlay groups -----------------------------------------------------
  const groups = A.groups = {
    border: L.layerGroup(), territories: L.layerGroup(), sieges: L.layerGroup(), zones: L.layerGroup(), players: L.layerGroup(), paths: L.layerGroup(),
    markers: L.layerGroup(), engine: L.layerGroup(), usermarks: L.layerGroup(), worldevents: L.layerGroup(), points: L.layerGroup(), heat: L.layerGroup()
  };
  const measureGroup = L.layerGroup().addTo(map);
  const OVERLAYS = [
    // Only listed for a world whose border the server reports.
    { key: "border", label: "World border", vis: () => !!A.worldBorder(), def: true },
    { key: "territories", label: "Territories", vis: () => A.layerOn("territories") && !state.standalone, def: true },
    { key: "sieges", label: "Sieges", vis: () => A.layerOn("territories") && !state.standalone, def: true },
    { key: "zones", label: "Zone names", vis: () => A.layerOn("zones"), def: true },
    { key: "players", label: "Players", vis: () => A.layerOn("players") && !state.playersHidden, def: true },
    { key: "paths", label: "Player paths", vis: () => A.layerOn("paths"), def: false },
    { key: "markers", label: "Spawn and places", vis: () => A.layerOn("markers"), def: true },
    { key: "engine", label: "Engine markers", vis: () => A.layerOn("markers"), def: true },
    // Player made markers are switched by kind with the chips below the layer list, so they have no checkbox.
    { key: "usermarks", label: "Player markers", vis: () => A.layerOn("markers"), def: true, list: false },
    { key: "worldevents", label: "World events", vis: () => A.layerOn("worldevents"), def: true },
    { key: "points", label: "Points", vis: () => A.layerOn("points") || A.layerOn("events"), def: true },
    { key: "heat", label: "Activity heat", vis: () => A.layerOn("heat"), def: false }
  ];
  const userOverlays = A.store.get("overlays", {});
  const wantOverlay = (o) => userOverlays[o.key] != null ? !!userOverlays[o.key] : o.def;
  A.overlayShown = (key) => map.hasLayer(groups[key]);

  A.applyOverlays = () => {
    for (const o of OVERLAYS) {
      const on = o.vis() && wantOverlay(o);
      if (on && !map.hasLayer(groups[o.key])) {
        groups[o.key].addTo(map);
        if (o.key === "heat") A.loadHeat();
        if (o.key === "paths") A.loadPaths();
      } else if (!on && map.hasLayer(groups[o.key])) {
        map.removeLayer(groups[o.key]);
      }
    }
    const canTerrain = state.layers.terrain !== false;
    // While a past day is shown its tiles take the place of the live ones. A day with no snapshot before it is blank.
    const wantTerrain = canTerrain && A.store.get("base", "terrain") === "terrain";
    const base = !wantTerrain ? blank : state.tlDate ? (state.tlTiles ? dayTerrain : blank) : terrain;
    for (const l of [terrain, dayTerrain, blank]) if (l !== base && map.hasLayer(l)) map.removeLayer(l);
    if (!map.hasLayer(base)) base.addTo(map);
    A.applyMarkerLayers();
    A.setNight();
    if (!$("layers-pop").hidden) renderLayersPop();
  };

  // ---- day and night ------------------------------------------------------
  // A very light dimming of the terrain from the world clock in api/worlds.
  A.setNight = () => {
    const w = A.currentWorld();
    let night = 0;
    // Off unless the viewer turns it on. Full colour from hour 7 to 18, easing to the darkest point at midnight.
    if (A.store.get("tint", false) && w && w.time && w.time.hour != null && map.hasLayer(terrain) && !state.tlDate) {
      const h = (Number(w.time.hour) + (w.time.minute ? w.time.minute / 60 : 0)) % 24;
      const fromDay = h >= 18 ? h - 18 : h < 7 ? h + 6 : 0;
      const span = 6, back = 7;
      night = fromDay <= 0 ? 0 : fromDay <= span ? fromDay / span : Math.max(0, 1 - (fromDay - span) / back);
    }
    map.getContainer().style.setProperty("--night", night.toFixed(3));
  };

  // ---- symbols ------------------------------------------------------------
  const POINT_KIND = {
    home: ["home", "#3f7fc9", "Home"], warp: ["warp", "#8a5ccf", "Warp"], rally: ["flag", "#d66a35", "Rally"], beacon: ["diamond", "#c7951c", "Beacon"],
    outpost: ["tower", "#2f8d58", "Outpost"], ping: ["alert", "#cf4a42", "Ping"], waypoint: ["dot", "#4a8ea4", "Waypoint"], siege: ["swords", "#b0352c", "Siege"]
  };
  const EVENT_KIND = { hill: ["crown", "#c2552a", "Hill"], koth: ["crown", "#c2552a", "Hill"], outpost: ["tower", "#2d7f45", "Outpost"], envoy: ["feather", "#a87a17", "Envoy"] };
  A.POINT_KIND = POINT_KIND;
  // Engine marker images are mapped to the built in symbol set by name, with a generic pin as the fallback.
  A.engineIcon = (name) => {
    const n = String(name || "").toLowerCase();
    if (/hunt|target|prey/.test(n)) return "target";
    if (/breach|portal|rift|gate/.test(n)) return "portal";
    if (/objective|quest|goal/.test(n)) return "flag";
    if (/spawn|respawn/.test(n)) return "spawn";
    if (/home|bed|house/.test(n)) return "home";
    if (/boss/.test(n)) return "boss";
    if (/camp|fort|tower|outpost/.test(n)) return "tower";
    if (/death|skull|grave/.test(n)) return "skull";
    if (/warp|teleport/.test(n)) return "warp";
    return "pin";
  };
  // The inside of a round symbol badge. A game marker image when one is loaded, otherwise the built in symbol.
  A.symInner = (icon, img) => img ? "<img src='" + esc(img) + "' alt='' draggable='false'>" : A.icon(icon);
  function symIcon(icon, color, opts) {
    opts = opts || {};
    const size = opts.size === "big" ? 34 : opts.size === "small" ? 22 : 28;
    // The clickable box is at least 40 px on touch screens and 32 px elsewhere, with the symbol centred in it.
    const pad = Math.max(0, (touch ? 40 : 32) - (size + 4));
    const w = opts.count ? 80 : size + 4 + pad;
    const h = (opts.count ? size + 22 : size + 4) + pad;
    const pulse = opts.pulse ? " pulse" : "";
    const pc = opts.pulse ? ";--pulse:" + (opts.pulseColor || "rgba(220,70,50,.55)") : "";
    // Symbols are drawn in the ink that reads best on the badge, since player tints can be very light.
    const ink = (opts.img ? "" : ";color:" + A.inkOn(color)) + (opts.ring && !opts.own ? ";border-color:" + opts.ring : "");
    return L.divIcon({
      className: "", iconSize: [w, h], iconAnchor: [w / 2, size / 2 + 2 + pad / 2],
      html: "<div class='symwrap' style='padding-top:" + pad / 2 + "px'><div class='sym " + (opts.size || "") + pulse + (opts.own ? " own" : "") + (opts.img ? " img" : "") + "' style='background:" + color + ink + pc + "'>" + A.symInner(icon, opts.img) + "</div>" +
        (opts.count ? "<div class='count' data-ends='" + (opts.endsAt || "") + "'></div>" : "") + "</div>"
    });
  }
  const alpha = (hex, a) => /^#[0-9a-fA-F]{6}$/.test(hex) ? hex + a : hex;
  const tip = (m, html, o) => m.bindTooltip(html, Object.assign({ className: "card", direction: "top", offset: [0, -14] }, o || {}));
  const kindHidden = (k) => state.hiddenKinds.has(k);
  A.symIcon = symIcon;
  A.tip = tip;

  // Every marker carries an accessible name, its label and kind, on the element Leaflet creates for it. Leaflet
  // makes a new element on setIcon, so the label is set again each time. A marker that is not a tab stop is an image.
  const Marker = L.Marker.extend({
    _initIcon: function () {
      L.Marker.prototype._initIcon.call(this);
      const el = this._icon;
      if (!el) return;
      if (this.options.label) el.setAttribute("aria-label", this.options.label);
      if (!this.options.keyboard) el.setAttribute("role", "img");
    }
  });
  const marker = A.marker = (ll, opts) => new Marker(ll, opts);

  // ---- marker actions -------------------------------------------------------
  // A click on a marker opens its card as a small popup with buttons such as View in 3D and Open the faction. The
  // hover card stays a tooltip, and a marker with nothing to offer keeps doing what a click did before.
  const actPop = L.popup({ className: "atlas-popup act-pop", maxWidth: 300, minWidth: 190, autoPanPadding: [24, 24], offset: [0, -12] });
  A.openActions = (latlng, html, acts, from) => {
    acts = (acts || []).filter(Boolean);
    if (!acts.length) return false;
    const el = document.createElement("div");
    el.innerHTML = html + "<div class='tc-acts'>" + acts.map((a, i) => "<button class='btn small" + (i === 0 ? " primary" : "") + "' data-i='" + i + "'" + (a.aria ? " aria-label='" + esc(a.aria) + "'" : "") + ">" + A.icon(a.icon, "sm") + esc(a.label) + "</button>").join("") + "</div>";
    el.querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", () => { map.closePopup(actPop); acts[+b.dataset.i].run(); }));
    if (from && from.closeTooltip) from.closeTooltip();
    actPop.setLatLng(latlng).setContent(el).openOn(map);
    const f = el.querySelector("button");
    if (f && !touch) f.focus({ preventScroll: true });
    return true;
  };
  // The View in 3D button of a card, or nothing while the server does not offer views to this viewer.
  A.isoAct = (o, what) => A.isoOk && A.isoOk() ? { label: "View in 3D", icon: "cube", aria: "View " + (what || "this place") + " in 3D", run: () => A.openIso(Object.assign({ world: state.world }, o)) } : null;
  A.factionAct = (tag) => tag && A.layerOn("factions") ? { label: "Open faction", icon: "shield", run: () => A.emit("openFaction", tag) } : null;
  // A popup a plugin or the server prepared is kept and gets the action row under it.
  A.withActions = (html, acts) => {
    const el = document.createElement("div");
    el.innerHTML = html;
    acts = (acts || []).filter(Boolean);
    if (!acts.length) return el;
    const row = document.createElement("div");
    row.className = "tc-acts";
    row.innerHTML = acts.map((a, i) => "<button class='btn small' data-i='" + i + "'" + (a.aria ? " aria-label='" + esc(a.aria) + "'" : "") + ">" + A.icon(a.icon, "sm") + esc(a.label) + "</button>").join("");
    row.querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", () => { map.closePopup(); acts[+b.dataset.i].run(); }));
    el.appendChild(row);
    return el;
  };

  // ---- tooltip cards --------------------------------------------------------
  // Every map tooltip is a small card. A title line, a muted subtitle, then the block position on its own line
  // in tabular figures. title and sub are html that the caller has already escaped.
  const coord = (n) => String(Math.round(Number(n)));
  A.xyz = (x, y, z) => "<div class='tc-xyz'><span>X " + coord(x) + "</span>" + (y != null && !Number.isNaN(Number(y)) ? "<span>Y " + coord(y) + "</span>" : "") + "<span>Z " + coord(z) + "</span></div>";
  A.xyzText = (x, y, z) => "X " + coord(x) + (y != null && !Number.isNaN(Number(y)) ? "  Y " + coord(y) : "") + "  Z " + coord(z);
  const card = A.card = (title, sub, pos, extra) => "<div class='tc-title'>" + title + "</div>" + (sub ? "<div class='tc-sub'>" + sub + "</div>" : "") +
    (pos && pos.x != null && pos.z != null ? A.xyz(pos.x, pos.y, pos.z) : "") + (extra || "");

  // ---- game marker images ---------------------------------------------------
  // api/marker-icons serves the game's own map marker images. Each name is asked for once. A name the server
  // does not have keeps the built in symbol. The user marker images are
  // white glyphs with a dark outline, so they sit untinted on a badge in the marker's tint, like every other symbol.
  const gameIcons = new Map();
  let iconsQueued = false;
  function iconsArrived() {
    if (iconsQueued) return;
    iconsQueued = true;
    setTimeout(() => { iconsQueued = false; drawMarkers(); A.emit("markerIcons"); }, 60);
  }
  A.gameIcon = (name) => {
    if (!name || !/^[A-Za-z0-9_]{1,40}$/.test(name)) return null;
    const url = "api/marker-icons/" + name + ".png";
    const s = gameIcons.get(name);
    if (s === "ok") return url;
    if (s) return null;
    gameIcons.set(name, "wait");
    const im = new Image();
    im.onload = () => { gameIcons.set(name, im.naturalWidth ? "ok" : "bad"); if (im.naturalWidth) iconsArrived(); };
    im.onerror = () => gameIcons.set(name, "bad");
    im.src = url;
    return null;
  };

  // ---- countdowns ---------------------------------------------------------
  function tickCountdowns() {
    const now = Date.now();
    document.querySelectorAll("[data-ends]").forEach((el) => {
      const t = Number(el.getAttribute("data-ends"));
      el.textContent = t ? A.fmtLeft(t - now) : "";
    });
  }
  A.tickCountdowns = tickCountdowns;
  setInterval(tickCountdowns, 1000);

  // ---- players ------------------------------------------------------------
  function playerIcon(p) {
    const ring = col(p.color, "#8f877b");
    const cls = "pm" + (p.self ? " self" : "") + (state.follow === p.uuid ? " followed" : "");
    return L.divIcon({
      className: cls, iconSize: [0, 0],
      html: "<div class='dot' style='border-color:" + ring + "'></div><div class='name'>" + esc(p.name) + "</div>"
    });
  }
  function playerCard(p) {
    const title = esc(p.name) + (p.faction ? " " + A.facChip(p.faction, p.color, true) : "") + (p.self ? " <span class='badge accent'>you</span>" : "") + (p.hidden || p.hiddenReason ? " <span class='badge bad'>" + esc(A.hiddenText(p)) + "</span>" : "");
    const sub = p.health != null ? "Health " + num(p.health) + (p.maxHealth != null ? " of " + num(p.maxHealth) : "") : "Player";
    const extra = p.faction && A.layerOn("factions") ? "<div class='tc-hint'>Click to open the faction</div>" : "";
    return card(title, sub, { x: p.x, y: p.y, z: p.z }, extra);
  }
  const iconKey = (p) => [p.name, p.color, !!p.self, state.follow === p.uuid].join("|");
  // Players are reached by keyboard through the Players tab, so their markers are labelled images, not tab stops.
  const playerLabel = (p) => (p.name || "Player") + ", player" + (p.faction ? " of " + p.faction : "") + (p.self ? ", you" : "");

  const placed = (p) => p.x != null && p.z != null && !Number.isNaN(Number(p.x)) && !Number.isNaN(Number(p.z));
  // Why an admin sees a player as hidden. Anything other than the known reasons is a plugin's own word.
  A.hiddenText = (p) => {
    const r = p && p.hiddenReason != null ? String(p.hiddenReason) : "";
    const why = r === "list" ? "hide list" : r === "dominion" ? "factions flag" : A.pretty(r).toLowerCase();
    return "hidden" + (why ? ", " + why : "");
  };
  function ensureMarker(s, now) {
    const p = s.data;
    // Players below hideBelowY come without a position and are listed without a marker.
    if (p.world !== state.world || !placed(p) || !A.inLimit(p.x, p.z)) {
      if (s.marker) { groups.players.removeLayer(s.marker); s.marker = null; }
      return;
    }
    if (!s.marker) {
      const m = marker(toLatLng(p.x, p.z), { icon: playerIcon(p), keyboard: false, riseOnHover: true, zIndexOffset: p.self ? 1000 : 0, label: playerLabel(p) });
      tip(m, playerCard(p), { offset: [0, -10] });
      // Admins and the player themself get the card with View in 3D. Everyone else opens the faction as before.
      m.on("click", () => {
        const d = s.data;
        if ((A.isAdmin() || d.self) && placed(d) && A.openActions(m.getLatLng(), playerCard(d).replace("<div class='tc-hint'>Click to open the faction</div>", ""), [A.isoAct({ world: d.world, x: d.x, z: d.z, title: d.name }, "the area around " + d.name), A.factionAct(d.faction)], m)) return;
        if (d.faction && A.layerOn("factions")) A.emit("openFaction", d.faction); else m.openTooltip();
      });
      groups.players.addLayer(m);
      const el = m.getElement();
      if (el) el.classList.add("fade-in");
      s.marker = m;
      s.icon = iconKey(p);
      s.from = { x: p.x, z: p.z };
      s.to = { x: p.x, z: p.z };
      s.t0 = now;
    }
  }

  function upsert(p, now) {
    let s = state.players.get(p.uuid);
    if (!s) {
      s = { data: p, marker: null, from: { x: p.x, z: p.z }, to: { x: p.x, z: p.z }, t0: now, icon: "" };
      state.players.set(p.uuid, s);
      ensureMarker(s, now);
    } else {
      const moved = s.data.x !== p.x || s.data.z !== p.z;
      const worldChanged = s.data.world !== p.world || placed(s.data) !== placed(p) || (placed(p) && A.inLimit(s.data.x, s.data.z) !== A.inLimit(p.x, p.z));
      if (s.marker && !worldChanged) {
        if (moved) { s.from = currentPos(s, now); s.to = { x: p.x, z: p.z }; s.t0 = now; }
      } else {
        s.from = { x: p.x, z: p.z }; s.to = { x: p.x, z: p.z }; s.t0 = now;
      }
      s.data = p;
      if (worldChanged && s.marker) { groups.players.removeLayer(s.marker); s.marker = null; }
      ensureMarker(s, now);
      if (s.marker) {
        if (worldChanged) s.marker.setLatLng(toLatLng(p.x, p.z));
        const k = iconKey(p);
        if (k !== s.icon) { s.marker.options.label = playerLabel(p); s.marker.setIcon(playerIcon(p)); s.icon = k; }
      }
    }
    if (s.marker) s.marker.setTooltipContent(playerCard(p));
  }
  function drop(uuid) {
    const s = state.players.get(uuid);
    if (!s) return;
    if (s.marker) groups.players.removeLayer(s.marker);
    state.players.delete(uuid);
    if (state.follow === uuid) followAway(uuid, s.data.name);
  }
  // A followed player who leaves this world is followed into the next one when it is shown on this map.
  function followAway(uuid, name) {
    A.api("api/players").then((d) => {
      if (state.follow !== uuid || state.players.has(uuid)) return;
      state.others = (d.players || []).filter((p) => p && p.world !== state.world);
      const p = state.others.find((x) => x.uuid === uuid);
      if (!p) { A.followPlayer(null, name + " left the map"); return; }
      if (!A.worldShown(p.world)) { A.followPlayer(null, name + " went to " + A.worldLabel(p.world) + ", which is not shown on this map"); return; }
      if (!placed(p)) { A.followPlayer(null, name + (p.located === false ? " has not shared a position" : " is underground right now")); return; }
      A.emit("switchWorld", { name: p.world, view: { x: p.x, z: p.z, zoom: map.getZoom() } });
    }).catch(() => { if (state.follow === uuid) A.followPlayer(null, name + " left the map"); });
  }

  // Full payloads replace the set, partial payloads only carry players that moved plus a left list.
  A.onPlayers = (d) => {
    if (!d) return;
    const wasHidden = state.playersHidden;
    state.playersHidden = !!d.hidden;
    if (wasHidden !== state.playersHidden) A.applyOverlays();
    const now = performance.now();
    const full = d.full !== false;
    if (state.lastPlayersAt) state.interval = Math.min(3000, Math.max(150, now - state.lastPlayersAt));
    state.lastPlayersAt = now;
    const list = Array.isArray(d.players) ? d.players : [];
    if (full) {
      const seen = new Set();
      for (const p of list) if (p && p.uuid) { seen.add(p.uuid); upsert(p, now); }
      for (const id of Array.from(state.players.keys())) if (!seen.has(id)) drop(id);
    } else {
      for (const p of list) if (p && p.uuid) upsert(p, now);
      for (const id of d.left || []) drop(id);
    }
    if (Array.isArray(d.offline)) state.offline = d.offline;
    declutter();
    A.emit("players");
  };
  A.syncPlayerMarkers = () => {
    const now = performance.now();
    for (const s of state.players.values()) ensureMarker(s, now);
    declutter();
  };
  A.clearPlayers = () => {
    groups.players.clearLayers();
    state.players.clear();
  };

  function currentPos(s, now) {
    const k = Math.min(1, (now - s.t0) / state.interval);
    return { x: s.from.x + (s.to.x - s.from.x) * k, z: s.from.z + (s.to.z - s.from.z) * k };
  }
  function animate() {
    const now = performance.now();
    for (const s of state.players.values()) {
      if (!s.marker || now - s.t0 > state.interval + 60) continue;
      const p = currentPos(s, now);
      s.marker.setLatLng(toLatLng(p.x, p.z));
    }
    if (state.follow) {
      const s = state.players.get(state.follow);
      if (s && s.marker && !map._animatingZoom && !dragging) {
        const pt = map.latLngToContainerPoint(s.marker.getLatLng());
        const c = map.getSize().divideBy(2);
        const dx = pt.x - c.x, dy = pt.y - c.y;
        if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) map.panBy([dx, dy], { animate: false });
      }
    }
    requestAnimationFrame(animate);
  }
  requestAnimationFrame(animate);

  // Names are hidden on a crowded screen, except on hover and for the own and followed player. Below that
  // cutoff a name is still hidden when its box would overlap a name already placed in the same pass.
  const nameWidth = new Map();
  function labelWidth(s) {
    const n = s.data.name || "";
    if (nameWidth.has(n)) return nameWidth.get(n);
    const el = s.marker && s.marker.getElement() && s.marker.getElement().querySelector(".name");
    const w = el && el.offsetWidth ? el.offsetWidth : n.length * 7.5;
    if (el && el.offsetWidth) nameWidth.set(n, w);
    return w;
  }
  let clutterQueued = false;
  function declutter() {
    if (clutterQueued) return;
    clutterQueued = true;
    requestAnimationFrame(() => { clutterQueued = false; declutterNow(); });
  }
  function declutterNow() {
    if (!map.hasLayer(groups.players)) return;
    const b = map.getBounds();
    const shown = [];
    for (const s of state.players.values()) if (s.marker && b.contains(s.marker.getLatLng())) shown.push(s);
    const crowded = shown.length > 30;
    map.getContainer().classList.toggle("declutter", crowded);
    if (crowded) return;
    const prio = (s) => (s.data.self ? 0 : state.follow === s.data.uuid ? 1 : 2);
    shown.sort((a, c) => prio(a) - prio(c) || String(a.data.name).localeCompare(String(c.data.name)));
    const boxes = [];
    for (const s of shown) {
      const el = s.marker.getElement();
      if (!el) continue;
      const pt = map.latLngToContainerPoint(s.marker.getLatLng());
      const box = { x0: pt.x - 9, y0: pt.y - 11, x1: pt.x + 14 + labelWidth(s), y1: pt.y + 9 };
      const hit = prio(s) > 1 && boxes.some((o) => box.x0 < o.x1 && box.x1 > o.x0 && box.y0 < o.y1 && box.y1 > o.y0);
      el.classList.toggle("nolabel", hit);
      if (!hit) boxes.push(box);
    }
  }
  map.on("moveend zoomend", declutter);
  A.on("follow", declutter);

  A.findPlayer = (name) => {
    const q = String(name || "").toLowerCase();
    for (const s of state.players.values()) if (String(s.data.name || "").toLowerCase() === q) return s;
    return null;
  };
  // Players in other worlds come from api/players, since the event stream only carries the current world.
  const otherPlayer = (uuid) => (state.others || []).find((p) => p.uuid === uuid) || null;
  A.playerData = (uuid) => { const s = state.players.get(uuid); return s ? s.data : otherPlayer(uuid); };
  A.locatePlayer = (uuid, opts) => {
    const s = state.players.get(uuid);
    const p = s ? s.data : otherPlayer(uuid);
    if (!p) { A.toast("That player is not on the map right now"); return false; }
    const zoom = Math.max(map.getZoom(), geo.maxNative - 1);
    if (!placed(p)) { A.toast(p.name + (p.located === false ? " has not shared a position" : " is underground right now")); return false; }
    if (p.world !== state.world) { A.emit("switchWorld", { name: p.world, view: { x: p.x, z: p.z, zoom } }); return true; }
    const to = s && s.to ? s.to : { x: p.x, z: p.z };
    map.setView(toLatLng(to.x, to.z), zoom, { animate: !(opts && opts.instant) });
    if (s && s.marker && !(opts && opts.quiet)) setTimeout(() => s.marker && s.marker.openTooltip(), 350);
    return true;
  };
  A.followPlayer = (uuid, reason) => {
    const prev = state.follow;
    state.follow = uuid && uuid !== prev ? uuid : null;
    for (const id of [prev, state.follow]) {
      const s = id && state.players.get(id);
      if (s && s.marker) { s.marker.setIcon(playerIcon(s.data)); s.icon = iconKey(s.data); }
    }
    const chip = $("follow-chip");
    if (state.follow) {
      const pd = A.playerData(state.follow);
      A.locatePlayer(state.follow, { quiet: true });
      chip.innerHTML = A.icon("eye", "sm") + "<span>Following <b>" + esc(pd ? pd.name : "") + "</b></span>" +
        "<button class='icon-btn' id='follow-stop' aria-label='Stop following'>" + A.icon("close", "sm") + "</button>";
      chip.hidden = false;
      $("follow-stop").addEventListener("click", () => A.followPlayer(null));
    } else {
      chip.hidden = true;
      if (reason) A.toast(reason);
    }
    A.emit("follow");
  };
  let dragging = false;
  map.on("dragstart", () => { dragging = true; if (state.follow) A.followPlayer(null, "Stopped following"); });
  map.on("dragend", () => { dragging = false; });

  A.findMe = () => {
    let me = null;
    for (const s of state.players.values()) if (s.data.self) me = s.data;
    if (!me) me = (state.others || []).find((p) => p.self) || null;
    if (me) { A.locatePlayer(me.uuid); return; }
    const v = state.viewer;
    if (!v || !v.linked || v.viewingAs) A.toast("Link this browser to your player to use Find me");
    else A.toast("You are not on the map right now");
  };

  // ---- territories --------------------------------------------------------
  function traceRings(chunks) {
    const set = new Set(chunks.map((c) => c[0] + "," + c[1]));
    const edges = new Map();
    const add = (ax, az, bx, bz) => {
      const k = ax + "," + az;
      if (!edges.has(k)) edges.set(k, []);
      edges.get(k).push([bx, bz]);
    };
    for (const [cx, cz] of chunks) {
      const x = cx, z = cz;
      if (!set.has(cx + "," + (cz - 1))) add(x, z, x + 1, z);
      if (!set.has((cx + 1) + "," + cz)) add(x + 1, z, x + 1, z + 1);
      if (!set.has(cx + "," + (cz + 1))) add(x + 1, z + 1, x, z + 1);
      if (!set.has((cx - 1) + "," + cz)) add(x, z + 1, x, z);
    }
    const rings = [];
    for (const [start, list] of edges) {
      while (list.length) {
        const ring = [];
        let [sx, sz] = start.split(",").map(Number);
        let cur = start;
        let guard = 0;
        while (guard++ < 200000) {
          const outs = edges.get(cur);
          if (!outs || !outs.length) break;
          const nxt = outs.pop();
          ring.push([sx * CHUNK, sz * CHUNK]);
          sx = nxt[0]; sz = nxt[1];
          cur = sx + "," + sz;
          if (cur === start) break;
        }
        if (ring.length >= 3) { ring.push(ring[0]); rings.push(simplify(ring)); }
      }
    }
    return rings;
  }
  function simplify(ring) {
    const out = [];
    for (let i = 0; i < ring.length; i++) {
      const a = out[out.length - 2], b = out[out.length - 1], c = ring[i];
      if (a && b && ((a[0] === b[0] && b[0] === c[0]) || (a[1] === b[1] && b[1] === c[1]))) out[out.length - 1] = c;
      else out.push(c);
    }
    return out;
  }
  function territoryCard(g) {
    const title = A.facChip(g.tag, g.color, true) + " " + esc(g.name || "");
    const sub = A.plural(g.claims || g.chunks.length, "claim") + (g.power != null ? ", power " + num(g.power) + " of " + num(g.maxPower) : "");
    let b = "";
    if (g.type && g.type !== "FACTION" && g.type !== "faction") b += "<span class='badge'>" + esc(String(g.type).toLowerCase()) + "</span>";
    if (g.raidable) b += "<span class='badge bad'>raidable</span>";
    if (g.breached.length) b += "<span class='badge bad'>" + g.breached.length + " breached</span>";
    if (g.zones.length) b += "<span class='badge'>" + A.plural(g.zones.length, "zone") + "</span>";
    return card(title, sub, null, b ? "<div class='tc-badges'>" + b + "</div>" : "");
  }
  let terrSeq = 0;
  A.loadTerritories = () => {
    const seq = ++terrSeq;
    if (!A.layerOn("territories") || !state.world) { groups.territories.clearLayers(); groups.sieges.clearLayers(); state.territories = null; return Promise.resolve(); }
    return A.api("api/territories/" + A.W()).then((d) => {
      if (seq !== terrSeq) return;
      state.territories = d;
      groups.territories.clearLayers();
      const byTag = new Map();
      for (const t of d.territories || []) {
        const k = t.tag || t.id;
        if (!byTag.has(k)) byTag.set(k, { tag: t.tag, name: t.name, color: t.color, type: t.type, raidable: t.raidable, power: t.power, maxPower: t.maxPower, claims: t.claims, chunks: [], breached: [], zones: [] });
        const g = byTag.get(k);
        g.chunks.push(...(t.chunks || []));
        for (const z of t.zones || []) if (z && z.name && Array.isArray(z.chunks)) g.zones.push(z);
        g.breached.push(...(t.breached || []));
        g.raidable = g.raidable || t.raidable;
      }
      for (const g of byTag.values()) {
        const color = col(g.color);
        const layer = L.geoJSON({ type: "Feature", properties: { tag: g.tag }, geometry: { type: "Polygon", coordinates: traceRings(g.chunks) } }, {
          coordsToLatLng: (c) => toLatLng(c[0], c[1]),
          style: { color, weight: 2, opacity: 0.95, fillColor: color, fillOpacity: 0.32, fillRule: "evenodd", className: "territory" }
        });
        layer.bindTooltip(territoryCard(g), { sticky: true, className: "card" });
        layer.on("click", () => { if (g.tag && A.layerOn("factions")) A.emit("openFaction", g.tag); });
        groups.territories.addLayer(layer);
        for (const z of g.zones) {
          const zr = traceRings(z.chunks || []);
          if (!zr.length) continue;
          const zl = L.geoJSON({ type: "Feature", properties: {}, geometry: { type: "Polygon", coordinates: zr } }, {
            coordsToLatLng: (c) => toLatLng(c[0], c[1]),
            style: { color: "#f7f0e4", weight: 1.5, opacity: 0.9, dashArray: "6 5", fill: true, fillOpacity: 0, fillRule: "evenodd" }
          });
          zl.bindTooltip(card(esc(z.name), "A zone of " + esc(g.tag || g.name || "this faction") + " with " + A.plural((z.chunks || []).length, "chunk")), { sticky: true, className: "card" });
          zl.on("click", () => { if (g.tag && A.layerOn("factions")) A.emit("openFaction", g.tag); });
          groups.territories.addLayer(zl);
        }
        for (const b of g.breached) {
          groups.territories.addLayer(L.rectangle([toLatLng(b[0] * CHUNK, b[1] * CHUNK), toLatLng(b[0] * CHUNK + CHUNK, b[1] * CHUNK + CHUNK)],
            { color: "#ef5b4b", weight: 2, dashArray: "4 4", fillOpacity: 0.15, interactive: false }));
        }
      }
      drawSieges(d.sieges || []);
      A.emit("territories");
    }).catch(() => { /* the layer stays as it was */ });
  };
  function drawSieges(list) {
    groups.sieges.clearLayers();
    for (const s of list) {
      if (!Array.isArray(s.chunk) || s.chunk.length < 2) continue;
      const x = s.chunk[0] * CHUNK + CHUNK / 2, z = s.chunk[1] * CHUNK + CHUNK / 2;
      const m = marker(toLatLng(x, z), { icon: symIcon("swords", "#b0352c", { size: "big", count: true, endsAt: s.endsAt, pulse: true }), label: (s.attacker || "Someone") + " besieging " + (s.defender || "a faction") + ", siege" });
      tip(m, card(esc(s.attacker) + " <span class='tc-thin'>is besieging</span> " + esc(s.defender), "Siege, phase " + esc(String(s.phase || "").toLowerCase()), { x: x, z: z }), { offset: [0, -18] });
      m.on("click", () => { if (s.defender && A.layerOn("factions")) A.emit("openFaction", s.defender); });
      groups.sieges.addLayer(m);
    }
    tickCountdowns();
  }

  // ---- world border ---------------------------------------------------------
  // A circle from api/worlds in map units, which are blocks on this flat map. A wide faint halo under a thin
  // dashed line keeps it readable on any terrain. Only the line itself answers the pointer, never the inside.
  A.worldBorder = () => {
    const w = A.currentWorld();
    return circleOf(w && w.border);
  };
  // ---- render limit -------------------------------------------------------
  // A world may be drawn only inside a circle from atlas.json. Tiles outside it answer 404, the outside is
  // covered in the map background, the edge is a faint circle, and nothing Atlas draws is placed beyond it.
  const circleOf = (b) => {
    if (!b || typeof b !== "object" || String(b.shape || "circle").toLowerCase() !== "circle") return null;
    const x = Number(b.x), z = Number(b.z), r = Number(b.radius);
    return isFinite(x) && isFinite(z) && isFinite(r) && r > 0 ? { x, z, r } : null;
  };
  let limitNow = null, limitSig = "";
  const limitGroup = L.layerGroup().addTo(map);
  A.renderLimit = () => limitNow;
  A.inLimit = (x, z) => {
    if (!limitNow) return true;
    const dx = Number(x) - limitNow.x, dz = Number(z) - limitNow.z;
    return dx * dx + dz * dz <= limitNow.r * limitNow.r;
  };
  function drawLimit() {
    const w = A.currentWorld();
    const l = circleOf(w && w.renderLimit);
    const sig = l ? [state.world, l.x, l.z, l.r].join("|") : "";
    if (sig === limitSig) return false;
    limitSig = sig;
    limitNow = l;
    limitGroup.clearLayers();
    map.setMaxBounds(null);
    if (!l) return true;
    const ring = [];
    for (let i = 0; i < 160; i++) { const a = i / 160 * Math.PI * 2; ring.push(toLatLng(l.x + Math.cos(a) * l.r, l.z + Math.sin(a) * l.r)); }
    const far = 1e7;
    const outer = [toLatLng(-far, -far), toLatLng(far, -far), toLatLng(far, far), toLatLng(-far, far)];
    limitGroup.addLayer(L.polygon([outer, ring], { renderer: A.renderers.limitMask, stroke: false, fillOpacity: 1, interactive: false, className: "render-limit-mask" }));
    const edge = L.circle(toLatLng(l.x, l.z), { radius: l.r, weight: 1.5, opacity: 1, fill: false, dashArray: "3 6", className: "render-limit" });
    edge.bindTooltip(card("Edge of the map", "This world is drawn up to " + A.int(l.r) + " blocks from its centre"), { sticky: true, className: "card" });
    limitGroup.addLayer(edge);
    // The view may wander a little past the edge, never far into the empty outside.
    map.setMaxBounds(L.latLngBounds(toLatLng(l.x - l.r * 1.6, l.z - l.r * 1.6), toLatLng(l.x + l.r * 1.6, l.z + l.r * 1.6)));
    return true;
  }

  let borderSig = "";
  A.drawBorder = () => {
    if (drawLimit()) { drawMarkers(); drawPoints(); A.syncPlayerMarkers(); A.drawMarkerLayers(); }
    const b = A.worldBorder();
    const sig = b ? [state.world, b.x, b.z, b.r].join("|") : "";
    if (sig === borderSig) return;
    const wasShown = !!borderSig;
    borderSig = sig;
    groups.border.clearLayers();
    if (b) {
      const at = toLatLng(b.x, b.z);
      const r = String(Math.round(b.r));
      const centre = "<div class='tc-xyz'><span class='tc-thin'>Centre</span><span>X " + coord(b.x) + "</span><span>Z " + coord(b.z) + "</span></div>";
      const halo = L.circle(at, { radius: b.r, weight: 7, opacity: 1, fill: false, className: "world-border-halo" });
      halo.bindTooltip(card("World border", "Radius " + r + " blocks", null, centre), { sticky: true, className: "card" });
      groups.border.addLayer(halo);
      groups.border.addLayer(L.circle(at, { radius: b.r, weight: 2, opacity: 1, fill: false, dashArray: "10 7", interactive: false, className: "world-border" }));
    }
    if (wasShown !== !!b) A.applyOverlays();
  };

  // ---- points and zone names ----------------------------------------------
  function eventPoint(p, e) {
    const ends = p.expiresAt || 0;
    const m = marker(toLatLng(p.x, p.z), { icon: symIcon(e[0], col(p.color, e[1]), { size: "big", count: true, endsAt: ends }), label: (p.label || e[2]) + ", " + e[2].toLowerCase() });
    let sub = e[2];
    if (p.holder) sub += ", held by " + A.facChip(p.holder, null, true);
    else if (String(p.kind).toLowerCase() !== "envoy") sub += ", nobody holds it";
    let extra = "";
    if (p.control) extra += "<div class='tc-sub'>" + esc(p.control) + "</div>";
    if (p.status && p.status !== p.control) extra += "<div class='tc-sub'>" + esc(p.status) + "</div>";
    const html = card(esc(p.label || e[2]), sub, { x: p.x, z: p.z }, extra);
    tip(m, html, { offset: [0, -18] });
    m.on("click", () => {
      if (A.openActions(m.getLatLng(), html, [A.isoAct({ x: p.x, z: p.z, title: p.label || e[2] }, p.label || e[2]), A.factionAct(p.holder)], m)) return;
      if (p.holder && A.layerOn("factions")) A.emit("openFaction", p.holder);
    });
    return m;
  }
  let pointsSeq = 0;
  A.loadPoints = () => {
    const seq = ++pointsSeq;
    if (!state.world || (!A.layerOn("points") && !A.layerOn("zones") && !A.layerOn("events"))) {
      groups.points.clearLayers(); groups.zones.clearLayers(); state.points = null; return Promise.resolve();
    }
    return A.api("api/points/" + A.W()).then((d) => {
      if (seq !== pointsSeq) return;
      state.points = d;
      drawPoints();
      A.emit("points");
    }).catch(() => { /* keep what is drawn */ });
  };
  function drawPoints() {
    const d = state.points || {};
    groups.points.clearLayers();
    groups.zones.clearLayers();
    for (const p of d.points || []) {
      const kind = String(p.kind || "").toLowerCase();
      if (kindHidden("point:" + kind) || !A.inLimit(p.x, p.z)) continue;
      const ek = EVENT_KIND[kind];
      if (ek) { groups.points.addLayer(eventPoint(p, ek)); continue; }
      const k = POINT_KIND[kind] || ["pin", "#6d7680", A.pretty(p.kind || "Point")];
      const m = marker(toLatLng(p.x, p.z), { icon: symIcon(k[0], col(p.color, k[1]), { count: !!p.expiresAt, endsAt: p.expiresAt }), label: (p.label || k[2]) + ", " + String(k[2]).toLowerCase() + (p.faction ? " of " + p.faction : "") });
      const title = esc(p.label || k[2]) + (p.faction ? " " + A.facChip(p.faction, p.color, true) : "");
      const exp = p.expiresAt ? "<div class='tc-sub'>Expires in <span data-ends='" + Number(p.expiresAt) + "'>" + A.fmtLeft(p.expiresAt - Date.now()) + "</span></div>" : "";
      const html = card(title, esc(k[2]), { x: p.x, z: p.z }, exp);
      tip(m, html);
      // Waypoints, homes, rallies and the other faction points open their card with View in 3D.
      m.on("click", () => {
        if (A.openActions(m.getLatLng(), html, [A.isoAct({ x: p.x, z: p.z, title: p.label || k[2] }, p.label || k[2]), A.factionAct(p.faction)], m)) return;
        if (p.faction && A.layerOn("factions")) A.emit("openFaction", p.faction);
      });
      groups.points.addLayer(m);
    }
    for (const z of d.zones || []) {
      const m = L.marker(toLatLng(z.x, z.z), { icon: L.divIcon({ className: "", iconSize: [0, 0] }), interactive: false, keyboard: false });
      m.bindTooltip(esc(z.name), { permanent: true, direction: "center", className: "zone-label" });
      groups.zones.addLayer(m);
    }
    tickCountdowns();
  }

  // ---- engine markers and spawn -------------------------------------------
  // Kinds from api/markers. shared markers are placed by players on the shared in game map. personal, home and
  // death belong to the viewer alone and only ever arrive in that viewer's own payload.
  const MARKER_KIND = A.MARKER_KIND = {
    spawn: { label: "Spawn", sub: "World spawn", color: "#c7951c", img: "Spawn" },
    poi: { label: "Places", sub: "Place", color: "#5c7a8c" },
    engine: { label: "Engine markers", sub: "Map marker", color: "#5f6f86" },
    shared: { label: "Player markers", sub: "Shared marker", color: "#4f6d8a" },
    personal: { label: "My markers", sub: "My marker", color: "#a8552e" },
    home: { label: "Homes", sub: "Home", color: "#3f7fc9", img: "Home", title: "Respawn point" },
    death: { label: "Deaths", sub: "Death", color: "#7d3a33", img: "Death", title: "You died here" }
  };
  const OWN_KINDS = A.OWN_KINDS = new Set(["personal", "home", "death"]);
  A.markerKind = (mk) => { const k = String((mk && mk.kind) || "engine").toLowerCase(); return MARKER_KIND[k] ? k : "engine"; };
  A.markerKey = (mk) => String(mk.id != null ? mk.id : A.markerKind(mk) + ":" + mk.x + ":" + mk.z);
  // Everything needed to draw one marker, shared by the map and the Markers tab.
  A.markerLook = (mk) => {
    const kind = A.markerKind(mk);
    const def = MARKER_KIND[kind];
    const own = OWN_KINDS.has(kind);
    const user = own || kind === "shared";
    let color = kind === "engine" && mk.major ? "#7a4fb0" : def.color;
    // A very light tint, white included, would put a white glyph on a white badge. The badge then takes the
    // dark ink and the tint moves to the ring, so the glyph reads on both themes.
    let ring = null;
    if (user) {
      color = col(mk.tint, color);
      if (A.luminance(color) > 0.8) { ring = color; color = "#1d1812"; }
    }
    const alert = mk.alert && typeof mk.alert === "object" ? mk.alert : null;
    if (alert && alert.tint) color = col(alert.tint, color);
    const sym = kind === "spawn" ? "spawn" : kind === "home" ? "home" : kind === "death" ? "skull" : A.engineIcon(mk.icon || (user ? "" : mk.name));
    const by = mk.by && typeof mk.by === "object" && mk.by.name ? String(mk.by.name) : "";
    const sub = esc(def.sub) + (kind === "shared" && by ? " by " + esc(by) : "");
    const title = mk.name || def.title || (user ? "Marker" : A.pretty(mk.icon) || "Marker");
    return {
      kind, own, user, color, ring, alert, sym, by, sub, title,
      img: A.gameIcon(mk.icon || def.img),
      size: kind === "spawn" || (kind === "engine" && mk.major) ? "big" : kind === "poi" ? "small" : "",
      z: kind === "spawn" ? 500 : own ? 450 : kind === "shared" ? 250 : 0,
      group: user ? groups.usermarks : kind === "engine" ? groups.engine : groups.markers
    };
  };
  // A subtitle that only repeats the title, such as World spawn under World spawn, is left out.
  const markerCard = (mk, lk) => card(esc(lk.title), lk.sub.toLowerCase() === esc(lk.title).toLowerCase() ? "" : lk.sub, { x: mk.x, y: mk.y, z: mk.z });

  let markersSeq = 0;
  A.loadMarkers = () => {
    const seq = ++markersSeq;
    if (!state.world || !A.layerOn("markers")) {
      groups.markers.clearLayers(); groups.engine.clearLayers(); groups.usermarks.clearLayers(); markerRefs.clear(); state.markers = []; A.emit("markers"); return Promise.resolve();
    }
    return A.api("api/markers/" + A.W()).then((d) => {
      if (seq !== markersSeq) return;
      state.markers = d && Array.isArray(d.markers) ? d.markers.filter((mk) => mk && mk.x != null && mk.z != null) : [];
      drawMarkers();
      A.emit("markers");
    }).catch(() => { /* keep what is drawn */ });
  };
  const markerRefs = new Map();
  // A world with more than this many markers only keeps the ones near the view on the map, refreshed after each move.
  const CULL_OVER = 300;
  function cullMarkers() {
    if (markerRefs.size <= CULL_OVER) {
      for (const m of markerRefs.values()) if (!m._grp.hasLayer(m)) m._grp.addLayer(m);
      return;
    }
    const b = map.getBounds().pad(0.5);
    for (const m of markerRefs.values()) {
      const inside = b.contains(m.getLatLng());
      const on = m._grp.hasLayer(m);
      if (inside && !on) m._grp.addLayer(m);
      else if (!inside && on && !(m.isTooltipOpen && m.isTooltipOpen())) m._grp.removeLayer(m);
    }
  }
  map.on("moveend", () => { if (markerRefs.size > CULL_OVER) cullMarkers(); });
  function drawMarkers() {
    // A redraw keeps the tooltip that was open, so a live refresh does not snatch it from under the pointer.
    let openKey = null;
    for (const [k, m] of markerRefs) if (m.isTooltipOpen && m.isTooltipOpen()) openKey = k;
    groups.markers.clearLayers();
    groups.engine.clearLayers();
    groups.usermarks.clearLayers();
    markerRefs.clear();
    for (const mk of state.markers) {
      const lk = A.markerLook(mk);
      if (kindHidden("marker:" + lk.kind) || !A.inLimit(mk.x, mk.z)) continue;
      const pulseColor = lk.alert ? alpha(col(lk.alert.tint, "#dc4632"), "99") : null;
      const label = lk.title + ", " + (lk.kind === "shared" && lk.by ? "shared marker by " + lk.by : MARKER_KIND[lk.kind].sub.toLowerCase());
      const m = marker(toLatLng(mk.x, mk.z), { icon: symIcon(lk.sym, lk.color, { size: lk.size, img: lk.img, own: lk.own, ring: lk.ring, pulse: !!lk.alert, pulseColor }), zIndexOffset: lk.z, title: "", label });
      tip(m, markerCard(mk, lk), { offset: [0, lk.size === "big" ? -18 : -14] });
      // The server looks the marker up by its id, the viewer's own markers included.
      m.on("click", () => A.openActions(m.getLatLng(), markerCard(mk, lk), [A.isoAct({ marker: mk.id, x: mk.x, z: mk.z, title: lk.title }, lk.title)], m));
      m._grp = lk.group;
      markerRefs.set(A.markerKey(mk), m);
    }
    cullMarkers();
    if (openKey && markerRefs.has(openKey) && map.hasLayer(markerRefs.get(openKey))) markerRefs.get(openKey).openTooltip();
  }
  // Pans to a marker and opens its card. A marker hidden by a filter still gets its card, as a loose tooltip.
  let looseTip = null, shownTip = null;
  A.showMarker = (key) => {
    const mk = state.markers.find((x) => A.markerKey(x) === key);
    if (!mk) { A.toast("That marker is no longer on the map"); return; }
    if (looseTip) { looseTip.remove(); looseTip = null; }
    if (shownTip) { shownTip.closeTooltip(); shownTip = null; }
    // A redraw may have reopened the last card on a fresh marker, so every open marker card is closed.
    for (const m of markerRefs.values()) if (m.isTooltipOpen()) m.closeTooltip();
    map.setView(toLatLng(mk.x, mk.z), Math.max(map.getZoom(), geo.maxNative - 1));
    setTimeout(() => {
      const m = markerRefs.get(key);
      if (m && map.hasLayer(m)) { m.openTooltip(); shownTip = m; return; }
      looseTip = L.tooltip({ className: "card", direction: "top", offset: [0, -4] }).setLatLng(toLatLng(mk.x, mk.z))
        .setContent(markerCard(mk, A.markerLook(mk)) + "<div class='tc-hint'>Hidden on the map by your filters</div>").addTo(map);
      const t = looseTip;
      setTimeout(() => map.once("click movestart", () => { t.remove(); if (looseTip === t) looseTip = null; }), 0);
    }, 380);
  };

  // ---- marker layers --------------------------------------------------------
  // Layers from other plugins, the markers folder and /atlas marker, read from api/layers. Each layer is one
  // group with its own switch in the layers popover, remembered per layer id. Higher priority draws on top.
  const mlayers = A.mlayers = new Map();
  const layerVis = A.store.get("layerVis", {});
  A.mlayerWanted = (def) => layerVis[def.id] != null ? !!layerVis[def.id] : !def.defaultHidden;
  A.mlayerZoomOk = (def) => {
    const z = A.atlasZoom();
    return (def.minZoom == null || z >= Number(def.minZoom)) && (def.maxZoom == null || z <= Number(def.maxZoom));
  };
  A.markerLayers = () => Array.from(mlayers.values()).map((r) => r.def).sort((a, b) => (Number(b.priority) || 0) - (Number(a.priority) || 0) || String(a.id).localeCompare(String(b.id)));
  const fin = (n) => n != null && n !== "" && isFinite(Number(n));
  // An inline icon is only ever a small base64 image. It is used as the src of an img element and never put
  // into the page as markup, so an SVG inside it cannot run anything.
  const DATA_IMG = /^data:image\/(?:png|gif|jpeg|webp|svg\+xml);base64,[A-Za-z0-9+/]+=*$/;
  const inlineIcon = (s) => typeof s === "string" && s.length <= 12000 && DATA_IMG.test(s) ? s : null;
  // A fill colour with its own alpha is used as written, a plain one is laid on lightly.
  const fillOf = (m, tint) => {
    const f = /^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?([0-9a-fA-F]{2})?$/.test(String(m.fill || "")) ? String(m.fill) : "";
    return f ? { c: f, o: f.length === 9 ? 1 : 0.3 } : { c: tint, o: 0.18 };
  };
  // Where a layer marker sits, for the Markers tab and for Show. A circle by its centre, a shape by its first point.
  A.layerMarkerAt = (m) => {
    if (m.kind === "point" || m.kind === "circle") return fin(m.x) && fin(m.z) ? { x: Number(m.x), z: Number(m.z) } : null;
    const p = Array.isArray(m.points) ? m.points.find((q) => Array.isArray(q) && fin(q[0]) && fin(q[1])) : null;
    return p ? { x: Number(p[0]), z: Number(p[1]) } : null;
  };
  const MKIND = { point: "Place", polyline: "Line", polygon: "Area", circle: "Circle" };
  function layerItem(def, m) {
    const tint = col(m.tint, "#4a8ea4");
    const name = m.label ? String(m.label) : MKIND[m.kind] || "Marker";
    const sub = esc(def.label || def.id) + (m.kind !== "point" ? ", " + esc((MKIND[m.kind] || "shape").toLowerCase()) : "");
    const hint = m.popup ? "<div class='tc-hint'>Click for details</div>" : "";
    const weight = fin(m.weight) ? Math.max(0.5, Math.min(12, Number(m.weight))) : null;
    let it = null;
    if (m.kind === "point") {
      if (!fin(m.x) || !fin(m.z) || !A.inLimit(m.x, m.z)) return null;
      const img = inlineIcon(m.iconData) || (m.icon ? A.gameIcon(m.icon) : null);
      it = marker(toLatLng(Number(m.x), Number(m.z)), {
        icon: symIcon(m.icon ? A.engineIcon(m.icon) : "pin", tint, { img }), zIndexOffset: 100 + (Number(def.priority) || 0), title: "",
        label: name + ", " + (def.label || "map layer")
      });
      tip(it, card(esc(name), sub, { x: m.x, y: m.y, z: m.z }, hint));
    } else if (m.kind === "circle") {
      if (!fin(m.x) || !fin(m.z) || !fin(m.radius) || Number(m.radius) <= 0) return null;
      const f = fillOf(m, tint);
      it = L.circle(toLatLng(Number(m.x), Number(m.z)), { radius: Number(m.radius), color: tint, weight: weight || 2, fillColor: f.c, fillOpacity: f.o });
      it.bindTooltip(card(esc(name), sub + ", radius " + A.int(m.radius) + " blocks", { x: m.x, z: m.z }, hint), { sticky: true, className: "card" });
    } else if (m.kind === "polyline" || m.kind === "polygon") {
      const pts = (Array.isArray(m.points) ? m.points : []).filter((q) => Array.isArray(q) && fin(q[0]) && fin(q[1])).map((q) => toLatLng(Number(q[0]), Number(q[1])));
      if (pts.length < (m.kind === "polygon" ? 3 : 2)) return null;
      if (m.kind === "polyline") it = L.polyline(pts, { color: tint, weight: weight || 3, opacity: 0.95, lineJoin: "round", lineCap: "round" });
      else { const f = fillOf(m, tint); it = L.polygon(pts, { color: tint, weight: weight || 2, fillColor: f.c, fillOpacity: f.o }); }
      it.bindTooltip(card(esc(name), sub, null, hint), { sticky: true, className: "card" });
    }
    // The popup is html the server prepared, with text from untrusted sources already escaped. View in 3D goes under
    // it. A point uses the marker route, a shape leaves the radius to the server so the view covers it.
    if (!it) return null;
    const iso = () => A.isoAct({ marker: m.id, layer: def.id, autoR: m.kind !== "point", x: m.kind === "point" || m.kind === "circle" ? m.x : null, z: m.kind === "point" || m.kind === "circle" ? m.z : null, title: name }, name);
    if (m.popup) {
      it.bindPopup(() => A.withActions(String(m.popup), [iso()]), { className: "atlas-popup", maxWidth: 300, autoPanPadding: [24, 24] });
      it.on("popupopen", () => it.closeTooltip());
    } else {
      it.on("click", (e) => {
        const at = m.kind === "point" ? it.getLatLng() : e.latlng;
        const html = card(esc(name), sub, m.kind === "point" ? { x: m.x, y: m.y, z: m.z } : m.kind === "circle" ? { x: m.x, z: m.z } : null);
        A.openActions(at, html, [iso()], it);
      });
    }
    return it;
  }
  function buildLayer(def) {
    const rec = { def, group: L.layerGroup(), points: [], items: new Map() };
    for (const m of Array.isArray(def.markers) ? def.markers : []) {
      if (!m || !m.kind) continue;
      const it = layerItem(def, m);
      if (!it) continue;
      rec.items.set(String(m.id), it);
      if (m.kind === "point") rec.points.push(it); else rec.group.addLayer(it);
    }
    return rec;
  }
  // A layer with more points than this keeps only the points near the view, like the engine markers.
  function cullLayer(rec) {
    const many = rec.points.length > CULL_OVER;
    const b = many ? map.getBounds().pad(0.5) : null;
    for (const m of rec.points) {
      const inside = !many || b.contains(m.getLatLng());
      const on = rec.group.hasLayer(m);
      if (inside && !on) rec.group.addLayer(m);
      else if (!inside && on && !(m.isTooltipOpen && m.isTooltipOpen()) && !(m.isPopupOpen && m.isPopupOpen())) rec.group.removeLayer(m);
    }
  }
  // Groups are added lowest priority first, so a higher layer's shapes are drawn over a lower one's.
  A.applyMarkerLayers = () => {
    const ok = A.layerOn("layers");
    const defs = A.markerLayers().reverse();
    for (const def of defs) { const rec = mlayers.get(def.id); if (map.hasLayer(rec.group)) map.removeLayer(rec.group); }
    for (const def of defs) {
      const rec = mlayers.get(def.id);
      if (ok && A.mlayerWanted(def) && A.mlayerZoomOk(def)) { cullLayer(rec); rec.group.addTo(map); }
    }
  };
  A.drawMarkerLayers = () => {
    for (const [id, rec] of Array.from(mlayers)) mlayers.set(id, buildLayer(rec.def));
    A.applyMarkerLayers();
  };
  A.setMarkerLayer = (id, on) => {
    layerVis[id] = !!on;
    A.store.set("layerVis", layerVis);
    A.applyMarkerLayers();
  };
  map.on("zoomend", () => { if (mlayers.size) { A.applyMarkerLayers(); if (!$("layers-pop").hidden) renderLayersPop(); } });
  map.on("moveend", () => { for (const rec of mlayers.values()) if (rec.points.length > CULL_OVER && map.hasLayer(rec.group)) cullLayer(rec); });
  A.on("markerIcons", () => { if (mlayers.size) A.drawMarkerLayers(); });
  function clearMarkerLayers() {
    for (const rec of mlayers.values()) map.removeLayer(rec.group);
    mlayers.clear();
  }
  // A layers event names the layers that changed, and only those are replaced. Names that arrive while a request
  // is running are kept for the next answer, so no change is lost. A full load replaces every layer.
  let layersSeq = 0;
  let layersWant = new Set();
  let layersAll = false;
  A.loadLayers = (only) => {
    const world = state.world;
    if (!world || !A.layerOn("layers")) { clearMarkerLayers(); A.emit("mlayers"); return Promise.resolve(); }
    if (Array.isArray(only)) for (const id of only) layersWant.add(String(id)); else layersAll = true;
    const seq = ++layersSeq;
    return A.api("api/layers/" + A.W()).then((d) => {
      if (seq !== layersSeq || world !== state.world) return;
      const got = new Map((d && Array.isArray(d.layers) ? d.layers : []).filter((l) => l && l.id != null).map((l) => [String(l.id), Object.assign({}, l, { id: String(l.id) })]));
      const ids = layersAll ? new Set([...mlayers.keys(), ...got.keys()]) : layersWant;
      layersWant = new Set();
      layersAll = false;
      for (const id of ids) {
        const old = mlayers.get(id);
        if (old) { map.removeLayer(old.group); mlayers.delete(id); }
        if (got.has(id)) mlayers.set(id, buildLayer(got.get(id)));
      }
      A.applyMarkerLayers();
      A.emit("mlayers");
      if (!$("layers-pop").hidden) renderLayersPop();
    }).catch((e) => {
      if (seq !== layersSeq) return;
      // 403 means no layer is visible to this viewer and 404 that the world is gone. Both leave nothing drawn.
      if (e.status === 403 || e.status === 404) { clearMarkerLayers(); layersWant = new Set(); layersAll = false; A.emit("mlayers"); }
    });
  };
  // Pans to a layer marker and opens its popup, or its card when it has none. A switched off layer still shows the card.
  A.showLayerMarker = (layerId, markerId) => {
    const rec = mlayers.get(layerId);
    const m = rec && (rec.def.markers || []).find((x) => String(x.id) === String(markerId));
    const at = m && A.layerMarkerAt(m);
    if (!at) { A.toast("That marker is no longer on the map"); return; }
    map.setView(toLatLng(at.x, at.z), Math.max(map.getZoom(), geo.maxNative - 1));
    setTimeout(() => {
      const it = rec.items.get(String(m.id));
      if (it && map.hasLayer(it)) { if (m.popup) it.openPopup(); else it.openTooltip(toLatLng(at.x, at.z)); return; }
      const t = L.tooltip({ className: "card", direction: "top", offset: [0, -4] }).setLatLng(toLatLng(at.x, at.z))
        .setContent(card(esc(m.label || MKIND[m.kind] || "Marker"), esc(rec.def.label || rec.def.id), at) + "<div class='tc-hint'>This layer is switched off or not shown at this zoom</div>").addTo(map);
      setTimeout(() => map.once("click movestart", () => t.remove()), 0);
    }, 380);
  };

  // ---- world events -------------------------------------------------------
  A.loadWorldEvents = () => {
    if (!A.layerOn("worldevents")) { state.worldEvents = []; drawWorldEvents(); return Promise.resolve(); }
    return A.api("api/worldevents").then((d) => {
      state.worldEvents = d && Array.isArray(d.events) ? d.events : [];
      drawWorldEvents();
      A.emit("worldevents");
    }).catch(() => { /* keep what is drawn */ });
  };
  A.onWorldEvent = (e) => {
    if (!e || !e.id) return;
    const list = state.worldEvents.filter((x) => x.id !== e.id);
    if (e.state !== "end") list.push({ id: e.id, world: e.world, name: e.name, displayName: e.displayName, startedAt: e.at, x: e.x, z: e.z, located: e.located });
    state.worldEvents = list;
    drawWorldEvents();
    if (A.layerOn("markers")) setTimeout(A.loadMarkers, 1500);
  };
  const located = (e) => e && e.located !== false && e.x != null && e.z != null;
  function drawWorldEvents() {
    groups.worldevents.clearLayers();
    for (const e of state.worldEvents) {
      if (e.world !== state.world || !located(e) || !A.inLimit(e.x, e.z)) continue;
      const m = marker(toLatLng(e.x, e.z), { icon: symIcon(A.engineIcon(e.name) === "pin" ? "bolt" : A.engineIcon(e.name), "#b4532a", { size: "big", pulse: true, pulseColor: "rgba(233,150,70,.6)" }), zIndexOffset: 800, label: (e.displayName || A.pretty(e.name)) + ", world event" });
      tip(m, card(esc(e.displayName || A.pretty(e.name)), "World event" + (e.startedAt ? ", open since " + esc(A.clock(e.startedAt)) : ""), { x: e.x, z: e.z }), { offset: [0, -18] });
      groups.worldevents.addLayer(m);
    }
    A.renderBanners();
  }

  // ---- banner for active events ---------------------------------------------
  // Active events share one compact pill. Hiding it lasts for this browser session, and a new event shows it again.
  let pillOpen = false;
  const dismissed = new Set((() => { try { return JSON.parse(sessionStorage.getItem("atlas.eventsHidden") || "[]"); } catch (e) { return []; } })());
  A.renderBanners = () => {
    const box = $("event-banners");
    let h = "";
    const evs = (state.worldEvents || []).filter((e) => e.world === state.world && located(e)).sort((a, b) => (a.startedAt || 0) - (b.startedAt || 0));
    const fresh = evs.filter((e) => !dismissed.has(e.id));
    if (fresh.length) {
      const line = (e) => esc(e.displayName || A.pretty(e.name)) + " open since " + esc(A.clock(e.startedAt));
      const first = evs[0];
      h += "<div class='banner breach pill" + (pillOpen ? " open" : "") + "'><div class='pill-row'>" +
        "<button class='pill-main' aria-expanded='" + pillOpen + "' aria-controls='pill-list'>" + A.icon("bolt") + "<span class='badge warn'>" + evs.length + "</span><span class='grow'>" +
        line(first) + (evs.length > 1 ? " and " + (evs.length - 1) + " more" : "") + "</span>" + A.icon("chevron", "sm pill-chev") + "</button>" +
        "<button class='icon-btn' data-dismiss aria-label='Hide world events for this session'>" + A.icon("close", "sm") + "</button></div>" +
        "<ul class='pill-list' id='pill-list'" + (pillOpen ? "" : " hidden") + ">" + evs.map((e) => "<li><span class='grow'>" + line(e) + "</span><button class='btn small' data-goto='" + e.x + "," + e.z + "'>Show</button></li>").join("") + "</ul></div>";
    }
    box.innerHTML = h;
    const main = box.querySelector(".pill-main");
    if (main) main.addEventListener("click", () => { pillOpen = !pillOpen; A.renderBanners(); const m = box.querySelector(".pill-main"); if (m) m.focus(); });
    const x = box.querySelector("[data-dismiss]");
    if (x) x.addEventListener("click", () => {
      for (const e of evs) dismissed.add(e.id);
      try { sessionStorage.setItem("atlas.eventsHidden", JSON.stringify(Array.from(dismissed))); } catch (err) { /* kept in memory */ }
      A.renderBanners();
    });
    box.querySelectorAll("[data-goto]").forEach((b) => b.addEventListener("click", () => {
      const [x, z] = b.dataset.goto.split(",").map(Number);
      map.setView(toLatLng(x, z), Math.max(map.getZoom(), geo.maxNative - 1));
    }));
  };

  // ---- heat and paths -----------------------------------------------------
  function heatColor(t) { return "hsl(" + ((1 - t) * 55) + ", 92%, " + (56 - t * 12) + "%)"; }
  A.heatWindow = A.store.get("heatWindow", 86400000);
  A.loadHeat = () => {
    if (!map.hasLayer(groups.heat) || !state.world) return;
    A.api("api/heat/" + A.W() + "?since=" + (Date.now() - A.heatWindow)).then((d) => {
      groups.heat.clearLayers();
      const rows = Array.isArray(d.chunks) ? d.chunks : [];
      let max = 1;
      for (const r of rows) max = Math.max(max, r[2] + r[3]);
      for (const r of rows) {
        const total = r[2] + r[3];
        const t = Math.log(1 + total) / Math.log(1 + max);
        const rect = L.rectangle([toLatLng(r[0] * CHUNK, r[1] * CHUNK), toLatLng(r[0] * CHUNK + CHUNK, r[1] * CHUNK + CHUNK)],
          { stroke: false, fillColor: heatColor(t), fillOpacity: 0.22 + t * 0.5 });
        rect.bindTooltip(card(num(r[2]) + " broken and " + num(r[3]) + " placed", "Chunk " + r[0] + " " + r[1]), { className: "card", sticky: true });
        groups.heat.addLayer(rect);
      }
    }).catch((e) => { groups.heat.clearLayers(); if (e.status !== 403 && e.status !== 404 && e.status !== 429) A.toast("Activity heat could not be loaded", "bad"); });
  };
  setInterval(() => A.loadHeat(), 60000);
  A.loadPaths = () => {
    if (!map.hasLayer(groups.paths) || !state.world) return;
    A.api("api/players?trails=1&world=" + A.W()).then((d) => {
      groups.paths.clearLayers();
      for (const p of d.players || []) {
        if (!p.trail || p.trail.length < 2) continue;
        groups.paths.addLayer(L.polyline(p.trail.map((t) => toLatLng(t[0], t[1])), { color: col(p.color, "#f2c14e"), weight: 2.5, opacity: 0.85, lineJoin: "round" }));
      }
    }).catch(() => { /* paths stay as they were */ });
  };
  setInterval(() => A.loadPaths(), 10000);

  // ---- tiles --------------------------------------------------------------
  // Versions are kept for the tiles on screen plus the most recent TILE_KEEP others, oldest dropped first.
  // The map keeps insertion order, so a version that changes is moved to the end.
  const TILE_KEEP = 2000;
  A.onTiles = (d) => {
    if (!d || d.world !== state.world || !Array.isArray(d.tiles)) return;
    const tv = state.tileVersions;
    for (const t of d.tiles) { const k = t[0] + "/" + t[1] + "/" + t[2]; tv.delete(k); tv.set(k, d.at); }
    const shown = new Set();
    for (const key in terrain._tiles) {
      const tile = terrain._tiles[key];
      const c = tile.coords;
      const k = geo.lToZ(c.z) + "/" + c.x + "/" + c.y;
      shown.add(k);
      if (tv.get(k) === d.at) tile.el.src = terrain.getTileUrl(c);
    }
    let extra = tv.size - TILE_KEEP - shown.size;
    if (extra > 0) for (const k of tv.keys()) { if (extra <= 0) break; if (!shown.has(k)) { tv.delete(k); extra--; } }
  };
  A.resetTiles = () => { state.tileVersions.clear(); terrain.redraw(); };

  // ---- view and URL -------------------------------------------------------
  A.viewTo = (x, z, zoom, animate) => map.setView(toLatLng(x, z), zoom == null ? map.getZoom() : zoom, { animate: animate !== false });
  // A link made while a past day is shown opens that day, with t set to its date.
  A.urlFor = (x, z, zoom) => {
    const q = new URLSearchParams({ world: state.world || "", x: String(Math.round(x)), z: String(Math.round(z)), zoom: String(zoom) });
    if (state.tlDate) q.set("t", state.tlDate);
    return location.origin + location.pathname + "?" + q.toString();
  };
  // While a 3D view is open the address carries it as iso=world,x,z,r,az,s, so a copied address opens it again.
  const isoTail = () => { const p = A.isoParam ? A.isoParam() : ""; return p ? "&iso=" + p : ""; };
  A.saveUrl = () => {
    if (!state.world) return;
    const c = fromLatLng(map.getCenter());
    const q = new URLSearchParams({ world: state.world, x: String(c.x), z: String(c.z), zoom: String(map.getZoom()) });
    if (state.tlDate) q.set("t", state.tlDate);
    try { history.replaceState(null, "", "?" + q.toString() + isoTail() + location.hash); } catch (e) { /* some embedded views block it */ }
  };
  map.on("moveend", A.throttle(() => A.saveUrl(), 400));
  A.shareView = () => { A.saveUrl(); A.copy(location.href, "Link to this view"); };

  // ---- coordinates --------------------------------------------------------
  A.touch = touch;
  if (touch) document.body.classList.add("touch");
  const fmtCoords = (p) => "X " + p.x + "  Z " + p.z + "  Chunk " + Math.floor(p.x / CHUNK) + " " + Math.floor(p.z / CHUNK);
  function showCoords(ll) { $("coords").textContent = fmtCoords(fromLatLng(ll)); }
  let pointerIn = false;
  if (touch) map.on("move", () => showCoords(map.getCenter()));
  else {
    map.on("mousemove", (e) => { pointerIn = true; showCoords(e.latlng); });
    map.on("mouseout", () => { pointerIn = false; showCoords(map.getCenter()); });
  }
  map.on("moveend", () => { if (!pointerIn) showCoords(map.getCenter()); });

  // ---- context menu -------------------------------------------------------
  const ctx = $("ctxmenu");
  function closeCtx() { ctx.hidden = true; }
  A.closeCtx = closeCtx;
  map.on("contextmenu", (e) => {
    if (measuring) return;
    const p = fromLatLng(e.latlng);
    const cx = Math.floor(p.x / CHUNK), cz = Math.floor(p.z / CHUNK);
    ctx.innerHTML = "<div class='head'><b>X " + p.x + "  Z " + p.z + "</b><br><span class='muted'>Chunk " + cx + " " + cz + "</span></div>" +
      "<div class='menu-sep'></div>" +
      "<button class='menu-item' data-act='copy'>" + A.icon("copy", "sm") + "<span class='grow'>Copy coordinates</span></button>" +
      "<button class='menu-item' data-act='link'>" + A.icon("link", "sm") + "<span class='grow'>Copy a link to this spot</span></button>" +
      "<button class='menu-item' data-act='measure'>" + A.icon("ruler", "sm") + "<span class='grow'>Measure from here</span></button>" +
      (A.isoOk && A.isoOk() ? "<button class='menu-item' data-act='iso'>" + A.icon("cube", "sm") + "<span class='grow'>View this area in 3D</span></button>" : "");
    ctx.hidden = false;
    const size = map.getSize();
    const w = ctx.offsetWidth, h = ctx.offsetHeight;
    ctx.style.left = Math.max(8, Math.min(e.containerPoint.x, size.x - w - 8)) + "px";
    ctx.style.top = Math.max(8, Math.min(e.containerPoint.y, size.y - h - 8)) + "px";
    ctx.querySelector("[data-act='copy']").addEventListener("click", () => { closeCtx(); A.copy(p.x + " " + p.z, "Coordinates X " + p.x + " Z " + p.z); });
    ctx.querySelector("[data-act='link']").addEventListener("click", () => { closeCtx(); A.copy(A.urlFor(p.x, p.z, map.getZoom()), "Link to this spot"); });
    ctx.querySelector("[data-act='measure']").addEventListener("click", () => { closeCtx(); A.setMeasure(true); addMeasurePoint(e.latlng); });
    const iso = ctx.querySelector("[data-act='iso']");
    if (iso) iso.addEventListener("click", () => { closeCtx(); A.openIso({ x: p.x, z: p.z, title: "X " + p.x + "  Z " + p.z }); });
    ctx.querySelector("button").focus({ preventScroll: true });
  });
  map.on("click movestart zoomstart", closeCtx);
  document.addEventListener("pointerdown", (e) => { if (!ctx.hidden && !ctx.contains(e.target)) closeCtx(); });

  // ---- measure ------------------------------------------------------------
  let measuring = false;
  let mpoints = [];
  let mline = null;
  function measureText() {
    let d = 0;
    for (let i = 1; i < mpoints.length; i++) {
      const a = fromLatLngExact(mpoints[i - 1]), b = fromLatLngExact(mpoints[i]);
      d += Math.hypot(b.x - a.x, b.z - a.z);
    }
    return { d, text: A.int(d) + " blocks, " + num(d / CHUNK, 1) + " chunks" };
  }
  const fromLatLngExact = (ll) => ({ x: ll.lng, z: -ll.lat });
  function drawMeasure() {
    measureGroup.clearLayers();
    if (!mpoints.length) { updateMeasureChip(); return; }
    mline = L.polyline(mpoints, { color: "#e8925f", weight: 3, dashArray: "8 6", interactive: false }).addTo(measureGroup);
    mpoints.forEach((ll, i) => {
      const c = L.circleMarker(ll, { radius: 5, color: "#fbf6ee", weight: 2, fillColor: "#e8925f", fillOpacity: 1, interactive: false }).addTo(measureGroup);
      if (i === mpoints.length - 1 && mpoints.length > 1) c.bindTooltip(measureText().text, { permanent: true, direction: "right", offset: [8, 0], className: "measure-label" });
    });
    updateMeasureChip();
  }
  function updateMeasureChip() {
    const chip = $("measure-chip");
    if (!measuring && !mpoints.length) { chip.hidden = true; return; }
    const t = mpoints.length > 1 ? measureText().text : mpoints.length ? "Click the next point" : "Click two or more points on the map";
    chip.innerHTML = A.icon("ruler", "sm") + "<span>" + esc(t) + "</span>" +
      (mpoints.length ? "<button class='btn small' id='measure-clear'>Clear</button>" : "") +
      "<button class='icon-btn' id='measure-done' aria-label='Stop measuring'>" + A.icon("close", "sm") + "</button>";
    chip.hidden = false;
    const clr = $("measure-clear");
    if (clr) clr.addEventListener("click", () => { mpoints = []; drawMeasure(); });
    $("measure-done").addEventListener("click", () => A.setMeasure(false, true));
  }
  function addMeasurePoint(ll) { mpoints.push(ll); drawMeasure(); }
  A.setMeasure = (on, clear) => {
    measuring = !!on;
    document.body.classList.toggle("measuring", measuring);
    $("measure-btn").setAttribute("aria-pressed", String(measuring));
    if (measuring) map.doubleClickZoom.disable(); else map.doubleClickZoom.enable();
    if (clear) mpoints = [];
    drawMeasure();
  };
  map.on("click", (e) => { if (measuring) addMeasurePoint(e.latlng); });

  A.clearTools = () => {
    let did = false;
    if (!ctx.hidden) { closeCtx(); did = true; }
    if (measuring || mpoints.length) { A.setMeasure(false, true); did = true; }
    if (state.follow) { A.followPlayer(null); did = true; }
    if (!$("layers-pop").hidden) { toggleLayersPop(false); did = true; }
    return did;
  };

  // ---- controls -----------------------------------------------------------
  $("zoom-in").addEventListener("click", () => map.zoomIn());
  $("zoom-out").addEventListener("click", () => map.zoomOut());
  $("findme-btn").addEventListener("click", () => A.findMe());
  $("measure-btn").addEventListener("click", () => A.setMeasure(!measuring, true));
  $("share-btn").addEventListener("click", () => A.shareView());
  map.on("zoomend", () => {
    $("zoom-in").disabled = map.getZoom() >= map.getMaxZoom();
    $("zoom-out").disabled = map.getZoom() <= map.getMinZoom();
  });

  // ---- layers popover -----------------------------------------------------
  const pop = $("layers-pop");
  function toggleLayersPop(open) {
    const show = open == null ? pop.hidden : open;
    pop.hidden = !show;
    $("layers-btn").setAttribute("aria-expanded", String(show));
    $("layers-btn").classList.toggle("on", show);
    if (show) renderLayersPop();
  }
  A.toggleLayersPop = toggleLayersPop;
  $("layers-btn").addEventListener("click", (e) => { e.stopPropagation(); toggleLayersPop(); });
  document.addEventListener("pointerdown", (e) => {
    if (!pop.hidden && !pop.contains(e.target) && !$("layers-btn").contains(e.target)) toggleLayersPop(false);
  });

  function kindsPresent() {
    const out = [];
    const seen = new Set();
    const add = (key, label, color) => { if (!seen.has(key)) { seen.add(key); out.push([key, label, color]); } };
    if (state.points) {
      for (const p of state.points.points || []) {
        const k = String(p.kind || "").toLowerCase();
        const def = EVENT_KIND[k] || POINT_KIND[k];
        add("point:" + k, def ? def[2] : A.pretty(p.kind || "Point"), def ? def[1] : "#6d7680");
      }
    }
    const mk = MARKER_KIND;
    for (const m of state.markers || []) {
      const k = A.markerKind(m);
      add("marker:" + k, mk[k].label, mk[k].color);
    }
    // Player markers always have a chip while markers are shown, and a linked viewer always gets chips for
    // their own kinds, so the choice can be made before the first marker of that kind exists.
    if (A.layerOn("markers")) {
      add("marker:shared", mk.shared.label, mk.shared.color);
      if (A.hasOwnKinds()) for (const k of OWN_KINDS) add("marker:" + k, mk[k].label, mk[k].color);
    }
    for (const k of state.hiddenKinds) if (!seen.has(k)) add(k, A.pretty(k.split(":")[1] || k), "#6d7680");
    return out;
  }

  function renderLayersPop() {
    const canTerrain = state.layers.terrain !== false;
    const baseIsTerrain = canTerrain && A.store.get("base", "terrain") === "terrain";
    let h = "<h4>Base map</h4>";
    if (canTerrain) h += "<label class='check'><input type='radio' name='base' value='terrain'" + (baseIsTerrain ? " checked" : "") + "><span class='grow'>Terrain</span></label>";
    h += "<label class='check'><input type='radio' name='base' value='blank'" + (!baseIsTerrain ? " checked" : "") + "><span class='grow'>Blank</span></label>";
    const vis = OVERLAYS.filter((o) => o.vis() && o.list !== false);
    if (vis.length) {
      h += "<h4>Layers</h4>";
      for (const o of vis) {
        h += "<label class='check'><input type='checkbox' data-ov='" + o.key + "'" + (map.hasLayer(groups[o.key]) ? " checked" : "") + "><span class='grow'>" + o.label + "</span>";
        if (o.key === "heat") {
          const opts = [[3600000, "Last hour"], [21600000, "Last 6 hours"], [86400000, "Last day"], [604800000, "Last 7 days"]];
          h += "<select id='heat-window' aria-label='Activity window'>" + opts.map((x) => "<option value='" + x[0] + "'" + (x[0] === A.heatWindow ? " selected" : "") + ">" + x[1] + "</option>").join("") + "</select>";
        }
        h += "</label>";
      }
    }
    h += "<label class='check'><input type='checkbox' id='tint-toggle'" + (A.store.get("tint", false) ? " checked" : "") + "><span class='grow'>Day and night tint</span></label>";
    // Marker layers from plugins and files, highest priority first. A layer outside its zoom range says so.
    const mls = A.layerOn("layers") ? A.markerLayers() : [];
    if (mls.length) {
      h += "<h4>Marker layers</h4>";
      for (const def of mls) {
        const n = Array.isArray(def.markers) ? def.markers.length : 0;
        h += "<label class='check'><input type='checkbox' data-ml='" + esc(def.id) + "'" + (A.mlayerWanted(def) ? " checked" : "") + "><span class='grow'>" + esc(def.label || def.id) +
          (A.mlayerZoomOk(def) ? "" : "<small class='ml-zoom'>Shown at " + (def.minZoom != null && A.atlasZoom() < Number(def.minZoom) ? "a wider" : "a closer") + " zoom</small>") + "</span><span class='badge'>" + num(n, 0) + "</span></label>";
      }
    }
    const kinds = kindsPresent();
    if (kinds.length) {
      h += "<h4>Show on the map</h4><div class='chips'>" + kinds.map((k) => "<button class='fchip' data-kind='" + esc(k[0]) + "' aria-pressed='" + (!state.hiddenKinds.has(k[0])) + "'><span class='sw' style='background:" + k[2] + "'></span>" + esc(k[1]) + "</button>").join("") + "</div>";
    }
    pop.innerHTML = h;
    pop.querySelectorAll("input[name='base']").forEach((r) => r.addEventListener("change", () => { A.store.set("base", r.value); A.applyOverlays(); }));
    pop.querySelectorAll("input[data-ov]").forEach((c) => c.addEventListener("change", () => { userOverlays[c.dataset.ov] = c.checked; A.store.set("overlays", userOverlays); A.applyOverlays(); }));
    pop.querySelectorAll("input[data-ml]").forEach((c) => c.addEventListener("change", () => A.setMarkerLayer(c.dataset.ml, c.checked)));
    const hw = $("heat-window");
    if (hw) {
      hw.addEventListener("change", () => { A.heatWindow = Number(hw.value); A.store.set("heatWindow", A.heatWindow); A.loadHeat(); });
    }
    $("tint-toggle").addEventListener("change", (e) => { A.store.set("tint", e.target.checked); A.setNight(); });
    pop.querySelectorAll(".fchip").forEach((b) => b.addEventListener("click", () => {
      const k = b.dataset.kind;
      if (state.hiddenKinds.has(k)) state.hiddenKinds.delete(k); else state.hiddenKinds.add(k);
      A.store.set("hiddenKinds", Array.from(state.hiddenKinds));
      b.setAttribute("aria-pressed", String(!state.hiddenKinds.has(k)));
      drawPoints();
      drawMarkers();
    }));
  }
})();
