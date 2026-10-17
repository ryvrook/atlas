# Changelog

Every release keeps the values an existing `atlas.json` already holds. Keys a release adds are
filled in with their defaults and written back on the next start. Changed defaults only apply to
new files.

## 1.9.1 - 2026-10-17

### For admins

- The map no longer shows `Atlas was updated, reload to continue` on every visit. Since 1.8.0 the page's script
  addresses carry the version and a hash of the file, and the page compared that whole tag with the plain version the
  API reports, so they never matched. The page now compares the version alone. A bar still comes up when the jar
  really changes version. Updating is a jar swap, no setting changed.

## 1.9.0 - 2026-10-16

### For admins

- `/atlas integrations`, also `/atlas status`, lists TrackerAPI, Coinage, Dominion, Feats, Chronicle Stats,
  Lullaby, SocialBridge and RestAPI with the version installed and what each one adds to the map right now.
- `coinage`, `dominion`, `feats` and `chronicle` join `tracker` in atlas.json, each `auto`, `always` or `never`.
  `never` leaves that mod alone even when it is installed, so no balances, no faction layers, no Leaderboard tab or
  no Chronicle tab. `dominion` `always` keeps drawing the snapshot after Dominion is gone, where `auto` drops it after
  a minute. `coinage` `always` warns at start when Coinage is missing.
- A `/plugin reload` of TrackerAPI 1.0.3 or newer logs one information line that TrackerAPI reloads Atlas by itself,
  where it used to warn and ask for a reload. The warning comes only when TrackerAPI is still gone after 30 seconds.
- A server stop or a `/plugin reload` no longer waits on Atlas. The TrackerAPI and Dominion watches, `/atlas status`
  and the dependents pass only try the plugin manager's lock and use the last answer while it is held, and Atlas
  gives its scheduler, tile and 3D view threads 1 second plus half a second after an interrupt instead of 5 seconds.

### Upgrading

- Four settings are added on the first start, `coinage`, `dominion`, `feats` and `chronicle`, all `auto`, which is
  how 1.8.0 behaved. A server coming from 1.6.0 also gets `showOwnMoney` from 1.7.0. docs/DEVELOPMENT.md lists what
  else changes on that jump.

### For plugin authors

- `AtlasApi.onMarker(listener)` reports the shared markers players place and remove on the in game map and the
  server markers staff place and remove with `/atlas marker`, as `MarkerEvent` records with the kind, `ADDED` or
  `REMOVED`, the author when there is one, the world, the layer, the marker id, the position and the time. The
  handle it returns removes the listener. Listeners run on the Atlas scheduler in the order of the changes, a
  listener that throws is logged at most once a minute and the others still run. Feats 1.7.2 counts placed
  markers with it.
- `AtlasApi.VERSION` is 3.

## 1.8.0 - 2026-10-16

### For admins

- After a `/plugin reload` of Atlas, Atlas reloads the plugins that list it, Dominion, SocialBridge and RestAPI
  among them, two seconds after it starts, so they link to the new instance. Before, each one had to be reloaded
  by hand. The pass shares the `ryvrook.dependents.lock` lock with TrackerAPI and Coinage, so a plugin reloaded by
  one of them in the meantime is left alone.

### For plugin authors

- `AtlasApi.publicUrl()` gives the map address players open, `publicUrl` from atlas.json or else
  `http://bind:port/basePath/`, the same rule as Chronicle Stats. `publicUrlSet()` says which of the two it is.
- `AtlasApi.renderStatus()` gives the render queue, the queued tiles per world and the chunk image budget as a
  `RenderStatus` record.
- `AtlasApi.worlds()` lists the worlds Atlas draws as `WorldStatus` records with players, explored chunks, queued
  tiles and whether the public may open them.
- `AtlasApi.VERSION` is 2 and `version()` gives the version of the running instance.

## 1.7.0 - 2026-10-15

### For players

- With Coinage installed and `showOwnMoney` on, a linked player sees their own wallet and net worth in the account
  menu. Nobody else sees them.
