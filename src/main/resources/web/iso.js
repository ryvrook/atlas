/* Atlas 3D views. The View in 3D lightbox with pan and zoom, four directions and a radius picker, the check of
   api/iso that hides the action, the share link, and the daily views of the timeline with a compare slider.
   Loaded after timeline.js. */
(function () {
  "use strict";

  const A = window.Atlas;
  if (!A || A.noLeaflet || !A.map) return;
  const { $, esc, state } = A;

  // Directions as the server numbers them. 0 looks from the south east, then a quarter turn each.
  const AZ = [[0, "SE", "south east"], [90, "SW", "south west"], [180, "NW", "north west"], [270, "NE", "north east"]];
  const RADII = [2, 4, 8, 16];
  const ISO = A.iso = { caps: null, loaded: false };

  // ---- what the server allows ---------------------------------------------------
  // api/iso is asked on every load of the world list and again when the viewer changes. The action shows only
  // while views are on, the viewer may see them, and the terrain and iso layers both allow it.
  let capsSeq = 0;
  A.loadIsoCaps = () => {
    const seq = ++capsSeq;
    return A.api("api/iso").then((d) => {
      if (seq !== capsSeq) return;
      ISO.caps = d && typeof d === "object" ? d : null;
      ISO.loaded = true;
      A.emit("isoCaps");
      wantFlush();
    }).catch(() => {
      if (seq !== capsSeq) return;
      ISO.caps = null;
      ISO.loaded = true;
      A.emit("isoCaps");
      wantFlush();
    });
  };
  A.isoOk = () => {
    const c = ISO.caps;
    return !!(c && c.enabled && c.visible) && state.layers.terrain !== false && state.layers.iso !== false;
  };
  const maxR = () => { const m = Number(ISO.caps && ISO.caps.maxRadius); return m >= 1 ? Math.min(16, Math.floor(m)) : 16; };
  const radii = () => { const l = RADII.filter((r) => r <= maxR()); return l.length ? l : [maxR()]; };
  const fitR = (r) => { const l = radii(); return l.includes(r) ? r : l.reduce((b, x) => Math.abs(x - r) < Math.abs(b - r) ? x : b, l[0]); };
  const wantScale = () => { const s = Number(ISO.caps && ISO.caps.defaultScale); return s >= 1 && s <= 3 ? s : 2; };
  const azOf = (a) => AZ.find((x) => x[0] === Number(a)) || AZ[0];

  // ---- view and request state -----------------------------------------------------
  // view is what was asked for. drawn is the X-Atlas-Iso header of the image on screen, the view as the server
  // drew it after clamping, and every share link is built from it.
  let view = null;
  let drawn = null;
  let reqSeq = 0, pollTimer = null, tickTimer = null, ctrl = null, opener = null;
  // Recent images as blob addresses, so turning back to a direction seen a moment ago is instant.
  const cache = new Map();
  let shownBlob = null;
  const CACHE_KEEP = 6;
  function cachePut(url, ent) {
    cache.delete(url);
    cache.set(url, ent);
    while (cache.size > CACHE_KEEP) {
      const [k, old] = cache.entries().next().value;
      cache.delete(k);
      if (old.url !== shownBlob) URL.revokeObjectURL(old.url);
    }
  }
  const parseHdr = (v) => {
    const p = String(v || "").split(",");
    if (p.length < 6) return null;
    const n = p.slice(-5).map(Number);
    if (!n.every((x) => isFinite(x))) return null;
    return { world: p.slice(0, -5).join(","), x: n[0], z: n[1], r: n[2], az: n[3], s: n[4], raw: String(v) };
  };
  const enc = encodeURIComponent;
  function isoUrl(v) {
    const q = new URLSearchParams();
    let path;
    if (v.marker != null) {
      path = "api/iso/" + enc(v.world) + "/marker/" + enc(v.marker);
      if (v.layer) q.set("layer", v.layer);
      if (!v.autoR) q.set("r", String(v.r));
    } else {
      path = "api/iso/" + enc(v.world);
      q.set("x", String(Math.round(v.x)));
      q.set("z", String(Math.round(v.z)));
      q.set("r", String(v.r));
    }
    q.set("az", String(v.az));
    q.set("s", String(v.s));
    if (state.asTag) q.set("as", state.asTag);
    return path + "?" + q.toString();
  }
  const retrySecs = (r) => {
    const v = Number(r.headers.get("Retry-After"));
    return isFinite(v) && v > 0 ? Math.min(300, Math.ceil(v)) : 10;
  };

  // ---- the lightbox ----------------------------------------------------------------
  let dlg, stage, layA, layB, imgA, imgB, divider, stateEl, noteEl, titleEl, subEl, azBox, rBox, cmpSel, dl, shareBtn, liveBtn, tagL, tagR;
  function build() {
    if (dlg) return;
    dlg = document.createElement("dialog");
    dlg.id = "iso-dialog";
    dlg.className = "iso-dialog";
    dlg.setAttribute("aria-labelledby", "iso-title");
    dlg.setAttribute("aria-describedby", "iso-sub");
    dlg.innerHTML = "<div class='iso-head'><span class='iso-mark' aria-hidden='true'>" + A.icon("cube") + "</span><div class='grow'><h2 id='iso-title'></h2><div id='iso-sub' class='iso-sub'></div></div>" +
      "<button class='icon-btn' id='iso-close' aria-label='Close the 3D view' title='Close (Esc)'>" + A.icon("close") + "</button></div>" +
      "<div class='iso-stage' id='iso-stage' tabindex='0' role='group' aria-roledescription='3D view' aria-label='3D view. Drag to move it, scroll or pinch to zoom, left and right arrows turn it, 0 fits it.'>" +
      "<div class='iso-layer' id='iso-la'><img id='iso-img' alt='' draggable='false'></div>" +
      "<div class='iso-layer iso-top' id='iso-lb' hidden><img id='iso-img2' alt='' draggable='false'></div>" +
      "<span class='iso-tag left' id='iso-tag-l' hidden></span><span class='iso-tag right' id='iso-tag-r' hidden></span>" +
      "<div class='iso-divider' id='iso-div' role='slider' tabindex='0' aria-label='Compare divider, the older day is on the left' aria-valuemin='0' aria-valuemax='100' hidden><span class='iso-knob' aria-hidden='true'>" + A.icon("back", "sm") + A.icon("next", "sm") + "</span></div>" +
      "<div class='iso-state' id='iso-state' role='status' aria-live='polite' hidden></div></div>" +
      "<div class='iso-note' id='iso-note' hidden></div>" +
      "<div class='iso-bar'>" +
      "<div class='seg' id='iso-az' role='group' aria-label='Direction to look from'>" + AZ.map((a) => "<button data-az='" + a[0] + "' aria-label='Look from the " + a[2] + "' title='From the " + a[2] + "'>" + a[1] + "</button>").join("") + "</div>" +
      "<div class='seg' id='iso-r' role='group' aria-label='Radius in chunks'></div>" +
      "<select id='iso-cmp' class='tl-select' aria-label='Compare with another day' hidden></select>" +
      "<button class='btn small' id='iso-live' hidden>" + A.icon("cube", "sm") + "Live view</button>" +
      "<span class='spacer'></span>" +
      "<div class='seg' role='group' aria-label='Zoom'><button id='iso-out' aria-label='Zoom out' title='Zoom out (minus)'>" + A.icon("minus", "sm") + "</button>" +
      "<button id='iso-fit' aria-label='Fit the view' title='Fit (0)'>" + A.icon("fit", "sm") + "</button>" +
      "<button id='iso-in' aria-label='Zoom in' title='Zoom in (plus)'>" + A.icon("plus", "sm") + "</button></div>" +
      "<a class='btn small' id='iso-dl' role='button' aria-disabled='true' aria-label='Download the image' title='Download the image'>" + A.icon("download", "sm") + "<span class='lbl'>Download</span></a>" +
      "<button class='btn small' id='iso-share' aria-label='Copy a link to this 3D view' title='Copy a link to this 3D view'>" + A.icon("link", "sm") + "<span class='lbl'>Copy link</span></button></div>";
    document.body.appendChild(dlg);
    stage = $("iso-stage"); layA = $("iso-la"); layB = $("iso-lb"); imgA = $("iso-img"); imgB = $("iso-img2");
    divider = $("iso-div"); stateEl = $("iso-state"); noteEl = $("iso-note"); titleEl = $("iso-title"); subEl = $("iso-sub");
    azBox = $("iso-az"); rBox = $("iso-r"); cmpSel = $("iso-cmp"); dl = $("iso-dl"); shareBtn = $("iso-share"); liveBtn = $("iso-live");
    tagL = $("iso-tag-l"); tagR = $("iso-tag-r");
    $("iso-close").addEventListener("click", () => dlg.close());
    dlg.addEventListener("close", onClose);
    // A click on the dimmed backdrop lands on the dialog itself and closes it.
    dlg.addEventListener("click", (e) => { if (e.target === dlg) dlg.close(); });
    azBox.addEventListener("click", (e) => { const b = e.target.closest("[data-az]"); if (b) turnTo(Number(b.dataset.az)); });
    rBox.addEventListener("click", (e) => { const b = e.target.closest("[data-r]"); if (b) setRadius(Number(b.dataset.r)); });
    cmpSel.addEventListener("change", () => setCompare(cmpSel.value || null));
    liveBtn.addEventListener("click", () => { const v = view; if (v && v.mode === "day") A.openIso({ world: v.world, x: v.x, z: v.z, r: v.radius, title: v.title }); });
    $("iso-out").addEventListener("click", () => zoomAt(1 / 1.5));
    $("iso-in").addEventListener("click", () => zoomAt(1.5));
    $("iso-fit").addEventListener("click", fit);
    shareBtn.addEventListener("click", () => { const u = shareUrl(); if (u) A.copy(u, "Link to this 3D view"); });
    dl.addEventListener("click", (e) => { if (dl.getAttribute("aria-disabled") === "true") e.preventDefault(); });
    dlg.addEventListener("keydown", onKey);
    wireStage();
    imgA.addEventListener("load", () => { if (imgA._want && imgA.src === imgA._want) imageReady(); });
    imgA.addEventListener("error", () => { if (imgA._want && imgA.src === imgA._want) imageFailed(); });
    imgB.addEventListener("error", () => { if (view && view.mode === "day" && view.cmp) setState("error", "That day could not be loaded", "Try another day.", null); });
    window.addEventListener("resize", () => { if (dlg.open) { if (pz.touched) { clampPan(); apply(); } else fit(); } });
  }

  function show() {
    if (!dlg.open) {
      opener = document.activeElement;
      if (dlg.showModal) dlg.showModal(); else dlg.setAttribute("open", "");
      document.body.classList.add("iso-on");
    }
    setTimeout(() => stage.focus({ preventScroll: true }), 0);
  }
  function onClose() {
    reqSeq++;
    clearTimeout(pollTimer); clearInterval(tickTimer);
    if (ctrl) { try { ctrl.abort(); } catch (e) { /* already done */ } ctrl = null; }
    ptrs.clear(); pinch = null; drag = null;
    view = null; drawn = null;
    document.body.classList.remove("iso-on");
    A.saveUrl();
    // Focus goes back where it came from, or to the map when that was a card that has closed since.
    const back = opener && opener.isConnected && opener.focus ? opener : $("map");
    try { back.focus({ preventScroll: true }); } catch (e) { /* focus is optional */ }
    opener = null;
  }

  // ---- opening ---------------------------------------------------------------------
  // o has world, x and z, and marker with an optional layer for a marker the server should look up. A marker
  // that is an area, a line or a circle leaves the radius to the server so the view covers it.
  A.openIso = (o) => {
    if (!o) return;
    if (!A.isoOk()) { A.toast("3D views are not available on this map"); return; }
    build();
    const marker = o.marker != null ? String(o.marker) : null;
    view = {
      mode: "live", world: o.world || state.world, x: Number(o.x) || 0, z: Number(o.z) || 0,
      r: fitR(o.r != null && isFinite(Number(o.r)) ? Number(o.r) : 4), az: azOf(o.az)[0],
      s: o.s >= 1 && o.s <= 3 ? Number(o.s) : wantScale(), marker, layer: marker != null && o.layer ? String(o.layer) : null,
      autoR: !!o.autoR && marker != null, title: o.title ? String(o.title) : ""
    };
    drawn = null;
    resetImages();
    show();
    syncControls();
    load();
  };
  // A daily view from the timeline. o has world, label, date, file, x, z, radius and days, the other days that
  // hold a view with the same label, for the compare slider.
  A.openIsoDay = (o) => {
    if (!o || !o.file) return;
    build();
    view = Object.assign({ mode: "day", cmp: null, days: [] }, o);
    drawn = null;
    resetImages();
    show();
    syncControls();
    setCompare(null);
  };

  // ---- loading a live view -----------------------------------------------------------
  // A fetch rather than an img, so the 202 answer can be read. While the view is queued the request is sent again
  // after etaSeconds, at least one second apart. A 429 or 503 offers a retry once its Retry-After has passed.
  function load() {
    if (!view || view.mode !== "live") return;
    const seq = ++reqSeq;
    clearTimeout(pollTimer); clearInterval(tickTimer);
    if (ctrl) { try { ctrl.abort(); } catch (e) { /* already done */ } }
    ctrl = window.AbortController ? new AbortController() : null;
    const url = isoUrl(view);
    const hit = cache.get(url);
    if (hit) { cachePut(url, hit); showEntry(hit); return; }
    setState("loading", "Loading the view", "", null);
    fetch(url, { credentials: "same-origin", headers: { Accept: "image/png, application/json" }, signal: ctrl ? ctrl.signal : undefined }).then((r) => {
      if (seq !== reqSeq) return null;
      const hdr = parseHdr(r.headers.get("X-Atlas-Iso"));
      if (r.status === 200) {
        return r.blob().then((b) => {
          if (seq !== reqSeq) return;
          const ent = { url: URL.createObjectURL(b), hdr, partial: r.headers.get("X-Atlas-Partial") === "1", bytes: b.size };
          // A partial view is drawn again later, so it is never kept.
          if (!ent.partial) cachePut(url, ent);
          showEntry(ent);
        });
      }
      if (r.status === 202) {
        return r.json().catch(() => ({})).then((d) => {
          if (seq !== reqSeq) return;
          const eta = Math.max(1, Math.min(120, Math.round(Number(d && d.etaSeconds) || 1)));
          queued(eta, seq);
        });
      }
      return r.json().catch(() => null).then((b) => { if (seq === reqSeq) failed(r.status, b && typeof b.error === "string" ? b.error : "", retrySecs(r)); });
    }).catch((e) => {
      if (seq !== reqSeq || (e && e.name === "AbortError")) return;
      if (e && e.status == null && !(e instanceof TypeError)) { A.reportBug(e); return; }
      failed(0, "", 0);
    });
  }
  function queued(eta, seq) {
    let left = eta;
    const say = () => setState("queued", "Drawing this view", left > 1 ? "Ready in about " + A.plural(left, "second") + ". Large views take up to a minute." : "Almost ready.", null);
    say();
    tickTimer = setInterval(() => { if (seq !== reqSeq) { clearInterval(tickTimer); return; } left = Math.max(1, left - 1); say(); }, 1000);
    pollTimer = setTimeout(() => { if (seq === reqSeq) load(); }, eta * 1000);
  }
  function failed(status, text, wait) {
    const lead = status === 404 ? "Nothing explored here"
      : status === 403 ? "3D views are private"
      : status === 429 ? "Too many views at once"
      : status === 503 ? "This view could not be drawn"
      : status === 400 ? "That view is not possible"
      : status ? "This view could not be loaded" : "The map server could not be reached";
    const body = status === 404 ? (text || "Nobody has explored this area yet, so there is nothing to draw.")
      : status === 403 ? "Your access on this server does not include 3D views."
      : text || (status ? "Try again in a moment." : "Check the connection and try again.");
    const retry = status !== 404 && status !== 403 && status !== 400;
    setState("error", lead, body, retry ? wait : null);
  }
  function showEntry(ent) {
    clearInterval(tickTimer);
    drawn = ent.hdr || { world: view.world, x: view.x, z: view.z, r: view.r, az: view.az, s: view.s, raw: "" };
    // The first answer of a marker names the place and radius, and later turns and radii keep them.
    if (view.marker != null) view.autoR = false;
    view.r = drawn.r; view.az = drawn.az;
    view.x = drawn.x; view.z = drawn.z;
    view.partial = ent.partial;
    shownBlob = ent.url;
    setImage(ent.url);
    dl.href = ent.url;
    dl.download = fileName();
    dl.setAttribute("aria-disabled", "false");
    syncControls();
    A.saveUrl();
  }

  // ---- images on the stage -------------------------------------------------------------
  function resetImages() {
    imgA._want = ""; imgA.removeAttribute("src");
    imgB.removeAttribute("src"); layB.hidden = true; divider.hidden = true; tagL.hidden = true; tagR.hidden = true;
    pz.w = 0; pz.h = 0; pz.touched = false;
    stage.classList.remove("has-img", "busy");
    noteEl.hidden = true;
    dl.removeAttribute("href"); dl.setAttribute("aria-disabled", "true");
    if (cmpSel) { cmpSel.value = ""; }
  }
  // The img reports its address in full, so the wanted one is kept the same way to tell a late answer apart.
  function setImage(url) {
    const full = new URL(url, location.href).href;
    imgA._want = full;
    if (imgA.src === full && imgA.complete && imgA.naturalWidth) { imageReady(); return; }
    imgA.src = full;
  }
  function imageReady() {
    const w = imgA.naturalWidth, h = imgA.naturalHeight;
    const changed = w !== pz.w || h !== pz.h;
    pz.w = w; pz.h = h;
    stage.classList.add("has-img");
    if (changed || !pz.touched) fit(); else apply();
    setState(null);
    syncNote();
  }
  function imageFailed() {
    if (view && view.mode === "day") setState("error", "This view could not be loaded", "The image of that day is missing or private.", null);
    else setState("error", "This view could not be shown", "Try again in a moment.", 0);
  }

  // The overlay on the stage for loading, queued and failed requests. retry is the number of seconds before
  // the Try again button works, or null for no button.
  let retryTimer = null;
  function setState(kind, lead, text, retry) {
    clearInterval(retryTimer);
    stage.classList.toggle("busy", !!kind);
    if (!kind) { stateEl.hidden = true; stateEl.innerHTML = ""; return; }
    const spin = kind === "loading" || kind === "queued";
    stateEl.className = "iso-state " + kind;
    stateEl.innerHTML = (spin ? "<span class='iso-spin' aria-hidden='true'></span>" : A.icon(kind === "error" ? "alert" : "cube")) +
      "<p class='lead'>" + esc(lead) + "</p>" + (text ? "<p>" + esc(text) + "</p>" : "") +
      (retry != null ? "<button class='btn small' id='iso-retry'>Try again</button>" : "");
    stateEl.hidden = false;
    const b = $("iso-retry");
    if (!b) return;
    b.addEventListener("click", () => load());
    let left = Math.ceil(retry || 0);
    const tick = () => {
      b.disabled = left > 0;
      b.textContent = left > 0 ? "Try again in " + left + " s" : "Try again";
      left--;
      if (left < 0) clearInterval(retryTimer);
    };
    tick();
    if (left >= 0) retryTimer = setInterval(tick, 1000);
  }
  // Small notes under the stage. A partial view and a scale the server lowered for a large radius.
  function syncNote() {
    const lines = [];
    if (view && view.mode === "live") {
      if (view.partial) lines.push("<span class='badge warn'>partial</span> Some chunks could not be read in time, so parts of this view are missing. <button class='btn small' id='iso-again'>Try again</button>");
      if (drawn && drawn.s < view.s) lines.push("<span class='badge'>scale " + esc(drawn.s) + "</span> Drawn at a smaller scale, the most a radius of " + esc(drawn.r) + " chunks allows.");
    }
    noteEl.innerHTML = lines.map((l) => "<div>" + l + "</div>").join("");
    noteEl.hidden = !lines.length;
    const again = $("iso-again");
    if (again) again.addEventListener("click", () => load());
  }

  // ---- controls ------------------------------------------------------------------------
  function syncControls() {
    if (!view) return;
    const live = view.mode === "live";
    azBox.hidden = !live;
    rBox.hidden = !live;
    shareBtn.hidden = !live;
    shareBtn.disabled = !drawn;
    cmpSel.hidden = live || !(view.days && view.days.length);
    liveBtn.hidden = live || !A.isoOk() || view.x == null;
    if (live) {
      const t = view.title || "This spot";
      if (titleEl.textContent !== t) titleEl.textContent = t;
      // The buttons show what was asked for, so a click answers at once while the view loads.
      const az = view.az;
      const r = view.autoR && !drawn ? -1 : view.r;
      const x = drawn ? drawn.x : view.x, z = drawn ? drawn.z : view.z;
      subEl.textContent = !drawn && view.autoR ? "Sized to cover the whole marker, seen from the " + azOf(az)[2]
        : "X " + Math.round(x) + "  Z " + Math.round(z) + ", " + A.plural(r, "chunk") + " each way, seen from the " + azOf(az)[2];
      azBox.querySelectorAll("[data-az]").forEach((b) => b.setAttribute("aria-pressed", String(Number(b.dataset.az) === Number(az))));
      const list = radii();
      const sig = list.join();
      if (rBox._sig !== sig) {
        rBox._sig = sig;
        rBox.innerHTML = list.map((n) => "<button data-r='" + n + "' aria-label='Radius " + n + " chunks' title='" + (2 * n + 1) + " by " + (2 * n + 1) + " chunks'>" + n + "</button>").join("");
      }
      rBox.querySelectorAll("[data-r]").forEach((b) => b.setAttribute("aria-pressed", String(Number(b.dataset.r) === Number(r))));
    } else {
      titleEl.textContent = view.title || A.pretty(view.label);
      subEl.textContent = (A.dayName ? A.dayName(view.date, true) : view.date) + (view.radius != null ? ", " + A.plural(view.radius, "chunk") + " each way" : "") + (view.x != null ? ", around X " + Math.round(view.x) + "  Z " + Math.round(view.z) : "");
      const sig = (view.days || []).map((d) => d.date).join();
      if (cmpSel._sig !== sig) {
        cmpSel._sig = sig;
        cmpSel.innerHTML = "<option value=''>Compare with a day</option>" + (view.days || []).map((d) => "<option value='" + esc(d.date) + "'>With " + esc(A.dayName ? A.dayName(d.date) : d.date) + "</option>").join("");
      }
      cmpSel.value = view.cmp || "";
      dl.href = view.file;
      dl.download = fileName();
      dl.setAttribute("aria-disabled", "false");
    }
  }
  function turnTo(az) {
    if (!view || view.mode !== "live" || az === view.az) return;
    view.az = az;
    syncControls();
    load();
  }
  function turn(dir) {
    if (!view || view.mode !== "live") return;
    const i = AZ.findIndex((a) => a[0] === view.az);
    turnTo(AZ[(i + dir + 4) % 4][0]);
  }
  function setRadius(r) {
    if (!view || view.mode !== "live") return;
    r = fitR(r);
    if (r === view.r && !view.autoR) return;
    view.r = r;
    view.autoR = false;
    syncControls();
    load();
  }
  const safe = (s) => String(s == null ? "" : s).replace(/[^A-Za-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60) || "map";
  function fileName() {
    if (!view) return "atlas-3d.png";
    if (view.mode === "day") return "atlas-3d-" + safe(view.world) + "-" + safe(view.label) + "-" + safe(view.cmp ? [view.date, view.cmp].sort().join("-") : view.date) + ".png";
    const d = drawn || view;
    return "atlas-3d-" + safe(d.world) + "-" + Math.round(d.x) + "-" + Math.round(d.z) + "-r" + d.r + "-" + azOf(d.az)[1].toLowerCase() + ".png";
  }

  // ---- share link ------------------------------------------------------------------------
  // The link names the view as drawn, iso=world,x,z,r,az,s, so it always opens the same cached image.
  A.isoParam = () => {
    if (!view || view.mode !== "live" || !drawn) return "";
    return [enc(drawn.world), drawn.x, drawn.z, drawn.r, drawn.az, drawn.s].join(",");
  };
  function shareUrl() {
    const p = A.isoParam();
    if (!p) return "";
    const zoom = drawn.world === state.world ? A.map.getZoom() : A.defaultZoom();
    const q = new URLSearchParams({ world: drawn.world, x: String(Math.round(drawn.x)), z: String(Math.round(drawn.z)), zoom: String(zoom) });
    return location.origin + location.pathname + "?" + q.toString() + "&iso=" + p;
  }
  // A link with iso opens that view once the server has said whether views are allowed.
  let want = null;
  A.isoWant = (s) => {
    const h = parseHdr(s);
    if (!h || !h.world) return;
    want = h;
    wantFlush();
  };
  function wantFlush() {
    if (!want || !ISO.loaded || !state.world) return;
    const w = want;
    want = null;
    if (!A.isoOk()) { A.toast("3D views are not available on this map"); return; }
    if (A.worldShown && !A.worldShown(w.world)) { A.toast("That 3D view is of a world this map does not show"); return; }
    A.openIso({ world: w.world, x: w.x, z: w.z, r: w.r, az: w.az, s: w.s });
  }

  // ---- compare two days ---------------------------------------------------------------------
  // The older day sits on the left of the divider and the newer one on the right. Both images share one pan and zoom.
  let cmpX = 0.5;
  function setCompare(date) {
    if (!view || view.mode !== "day") return;
    const other = date ? (view.days || []).find((d) => d.date === date) : null;
    view.cmp = other ? other.date : null;
    cmpSel.value = view.cmp || "";
    setState("loading", "Loading the view", "", null);
    if (!other) {
      layB.hidden = true; divider.hidden = true; tagL.hidden = true; tagR.hidden = true;
      imgB.removeAttribute("src");
      setImage(view.file);
    } else {
      const pair = [{ date: view.date, file: view.file }, other].sort((a, b) => a.date < b.date ? -1 : 1);
      imgB.src = pair[0].file;
      setImage(pair[1].file);
      tagL.textContent = A.dayName ? A.dayName(pair[0].date) : pair[0].date;
      tagR.textContent = A.dayName ? A.dayName(pair[1].date) : pair[1].date;
      layB.hidden = false; divider.hidden = false; tagL.hidden = false; tagR.hidden = false;
      divider.setAttribute("aria-label", "Compare divider, " + tagL.textContent + " on the left and " + tagR.textContent + " on the right");
      placeDivider(cmpX);
    }
    dl.download = fileName();
  }
  function placeDivider(x) {
    cmpX = Math.max(0, Math.min(1, x));
    const pct = Math.round(cmpX * 1000) / 10;
    layB.style.clipPath = "inset(0 " + (100 - pct) + "% 0 0)";
    divider.style.left = pct + "%";
    divider.setAttribute("aria-valuenow", String(Math.round(pct)));
    divider.setAttribute("aria-valuetext", Math.round(pct) + " percent of the older day shown");
  }

  // ---- pan and zoom ---------------------------------------------------------------------------
  // CSS transforms only. k is the scale and x, y the offset of the image's top left corner on the stage.
  const pz = { k: 1, x: 0, y: 0, w: 0, h: 0, touched: false };
  const box = () => stage.getBoundingClientRect();
  const fitK = () => { const r = box(); return pz.w && r.width ? Math.min(2, Math.min(r.width / pz.w, r.height / pz.h) * 0.94) : 1; };
  function apply() {
    const t = "translate(" + pz.x.toFixed(1) + "px," + pz.y.toFixed(1) + "px) scale(" + pz.k.toFixed(4) + ")";
    imgA.style.transform = t;
    imgB.style.transform = t;
    stage.classList.toggle("crisp", pz.k >= 1.5);
    $("iso-in").disabled = pz.k >= 8 - 1e-6;
    $("iso-out").disabled = pz.k <= fitK() * 0.5 + 1e-6;
  }
  function fit() {
    const r = box();
    if (!pz.w || !r.width) return;
    pz.k = fitK();
    pz.x = (r.width - pz.w * pz.k) / 2;
    pz.y = (r.height - pz.h * pz.k) / 2;
    pz.touched = false;
    apply();
  }
  function clampPan() {
    const r = box(), cw = pz.w * pz.k, ch = pz.h * pz.k, keep = 60;
    pz.x = Math.max(keep - cw, Math.min(r.width - keep, pz.x));
    pz.y = Math.max(keep - ch, Math.min(r.height - keep, pz.y));
  }
  function zoomAt(f, cx, cy) {
    if (!pz.w) return;
    const r = box();
    if (cx == null) { cx = r.width / 2; cy = r.height / 2; }
    const k = Math.max(fitK() * 0.5, Math.min(8, pz.k * f));
    pz.x = cx - (cx - pz.x) * (k / pz.k);
    pz.y = cy - (cy - pz.y) * (k / pz.k);
    pz.k = k;
    pz.touched = true;
    clampPan();
    apply();
  }
  const ptrs = new Map();
  let pinch = null, drag = null, divDrag = null;
  function wireStage() {
    const local = (e) => { const r = box(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };
    stage.addEventListener("pointerdown", (e) => {
      if (e.target.closest(".iso-divider, button")) return;
      if (e.pointerType === "mouse" && e.button !== 0) return;
      try { stage.setPointerCapture(e.pointerId); } catch (err) { /* capture is optional */ }
      ptrs.set(e.pointerId, local(e));
      if (ptrs.size === 2) {
        const [a, b] = Array.from(ptrs.values());
        const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        pinch = { d: Math.hypot(a.x - b.x, a.y - b.y) || 1, k: pz.k, ix: (mid.x - pz.x) / pz.k, iy: (mid.y - pz.y) / pz.k };
        drag = null;
      } else if (ptrs.size === 1) {
        const p = local(e);
        drag = { x: p.x, y: p.y, px: pz.x, py: pz.y };
      }
      stage.classList.add("grabbing");
    });
    stage.addEventListener("pointermove", (e) => {
      if (!ptrs.has(e.pointerId)) return;
      ptrs.set(e.pointerId, local(e));
      if (pinch && ptrs.size >= 2) {
        const [a, b] = Array.from(ptrs.values());
        const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        pz.k = Math.max(fitK() * 0.5, Math.min(8, pinch.k * (Math.hypot(a.x - b.x, a.y - b.y) || 1) / pinch.d));
        pz.x = mid.x - pinch.ix * pz.k;
        pz.y = mid.y - pinch.iy * pz.k;
      } else if (drag) {
        const p = ptrs.get(e.pointerId);
        pz.x = drag.px + p.x - drag.x;
        pz.y = drag.py + p.y - drag.y;
      } else return;
      pz.touched = true;
      clampPan();
      apply();
    });
    const up = (e) => {
      if (!ptrs.has(e.pointerId)) return;
      ptrs.delete(e.pointerId);
      if (ptrs.size < 2) pinch = null;
      if (ptrs.size === 1) { const p = Array.from(ptrs.values())[0]; drag = { x: p.x, y: p.y, px: pz.x, py: pz.y }; }
      if (!ptrs.size) { drag = null; stage.classList.remove("grabbing"); }
    };
    stage.addEventListener("pointerup", up);
    stage.addEventListener("pointercancel", up);
    stage.addEventListener("wheel", (e) => {
      e.preventDefault();
      const p = local(e);
      zoomAt(Math.exp(-e.deltaY * (e.deltaMode ? 0.05 : 0.0018)), p.x, p.y);
    }, { passive: false });
    stage.addEventListener("dblclick", (e) => { const p = local(e); zoomAt(2, p.x, p.y); });
    divider.addEventListener("pointerdown", (e) => {
      e.stopPropagation();
      try { divider.setPointerCapture(e.pointerId); } catch (err) { /* capture is optional */ }
      divDrag = e.pointerId;
    });
    divider.addEventListener("pointermove", (e) => {
      if (divDrag !== e.pointerId) return;
      const r = box();
      placeDivider((e.clientX - r.left) / r.width);
    });
    const dup = (e) => { if (divDrag === e.pointerId) divDrag = null; };
    divider.addEventListener("pointerup", dup);
    divider.addEventListener("pointercancel", dup);
  }

  // ---- keys ------------------------------------------------------------------------------------
  // Esc closes through the dialog itself. Left and right turn a live view, or move the compare divider.
  function onKey(e) {
    if (e.metaKey || e.ctrlKey || e.altKey || !view) return;
    const t = e.target;
    if (t && (t.tagName === "SELECT" || t.tagName === "INPUT")) return;
    const k = e.key;
    if (k === "ArrowLeft" || k === "ArrowRight") {
      const dir = k === "ArrowRight" ? 1 : -1;
      if (view.mode === "day") { if (view.cmp) placeDivider(cmpX + dir * 0.05); else return; }
      else turn(dir);
    } else if (k === "ArrowUp" || k === "+" || k === "=") zoomAt(1.25);
    else if (k === "ArrowDown" || k === "-" || k === "_") zoomAt(1 / 1.25);
    else if (k === "0") fit();
    else if (k === "Home" && view.mode === "day" && view.cmp) placeDivider(0);
    else if (k === "End" && view.mode === "day" && view.cmp) placeDivider(1);
    else return;
    e.preventDefault();
    e.stopPropagation();
  }

  // Lists and cards draw their action again when the server's answer changes what is allowed.
  A.on("world", () => { if (dlg && dlg.open && view && view.mode === "day") dlg.close(); });
})();
