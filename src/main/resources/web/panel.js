/* Atlas side panel. Tabs, every panel view, the faction page and the bottom sheet on phones. */
(function () {
  "use strict";

  const A = window.Atlas;
  const { $, esc, col, num, state } = A;
  const body = $("tab-body");
  const tabsEl = $("tabs");

  const TABS = {
    players: { label: "Players", icon: "players" },
    markers: { label: "Markers", icon: "pin" },
    factions: { label: "Factions", icon: "shield" },
    nations: { label: "Nations", icon: "globe" },
    activity: { label: "Activity", icon: "activity" },
    timeline: { label: "Timeline", icon: "history" },
    chat: { label: "Chat", icon: "chat" },
    leaderboard: { label: "Leaderboard", icon: "trophy" },
    stats: { label: "Stats", icon: "stats" },
    admin: { label: "Admin", icon: "admin" }
  };
  state.tab = null;
  state.detail = null;
  state.nationFocus = null;
  let renderSeq = 0;
  let currentTabs = [];

  const can = {
    players: () => A.layerOn("players"),
    // Shown while the viewer may see markers and there is something to list or the viewer could have their own.
    // While it is the open tab it stays, so a list that empties shows its empty state instead of vanishing.
    markers: () => (A.layerOn("markers") && (A.hasOwnKinds() || state.markers.some(listable) || state.tab === "markers")) || (A.layerOn("layers") && !!A.mlayers && A.mlayers.size > 0),
    factions: () => A.layerOn("factions") && !state.standalone,
    nations: () => A.layerOn("nations") && !state.standalone && !!(state.nations && state.nations.length),
    activity: () => SOURCES.some((s) => s.ok()),
    timeline: () => A.layerOn("timeline") && !!A.renderTimeline,
    chat: () => A.layerOn("chat"),
    leaderboard: () => A.layerOn("leaderboard"),
    stats: () => A.layerOn("stats"),
    admin: () => !!(state.viewer && (state.viewer.admin || state.viewer.viewingAs))
  };
  A.canTab = (t) => !!(can[t] && can[t]());

  // ---- panel open and close -----------------------------------------------
  const narrow = () => window.matchMedia("(max-width: 720px)").matches;
  A.panelOpen = () => !document.body.classList.contains("panel-closed");
  A.setPanel = (open) => {
    document.body.classList.toggle("panel-closed", !open);
    $("panel-btn").setAttribute("aria-expanded", String(!!open));
    $("sheet-peek").setAttribute("aria-expanded", String(!!open));
    $("panel").style.transform = "";
    // A closed sheet on a phone still shows its peek, so the hidden content is taken out of the tab order.
    for (const id of ["tabs", "tab-body"]) { const el = $(id); if (open) el.removeAttribute("inert"); else el.setAttribute("inert", ""); }
    if (!narrow()) A.store.set("panelOpen", !!open);
    if (open && state.factionsStale && (state.detail || state.tab === "factions") && A.render) A.render();
    setTimeout(() => A.map && A.map.invalidateSize(), 260);
  };
  A.togglePanel = () => A.setPanel(!A.panelOpen());

  // Swipe the sheet top down to close it on phones and up to open it again. A tap on the peek opens it.
  (function sheet() {
    const handle = $("sheet-handle");
    const panel = $("panel");
    const peek = $("sheet-peek");
    let y0 = null, dy = 0, wasOpen = false, swiped = false;
    handle.addEventListener("pointerdown", (e) => {
      if (!narrow()) return;
      y0 = e.clientY; dy = 0; wasOpen = A.panelOpen(); swiped = false;
      if (!peek.contains(e.target)) handle.setPointerCapture(e.pointerId);
      panel.style.transition = "none";
    });
    handle.addEventListener("pointermove", (e) => {
      if (y0 == null) return;
      dy = e.clientY - y0;
      if (wasOpen && dy > 0) panel.style.transform = "translateY(" + dy + "px)";
    });
    const end = (e) => {
      if (y0 == null) return;
      panel.style.transition = "";
      panel.style.transform = "";
      const onPeek = e && peek.contains(e.target);
      if (wasOpen && dy > 60) { A.setPanel(false); swiped = true; }
      else if (!wasOpen && dy < -20) { A.setPanel(true); swiped = true; }
      else if (Math.abs(dy) < 6 && !onPeek) A.setPanel(!wasOpen);
      y0 = null;
    };
    handle.addEventListener("pointerup", end);
    handle.addEventListener("pointercancel", end);
    peek.addEventListener("click", () => { if (swiped) { swiped = false; return; } A.setPanel(!A.panelOpen()); });
  })();

  // ---- tabs ---------------------------------------------------------------
  A.buildTabs = () => {
    const list = Object.keys(TABS).filter((t) => A.canTab(t));
    const changed = list.join() !== currentTabs.join();
    currentTabs = list;
    if (changed) {
      tabsEl.innerHTML = list.map((t) => "<button class='tab' role='tab' id='tab-" + t + "' data-tab='" + t + "' aria-controls='tab-body' aria-label='" + TABS[t].label + "' title='" + TABS[t].label + "'>" +
        A.icon(TABS[t].icon) + "<span class='lbl'>" + TABS[t].label + "</span></button>").join("");
      tabsEl.querySelectorAll(".tab").forEach((b) => b.addEventListener("click", () => A.showTab(b.dataset.tab)));
    }
    if (!list.length) {
      body.innerHTML = emptyState("panel", "Nothing to show here", "This server keeps its map details private. Link your account to see more.");
      state.tab = null;
      return;
    }
    if (!list.includes(state.tab)) {
      const stored = A.store.get("tab", null);
      state.tab = stored && list.includes(stored) ? stored
        : (!state.standalone && list.includes("factions")) ? "factions"
        : list.includes("players") ? "players" : list.includes("activity") ? "activity" : list[0];
      state.detail = null;
      A.render();
    } else if (changed) {
      markTabs();
    }
  };
  function markTabs() {
    tabsEl.querySelectorAll(".tab").forEach((b) => {
      const on = b.dataset.tab === state.tab;
      b.setAttribute("aria-selected", String(on));
      b.tabIndex = on ? 0 : -1;
    });
    const t = TABS[state.tab];
    $("peek-label").textContent = state.detail ? "Faction " + state.detail : t ? t.label : "Panel";
    $("peek-icon").setAttribute("href", "#i-" + (state.detail ? "shield" : t ? t.icon : "panel"));
  }
  tabsEl.addEventListener("keydown", (e) => {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
    const i = currentTabs.indexOf(state.tab);
    const n = currentTabs[(i + (e.key === "ArrowRight" ? 1 : currentTabs.length - 1)) % currentTabs.length];
    A.showTab(n);
    const b = $("tab-" + n);
    if (b) b.focus();
    e.preventDefault();
  });
  A.showTab = (t) => {
    if (!A.canTab(t)) return;
    const left = state.tab;
    state.tab = t;
    state.detail = null;
    A.store.set("tab", t);
    // Leaving the Markers tab lets the bar drop it if it no longer has anything to show.
    if (left === "markers" && t !== "markers") { A.buildTabs(); const nb = $("tab-" + t); if (nb && document.activeElement === document.body) nb.focus(); }
    body.scrollTop = 0;
    A.render();
    const b = $("tab-" + t);
    if (b && b.scrollIntoView) b.scrollIntoView({ block: "nearest", inline: "nearest" });
  };

  A.render = () => {
    markTabs();
    const seq = ++renderSeq;
    const live = () => seq === renderSeq;
    body.dataset.view = "";
    if (state.detail) return renderFaction(state.detail, live);
    switch (state.tab) {
      case "players": return renderPlayers();
      case "markers": return renderMarkers();
      case "factions": return renderFactions(live);
      case "nations": return renderNations(live);
      case "activity": return renderActivity();
      case "timeline": return A.renderTimeline(body, live);
      case "chat": return renderChat();
      case "leaderboard": return renderLeaderboard(live);
      case "stats": return renderStats(live);
      case "admin": return renderAdmin(live);
      default: return undefined;
    }
  };
  // Live data re-renders only the tab that shows it.
  A.renderIf = (...tabs) => { if (!state.detail && tabs.includes(state.tab)) A.render(); };

  const emptyState = A.emptyState = (icon, lead, text) => "<div class='empty'>" + A.icon(icon) + "<p class='lead'>" + esc(lead) + "</p>" + (text ? "<p>" + esc(text) + "</p>" : "") + "</div>";
  const loading = A.loading = () => "<div class='skeleton' style='width:60%'></div><div class='skeleton'></div><div class='skeleton' style='width:80%'></div>";
  const head = A.head = (title, extra) => "<div class='sec-head'><h2>" + esc(title) + "</h2>" + (extra || "") + "</div>";
  // The empty state for a request that failed. 403 means the layer is private for this viewer, 404 that the thing
  // is not there, 429 that the server asked for a pause, after which the view is drawn again while it is on screen.
  // what is the subject of the lead sentence, such as Factions or The leaderboard, and are its verb.
  const failState = (e, icon, what, are, live) => {
    const st = e && e.status;
    if (st === 403) return emptyState(icon, what + " " + are + " private", state.viewer && state.viewer.linked ? "Your access on this server does not include this part of the map." : "Link this browser to your player to see more.");
    if (st === 404) return emptyState(icon, what + " " + are + " not on this map", e.serverText || "");
    if (st === 429) {
      const ms = Math.max(1000, e.retryMs || A.busyFor() || 10000);
      if (live) setTimeout(() => { if (live()) A.render(); }, ms + 250);
      return emptyState("clock", "The map server is busy", "Trying again in " + A.plural(Math.ceil(ms / 1000), "second") + ".");
    }
    return emptyState(icon, what + " could not be loaded", "Try again in a moment.");
  };
  A.failState = failState;

  function wireFactionLinks(root) {
    root.querySelectorAll("[data-tag]").forEach((el) => {
      if (el.dataset.wired) return;
      el.dataset.wired = "1";
      const go = (e) => { e.stopPropagation(); if (A.layerOn("factions")) A.openFaction(el.dataset.tag); };
      el.addEventListener("click", go);
      el.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(e); } });
    });
  }

  // ---- players ------------------------------------------------------------
  let playerFilter = "";
  let offlineOpen = A.store.get("offlineOpen", false);
  let offlineAt = 0;
  function renderPlayers() {
    if (body.dataset.view !== "players") {
      body.dataset.view = "players";
      body.innerHTML = head("Players", "<span class='badge' id='pl-count'></span>") +
        "<input class='filter' id='pl-filter' type='search' placeholder='Filter players' aria-label='Filter players' value='" + esc(playerFilter) + "'>" +
        "<div id='pl-msg'></div><div id='pl-list' class='list'></div><div id='pl-offwrap'></div>";
      $("pl-filter").addEventListener("input", (e) => { playerFilter = e.target.value; drawPlayerList(); });
    }
    drawPlayerList();
    A.refreshAllPlayers();
  }
  // The event stream carries the current world only, so the list asks api/players for the rest and the offline list.
  A.refreshAllPlayers = (force) => {
    if (!A.layerOn("players") || (!force && Date.now() - offlineAt < 15000)) return;
    offlineAt = Date.now();
    A.api("api/players").then((d) => {
      state.others = (d.players || []).filter((p) => p && p.world !== state.world);
      state.offline = Array.isArray(d.offline) ? d.offline : [];
      A.emit("others");
    }).catch((e) => {
      offlineAt = 0;
      if (e.status === 403) { state.others = []; state.offline = []; offlineAt = Date.now(); A.emit("others"); }
    });
  };
  setInterval(() => { if (state.tab === "players" && !state.detail && A.panelOpen()) A.refreshAllPlayers(); }, 15000);
  const initial = (n) => esc(String(n || "?").charAt(0).toUpperCase());
  const playerRowSpec = {
    key: (p) => p.uuid,
    create: () => {
      const el = document.createElement("div");
      el.className = "row click";
      el.setAttribute("role", "button");
      el.tabIndex = 0;
      el.innerHTML = "<span class='avatar'></span><div class='grow'><div class='title'></div><div class='sub'></div></div>" +
        "<div class='acts'><button class='icon-btn' data-act='iso' title='View in 3D' hidden>" + A.icon("cube", "sm") + "</button>" +
        "<button class='icon-btn' data-act='locate' title='Show on the map'>" + A.icon("locate", "sm") + "</button>" +
        "<button class='icon-btn' data-act='follow' title='Follow'>" + A.icon("eye", "sm") + "</button></div>";
      return el;
    },
    update: (el, p) => {
      const av = el.firstElementChild;
      const ring = col(p.color, "var(--border-strong)");
      if (av._ring !== ring) { av.style.borderColor = ring; av._ring = ring; }
      if (av._n !== p.name) { av.innerHTML = initial(p.name); av._n = p.name; }
      const title = "<b>" + esc(p.name) + "</b> " + (p.faction ? A.facChip(p.faction, p.color, true) : "") + (p.self ? " <span class='badge accent'>you</span>" : "") + (p.hidden || p.hiddenReason ? " <span class='badge bad'>" + esc(A.hiddenText(p)) + "</span>" : "");
      const t = el.querySelector(".title");
      if (t._h !== title) { t.innerHTML = title; t._h = title; }
      const sub = A.worldLabel(p.world) + (p.x == null || p.z == null ? (p.located === false ? ", position not shared" : ", underground") : " at X " + num(p.x, 0) + " Z " + num(p.z, 0));
      const su = el.querySelector(".sub");
      if (su.textContent !== sub) su.textContent = sub;
      const label = "Show " + (p.name || "player") + " on the map, " + sub;
      if (el.getAttribute("aria-label") !== label) el.setAttribute("aria-label", label);
      const loc = el.querySelector("[data-act=locate]");
      if (loc.getAttribute("aria-label") !== "Show " + p.name + " on the map") loc.setAttribute("aria-label", "Show " + p.name + " on the map");
      // Admins may view the area around any placed player in 3D, everyone else only around themself.
      const iso = el.querySelector("[data-act=iso]");
      const isoOn = !!(A.isoOk && A.isoOk() && (A.isAdmin() || p.self) && p.x != null && p.z != null && A.worldShown(p.world));
      if (iso.hidden === isoOn) iso.hidden = !isoOn;
      if (isoOn && iso.getAttribute("aria-label") !== "View the area around " + p.name + " in 3D") iso.setAttribute("aria-label", "View the area around " + p.name + " in 3D");
      const fol = el.querySelector("[data-act=follow]");
      const on = state.follow === p.uuid;
      fol.classList.toggle("on", on);
      fol.setAttribute("aria-pressed", String(on));
      fol.setAttribute("aria-label", (on ? "Stop following " : "Follow ") + p.name);
    }
  };
  const offlineRowSpec = {
    key: (o) => o.uuid || (o.name || "") + "|" + (o.at || ""),
    create: () => {
      const el = document.createElement("div");
      el.className = "row";
      el.innerHTML = "<span class='avatar' style='opacity:.6'></span><div class='grow'><div class='title'></div><div class='sub'></div></div>";
      return el;
    },
    update: (el, o) => {
      const where = o.x != null && o.z != null;
      el.classList.toggle("click", where);
      if (where) { el.setAttribute("role", "button"); el.tabIndex = 0; el.dataset.off = (o.world || "") + "|" + o.x + "|" + o.z; }
      else { el.removeAttribute("role"); el.removeAttribute("tabindex"); delete el.dataset.off; }
      const av = el.firstElementChild;
      if (av._n !== o.name) { av.innerHTML = initial(o.name); av._n = o.name; }
      const title = esc(o.name) + " " + (o.faction ? A.facChip(o.faction, null, true) : "");
      const t = el.querySelector(".title");
      if (t._h !== title) { t.innerHTML = title; t._h = title; }
      const sub = "Last seen " + A.rel(o.at) + ", " + A.date(o.at) + " " + A.clock(o.at) + (o.world ? " in " + A.worldLabel(o.world) : "");
      const su = el.querySelector(".sub");
      if (su.textContent !== sub) su.textContent = sub;
    }
  };
  function playerListClick(e) {
    const row = e.target.closest(".row[data-key]");
    if (!row || e.target.closest(".fac")) return;
    const id = row.dataset.key;
    const act = e.target.closest("[data-act]");
    if (act && act.dataset.act === "iso") {
      const p = A.playerData(id);
      if (p && p.x != null && p.z != null) A.openIso({ world: p.world, x: p.x, z: p.z, title: p.name });
      return;
    }
    if (act && act.dataset.act === "follow") A.followPlayer(id);
    else A.locatePlayer(id);
    if (narrow() && !(act && act.dataset.act === "follow")) A.setPanel(false);
  }
  function offlineClick(e) {
    const r = e.target.closest(".row[data-off]");
    if (!r || e.target.closest(".fac")) return;
    const [w, x, z] = r.dataset.off.split("|");
    const view = { x: +x, z: +z, zoom: Math.max(A.map.getZoom(), A.geo.maxNative - 2) };
    if (w && w !== state.world) A.emit("switchWorld", { name: w, view }); else A.viewTo(view.x, view.z, view.zoom);
  }
  function drawPlayerList() {
    const box = $("pl-list");
    if (!box) return;
    const msg = $("pl-msg");
    if (!box._wired) { box._wired = true; box.addEventListener("click", playerListClick); }
    if (state.playersHidden) {
      box.textContent = "";
      msg.innerHTML = emptyState("players", "Players are private", "Link your account to see who is online.");
      $("pl-count").textContent = "";
      drawOffline("");
      return;
    }
    const q = playerFilter.trim().toLowerCase();
    const all = Array.from(state.players.values()).map((s) => s.data).concat((state.others || []).filter((p) => p.world !== state.world && !state.players.has(p.uuid)));
    all.sort((a, b) => (b.self ? 1 : 0) - (a.self ? 1 : 0) || (a.world === state.world ? 0 : 1) - (b.world === state.world ? 0 : 1) || String(a.name).localeCompare(String(b.name)));
    const list = q ? all.filter((p) => String(p.name || "").toLowerCase().includes(q) || String(p.faction || "").toLowerCase().includes(q)) : all;
    $("pl-count").textContent = A.plural(all.length, "online", "online");
    const m = !all.length ? emptyState("players", "Nobody is online", "Players show up here and on the map as soon as they join.")
      : !list.length ? "<p class='muted'>No online player matches that filter.</p>" : "";
    if (msg._h !== m) { msg.innerHTML = m; msg._h = m; }
    A.keyed(box, list, playerRowSpec, drawPlayerList);
    wireFactionLinks(box);
    drawOffline(q);
  }
  function drawOffline(q) {
    const wrap = $("pl-offwrap");
    if (!wrap) return;
    if (!A.layerOn("lastSeen") || state.playersHidden) { wrap.textContent = ""; return; }
    if (!$("pl-offline")) {
      wrap.innerHTML = "<details class='fold' id='pl-offline'" + (offlineOpen ? " open" : "") + "><summary>" + A.icon("chevron", "sm") + "Offline in the last 7 days <span class='badge' id='pl-offcount'></span></summary>" +
        "<p class='muted' id='pl-offmsg' hidden>Nobody else has been seen recently.</p><div id='pl-offlist'></div></details>";
      const det = $("pl-offline");
      det.addEventListener("toggle", () => { offlineOpen = det.open; A.store.set("offlineOpen", offlineOpen); });
      $("pl-offlist").addEventListener("click", offlineClick);
    }
    const off = (state.offline || []).filter((o) => !q || String(o.name || "").toLowerCase().includes(q));
    $("pl-offcount").textContent = String(off.length);
    $("pl-offmsg").hidden = off.length > 0;
    A.keyed($("pl-offlist"), off, offlineRowSpec, drawPlayerList);
    wireFactionLinks($("pl-offlist"));
  }
  A.on("players", A.throttle(() => { if (state.tab === "players" && !state.detail) drawPlayerList(); }, 1000));
  A.on("others", () => { if (state.tab === "players" && !state.detail) drawPlayerList(); });
  A.on("follow", () => { if (state.tab === "players" && !state.detail) drawPlayerList(); });

  // ---- markers ------------------------------------------------------------
  // Markers players placed on the shared in game map, and the viewer's own markers, homes and deaths.
  function listable(mk) { const k = A.markerKind ? A.markerKind(mk) : ""; return k === "shared" || !!(A.OWN_KINDS && A.OWN_KINDS.has(k)); }
  const MK_FILTERS = [["personal", "Mine"], ["home", "Homes"], ["death", "Deaths"], ["shared", "Player markers"]];
  const mkRank = { personal: 0, home: 1, death: 2, shared: 3 };
  const mkOff = new Set(A.store.get("markerListOff", []));
  const markerRowSpec = {
    key: (mk) => A.markerKey(mk),
    create: () => {
      const el = document.createElement("div");
      el.className = "row mk-row";
      el.innerHTML = "<span class='sym mini' aria-hidden='true'></span><div class='grow'><div class='title'></div><div class='sub'><span class='mk-by'></span><span class='mk-xyz'></span></div></div>" +
        "<button class='btn small iso-btn' data-act='iso' hidden>" + A.icon("cube", "sm") + "3D</button>" +
        "<button class='btn small' data-act='show'>" + A.icon("locate", "sm") + "Show</button>";
      return el;
    },
    update: (el, mk) => {
      const lk = A.markerLook(mk);
      const ic = el.firstElementChild;
      const sig = [lk.color, lk.ring, lk.img, lk.sym, lk.own].join("|");
      if (ic._s !== sig) {
        ic._s = sig;
        ic.className = "sym mini" + (lk.own ? " own" : "") + (lk.img ? " img" : "");
        ic.style.background = lk.color;
        ic.style.borderColor = lk.ring && !lk.own ? lk.ring : "";
        ic.style.color = A.inkOn(lk.color);
        ic.innerHTML = A.symInner(lk.sym, lk.img);
      }
      const t = el.querySelector(".title");
      if (t.textContent !== lk.title) t.textContent = lk.title;
      const by = lk.kind === "shared" ? (lk.by ? "By " + lk.by : "Shared marker") : A.MARKER_KIND[lk.kind].sub;
      const b = el.querySelector(".mk-by");
      if (b.textContent !== by) b.textContent = by;
      const xyz = A.xyzText(mk.x, mk.y, mk.z);
      const x = el.querySelector(".mk-xyz");
      if (x.textContent !== xyz) x.textContent = xyz;
      const btn = el.querySelector("[data-act=show]");
      const label = "Show " + lk.title + " on the map";
      if (btn.getAttribute("aria-label") !== label) btn.setAttribute("aria-label", label);
      const iso = el.querySelector("[data-act=iso]");
      const isoOn = !!(A.isoOk && A.isoOk());
      if (iso.hidden === isoOn) iso.hidden = !isoOn;
      if (iso.getAttribute("aria-label") !== "View " + lk.title + " in 3D") iso.setAttribute("aria-label", "View " + lk.title + " in 3D");
    }
  };
  const chipSpec = {
    key: (p) => p[0],
    create: ([k, label]) => {
      const b = document.createElement("button");
      b.className = "fchip";
      b.dataset.mk = k;
      b.innerHTML = "<span class='sw' style='background:" + A.MARKER_KIND[k].color + "'></span>" + esc(label);
      return b;
    },
    update: (b, [k]) => { const v = String(!mkOff.has(k)); if (b.getAttribute("aria-pressed") !== v) b.setAttribute("aria-pressed", v); }
  };
  function renderMarkers() {
    if (body.dataset.view !== "markers") {
      body.dataset.view = "markers";
      body.innerHTML = head("Markers", "<span class='badge' id='mk-count'></span>") +
        "<div class='chips' id='mk-chips' style='padding:0 0 10px'></div><div id='mk-msg'></div><div id='mk-list' class='list'></div><div id='mk-layers'></div>";
      $("mk-chips").addEventListener("click", (e) => {
        const b = e.target.closest("[data-mk]");
        if (!b) return;
        const k = b.dataset.mk;
        if (mkOff.has(k)) mkOff.delete(k); else mkOff.add(k);
        A.store.set("markerListOff", Array.from(mkOff));
        b.setAttribute("aria-pressed", String(!mkOff.has(k)));
        drawMarkerList();
      });
      $("mk-list").addEventListener("click", (e) => {
        const iso = e.target.closest("[data-act=iso]");
        const ir = iso && iso.closest(".row[data-key]");
        const mk = ir && state.markers.find((x) => A.markerKey(x) === ir.dataset.key);
        if (mk) { A.openIso({ marker: mk.id, x: mk.x, z: mk.z, title: A.markerLook(mk).title }); return; }
        const b = e.target.closest("[data-act=show]");
        const row = b && b.closest(".row[data-key]");
        if (!row) return;
        A.showMarker(row.dataset.key);
        if (narrow()) A.setPanel(false);
      });
      $("mk-layers").addEventListener("click", (e) => {
        const iso = e.target.closest("[data-lm3]");
        if (iso) {
          const [layer, id] = JSON.parse(iso.dataset.lm3);
          const rec = A.mlayers.get(layer);
          const m = rec && (rec.def.markers || []).find((x) => String(x.id) === id);
          if (m) { const at = A.layerMarkerAt(m); A.openIso({ marker: id, layer, autoR: m.kind !== "point", x: at && at.x, z: at && at.z, title: m.label || LM_KIND[m.kind] || "Marker" }); }
          return;
        }
        const b = e.target.closest("[data-lm]");
        if (!b) return;
        const [layer, id] = JSON.parse(b.dataset.lm);
        A.showLayerMarker(layer, id);
        if (narrow()) A.setPanel(false);
      });
    }
    drawMarkerList();
  }
  // Layer markers grouped by layer, highest priority first. A long layer lists its first rows and says how many more it holds.
  const LAYER_ROWS = 60;
  const LM_KIND = { point: "Place", polyline: "Line", polygon: "Area", circle: "Circle" };
  function drawLayerLists() {
    const box = $("mk-layers");
    if (!box) return;
    const defs = A.layerOn("layers") && A.markerLayers ? A.markerLayers().filter((d) => Array.isArray(d.markers) && d.markers.length) : [];
    let h = "";
    for (const def of defs) {
      const rows = def.markers.filter((m) => m && A.layerMarkerAt(m));
      if (!rows.length) continue;
      const off = !A.mlayerWanted(def);
      const iso = !!(A.isoOk && A.isoOk());
      h += "<h3 class='sub'>" + A.icon("layer", "xs") + esc(def.label || def.id) + " <span class='badge'>" + num(rows.length, 0) + "</span>" + (off ? "<span class='badge'>switched off</span>" : "") + "</h3>";
      h += rows.slice(0, LAYER_ROWS).map((m) => {
        const at = A.layerMarkerAt(m);
        const label = m.label ? String(m.label) : LM_KIND[m.kind] || "Marker";
        const c = A.col(m.tint, "#4a8ea4");
        return "<div class='row mk-row'><span class='sym mini' aria-hidden='true' style='background:" + c + ";color:" + A.inkOn(c) + "'>" + A.icon(m.kind === "point" ? (m.icon ? A.engineIcon(m.icon) : "pin") : m.kind === "circle" ? "target" : m.kind === "polyline" ? "ruler" : "diamond") + "</span>" +
          "<div class='grow'><div class='title'>" + esc(label) + "</div><div class='sub'><span>" + esc(LM_KIND[m.kind] || "Marker") + "</span><span class='mk-xyz'>" + esc(A.xyzText(at.x, null, at.z)) + "</span></div></div>" +
          (iso ? "<button class='btn small iso-btn' data-lm3='" + esc(JSON.stringify([def.id, String(m.id)])) + "' aria-label='" + esc("View " + label + " in 3D") + "'>" + A.icon("cube", "sm") + "3D</button>" : "") +
          "<button class='btn small' data-lm='" + esc(JSON.stringify([def.id, String(m.id)])) + "' aria-label='" + esc("Show " + label + " on the map") + "'>" + A.icon("locate", "sm") + "Show</button></div>";
      }).join("");
      if (rows.length > LAYER_ROWS) h += "<p class='muted' style='font-size:13px'>" + esc("And " + A.plural(rows.length - LAYER_ROWS, "more marker") + " on the map.") + "</p>";
    }
    if (box._h !== h) { box.innerHTML = h; box._h = h; }
  }
  function drawMarkerList() {
    const box = $("mk-list");
    if (!box || body.dataset.view !== "markers" || state.detail) return;
    drawLayerLists();
    const all = A.layerOn("markers") ? state.markers.filter(listable) : [];
    const layered = !!$("mk-layers").innerHTML;
    const kindOf = (m) => A.markerKind(m);
    const mine = A.hasOwnKinds();
    const present = MK_FILTERS.filter(([k]) => (mine || !A.OWN_KINDS.has(k)) && all.some((m) => kindOf(m) === k));
    // Filters only apply while their chips are on screen, so a kind can never be hidden with no way back.
    const filtering = present.length > 1;
    const chips = $("mk-chips");
    // The chips are keyed like the rows, so a focused chip keeps its focus when the kinds change.
    A.keyed(chips, filtering ? present : [], chipSpec, drawMarkerList);
    chips.hidden = !filtering;
    const list = all.filter((m) => !filtering || !mkOff.has(kindOf(m)))
      .sort((a, b) => mkRank[kindOf(a)] - mkRank[kindOf(b)] || (a.name ? 0 : 1) - (b.name ? 0 : 1) || String(a.name || "").localeCompare(String(b.name || "")));
    $("mk-count").textContent = all.length ? String(all.length) : "";

    let m = "";
    if (!all.length && (layered || !A.layerOn("markers"))) m = "";
    else if (!all.length) m = emptyState("pin", "No player markers yet", "Markers that players place on the shared map in game show up here." + (mine ? " Your own markers, homes and deaths show up here too, for you alone." : ""));
    else if (!list.length) m = "<p class='muted'>Every kind is switched off above.</p>";
    else if (mine && all.some((x) => A.OWN_KINDS.has(kindOf(x)))) m = "<p class='muted mk-note'>" + A.icon("eye", "xs") + "Your own markers, homes and deaths are shown to you alone.</p>";
    const msg = $("mk-msg");
    if (msg._h !== m) { msg.innerHTML = m; msg._h = m; }
    A.keyed(box, list, markerRowSpec, drawMarkerList);
  }
  A.on("markers", () => { A.buildTabs(); if (state.tab === "markers") drawMarkerList(); });
  A.on("mlayers", () => { A.buildTabs(); if (state.tab === "markers") drawMarkerList(); });
  A.on("markerIcons", () => { if (state.tab === "markers") drawMarkerList(); });
  // The 3D buttons follow what api/iso allows.
  A.on("isoCaps", () => {
    if (state.detail) return;
    if (state.tab === "markers") drawMarkerList();
    if (state.tab === "players") drawPlayerList();
  });

  // ---- factions -----------------------------------------------------------
  const factionRow = (f, i) => "<div class='row click' role='button' tabindex='0' data-open='" + esc(f.tag) + "'>" + (i != null ? "<span class='rank'>" + (i + 1) + "</span>" : "") +
    "<span class='swatch' style='background:" + col(f.color) + "'></span><div class='grow'><div class='title'><b>" + esc(f.tag) + "</b> " + esc(f.name || "") + "</div></div>" +
    "<span class='muted num'>" + num(f.value) + "</span></div>";
  function wireOpen(root) { root.querySelectorAll("[data-open]").forEach((r) => r.addEventListener("click", () => A.openFaction(r.dataset.open))); }
  A.loadFactions = () => A.api("api/factions").then((d) => { state.factions = d.factions || []; state.factionsStale = false; return state.factions; });
  function renderFactions(live) {
    body.innerHTML = head("Factions") + (state.factions.length ? "" : loading());
    const draw = () => {
      if (!live()) return;
      if (!state.factions.length) { body.innerHTML = head("Factions") + emptyState("shield", "No factions yet", "Factions show up here as soon as someone founds one."); return; }
      body.innerHTML = head("Factions", "<span class='badge'>" + state.factions.length + "</span>") + "<p class='muted' style='margin:-4px 0 8px;font-size:13px'>Ranked by value</p>" + state.factions.map(factionRow).join("");
      wireOpen(body);
    };
    if (state.factions.length) draw();
    A.loadFactions().then(draw).catch((e) => { if (live()) body.innerHTML = head("Factions") + failState(e, "shield", "Factions", "are", live); });
  }

  A.openFaction = (tag) => {
    if (!tag) return;
    state.detail = tag;
    if (!["factions", "stats", "nations"].includes(state.tab) && A.canTab("factions")) state.tab = "factions";
    A.setPanel(true);
    body.scrollTop = 0;
    A.render();
  };
  A.on("openFaction", (tag) => A.openFaction(tag));

  const nationColor = A.nationColor = (n) => n && /^#[0-9a-fA-F]{3,8}$/.test(String(n.color || "")) ? n.color : A.hashColor(n && n.tag);
  const relClass = (r) => { r = String(r || "").toLowerCase(); return r.includes("ally") ? "ally" : r.includes("enemy") || r.includes("war") ? "enemy" : r.includes("truce") ? "truce" : "neutral"; };

  function renderFaction(tag, live) {
    const back = "<button class='btn small back' id='fac-back'>" + A.icon("back", "sm") + "Back</button>";
    body.innerHTML = back + loading();
    const wireBack = () => { const b = $("fac-back"); if (b) b.addEventListener("click", () => { state.detail = null; A.render(); }); };
    wireBack();
    A.api("api/factions/" + encodeURIComponent(tag)).then((d) => {
      if (!live()) return;
      const f = d.faction || {};
      const nc = f.nation ? nationColor(f.nation) : null;
      const fc = col(f.color, A.hashColor(f.tag));
      let h = back + "<div class='fhead'" + (nc ? " style='--nation:" + esc(nc) + "'" : "") + "><h2><span class='swatch' style='background:" + fc + ";width:16px;height:16px'></span>" + A.facChip(f.tag, f.color) + " " + esc(f.name || "") + "</h2>";
      if (f.nation) h += "<button class='nation-tag' data-nation='" + esc(f.nation.tag) + "' style='background:" + esc(nc) + ";color:" + A.inkOn(nc) + "'>" + A.icon("globe", "xs") + esc(f.nation.tag) + "</button>" +
        (f.nation.name && f.nation.name !== f.nation.tag ? " <span class='muted'>" + esc(f.nation.name) + "</span>" : "");
      if (f.description) h += "<p class='muted' style='margin:8px 0 0'>" + esc(f.description) + "</p>";
      h += "</div>";
      const members = f.members;
      h += "<div class='tiles'><div class='tile'><b>" + num(f.value) + "</b><span>Value</span></div><div class='tile'><b>" + num(members) + "</b><span>Members</span></div><div class='tile'><b>" + num(f.online) + "</b><span>Online</span></div></div>";
      h += "<dl class='kv'>";
      const kv = [["Leader", f.leader], ["Rank", f.rank != null ? "#" + f.rank : null], ["Value", num(f.value)], ["Members", num(members)], ["Online", num(f.online)],
        ["Claims", num(f.claims)], ["Power", f.power != null ? num(f.power) + " of " + num(f.maxPower) : null], ["Level", num(f.level)], ["Points", num(f.points)],
        ["Kills", num(f.kills)], ["Deaths", num(f.deaths)], ["Sieges won", num(f.siegesWon)], ["Sieges lost", num(f.siegesLost)],
        ["Bank", f.bank != null ? num(f.bank) : null], ["Founded", f.createdAt ? new Date(f.createdAt).toLocaleDateString() : null]];
      for (const [k, v] of kv) if (v != null && v !== "none") h += "<dt>" + k + "</dt><dd>" + esc(v) + "</dd>";
      h += "</dl>";
      const rel = f.relations || {};
      const relKeys = Object.keys(rel);
      if (relKeys.length) {
        h += "<h3 class='sub'>Relations</h3><div class='chips' style='padding:0'>" + relKeys.map((t) => "<button class='rel " + relClass(rel[t]) + "' data-open='" + esc(t) + "'><b>" + esc(t) + "</b><small>" + esc(String(rel[t]).toLowerCase()) + "</small></button>").join("") + "</div>";
      }
      if (d.lands && d.lands.length) {
        h += "<h3 class='sub'>Land</h3>" + d.lands.map((l) => "<div class='row click land' role='button' tabindex='0' data-world='" + esc(l.world) + "' data-x='" + Number(l.x) + "' data-z='" + Number(l.z) + "'>" + A.icon("pin", "sm") +
          "<div class='grow'><div class='title'>" + esc(A.worldLabel(l.world)) + "</div><div class='sub'>Around X " + num(l.x, 0) + " Z " + num(l.z, 0) + "</div></div><span class='muted num'>" + A.plural(l.claims, "chunk") + "</span></div>").join("");
      }
      if (d.onlineMembers && d.onlineMembers.length) {
        h += "<h3 class='sub'>Online now</h3>" + d.onlineMembers.map((m) => "<div class='row click player' role='button' tabindex='0' data-name='" + esc(m.name) + "'><span class='avatar' style='border-color:" + fc + "'>" + esc(String(m.name || "?").charAt(0).toUpperCase()) + "</span><div class='grow'><div class='title'>" + esc(m.name) + "</div><div class='sub'>" + esc(A.worldLabel(m.world)) + "</div></div>" + A.icon("locate", "sm") + "</div>").join("");
      }
      if (d.sieges && d.sieges.length) {
        h += "<h3 class='sub'>Sieges</h3><ul class='feed'>" + d.sieges.map((s) => "<li class='pvp'><span class='ico'>" + A.icon("swords") + "</span><div class='body'><b>" + esc(s.attacker) + "</b> against <b>" + esc(s.defender) + "</b> <span class='badge'>" + esc(String(s.phase || "").toLowerCase()) + "</span>" +
          (s.endsAt ? "<time>Ends in <span data-ends='" + Number(s.endsAt) + "'>" + A.fmtLeft(s.endsAt - Date.now()) + "</span></time>" : "") + "</div></li>").join("") + "</ul>";
      }
      body.innerHTML = h;
      wireBack();
      wireOpen(body);
      body.querySelectorAll(".nation-tag").forEach((r) => r.addEventListener("click", () => {
        if (!A.canTab("nations")) return;
        state.detail = null; state.tab = "nations"; state.nationFocus = r.dataset.nation; A.render();
      }));
      body.querySelectorAll(".row.land").forEach((r) => r.addEventListener("click", () => {
        const view = { x: +r.dataset.x, z: +r.dataset.z, zoom: Math.max(A.map.getZoom(), A.geo.maxNative - 2) };
        if (r.dataset.world !== state.world) A.emit("switchWorld", { name: r.dataset.world, view });
        else A.viewTo(view.x, view.z, view.zoom);
        if (narrow()) A.setPanel(false);
      }));
      body.querySelectorAll(".row.player").forEach((r) => r.addEventListener("click", () => {
        const s = A.findPlayer(r.dataset.name);
        if (s) A.locatePlayer(s.data.uuid); else A.toast(r.dataset.name + " is not on the map right now");
      }));
    }).catch((e) => {
      if (!live()) return;
      body.innerHTML = back + (e.status === 404 ? emptyState("shield", "That faction is not on this map", "It may have disbanded or be hidden from you.") : failState(e, "shield", "Factions", "are", live));
      wireBack();
    });
  }

  // ---- nations ------------------------------------------------------------
  // The member list from Dominion counts factions the viewer may not see, so the larger figure wins.
  const nationSize = (n) => Math.max((n.factions || []).length, Number(n.memberCount) || 0);
  A.loadNations = () => {
    if (!A.layerOn("nations")) { state.nations = null; A.buildTabs(); return Promise.resolve(); }
    return A.api("api/nations").then((d) => { state.nations = d.nations || []; state.nationsData = d; A.buildTabs(); return d; }).catch(() => { /* keep the last list */ });
  };
  function renderNations(live) {
    body.innerHTML = head("Nations") + loading();
    A.api("api/nations").then((d) => {
      if (!live()) return;
      state.nations = d.nations || [];
      const list = d.nations || [];
      if (!list.length) { body.innerHTML = head("Nations") + emptyState("globe", "No nations yet", "Nations show up here once factions band together."); return; }
      let h = head("Nations", "<span class='badge'>" + list.length + "</span>");
      h += list.map((n) => {
        const c = nationColor(n);
        return "<div class='nation' data-nation='" + esc(n.tag) + "'><div class='nation-title' style='background:" + esc(c) + ";color:" + A.inkOn(c) + "'><b>" + esc(n.tag) + "</b><span class='grow'>" +
          esc(n.name && n.name !== n.tag ? n.name : "") + "</span><span>" + A.plural(nationSize(n), "faction") + "</span></div><div class='rows'>" +
          ((n.factions || []).length ? (n.factions || []).map((f) => factionRow(f)).join("") : "<p class='muted'>No visible factions.</p>") + "</div></div>";
      }).join("");
      const ind = d.independent || [];
      if (ind.length) h += "<h3 class='sub'>Independent factions</h3>" + ind.map((f) => factionRow(f)).join("");
      body.innerHTML = h;
      wireOpen(body);
      if (state.nationFocus) {
        const el = body.querySelector(".nation[data-nation='" + CSS.escape(state.nationFocus) + "']");
        if (el) el.scrollIntoView({ block: "start" });
        state.nationFocus = null;
      }
    }).catch((e) => { if (live()) body.innerHTML = head("Nations") + failState(e, "globe", "Nations", "are", live); });
  }

  // ---- activity -----------------------------------------------------------
  const SOURCES = [
    { key: "feed", label: "Feed", ok: () => A.layerOn("feed") },
    { key: "kills", label: "Kills", ok: () => A.layerOn("kills") },
    { key: "events", label: "World events", ok: () => A.layerOn("worldevents") }
  ];
  const actOff = new Set(A.store.get("activityOff", []));
  function timeline() {
    const items = [];
    const use = (k) => SOURCES.find((s) => s.key === k).ok() && !actOff.has(k);
    // Atlas also writes its own world event and death lines into the feed. They are skipped when the richer source is shown.
    const dup = { worldevent: "events", death: "kills" };
    const usable = (k) => SOURCES.find((s) => s.key === k).ok();
    if (use("feed")) for (const l of state.feed) if (!(l && l.source === "atlas" && dup[l.type] && usable(dup[l.type]))) items.push({ at: l.at, src: "feed", l });
    if (use("kills")) for (const k of state.kills) items.push({ at: k.at, src: "kills", k });
    // Events without a position, such as bootstrap events, are bookkeeping and stay out of the timeline.
    if (use("events")) for (const e of state.eventLog || []) if (e.located !== false && !(e.x == null && e.z == null)) items.push({ at: e.at, src: "events", e });
    items.sort((a, b) => (b.at || 0) - (a.at || 0));
    return items.slice(0, 250);
  }
  const who = (x) => x ? "<b class='pl'>" + esc(x.name || A.pretty(x.type) || "Someone") + "</b>" : "";
  function itemHtml(it) {
    const other = (w) => w && w !== state.world ? " <span class='badge'>" + esc(A.worldLabel(w)) + "</span>" : "";
    if (it.src === "feed") {
      return "<li class='feedline'><span class='ico'>" + A.icon(it.l.source === "atlas" ? (it.l.type === "worldevent" ? "bolt" : it.l.type === "death" ? "skull" : "activity") : "shield") + "</span><div class='body'>" + esc(it.l.text) + A.when(it.at) + "</div></li>";
    }
    if (it.src === "kills") {
      const k = it.k, v = k.victim || {}, kl = k.killer;
      const boss = !!v.boss, pvp = !!(v.player && kl && kl.player);
      let t;
      if (kl) t = who(kl) + " killed " + who(v) + (k.ranged ? " from range" : "");
      else t = who(v) + " died" + (k.cause ? " from " + esc(A.pretty(k.cause).toLowerCase()) : "");
      if (boss) t = "<span class='badge bad'>boss down</span> " + t;
      else if (pvp) t = "<span class='badge warn'>player versus player</span> " + t;
      return "<li class='" + (boss ? "boss" : pvp ? "pvp" : "kill") + "'><span class='ico'>" + A.icon(boss ? "boss" : pvp ? "swords" : "skull") + "</span><div class='body'>" + t + other(k.world) + A.when(it.at) + "</div></li>";
    }
    if (it.src === "events") {
      const e = it.e;
      const go = e.x != null && e.z != null && e.state !== "end" ? " <button class='btn small' data-goto='" + esc(e.world) + "|" + e.x + "|" + e.z + "'>Show</button>" : "";
      return "<li class='event'><span class='ico'>" + A.icon(e.state === "end" ? "portal" : "bolt") + "</span><div class='body'><b>" + esc(e.displayName || A.pretty(e.name)) + "</b> " +
        (e.state === "end" ? "closed" : "opened") + other(e.world) + go + A.when(it.at) + "</div></li>";
    }
    return "";
  }
  const itemKey = (it) => it.src === "feed" ? "f|" + it.at + "|" + it.l.text
    : it.src === "kills" ? "k|" + [it.k.at, it.k.victim && it.k.victim.name, it.k.victim && it.k.victim.type, it.k.killer && it.k.killer.name].join("|")
    : "e|" + it.e.id + "|" + it.e.state + "|" + it.at;
  // Rows are built from html once and only rewritten when something other than the relative time changed.
  const noTime = (h) => h.replace(/<time[\s\S]*?<\/time>/g, "");
  const htmlRowSpec = (key, html) => ({
    key,
    create: (it) => { const t = document.createElement("template"); t.innerHTML = html(it); return t.content.firstElementChild; },
    update: (el, it) => {
      const h = html(it), sig = noTime(h);
      if (el._sig === undefined) { el._sig = sig; return; }
      if (el._sig === sig) return;
      const t = document.createElement("template"); t.innerHTML = h;
      const n = t.content.firstElementChild;
      el.className = n.className; el.innerHTML = n.innerHTML; el._sig = sig;
    }
  });
  const activitySpec = htmlRowSpec(itemKey, itemHtml);
  function liveText() { return state.conn === "live" ? "Live" : "Paused"; }
  function renderActivity() {
    if (body.dataset.view !== "activity") {
      body.dataset.view = "activity";
      body.innerHTML = head("Activity", "<span class='live-pill' id='act-live'><span class='dot'></span><span class='txt'></span></span>") +
        "<div class='chips' id='act-chips' style='padding:0 0 10px'></div><div id='act-empty'></div><ul class='feed' id='act-list'></ul>";
      $("act-chips").addEventListener("click", (e) => {
        const b = e.target.closest("[data-src]");
        if (!b) return;
        const k = b.dataset.src;
        if (actOff.has(k)) actOff.delete(k); else actOff.add(k);
        A.store.set("activityOff", Array.from(actOff));
        b.setAttribute("aria-pressed", String(!actOff.has(k)));
        drawActivity();
      });
      $("act-list").addEventListener("click", (e) => {
        const b = e.target.closest("[data-goto]");
        if (!b) return;
        const [w, x, z] = b.dataset.goto.split("|");
        const view = { x: +x, z: +z, zoom: Math.max(A.map.getZoom(), A.geo.maxNative - 2) };
        if (w && w !== state.world) A.emit("switchWorld", { name: w, view }); else A.viewTo(view.x, view.z, view.zoom);
      });
    }
    drawActivity();
  }
  function drawLive() {
    const p = $("act-live");
    if (!p) return;
    p.classList.toggle("on", state.conn === "live");
    p.querySelector(".txt").textContent = liveText();
  }
  function drawActivity() {
    const list = $("act-list");
    if (!list || body.dataset.view !== "activity") return;
    drawLive();
    const srcs = SOURCES.filter((s) => s.ok());
    const chips = $("act-chips");
    const ck = srcs.length > 1 ? srcs.map((s) => s.key).join() : "";
    if (chips._k !== ck) {
      chips._k = ck;
      chips.innerHTML = srcs.length > 1 ? srcs.map((s) => "<button class='fchip' data-src='" + s.key + "' aria-pressed='" + !actOff.has(s.key) + "'>" + s.label + "</button>").join("") : "";
      chips.hidden = !ck;
    }
    const items = timeline();
    const em = items.length ? "" : emptyState("activity", "Quiet for now", state.standalone ? "Kills and world events show up here as they happen." : "Kills, world events and faction news show up here as they happen.");
    const emEl = $("act-empty");
    if (emEl._h !== em) { emEl.innerHTML = em; emEl._h = em; }
    A.keyed(list, items, activitySpec, drawActivity);
  }
  const activityRefresh = A.throttle(() => { if (!state.detail && state.tab === "activity") drawActivity(); }, 800);
  A.on("activity", activityRefresh);
  A.on("conn", drawLive);

  // ---- chat ---------------------------------------------------------------
  const chatHtml = (l) => "<li class='chat'><span class='avatar' style='width:28px;height:28px;font-size:12px'>" + initial(l.name) + "</span><div class='body'><b class='pl'>" + esc(l.name) + "</b> " +
    (l.tag ? A.facChip(l.tag) + " " : "") + "<span>" + esc(l.text) + "</span>" + A.when(l.at) + "</div></li>";
  const chatSpec = htmlRowSpec((l) => "c|" + l.at + "|" + l.name + "|" + l.text, chatHtml);
  function renderChat() {
    if (body.dataset.view !== "chat") {
      body.dataset.view = "chat";
      body.innerHTML = head("Chat") + "<div id='chat-empty'></div><ul class='feed' id='chat-list'></ul>";
    }
    const list = $("chat-list");
    const em = state.chat.length ? "" : emptyState("chat", "No chat yet", "Public chat from the server shows up here.");
    const emEl = $("chat-empty");
    if (emEl._h !== em) { emEl.innerHTML = em; emEl._h = em; }
    A.keyed(list, state.chat.slice().reverse(), chatSpec, renderChat);
    wireFactionLinks(list);
  }
  A.on("chat", A.throttle(() => { if (!state.detail && state.tab === "chat" && body.dataset.view === "chat") renderChat(); }, 500));

  // ---- leaderboard --------------------------------------------------------
  const TIER = { bronze: "var(--tier-bronze)", silver: "var(--tier-silver)", gold: "var(--tier-gold)", platinum: "var(--tier-platinum)" };
  const tierColor = (t) => TIER[String(t || "").toLowerCase()] || "var(--border-strong)";
  A.loadLeaderboard = () => A.api("api/leaderboard").then((d) => { state.leaderboard = d; return d; });
  // Rarity is the share of counted players who own a feat, from 0 to 1. Under five percent a feat counts as rare.
  const rarityOf = (d, feat, own) => typeof own === "number" ? own : d.rarity && typeof d.rarity[feat] === "number" ? d.rarity[feat] : null;
  const pct = (r) => r < 0.01 ? "under 1 percent" : Math.round(r * 100) + " percent";
  const rarityHtml = (r) => r == null ? "" : (r < 0.05 ? "<span class='badge warn'>rare</span> " : "") + "<span class='muted'>owned by " + pct(r) + " of players</span>";
  const tierChip = (t) => t ? "<span class='tier' style='background:" + tierColor(t) + "'>" + esc(t) + "</span> " : "";
  function recordsHtml(title, list, empty, nobody) {
    let h = "<h3 class='sub'>" + esc(title) + "</h3>";
    if (!list.length) return h + "<p class='muted'>" + esc(empty) + "</p>";
    return h + "<ul class='feed'>" + list.map((r) => "<li><span class='ico' style='background:" + tierColor(r.tier) + ";color:#1d1812'>" + A.icon("trophy") + "</span><div class='body'><b>" + esc(r.featName || A.pretty(r.feat)) + "</b> " + tierChip(r.tier) +
      "<div>" + (r.name ? "<b class='pl'>" + esc(r.name) + "</b>" : "<span class='muted'>" + esc(nobody) + "</span>") + "</div>" + A.when(r.at) + "</div></li>").join("") + "</ul>";
  }
  function renderLeaderboard(live) {
    const draw = (d) => {
      if (!live() || !d) return;
      const players = Array.isArray(d.players) ? d.players : [];
      if (d.available === false) {
        body.innerHTML = head("Leaderboard") + emptyState("trophy", "No leaderboard yet", "The leaderboard appears once the server runs Feats and has written its first export.");
        return;
      }
      let h = head("Leaderboard", d.updatedAt ? "<span class='muted' style='font-size:12px'>Updated " + esc(A.rel(d.updatedAt)) + "</span>" : "");
      // Feats counts the players it rates feats against. That figure is the honest player count when it is there.
      const counted = Number(d.rarityPlayers) > 0 ? Number(d.rarityPlayers) : players.length;
      h += "<div class='tiles" + (d.online != null ? " four" : "") + "'><div class='tile'><b>" + num(d.totalFeats) + "</b><span>Feats</span></div><div class='tile'><b>" + num(d.totalPoints) + "</b><span>" + (d.online != null ? "Points" : "Points to earn") + "</span></div>" +
        "<div class='tile'><b>" + num(counted) + "</b><span>Players</span></div>" + (d.online != null ? "<div class='tile'><b>" + num(d.online) + "</b><span>Online</span></div>" : "") + "</div>";
      h += "<h3 class='sub'>Top players</h3>";
      if (!players.length) h += "<p class='muted'>Nobody has unlocked a feat yet.</p>";
      const max = Math.max(1, ...players.map((p) => Number(p.points) || 0));
      const medal = ["var(--tier-gold)", "var(--tier-silver)", "var(--tier-bronze)"];
      h += players.slice(0, 100).map((p, i) => {
        const rank = Number(p.rank) > 0 ? Number(p.rank) : i + 1;
        return "<div class='row lb-row'>" + (rank <= 3 ? "<span class='medal' style='background:" + medal[rank - 1] + "'>" + rank + "</span>" : "<span class='rank' style='width:24px'>" + rank + "</span>") +
          "<div class='grow'><div class='title'><b>" + esc(p.name) + "</b> <span class='muted' style='font-size:12.5px'>" + A.plural(p.unlocked, "feat") + (d.totalFeats ? " of " + num(d.totalFeats) : "") + "</span></div>" +
          (p.lastUnlockAt ? "<div class='sub'>Last unlock " + esc(A.rel(p.lastUnlockAt)) + "</div>" : "") +
          "<div class='bar'><i style='width:" + ((Number(p.points) || 0) / max * 100).toFixed(1) + "%'></i></div></div><span class='pts'>" + num(p.points, 0) + "</span></div>";
      }).join("");
      const recent = Array.isArray(d.recent) ? d.recent.slice().sort((a, b) => (b.at || 0) - (a.at || 0)) : [];
      if (recent.length) {
        h += "<h3 class='sub'>Recent unlocks</h3><ul class='feed'>" + recent.map((r) => "<li><span class='ico' style='background:" + tierColor(r.tier) + ";color:#1d1812'>" + A.icon("trophy") + "</span><div class='body'><b class='pl'>" + esc(r.name) + "</b> unlocked <b>" + esc(r.featName || A.pretty(r.feat)) + "</b> " +
          tierChip(r.tier) + (r.hiddenFeat ? "<span class='badge'>secret</span> " : "") +
          "<span class='muted'>" + (r.points != null ? A.plural(r.points, "point") : "") + (r.category ? ", " + esc(r.category) : "") + "</span>" +
          (rarityOf(d, r.feat, r.rarity) != null ? "<div class='rar'>" + rarityHtml(rarityOf(d, r.feat, r.rarity)) + "</div>" : "") + A.when(r.at) + "</div></li>").join("") + "</ul>";
      }
      const milestones = Array.isArray(d.milestones) ? d.milestones : [];
      const firsts = Array.isArray(d.firsts) ? d.firsts : [];
      if (milestones.length) h += recordsHtml("Milestones", milestones.slice().reverse(), "", "Reached");
      if (firsts.length) h += recordsHtml("Firsts", firsts.slice().reverse(), "", "Nobody yet");
      body.innerHTML = h;
    };
    if (state.leaderboard) draw(state.leaderboard); else body.innerHTML = head("Leaderboard") + loading();
    A.loadLeaderboard().then(draw).catch((e) => { if (live() && !state.leaderboard) body.innerHTML = head("Leaderboard") + failState(e, "trophy", "The leaderboard", "is", live); });
  }

  // ---- stats --------------------------------------------------------------
  function eventsHtml(ev) {
    if (!ev) return "";
    let h = "<h3 class='sub'>Events</h3>";
    if (ev.enabled === false) return h + "<p class='muted'>Server events are switched off.</p>";
    h += "<dl class='kv'>";
    if (ev.season) h += "<dt>Season</dt><dd>" + esc(ev.season) + (ev.seasonEndsAt ? ", ends in <span data-ends='" + Number(ev.seasonEndsAt) + "'>" + A.fmtLeft(ev.seasonEndsAt - Date.now()) + "</span>" : "") + "</dd>";
    h += "<dt>Open bounties</dt><dd>" + num(ev.bounties || 0) + "</dd>";
    h += "<dt>Envoys</dt><dd>" + num((ev.envoys || []).length) + "</dd></dl>";
    const line = (icon, title, rest) => "<li class='event'><span class='ico'>" + A.icon(icon) + "</span><div class='body'><b>" + esc(title) + "</b>" + (rest ? " <span class='muted'>" + rest + "</span>" : "") + "</div></li>";
    let l = "";
    const ends = (x) => x.endsAt ? ", ends in <span data-ends='" + Number(x.endsAt) + "'>" + A.fmtLeft(x.endsAt - Date.now()) + "</span>" : "";
    for (const k of ev.koths || []) l += line("crown", "Hill " + k.name, esc(k.status || "") + (k.holder ? " held by " + A.facChip(k.holder) : "") + ends(k));
    for (const o of ev.outposts || []) l += line("tower", "Outpost " + o.name, (o.holder ? "held by " + A.facChip(o.holder) : "not held") + (o.control ? ". " + esc(o.control) : "") + ends(o));
    for (const e of ev.envoys || []) l += line("feather", "Envoy", esc(e));
    return h + (l ? "<ul class='feed'>" + l + "</ul>" : "");
  }
  function statsHtml(d) {
    // The server block tells how long the server has run, counted on from when it arrived.
    const sv = state.server && state.server.uptimeSeconds != null ? state.server.uptimeSeconds * 1000 + (Date.now() - state.serverAt) : null;
    const uptime = sv != null ? sv : d.uptimeSeconds != null ? d.uptimeSeconds * 1000 : null;
    let h = "<div class='tiles'><div class='tile'><b>" + num(d.online) + "</b><span>Online</span></div><div class='tile'><b>" + num(d.worlds) + "</b><span>Worlds</span></div><div class='tile'><b>" + esc(uptime != null ? A.fmtLeft(uptime) : "none") + "</b><span>Uptime</span></div></div>";
    h += "<dl class='kv'>";
    if (d.uptimeSeconds != null && sv != null) h += "<dt>Map uptime</dt><dd>" + esc(A.fmtUptime(d.uptimeSeconds * 1000)) + "</dd>";
    const fac = state.standalone ? null : d.factions;
    if (fac) h += "<dt>Factions</dt><dd>" + num(d.factions.count) + "</dd><dt>Claimed chunks</dt><dd>" + num(d.factions.claims) + "</dd>";
    if (d.activeSieges != null && !state.standalone) h += "<dt>Active sieges</dt><dd>" + num(d.activeSieges) + "</dd>";
    h += "</dl>";
    if (d.grace && d.grace.active && !state.standalone) h += "<div class='note'><span class='badge accent'>grace period</span> Ends in <span data-ends='" + Number(d.grace.endsAt) + "'>" + A.fmtLeft(d.grace.endsAt - Date.now()) + "</span></div>";
    if (d.eotw && !state.standalone) h += "<div class='note'><span class='badge bad'>end of the world</span> Claims are open to everyone.</div>";
    const a = d.activity || {};
    h += "<h3 class='sub'>Building activity</h3><dl class='kv'><dt>Broken in the last hour</dt><dd>" + num(a.breaksLastHour) + "</dd><dt>Placed in the last hour</dt><dd>" + num(a.placesLastHour) +
      "</dd><dt>Broken today</dt><dd>" + num(a.breaksLastDay) + "</dd><dt>Placed today</dt><dd>" + num(a.placesLastDay) + "</dd></dl>";
    const tops = [["topValue", "Most valuable"], ["topPower", "Most power"], ["topKills", "Most kills"]];
    for (const [k, label] of tops) {
      const list = fac && fac[k];
      if (!list || !list.length) continue;
      h += "<h3 class='sub'>" + label + "</h3>" + list.map((x, i) => "<div class='row click' role='button' tabindex='0' data-open='" + esc(x.tag) + "'><span class='rank'>" + (i + 1) + "</span><span class='swatch' style='background:" + col(x.color) + "'></span><div class='grow'><div class='title'><b>" +
        esc(x.tag) + "</b> " + esc(x.name) + "</div></div><span class='muted num'>" + num(x.amount) + "</span></div>").join("");
    }
    if (!state.standalone) h += eventsHtml(d.events);
    if (!state.standalone && (!d.snapshot || !d.snapshot.present)) h += "<div class='note'>Faction data has not arrived from the server yet.</div>";
    return h;
  }
  // The problems api/health names, as sentences. An unknown code is shown with its words split apart.
  const ISSUES = {
    renderBacklog: () => "The tile render queue is backed up",
    worldPollStalled: (hd) => "World details stopped updating" + (Array.isArray(hd.stalledWorlds) && hd.stalledWorlds.length ? " for " + hd.stalledWorlds.map((w) => A.worldLabel(w.world)).join(", ") : ""),
    bindRetry: () => "The web server is still trying to open its port",
    taskFailing: () => "A background task keeps failing. See the server log",
    diskWriteFailing: () => "Saving data to disk failed. See the server log",
    tileWriteFailing: () => "Saving map tiles to disk failed recently. See the server log"
  };
  const issueText = (code, hd) => ISSUES[code] ? ISSUES[code](hd) : A.pretty(code).replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase().replace(/^./, (c) => c.toUpperCase());
  // Admin health from api/health. Figures the server does not send are left out.
  function healthHtml(hd) {
    if (!hd) return "";
    const c = hd.tileCache || {};
    const mb = (b) => b == null ? "none" : num(b / 1048576, 1) + " MB";
    let h = "<h3 class='sub'>Server health</h3>";
    const issues = Array.isArray(hd.degraded) ? hd.degraded.filter((x) => typeof x === "string") : [];
    if (issues.length) h += "<div class='note'><span class='badge warn'>needs attention</span> " + issues.map((x) => esc(issueText(x, hd))).join(". ") + ".</div>";
    else if (Array.isArray(hd.degraded)) h += "<div class='note'><span class='badge good'>healthy</span> Nothing needs attention.</div>";
    h += "<dl class='kv'>" + (hd.version ? "<dt>Version</dt><dd>" + esc(hd.version) + "</dd>" : "");
    if (hd.sseClients != null) h += "<dt>Stream clients</dt><dd>" + num(hd.sseClients) + "</dd>";
    if (hd.tileCache) h += "<dt>Tile cache</dt><dd>" + mb(c.bytes) + " of " + mb(c.maxBytes) + "</dd><dt>Cached tiles</dt><dd>" + num(c.entries) + "</dd><dt>Tiles waiting</dt><dd>" + num(c.queued) + "</dd>";
    const r = hd.render || hd.renderStatus;
    if (r && typeof r === "object") {
      h += "<dt>Rendering</dt><dd>" + (r.paused ? "<span class='badge warn'>paused</span> " : "") + esc(num(r.queued, 0) + " queued, " + num(r.running, 0) + " running, " + num(r.done, 0) + " done") + "</dd>";
    }
    // Named only when the server found and honours the settings of the map mod it runs alongside.
    if (state.server && state.server.betterMap === true) h += "<dt>Map settings</dt><dd>BetterMap</dd>";
    return h + "</dl>";
  }
  function renderStats(live) {
    body.innerHTML = head("Stats") + loading();
    const admin = A.isAdmin();
    Promise.all([A.api("api/stats"), admin ? A.api("api/health").catch(() => null) : Promise.resolve(null)]).then(([d, hd]) => {
      if (!live()) return;
      body.innerHTML = head("Stats") + statsHtml(d) + healthHtml(hd);
      wireOpen(body);
      wireFactionLinks(body);
    }).catch((e) => { if (live()) body.innerHTML = head("Stats") + failState(e, "stats", "Stats", "are", live); });
  }

  // ---- admin --------------------------------------------------------------
  const LAYER_NAMES = {
    terrain: "Terrain", territories: "Territories", players: "Players", points: "Points", zones: "Zone names", heat: "Activity heat",
    paths: "Player paths", chat: "Chat", factions: "Factions", stats: "Stats", feed: "Faction feed", events: "Server events",
    nations: "Nations", markers: "Map markers", worldevents: "World events", kills: "Kills and deaths", leaderboard: "Leaderboard", lastSeen: "Last seen",
    layers: "Marker layers", timeline: "Timeline"
  };
  // Layers that only mean something with faction data are left out of the readout on a standalone server.
  const FACTION_LAYERS = new Set(["territories", "factions", "nations"]);
  A.layerName = (k) => k === "feed" && state.standalone ? "Feed" : LAYER_NAMES[k] || A.pretty(k).replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^./, (c) => c.toUpperCase());
  function renderAdmin(live) {
    const v = state.viewer || {};
    const s = state.server || {};
    const t = state.tile || {};
    let h = head("Admin");
    const viewAs = !state.standalone || v.viewingAs;
    if (viewAs) h += "<h3 class='sub'>View as a faction</h3><p class='muted' style='font-size:13px;margin:0 0 8px'>See the map exactly as a member of that faction sees it.</p>" +
      "<select id='view-as' class='filter' aria-label='View as faction'><option value=''>View as admin</option></select>";
    if (v.viewingAs) h += "<div class='note'>You are viewing the map as " + esc(v.faction) + ". Pick View as admin to go back.</div>";
    h += "<h3 class='sub'>Server</h3><dl class='kv'>";
    if (s.version) h += "<dt>Version</dt><dd>" + esc(s.version) + "</dd>";
    h += "<dt>Title</dt><dd>" + esc(state.title) + "</dd>";
    h += "<dt>Public address</dt><dd>" + (s.publicUrl ? esc(s.publicUrl) : "<span class='badge warn'>not set</span>") + "</dd>";
    if (!state.standalone) h += "<dt>Dominion</dt><dd>connected</dd>";
    h += "<dt>Worlds</dt><dd>" + num(state.worlds.length) + "</dd>";
    const z0 = t.minZoom != null ? t.minZoom : 0, z1 = t.maxZoom != null ? t.maxZoom : 4;
    h += "<dt>Tile zoom levels</dt><dd>" + esc(z0 + " to " + z1) + "</dd></dl>";
    const layers = v.layers || {};
    const keys = Object.keys(layers).filter((k) => !(state.standalone && FACTION_LAYERS.has(k))).sort((a, b) => A.layerName(a).localeCompare(A.layerName(b)));
    if (keys.length) h += "<h3 class='sub'>Layers for this view</h3><div class='chips' style='padding:0'>" + keys.map((k) => "<span class='badge " + (layers[k] ? "good" : "") + "' title='" + esc(k) + "'>" + esc(A.layerName(k)) + (layers[k] ? "" : " hidden") + "</span>").join("") + "</div>";
    h += "<div id='admin-health'></div>";
    body.innerHTML = h;
    const sel = $("view-as");
    if (sel) A.api("api/factions").then((d) => {
      if (!live()) return;
      const tags = (d.factions || []).map((f) => f.tag).filter(Boolean);
      sel.innerHTML = "<option value=''>View as admin</option>" + tags.map((x) => "<option value='" + esc(x) + "'>View as " + esc(x) + "</option>").join("");
      sel.value = state.asTag;
      if (!tags.length) sel.insertAdjacentHTML("afterend", "<p class='muted' style='font-size:13px'>There are no factions to view as.</p>");
    }).catch(() => { /* the picker keeps the admin entry */ });
    if (sel) sel.addEventListener("change", () => A.emit("viewAs", sel.value));
    if (A.isAdmin()) A.api("api/health").then((hd) => { if (live() && $("admin-health")) $("admin-health").innerHTML = healthHtml(hd); }).catch(() => { /* health is optional */ });
  }

  // Relative times refresh on their own.
  setInterval(() => {
    body.querySelectorAll("time[data-at]").forEach((el) => {
      const at = Number(el.dataset.at);
      el.innerHTML = esc(A.rel(at)) + " <span class='faint'>" + esc(A.stamp(at)) + "</span>";
    });
  }, 30000);
})();
