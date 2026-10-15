# Atlas settings

`atlas.json` lives in the plugin data directory, `mods/Ryvrook_Atlas`. On first start Atlas writes it
with every option. Later starts add missing keys with their defaults and otherwise leave the file
alone, so existing values are kept. Unknown keys are reported with a warning and kept in the file.
Run `/atlas reload` after editing. When `bind` or `port` changed, Atlas binds the new address first
and stops the old server only once that worked.

**An invalid file fails closed.** When `atlas.json` is not valid JSON, holds a value of the wrong
type (a port in quotes, `"yes"` for a switch) or a value out of range (a port above 65535, a negative
limit, `tileCacheMb` under 4, an unknown visibility level such as `pubic`), Atlas keeps its web server
down and logs one SEVERE line that names the problem. A reload with an invalid file takes a running
map down the same way, so a setting you meant to tighten can never stay loose. Fix the file and run
`/atlas reload`.

| Key | Default | Meaning |
| --- | --- | --- |
| `bind` | `0.0.0.0` | Address the web server binds to |
| `port` | `8123` | Port of the web server, 1 to 65535 |
| `publicTitle` | `Atlas` | Title shown in the browser |
| `publicUrl` | empty | Address printed by `/atlas` and sent to browsers |
| `basePath` | empty | Sub path stripped from requests behind a reverse proxy, such as `/map` |
| `cookieName` | `ryvrook_session` | Name of the session cookie, 1 to 32 letters, digits or underscores. Chronicle uses the same name |
| `cookieDomain` | empty | Domain attribute of the session cookie, such as `example.com`. Empty sends none |
| `showPlayers` | `true` | Show players to non admins at all |
| `showPlayerHealth` | `false` | Include health in player data for non admins |
| `hideBelowY` | `0` | Players below this height count as underground and non admins get no position for them |
| `playerUpdateHz` | `1` | Player pushes per second over the event stream, above 0 and clamped to 0.1 to 10 |
| `showPortalWorlds` | `false` | Show portal and instance worlds to non admins |
| `featsExport` | see below | Optional leaderboard export written by the separate Feats plugin |
| `chronicleExport` | see below | Optional stats export written by the separate Chronicle plugin |
| `chronicleUrl` | empty | Address of the Chronicle web page, linked from the Chronicle tab. Starts with `http://`, `https://` or `/` |
| `sseClientsPerIp` | `8` | Event stream connections per address, next to a global limit of 500. At least 1 |
| `apiRequestsPerMinute` | `600` | API requests per minute and address. 0 means unlimited |
| `tileRequestsPerMinute` | `3000` | Tile and marker icon requests per minute and address, timeline tiles included. 0 means unlimited |
| `chunkImagesPerMinute` | `1200` | Chunk images the tile renderer may ask the engine for per minute, shared by every render. 0 means unlimited |
| `tileMaxAgeHours` | `24` | Saved tiles older than this are checked against the current chunk images. 0 turns the check off |
| `preRenderIdle` | `true` | Draw explored tiles that have no saved copy yet while nobody requests tiles |
| `showMarkerAuthors` | `true` | Show who placed a player made marker. When false the creator is left out everywhere |
| `hidePlayersCrouching` | `false` | Hide a player's position from non admins while they crouch |
| `hidePlayersInvisible` | `true` | Hide a player's position from non admins while they are invisible |
| `tileCacheMb` | `64` | Memory budget for encoded tiles in MB, at least 4 |
| `adminToken` | random | Admin secret, sent as the `X-Atlas-Token` header or as a Bearer token |
| `dominionSnapshot` | see below | Path of the optional factions snapshot file |
| `honorBetterMap` | `true` | Apply BetterMap's server wide rules when its settings file exists |
| `betterMapDir` | `mods/BetterMap` | BetterMap's folder, relative to the server working directory |
| `tracker` | `auto` | Where the heat layer and the kill feed come from. `auto` uses TrackerAPI when it is installed, `always` only TrackerAPI, `never` only Atlas's own systems. Read at server start |
| `showOwnMoney` | `false` | Show a linked player their own Coinage wallet and net worth in the account menu. Needs Coinage |
| `trustedProxies` | empty | Reverse proxies whose forwarded headers are believed, as addresses or CIDR ranges such as `10.0.0.0/8` |
| `visibility` | see below | Who sees each layer |
| `defaultWorld` | empty | World the map opens on. Empty means the server's default world |
| `worlds` | empty | Settings per world, see [Per world settings](#per-world-settings) |
| `timeline` | see below | Daily snapshots, see [Timeline](#timeline) |
| `isoEnabled` | `true` | Serve 3D views |
| `isoCacheMb` | `64` | Memory for rendered 3D views in MB, at least 4 |
| `isoDiskMb` | `512` | Disk for rendered 3D views in MB. 0 keeps none on disk |
| `isoMaxRadius` | `16` | Largest 3D view radius in chunks a viewer may ask for, 1 to 16 |
| `isoRequestsPerMinute` | `20` | New 3D renders one address may start per minute. 0 means unlimited |
| `isoViewsPerAddress` | `4` | 3D views one address may hold in the render queue, 1 to 16 |
| `isoLargeViewsPerAddress` | `2` | Large 3D views one address may hold waiting in the queue, 1 to 4 |
| `isoKeyPlaceLimit` | `3` | Key places `iso.keyPlaces` may hold, 1 to 10 |
| `iso` | see below | Daily 3D views, see [3D views](#3d-views) |

`hidePlayersInOwnTerritory` (default `true`) hides players standing in their own faction land from
other factions. It needs the factions snapshot file.

`featsExport` defaults to `mods/Ryvrook_Feats/export/feats.json`, `chronicleExport` to
`mods/Ryvrook_ChronicleStats/export/stats.json` and `dominionSnapshot` to
`mods/Ryvrook_Dominion/export/snapshot.json`, all relative to the server working directory. Chronicle
wrote under `mods/Ryvrook_Chronicle` before 1.1.0, so while `chronicleExport` is either default path and
its file is missing, Atlas reads the other one.

`adminToken` is filled with a random 32 character value when empty. A value shorter than 24
characters is replaced with a random one and a warning is logged.

> **Warning** `adminToken` grants full access, including hidden players and exact coordinates. Keep `atlas.json` private.

**Tile settings in practice.** Browsers may use the whole chunk image budget, background work only
its upper half. When the budget is spent a browser gets the cached tile or a partial tile at once
instead of waiting. The age check runs on the ten minute refresh. A tile whose chunk images did not
change is kept and dated again, one that changed is drawn again. Idle pre rendering starts after 30
seconds without a tile request and draws one tile at a time within the background half of the budget.

## Visibility

Each value is `public`, `linked`, `faction` or `admin`. `faction` means linked viewers who see only
the data of their own faction. Any other value makes the file invalid.

| Layer | Default | Covers |
| --- | --- | --- |
| `terrain` | `public` | Map tiles, and the tiles and changed list of timeline days |
| `players` | `linked` | Player markers and online counts |
| `zones` | `public` | Zone name labels from the world map |
| `markers` | `public` | Engine markers, player made markers and the Markers tab |
| `layers` | `public` | Marker layers from plugins, files and `/atlas marker` |
| `timeline` | `public` | The Timeline tab, day list, stats and events |
| `iso` | `public` | 3D views, which also need `terrain` |
| `worldevents` | `public` | Active world events |
| `kills` | `public` | Kill feed |
| `leaderboard` | `public` | Feats leaderboard |
| `stats` | `public` | Stats tab |
| `lastSeen` | `linked` | Offline player list, only sent when `players` is visible too |
| `feed` | `linked` | Live feed |
| `heat` | `admin` | Activity heat overlay |
| `paths` | `admin` | Player paths |
| `chat` | `admin` | Public chat |
| `territories` | `public` | Faction land, from the snapshot file |
| `points` | `faction` | Snapshot points |
| `factions` | `public` | Faction list and details |
| `nations` | `public` | Nations panel and the nation shown on a faction |
| `events` | `public` | Hills, outposts, envoys, bounties and the season |

Players and the feed are `linked` by default so a stranger who finds the map cannot follow anyone
around. Hill, outpost and envoy points follow `events` instead of `points` and are never limited to
one faction, since every faction may contest them. A marker layer also has its own tier, and a
viewer must meet both the `layers` setting and that tier.

## Timeline

`timeline.perDay` times a day (4 by default), at evenly spaced hours from `timeline.hour` (server local time, 4 by
default), Atlas takes a snapshot of every plain world that has tiles, so 04:00, 10:00, 16:00 and 22:00 with the
defaults. Portal, instance and disabled worlds are left out. If the server was down when a slot began, the latest
missed slot is taken five minutes after the next start.

With `skipUnchanged` on, a scheduled snapshot is dropped when no zoom 0 tile changed since the slot before and no
event happened in between. With `events` off, Atlas asks its activity counters for the same span instead. The first
snapshot of a world and every `/atlas snapshot` are always kept. A skipped slot leaves no gap, the slot before
covers its hours.

A snapshot stores each tile image once by hash, so a slot where little changed costs a few kilobytes. Each slot
gets stats for the 24 hours before it, or back to the last kept slot, up to seven days. The event log keeps at most
5,000 events a day per world. Days older than `retainDays` are pruned, oldest first while the timeline is over
`maxDiskMb`. Day folders from Atlas 1.1 or older count as one slot of their day.

The keys sit under `timeline`.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Take snapshots and keep the event log. Off answers 404 on every timeline route |
| `hour` | `4` | Server local hour of the first snapshot of the day, 0 to 23 |
| `perDay` | `4` | Snapshots a day at evenly spaced hours from `hour`, one of 1, 2, 3, 4, 6, 8, 12 or 24 |
| `retainDays` | `90` | Days kept. 0 keeps only the newest day |
| `maxDiskMb` | `2048` | Disk the timeline may use in MB. 0 means no cap |
| `events` | `true` | Write the daily event log |
| `skipUnchanged` | `true` | Drop a scheduled snapshot when no block changed and no event happened since the one before. With `events` off the activity counters decide instead |

## 3D views

The `iso*` keys above set the caches and the limits. Views are read from explored chunks only, inside the
render limit, and every chunk read that is not in memory takes a token from the background half of
`chunkImagesPerMinute`, so a large view never slows the tiles people look at. A radius 4 view takes under a
second, a radius 16 view up to a minute with nothing cached.

**Daily views.** List areas in `iso.snapshots` and each one is drawn into the timeline with the first
snapshot kept that day in its world, so a day with every snapshot skipped has none. The Timeline tab shows them for the picked day. Labels are lower case
letters, digits, dashes and underscores, unique per world, and the radius defaults to 4. With
`iso.snapshotAdminMarkers` set, every server marker of the world is drawn too, as `marker-<id>`, but
only while the server marker layer is public, so private marker positions never reach the timeline.
A world gets at most 64 daily views. They are kept and pruned with the day they belong to.

```json
"iso": {
  "snapshots": [{"world": "default", "x": 1280, "z": 416, "radius": 4, "label": "spawn"}],
  "snapshotAdminMarkers": false
}
```

**Key places.** Up to three places (`isoKeyPlaceLimit`, 1 to 10) are the curated set on top of
`iso.snapshots`. With the first snapshot kept each day every key place of the world is drawn from all four
directions, at scale 2 (scale 1 above radius 8), into the timeline as `iso/<label>-<azimuth>.png`. The
renders take the background path of the daily views. Before drawing a place that was drawn before, Atlas
reads its chunks without drawing them and compares their fingerprint with the one kept with the last
render. When nothing changed the pictures of that render are linked into the new day (copied where the
file system has no links), so a quiet day costs no disk, and the day marks them unchanged. A key place
has a `world`, `x` and `z` in blocks, a `radius` in chunks (6 by default, 1 to 16, held to
`isoMaxRadius`), a `label` like the labels above that is unique across worlds, and a `name` of up to 60
characters shown in the browser.

```json
"iso": {
  "keyPlaces": [{"world": "default", "x": 1280, "z": 416, "radius": 6, "label": "town_hall", "name": "Town Hall"}]
}
```

Admins add and remove key places with `/atlas key add`, `/atlas key remove` or the Admin tab. Both write
the list into `atlas.json` with every other value kept as it is, check the whole file again and apply the
change at once. A file that does not parse is left alone and the change is refused. `/atlas key render
<label>` draws a place now, into today's timeline when today has a snapshot of its world, otherwise under
`iso/exports`.

## Per world settings

```json
"defaultWorld": "default",
"worlds": {
  "default": {"displayName": "Overworld", "renderLimit": {"x": 0, "z": 0, "radius": 5000}},
  "creative": {"hidden": true},
  "old_world": {"enabled": false}
}
```

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | `false` takes the world off the map for everyone, admins included |
| `displayName` | null | Name shown in the world menu and everywhere else on the page |
| `hidden` | null | `true` hides the world from non admins, `false` shows it |
| `renderLimit` | null | A circle with `x`, `z` and `radius` in blocks |

A world not listed uses the defaults. When `hidden` is null, portal and instance worlds follow
`showPortalWorlds` and every other world is shown. A hidden world answers 404 to non admins on every
route that names it, and its kills, feed lines, world events and tile updates never reach them.

A disabled world is not rendered and every route that names it answers 404. Switching a world off
drops its tiles from memory and its queued renders. Its tiles on disk stay, so switching it on again
brings it back as it was.

Tiles outside a render limit are neither rendered nor served. The browser draws a faint dashed line
at the edge and keeps the view from wandering far past it. A wrong type or a radius of 0 or less makes
`atlas.json` invalid. When `/atlas reload` changes any world setting or `defaultWorld`, open pages
fetch the world list again.

## BetterMap rules

Atlas contains no BetterMap code and only reads BetterMap's files. When `honorBetterMap` is true and
`config.json` exists in `betterMapDir`, Atlas applies BetterMap's server wide settings to every
viewer, admins included, so the web map shows what the in game map shows.

- `hideSpawnOnMap`, or `spawn` in `hiddenPoiNames`, drops the spawn marker.
- `hideAllPoiOnMap` drops every point of interest and block marker. `hiddenPoiNames` drops matching ones.
- `hideUnexploredPoiOnMap` and `hideUnexploredWarpsOnMap` drop points and warps outside explored chunks on the worlds in `allowedWorlds`.
- `hideAllWarpsOnMap`, `hideGlobalWaypointsOnMap` and `hideDeathMarkerOnMap` drop warps, shared markers and deaths.
- `worldBorderEnabled` with its radius and offsets gives the worlds BetterMap tracks a `border` drawn as a dashed circle. Without `allowedWorlds` those are `default` and `world`.
- Waypoint heights from BetterMap's data files fill `y` of shared and personal markers.

The settings file is checked every 15 seconds, so no reload is needed. BetterMap's per player
settings and exploration files are not used. Atlas only reads BetterMap's folder and never writes to it.

## Data files

All files live in the plugin data directory, `mods/Ryvrook_Atlas`.

| File | Contents |
| --- | --- |
| `atlas.json` | Settings |
| `sessions.json` | Linked browsers, stored as hashes of their session tokens |
| `hidden.json` | Players hidden with `/atlas hide` |
| `heat.json` | Block activity per chunk in ten minute buckets for seven days |
| `kills.json` | The last 200 kills |
| `lastseen.json` | Where each player was last seen |
| `transient-worlds.json` | Portal and instance worlds whose tiles are removed once the world is gone |
| `tiles/` | Rendered tile cache, one folder per world with a `world.json` stamp |
| `markers/` | Marker layer files, one layer per file |
| `markers/admin.json` | Server markers from `/atlas marker` |
| `snapshots/` | The timeline |
| `iso/` | Cached 3D views, one folder per world, and `iso/exports` for views drawn by command |

Inside `snapshots`, `blobs` holds the tile images by content hash and `worlds` holds one folder per
world with a folder per slot, such as `2026-10-13T16`, for its tile list, summary, stats and, in the
first slot of a day, an `iso` folder of daily 3D views and key places, and an `events` folder with one
`<date>.jsonl` file per day. A folder named by a plain date is a day from Atlas 1.1 or older.
`activity.json` and `state.json` hold the counters for the daily stats and the last slot taken.
