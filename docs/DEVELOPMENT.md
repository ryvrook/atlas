# Developing Atlas

This guide is for contributors. It covers how to build and smoke test Atlas on a headless server,
how tiles are made, which threads do what, how markers are merged and how the BetterMap integration
reads its files. The README covers installing and configuring Atlas.

## Building

Atlas needs JDK 25. When no JDK 25 is installed, the foojay toolchain resolver in
`settings.gradle.kts` lets Gradle download one. Point `JAVA_HOME` at any JDK that can run Gradle
and build with the wrapper.

```sh
./gradlew build
```

The jar lands in `build/libs/Atlas-<version>.jar`. The Hytale server artifact comes from the Hytale
Maven repository, and `hytale_channel` and `hytale_version` in `gradle.properties` select it.

## Smoke test on a headless server

You need `HytaleServer.jar` and `Assets.zip` from a Hytale install or the server download. The
commands below use `$HYTALE` for the folder that holds `Server/HytaleServer.jar` and `Assets.zip`,
and `$JAVA` for a Java 25 binary.

1. Make an empty server folder, create `mods` in it and copy the built jar there.
2. Give the server a `permissions.json` containing `{"users":{},"groups":{}}`. A server without one
   can exit right after start.
3. To keep the map off the default port, create `mods/Ryvrook_Atlas/atlas.json` with only
   `{"bind": "127.0.0.1", "port": 8190}`. Atlas fills in every other key on first start.
4. Start the server from that folder. A bare server (`--bare`) loads no world, so every tile would
   return 404. Start it without `--bare` so it generates a world, then load chunks by piping console
   commands into it. Wait for the world before sending them and keep the pipe open afterwards so the
   console stays attached.

```sh
cd /path/to/testserver
( sleep 60; printf 'chunk load 0 0\nchunk load 1 0\nchunk load 0 1\n'; sleep 600 ) | \
  "$JAVA" -jar "$HYTALE/Server/HytaleServer.jar" --assets "$HYTALE/Assets.zip" \
  --auth-mode offline --disable-sentry --bind 127.0.0.1:5521 > boot.log 2>&1
```

A saved world from an earlier run can be reused by copying the whole folder, `universe` included.
Its explored chunks are read at start, so tiles work without the chunk load commands.

Loaded chunks are picked up within 30 seconds. Then check the server from another shell.

```sh
curl -s http://127.0.0.1:8190/api/health
curl -s http://127.0.0.1:8190/api/worlds
curl -s -o tile.png -w '%{http_code}\n' http://127.0.0.1:8190/api/tiles/default/0/0/0.png
curl -sI http://127.0.0.1:8190/ | grep -i -e content-security-policy -e referrer-policy -e nosniff
grep -n 'dev.ryvrook' boot.log
```

What to expect.

- `api/health` answers `{"ok":true,"version":"..."}` without a token and adds uptime, stream
  and tile cache figures with `X-Atlas-Token`.
- `api/worlds` lists the world with `exploredChunks` above 0 and a `bounds` object.
- The tile request returns 200 with a 256 pixel PNG near spawn, and 404 far from anything explored.
- The page carries the Content Security Policy, `Referrer-Policy` and `X-Content-Type-Options`.
- `boot.log` holds the Atlas start lines and no stack trace that mentions `dev.ryvrook`.

Open the page in a browser and check the console. The client must load without Content Security
Policy reports. The policy allows scripts and styles from the same origin, inline style attributes,
the one inline boot script in `index.html` by its hash, and images from the same origin, `data:` and
`blob:`. New client code must not add inline scripts or event handler attributes.

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

Saved chunks are read from the world storage at start and every ten minutes, and chunks loaded in
memory are added every 30 seconds. A chunk that becomes explored after its tile was drawn has its
tile redrawn within about 30 seconds, without the five minute wait, and browsers are told over the
event stream. Its explored neighbours are redrawn with it, since they may have been blank frontier
chunks until it appeared. Tiles that were drawn after the chunk appeared are left alone. Tiles still
waiting for a refresh when the server stops are removed from disk so the next start draws them again.

## Thread model

- Periodic work runs on two `Atlas-scheduler` threads owned by the plugin. Nothing runs on the
  engine's shared scheduled executor.
- Tile renders run on two `Atlas-tiles` threads.
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

The periodic tasks and their intervals.

| Task | Interval |
| --- | --- |
| Factions snapshot check | 1 second |
| World info and player health | 2 seconds |
| Player push over the event stream | `playerUpdateHz`, 1 per second by default, full list every 10 seconds |
| World watch for added and removed worlds | 5 seconds |
| World events | 5 seconds |
| Shared and personal markers | 5 seconds |
| Feats export check | 5 seconds |
| BetterMap settings check | 15 seconds |
| Loaded chunks and dirty tiles | 30 seconds |
| Saved chunks from world storage | 10 minutes |
| Saving data files | 5 minutes |
| Pruning sessions, heat, last seen and caches | 10 minutes |

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
  every world in `/api/worlds` a border, drawn as a dashed circle. Without it `border` is null.

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

Keep each change focused on one thing and make sure `./gradlew build` passes. For changes to tiles,
markers or the web client, run the smoke test above and say in the pull request what you checked.
