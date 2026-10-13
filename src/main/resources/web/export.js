/* Atlas exports. The map on screen saved as an image with its date, the timeline recorded as a WebM video with
   MediaRecorder, and the admin link to the timeline data export. Loaded after timeline.js. */
(function () {
  "use strict";

  const A = window.Atlas;
  if (!A || A.noLeaflet || !A.map || !A.tl) return;
  const { $, esc, state } = A;
  const map = A.map;
  const T = A.tl;
  const DAY = 86400000;
  const SECS = [[250, "0.25 s a day"], [500, "0.5 s a day"], [1000, "1 s a day"]];
  const MAX_DATA_DAYS = 90;

  // One export at a time. Its state outlives the tab, so the progress shows again when the tab is opened again.
  const job = {
    running: false, cancel: false, i: 0, n: 0, date: "", result: null, error: "", from: null, to: null, open: false,
    sec: SECS.some((x) => x[0] === A.store.get("tlExportSec", 500)) ? A.store.get("tlExportSec", 500) : 500,
    tint: !!A.store.get("tlExportTint", false)
  };
  const canRecord = () => !!(window.MediaRecorder && window.HTMLCanvasElement && HTMLCanvasElement.prototype.captureStream);
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  // Steps are slots, a date with an hour such as 2026-10-13T16, or a plain date.
  const parseIso = (s) => { const m = /^(\d{4})-(\d{2})-(\d{2})(?:T\d{2})?$/.exec(String(s || "")); return m ? Date.UTC(+m[1], +m[2] - 1, +m[3]) : NaN; };
  const isoOf = (t) => new Date(t).toISOString().slice(0, 10);
  const longDate = (s) => { const t = parseIso(s); const h = A.slotHour ? A.slotHour(s) : ""; return isNaN(t) ? String(s || "") : new Date(t).toLocaleDateString([], { timeZone: "UTC", weekday: "short", year: "numeric", month: "short", day: "numeric" }) + (h ? ", " + h : ""); };
  const shortDate = (s) => A.slotName ? A.slotName(s) : A.dayName ? A.dayName(s) : s;
  const dayOnly = (s) => A.dayName ? A.dayName(String(s || "").slice(0, 10)) : String(s || "").slice(0, 10);
  const safe = (s) => String(s == null ? "" : s).replace(/[^A-Za-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60) || "map";
  const cssVar = (n, d) => getComputedStyle(document.documentElement).getPropertyValue(n).trim() || d;

  // ---- drawing the view ------------------------------------------------------------
  // The tiles that cover the map on screen at its zoom, worked out once so every frame of a video shows the same place.
  function geometry() {
    const z = map.getZoom();
    const nz = Math.max(A.geo.minNative, Math.min(A.geo.maxNative, Math.round(z)));
    const ts = 256 * Math.pow(2, z - nz);
    const size = map.getSize(), pb = map.getPixelBounds();
    // Video encoders want even sizes.
    const w = Math.max(2, Math.floor(size.x / 2) * 2), h = Math.max(2, Math.floor(size.y / 2) * 2);
    const tiles = [];
    for (let y = Math.floor(pb.min.y / ts); y * ts < pb.min.y + h; y++) {
      for (let x = Math.floor(pb.min.x / ts); x * ts < pb.min.x + w; x++) tiles.push({ x, y, dx: x * ts - pb.min.x, dy: y * ts - pb.min.y });
    }
    const lim = A.renderLimit();
    let clip = null;
    if (lim) { const c = map.latLngToContainerPoint(A.toLatLng(lim.x, lim.z)); clip = { x: c.x, y: c.y, r: lim.r * Math.pow(2, z) }; }
    return { z, nz, ts, w, h, ox: pb.min.x, oy: pb.min.y, tiles, clip, sharp: z > nz, world: state.world, atlasZ: A.geo.lToZ(nz) };
  }
  const loadImg = (url) => new Promise((res) => { const im = new Image(); im.decoding = "async"; im.onload = () => res(im); im.onerror = () => res(null); im.src = url; });
  // The changed tiles of a day, shared with the slider's own copy.
  function changedOf(date) {
    const k = state.world + "|" + date;
    if (T.changed.has(k)) return Promise.resolve(T.changed.get(k));
    return A.api("api/timeline/" + A.W() + "/" + encodeURIComponent(date) + "/changed").then((d) => {
      const v = d && Array.isArray(d.tiles) ? d : { first: true, tiles: [] };
      T.changed.set(k, v);
      return v;
    }).catch(() => null);
  }
  // Draws one picture of the view. tiles is the snapshot date whose tiles to use, "live" for the live map or null
  // for a blank map. The same image addresses as the slider are used, so tiles it already fetched come from the cache.
  async function paintView(g, geo, tiles, changed, title, sub) {
    const imgs = tiles ? await Promise.all(geo.tiles.map((t) => {
      const c = { x: t.x, y: t.y, z: geo.nz };
      return loadImg(tiles === "live" ? A.liveTileUrl(c) : A.dayTileUrl(tiles, c));
    })) : [];
    g.save();
    g.fillStyle = cssVar("--mapbg", "#13110e");
    g.fillRect(0, 0, geo.w, geo.h);
    if (geo.clip) { g.beginPath(); g.arc(geo.clip.x, geo.clip.y, geo.clip.r, 0, Math.PI * 2); g.clip(); }
    g.imageSmoothingEnabled = !geo.sharp;
    imgs.forEach((im, i) => { if (im) g.drawImage(im, geo.tiles[i].dx, geo.tiles[i].dy, geo.ts, geo.ts); });
    if (changed && !changed.first && Array.isArray(changed.tiles)) {
      const accent = cssVar("--accent", "#e8925f");
      g.fillStyle = accent; g.strokeStyle = accent; g.lineWidth = 1.5;
      for (const r of changed.tiles) {
        if (!Array.isArray(r) || Number(r[0]) !== geo.atlasZ) continue;
        const x = Number(r[1]) * geo.ts - geo.ox, y = Number(r[2]) * geo.ts - geo.oy;
        if (x > geo.w || y > geo.h || x + geo.ts < 0 || y + geo.ts < 0) continue;
        g.globalAlpha = 0.26; g.fillRect(x, y, geo.ts, geo.ts);
        g.globalAlpha = 0.9; g.strokeRect(x + 0.75, y + 0.75, geo.ts - 1.5, geo.ts - 1.5);
      }
      g.globalAlpha = 1;
    }
    g.restore();
    stamp(g, geo, title, sub);
  }
  // The date in the bottom left corner, light text on a dark plate so it reads on any terrain.
  function stamp(g, geo, title, sub) {
    const f = Math.max(13, Math.round(Math.min(geo.w, geo.h) / 32));
    const fs = Math.round(f * 0.66), pad = Math.round(f * 0.55), font = "system-ui, -apple-system, Segoe UI, Roboto, sans-serif";
    g.save();
    g.font = "650 " + f + "px " + font;
    const w1 = g.measureText(title).width;
    g.font = "500 " + fs + "px " + font;
    const w2 = sub ? g.measureText(sub).width : 0;
    const bw = Math.ceil(Math.max(w1, w2) + pad * 2), bh = Math.ceil(f * 1.25 + (sub ? fs * 1.35 : 0) + pad * 1.4);
    const x = pad, y = geo.h - pad - bh;
    g.fillStyle = "rgba(22, 18, 14, .78)";
    g.beginPath();
    if (g.roundRect) g.roundRect(x, y, bw, bh, Math.round(f * 0.3)); else g.rect(x, y, bw, bh);
    g.fill();
    g.textBaseline = "top";
    g.fillStyle = "#f7f0e4";
    g.font = "650 " + f + "px " + font;
    g.fillText(title, x + pad, y + pad * 0.7);
    if (sub) { g.fillStyle = "#d6cbb9"; g.font = "500 " + fs + "px " + font; g.fillText(sub, x + pad, y + pad * 0.7 + f * 1.25); }
    g.restore();
  }
  const subLine = () => (state.title ? state.title + ", " : "") + A.worldLabel(state.world);
  function download(blob, name) {
    const u = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = u; a.download = name; a.hidden = true;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(u), 60000);
  }

  // ---- save image --------------------------------------------------------------------
  // The day on screen, or the live map, as a PNG the size of the map with the date in the corner. The tint of
  // changed tiles is kept when it is on. Markers and other overlays are not part of the picture.
  let saving = false;
  async function saveImage() {
    if (saving) return;
    saving = true;
    syncUi();
    try {
      const s = T.index >= 0 ? T.slots[T.index] : null;
      const geo = geometry();
      const base = map.hasLayer(A.terrain) ? "live" : map.hasLayer(A.dayTerrain) && s ? s.tiles : null;
      const ch = s && s.kind === "snap" && T.changedOn && A.layerOn("terrain") ? await changedOf(s.date) : null;
      const c = document.createElement("canvas");
      c.width = geo.w; c.height = geo.h;
      const today = isoOf(Date.now() - new Date().getTimezoneOffset() * 60000);
      await paintView(c.getContext("2d"), geo, base, ch, s ? longDate(s.date) : "Live, " + longDate(today), subLine());
      const blob = await new Promise((r) => c.toBlob(r, "image/png"));
      if (!blob) throw new Error("No image");
      download(blob, "atlas-" + safe(state.world) + "-" + (s ? s.date : "live-" + today) + ".png");
      A.toast("Image saved", "good");
    } catch (e) {
      A.toast("The image could not be made", "bad");
    }
    saving = false;
    syncUi();
  }

  // ---- record a video --------------------------------------------------------------------
  // Every snapshot in the range is drawn on a canvas and held for the chosen time. The recorder pauses while a
  // day is drawn, so slow tiles never stretch the day before it.
  const daysIn = (from, to) => T.slots.filter((s) => s.kind === "snap" && s.date >= from && s.date <= to);
  async function record() {
    if (job.running) return;
    if (!canRecord()) { job.error = "This browser cannot record video. Save image still works."; syncUi(); return; }
    const days = daysIn(job.from, job.to);
    if (!days.length) { job.error = "There is no snapshot in that range."; syncUi(); return; }
    if (A.tlStop) A.tlStop();
    clearResult();
    Object.assign(job, { running: true, cancel: false, i: 0, n: days.length, date: days[0].date, error: "" });
    syncUi();
    const geo = geometry();
    const buf = document.createElement("canvas"), out = document.createElement("canvas");
    buf.width = out.width = geo.w; buf.height = out.height = geo.h;
    const gb = buf.getContext("2d"), go = out.getContext("2d");
    let stream = null, rec = null, painter = null, mime = "";
    const parts = [];
    try {
      stream = out.captureStream(30);
      mime = ["video/webm;codecs=vp9", "video/webm;codecs=vp8", "video/webm"].find((t) => MediaRecorder.isTypeSupported(t)) || "";
      rec = new MediaRecorder(stream, mime ? { mimeType: mime, videoBitsPerSecond: 5000000 } : {});
      rec.ondataavailable = (e) => { if (e.data && e.data.size) parts.push(e.data); };
      const stopped = new Promise((r) => { rec.onstop = r; });
      const paint = () => go.drawImage(buf, 0, 0);
      for (let i = 0; i < days.length && !job.cancel; i++) {
        job.i = i; job.date = days[i].date;
        syncUi();
        const ch = job.tint ? await changedOf(days[i].date) : null;
        await paintView(gb, geo, days[i].tiles, ch, longDate(days[i].date), subLine());
        if (job.cancel || geo.world !== state.world) { job.cancel = true; break; }
        paint();
        if (rec.state === "inactive") rec.start(1000); else rec.resume();
        // The canvas is drawn again through the hold, so the recorder keeps receiving frames of this day.
        painter = setInterval(paint, 1000 / 30);
        await wait(job.sec);
        clearInterval(painter);
        painter = null;
        rec.pause();
      }
      if (rec.state !== "inactive") { rec.stop(); await stopped; }
      if (!job.cancel) {
        const blob = new Blob(parts, { type: "video/webm" });
        if (!blob.size) throw new Error("Empty video");
        job.result = { url: URL.createObjectURL(blob), size: blob.size, days: days.length, name: "atlas-" + safe(state.world) + "-" + days[0].date + "-to-" + days[days.length - 1].date + ".webm" };
      }
    } catch (e) {
      job.error = "The video could not be made in this browser. Save image still works.";
    } finally {
      clearInterval(painter);
      if (stream) stream.getTracks().forEach((t) => t.stop());
      job.running = false;
      syncUi();
    }
    if (job.result) A.toast("The video is ready to download", "good");
  }
  function clearResult() {
    if (job.result) URL.revokeObjectURL(job.result.url);
    job.result = null;
  }
  // A world switch ends a recording, since the tiles would be of the wrong world.
  A.on("world", () => { if (job.running) job.cancel = true; });

  // ---- the Export block of the Timeline tab -------------------------------------------------
  let box = null;
  A.drawTlExport = (el) => {
    if (!el) return;
    if (el !== box || !el._built) build(el);
    syncUi();
  };
  function build(el) {
    box = el;
    el._built = true;
    el.innerHTML = "<h3 class='sub'>Export</h3>" +
      "<div class='tl-x-range'><label>From <select id='tl-x-from' class='tl-select' aria-label='First day to export'></select></label>" +
      "<label>To <select id='tl-x-to' class='tl-select' aria-label='Last day to export'></select></label></div>" +
      "<div class='tl-x-acts'><button class='btn small' id='tl-x-save'>" + A.icon("image", "sm") + "<span class='lbl'>Save image</span></button>" +
      "<button class='btn small' id='tl-x-video' aria-expanded='false' aria-controls='tl-x-form'>" + A.icon("film", "sm") + "Make a video</button>" +
      "<a class='btn small' id='tl-x-data' hidden>" + A.icon("archive", "sm") + "Export data</a></div>" +
      "<div class='tl-x-form' id='tl-x-form' hidden>" +
      "<label class='tl-x-field'>Each day <select id='tl-x-sec' class='tl-select' aria-label='Time each day is shown'>" + SECS.map((x) => "<option value='" + x[0] + "'>" + x[1] + "</option>").join("") + "</select></label>" +
      "<label class='check'><input type='checkbox' id='tl-x-tint'><span class='grow'>Tint the tiles that changed</span></label>" +
      "<p class='muted tl-x-about' id='tl-x-about'></p>" +
      "<button class='btn small primary' id='tl-x-go'>" + A.icon("film", "sm") + "Record the video</button></div>" +
      "<div class='tl-x-prog' id='tl-x-prog' hidden><div class='tl-x-line'><span id='tl-x-txt' role='status' aria-live='polite'></span><button class='btn small' id='tl-x-cancel'>Cancel</button></div>" +
      "<progress id='tl-x-bar' max='1' value='0' aria-label='Export progress'></progress></div>" +
      "<div class='note tl-x-done' id='tl-x-done' hidden></div>" +
      "<p class='tl-x-msg' id='tl-x-msg' role='alert' hidden></p>";
    $("tl-x-from").addEventListener("change", (e) => { job.from = e.target.value; if (job.to < job.from) job.to = job.from; job.error = ""; syncUi(); });
    $("tl-x-to").addEventListener("change", (e) => { job.to = e.target.value; if (job.from > job.to) job.from = job.to; job.error = ""; syncUi(); });
    $("tl-x-save").addEventListener("click", saveImage);
    $("tl-x-video").addEventListener("click", () => { job.open = !job.open; job.error = canRecord() ? "" : "This browser cannot record video. Save image still works."; syncUi(); });
    $("tl-x-sec").addEventListener("change", (e) => { job.sec = Number(e.target.value); A.store.set("tlExportSec", job.sec); syncUi(); });
    $("tl-x-tint").addEventListener("change", (e) => { job.tint = e.target.checked; A.store.set("tlExportTint", job.tint); });
    $("tl-x-go").addEventListener("click", record);
    $("tl-x-cancel").addEventListener("click", () => { job.cancel = true; syncUi(); });
    $("tl-x-done").addEventListener("click", (e) => { if (e.target.closest("#tl-x-clear")) { clearResult(); syncUi(); } });
  }
  // The range lists every day the slider can show. From and To start at the first and last of them.
  function syncUi() {
    if (!box || !box.isConnected) return;
    const days = T.slots.filter((s) => A.tlSelectable ? A.tlSelectable(s) : s.kind === "snap");
    const sig = state.world + "|" + days.map((s) => s.date).join();
    const from = $("tl-x-from"), to = $("tl-x-to");
    if (from._sig !== sig) {
      from._sig = sig;
      const opts = days.map((s) => "<option value='" + esc(s.date) + "'>" + esc(shortDate(s.date)) + "</option>").join("");
      from.innerHTML = opts;
      to.innerHTML = opts;
      if (!days.some((s) => s.date === job.from)) job.from = days.length ? days[0].date : null;
      if (!days.some((s) => s.date === job.to)) job.to = days.length ? days[days.length - 1].date : null;
    }
    if (job.from) from.value = job.from;
    if (job.to) to.value = job.to;
    const busy = job.running;
    from.disabled = to.disabled = busy;
    $("tl-x-save").disabled = busy || saving;
    $("tl-x-save").querySelector(".lbl").textContent = saving ? "Saving" : "Save image";
    const vb = $("tl-x-video");
    vb.disabled = busy;
    vb.setAttribute("aria-expanded", String(job.open));
    $("tl-x-form").hidden = !job.open || busy;
    $("tl-x-sec").value = String(job.sec);
    $("tl-x-tint").checked = job.tint;
    $("tl-x-tint").closest("label").hidden = !A.layerOn("terrain");
    const n = job.from && job.to ? daysIn(job.from, job.to).length : 0;
    const secs = n * job.sec / 1000;
    $("tl-x-about").textContent = n ? A.plural(n, "snapshot") + " of the map as it is framed now, at this zoom, about " + A.plural(Math.max(1, Math.round(secs)), "second") + " of video." : "There is no snapshot in that range.";
    $("tl-x-go").disabled = !n || !canRecord();
    // Admins get the data export of the same range, at most 90 days ending at To.
    const data = $("tl-x-data");
    const admin = A.isAdmin() && !!job.from && !!job.to;
    data.hidden = !admin;
    if (admin) {
      const toT = parseIso(job.to);
      const fromD = isoOf(Math.max(parseIso(job.from), toT - (MAX_DATA_DAYS - 1) * DAY));
      const href = "api/timeline/" + A.W() + "/export?from=" + fromD + "&to=" + String(job.to).slice(0, 10);
      if (data.getAttribute("href") !== href) data.setAttribute("href", href);
      data.setAttribute("download", "");
      data.title = "A zip of the snapshots, stats, events and 3D views from " + dayOnly(fromD) + " to " + dayOnly(job.to) + ", at most 90 days";
      data.setAttribute("aria-label", "Export the timeline data from " + dayOnly(fromD) + " to " + dayOnly(job.to));
    }
    const prog = $("tl-x-prog");
    prog.hidden = !busy;
    if (busy) {
      const t = job.cancel ? "Stopping" : "Drawing " + shortDate(job.date) + ", picture " + (job.i + 1) + " of " + job.n;
      if ($("tl-x-txt").textContent !== t) $("tl-x-txt").textContent = t;
      $("tl-x-bar").max = job.n;
      $("tl-x-bar").value = job.i + (job.cancel ? 0 : 0.5);
      $("tl-x-cancel").disabled = job.cancel;
    }
    const done = $("tl-x-done");
    const dh = job.result && !busy ? A.icon("film", "sm") + " The video is ready, " + esc(A.plural(job.result.days, "day")) + ", " + esc(A.num(job.result.size / 1048576, 1)) + " MB. " +
      "<span class='tl-x-line'><a class='btn small primary' href='" + esc(job.result.url) + "' download='" + esc(job.result.name) + "'>" + A.icon("download", "sm") + "Download the video</a>" +
      "<button class='btn small' id='tl-x-clear'>Dismiss</button></span>" : "";
    if (done._h !== dh) {
      done.innerHTML = dh; done._h = dh;
      done.hidden = !dh;
      if (dh) done.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }
    done.hidden = !dh;
    const msg = $("tl-x-msg");
    msg.textContent = job.error;
    msg.hidden = !job.error;
  }
})();
