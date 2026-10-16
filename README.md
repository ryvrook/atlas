# Atlas

Atlas is a live web map that runs inside the Hytale server. It draws terrain from the game's own world map
images, shows players, markers, world events, a kill feed and activity heat, keeps a timeline of every world you
can scrub back through, and draws any spot as a 3D picture. Open `http://<server>:8123/` in a browser. There is
nothing else to host, and players need no client mod.

Every layer has its own visibility. By default a stranger sees terrain and markers, a player who links their
browser also sees players and the live feed, and admins see everything.

## Requirements

Hytale server 0.7.0 release or newer, tested on 0.7.1, and Java 25. Atlas reads Chapter I engine state such as
portal worlds and world events, so it does not run on 0.6.8. Use Atlas 0.1.0 there. No other mod is needed.

## Install

1. Put `Atlas-1.9.0.jar` in the server's `mods` folder, or `UserData/Mods` for singleplayer.
2. Start the server. Atlas writes `mods/Ryvrook_Atlas/atlas.json` and listens on port 8123.
3. Set `publicUrl` to the address players open, such as `https://map.example.com/`, and run `/atlas reload`.
   Without it `/atlas` cannot hand players a working link, and Atlas warns at start.
4. Open TCP 8123 only if the map should be reachable from other machines. Behind a reverse proxy keep it closed.
5. In game, `/atlas link` prints a link that signs your browser in. Give staff `atlas.admin`.

The first timeline snapshot is taken at 04:00 server time. `/atlas snapshot` takes one now.

## Commands

Every command also works as `/webmap`, for servers where another map mod already owns `/atlas`. Admin commands
need `atlas.admin` and work from the console.

| Command | What it does |
|---|---|
| `/atlas` | The map address as a link |
| `/atlas link` | A six character code, good for five minutes, and a link that fills it in |
| `/atlas hide`, `/atlas show` | Hide yourself from non admins, or show yourself again |
| `/atlas admin` | The address and your admin access |
| `/atlas reload` | Re-read `atlas.json` and the Dominion snapshot |
| `/atlas hide <player>`, `/atlas show <player>` | Hide or show another player, saved in `hidden.json` |
| `/atlas unlink <player>` | Sign every browser of a player out |
| `/atlas tiles purge <world>` | Delete a world's saved tiles. They are drawn again on request |
| `/atlas tiles rerender <world>` | Redraw every explored tile of a loaded world |
| `/atlas render status` | Queued and running renders and the chunk image budget |
| `/atlas render radius <world> <x> <z> <chunks>` | Redraw up to 256 chunks around a block |
| `/atlas render pause`, `resume`, `cancel`, `purge <world>` | Steer the background renders |
| `/atlas marker add <world> <id> <x> <z> <label>` | Add or move a server marker |
| `/atlas marker area add <world> <id> <x1> <z1> <x2> <z2> <label>` | Add a rectangle |
| `/atlas marker remove <world> <id>`, `/atlas marker list <world>` | Remove or list server markers |
| `/atlas snapshot [world]` | Take a timeline snapshot now. Always kept |
| `/atlas timeline status` | Schedule, days held, disk used, snapshots skipped |
| `/atlas iso <world> <x> <z> [radius] [scale]` | Draw a 3D view into `iso/exports` |
| `/atlas iso status` | The 3D queue, caches and budget |
| `/atlas key add <world> <x> <z> <label> [radius] [name]` | Add a key place |
| `/atlas key remove <label>`, `list`, `render <label>` | Manage key places |
| `/atlas integrations` | My other mods, their versions and what each one adds to the map now |

## Settings

Every key, the visibility layers, the timeline, 3D views, per world settings and the data files are in
[docs/SETTINGS.md](docs/SETTINGS.md). Run `/atlas reload` after editing. A changed `bind` or `port` binds the new
address before the old one stops.

An invalid `atlas.json` fails closed. A syntax error, a value of the wrong type, a port above 65535 or a
visibility level such as `pubic` keeps the web server down with one SEVERE line naming the problem, at start and
on reload. A setting you meant to tighten can never stay loose by accident.

