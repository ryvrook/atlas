# Atlas

Atlas is a live web map for Hytale servers. It runs a small HTTP server inside the Hytale server and
shows terrain rendered from the in game world map, live players, map markers, world events, a kill
feed and server activity in any desktop or phone browser. There is nothing else to host. Each layer
has its own visibility setting, so a stranger who finds the map sees only what you choose to make
public, players who link their browser see a little more, and admins see everything.

## Features

- Terrain tiles at seven zoom levels down to 2 pixels per block, built from explored chunks only and cached in memory and on disk.
- Live players over one server sent events stream, one update per second by default, with player paths and a last seen list.
- Spawn, points of interest, warps, portal exits, breach markers and player placed markers with the game's own icons.
- Private markers, beds and death places sent only to the player they belong to.
- Portal and instance worlds with type, time left and void stage.
- Active world events such as Goblin Breaches, a kill feed with boss kills, public chat and an activity heat overlay.
- A leaderboard tab and territory, faction and nation layers, fed by optional companion files that separate plugins write. Atlas runs fully without them.
- Follows BetterMap's server wide map rules, world border and waypoint heights when BetterMap's files are present.
- Light and dark themes, a phone layout, search, follow, measure and shareable view links.
- Leaflet 1.9.4 is bundled in the jar, so browsers contact no third party hosts.
- A JSON API behind every panel.

## Requirements

| Requirement | Version |
| --- | --- |
| Hytale server | 0.7.0 release (Chapter 1) or newer |
| Java | 25 |

Atlas 0.2.2 reads Chapter I engine state such as portal worlds, world events and block markers, so it
does not run on server 0.6.8. Use Atlas 0.1.0 there. Atlas is server side only and players need no
client mod.

## Install

1. Build `Atlas-0.2.2.jar` from source (see below), or download it from the Releases page once one is published.
2. On a dedicated server, put the jar in the `mods` folder inside the server's working directory, the folder you start `HytaleServer.jar` from. For a singleplayer world, use the `Mods` folder in Hytale's `UserData` folder.
3. Start the server. On first start Atlas creates its data folder `mods/Ryvrook_Atlas`, writes `atlas.json` there and listens on port 8123.
4. Open `http://<server address>:8123/` in a browser.

## Quick start

**Set the public address.** Set `publicUrl` in `atlas.json` to the address players open in their
browser, for example `https://map.example.com/`, then run `/atlas reload`. `/atlas` hands this address
to players in game. While `publicUrl` is empty and `bind` is `0.0.0.0`, Atlas logs a warning at start.

> **Warning** Set `publicUrl` before sharing the map, otherwise `/atlas` cannot give players a working address.

**Open the firewall.** Allow TCP port 8123 (or your `port`) only if the map should be reachable from
other machines. Behind a reverse proxy, keep the port closed and set `bind` to `127.0.0.1`.

**Reverse proxy.** Atlas speaks plain HTTP. A reverse proxy is recommended whenever the map is
reachable from the open internet, for HTTPS and for connection limits and timeouts beyond Atlas's
own. Atlas handles at most 64 requests at once, event streams aside, answers 503 beyond that and
gives a client 30 seconds to send a request. List the proxy in `trustedProxies`. A request whose
direct peer is trusted is treated as coming from the last address in `X-Forwarded-For` that is not
itself a trusted proxy. That address is used for the link rate limit, the stream limit per address
and log lines. `X-Forwarded-Proto` from a trusted proxy tells Atlas the request came over https.
Entries that are not addresses or CIDR ranges are ignored with a warning.

To mount the map under a sub path such as `/map`, set `basePath` to that path and forward it
unchanged. Requests work with and without the prefix, and the bare prefix redirects to the same path
with a trailing slash. The client only uses relative addresses.

```json
{
  "bind": "127.0.0.1",
  "publicUrl": "https://example.com/map/",
  "basePath": "/map",
  "trustedProxies": ["127.0.0.1"]
}
```

> **Warning** Behind a reverse proxy, always list it in `trustedProxies`. Otherwise every browser shares the proxy's address for rate limits and logs.

**Link a browser.** A player runs `/atlas link` in game and enters the six character code in the
map's link dialog. The browser then sees layers marked `linked` and the player's own markers.

