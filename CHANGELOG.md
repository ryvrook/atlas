# Changelog

Every release keeps the values an existing `atlas.json` already holds. Keys a release adds are
filled in with their defaults and written back on the next start. Changed defaults only apply to
new files.

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
