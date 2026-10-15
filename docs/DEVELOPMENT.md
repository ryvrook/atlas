# Developing Atlas

This guide is for contributors. It covers the source layout, the tests, how to build and smoke test
Atlas on a headless server, how tiles are made, which threads do what, how markers are merged, how
marker layers and the timeline are stored and how the BetterMap integration reads its files. The
README covers installing and configuring Atlas, and [API.md](API.md) is the browser API reference.

## Source layout

Everything lives under `src/main/java/dev/ryvrook/atlas`.

| Package | What it holds |
| --- | --- |
| root | `AtlasPlugin`, the plugin entry and scheduler, and `Atlas`, the shared state and the JSON routes |
| `api` | The public marker API and the session API for other plugins. The only package other mods may compile against |
| `auth` | Link codes, sessions, the session API behind `SessionApi`, viewers and the rate key of an address |
| `command` | `/atlas` and its sub commands |
| `config` | `atlas.json` parsing, validation and visibility levels |
| `data` | Engine reads, the factions snapshot, Feats, the Chronicle export, kills, heat, last seen and the payload builders |
| `integration` | Plugin presence checks, the version compare, the Dominion watch, and `TrackerBridge` and `CoinageBridge`, the only classes that name TrackerAPI and Coinage types |
| `iso` | 3D views, the chunk reader, the raster, the colour table, the PNG encoder, the cache, daily views and the routes |
| `http` | The embedded HTTP server, the event stream hub, rate limits, trusted proxies and marker icons |
| `markers` | Marker layers behind the API, the drop folder, the admin layer and the layer route |
| `systems` | Engine systems that watch deaths, blocks and chunk saves. The block and death systems are only registered without TrackerAPI |
| `tiles` | Tile keys, the explored index, chunk image decoding, the render queue and the tile cache |
| `timeline` | Snapshots, the blob store, manifests, the event log, daily stats and the timeline routes |
| `util` | JSON helpers and atomic writes |

The web client lives in `src/main/resources/web` with `core.js`, `app.js`, `map.js`, `panel.js`,
`timeline.js`, `iso.js`, `export.js`, `style.css`, `index.html` and the bundled Leaflet under `vendor`.

`iso.js` holds the 3D lightbox. It reads `api/iso` with the other shared data, asks for views with
`fetch` so a 202 answer can be read, polls after `etaSeconds`, keeps the last six pictures as blob
addresses and builds the share link from the `X-Atlas-Iso` header of the picture on screen. Pan and
zoom are CSS transforms on the image, and the compare slider clips the older day's layer with
`clip-path`. `export.js` draws the view onto a canvas from the same tile addresses the map uses,
records with `canvas.captureStream` and `MediaRecorder`, and pauses the recorder while each day's
tiles load so slow tiles never stretch a day. A click on a marker opens `A.openActions`, a popup with
the hover card and its buttons.

`Payloads` in `data` builds every API body from an explicit field list. A new field goes there and
into [API.md](API.md). Version 1 may only grow, so never rename, retype or remove a field.

## Building

Atlas needs JDK 25. When no JDK 25 is installed, the foojay toolchain resolver in
`settings.gradle.kts` lets Gradle download one. Point `JAVA_HOME` at any JDK that can run Gradle
and build with the wrapper.

```sh
./gradlew build
```

The jar lands in `build/libs/Atlas-<version>.jar`. The Hytale server artifact comes from the Hytale
Maven repository, and `hytale_channel` and `hytale_version` in `gradle.properties` select it.

The TrackerAPI and Coinage jars are compile and test dependencies only and are never bundled. Gradle
looks for `../trackerapi/build/libs/TrackerAPI-1.0.0.jar` and for
`../coinage/build/libs/Coinage-api-1.1.0.jar`, then the same Coinage jar under `~/Projects/coinage`.
`-PtrackerApiJar=/path/to/TrackerAPI-1.0.0.jar` and `-PcoinageJar=/path/to/Coinage-api-1.1.0.jar` point
them elsewhere.

The version is set once, as `version` in `build.gradle.kts`. `processResources` writes it into
`manifest.json` in place of `@version@`, and a generated `version.txt` next to `AtlasVersion` carries
it to the code. Never edit the version anywhere else.

## Tests

`./gradlew build` runs the JUnit 5 tests under `src/test/java`, and `./gradlew test` runs them alone.
Only failures are printed, with the full stack trace. The tests need no running server. They cover
pure logic and use the Hytale server jar only for types.