- The public map no longer names hidden feats. Recent unlocks, milestones and firsts of a feat Feats marks hidden
  say "A hidden feat" to everyone but admins and the player who earned it, and timeline lines do not name it.
- Each Dominion siege shows once. The live siege layer stays off while the snapshot lists sieges, where before the
  map drew every siege twice and listed two Sieges layers.

### For admins

- A Dominion that has been missing from the server for a minute takes its territories off the map, and they come
  back when Dominion does. Before, a removed Dominion left its last territories up for good.
- `chronicleExport` defaults to `mods/Ryvrook_ChronicleStats/export/stats.json`. An `atlas.json` with the old
  default reads the new file too, and the old `Ryvrook_Chronicle` file is read only while the new one is missing.
- With TrackerAPI 1.0.3 or newer, which reloads Atlas itself, Atlas no longer logs a line asking for that reload.

### Upgrading

- One setting is added on the first start, `showOwnMoney` (`false`).
- The manifest lists `Ryvrook:Coinage` as an optional dependency, so Atlas loads after Coinage.

### Other changes

- For everyone but admins, the `rarity` map of `/api/leaderboard` holds only the feats the payload names, so ids
  of hidden feats nobody earned stay off the map.
- Scripts, stylesheets and icons that index.html links are cached for a year as immutable. Their `?v=` tag is the
  version plus the start of the file's hash, so a rebuilt jar with the same version still gets new addresses.
- `AtlasApi.removeHooks` takes back hooks added with `onEnable` and `onDisable`.

## 1.6.0 - 2026-10-14

This is Atlas 1.6.0, dated 2026-10-14. Atlas works with TrackerAPI. When TrackerAPI 1.0.0 or newer is
installed, the heat layer and the kill feed are fed from its events and Atlas registers no block or
death systems of its own.

### Upgrading

- One new setting is added with its default on the first start, `tracker` (`auto`). It takes `auto`,
  `always` or `never` and fails closed like the rest, see Configuration in the README. It is read at
  server start, so a change needs a restart or `/plugin reload` of Atlas.
- `heat.json` and `kills.json` stay as they are and keep counting from where they were, whichever source
  feeds them.

### Added

- With `tracker` at `auto` and TrackerAPI installed, block breaks and places come from its
  `BlockBroken` and `BlockPlaced` events and deaths from `EntityDied`. Without TrackerAPI Atlas uses its
  own systems as before. `always` uses TrackerAPI only and records nothing without it, `never` ignores
  it. The server log says in one line at start which source is active.
- When TrackerAPI is loaded, reloaded or unloaded while Atlas runs, Atlas logs one line asking for a
  reload of Atlas, since the engine systems it uses can only be chosen at start.

### Changed

- The manifest lists `Ryvrook:TrackerAPI` as an optional dependency.

## 1.5.0 - 2026-10-14

This is Atlas 1.5.0, dated 2026-10-14. The timeline skips a scheduled snapshot when nothing changed, so
more snapshots a day cost nothing on quiet hours.

### Upgrading

- One new setting is added with its default on the first start, `timeline.skipUnchanged` (`true`). It
  fails closed like the rest, see Configuration in the README. Set it to `false` to keep every
  scheduled snapshot as before.
- Snapshots already held stay as they are. Nothing is removed from earlier slots.

### Changed

- A scheduled snapshot, the catch up after a restart included, is dropped when no block changed since
  the snapshot before and no event happened in between. A block change is a zoom 0 tile whose image
  differs from the slot before, or one the slot before lacked, which is newly explored ground. Tiles of
  the other zooms, which are drawn from zoom 0, and tiles carried over do not count. A zoom 0 tile that
  was being redrawn while it was read counts. Nothing of a dropped snapshot is kept, no slot, no stats
  and no `snapshot` event, and the server log says Atlas skipped it in one line.