| Key | Default | What it does |
|---|---|---|
| `port` | `8123` | Web port |
| `bind` | `0.0.0.0` | Use `127.0.0.1` behind a reverse proxy |
| `publicUrl` | empty | The address `/atlas` hands out |
| `basePath` | empty | Sub path behind a proxy, such as `/map` |
| `trustedProxies` | empty | Proxy addresses whose forwarded headers are believed |
| `visibility` | see SETTINGS | `public`, `linked`, `faction` or `admin` per layer |
| `hideBelowY` | `0` | Players below this height get no position for non admins |
| `hidePlayersInvisible` | `true` | Hide invisible players' positions |
| `hidePlayersCrouching` | `false` | Hide crouching players' positions |
| `chunkImagesPerMinute` | `1200` | Chunk images all renders may ask the engine for per minute |
| `tracker` | `auto` | Use TrackerAPI for heat and kills when present. Read at start |
| `showOwnMoney` | `false` | Show a linked player their own Coinage wallet |
| `coinage`, `dominion`, `feats`, `chronicle` | `auto` | `auto`, `always` or `never` for each of my other mods |

`adminToken` is filled with a random 32 character value on first start and opens every layer, hidden players and
exact coordinates included. Keep `atlas.json` private.

## What the map shows

Tiles come at seven zoom levels down to 2 pixels per block, drawn only from chunks that exist, so the map never
makes the server generate terrain. New ground shows up within about 30 seconds. Saved chunks redraw their tiles at
most every five minutes per world.

Players move over one server sent events stream, once a second by default. There are paths, a last seen list,
spawn, points of interest, warps, portal exits and player markers with the game's own icons, portal worlds with
time left, active world events such as Goblin Breaches, a kill feed and an activity heat overlay. Personal
markers, beds and death places go only to the browser linked to that player, admins included.

**Timeline.** Four snapshots a day by default. Scrub back, play the days as a timelapse, tint what changed, and see
the joins, kills, unlocks and world events of each slot land on the map. A snapshot where nothing changed is
skipped. Save the view as a dated PNG or record a WebM video in the browser. Admins can download a zip of up to 90
days.

**3D views.** Click any marker, or right click any spot, and View in 3D draws an isometric picture of the area
from one of four sides, 2 to 16 chunks across, with trees, water and shadows. A radius 4 view takes under a second.
Up to three key places are drawn from all four sides every day into the timeline, and a day where nothing was built
reuses yesterday's pictures. Views go out through the same chunk budget as tiles, so a big one never slows the map.

## Hiding players

| Way | Who | Saved | Hides |
|---|---|---|---|
| `/atlas hide` | The player | Yes | Position and name |
| `/atlas hide <player>` | Staff | Yes | Position and name |
| Dominion's hidden flag | Dominion | In its snapshot | Position and name |
| `AtlasApi.setHidden` | A plugin, such as a vanish mod | No | Position and name |
| Crouching | Automatic, off by default | No | Position |
| Invisible | Automatic, on by default | No | Position |

A hidden player shows as "A player" with no uuid in kills, the feed, the leaderboard and the timeline, except to
themselves and admins. Crouch and invisibility are read every two seconds, so a position can show for up to two
seconds after a player crouches. Rules apply when data is served, so showing a player again brings their history
back.

## Security

Non admins get coordinates rounded to whole blocks, offline positions rounded to the chunk, and no position at all
below `hideBelowY`. API and tile requests are rate limited per address and answer 429 with `Retry-After`. Link
codes and wrong admin tokens share a limit of 10 a minute. The session cookie `ryvrook_session` is HttpOnly,
SameSite=Lax, lasts 30 days after the last visit, and `sessions.json` keeps only a SHA-256 hash of each token. A
revoke, a lost permission or a new `adminToken` reaches open streams within 30 seconds. The page carries a Content
Security Policy and runs no inline script except one boot script allowed by its hash. Leaflet 1.9.4 is bundled, so
browsers talk to no third party.

## Behind a reverse proxy

Atlas speaks plain HTTP. For the open internet, put it behind nginx or Caddy for HTTPS.

```json
{
  "bind": "127.0.0.1",
  "publicUrl": "https://example.com/map/",
  "basePath": "/map",
  "trustedProxies": ["127.0.0.1"],
  "chronicleUrl": "https://example.com/stats/"
}
```