| Package | Tests |
| --- | --- |
| `auth` | Rate keys, revoke, linked name lookup, the attempt limit and the session API with its events and threads |
| `config` | Parsing, defaults, added keys, the fail closed cases, the cookie and Chronicle keys and per world settings |
| `data` | Marker merging, the hidden player rule, payload field lists, Feats diffs, the Chronicle export reader and the kill log file |
| `http` | Path safety, rate limits, rate keys, trusted proxies, the event stream queue and the move from the old session cookie |
| `integration` | The `tracker` decision, the reload watch, `TrackerBridge` fed synthetic TrackerAPI records with the heat and kill lines the block and death systems make, the Dominion watch and the own money route |
| `iso` | View parameters, the cache keys and prune, the raster on synthetic columns and the iso config |
| `markers` | The drop folder parser, the layer payload and tiers, popup escaping, timeline visibility and Dominion's siege layer giving way to the snapshot |
| `tiles` | Tile key math, the explored index, chunk image decoding, the render queue and the token bucket |
| `timeline` | The blob store, manifest diffs, block changes, the skip decision and the catch up, carried and drawn snapshot tiles, slots filled in from earlier slots, the export contents, slot windows, pruning with tombstones, the event log bound, event places, route parsing and the export range |

Add a test next to the code you change. A change to a payload needs a case in `PayloadsTest` or
`LayerJsonTest` that pins the field list.

## Smoke test on a headless server

You need `HytaleServer.jar` and `Assets.zip` from a Hytale install or the server download. The
commands below use `$HYTALE` for the folder that holds `Server/HytaleServer.jar` and `Assets.zip`,
and `$JAVA` for a Java 25 binary.

1. Make an empty server folder, create `mods` in it and copy the built jar there.
2. Give the server a `permissions.json` containing `{"users":{},"groups":{}}`. A server without one
   can exit right after start.
3. To keep the map off the default port, create `mods/Ryvrook_Atlas/atlas.json` with only
   `{"bind": "127.0.0.1", "port": 8190}`. Atlas fills in every other key on first start. Pick ports
   of your own when several test servers run at once.
4. Copy a saved world from an earlier run, its `universe` folder and `config.json`, into the server
   folder. Its explored chunks are read at start, so tiles work at once. Without one, start the
   server without `--bare` so it generates a world. A bare server loads no world and every tile
   would return 404.
5. Start the server from that folder with `chunk load` lines on its console and a time limit, so a
   test run never leaves a server behind. Wait for the world before sending commands and keep the
   pipe open afterwards so the console stays attached.

```sh
cd /path/to/testserver
( sleep 60; printf 'chunk load 0 0\nchunk load 1 0\nchunk load 0 1\n'; sleep 600 ) | \
  perl -e 'alarm 660; exec @ARGV' -- "$JAVA" -jar "$HYTALE/Server/HytaleServer.jar" \
  --assets "$HYTALE/Assets.zip" --auth-mode offline --disable-sentry --bind 127.0.0.1:5521 > boot.log 2>&1
```

`perl -e 'alarm N'` ends the server after N seconds with exit code 142. Console commands such as
`atlas snapshot default`, `atlas render status` or `atlas marker add default spawn 0 0 Spawn` can be
added to the `printf` line, since Atlas commands also run from the console.

Loaded chunks are picked up within 30 seconds. Then check the server from another shell. `$TOKEN`
is the `adminToken` from the test server's `atlas.json`.

```sh
curl -s http://127.0.0.1:8190/api/health
curl -s -H "X-Atlas-Token: $TOKEN" http://127.0.0.1:8190/api/health
curl -s http://127.0.0.1:8190/api/worlds
curl -s -o tile.png -D - http://127.0.0.1:8190/api/tiles/default/0/0/0.png | grep -i -e '^HTTP' -e etag
curl -sI http://127.0.0.1:8190/ | grep -i -e content-security-policy -e referrer-policy -e nosniff
curl -s -H 'Accept-Encoding: gzip' -o /dev/null -D - http://127.0.0.1:8190/app.js | grep -i content-encoding
curl -s http://127.0.0.1:8190/api/layers/default
curl -s http://127.0.0.1:8190/api/timeline/default
curl -s -D - -o view.png 'http://127.0.0.1:8190/api/iso/default?x=0&z=0&r=4&s=2' | grep -i -e '^HTTP' -e etag
grep -n 'dev.ryvrook' boot.log
```

What to expect.

- `api/health` answers `{"ok":true,"version":"..."}` without a token, and with it adds uptime,
  stream, tile cache and render figures, `status` and `degraded`.
- `api/worlds` carries `apiVersion` 1 and lists the world with `exploredChunks` above 0 and a
  `bounds` object.