**Give staff admin access.** Grant the `atlas.admin` permission. A linked browser whose player has it
sees every layer. Scripts and tools can instead send the `adminToken` from `atlas.json`.

## Configuration

`atlas.json` lives in the plugin data directory, `mods/Ryvrook_Atlas`. Missing keys are filled in with
their defaults and written back, so the file always lists every option. Existing values are kept.
Run `/atlas reload` after editing. When `bind` or `port` changed, Atlas binds the new address first
and stops the old server only once that worked, so a failed bind keeps the map where it was.

| Key | Default | Meaning |
| --- | --- | --- |
| `bind` | `0.0.0.0` | Address the web server binds to |
| `port` | `8123` | Port of the web server |
| `publicTitle` | `Atlas` | Title shown in the browser |
| `publicUrl` | empty | Address printed by `/atlas` and sent to browsers |
| `basePath` | empty | Sub path stripped from requests behind a reverse proxy, such as `/map` |
| `showPlayers` | `true` | Show players to non admins at all |
| `showPlayerHealth` | `false` | Include health in player data for non admins |
| `hidePlayersInOwnTerritory` | `true` | Hide players standing in their own faction land from other factions. Needs a snapshot file |
| `hideBelowY` | `0` | Players below this height count as underground and non admins get no position for them |
| `playerUpdateHz` | `1` | Player pushes per second over the event stream, clamped to 0.1 to 10 |
| `showPortalWorlds` | `false` | Show portal and instance worlds to non admins. When false they are left out of the world picker and their tiles, markers and other world endpoints return 404 |
| `featsExport` | `mods/Ryvrook_Feats/export/feats.json` | Optional leaderboard export written by the separate Feats plugin, relative to the server working directory |
| `sseClientsPerIp` | `8` | Event stream connections per address, next to a global limit of 500 |
| `showMarkerAuthors` | `true` | Show who placed a player made marker. When false the creator is left out everywhere |
| `tileCacheMb` | `64` | Memory budget for encoded tiles in MB, at least 4 |
| `adminToken` | random | Admin secret, sent as the `X-Atlas-Token` header or as a Bearer token. A value shorter than 24 characters is replaced with a random one and a warning is logged |
| `dominionSnapshot` | `mods/Ryvrook_Dominion/export/snapshot.json` | Path of the optional factions snapshot file, relative to the server working directory |
| `honorBetterMap` | `true` | Apply BetterMap's server wide rules when its settings file exists |
| `betterMapDir` | `mods/BetterMap` | BetterMap's folder, relative to the server working directory |
| `trustedProxies` | empty | Reverse proxies whose forwarded headers are believed, as addresses or CIDR ranges such as `10.0.0.0/8` |
| `visibility` | see below | Who sees each layer |

> **Warning** `adminToken` grants full access, including hidden players and exact coordinates. Keep `atlas.json` private.

### Visibility

Each value is `public`, `linked`, `faction` or `admin`. `faction` means linked viewers who see only
the data of their own faction. An unknown layer is treated as `admin`.

| Layer | Default | Covers |
| --- | --- | --- |
| `terrain` | `public` | Map tiles |
| `players` | `linked` | Player markers and online counts |
| `zones` | `public` | Zone name labels from the world map |
| `markers` | `public` | Engine markers, player made markers and the Markers tab |
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
| `events` | `public` | Hills, outposts, envoys, bounties, the season and boss bars |