- A snapshot with no block change but with events since the one before is kept for the events, and its
  log line says so. Snapshot lines do not count as events, the line that says a full day dropped
  further events does. While `timeline.events` is off the activity counters decide instead, players
  seen, kills, deaths, world events and blocks broken or placed, and a snapshot with any is kept for
  activity. When the event log could not be written just before the check, the snapshot is kept.
- The stats of a snapshot cover the 24 hours before it, or back to the snapshot kept before it when
  that is longer ago, up to seven days, and `windowSeconds` holds the span. So skipped snapshots leave
  no hours out of the stats. The activity counters keep eight days instead of two.
- Daily 3D views and key places are drawn with the first snapshot kept each day. A day with every
  snapshot skipped has none, since they would have been unchanged.
- `/atlas snapshot` is always kept. When no blocks changed since the slot before, the reply and the log
  say so.
- The speed menu reads 0.5 sec/snapshot to 10 sec/snapshot. Play and the speed menu live in the bar
  only. The Timeline tab keeps the step arrows, Changed tiles and Live, and on phones the speed menu,
  since the bar has no room for it there.
- `/atlas timeline status` says whether unchanged snapshots are skipped and how many were skipped since
  the start.
- The day list of `GET /api/timeline/{world}` gains `skipUnchanged` next to `perDay`. A slot hour may be
  missing from the list because its snapshot was skipped, and the slot held before it covers its hours.

## 1.4.0 - 2026-10-14

This is Atlas 1.4.0, dated 2026-10-14. The timeline is rebuilt. Snapshots are complete at every zoom,
playback cross fades between them, the track shows what each mark holds, events have a place on the map
and the key places block is one compact unit.

### Upgrading

- No new settings.
- Snapshots taken by earlier releases are filled in when served. A tile a slot lacks comes from the
  newest earlier slot that holds it, so old black squares go away without a new snapshot. The first
  snapshot after the upgrade is complete on its own. Browsers that cached the old 404 answers ask again,
  since day tile addresses now carry a key.
- Kill records gain `x` and `z`. Older records load without them.

### Fixed

- The tile renderer drops the zoomed out and zoomed in tiles whenever a base tile is drawn again and
  draws them only when a browser asks, so a snapshot caught a different random subset of them each
  time and the timeline showed black squares that changed at every step. A snapshot now carries every
  tile the live folder lacks forward from the slot before, draws the zoomed out tiles itself from fresh
  tiles below, and counts carried tiles in `carried`. `changed` counts only tiles whose image differs.
- Joins, leaves, deaths, kills and Feats events were recorded without a place, so none of them could be
  shown on the map. They now carry one, with the same rules as the live player rows. A place is withheld
  when the player is underground, in their own territory with `hidePlayersInOwnTerritory`, crouching or
  invisible when those are hidden, and at serve time for hidden players, for players of another faction
  on a faction limited layer and for worlds the viewer may not open. Non admins get the chunk corner.
- On a day that holds the day folder of Atlas 1.1 and slots with an hour, both listed the events of the
  whole day. The day folder now covers midnight up to the first hour slot, so each event belongs to one
  slot.
- A prune could leave stale day counts in memory until the next snapshot.
- A corrupt tile image failed a whole snapshot. The tile is carried over instead.

### Changed

- Playback double buffers the day tiles. The next slot loads out of sight and cross fades in once every
  tile on screen has answered, so no gap opens where the step before had a tile. Each step's time on
  screen starts when its picture is fully shown, and the chip says loading when the next one is late.
- The slider is a track. It has a time axis with day labels, a picture mark per snapshot, a cube mark
  for a snapshot with 3D views, a tick for a slot with events only, a hatched block for a removed day, an
  event bar under each slot, and a playhead with a bubble that slides to the next slot while playing. A
  hover or the arrow keys on the focused track show a card with the slot's tiles, changed, events and a
  3D thumbnail. A legend names the marks. Click or drag anywhere on the track to move through time.
- The chip shows the run as 2 of 5 with a progress bar, and the ring around Play fills while each
  snapshot is on screen.