- The tile request returns 200 with a 256 pixel PNG and an `ETag` near spawn, and 404 far from
  anything explored. The same request with `If-None-Match` set to that ETag returns 304.
- The page carries the Content Security Policy, `Referrer-Policy` and `X-Content-Type-Options`, and
  `app.js` comes back gzipped.
- `api/layers/default` lists any layer you dropped into `mods/Ryvrook_Atlas/markers`.
- `api/timeline/default` lists a day with `snapshot` true once `atlas snapshot default` ran, and
  `skipUnchanged` true. A second snapshot on the same day writes no new blobs, and its reply says no
  blocks changed since the slot before.
- The 3D view answers 202 with `etaSeconds` first and 200 with a PNG when asked again after that time.
- `boot.log` holds the Atlas start lines and no stack trace that mentions `dev.ryvrook`.

To check linking without a browser, post a code to `api/link` and send the cookie back. A code comes
from `/atlas link` in game. `/atlas link` needs a player, so on a headless server use a small test
plugin that calls `SessionApi.get().issueCode(uuid, name)` from a console command of its own. `$CODE`
is that code and `$TOKEN2` the cookie value the link answer set.

```sh
curl -s -D - -c cookies.txt -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\"}" http://127.0.0.1:8190/api/link
curl -s -b cookies.txt http://127.0.0.1:8190/api/worlds | grep -o '"linked":[a-z]*'
curl -s -D - -o /dev/null -H "Cookie: atlas_session=$TOKEN2" http://127.0.0.1:8190/api/worlds | grep -i set-cookie
curl -s http://127.0.0.1:8190/api/chronicle
```

- The link answer sets `ryvrook_session`, and the worlds answer with that cookie says `"linked":true`.
- A request that carries the same token as `atlas_session` is linked too and its answer sets
  `ryvrook_session` and clears `atlas_session`.
- After a `stats.json` in the shape Chronicle writes is copied into `mods/Ryvrook_ChronicleStats/export`,
  `api/chronicle` answers `"available":true` with its totals and boards within five seconds.

To check that a broken config fails closed, put `{"port": "x"}` in `atlas.json` before boot and expect
one SEVERE line and no web server, then fix the file and run `atlas reload` on the console. To check
the bind retry, hold the port with `nc -l 8190` before boot.

Open the page in a browser and check the console. The client must load without Content Security
Policy reports. The policy allows scripts and styles from the same origin, inline style attributes,
the one inline boot script in `index.html` by its hash, and images from the same origin, `data:` and
`blob:`. New client code must not add inline scripts or event handler attributes, and labels from
marker layers must be set as text, never as HTML. Run `node --check` on every client script after a
change.

## Tiles

Zoom 0 tiles cover 16 by 16 chunks. A Hytale chunk is 32 blocks, so a tile spans 512 blocks and
each chunk becomes 16 by 16 pixels. Zoom 1 to 4 each halve the previous level. Zoom -1 and -2 are
native close up levels at 1 and 2 pixels per block, covering 8 by 8 and 4 by 4 chunks, drawn
straight from the chunk images. Tile x is the block x divided by the tile span of the zoom,
`chunksPerTile * 32`, rounded down, and the same goes for tile y and block z. `/api/worlds` lists
every level under `tile.levels`. Only one render runs per tile at a time, and a second request for
the same tile waits for the first.

Chunk images come from `WorldMapManager.getImageAsync`. Each `MapImage` has a width, a height, an
RGBA palette (red in the top byte, alpha in the low byte), a `bitsPerIndex` of 4, 8, 12 or 16 and
`packedIndices`. Indices are packed least significant bit first at bit offset index times bits,
row major. The default provider renders 96 by 96 pixel images per chunk (image scale 3), which
Atlas box filters down to 64, 32 and 16 pixels square for zoom -2, -1 and 0.

Only chunks that exist in the world save or in memory are rendered, so an unexplored area returns
404. The engine builds a chunk image from the chunk and its eight neighbours and would generate any
neighbour that is missing, so Atlas only asks for an image once the whole ring around a chunk
exists. The outermost ring of explored chunks therefore stays blank until the land beyond it is
explored, and the map never makes the server generate terrain.

A request fetches at most 64 chunk images and waits at most four seconds. Anything missing is
finished on a background thread and announced over the event stream. A tile gets three background
rounds. When chunk images still do not arrive it is served as it is, marked partial, for ten
minutes before it is tried again. At most four renders run at once and a fifth request gets 503.