Forward the path unchanged, pass `X-Forwarded-For` and `X-Forwarded-Proto`, and turn buffering off for
`/api/events`. Without `trustedProxies` every browser shares the proxy's address for rate limits. While it is empty,
loopback and private addresses are not limited per address at all, since behind an unlisted proxy everyone would
look like one.

Serve Atlas and Chronicle Stats under two paths of one host name, keep the default `cookieName` and leave
`cookieDomain` empty, and one login works on both. Set `cookieDomain` only for sibling host names such as
`map.example.com` and `stats.example.com`.

## With the other mods

Atlas runs complete with no other mod. Each of my other mods adds something when it is installed, and nothing
changes for players when it is not. Atlas compiles against TrackerAPI and Coinage and keeps each in one bridge class
it loads only after the plugin manager reported the mod. Dominion, Feats and Chronicle Stats are read from the files
they export, so Atlas never links against them. The other way round, Dominion, Feats, Chronicle Stats, SocialBridge
and RestAPI call `AtlasApi` themselves and switch that off in their own settings. `/atlas integrations` lists each
mod with its version and what it does on the map right now.

| Mod | What appears when it is installed | Setting in atlas.json |
|---|---|---|
| TrackerAPI 1.0.0+ | The heat layer and the kill feed come from its events, and Atlas registers no block or death systems of its own | `tracker` |
| Coinage 1.0.0+ | A linked player sees their own wallet and net worth in the account menu, once `showOwnMoney` is true | `coinage`, `showOwnMoney` |
| Dominion Factions | Territories, factions, nations, sieges, hills, outposts, envoys and faction chat from its snapshot, plus its live sieges layer | `dominion`, `dominionSnapshot` |
| Feats | A Leaderboard tab with top players, recent unlocks with rarity, milestones and server firsts, and unlocks on the timeline | `feats`, `featsExport` |
| Chronicle Stats | A Chronicle tab with server totals and top players linked to `chronicleUrl`, and one login for map and dashboard | `chronicle`, `chronicleExport` |
| SocialBridge 0.2.0+ | The map link in Discord, `/map` link codes, and players hidden on the map left out of its roster | its own `suite.atlas` |
| RestAPI 0.2.0+ | `/api/atlas` routes for the render queue, worlds, layers, markers and hidden players | its own `siblings.atlas` |
| Lullaby | Nothing, it has nothing to put on a map | none |

Each of `tracker`, `coinage`, `dominion`, `feats` and `chronicle` takes `auto` (the default), `always` or `never`.
`never` leaves the mod alone even when it is installed. `always` for `tracker` uses TrackerAPI only and records
nothing without it, for `coinage` warns at start when Coinage is missing, and for `dominion` keeps drawing the
snapshot after Dominion is gone. Under `auto` a Dominion that has been missing for a minute takes its territories
off the map. `tracker` and `coinage` are read at start, the other three on `/atlas reload` too.

Dominion's live sieges layer stays off while the snapshot already lists sieges, so each siege shows once.
`hidePlayersInOwnTerritory` (on) hides players in their own land from other factions. A hidden feat is named only to
admins and the player who earned it. A player hidden by Chronicle shows as "A player" in the tab, and players hidden
on the map stay hidden on the dashboard. `heat.json` and `kills.json` carry on whichever source feeds them.
TrackerAPI 1.0.3+ and Coinage reload Atlas after their own reload, and Atlas reloads the mods that list it after
its own. After adding or removing TrackerAPI, restart or reload Atlas. Atlas only reads balances and never moves
money. Feats 1.7.2 counts the markers players place through `AtlasApi.onMarker`.

**BetterMap.** With `honorBetterMap` on, Atlas follows BetterMap's server wide rules from
`mods/BetterMap/config.json`, hidden spawn, POIs, warps and deaths, the world border and waypoint heights. It only
reads that folder.

## Marker layers for other plugins

Drop a JSON file per layer into `mods/Ryvrook_Atlas/markers/`. Atlas checks the folder every five seconds, so no
reload is needed. A broken file is logged once and its last good version stays up.