- The events of a step land with its picture and pop in. Close events merge into a count badge until
  the map is zoomed in, hovering a feed row lights up its marker, and a slot's feed is grouped by hour.
- The speed menu reads 0.5 to 10 seconds a snapshot and says how long each snapshot stays on screen.
- The Timeline tab starts with the controls and the figures of the shown slot, then a strip of its 3D
  views, then Key places as one block with place chips, one picture, a day strip, a direction switch
  and Play, Compare and Live 3D, then the stats, the charts and the feed.
- The day list carries `carried` per slot, and `tiles` counts carried tiles too.

## 1.3.0 - 2026-10-13

This is Atlas 1.3.0, dated 2026-10-13. The timeline plays properly, with more speeds, and the 3D views
have shadows.

### Upgrading

- No new settings.
- The 3D renderer changed, so cached 3D views are drawn again. Files of the old style are removed from
  the 3D cache on the first start and each key place is drawn once more the next time its day runs.

### Fixed

- On a day that holds both the day folder of Atlas 1.1 and slots with an hour, the plain date named
  the last slot of that day, so its tiles, its changed list and its stats were those of the later slot
  and the timeline showed the same map twice. A plain date now names its own folder when that folder
  holds a snapshot, and otherwise the last slot of the day as before. A retaken slot on such a day now
  compares itself with the right snapshot.
- Play finished in a few seconds on a short timeline and ended on an events only slot that shows the
  map of the slot before, so it looked as if nothing had happened.

### Changed

- Play starts at the oldest slot held when started from the live map or the newest slot, and a pause
  resumes where it stopped. The chip names each slot with its place in the run, such as 2 of 3. A slot
  with events only that shows the same map as the step before passes in half the time and says so.
  After the newest slot the live map returns and a toast says what was played. With fewer than two
  slots Play is dimmed and its tooltip says when the next snapshot is due.
- The playback speeds are 0.5, 1, 2, 3, 5 and 10 seconds a step. A stored speed outside that list
  falls back to 1 second.
- 3D views have soft cast shadows from the fixed sun on ground, water and tree tops, darker corners
  and cliff feet, a lighter shoreline, and lava that is never shaded.
- The 3D lightbox has a compass that shows where north lies and which side the view is seen from, and
  a second double click fits the view again.
- The build targets server 0.7.1 by default. Nothing in the engine that Atlas uses changed between
  0.7.0 and 0.7.1.

## 1.2.0 - 2026-10-13

This is Atlas 1.2.0, dated 2026-10-13. It shows more of what players build. The timeline keeps several
snapshots a day, up to three key places are drawn from all four directions every day, and each address
may hold more 3D views at once.

### Upgrading

- New settings are added with their defaults on the first start. They are `timeline.perDay` (`4`),
  `isoViewsPerAddress` (`4`), `isoLargeViewsPerAddress` (`2`), `isoKeyPlaceLimit` (`3`) and
  `iso.keyPlaces` (empty). Each fails closed like the rest, see Configuration in the README.
- The default of `isoRequestsPerMinute` is now `20`. An existing `atlas.json` keeps the value it holds,
  so set it to `20` by hand to get the new allowance.
- The timeline now takes `timeline.perDay` snapshots a day, four by default at 04:00, 10:00, 16:00 and
  22:00 with `timeline.hour` at 4. Set `timeline.perDay` to `1` for one snapshot a day as before. Each
  snapshot is a slot named by its date and hour, such as `2026-10-13T16`, and gets a folder of that name.
  Day folders written by 1.0 and 1.1 stay as they are and count as one slot of their day. A plain date
  still works in every address and share link and means the last slot of that day.
- Retention and the disk cap still count days. A pruned day loses all of its slots together. With four
  slots a day the timeline holds about four times as many manifests and stats files, which are small.
  The tile images are shared by every slot as before, so the disk use grows with what changed only.