Players and the feed are `linked` by default so a stranger who finds the map cannot follow anyone
around. Hill, outpost and envoy points follow `events` instead of `points` and are never limited to
one faction, since every faction may contest them.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/atlas` | Prints the map address, or says none is set and gives the port. Alias `/webmap` | None |
| `/atlas link` | Prints a six character link code, valid for five minutes | None |
| `/atlas hide` | Hides you from non admins on the map. Run again to show yourself | None |
| `/atlas admin` | Prints the address and whether you have admin access | None |
| `/atlas reload` | Reloads `atlas.json` and the snapshot file | `atlas.admin` |

## How it works

### Embedded HTTP server

Atlas serves the web client and a JSON API from inside the Hytale server. `index.html` is sent with
`Cache-Control: no-cache` and an ETag. Every script, stylesheet and icon it references carries
`?v=<version>` and is cached for a day, so a new jar reaches browsers without a hard refresh. Periodic
work runs on Atlas's own scheduler and tile threads, and anything that touches a world hops to that
world's thread.

### Tiles

Tiles are 256 pixel PNGs rendered from the engine's world map images. Zoom 0 covers 16 by 16 chunks
(512 blocks). Zoom 1 to 4 each halve the previous level, and zoom -1 and -2 are close up levels at 1
and 2 pixels per block. `/api/worlds` lists every level under `tile.levels`.

Only chunks that already exist in the world save or in memory are rendered, so unexplored areas return
404 and the map never makes the server generate terrain. The engine builds a chunk image from the
chunk and its eight neighbours, so Atlas waits until that whole ring exists. The outermost ring of
explored chunks stays blank until the land beyond it is explored.

Newly explored chunks appear within about 30 seconds. Saved chunks mark their tiles dirty, and dirty
tiles are re-rendered at most every five minutes per world. Browsers are told over the event stream
which tiles to reload.

### Event stream

`/api/events` is a server sent events stream. Player payloads are built once per viewer class each
tick and shared by every client in that class. A tick lists only players that moved, with
`"full": false` and departed players under `left`. Every ten seconds a full list goes out with
`"full": true`. Each open stream resolves its viewer again every 30 seconds, so a revoked session or
a new permission arrives as a fresh `hello` without reconnecting.

Each address may hold `sseClientsPerIp` streams, and more get 429. While `trustedProxies` is empty,
loopback and private addresses are only held to the global limit of 500, and Atlas logs one warning
suggesting `trustedProxies` the first time that happens.

### Privacy model

| Viewer | How | Sees |
| --- | --- | --- |
| Public | Any browser | Layers marked `public` |
| Linked | Entered a code from `/atlas link` | `linked` layers, own faction data for `faction` layers, own private markers |
| Admin | Linked with `atlas.admin`, or sends `adminToken` | Every layer, hidden players, exact coordinates and health |

Linking stores an `atlas_session` cookie that lasts 30 days after the last visit. It is HttpOnly,
SameSite=Lax and Path `/`, and Secure when a trusted proxy reports https. `sessions.json` keeps only
a SHA-256 hash of each session token. Link codes are single use, and link codes and wrong admin
tokens share one rate limit per address. Admins can add `?as=TAG` to any endpoint to see what a
member of that faction sees.

For non admins, coordinates are rounded to whole blocks and offline positions to the chunk. A player
below `hideBelowY` is sent with null `x`, `y` and `z` and `"underground": true`.

**Hidden players.** A player who ran `/atlas hide` or is marked hidden in the snapshot file is never
shown to non admins, except to themselves. They appear as "A player" in the kill feed, the feed, the
leaderboard and as marker author, and are left out of the offline list.

**Private markers.** Personal markers, homes and death places are sent only to the browser linked to
that player, never to anyone else, admins included. They are kept in memory for an hour after logout
and never written to disk.

## HTTP API

All endpoints return JSON with `Access-Control-Allow-Origin: *` unless noted. Admin requests send the
token as `X-Atlas-Token` or `Authorization: Bearer <token>`.

| Endpoint | Returns |
| --- | --- |
| `GET /` | The web client |
| `GET /{file}` | Client assets from the jar, cached for a day |
| `GET /api/worlds` | Title, viewer, visible layers, server info, tile levels, and per world its kind, display name, hidden flag, portal state, players, spawn, time, explored chunk count, bounds and border |
| `GET /api/tiles/{world}/{zoom}/{x}/{z}.png` | A 256 pixel PNG tile at zoom -2 to 4. 404 when unexplored, 503 when four renders are running |
| `GET /api/players` | Visible players, plus `offline` players seen in the last seven days when `lastSeen` is visible. Takes `world`, `trails=1` and `as` |
| `GET /api/markers/{world}` | World spawn, engine markers, points of interest, warps, shared markers, and the viewer's own markers, homes and deaths |
| `GET /api/marker-icons/{Name}.png` | A marker image from the server's Assets.zip, such as `UserB`, `Home` or `Death`. Cached with an ETag, 404 for unknown names |
| `GET /api/worldevents` | Active world events with start time, position when known and `located`. Takes `world` |
| `GET /api/kills` | The kill feed, newest last. Takes `limit`, 50 by default and at most 200 |
| `GET /api/bosses` | Active boss bars. Always empty on 0.7.0, which exposes no boss bar state |
| `GET /api/leaderboard` | The Feats export with `available` and `updatedAt`, or `{"available": false}` |
| `GET /api/territories/{world}` | Territories with chunks, breached chunks and zones, plus sieges in that world |
| `GET /api/points/{world}` | Snapshot points and zone names |
| `GET /api/factions` | Factions sorted by value |
| `GET /api/factions/{tag}` | One faction with nation, land per world, online members and sieges |
| `GET /api/nations` | Factions grouped by nation with totals, and the factions without a nation |
| `GET /api/stats` | Server, faction, event and building activity totals |
| `GET /api/changes/{world}?since=ms` | Tiles changed since the time, and heat per chunk when heat is visible |
| `GET /api/events` | The server sent events stream. Takes `world` and `as` |
| `GET /api/health` | `ok` and `version`. Admins also get uptime, stream count and tile cache figures |
| `POST /api/link` | `{"code":"ABC123"}` links the browser, `{"action":"logout"}` unlinks. Other content types get 415 |

Each marker has `id`, `kind`, `name`, `x`, `y`, `z`, `icon`, `major`, `alert`, `tint`, `by` and `own`.
`kind` is one of the engine kinds, `shared`, `personal`, `home` or `death`. `by` is
`{"uuid": "...", "name": "..."}` or null, and `y` is null for player made markers unless BetterMap
stores a height. Duplicates from different sources are listed once.

### Stream events

| Event | Payload |
| --- | --- |
| `hello` | Viewer and server info, sent on connect and when the viewer changes |
| `players` | Same shape as `/api/players`, partial or full |
| `snapshot` | Snapshot sections whose content changed, such as territories or factions |
| `tiles` | Tiles to reload as zoom, x and y |
| `feedlog`, `feed` | Recent feed lines on connect, then new lines |
| `chatlog`, `chat` | Recent public chat lines, then new lines, with the sender's faction tag |
| `killlog`, `kill` | Recent kills, then new kills |
| `worldevent` | A world event started or ended |
| `leaderboard` | The Feats export changed |
| `worlds` | A world was added or removed |
| `markers` | Markers of a world changed, `{"world": "default"}` or `{"world": "default", "own": true}`. Refetch `/api/markers` |
| `boss` | Reserved, never sent on 0.7.0 |

Chat capture records public chat only. Messages sent to fewer players than are online belong to a
private channel and are skipped.

## BetterMap compatibility

Atlas contains no BetterMap code and only reads BetterMap's files. When `honorBetterMap` is true and
`config.json` exists in `betterMapDir`, Atlas applies BetterMap's server wide settings to every
viewer, admins included, so the web map shows what the in game map shows.

- `hideSpawnOnMap`, or `spawn` in `hiddenPoiNames`, drops the spawn marker.
- `hideAllPoiOnMap` drops every point of interest and block marker. `hiddenPoiNames` drops matching ones.
- `hideUnexploredPoiOnMap` and `hideUnexploredWarpsOnMap` drop points and warps outside explored chunks on the worlds in `allowedWorlds`.
- `hideAllWarpsOnMap`, `hideGlobalWaypointsOnMap` and `hideDeathMarkerOnMap` drop warps, shared markers and deaths.
- `worldBorderEnabled` with its radius and offsets gives each world a `border` drawn as a dashed circle.
- Waypoint heights from BetterMap's data files fill `y` of shared and personal markers.

The settings file is checked every 15 seconds, so no reload is needed. BetterMap's per player
settings and exploration files are not used. Atlas only reads BetterMap's folder and never writes to it.

## Factions snapshot

Atlas can show territories, factions, nations, sieges and faction events from an optional factions
snapshot file, a companion file written by a separate plugin. You do not need that plugin to run
Atlas. Atlas reads the file at `dominionSnapshot` and never links against the plugin that writes it.
Without the file Atlas runs standalone with terrain, players, markers and zone names.

The file is reloaded when its modification time changes, checked once a second. A missing file is
logged once with the path Atlas looked at, and a file that fails to parse is logged at most once a minute.

```
{"version":1,"at":ms,"server":{"online":n},
 "worlds":[{"name","players":[{"uuid","name","x","y","z","faction","color","hidden"}],
   "territories":[{"id","tag","name","color","type","raidable","chunks":[[cx,cz]],"breached":[[cx,cz]],
     "zones":[{"id","name","chunks":[[cx,cz]]}]}],
   "points":[{"id","kind","label","x","y","z","color","faction","expiresAt"}]}],
 "factions":[{"id","tag","name","color","nation","leader","members","memberIds":["uuid"],"online","claims",
   "power","maxPower","value","rank","kills","deaths","siegesWon","siegesLost","level","points","bank",
   "relations":{"TAG":"ALLY"},"createdAt","description"}],
 "nations":[{"id","tag","name","color","members":["TAG"]}],
 "sieges":[{"id","attacker","defender","phase","world","chunk":[cx,cz],"endsAt"}],
 "grace":{"active","endsAt"},"eotw":false,"feed":[{"at","type","text"}],
 "chat":[{"at","name","tag","text"}],
 "events":{"enabled","koths":[{"name","status"}],"outposts":[{"name","holder","control"}],
   "envoys":["text"],"bounties":n,"season":"text"}}