Tiles are cached in memory up to `tileCacheMb` and on disk under `tiles` in the data directory.
When the engine saves a chunk its tiles are marked dirty and re-rendered at most every five minutes
per world.

Every chunk image the engine has to build takes a token from one bucket of `chunkImagesPerMinute`
shared by all renders. Images already cached or in memory cost nothing. Browser requests may drain it, background work only while it is above half. A request that
finds the bucket empty gets the cached tile or a partial one at once instead of waiting. Background
renders go through a queue of at most 8,192 tasks with one task per tile, worked by two
`Atlas-tiles` threads that get five seconds to finish at shutdown. `Atlas-tiles-control` runs the
idle pre render every five seconds and removes orphaned temporary files two seconds after start.

Each world's tile folder holds `world.json` with the world UUID and seed. A mismatch at start or
when the world is added clears the folder once. On the ten minute refresh, saved zoom 0 tiles older
than `tileMaxAgeHours` are compared with a hash of their current chunk images, at most 256 per pass,
and drawn again only when the images changed. Tiles are written to a temporary file with a unique
name, flushed with fsync and moved into place. Each tile carries a strong ETag made from its bytes.

Saved chunks are read from the world storage at start and every ten minutes, and chunks loaded in
memory are added every 30 seconds. A chunk that becomes explored after its tile was drawn has its
tile redrawn within about 30 seconds, without the five minute wait, and browsers are told over the
event stream. Its explored neighbours are redrawn with it, since they may have been blank frontier
chunks until it appeared. Tiles that were drawn after the chunk appeared are left alone. Tiles still
waiting for a refresh when the server stops are removed from disk so the next start draws them again.

## Thread model

- Periodic work runs on two `Atlas-scheduler` threads owned by the plugin. Nothing runs on the
  engine's shared scheduled executor.
- Tile renders run on two `Atlas-tiles` threads, and `Atlas-tiles-control` steers them.
- `Atlas-sse-watchdog` closes event streams whose writes stall for 15 seconds.
- 3D views render one at a time on the single `Atlas-iso` thread.
- HTTP requests run on virtual threads, one per request. At most 64 ordinary requests are handled
  at once and the rest get 503 with `Retry-After`. Event streams are not counted there because
  they have their own limits of `sseClientsPerIp` per address and 500 in total.
- A client gets 30 seconds to send its whole request, through the JDK server property
  `sun.net.httpserver.maxReqTime`. Atlas sets it only when the server owner has not.
- Anything that reads or changes a world hops to that world's thread with `world.execute`. Engine
  markers, shared markers, personal markers, world events and world info are all read there and
  cached for the other threads.
- A failing periodic task is logged once with its stack trace and then at most every five minutes
  while it keeps failing.
- Session API calls run on the caller's thread and never block on IO. Changes are queued and handed
  to the listeners by one task at a time on `Atlas-scheduler`, so listeners see them in order. The API
  is withdrawn first when the plugin stops.

The periodic tasks and their intervals.

| Task | Interval |
| --- | --- |
| Factions snapshot check | 1 second |
| Dominion presence at the default snapshot path | 5 seconds |
| World info and player health | 2 seconds |
| Player push over the event stream | `playerUpdateHz`, 1 per second by default, full list every 10 seconds |
| World watch for added and removed worlds | 5 seconds |
| World events | 5 seconds |
| Shared and personal markers | 5 seconds |
| Feats export check | 5 seconds |
| Chronicle export check | 5 seconds |
| BetterMap settings check | 15 seconds |
| TrackerAPI and Coinage reload watch | 5 seconds |
| Marker layer drop folder | 5 seconds, with the shared markers |
| Idle pre render | 5 seconds, after 30 seconds without a tile request |
| Timeline event log flush | 5 seconds |
| Timeline schedule check | 1 minute |
| Loaded chunks and dirty tiles | 30 seconds |
| Saved chunks, missing chunks and the tile age check | 10 minutes |
| Saving data files and the timeline activity counters | 5 minutes |
| Removing daily 3D views of pruned timeline days | 10 minutes |
| Pruning sessions, heat, last seen, caches and rate limit buckets | 10 minutes |

Engine markers are read on the world thread at most every ten seconds per world. Each open event
stream resolves its viewer again every 30 seconds.

## Markers

`/api/markers/{world}` lists every marker the in game world map would show, in one shape.

Shared markers are read straight from the world's marker store, the same store the game saves to
`resources/SharedUserMapMarkers.json`, so they show up even when a map mod replaces the engine
marker providers. Points of interest are read straight from the world map, and warps straight from
the teleport plugin's warp list, the same way the engine draws them. Portal exits and other provider
markers are still collected through the providers that do not depend on one player.