- `timeline` events from the event stream carry `slot` next to `date`, and the day list of
  `GET /api/timeline/{world}` lists slots, see Changed.

### Added

- Key places. Up to `isoKeyPlaceLimit` places in `iso.keyPlaces`, each with a world, a position, a
  radius (6 by default, 1 to 16), a label and a name. With the first snapshot of each day every key place
  is drawn from all four directions at scale 2, lowered for a wide radius as usual, into
  `iso/<label>-<azimuth>.png` of that slot. When nothing changed since the last render, which Atlas
  learns by reading the chunks and comparing their fingerprint without drawing them, the pictures of that
  render are linked into the new day instead of drawn again and the entries are marked unchanged.
- `/atlas key add <world> <x> <z> <label> [radius] [name]`, `/atlas key remove <label>`,
  `/atlas key list` and `/atlas key render <label>`, which draws a place now.
- `GET`, `POST` and `DELETE /api/admin/keyplaces` for admins, by the admin token or a linked session with
  the admin permission. Changes are written into `atlas.json` and apply at once.
- A Key places section in the Admin tab with the list, a remove button and a form that starts from the
  last spot right clicked on the map, or its middle. A right click offers Make this a key place to admins.
- A Key places block at the top of the Timeline tab. Each place shows the picked direction for the day on
  the slider, a strip of its four directions, Compare days with the divider, View live in 3D and Play,
  which steps the place through every day held.
- A Key places layer on the map for admins, a camera symbol per place with View in 3D.
- `timeline.perDay`, `isoViewsPerAddress`, `isoLargeViewsPerAddress` and `isoKeyPlaceLimit`.

### Changed

- One address may hold `isoViewsPerAddress` 3D views in the render queue (4 by default, 2 before) and
  `isoLargeViewsPerAddress` large ones waiting (2 by default, 1 before). The queue still holds 8 browser
  views and admits 2 large ones at a time. `/atlas iso status` prints the allowances.
- The default of `isoRequestsPerMinute` is 20 instead of 10.
- The day list of `GET /api/timeline/{world}` has one row per slot with `slot`, `date`, `hour` and
  `window`, and gains `perDay`, `hours` and `currentSlot`. Every route that took a date takes a slot.
  The events of a slot are the hours of its day from its own hour to the next slot. `isoViews` entries of
  key places carry `name`, `azimuth`, `scale`, `keyPlace`, `unchanged` and `sameAs`.
- The timeline slider has a step per slot with the hour under the date, playback steps per slot, and
  share links carry the slot as `t=2026-10-13T16`.
- The 3D views of `iso.snapshots`, admin markers and key places are drawn once a day, with the first slot.
  The other slots of the day show the same views.
- A missed slot is taken five minutes after a start, only the latest one missed.
- `/atlas timeline status` prints the schedule and the slots held.
- The manifest of a slot names the slot before it in `previous`, and the export zip holds a folder per
  slot next to the events of each day.

## 1.1.0 - 2026-10-13

This is Atlas 1.1.0, dated 2026-10-13. It lets the separate Chronicle stats plugin share one login with
the map and adds a Chronicle tab.

### Upgrading

- The session cookie is now called `ryvrook_session`, the name Chronicle uses too. Browsers linked on
  1.0.0 stay linked. Atlas 1.1.0 still accepts the old `atlas_session` cookie and on its next answer
  sets the same session again as `ryvrook_session` and clears the old cookie. Accepting `atlas_session`
  ends with the next release, so a browser that does not visit the map before then has to link again.
- New settings are added with their defaults on the first start. They are `cookieName`
  (`ryvrook_session`), `cookieDomain` (empty), `chronicleExport`
  (`mods/Ryvrook_Chronicle/export/stats.json`) and `chronicleUrl` (empty).
- To share the login, serve Atlas and Chronicle from one host name under two base paths, such as
  `https://example.com/map/` and `https://example.com/stats/`, with the same `cookieName` in both. This
  is the documented reverse proxy setup. Atlas sets its cookie with Path `/`, so both base paths see
  it. Set `cookieDomain` only when the two run on sibling host names instead.