```json
{
  "label": "Towns",
  "tier": "public",
  "markers": [
    {"id": "capital", "x": 10, "z": 20, "icon": "Home", "label": "Capital", "popup": "Founded on day one"},
    {"id": "road", "kind": "polyline", "points": [[0, 0], [64, 32], [128, 0]], "tint": "#ffffff", "weight": 2},
    {"id": "arena", "kind": "circle", "x": 300, "z": -200, "radius": 40}
  ]
}
```

A file holds at most 2 MB and 10,000 markers. Kinds are `point`, `polyline`, `polygon` and `circle`. Layers also
take `defaultHidden`, `priority`, `minZoom`, `maxZoom` and `world`, markers take `fill` and `faction`. Unknown keys
make the file invalid, so a typo is noticed. Popups from files are always escaped.

From Java, list `"Ryvrook:Atlas": "*"` as an optional dependency and use `dev.ryvrook.atlas.api.AtlasApi`.

```java
AtlasApi.onEnable(api -> {
    MarkerLayer shops = api.layer("myplugin", "shops").label("Shops").tier(Tier.PUBLIC).priority(5);
    shops.point("default", "market", 120, -40).label("Market").icon("Home").popup("Open at dawn");
});
AtlasApi.onDisable(() -> {
    // Atlas stopped and took the layers with it. Register them again from onEnable.
});
```

`onEnable` runs at once when Atlas is up and again every time it starts. `AtlasApi.removeHooks` takes the hooks
back. Everything is thread safe and never touches a world thread. `popupHtml` sends your own HTML, never text players
typed. `api.setHidden(uuid, true, "vanished")` hides a player until the next restart. From Atlas 1.8.0 `api.publicUrl()` gives the
map address players open, `publicUrlSet()` says whether that came from `publicUrl` in atlas.json or was built from
`bind`, `port` and `basePath`, `renderStatus()` gives the render queue and the chunk image budget, and `worlds()` lists
the worlds Atlas draws with their explored chunks and queued tiles. From Atlas 1.9.0 `api.onMarker(listener)` reports the
shared markers players place and remove on the in game map and the server markers staff place with `/atlas marker`, as
`MarkerEvent` records with the kind, the author when there is one, the world, the layer, the marker id, the position and
the time. The handle it returns removes the listener. `AtlasApi.VERSION` is 3 there. The browser JSON API, frozen
as version 1, the marker events and the session API for plugins with their own web page are in [docs/API.md](docs/API.md).

## Dominion snapshot format

Any factions plugin can write this file and Atlas will draw it. Optional fields can be left out, and a player
without a position counts as online but is never drawn at 0, 0.

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

`memberIds`, `nation`, `nations`, `zones`, `events` and `chat` are optional. `memberIds` finds an offline linked
viewer's faction and never reaches browsers. `chat` holds the last 50 public lines and replaces Atlas's own chat
capture. Live positions from the server win over the file.

## Limits

- The outermost ring of explored chunks stays blank until the land past it is explored, because the engine builds a
  chunk image from the chunk and its eight neighbours.
- A resumed event stream can replay up to a minute of frames built before a change, such as a position from right
  before a player was hidden.
- Video export needs a browser that records WebM. Save image works everywhere.
- When port 8123 is taken at start, Atlas retries every 30 seconds for 10 minutes and then gives up until
  `/atlas reload`.

## Building

JDK 25, or let the foojay resolver download one. `./gradlew build` puts `Atlas-1.9.0.jar` in `build/libs/` and
runs the JUnit tests. `./gradlew installMod` copies it to your local `UserData/Mods`, or elsewhere with
`-PmodsDir=<path>`. The version lives only in `build.gradle.kts`. A change to an API shape must keep version 1
intact. [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) covers the packages, tiles, threads, the timeline and the
headless smoke test.

Issues and pull requests are welcome on [GitHub](https://github.com/ryvrook/atlas).

## Licence

MIT, copyright (c) 2026 ryvrook. See [LICENSE](LICENSE). Atlas bundles [Leaflet](https://leafletjs.com/) 1.9.4
under BSD-2-Clause, with its licence in
[src/main/resources/web/vendor/LICENSE](src/main/resources/web/vendor/LICENSE).