Personal markers, homes and deaths are read from the player while they are online and kept in
memory for an hour after logout. They are never written to disk and only go to the browser linked
to that player.

Every five seconds Atlas reads the shared markers of each world and the personal markers of online
players who have the map open. When the shared set of a world changes, everyone who may see markers
gets a `markers` event `{"world": "default"}`. When a player's own set changes, only that player's
streams get `{"world": "default", "own": true}`.

### Merge rules

After the privacy and BetterMap filters, one pass in `MarkerMerge` drops duplicates. Every entry has
a rank and the lower rank wins.

1. Shared markers from the world store.
2. The viewer's own personal markers, homes and deaths.
3. The world spawn.
4. Markers read from an engine store, which are points of interest, warps and block markers.
5. Markers that came through a provider.

Two entries count as the same marker when their ids match, when a provider marker is within one
block of a store marker with the same name ignoring case, or when the viewer's personal marker is
within one block of a shared marker the viewer placed with the same name. In that last case the
shared entry is kept and sent with `"own": true`. A null name only matches a null name.

Entries of the same rank are never merged by position, so two players' markers, two beds or two
deaths side by side all stay, and another player's shared marker never hides your personal one. A
home swallows a store or provider entry within one block of it that is a bed, which is one with the
`Home` icon, a name containing "bed" or the home's own name. A provider entry whose id starts with
`user_` is a player marker the store read already covers and is always dropped.

## Marker layers

`MarkerService` in `markers` holds every layer, whether it came from the API, a drop folder file or
`admin.json`. The API classes in `api` keep their own state under the layer lock and report each
change to a listener, which `MarkerService` turns into a `layers` event per world after a short
delay of 300 milliseconds, so a plugin that adds many markers causes one event. The tier is applied
in `LayerJson` when a layer is served, never when it is stored.

Drop folder files are parsed by `LayerFile` into a detached layer that is swapped in whole, so a
half read file never shows. A file that fails keeps its last good layer and logs once until its
modification time or size changes. The admin commands copy the admin layer, change the copy and
write `admin.json` atomically, and refuse to write while the file holds an error.

`AtlasApi` publishes the running instance through `enable` and `disable`. Hooks registered with
`onEnable` and `onDisable` live in static lists, so they survive `/atlas reload`, and
`removeHooks` takes them out. A `/plugin reload` of Atlas starts over with new classes, see Dominion
below.

## Session API

`SessionApi` in `api` is versioned on its own, so its version only moves when the session surface
grows. `SessionService` in `auth` implements it on top of `LinkService` and is published from the
plugin's start until its shutdown. `LinkService` reports each redeemed, adopted and revoked session to
one listener on the thread that made the change, and `SessionService` queues those events for the
scheduler. Sessions taken in with `adopt` live in the same map as every other session and are written
by the usual save of `sessions.json`, so nothing else touches the disk.

`ApiServer` reads the cookie named by `cookieName` and falls back to `atlas_session`, the name before
1.1.0. A valid session found only under the old name is set again under the new one on the same
answer and the old cookie is cleared. That fallback is meant to go in the release after 1.1.0.

## Timeline

`Timeline` owns the schedule. Once a minute it checks whether the daily run for `timeline.hour` is
due, and a run missed while the server was down follows five minutes after start. `SnapshotJob`
walks the saved tiles of a world in batches of 128 with a short pause between batches, hashes each
image with SHA-256 and stores new images in `BlobStore` under `snapshots/blobs`. Tiles mid write are
skipped and keep the image of the day before, which marks the day partial. Nothing runs on a world
thread.

The live renderer deletes the tiles of zoom 1 to 4 above a zoom 0 tile it draws again, and those of
zoom -1 and -2 below it, and draws them only when a browser asks. So the tile folder rarely holds every
tile at once. Once the files are read, `SnapshotJob` draws each missing tile of zoom 1 to 4 from the
four tiles of the zoom below with `TileService.composeUpper`, a few per step (one draw counts as 32
reads), when those quarters were
read or drawn in this snapshot. It reads no chunk image and never touches the world. A quarter that is
explored but missing leaves the tile to be carried over. Every tile of the slot before that is still
missing then keeps its hash and is counted in `carried`, since explored ground never goes away. Zoom
-1 and -2 are always carried, drawing them for a whole world would be too heavy. Such a manifest is
`complete`.