### Added

- `dev.ryvrook.atlas.api.SessionApi`, a session API for other plugins, versioned on its own as
  version 1. It issues and redeems link codes, resolves and revokes sessions by the SHA-256 hash of
  their token, takes in sessions another plugin redeemed with `adopt`, shares the hidden player list
  and reports every redeemed, adopted and revoked session to listeners on the Atlas scheduler.
- A Chronicle tab with the server totals, players online and the top players of each board from the
  Chronicle export, a link to the Chronicle dashboard when `chronicleUrl` is set, and "A player" for
  hidden players. It shows only while the export exists. The Stats tab stays as it was.
- `GET /api/chronicle`, which follows the `stats` visibility, and the `chronicle` stream event.
- `cookieName` and `cookieDomain`. A `cookieDomain` adds the Domain attribute to the session cookie.
- `chronicleExport` and `chronicleUrl`.

### Changed

- The session cookie is `ryvrook_session` instead of `atlas_session`, see Upgrading.
- Signing out also ends the session of an old `atlas_session` cookie and clears it.

## 1.0.0 - 2026-10-13

This is Atlas 1.0.0, dated 2026-10-13. The version number changes when it ships.

### Upgrading

- An invalid `atlas.json` now keeps the web server down instead of falling back to defaults. Check the
  log for one SEVERE line after the upgrade. Values that used to be corrected quietly now count as
  invalid, such as `tileCacheMb` under 4, `playerUpdateHz` of 0 or less and an unknown visibility
  level.
- New settings are added with their defaults on the first start. They are `apiRequestsPerMinute`
  (`600`), `tileRequestsPerMinute` (`3000`), `chunkImagesPerMinute` (`1200`), `tileMaxAgeHours`
  (`24`), `preRenderIdle` (`true`), `hidePlayersCrouching` (`false`), `hidePlayersInvisible`
  (`true`), `defaultWorld` (empty), `worlds` (empty), the `timeline` object (`enabled` true, `hour` 4,
  `retainDays` 90, `maxDiskMb` 2048, `events` true), `isoEnabled` (`true`), `isoCacheMb` (`64`),
  `isoDiskMb` (`512`), `isoMaxRadius` (`16`), `isoRequestsPerMinute` (`10`) and the `iso` object
  (no `snapshots`, `snapshotAdminMarkers` false), plus the visibility keys `layers`, `timeline` and
  `iso`, all `public`.
- `atlas.json` is written only when keys were added or the admin token was replaced, and unknown keys
  stay in the file.
- An existing `sessions.json` is converted to hashed tokens on the first start, and linked browsers
  stay linked.
- The browser API is now version 1 and some fields changed, see Changed and Removed. Scripts that
  read the API need updating. The web client in the jar already matches.
- Tile folders get a stamp on the first start. A tile folder from an older Atlas is kept and stamped.

### Added

- A timeline of every world. Daily snapshots of the map tiles at a set hour, stats for each day, an
  event log per world and day, retention by days and by disk use, and a Timeline tab with a day
  slider, playback, changed tiles, day events on the map, charts and share links with `t=<date>`.
- `/atlas snapshot [world]` and `/atlas timeline status`.
- 3D views. Isometric pictures of any explored area from four directions at one to three pixels per
  block, with trees, water and plants, under `/api/iso/{world}` and `/api/iso/{world}/marker/{id}`.
  They read only explored chunks, never generate terrain, use the background half of the chunk image
  budget for chunks not already in memory, put browser views ahead of daily ones, are cached in
  memory and on disk and have their own per address limit and an `iso` visibility key.
- Daily 3D views of the areas in `iso.snapshots`, and of every server marker with
  `iso.snapshotAdminMarkers`, drawn with each timeline snapshot and listed as `isoViews` on the day.
