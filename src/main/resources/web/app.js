/*
 * Atlas web client.
 *
 * DEVELOPMENT
 * There is no build step and no bundler. index.html loads plain scripts in this order, and each one adds
 * to the shared window.Atlas object.
 *
 *   vendor/leaflet.js   Leaflet 1.9.4, bundled so no third party host is contacted.
 *   core.js             Shared state, text and time helpers, the API wrapper, an event bus, toasts and copy.
 *   map.js              The Leaflet map, tile geometry from api/worlds, every overlay, players with
 *                       interpolation, measure, context menu, follow, coordinates and the layers popover.
 *   panel.js            The side panel and bottom sheet, its tabs and every tab view including the faction page.
 *   app.js              This file. Boot, the world picker, the event stream, search, linking, theme and keys.
 *
 * All URLs are relative (api/worlds, never /api/worlds) so the map also works under a sub path. The client
 * speaks API version 1 as described in the README. A 403 or 404 answer has a JSON body with an error text and
 * each panel shows its own empty state for it. Modules talk through Atlas.emit and Atlas.on.
 */
(function () {
  "use strict";

  const A = window.Atlas;
  const { $, esc, num, state } = A;

  if (A.noLeaflet) {
    $("map").innerHTML = "<div class='empty'><p class='lead'>The map could not start</p><p>The map library did not load. Reload the page to try again.</p></div>";
    return;
  }
  const map = A.map;

  // ---- theme --------------------------------------------------------------
  function setTheme(t, save) {
    document.documentElement.setAttribute("data-theme", t);
    $("theme-btn").innerHTML = A.icon(t === "dark" ? "sun" : "moon");
    $("theme-btn").setAttribute("aria-label", t === "dark" ? "Switch to the light theme" : "Switch to the dark theme");
    $("theme-btn").title = $("theme-btn").getAttribute("aria-label") + " (t)";
    if (save) { try { localStorage.setItem("atlas.theme", t); } catch (e) { /* not saved */ } }
  }
  const toggleTheme = () => setTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark", true);
  setTheme(document.documentElement.getAttribute("data-theme") || "dark", false);
  $("theme-btn").addEventListener("click", toggleTheme);
  if (window.matchMedia) {
    const mq = matchMedia("(prefers-color-scheme: light)");
    const follow = () => {
      let saved = null;
      try { saved = localStorage.getItem("atlas.theme"); } catch (e) { saved = null; }
      if (!saved) setTheme(mq.matches ? "light" : "dark", false);
    };
    if (mq.addEventListener) mq.addEventListener("change", follow);
  }

  // ---- panel --------------------------------------------------------------
  const narrow = () => window.matchMedia("(max-width: 720px)").matches;
  A.setPanel(narrow() ? false : A.store.get("panelOpen", true));
  $("panel-btn").addEventListener("click", () => A.togglePanel());

  // ---- worlds -------------------------------------------------------------
  const visibleWorlds = () => state.worlds.filter((w) => !w.hiddenWorld || A.isAdmin());
  A.worldShown = (name) => visibleWorlds().some((w) => w.name === name);
  function applyWorlds(d) {
    const server = d.server && typeof d.server === "object" ? d.server : null;
    state.server = server;
    state.serverAt = Date.now();
    checkVersion(server);
    state.title = (server && server.title) || d.title || state.title || "Atlas";
    document.title = state.title;
    $("title").textContent = state.title;
    const wasStandalone = state.standalone;
    state.standalone = !!d.standalone;
    if (!state.tile || JSON.stringify(state.tile) !== JSON.stringify(d.tile)) { state.tile = d.tile || {}; A.initGeometry(state.tile); }
    state.worlds = Array.isArray(d.worlds) ? d.worlds : [];
    state.worldsAt = Date.now();
    renderWorldButton();
    if (!$("world-menu").hidden) renderWorldMenu();
    updateOnline();
    A.setNight();
    A.drawBorder();
    if (wasStandalone !== state.standalone) { A.buildTabs(); A.applyOverlays(); }
    // A standalone server has no factions, so the search never offers them.
    $("search").placeholder = state.standalone ? "Player, place or x z" : "Player, faction, place or x z";
  }
  // ---- version change -------------------------------------------------------
  // A server that runs a newer jar than the one this page came from gets a one line bar with a Reload button.
  // A tab nobody looks at reloads on its own, so it never keeps talking to the new server with old code.
  let updatePending = false;
  function checkVersion(server) {
    const v = server && typeof server.version === "string" ? server.version : "";
    if (!v || !A.clientVersion || v === A.clientVersion || updatePending) return;
    updatePending = true;
    if (document.visibilityState === "hidden") { location.reload(); return; }
    const bar = $("update-bar");
    bar.innerHTML = A.icon("alert", "sm") + "<span class='grow'>Atlas was updated, reload to continue</span><button class='btn small primary' id='update-reload'>Reload</button>";
    bar.hidden = false;
    $("update-reload").addEventListener("click", () => location.reload());
  }
  document.addEventListener("visibilitychange", () => { if (updatePending && document.visibilityState === "hidden") location.reload(); });

  function refetchWorlds() {
    return A.api("api/worlds").then((d) => {
      applyWorlds(d);
      if (state.world && !visibleWorlds().some((w) => w.name === state.world)) {
        const next = defaultWorld();
        if (next) { A.toast(A.worldLabel(state.world) + " is gone, back to " + (next.displayName || next.name)); setWorld(next.name, null); }
      }
    }).catch(() => { /* keep the last list */ });
  }
  setInterval(refetchWorlds, 30000);

  function defaultWorld() {
    const list = visibleWorlds();
    return list.find((w) => (w.kind || "world") === "world" && w.players > 0) || list.find((w) => w.players > 0) || list.find((w) => (w.kind || "world") === "world") || list[0] || null;
  }
  const kindBadge = (w) => {
    const k = w.kind || "world";
    if (k === "world") return "";
    return "<span class='badge accent'>" + A.icon(k === "portal" ? "portal" : "pin", "xs") + (k === "portal" ? "portal" : "instance") + "</span>";
  };
  function portalLine(w) {
    const p = w.portal;
    if (!p) return "";
    let h = "";
    if (p.remainingSeconds != null) h += "<span class='badge warn'>" + A.icon("clock", "xs") + "<span data-ends='" + Math.round(state.worldsAt + p.remainingSeconds * 1000) + "'>" + A.fmtLeft(p.remainingSeconds * 1000) + "</span></span> ";
    if (p.voidStage != null) h += "<span class='badge " + (p.voidActive ? "bad" : "") + "'>void stage " + esc(p.voidStage) + "</span> ";
    if (p.entries != null) h += "<span class='badge'>" + A.plural(p.entries, "entry", "entries") + "</span>";
    return h;
  }
  function renderWorldButton() {
    const w = A.currentWorld();
    $("world-label").textContent = w ? (w.displayName || w.name) : "No world";
    $("world-btn").hidden = visibleWorlds().length < 2 && !(w && w.portal);
  }
  function renderWorldMenu() {
    const list = visibleWorlds();
    const menu = $("world-menu");
    if (!list.length) { menu.innerHTML = "<div class='menu-note'>No worlds are loaded.</div>"; return; }
    menu.innerHTML = list.map((w) => "<button class='menu-item" + (w.name === state.world ? " current" : "") + "' role='menuitemradio' aria-checked='" + (w.name === state.world) + "' data-world='" + esc(w.name) + "'>" +
      A.icon(w.kind === "portal" ? "portal" : w.kind === "instance" ? "pin" : "globe", "sm") +
      "<span class='grow'>" + esc(w.displayName || w.name) + " " + kindBadge(w) + (w.hiddenWorld ? " <span class='badge'>hidden</span>" : "") +
      "<small>" + (w.players != null ? A.plural(w.players, "player") : "") + (w.displayName && w.displayName !== w.name ? " " + esc(w.name) : "") + "</small>" +
      (w.portal ? "<small>" + portalLine(w) + "</small>" : "") + "</span></button>").join("");
    menu.querySelectorAll("[data-world]").forEach((b) => b.addEventListener("click", () => { closeWorldMenu(); if (b.dataset.world !== state.world) setWorld(b.dataset.world, null); }));
    A.tickCountdowns();
  }
  function openWorldMenu() { renderWorldMenu(); $("world-menu").hidden = false; $("world-btn").setAttribute("aria-expanded", "true"); const c = $("world-menu").querySelector(".current") || $("world-menu").querySelector("button"); if (c) c.focus(); }
  function closeWorldMenu() { $("world-menu").hidden = true; $("world-btn").setAttribute("aria-expanded", "false"); }
  $("world-btn").addEventListener("click", () => { if ($("world-menu").hidden) openWorldMenu(); else closeWorldMenu(); });
  menuKeys($("world-menu"), closeWorldMenu, $("world-btn"));

  function menuKeys(menu, close, opener) {
    menu.addEventListener("keydown", (e) => {
      const items = Array.from(menu.querySelectorAll("button"));
      const i = items.indexOf(document.activeElement);
      if (e.key === "ArrowDown") { (items[i + 1] || items[0]).focus(); e.preventDefault(); }
      else if (e.key === "ArrowUp") { (items[i - 1] || items[items.length - 1]).focus(); e.preventDefault(); }
      else if (e.key === "Escape") { close(); opener.focus(); e.stopPropagation(); }
    });
    document.addEventListener("pointerdown", (e) => { if (!menu.hidden && !menu.contains(e.target) && !opener.contains(e.target)) close(); });
  }

  function updateOnline() {
    const el = $("online");
    if (!A.layerOn("players") || state.playersHidden) { el.hidden = true; return; }
    let n = 0;
    for (const w of state.worlds) if (w.name !== state.world) n += Number(w.players) || 0;
    let here = 0;
    for (const s of state.players.values()) if (s.data.world === state.world) here++;
    n += state.lastPlayersAt ? here : ((A.currentWorld() || {}).players || 0);
    el.innerHTML = A.icon("players", "sm") + "<b>" + num(n, 0) + "</b><span class='lbl'>online</span>";
    el.title = A.plural(n, "player") + " online";
    el.hidden = false;
  }
  A.on("players", A.throttle(updateOnline, 1000));

  // ---- world switching ----------------------------------------------------
  function setWorld(name, view) {
    const changed = state.world !== name;
    state.world = name;
    renderWorldButton();
    const w = A.currentWorld();
    if (changed) {
      A.resetTiles();
      A.groups.heat.clearLayers();
      A.groups.paths.clearLayers();
      A.syncPlayerMarkers();
    }
    const zoom = A.defaultZoom();
    const b = w && w.bounds && [w.bounds.minX, w.bounds.minZ, w.bounds.maxX, w.bounds.maxZ].every((n) => typeof n === "number" && isFinite(n)) ? w.bounds : null;
    // A spawn outside the explored land would open on an empty map, so the explored bounds win then.
    const spawnInside = w && w.spawn && (!b || (w.spawn.x >= b.minX && w.spawn.x <= b.maxX && w.spawn.z >= b.minZ && w.spawn.z <= b.maxZ));
    if (view) map.setView(A.toLatLng(view.x, view.z), view.zoom != null && !Number.isNaN(view.zoom) ? view.zoom : zoom, { animate: false });
    else if (spawnInside) map.setView(A.toLatLng(w.spawn.x, w.spawn.z), zoom, { animate: false });
    else if (b) map.fitBounds(L.latLngBounds(A.toLatLng(b.minX, b.minZ), A.toLatLng(b.maxX, b.maxZ)), { animate: false, maxZoom: zoom });
    else map.setView(A.toLatLng(0, 0), zoom, { animate: false });
    if (changed || !es) connect();
    A.drawBorder();
    loadWorldData();
    A.setNight();
    A.saveUrl();
    updateOnline();
    if (changed) A.refreshAllPlayers(true);
    A.renderIf("players", "activity");
  }
  A.on("switchWorld", (d) => {
    if (!d || !d.name) return;
    if (!state.worlds.some((w) => w.name === d.name)) { A.toast("That world is not on the map"); return; }
    if (!A.worldShown(d.name)) { A.toast(A.worldLabel(d.name) + " is not shown on this map"); return; }
    if (d.name === state.world) { if (d.view) A.viewTo(d.view.x, d.view.z, d.view.zoom); return; }
    setWorld(d.name, d.view || null);
  });

  // ---- viewer -------------------------------------------------------------
  function applyViewer(v) {
    const before = JSON.stringify(state.viewer);
    state.viewer = v || { linked: false, admin: false, layers: {} };
    state.layers = (v && v.layers) || {};
    renderAccount();
    A.applyOverlays();
    A.buildTabs();
    updateOnline();
    renderWorldButton();
    return before !== JSON.stringify(state.viewer);
  }
  // Everything drawn for the current world. setWorld calls it, so boot does not ask twice.
  function loadWorldData() {
    A.loadTerritories();
    A.loadPoints();
    A.loadMarkers();
    A.loadWorldEvents();
    A.loadHeat();
    A.loadPaths();
  }
  // Data that does not depend on the world. The faction list is fetched by the views and search that use it.
  function loadShared(render) {
    A.loadNations();
    loadActivity();
    state.factionsStale = true;
    if (render) A.render();
  }
  function reloadAll() {
    loadWorldData();
    loadShared(true);
  }

  function renderAccount() {
    const v = state.viewer || {};
    const btn = $("account-btn");
    if (v.viewingAs) {
      $("account-label").textContent = "As " + v.faction;
      btn.querySelector("use").setAttribute("href", "#i-eye");
      btn.setAttribute("aria-label", "Viewing as " + v.faction + ". Open the account menu");
    } else if (v.linked) {
      $("account-label").textContent = v.name || "Linked";
      btn.querySelector("use").setAttribute("href", "#i-user");
      btn.setAttribute("aria-label", "Linked as " + (v.name || "") + ". Open the account menu");
    } else {
      $("account-label").textContent = "Link";
      btn.querySelector("use").setAttribute("href", "#i-link");
      btn.setAttribute("aria-label", "Link this browser to your player");
    }
  }
  function renderAccountMenu() {
    const v = state.viewer || {};
    const menu = $("account-menu");
    let h = "<div class='menu-note'>";
    if (v.viewingAs) h += "Viewing the map as <b>" + esc(v.faction) + "</b>.";
    else if (v.admin) h += "Linked as <b>" + esc(v.name || "admin") + "</b> with admin access.";
    else h += "Linked as <b>" + esc(v.name) + "</b>" + (v.faction ? " of " + A.facChip(v.faction) : "") + ".";
    h += "</div><div class='menu-sep'></div>";
    if (v.viewingAs) h += "<button class='menu-item' data-act='admin'>" + A.icon("admin", "sm") + "<span class='grow'>Back to the admin view</span></button>";
    h += "<button class='menu-item' data-act='findme'>" + A.icon("locate", "sm") + "<span class='grow'>Find me on the map</span></button>";
    if (!v.viewingAs) h += "<button class='menu-item' data-act='logout'>" + A.icon("logout", "sm") + "<span class='grow'>Unlink this browser</span></button>";
    menu.innerHTML = h;
    menu.querySelectorAll("[data-act]").forEach((b) => b.addEventListener("click", () => {
      closeAccountMenu();
      if (b.dataset.act === "logout") logout();
      else if (b.dataset.act === "admin") A.emit("viewAs", "");
      else A.findMe();
    }));
  }
  function closeAccountMenu() { $("account-menu").hidden = true; $("account-btn").setAttribute("aria-expanded", "false"); }
  $("account-btn").addEventListener("click", () => {
    const v = state.viewer || {};
    if (!v.linked && !v.viewingAs) { openLink(); return; }
    if ($("account-menu").hidden) {
      renderAccountMenu();
      $("account-menu").hidden = false;
      $("account-btn").setAttribute("aria-expanded", "true");
      const f = $("account-menu").querySelector("button");
      if (f) f.focus();
    } else closeAccountMenu();
  });
  menuKeys($("account-menu"), closeAccountMenu, $("account-btn"));

  A.on("viewAs", (tag) => {
    state.asTag = tag || "";
    boot(true).then((ok) => ok && A.toast(state.asTag ? "Viewing the map as " + state.asTag : "Back to the admin view", "good"));
  });

  // ---- linking ------------------------------------------------------------
  const dlg = $("link-dialog");
  function openLink() {
    $("link-error").textContent = "";
    $("link-code").value = "";
    if (dlg.showModal) dlg.showModal(); else dlg.setAttribute("open", "");
    $("link-code").focus();
  }
  function closeLink() { if (dlg.close) dlg.close(); else dlg.removeAttribute("open"); }
  const postLink = (body) => fetch("api/link", { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  function logout() {
    postLink({ action: "logout" })
      .then(() => { state.asTag = ""; return boot(true); })
      .then((ok) => { if (ok) A.toast("This browser is no longer linked", "good"); })
      .catch(() => A.toast("The server could not be reached", "bad"));
  }
  $("link-cancel").addEventListener("click", closeLink);
  $("link-code").addEventListener("input", (e) => { e.target.value = e.target.value.replace(/[^0-9a-z]/gi, "").toUpperCase().slice(0, 6); });
  $("link-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const code = $("link-code").value.trim();
    if (code.length !== 6) { $("link-error").textContent = "The code has six characters."; return; }
    postLink({ code })
      .then((r) => r.json().catch(() => ({})).then((d) => ({ ok: r.ok, d })))
      .then(({ ok, d }) => {
        if (!ok) { $("link-error").textContent = d.error || "That did not work. Check the code and try again."; return; }
        closeLink();
        boot(true).then((ok) => ok && A.toast("Linked" + (state.viewer && state.viewer.name ? " as " + state.viewer.name : ""), "good"));
      }).catch(() => { $("link-error").textContent = "The server could not be reached."; });
  });

  // ---- event stream -------------------------------------------------------
  let es = null;
  let retry = 0;
  let retryTimer = null;
  let lostAt = 0;
  let everOpen = false;
  function setConn(s) {
    if (state.conn === s) return;
    state.conn = s;
    const el = $("status");
    el.className = "status " + s;
    el.querySelector(".txt").textContent = { live: "Live", reconnecting: "Reconnecting", offline: "Offline", connecting: "Connecting" }[s] || s;
    el.title = { live: "Live updates are on", reconnecting: "Live updates dropped and are reconnecting", offline: "Live updates are offline. The map keeps trying.", connecting: "Connecting to live updates" }[s] || "";
    A.emit("conn", s);
  }
  const parse = (e) => { try { return JSON.parse(e.data); } catch (err) { return null; } };

  function connect() {
    clearTimeout(retryTimer);
    if (es) es.close();
    if (!state.world) return;
    const q = new URLSearchParams({ world: state.world });
    if (state.asTag) q.set("as", state.asTag);
    let src;
    try { src = new EventSource("api/events?" + q.toString()); } catch (e) { setConn("offline"); return; }
    es = src;
    src.onopen = () => {
      if (es !== src) return;
      const wasDown = everOpen && state.conn !== "live";
      retry = 0; lostAt = 0; everOpen = true;
      setConn("live");
      // Every browser comes back at about the same moment after a restart, so the refetches are spread over a second.
      if (wasDown) { spread(refetchWorlds); spread(loadWorldData); }
    };
    src.onerror = () => {
      if (es !== src) return;
      if (!lostAt) lostAt = Date.now();
      // The browser retries a dropped stream itself but gives up after an error status such as 503 or 429.
      if (src.readyState === EventSource.CLOSED) {
        src.close();
        retry++;
        // The browser cannot read the status, but a closed stream means the server answered with an error such
        // as 429 or 503 with Retry-After 30. The first retry waits about 30 seconds and later ones back off further.
        // Up to two seconds of random delay on top keep a crowd of browsers from reconnecting in step.
        const wait = Math.min(120000, 30000 * Math.pow(1.5, Math.min(retry - 1, 4))) * (0.85 + Math.random() * 0.3) + Math.random() * 2000;
        setConn(retry > 4 ? "offline" : "reconnecting");
        retryTimer = setTimeout(connect, wait);
      } else {
        setConn(Date.now() - lostAt > 20000 ? "offline" : "reconnecting");
      }
    };
    const on = (name, fn) => src.addEventListener(name, (e) => { if (es !== src) return; const d = parse(e); if (d != null) fn(d); });
    // The server sends hello again when the viewer's access, faction or layers change.
    on("hello", (d) => {
      if (d.server) { state.server = Object.assign({}, state.server, d.server); state.serverAt = Date.now(); checkVersion(d.server); }
      if (d.viewer && JSON.stringify(d.viewer) !== JSON.stringify(state.viewer)) { applyViewer(d.viewer); spread(reloadAll); }
    });
    on("players", (d) => { A.onPlayers(d); });
    on("snapshot", onSnapshot);
    on("tiles", (d) => A.onTiles(d));
    const items = (d) => d && Array.isArray(d.items) ? d.items : [];
    on("feedlog", (d) => { state.feed = items(d).slice(-200); A.emit("activity"); });
    on("feed", (d) => { state.feed.push(d); state.feed = state.feed.slice(-200); A.emit("activity"); });
    on("chatlog", (d) => { state.chat = items(d).slice(-150); A.emit("chat"); });
    on("chat", (d) => { state.chat.push(d); state.chat = state.chat.slice(-150); A.emit("chat"); });
    on("killlog", (d) => addKills(items(d)));
    on("kill", (d) => addKills([d]));
    on("worldevent", (d) => {
      A.onWorldEvent(d);
      addEventLog([{ at: d.at || Date.now(), id: d.id, world: d.world, name: d.name, displayName: d.displayName, state: d.state === "end" ? "end" : "start", x: d.x, z: d.z }]);
      if (d.state !== "end" && d.world === state.world) A.toast((d.displayName || A.pretty(d.name)) + " opened");
    });
    on("leaderboard", () => { state.leaderboard = null; if (state.tab === "leaderboard" && !state.detail) A.render(); });
    on("worlds", () => refetchWorlds());
    // The shared marker set of a world changed, or with own set the viewer's own markers did.
    on("markers", (d) => { if (!d.world || d.world === state.world) markersSoon(); });
  }
  const spread = (fn) => setTimeout(fn, Math.random() * 1000);
  let markersTimer = null;
  function markersSoon() {
    clearTimeout(markersTimer);
    markersTimer = setTimeout(() => A.loadMarkers(), 500);
  }
  setInterval(() => { if (state.conn === "reconnecting" && lostAt && Date.now() - lostAt > 20000) setConn("offline"); }, 5000);
  window.addEventListener("online", () => { if (state.conn !== "live") connect(); });

  // Snapshot announcements name the sections that changed.
  let pendingSections = new Set();
  let snapTimer = null;
  let lastSnap = 0;
  function onSnapshot(d) {
    for (const s of (d && Array.isArray(d.sections) ? d.sections : [])) pendingSections.add(s);
    if (!pendingSections.size) return;
    if (snapTimer) return;
    snapTimer = setTimeout(flushSnapshot, Math.max(300, 2000 - (Date.now() - lastSnap)));
  }
  function flushSnapshot() {
    snapTimer = null;
    lastSnap = Date.now();
    const s = pendingSections;
    pendingSections = new Set();
    if (s.has("territories")) A.loadTerritories();
    if (s.has("points") || s.has("events")) A.loadPoints();
    if (s.has("nations")) A.loadNations();
    // The faction list is only fetched again while a faction view is on screen. Otherwise it is marked stale.
    const factionView = A.panelOpen() && (state.detail || state.tab === "factions");
    if (s.has("factions") && A.layerOn("factions") && !factionView) state.factionsStale = true;
    if (state.standalone) refetchWorlds();
    if (state.detail && (s.has("factions") || s.has("territories") || s.has("nations"))) { if (A.panelOpen()) A.render(); }
    else if ((s.has("factions") && state.tab === "factions" && A.panelOpen()) || ((s.has("factions") || s.has("events") || s.has("territories")) && state.tab === "stats") || (s.has("nations") && state.tab === "nations")) A.render();
  }

  // ---- activity sources ---------------------------------------------------
  const killKey = (k) => [k.at, k.victim && k.victim.name, k.victim && k.victim.type, k.killer && k.killer.name].join("|");
  function addKills(list) {
    const seen = new Set(state.kills.map(killKey));
    for (const k of list) if (k && k.at && !seen.has(killKey(k))) { seen.add(killKey(k)); state.kills.push(k); }
    state.kills.sort((a, b) => a.at - b.at);
    state.kills = state.kills.slice(-200);
    A.emit("activity");
  }
  function addEventLog(list) {
    state.eventLog = state.eventLog || [];
    const key = (e) => e.id + "|" + e.state;
    const seen = new Set(state.eventLog.map(key));
    for (const e of list) if (!seen.has(key(e))) { seen.add(key(e)); state.eventLog.push(e); }
    state.eventLog = state.eventLog.slice(-200);
    A.emit("activity");
  }
  function loadActivity() {
    if (A.layerOn("kills")) {
      A.api("api/kills?limit=50").then((d) => { if (d && Array.isArray(d.kills)) addKills(d.kills); }).catch(() => { /* the stream fills it */ });
    }
    if (A.layerOn("worldevents")) {
      A.api("api/worldevents").then((d) => {
        addEventLog((d.events || []).map((e) => ({ at: e.startedAt, id: e.id, world: e.world, name: e.name, displayName: e.displayName, state: "start", x: e.x, z: e.z, located: e.located })));
      }).catch(() => { /* the stream fills it */ });
    }
  }

  // ---- search -------------------------------------------------------------
  const input = $("search");
  const results = $("search-results");
  let items = [];
  let active = -1;
  function searchItems(q) {
    const out = [];
    const ql = q.toLowerCase();
    const m = q.match(/^(-?\d+(?:\.\d+)?)[,\s]+(-?\d+(?:\.\d+)?)(?:[,\s]+(-?\d+(?:\.\d+)?))?$/);
    if (m) {
      const x = Math.round(+m[1]), z = Math.round(m[3] != null ? +m[3] : +m[2]);
      out.push({ icon: "pin", label: "Go to X " + x + " Z " + z, kind: "Coordinates", go: () => A.viewTo(x, z, Math.max(map.getZoom(), A.geo.maxNative - 1)) });
    }
    if (!ql) return out;
    const live = Array.from(state.players.values()).map((s) => s.data).concat((state.others || []).filter((p) => p.world !== state.world));
    for (const p of live) if (String(p.name || "").toLowerCase().includes(ql)) out.push({ icon: "user", label: p.name, kind: "Player in " + A.worldLabel(p.world), go: () => A.locatePlayer(p.uuid) });
    for (const o of state.offline || []) {
      if (!String(o.name || "").toLowerCase().includes(ql) || o.x == null) continue;
      out.push({ icon: "clock", label: o.name, kind: "Last seen " + A.rel(o.at), go: () => A.emit("switchWorld", { name: o.world || state.world, view: { x: o.x, z: o.z, zoom: Math.max(map.getZoom(), A.geo.maxNative - 2) } }) });
    }
    if (A.layerOn("factions")) {
      for (const f of state.factions || []) {
        if (String(f.tag || "").toLowerCase().includes(ql) || String(f.name || "").toLowerCase().includes(ql)) out.push({ icon: "shield", label: f.tag + " " + (f.name || ""), kind: "Faction", go: () => A.openFaction(f.tag) });
      }
    }
    for (const n of state.nations || []) {
      if (String(n.tag || "").toLowerCase().includes(ql) || String(n.name || "").toLowerCase().includes(ql)) out.push({ icon: "globe", label: n.tag + (n.name && n.name !== n.tag ? " " + n.name : ""), kind: "Nation", go: () => { state.nationFocus = n.tag; A.setPanel(true); A.showTab("nations"); } });
    }
    for (const mk of state.markers || []) {
      const lk = A.markerLook(mk);
      const hit = String(mk.name || "").toLowerCase().includes(ql) || (lk.kind === "shared" && lk.by.toLowerCase().includes(ql));
      if (hit) out.push({ icon: lk.sym, label: lk.title, kind: lk.kind === "shared" && lk.by ? "Marker by " + lk.by : A.MARKER_KIND[lk.kind].sub, go: () => A.showMarker(A.markerKey(mk)) });
    }
    for (const z of (state.points && state.points.zones) || []) {
      if (String(z.name || "").toLowerCase().includes(ql)) out.push({ icon: "pin", label: z.name, kind: "Place", go: () => A.viewTo(z.x, z.z, Math.max(map.getZoom(), A.geo.maxNative - 2)) });
    }
    for (const p of (state.points && state.points.points) || []) {
      if (p.label && String(p.label).toLowerCase().includes(ql)) out.push({ icon: "flag", label: p.label, kind: A.pretty(p.kind || "Point"), go: () => A.viewTo(p.x, p.z, Math.max(map.getZoom(), A.geo.maxNative - 1)) });
    }
    for (const w of visibleWorlds()) {
      const label = w.displayName || w.name;
      if (label.toLowerCase().includes(ql) || w.name.toLowerCase().includes(ql)) out.push({ icon: "globe", label, kind: "World", go: () => setWorld(w.name, null) });
    }
    return out.slice(0, 14);
  }
  function drawResults() {
    if (!items.length) {
      results.innerHTML = "<div class='menu-note'>No matches. Try a player, " + (state.standalone ? "" : "a faction, ") + "a place or coordinates such as 120 -340.</div>";
    } else {
      results.innerHTML = items.map((r, i) => "<button class='menu-item" + (i === active ? " active" : "") + "' role='option' id='sr-" + i + "' aria-selected='" + (i === active) + "' data-i='" + i + "' tabindex='-1'>" +
        A.icon(r.icon, "sm") + "<span class='grow'>" + esc(r.label) + "<small class='kind'>" + esc(r.kind) + "</small></span></button>").join("");
      results.querySelectorAll("[data-i]").forEach((d) => d.addEventListener("mousedown", (e) => { e.preventDefault(); pick(+d.dataset.i); }));
    }
    results.hidden = false;
    input.setAttribute("aria-expanded", "true");
    input.setAttribute("aria-activedescendant", active >= 0 ? "sr-" + active : "");
  }
  function closeResults() { results.hidden = true; input.setAttribute("aria-expanded", "false"); }
  function pick(i) {
    const r = items[i];
    if (!r) return;
    closeResults();
    input.blur();
    document.body.classList.remove("search-open");
    r.go();
  }
  input.addEventListener("input", () => {
    const q = input.value.trim();
    if (!q) { closeResults(); return; }
    items = searchItems(q);
    active = items.length ? 0 : -1;
    drawResults();
  });
  input.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown") { active = Math.min(items.length - 1, active + 1); drawResults(); e.preventDefault(); }
    else if (e.key === "ArrowUp") { active = Math.max(0, active - 1); drawResults(); e.preventDefault(); }
    else if (e.key === "Enter") { pick(active); e.preventDefault(); }
    else if (e.key === "Escape") { closeResults(); input.value = ""; input.blur(); document.body.classList.remove("search-open"); e.stopPropagation(); }
  });
  input.addEventListener("focus", () => {
    if ((!state.factions.length || state.factionsStale) && A.layerOn("factions")) A.loadFactions().catch(() => { /* players still match */ });
    if (!state.others) A.refreshAllPlayers(true);
    if (input.value.trim()) input.dispatchEvent(new Event("input"));
  });
  input.addEventListener("blur", () => setTimeout(() => { closeResults(); if (!input.value) document.body.classList.remove("search-open"); }, 150));
  function focusSearch() {
    document.body.classList.add("search-open");
    setTimeout(() => { input.focus(); input.select(); }, 0);
  }
  $("search-toggle").addEventListener("click", () => { if (document.body.classList.contains("search-open")) { document.body.classList.remove("search-open"); input.blur(); } else focusSearch(); });

  // ---- keyboard -----------------------------------------------------------
  document.addEventListener("keydown", (e) => {
    const t = e.target;
    const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
    if (e.key === "Escape") {
      if (dlg.open) return;
      if (!$("world-menu").hidden) { closeWorldMenu(); return; }
      if (!$("account-menu").hidden) { closeAccountMenu(); return; }
      if (A.clearTools()) return;
      if (narrow() && A.panelOpen()) A.setPanel(false);
      return;
    }
    if (typing || e.metaKey || e.ctrlKey || e.altKey || dlg.open) return;
    if (e.key === "/") { e.preventDefault(); focusSearch(); }
    else if (e.key === "f") A.findMe();
    else if (e.key === "l") A.togglePanel();
    else if (e.key === "t") toggleTheme();
  });

  // ---- boot ---------------------------------------------------------------
  let booted = false;
  function boot(again) {
    return A.api("api/worlds").then((d) => {
      applyWorlds(d);
      applyViewer(d.viewer);
      const q = new URLSearchParams(location.search);
      let name = state.world;
      let view = null;
      if (!again || !name || !visibleWorlds().some((w) => w.name === name)) {
        const wanted = q.get("world");
        const byUrl = wanted && visibleWorlds().find((w) => w.name === wanted);
        const def = byUrl || defaultWorld();
        if (!def) {
          $("world-label").textContent = "No world";
          A.toast("No worlds are loaded on the server yet", "bad");
          setConn("offline");
          return;
        }
        name = def.name;
        if (byUrl && q.has("x") && q.has("z")) view = { x: +q.get("x"), z: +q.get("z"), zoom: q.has("zoom") ? +q.get("zoom") : null };
      } else {
        const c = A.fromLatLng(map.getCenter());
        view = { x: c.x, z: c.z, zoom: map.getZoom() };
      }
      if (again) { if (es) es.close(); es = null; state.world = null; A.clearPlayers(); }
      setWorld(name, view);
      loadShared(!!again);
      booted = true;
      showHint();
      return true;
    }).catch((e) => {
      if (!A.isServerError(e)) { booted = true; A.reportBug(e); return false; }
      setConn("offline");
      if (!booted) {
        $("title").textContent = state.title || document.title.replace("{{TITLE}}", "") || "Map";
        A.toast("The map server could not be reached. Retrying shortly.", "bad");
        setTimeout(() => boot(false), 8000);
      } else {
        A.toast("The map server could not be reached", "bad");
      }
    });
  }
  // ---- first visit hint ---------------------------------------------------
  // A small card over the map the first time, never a modal. Dismissing it is remembered in this browser.
  function showHint() {
    if (A.store.get("hintSeen", false)) return;
    const el = $("hint");
    if (!el || !el.hidden) return;
    const touch = A.touch;
    el.innerHTML = "<div class='hint-head'>" + A.icon("globe", "sm") + "<b class='grow'>Getting around</b>" +
      "<button class='icon-btn' id='hint-x' aria-label='Dismiss the tips'>" + A.icon("close", "sm") + "</button></div><ul>" +
      "<li>Use " + A.icon("link", "xs") + " <b>Link</b> and the code from /atlas link in game to see more of the map.</li>" +
      "<li>Pick " + A.icon("eye", "xs") + " <b>Follow</b> on a player in the Players list and the map keeps them in view.</li>" +
      "<li>" + (touch ? "Long press" : "Right click") + " the map to copy coordinates or a link to that spot.</li></ul>" +
      "<button class='btn small' id='hint-ok'>Got it</button>";
    el.hidden = false;
    const close = () => { A.store.set("hintSeen", true); el.hidden = true; };
    $("hint-x").addEventListener("click", close);
    $("hint-ok").addEventListener("click", close);
  }

  boot(false);
})();