When a job finishes, `Timeline.decide` says what becomes of it, a pure function with its tests in
`TimelineTest`. `Manifest.blockChanges` counts the zoom 0 tiles whose hash differs from the slot before
or that the slot before lacked, and `SnapshotJob.blockChanges` adds the zoom 0 tiles that were skipped
mid write. Only zoom 0 counts because every other zoom is drawn from it. A tile drawn by the job,
newly asked for by a browser or redrawn by the live renderer with other bytes is a new key or hash to
`Manifest.diff` without any block change. With no block change, a scheduled run (the catch up
included, see `scheduledRequests`) while `timeline.skipUnchanged` is on flushes the event log and asks
`EventLog.countBetween` for the events after the slot before's `takenAt` and before its own, across
every day file in between, `snapshot` lines left out. The `dropped` line counts, it stands for the
events past the cap. A failed flush may have lost events, so the run is kept. While `timeline.events`
is off, `activityCount` adds up `ActivityTracker.since` and the heat data over the same span, capped
at seven days, and any activity keeps the run. None means the run is dropped
before `TimelineStore.write`, so no folder, summary, stats, iso views or `snapshot` event is written,
and images it stored that no slot uses go at the prune after the queue. The schedule still records the
slot as run in `state.json`. The first snapshot of a world, a slot that already holds a snapshot and an
admin snapshot are always kept, the last two with a note when no block changed. `skippedUnchanged`
counts the dropped runs for `/atlas timeline status`. The slot windows need nothing for a dropped
slot, `Slots.window` already ends a slot at the next slot held. The stats of the next run kept reach
back to the run kept before, see `Timeline.statsWindowMs`.

`Manifest` maps each tile key to its hash and lists the keys that changed against the previous day.
Manifests written by older releases are not complete. `TimelineStore.effectiveTiles` fills them in
from the slots before, walking back along `previous` (or the slot list where that slot is gone) up to a
complete manifest or the oldest slot held, through the manifest cache. Concurrent requests for one slot
share one walk, and the result is cached until the next write or prune. The tile route, the changed list, the day list counts,
the export and the next snapshot all read slots through it.
`TimelineStore` keeps one folder per world and day with `tiles.json`, `summary.json` and
`stats.json`. Pruning removes days past `retainDays`, then the oldest days while the total is above
`maxDiskMb`, never the newest snapshot of a world or today, and leaves `tombstone.json` so the day
stays listed as pruned. Blobs no other day uses are deleted with it. Tombstones more than 400 days
past the retention are forgotten.

`EventLog.record` queues events from anywhere and a flush every five seconds appends them to
`events/<date>.jsonl`. A day stops at 5,000 events with one `dropped` line. Events are stored with
real names and places, and `TimelineRoutes` applies the hidden player rule, the layer of each kind and
the chunk rounding when they are served, so un-hiding a player later works. A hidden player's events
lose their place for other viewers. Places the live map would not show, below `hideBelowY`, crouching
or invisible, or in the player's own territory, are stored as null when the event or kill is recorded,
through `Atlas.placeWithheld`, since y is never stored. `Slots.window` gives each slot of a day its hours, a plain date
slot taking the hours before the first slot with an hour, and the day list and the events route both
use it with the slots held that day. `ActivityTracker` counts activity
per world in hourly buckets for the daily stats and keeps eight days in `activity.json`. The stats of a
slot cover the 24 hours before its `takenAt`, or back to the `takenAt` of the snapshot kept before when
that is longer ago, up to `ActivityTracker.MAX_WINDOW_MS` of seven days, the heat data retention. The
span is stored as `windowSeconds`. It does not follow the slot window, so the stats of the slots of one
day overlap, while two spans that meet never count an hour twice.

## 3D views

`IsoService` owns the single `Atlas-iso` thread and the queue. Browsers may hold at most eight
renders, at most two wider than 17 by 17 chunks, and the thread rests two seconds after a large one.
Admin commands and the daily snapshot queue without those limits. The queue is prioritised, so a
browser view waiting in it is drawn first, and daily views and console renders follow in the order
they came, which keeps a long daily snapshot from holding up views people wait for. A render that cannot
get its chunk reads within two minutes is drawn with what it has.

`IsoRenderer` reads the chunk components directly, not the world map images. It filters the square
against the explored index and the render limit, asks the chunk store for chunks in batches of 16
with `GetChunkFlags.NO_GENERATE`, and reads `BlockChunk`, `HeightmapColumn`, `ChunkColumn` and the
block and fluid sections with `getComponentConcurrent` on the `Atlas-iso` thread, never a world
thread. As with tiles, every read of a chunk that is not already in memory takes a token from the
background half of the shared `chunkImagesPerMinute` budget. Views have no budget of their own on
purpose. Chunks loaded
this way never tick and the engine unloads them within about half a minute, saving them on the way
out, so `IsoChunkSaveSystem` ignores saves of chunks a render loaded for two minutes. Every other
save inside a view drops that view from the cache.