- `/atlas iso <world> <x> <z> [radius] [scale]` and `/atlas iso status`.
- A timeline data export for admins, `/api/timeline/{world}/export`, a zip of up to 90 days.
- A View in 3D lightbox on marker cards, the Markers and Players tabs and the map's right click menu,
  with four directions, four sizes, download and share links with `iso=`.
- Daily 3D view thumbnails in the Timeline tab and a slider that compares one place on two days.
- Timeline exports in the browser. Save the framed map as a dated PNG, record a range of days as a WebM
  video, and for admins download the data zip.
- Marker layers. A Java API for other plugins in `dev.ryvrook.atlas.api` with points, lines, areas
  and circles, tiers, zoom ranges and an enable and disable lifecycle that survives `/atlas reload`.
  A drop folder in `markers` for JSON layer files, picked up within five seconds. A `layers`
  visibility key, the route `/api/layers/{world}` and the `layers` stream event.
- Admin markers with `/atlas marker add`, `remove`, `list` and `area add`, saved in
  `markers/admin.json`.
- Per world settings in `worlds` with `enabled`, `displayName`, `hidden` and `renderLimit`, and
  `defaultWorld` for the world the map opens on.
- A hide toolkit. `/atlas hide <player>` and `/atlas show <player>` for staff, `/atlas show` for
  yourself, automatic hiding while crouching or invisible, `AtlasApi.setHidden` for vanish plugins,
  and the reason a player is hidden shown to admins.
- `/atlas unlink <player>`, which signs every browser of a player out.
- `/atlas tiles purge` and `rerender`, and `/atlas render status`, `radius`, `pause`, `resume`,
  `cancel` and `purge` to watch and steer background renders.
- Per address rate limits for API and tile requests, answered with 429 and `Retry-After`.
- A global chunk image budget. When it is spent a browser gets the cached or a partial tile at once.
- Idle pre rendering of explored tiles that have no saved copy yet.
- An age check that compares saved tiles with the current chunk images and redraws the ones that
  changed.
- A world stamp in every tile folder, so a world replaced under the same name gets fresh tiles.
- ETags and 304 for tiles, gzip for scripts and stylesheets, HEAD on every GET route.
- Event ids on the stream and a resume of up to 200 missed frames within a minute.
- A bind retry every 30 seconds for 10 minutes when the port is taken, with a hint when another map
  mod seems to hold it.
- Degraded states in `/api/health` for admins, such as a render backlog, a stalled world poll,
  failing tasks, disk write failures and the bind retry.
- A leaderboard with rarity per feat, a rare tag, milestones, server firsts, the online count and the
  last unlock of every player.
- A bar that asks the viewer to reload after the server was updated.
- The map address printed by `/atlas`, `/webmap` and `/atlas admin` can be clicked in game, and the
  address in the `/atlas link` message opens the map, sends the code and removes it from the address
  bar.
- Accessible names on every map marker, and culling of markers far from the view in worlds with more
  than 300.
- A JUnit 5 test suite that runs with `./gradlew build`.
- docs/API.md, the reference for API version 1.

### Changed

- The browser API is frozen as version 1 with `apiVersion: 1` in `/api/worlds` and `hello`. Every
  payload is built from an explicit field list, and nothing from the factions snapshot file or the
  Feats export is passed through raw. Every key is always present, times are epoch milliseconds and
  durations are seconds in keys that end in `Seconds`.
- A layer the viewer may not see answers 403 and a missing thing 404, both with
  `{"ok": false, "error": "..."}`, instead of 200 with `{"hidden": true}`.
- Renamed fields. A world's `hidden` is now `hiddenWorld`, `uptimeMs` is now `uptimeSeconds`, and an
  offline row's `lastSeen` is now `at`. Offline rows gain `uuid`.
- Markers gain `source` next to `kind`. Players gain `hiddenReason`. Worlds gain `renderLimit`, and
  `/api/worlds` gains `defaultWorld`.