```

- `memberIds`, `nation`, `nations`, `zones`, `events` and `chat` are optional.
- Player `x`, `y` and `z` may be null or left out. The exporting plugin writes positions only when its owner opts in. Such players count as online and follow the hidden player rule, and are sent without a position, never at 0, 0.
- `memberIds` lets Atlas find a linked viewer's faction while they are offline. It is never sent to browsers.
- `nation` may be a tag, a nation id or an object with tag, name and color. A faction without one belongs to the nation that lists its tag.
- Points of kind `hill`, `outpost` and `envoy` get their own icons with a countdown to `expiresAt`.
- `chat` holds the last 50 public chat lines, oldest first, with strictly increasing `at`. When present it replaces Atlas's own chat capture.
- Live player positions from the server take precedence. Players listed only in the snapshot are shown while it is less than 15 seconds old.

## Data files

All files live in the plugin data directory, `mods/Ryvrook_Atlas`.

| File | Contents |
| --- | --- |
| `atlas.json` | Settings |
| `sessions.json` | Linked browsers, stored as hashes of their session tokens |
| `hidden.json` | Players who ran `/atlas hide` |
| `heat.json` | Block activity per chunk in ten minute buckets for seven days |
| `kills.json` | The last 200 kills |
| `lastseen.json` | Where each player was last seen |
| `transient-worlds.json` | Portal and instance worlds whose tiles are removed once the world is gone |
| `tiles/` | Rendered tile cache |

## Building from source

Atlas builds with Gradle and requires JDK 25. When no JDK 25 is installed, the foojay toolchain
resolver configured in `settings.gradle.kts` lets Gradle download one. The Hytale server artifact is
fetched from the Hytale Maven repository, and `gradle.properties` selects the channel and version.

```sh
./gradlew build
```

The jar lands in `build/libs/Atlas-<version>.jar`. `./gradlew installMod` copies it into your local
Hytale `UserData/Mods` folder, or into another folder with `-PmodsDir=/path/to/server/mods`.

## Contributing

Issues and pull requests are welcome. Please keep each change focused on one thing, describe what it
fixes or adds, and make sure `./gradlew build` passes. [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
covers the headless smoke test, tile internals, the thread model, marker merging and the BetterMap
details.

Changes between versions and upgrade notes are listed in [CHANGELOG.md](CHANGELOG.md).

## License

Atlas is released under the [MIT License](LICENSE). Copyright (c) 2026 ryvrook.

Atlas bundles [Leaflet](https://leafletjs.com/) 1.9.4, which is released under the BSD-2-Clause
license. Its license text is in
[src/main/resources/web/vendor/LICENSE](src/main/resources/web/vendor/LICENSE).

The bundled `leaflet.js` has its trailing source map comment removed, so its hash differs from the official 1.9.4 file.