The height map counts leaves but not plants or fluid, which sit one above it. `Palette` takes each
block type's texture computed colour, with the particle colour or grey as fallbacks, and the biome
tint is applied per face. The table is rebuilt after an asset reload. `IsoRaster` draws one column
per block in depth order into an int array. Side faces at the edge and next to missing chunks drop a
fixed skirt of 12 blocks, leaf canopies drop at most three blocks with the trunk drawn under them,
and missing chunks are a grey floor. The light is fixed on screen, from the front left. Every top face
takes a soft cast shadow from a walk of at most 24 blocks toward the light over the highest surfaces,
trees included, bounded per 8 by 8 square of cells so open ground stops after a step or two. Water
takes its shadow on the surface. Top faces darken a little next to higher neighbours, the lowest row of
a side face is darker where it meets the ground, water is lighter along the shore, and a fluid that
gives off light, like lava, is never shaded. `PngEncoder` writes with the Sub filter and the fastest deflate
level. `IsoView` lowers the scale so a side stays within 1,100 block pixels.

`IsoCache` keys a view on the world, centre chunk, radius, direction, scale and a six hour bucket. It
keeps an LRU in memory within `isoCacheMb` and files under `iso/<world>/` within `isoDiskMb`, oldest
first out. File names end in `_v<style>`, the `IsoRaster.STYLE` of the renderer that drew them, and a
file of another style is deleted when the cache loads, so a change to the pictures reaches every view.
Bump `STYLE` whenever the same chunks would draw differently. The key place fingerprint mixes it in
too, so key places are drawn again once after such a change. Partial views stay in memory for a minute and never reach disk. `IsoSnapshots` draws the
daily areas into `iso/` of the day folder with an `index.json`, which the day list reads as
`isoViews`, and a sweep every ten minutes removes the views of days that were pruned.

## TrackerAPI

TrackerAPI is optional. The manifest lists `Ryvrook:TrackerAPI` under `OptionalDependencies`, so the
engine's plugin class loader resolves its classes for Atlas when it is installed.

- `setup` asks the plugin manager for `Ryvrook:TrackerAPI` through `TrackerPresence.find` and compares
  its manifest version with 1.0.0. `TrackerPresence.decide` turns that and the `tracker` key into a
  source. `OWN` registers `BlockActivitySystem` and `DeathSystem` as before, `TRACKER` and `NONE`
  register neither. One log line names the source.
- `start` calls `TrackerBridge.install` only for `TRACKER`. It hooks `TrackerApi.onEnable` and
  `onDisable`, and `bind` subscribes ASYNC to `BlockBroken`, `BlockPlaced` and `EntityDied` as owner
  `Atlas`. `shutdown` closes the link, which removes both hooks and every subscription.
- `TrackerBridge` is the only class that imports `dev.ryvrook.trackerapi`. The plugin reaches it through
  `TrackerLink`, so the bridge class is first loaded after the presence check.
- Breaks and places call `HeatTracker.record` with the event's world, x, z and time. Deaths build the same
  `KillLog.Kill` as `DeathSystem`, with the victim's place from the event and the killer's from
  `TrackerApi.snapshot`, and pass it through the same `PlaceRule` and the same sink to the kill log, the
  feed and the timeline. A creature is a boss when the event says so or `KillLog.isBoss` names it.
- The chunk save systems, chat, disconnect and the asset reload listener stay registered either way.
- A reloaded TrackerAPI brings new classes in a new loader. TrackerAPI 1.0.3 and newer reloads every
  enabled plugin that lists it two seconds after its own start, Atlas included. `TrackerPresence.Watch`
  compares the plugin object every 5 seconds and stays quiet for such a TrackerAPI. For an older one,
  or one that was unloaded, it logs one line asking for `/plugin reload` of Atlas.

## Coinage

Coinage is optional too and listed under `OptionalDependencies`. `start` asks the plugin manager for
`Ryvrook:Coinage` and, for 1.0.0 or newer, loads `CoinageBridge`, which hooks `CoinageApi.onEnable` and
`onDisable` and is the only class that imports `dev.ryvrook.coinage`. The plugin reaches it through
`CoinageLink`. Atlas only reads. `/api/money` asks `reports().netWorth` for the viewer's own player in
the primary currency and formats the `wallet` part and the total with `Currency.format`, only for a
linked player, not an admin viewing as a faction, and only while `showOwnMoney` is on. The account
menu fetches it each time it opens, so nothing is cached and nothing about money goes over the event
stream. A Coinage that is reloaded or unloaded leaves the bridge on the old classes, and the 5 second
watch logs one line asking for a reload of Atlas.