- The player count of a world is null when the viewer may not see players.
- `feedlog`, `chatlog` and `killlog` carry `{"items": [...]}` instead of a bare list.
- Block activity moved from `/api/changes/{world}` to its own route, `/api/heat/{world}`.
- Session tokens are stored as SHA-256 hashes.
- An `adminToken` shorter than 24 characters is replaced with a new random token, with a warning in
  the log. Wrong admin tokens count against the same per address limit as link codes.
- Open event streams check the admin token again every 30 seconds, so a changed token applies
  without a reconnect.
- `/api/health` answers only `ok` and `version` unless the request carries admin access.
- Portal and instance worlds a viewer may not open return 404 on tiles, markers and other world
  routes, and their kills, feed lines, world events and tile updates no longer reach that viewer.
- The page and its files carry a Content Security Policy, `Referrer-Policy` and
  `X-Content-Type-Options`, and JSON answers carry `X-Content-Type-Options` too.
- At most 64 requests are handled at once, event streams aside, and a client gets 30 seconds to send
  a request.
- A slow event stream drops its oldest data frame when full and never a control frame. A client that
  takes no data for 15 seconds is closed. The reconnect delay is 3 to 5 seconds, different per client.
- IPv6 addresses are grouped per /64 for every limit, and every spelling of an address counts as one.
  Link local fe80::/10 peers count as local.
- The BetterMap world border applies only to the worlds BetterMap tracks.
- Hidden players are "A player" in Feats milestones and firsts too.
- The version is set in one place in `build.gradle.kts` and written into the manifest and the
  version Atlas reports.
- The client reconnects with a random delay and spreads its refetches over one second, and a 429
  pauses its requests until `Retry-After` has passed.

### Fixed

- `atlas.json` is no longer rewritten on every load.
- Chunks that no longer exist are dropped from the explored index, so their tiles are drawn again.
- Tiles are written with a unique temporary name and flushed before they are moved into place, and
  leftover temporary files are removed at start.
- The background render queue is bounded, keeps one task per tile and is given five seconds to
  finish at shutdown. Repeated render failures are logged once and then at most every ten minutes.
- Changes to warps, block markers and portal markers now send a `markers` event.
- The data of a removed world, such as heat, markers, world events and tiles in memory, is released.
- `heat.json` is written only when it changed.
- A Feats export that cannot be read is logged once and the last good copy stays in use.
- A factions snapshot file with an unexpected version is logged once and still read.
- Players in the factions snapshot may have null or missing coordinates. They are listed without a
  position instead of being dropped.

### Removed

- `/api/bosses` and the `boss` stream event. Server 0.7.0 exposes no boss bar state, so they were
  always empty. The route now answers 404.
- Heat in `/api/changes/{world}`.
- Compatibility code in the web client for servers older than 0.2.0.

## 0.2.2

- BetterMap support with hide rules, waypoint heights and the world border.
- Duplicate markers are listed once.
- Browsers pick up a new build without a hard refresh.

Upgrading adds the settings `honorBetterMap` (default `true`) and `betterMapDir` (default
`mods/BetterMap`).

## 0.2.1

- Shared player markers for everyone, and private markers, beds and deaths for their owner.
- Markers tab, marker layer chips and live marker updates.

Upgrading adds the setting `showMarkerAuthors` (default `true`).

## 0.2.0

- Rewritten web client with themes, phone layout, follow, measure, share links and shortcuts.
- Chapter I data with engine markers, world events, kill feed, portal worlds and an optional Feats
  leaderboard.
- Server hardening and privacy defaults. Requires the Hytale 0.7.0 release server.

Upgrading adds the settings `basePath`, `showPortalWorlds`, `featsExport` and `sseClientsPerIp`,
and the visibility keys `markers`, `worldevents`, `kills`, `leaderboard` and `lastSeen`. The
defaults of `playerUpdateHz` (now `1`), `hidePlayersInOwnTerritory` (now `true`),
`visibility.players` and `visibility.feed` (both now `linked`) changed and only apply to new files.

## 0.1.0

- First release with terrain tiles and live players.