## Dominion

Atlas never links against Dominion. It reads `export/snapshot.json`, and Dominion pushes a live siege
layer, `Ryvrook:Dominion:sieges`, and its `/f map hide` switch through `AtlasApi`. The snapshot gives
every siege a pin with its phase and countdown, so `MarkerService` leaves the siege layer out of the
layer payload while the snapshot is read and carries a `sieges` list. When the snapshot appears or goes
away, browsers are told to fetch that layer again. Hidden players reach Atlas both ways, which is
harmless since either one hides.

The snapshot file stays on disk when Dominion is removed. `DominionWatch` looks at the plugin manager
every 5 seconds and, at the default path only, drops the snapshot once Dominion has been missing,
disabled or failed for a minute, and reads it again as soon as Dominion is back. The minute covers a
`/plugin reload` of Dominion and the boot, since Dominion loads after Atlas.

A `/plugin reload` of Atlas loads the `api` classes again, while Dominion stays linked to the old
ones until it is reloaded too. Dominion notices and logs one line asking for that. Until then the
snapshot still draws its sieges and hides its hidden players. `AtlasApi.removeHooks` lets a plugin take
its hooks back when it stops first. TrackerAPI and Coinage each reload Atlas after their own reload, so
a reload of TrackerAPI reloads Atlas more than once, which is harmless.

## BetterMap

Atlas contains no BetterMap code. It only reads BetterMap's files and never writes to its folder.
When `honorBetterMap` is true and `config.json` exists in `betterMapDir`, Atlas logs the path once
and applies the server wide settings to every viewer, admins included. `/api/worlds` says
`"betterMap": true` under `server` while they apply. The file is checked every 15 seconds and read
again when it changes. A missing key takes BetterMap's own default, so the unexplored rules are on
unless the file turns them off.

- `hideSpawnOnMap`, or `spawn` in `hiddenPoiNames`, drops the spawn marker and the `spawn` of the
  world in `/api/worlds`.
- `hideAllPoiOnMap` drops every point of interest and block map marker such as breach markers, in
  the markers and in the zones layer.
- `hiddenPoiNames` drops points of interest whose raw name text, id or image path matches an entry,
  ignoring case, outer spaces and markup tags, the way BetterMap compares them. Block map markers
  are dropped when their name key, id or icon matches, or when the name key or icon contains the
  entry, as BetterMap does for them.
- `hideUnexploredPoiOnMap` and `hideUnexploredWarpsOnMap` drop points, block markers and warps
  outside the explored area Atlas knows, on the worlds listed in `allowedWorlds`. When that key is
  missing the list is `default` and `world`. BetterMap uses each player's own exploration, Atlas
  uses the chunks that exist.
- `hideAllWarpsOnMap` drops every warp.
- `hideGlobalWaypointsOnMap` drops shared markers for everyone, the player who placed them
  included, as the in game map does.
- `hideDeathMarkerOnMap` drops `death` entries.
- `worldBorderEnabled` with `worldBorderRadius`, `worldBorderOffsetX` and `worldBorderOffsetZ` gives
  the worlds BetterMap tracks a border in `/api/worlds`, drawn as a dashed circle. Those are the
  worlds in `allowedWorlds`, or `default` and `world` when the key is missing. Everywhere else
  `border` is null.

```json
{"shape": "circle", "x": 800, "z": 0, "radius": 400}
```

Per player settings in `player_configs/<uuid>.json` are not applied, so a player who overrides a
server wide rule in game still gets that rule on the web map. Player radar, cave mode, teleport,
limit and exploration sharing settings do not apply to the web map, and BetterMap's binary
exploration files are never read.

The game stores player made markers without a height. BetterMap keeps one in
`Data/<world>/global_waypoint_y.json` for shared markers and `Data/<world>/<uuid>_waypoint_y.json`
for a player's personal markers, each a plain object of marker id to height. Atlas fills `y` of
`shared` and `personal` entries from these files when they hold the marker, rounded to a whole
block, and leaves it null otherwise.

## Pull requests

Keep each change focused on one thing and make sure `./gradlew build` passes with its tests. For
changes to tiles, markers, the timeline or the web client, run the smoke test above and say in the
pull request what you checked. A change to an API shape must keep version 1 intact and update
[API.md](API.md).
