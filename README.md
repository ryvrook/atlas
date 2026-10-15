# Atlas

Atlas is a live web map for Hytale servers. It runs a small HTTP server inside the Hytale server and
shows terrain rendered from the in game world map, live players, map markers, world events, a kill
feed, server activity, 3D views of any area and a timeline of every world in any desktop
or phone browser.
There is nothing else to host. Each layer has its own visibility setting, so a stranger who finds
the map sees only what you choose to make public, players who link their browser see a little more,
and admins see everything.

## Features

- Terrain tiles at seven zoom levels down to 2 pixels per block, built from explored chunks only and cached in memory and on disk.
- A timeline of every world. Scrub back through snapshots of the map taken several times a day, play them as a timelapse, see what changed between them and what happened where.
- View in 3D. Any marker, waypoint, admin marker or spot on the map opens an isometric picture of the area around it, block by block with trees, water and plants, from four directions and at four sizes, ready to download or share as a link.
- Daily 3D views in the timeline, with a slider that compares the same place on two days.
- Key places. Up to three places drawn from all four directions with the first snapshot kept each day, kept in the timeline with a player that steps through the days, and reused when nothing was built.
- Timeline exports. Save the map on screen as a dated image, record the days as a WebM video, and, for admins, download the timeline data as a zip.
- One click linking. The link code message in game opens the map and links the browser at once.
- Marker layers from other plugins through a Java API, from JSON files in a drop folder and from the `/atlas marker` commands, each with its own switch in the layers menu.
- Live players over one server sent events stream, one update per second by default, with player paths and a last seen list.
- Spawn, points of interest, warps, portal exits, breach markers and player placed markers with the game's own icons.
- Private markers, beds and death places sent only to the player they belong to.
- Portal and instance worlds with type, time left and void stage.
- Per world settings for the world to open first, display names, hidden or disabled worlds and a render limit that ends the map at a circle.
- A staff hide toolkit. Hide any player, hide players automatically while they crouch or are invisible, and let vanish plugins hide players through the API.
- Active world events such as Goblin Breaches, a kill feed with boss kills, public chat and an activity heat overlay.
- A leaderboard tab with the Feats top list, recent unlocks with how rare each feat is, milestones, server firsts and the last unlock of every player.
- A Chronicle tab with the server totals and top players from the Chronicle stats plugin, and one shared login with the Chronicle web page.
- Territory, faction and nation layers fed by an optional factions snapshot file that a separate plugin writes. Atlas runs fully without it.
- Follows BetterMap's server wide map rules, world border and waypoint heights when BetterMap's files are present.
- Light and dark themes, a phone layout, search, follow, measure and shareable view links.
- Leaflet 1.9.4 is bundled in the jar, so browsers contact no third party hosts.
- A versioned JSON API behind every panel, frozen as version 1 and described in [docs/API.md](docs/API.md).
- Per address rate limits, a chunk image budget, gzip, ETags and a config that fails closed.

## Requirements

| Requirement | Version |
| --- | --- |
| Hytale server | 0.7.0 release (Chapter 1) or newer |
| Java | 25 |

Atlas reads Chapter I engine state such as portal worlds, world events and block markers, so it does
not run on server 0.6.8. Use Atlas 0.1.0 there. Atlas is server side only and players need no client
mod.

## Install

1. Build `Atlas-1.5.0.jar` from source (see below), or download it from the Releases page once one is published.
2. On a dedicated server, put the jar in the `mods` folder inside the server's working directory, the folder you start `HytaleServer.jar` from. For a singleplayer world, use the `Mods` folder in Hytale's `UserData` folder.
3. Start the server. On first start Atlas creates its data folder `mods/Ryvrook_Atlas`, writes `atlas.json` there and listens on port 8123.
4. Open `http://<server address>:8123/` in a browser.

## Quick start

**Set the public address.** Set `publicUrl` in `atlas.json` to the address players open in their
browser, for example `https://map.example.com/`, then run `/atlas reload`. `/atlas` and `/webmap`
hand this address to players in game as a link they can click. While `publicUrl` is empty and `bind`
is `0.0.0.0`, Atlas logs a warning at start.

> **Warning** Set `publicUrl` before sharing the map, otherwise `/atlas` cannot give players a working address.

**Open the firewall.** Allow TCP port 8123 (or your `port`) only if the map should be reachable from
other machines. Behind a reverse proxy, keep the port closed and set `bind` to `127.0.0.1`.

**Link a browser.** A player runs `/atlas link` in game. With `publicUrl` set, the address in that
message can be clicked and opens the map with the code filled in, so the browser links itself.
Otherwise the player enters the six character code in the map's link dialog. The browser then sees
layers marked `linked` and the player's own markers.

**Give staff admin access.** Grant the `atlas.admin` permission. A linked browser whose player has it
sees every layer. Scripts and tools can instead send the `adminToken` from `atlas.json`.

**Look at the timeline.** The first snapshot is taken at 4 in the morning server time. Run
`/atlas snapshot` to take one now, then open the Timeline tab.

## Commands

Every command works as `/atlas` and as `/webmap`. Another map mod also uses `/atlas`, so on a server
that runs both, use `/webmap`. The table shows the `/atlas` form. Admin commands need `atlas.admin`
and also work from the server console.

| Command | What it does | Permission |
| --- | --- | --- |
| `/atlas` | Prints the map address as a link you can click, or says none is set and gives the port | None |
| `/atlas link` | Prints a six character link code, valid for five minutes, and a link that fills it in | None |
| `/atlas hide` | Hides you from non admins on the map. Run again to show yourself | None |
| `/atlas show` | Shows you on the map again | None |
| `/atlas admin` | Prints the address and whether you have admin access | None |
| `/atlas reload` | Reloads `atlas.json` and the factions snapshot file | `atlas.admin` |
| `/atlas hide <player>` | Hides another player, by name or UUID, and saves it in `hidden.json` | `atlas.admin` |
| `/atlas show <player>` | Takes a player off the hide list | `atlas.admin` |
| `/atlas unlink` | Signs every linked browser of a player out. Takes a name or a UUID | `atlas.admin` |
| `/atlas tiles purge` | Deletes every saved and cached tile of a world and drops its queued renders | `atlas.admin` |
| `/atlas tiles rerender` | Marks every explored chunk of a loaded world dirty, so its tiles are drawn again | `atlas.admin` |
| `/atlas render status` | Shows queued, running and finished renders, the chunk image budget and counts per world | `atlas.admin` |
| `/atlas render radius` | Redraws the explored chunks within a radius of at most 256 chunks around a block | `atlas.admin` |
| `/atlas render pause` | Holds the background renders. Browsers still get tiles drawn on request | `atlas.admin` |
| `/atlas render resume` | Continues the background renders | `atlas.admin` |
| `/atlas render cancel` | Drops every queued background render. Renders already running finish | `atlas.admin` |
| `/atlas render purge` | Drops the queued background renders of one world | `atlas.admin` |
| `/atlas marker add` | Adds a server marker, or moves the one with that id | `atlas.admin` |
| `/atlas marker area add` | Adds a rectangle between two corners, or replaces the one with that id | `atlas.admin` |
| `/atlas marker remove` | Removes a server marker or area | `atlas.admin` |
| `/atlas marker list` | Lists the server markers of a world, at most 50 lines | `atlas.admin` |
| `/atlas snapshot` | Takes a timeline snapshot of every world now, or of one world, into the slot of the moment. It is always kept and says when no blocks changed since the slot before | `atlas.admin` |
| `/atlas timeline status` | Prints the schedule, the days and slots held, the disk used, the next snapshot and the snapshots skipped because nothing changed | `atlas.admin` |
| `/atlas iso` | Draws a 3D view of an area into `iso/exports` and prints the file | `atlas.admin` |
| `/atlas iso status` | Shows the 3D view queue and its allowances, the last render, the caches and the chunk budget | `atlas.admin` |
| `/atlas key add` | Adds a key place, or moves the one with that label | `atlas.admin` |
| `/atlas key remove` | Removes a key place. Its earlier pictures stay in the timeline | `atlas.admin` |
| `/atlas key list` | Lists the key places | `atlas.admin` |
| `/atlas key render` | Draws a key place from all four directions now | `atlas.admin` |

The full forms with their arguments.

```
/atlas unlink <player>
/atlas tiles purge <world>
/atlas tiles rerender <world>
/atlas render radius <world> <x> <z> <chunks>
/atlas render purge <world>
/atlas marker add <world> <id> <x> <z> <label>
/atlas marker area add <world> <id> <x1> <z1> <x2> <z2> <label>
/atlas marker remove <world> <id>
/atlas marker list <world>
/atlas snapshot [world]
/atlas iso <world> <x> <z> [radius] [scale]
/atlas key add <world> <x> <z> <label> [radius] [name]
/atlas key remove <label>
/atlas key list
/atlas key render <label>
```

`/atlas tiles purge` also works for a world that is no longer loaded but still has a tile folder.
Purged tiles are drawn again on request and while the map is idle.

## Configuration

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
`mods/Ryvrook_Chronicle/export/stats.json` and `dominionSnapshot` to
`mods/Ryvrook_Dominion/export/snapshot.json`, all relative to the server working directory.

`adminToken` is filled with a random 32 character value when empty. A value shorter than 24
characters is replaced with a random one and a warning is logged.

> **Warning** `adminToken` grants full access, including hidden players and exact coordinates. Keep `atlas.json` private.

**Tile settings in practice.** Browsers may use the whole chunk image budget, background work only
its upper half. When the budget is spent a browser gets the cached tile or a partial tile at once
instead of waiting. The age check runs on the ten minute refresh. A tile whose chunk images did not
change is kept and dated again, one that changed is drawn again. Idle pre rendering starts after 30
seconds without a tile request and draws one tile at a time within the background half of the budget.

### Visibility

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

Atlas keeps a daily picture of each world so players can scrub back through time, watch builds
appear and see when things happened.

**Snapshots.** `timeline.perDay` times a day (4 by default), at evenly spaced hours starting from
`timeline.hour` (server local time, 4 by default), Atlas takes a snapshot of every plain world that has
map tiles. With the defaults that is 04:00, 10:00, 16:00 and 22:00. Portal and instance worlds and
disabled worlds are left out. Each snapshot fills a slot named by its date and hour, such as
`2026-10-13T16`. If the server was down when a slot began, the latest slot it missed is taken five
minutes after the next start. Admins can take one at any time with `/atlas snapshot [world]`. It goes
into the slot of the moment and replaces what that slot held. Snapshots are never started from a web
page. `/atlas timeline status` prints the schedule and how many snapshots were skipped since the start.

**Unchanged snapshots are skipped.** With `timeline.skipUnchanged` (on by default) a scheduled
snapshot, the catch up after a restart included, is dropped when no block changed since the slot
before and no event happened in between. Nothing of it is kept, no slot, no stats and no snapshot
event, and the server log says so in one line. A block change means a tile of zoom 0, the zoom the
map is drawn at from the chunks, whose image differs from the slot before, or that the slot before
did not have, which is newly explored ground. Tiles of the other zooms are drawn from zoom 0 and do
not count, nor do tiles carried over from the slot before. A zoom 0 tile that was being redrawn while
the snapshot read it counts as a change. When no block changed but events happened between the two
snapshots, the snapshot is kept for the events and the log says so. Snapshot lines do not count as
events. The line that says a full day dropped further events does, so a busy slot is never skipped.
While `timeline.events` is off there is no event log to ask, so Atlas looks at its activity counters
for the same span instead, players seen, kills, deaths, world events and blocks broken or placed, and
keeps the snapshot when any of them is above zero, at most seven days back. The log then says it was
kept for activity. When the event log could not be written just before the check, the snapshot is
kept too. The first snapshot of a world, a slot that already holds a snapshot and every snapshot an
admin takes with `/atlas snapshot` are always kept, and the reply and the log say when no blocks
changed since the slot before. A skipped slot leaves no gap on the timeline. The slot held before it
that day covers its hours and shows the map they had, since nothing changed. When the first slot of a
day is skipped, the next slot held that day covers the hours from midnight instead, so those early
hours show its later map. A quiet day with every slot skipped and no events has no mark on the track,
and it has no key place pictures or daily 3D views either, so the key places day strip skips it. Those
pictures would have been unchanged.

**Upgrading from 1.1 or older.** Day folders written before 1.2 stay as they are and count as one slot
of their day. A plain date such as `2026-10-13` still works in every address and share link. It means
the old day folder of that date when there is one, so the snapshot taken before the upgrade stays
reachable, and otherwise the last slot of that day.

**What a snapshot holds.** Every tile Atlas has on disk, at every zoom, recorded by the hash of its
image. Each image is stored once and shared by every slot it appears in, so a slot where little
changed costs a few kilobytes. Changed tiles are counted against the slot before. Tiles that are being
redrawn while the snapshot runs keep their image from the slot before and the slot is marked partial.
Each slot also gets stats for the 24 hours before the snapshot, not for the hours of its slot. When
the snapshot kept before it is longer ago, as after skipped snapshots, the stats reach back to it, up
to seven days, so within those seven days no hour is left out and none is counted twice by the
activity counters. They are
explored chunks, tiles,
players seen, peak and current online, kills, deaths, blocks broken and placed, world events, and a
Feats summary with the top ten when a Feats export exists. Daily 3D views are drawn once a day, with the
first slot kept that day, and the other slots of the day show them, see [3D views](#3d-views). A day
with every snapshot skipped has none.

**Events.** Through the day Atlas writes an event log per world with joins and leaves, deaths and
kills, world events, Feats unlocks, milestones and firsts, factions events, markers placed and
removed, and snapshots. A day holds at most 5,000 events, after that one line says the rest were
dropped. The log stays one file per day. A slot shows the hours of its day from its own hour to the next
slot, and the first slot of a day starts at midnight, so every event belongs to one slot.

**Retention.** Days older than `timeline.retainDays` are pruned, and while the timeline uses more
than `timeline.maxDiskMb` the oldest days go first. A day is pruned with all of its slots. The day of
the newest snapshot of a world and today are always kept. A pruned slot stays on the slider as a
hatched gap.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Take snapshots and keep the event log. Off answers 404 on every timeline route |
| `hour` | `4` | Server local hour of the first snapshot of the day, 0 to 23 |
| `perDay` | `4` | Snapshots a day at evenly spaced hours from `hour`, one of 1, 2, 3, 4, 6, 8, 12 or 24 |
| `retainDays` | `90` | Days kept. 0 keeps only the newest day |
| `maxDiskMb` | `2048` | Disk the timeline may use in MB. 0 means no cap |
| `events` | `true` | Write the daily event log |
| `skipUnchanged` | `true` | Drop a scheduled snapshot when no block changed and no event happened since the one before. With `events` off the activity counters decide instead |

**In the browser.** The Timeline tab and the clock button under the map controls open a track along
the bottom of the page. It is a time axis from the oldest day held to the live map at the right end, with
day labels, a picture mark for each snapshot, a cube mark for a snapshot that also holds 3D views, a
short tick for a slot with events only, a hatched block for a day removed by retention, and a bar under
each slot that grows with its events. A legend names the marks. Hover a mark, or move along the focused
track with the arrow keys, to see a card with the slot's tiles, changed tiles, events and a 3D
thumbnail. Click or drag anywhere on the track to move through time.
Picking a slot cross fades the live tiles into the tiles of that slot, dims the live overlays and shows a
Viewing chip with a Return to live button. The events of the slot's hours land on the map as it appears,
close ones share a badge with their count until the map is zoomed in, and the list in the tab groups them
by hour with filters by kind and a Show button. Hovering a row lights up its marker. The diamond button
tints the tiles that changed since the slot before. A day with events but no snapshot shows the map of
the snapshot before it and says so.
The play button in the bar steps through the slots at 0.5, 1, 2, 3, 5 or 10 seconds a snapshot (the menu reads 2 sec/snapshot). The next slot
loads out of sight and fades in once every tile on screen has answered, so no gap opens, and each
snapshot's time on screen starts once it is fully shown. The playhead slides to the next mark while
playing, the ring around Play fills while a snapshot is on screen, and the chip names the slot with its
place in the run, such as 2 of 5, with a progress bar. From the live map or the newest slot Play starts
at the oldest slot held. A slot with events only that shows the same map as the step before passes in
half the time and says events only, same map. After the newest slot the live map returns and a toast
says what was played. Play needs at least two snapshots, and until then its tooltip says when the next
snapshot is due.
The tab starts with the step arrows, the Changed tiles switch and Live, then the figures of the shown slot, a strip of its 3D views, the key
places, the slot's stats, small charts over every slot held (a click jumps to that slot) and the events.
While the bar is open, the left and right arrows step a slot, space plays and pauses, and Home or Esc
return to the live map. When a snapshot finishes, open pages add the new slot by themselves.

**Share links.** A link copied while a slot is shown carries `t=<slot>`, such as `t=2026-10-13T16`, and
opens on that slot. An older link with a plain date opens the old day folder of that date, or the last
slot of that day when there is none.

Day tiles follow the `terrain` visibility, everything else the `timeline` visibility. Events follow
the layer they belong to as well, so kills need `kills`, world events `worldevents`, Feats lines
`leaderboard`, marker lines `markers` and the rest `feed`. Hidden players are shown as "A player" in
the timeline too. A join, leave, kill, death or Feats event carries the place of the player, with the
rules of the live player rows. The place is withheld when the player was underground, in their own
territory with `hidePlayersInOwnTerritory`, or crouching or invisible when those are hidden, and it is
dropped for hidden players, for players of another faction on a faction limited layer and for worlds
the viewer may not open. Non admins get the corner of the chunk.

## 3D views

Atlas draws isometric pictures of an area, one column per block with its side faces, trees as
crowns on trunks, water and plants. Trees and hills cast soft shadows from a fixed sun, corners and
the foot of cliffs are a little darker, and lakes and rivers have a lighter shoreline. A view covers a square of chunks around a point, from 1 to 16
chunks in every direction, seen from the south east, south west, north west or north east, at one to
three pixels per block edge. Wider views get a smaller scale, 3 up to radius 5, 2 up to radius 8 and 1
above, so the largest picture stays near 4400 by 2400 pixels.

**What the renderer reads.** Views are read straight from the chunks, not from the world map images.
Only explored chunks inside the world's render limit are drawn. Anything else in the square is a
flat grey floor, so a view never shows land nobody has seen and the server never generates terrain
for it. Block colours come from the game's own block textures with the biome tint.

**Limits.** Views are drawn one at a time on their own thread. They have no budget of their own on
purpose. Like tiles, every read of a chunk that is not already in memory takes a token from
`chunkImagesPerMinute`, and views only use its upper half as background work, so a large view never
slows the tiles people are looking at. Browsers may hold at most eight views in the queue, and at most
two views wider than 17 by 17 chunks are drawn at a time, and the thread rests two seconds after a large
one. One address may hold `isoViewsPerAddress` views (4 by default), at most `isoLargeViewsPerAddress`
of them large and waiting (2 by default). A new view from an address at its allowance replaces the oldest
of its views that has not started. Views a
browser asked for are drawn first, then daily views and console renders in the order they came. A radius 4 view takes under
a second, a radius 16 view up to a minute when nothing is cached. While a view is drawn the browser
gets an estimate and asks again.

**Cache.** Finished views are kept in memory up to `isoCacheMb` and on disk under `iso` up to
`isoDiskMb`, oldest first out. A view is drawn again when a chunk inside it is saved, and at least
every six hours. A view drawn while chunks were missing stays in memory for a minute only.

**Rate limit.** One address may start `isoRequestsPerMinute` new renders a minute. Cached views and
asking again about a queued view are free. Like the other limits it does not apply to local and
private addresses while `trustedProxies` is empty.

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

**In the Admin tab.** The Key places section lists the places with a button that shows each on the map
and a remove button that asks for a second click. Its form takes a name, a label made from the name, X,
Z and a radius, and starts from the last spot right clicked on the map in this world, or the middle of the
map. A right click on the map offers Make this a key place to admins, which opens the form there. The
Key places layer shows each place on the map for admins as a camera symbol with View in 3D.

**From the console.** `/atlas iso <world> <x> <z> [radius] [scale]` draws a view without the browser
limits and saves it under `iso/exports` in the data folder.

**In the browser.** Clicking a marker opens its card with a View in 3D button. That covers spawn,
places, engine markers, shared markers, your own markers, homes and deaths, faction waypoints and
other points, marker layer points and shapes, and players for admins and for yourself. The Markers tab
has a 3D button on every row, the Players tab has one for admins and on your own row, and a right
click or long press on the map offers View this area in 3D for any spot. The action only shows while
the server offers 3D views to the viewer.

The view opens in a lightbox that counts down while the server draws it. Drag to move the picture,
scroll or pinch to zoom, double click to zoom in, and double click again, press 0 or use the fit button
to see it whole. The
SE, SW, NW and NE buttons, or the left and right arrows, turn the view, and a compass in the corner shows
where north lies and which side the view is seen from. 2, 4, 8 and 16 set how many
chunks it reaches in every direction. A large radius is drawn at a smaller scale and the lightbox says
so. A partial view has a Try again button. Download saves the PNG, and Copy link copies an address
with `iso=world,x,z,r,az,s` that opens the same view for anyone who may see it. Esc closes it.

**Key places in the timeline.** The Timeline tab has one Key places block. Chips pick the place when
there is more than one, and the choice is remembered. The picture shows the picked direction for the
day on the slider, or the newest day while the map is live, with the day and direction on it. A day
whose picture did not change says unchanged since the day it was drawn. Under the picture a day strip
marks every day held for the place, the shown day filled, unchanged days muted and a dot where the main
slider stands, and a click shows that day. A small switch picks the direction, SE, SW, NW or NE. Play
steps the place through every day held with a progress bar, Compare opens the picture next to the day
before with the divider, and Live 3D opens the current view of the place.

**Daily views in the timeline.** The Timeline tab shows the day's other 3D views as thumbnails under its
figures, or the newest day's while the map is live. A thumbnail opens the lightbox. Pick another day
under Compare with a day to lay the two pictures over each other with a divider, the older day on the
left. Drag the divider, or focus it and use the arrow keys, Home and End. Live view opens the current
3D view of the same area.

## Timeline exports

The Export block of the Timeline tab has a From and To range, which starts at the first and last day
the slider holds.

- **Save image** downloads the map as it is framed on screen, as a PNG with the date in the bottom left
  corner. On a past day it shows that day's tiles, with the changed tint when it is on. Markers and
  other overlays are not part of the picture.
- **Make a video** records every snapshot in the range as a WebM video at the current place and zoom,
  at 0.25, 0.5 or 1 second a snapshot, with the date and hour stamped on each and, if you tick it, the
  changed tiles tinted. Progress shows picture by picture and Cancel stops it. A browser without video recording says
  so, and Save image still works there.
- **Export data**, for admins, downloads the zip described below for the range. One file holds at most
  90 days, so a longer range exports the 90 days that end at To.

### Timeline data export

Admins can download a world's timeline as a zip from
`/api/timeline/{world}/export?from=<date>&to=<date>`, at most 90 days at a time. It holds a
`README.txt`, then per day its events and per slot its tile list, stats and 3D views, then every tile
image those slots name once. One export runs at a time per server. Files are copied as they are stored, so player
names are not hidden, which is why only admins may export.

## Marker layers

Atlas shows extra marker layers next to its own markers, each with its own checkbox under Marker
layers in the layers menu, highest priority first. Layers come from three places, JSON files, other
plugins and the `/atlas marker` commands. Browsers learn about changes over the event stream and the
tier is checked whenever a layer is served, so changing a tier takes effect at once.

### Drop folder files

Put one file per layer in `mods/Ryvrook_Atlas/markers/`, named like `towns.json`. A name may hold
letters, digits and `_ . -`. Atlas checks the folder every five seconds and picks up new, changed and
deleted files, so no reload is needed. A file that does not parse is logged once and its last good
version stays on the map until the file is fixed. Files are at most 2 MB, a layer holds at most
10,000 markers and a line or area at most 2,000 points. Unknown keys make a file invalid, so a
misspelt field is noticed.

```json
{
  "label": "Towns",
  "tier": "public",
  "defaultHidden": false,
  "priority": 0,
  "minZoom": null,
  "maxZoom": null,
  "world": "default",
  "markers": [
    {"id": "capital", "x": 10, "z": 20, "icon": "Home", "label": "Capital", "popup": "Founded on day one"},
    {"id": "road", "kind": "polyline", "points": [[0, 0], [64, 32], [128, 0]], "tint": "#ffffff", "weight": 2},
    {"id": "farms", "kind": "polygon", "points": [[0, 0], [50, 0], [50, 50], [0, 50]], "fill": "#33aa3355"},
    {"id": "arena", "kind": "circle", "x": 300, "z": -200, "radius": 40, "world": "default"}
  ]
}
```

| Field | Meaning |
| --- | --- |
| `label` | Name in the layer list, the file name when missing |
| `tier` | Who sees the layer, `public`, `linked`, `faction` or `admin`. Public when missing |
| `defaultHidden` | List the layer switched off until the viewer turns it on |
| `priority` | Higher layers are listed first and drawn on top |
| `minZoom`, `maxZoom` | Map zoom range in which the layer draws, null for every zoom |
| `world` | World of every marker that does not name its own |
| `id` | Marker id, 1 to 64 letters, digits or `_ . : -` |
| `kind` | `point` (default), `polyline`, `polygon` or `circle` |
| `x`, `z`, `y` | Block position of a point or the centre of a circle. `y` is optional |
| `points` | `[x, z]` pairs of a line (2 or more) or an area (3 or more) |
| `radius` | Circle radius in blocks |
| `icon` | Points only. An engine marker icon name such as `Home`, or an image data URL of at most 8 KB |
| `label` | Plain text next to the marker, at most 200 characters |
| `popup` | Text shown when the marker is clicked, at most 4,000 characters. Any HTML in it shows as text |
| `tint` | Icon or outline colour such as `#ff8800` |
| `fill` | Inside colour of an area or circle such as `#ff880055` |
| `weight` | Outline width in pixels, at most 20 |
| `faction` | Faction tag the marker belongs to |

The zoom range uses the Atlas zoom levels listed in `tile.levels` of `/api/worlds`, where a larger
number is zoomed further out. On a layer with tier `faction`, a marker with a `faction` tag shows only
to members of that faction and a marker without one to every faction member. Data URLs must be base64
png, jpeg, gif, webp or svg images. A file can never send raw HTML. A popup from a file is always
escaped, and a file that holds `popupHtml` is refused.

### Java API

Other plugins compile against the Atlas jar, list Atlas as an optional dependency in their manifest
and use `dev.ryvrook.atlas.api.AtlasApi`.

```json
"OptionalDependencies": {"Ryvrook:Atlas": "*"}
```

```java
AtlasApi.onEnable(api -> {
    MarkerLayer shops = api.layer("myplugin", "shops").label("Shops").tier(Tier.PUBLIC).priority(5);
    shops.point("default", "market", 120, -40).label("Market").icon("Home").popup("Open at dawn");
    shops.polygon("default", "plaza", List.of(
            new double[]{0, 0}, new double[]{30, 0}, new double[]{30, 20})).fill("#ffcc0055");
});
AtlasApi.onDisable(() -> {
    // Atlas stopped. Its layers and markers are gone, drop your references.
});
```

**Lifecycle.** `AtlasApi.get()` is null while Atlas is not running. `onEnable` runs at once when
Atlas is already up and again every time Atlas starts. `onDisable` runs every time Atlas stops, and
the layers and markers go with it, so register them again from `onEnable`. Both hooks stay
registered across `/atlas reload`. A hook that throws is logged and never reaches Atlas.

**Layers and markers.** `layer(pluginId, layerId)` makes a layer on first use and returns it after
that. Layers take `label`, `tier`, `defaultHidden`, `priority`, `minZoom` and `maxZoom`, and add
markers with `point`, `polyline`, `polygon` and `circle`. Adding a marker with an id that already
exists in that world replaces it. Layers also have `get`, `remove`, `clear`, `markers` and `worlds`,
and `api.removeLayer` drops a whole layer. Markers take `label`, `popup`, `tint` and `faction`,
points take `y` and `icon`, shapes take `fill` and `weight`, and every marker has `remove`. Setters
apply at once and can be chained. `api.layers()` lists every layer, API, drop folder and admin layers
alike. Everything is thread safe and never touches a world thread. Bad input throws
`IllegalArgumentException`.

**Popups.** `popup` text is escaped. `popupHtml` sends HTML as it is, for HTML your plugin builds
itself and never for text players typed. The map's Content Security Policy still blocks scripts.

**Vanish.** `api.setHidden(uuid, true, "vanished")` hides a player the same way `/atlas hide` does,
for as long as Atlas runs. It is not saved, so call it again after a restart. Admins see the reason
on the map. `api.isHidden(uuid)` says whether a player is hidden for any reason.

**Sessions.** `dev.ryvrook.atlas.api.SessionApi` is a separate API with its own version for plugins
that serve a web page of their own and want to share the map's login. It is described in
[docs/API.md](docs/API.md#session-api-for-plugins).

### Admin markers

`/atlas marker` keeps the layer `atlas:admin`, labelled Server markers, in `markers/admin.json`.
It is public by default. You can edit the file by hand, for example to change its `tier` or `label`.
When the file has an error the commands refuse to change it, so your edit is not overwritten. The
commands only accept worlds that are loaded, so a typo never lands in the file.

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

## Hiding players

| Way | Who | Saved | Hides |
| --- | --- | --- | --- |
| `/atlas hide` | The player | Yes | Position and name |
| `/atlas hide <player>` | Staff | Yes | Position and name |
| Snapshot hidden flag | The factions plugin | In its file | Position and name |
| `AtlasApi.setHidden` | A plugin | No | Position and name |
| Crouching | Automatic, off by default | No | Position only |
| Invisible | Automatic, on by default | No | Position only |

A hidden player is never shown to non admins, except to themselves. Where their name would appear,
in the kill feed, the feed, the leaderboard, the timeline and as marker author, they are "A player"
with no UUID, and they are left out of the offline list. Crouching and invisible players only lose
their position, their names still show in kills, feeds and the leaderboard.

A player counts as invisible while the engine hides them from adventure players, which
`/entity hidefromadventureplayers` and vanish style tools set, or while an entity effect whose id
names invisibility is active. Both rules are read on the world thread every two seconds, so a
position may show for up to two seconds after a player crouches.

Every rule is applied when data is served, never when it is stored, so showing a player again brings
their history back. Admins see a hidden badge and the reason, such as hide list, factions flag,
crouching, invisible or the reason a plugin gave. `/atlas show <player>` says when a player stays
hidden for another reason.

## Security

**Who sees what.**

| Viewer | How | Sees |
| --- | --- | --- |
| Public | Any browser | Layers marked `public` |
| Linked | Entered a code from `/atlas link` | `linked` layers, own faction data for `faction` layers, own private markers |
| Admin | Linked with `atlas.admin`, or sends `adminToken` | Every layer, hidden players, exact coordinates and health |

For non admins, coordinates are rounded to whole blocks and offline positions to the chunk. A player
below `hideBelowY` is sent with null `x`, `y` and `z` and `"underground": true`. Admins can add
`?as=TAG` to any endpoint to see what a member of that faction sees.

**Private markers.** Personal markers, homes and death places are sent only to the browser linked to
that player, never to anyone else, admins included. They are kept in memory for an hour after logout
and never written to disk.

**Fail closed config.** An invalid `atlas.json` keeps the web server down, at start and on reload,
as described under [Configuration](#configuration).

**Rate limits.** API requests and tile requests are limited per address by `apiRequestsPerMinute` and
`tileRequestsPerMinute`, as a bucket that holds one minute of requests and refills steadily. Beyond
it Atlas answers 429 with `Retry-After`. IPv6 clients share one limit per /64, and every spelling of
an address counts as the same address. The page and its files are never limited. While
`trustedProxies` is empty, loopback, private and link local peers (fe80::/10 included) are not
limited per address, since behind an unlisted proxy every browser would share the proxy's address.
Link codes and wrong admin tokens share a separate limit of 10 attempts per minute and address. At
most 64 requests are handled at once, event streams aside, and more get 503.

**Sessions and revoke.** Linking stores a session cookie named by `cookieName`, `ryvrook_session` by
default, that lasts 30 days after the last visit. It is HttpOnly, SameSite=Lax and Path `/`, Secure
when a trusted proxy reports https, and carries a Domain only when `cookieDomain` is set. Atlas 1.1.0
still accepts the `atlas_session` cookie of 1.0.0 and moves it to the new name on its next answer.
`sessions.json` keeps only a SHA-256 hash of each session token. Link codes are single use and
last five minutes. The clickable link in `/atlas link` carries the code as `?code=`. The map fills it
into the link dialog, sends it at once and takes it out of the address bar straight away, so a reload
or a copied address never sends it again. A used or expired code shows the reason in the dialog.
`/atlas unlink <player>` signs every browser of that player out. Each open event stream checks its
session and admin token again every 30 seconds, so a revoke or a lost permission applies without a
reconnect.

**Token rotation.** Put a new `adminToken` of at least 24 characters in `atlas.json`, or empty the
value to get a random one, and run `/atlas reload`. The old token stops working at once for requests
and within 30 seconds for open streams.

**Stream resume.** Each event frame carries an id. A browser that reconnects within a minute, as the
same viewer and for the same world, gets the frames it missed, at most the last 200, without a new
`hello`. Those frames are replayed exactly as they were built for that viewer during that minute, so
a resume can show up to a minute of data from before a change, such as a player's position from just
before they were hidden. A viewer whose access changed resolves to a different viewer and starts
over with a fresh `hello`. Frame ids change with every restart, so ids from before a restart never
resume.

**Headers.** The page and its files carry a Content Security Policy, `Referrer-Policy` and
`X-Content-Type-Options`. No inline scripts run except the one boot script allowed by its hash.

## Reverse proxy

Atlas speaks plain HTTP. A reverse proxy is recommended whenever the map is reachable from the open
internet, for HTTPS and for connection limits beyond Atlas's own. Atlas gives a client 30 seconds to
send a request and closes an event stream that takes no data for 15 seconds.

List the proxy in `trustedProxies`. A request whose direct peer is trusted is treated as coming from
the last address in `X-Forwarded-For` that is not itself a trusted proxy. That address is used for
the rate limits, the stream limit per address and log lines. `X-Forwarded-Proto` from a trusted proxy
tells Atlas the request came over https. Entries that are not addresses or CIDR ranges are ignored
with a warning.

To mount the map under a sub path such as `/map`, set `basePath` to that path and forward it
unchanged. Requests work with and without the prefix, and the bare prefix redirects to the same path
with a trailing slash. The client only uses relative addresses. Event streams are long lived, so turn
off response buffering for `/api/events` in the proxy.

```json
{
  "bind": "127.0.0.1",
  "publicUrl": "https://example.com/map/",
  "basePath": "/map",
  "trustedProxies": ["127.0.0.1"]
}
```

> **Warning** Behind a reverse proxy, always list it in `trustedProxies`. Otherwise every browser shares the proxy's address for rate limits and logs.

**One login with Chronicle.** Serve Atlas and Chronicle from one host name under two base paths,
such as `https://example.com/map/` and `https://example.com/stats/`. Give both the same `cookieName`,
which is the default, and leave `cookieDomain` empty. The session cookie is sent to both paths, and
Chronicle looks it up through the session API, so a browser linked on one page is linked on the other.
Set `cookieDomain` to the shared parent domain only when the two run on sibling host names such as
`map.example.com` and `stats.example.com`.

```json
{
  "bind": "127.0.0.1",
  "publicUrl": "https://example.com/map/",
  "basePath": "/map",
  "trustedProxies": ["127.0.0.1"],
  "chronicleUrl": "https://example.com/stats/"
}
```

## How it works

### Embedded HTTP server

Atlas serves the web client and a JSON API from inside the Hytale server. `index.html` is sent with
`Cache-Control: no-cache` and an ETag. Every script, stylesheet and icon it references carries
`?v=<version>` and is cached for a day. Text files are compressed once at start and sent gzipped to
browsers that accept it. Static files, tiles and marker icons carry a strong ETag and answer a
matching `If-None-Match` with 304, and every GET endpoint also answers HEAD.

When the port is taken at start, Atlas logs one warning that says whether the port answers like
another web map and tries again every 30 seconds for 10 minutes. It logs one line when the bind
finally works, or one SEVERE line when it gives up, after which `/atlas reload` starts over.

Periodic work runs on Atlas's own scheduler and tile threads, and anything that touches a world hops
to that world's thread.

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

Each world's tile folder holds a stamp with the world UUID and seed. When a world is replaced under
the same name, Atlas clears the old tiles once and logs one line. Chunks that no longer exist are
dropped on the ten minute refresh. Tiles are written through a temporary file that is flushed and
moved into place, so a crash never leaves a torn tile, and leftover temporary files are removed at
start.

### Event stream

`/api/events` is a server sent events stream. Player payloads are built once per viewer class each
tick and shared by every client in that class. A tick lists only players that moved, with
`"full": false` and departed players under `left`. Every ten seconds a full list goes out with
`"full": true`.

When a slow client's queue is full, the oldest data frame is dropped, never a control frame such as
`hello`, `markers`, `tiles`, `worlds`, `layers` or `timeline`. The stream asks browsers to wait 3 to
5 seconds before a reconnect, different per client, so they do not all return at once after a
restart. Each address may hold `sseClientsPerIp` streams and more get 429. While `trustedProxies` is
empty, loopback and private addresses are only held to the global limit of 500.

### Web client

The client speaks API version 1. When the server runs a newer jar, a one line bar asks the viewer to
reload, and a tab in the background reloads on its own. After a dropped stream the browser reconnects
by itself with a random delay and refetches its data spread over one second. A 403 answer shows a
panel as private, a 404 says the thing is not on the map, and a 429 pauses every request until its
`Retry-After` has passed.

Every map marker has an accessible name made of its label and kind. A world or layer with more than
300 markers keeps only the markers near the view on the map. Keyboard shortcuts are `/` for search,
`f` to find yourself, `l` for the panel and `t` for the theme.

## HTTP API

The browser API is frozen as version 1. `GET /api/worlds` and the `hello` event carry
`apiVersion: 1`. Every answer is built by Atlas from a fixed field list, every key is always present
and holds `null` when there is no value, times are epoch milliseconds and durations are seconds in
keys that end in `Seconds`. A layer the viewer may not see answers 403 and a missing thing 404, both
with `{"ok": false, "error": "..."}`.

The endpoints are listed here. Shapes, events and the visibility rules are in
[docs/API.md](docs/API.md).

- `GET /api/worlds`, `/api/players`, `/api/markers/{world}`, `/api/layers/{world}`
- `GET /api/worldevents`, `/api/kills`, `/api/leaderboard`, `/api/stats`, `/api/chronicle`
- `GET /api/territories/{world}`, `/api/points/{world}`, `/api/factions`, `/api/factions/{tag}`, `/api/nations`
- `GET /api/changes/{world}`, `/api/heat/{world}`
- `GET /api/tiles/{world}/{zoom}/{x}/{z}.png`, `/api/marker-icons/{Name}.png`
- `GET /api/timeline/{world}` and the slot routes under it for tiles, changed tiles, stats, events and 3D views
- `GET /api/timeline/{world}/export`, a zip for admins
- `GET /api/iso`, `/api/iso/{world}` and `/api/iso/{world}/marker/{id}` for 3D views
- `GET /api/events`, the server sent events stream
- `GET /api/health`, `ok` and `version` for everyone, and the full state with degraded checks for admins
- `POST /api/link`
- `GET`, `POST` and `DELETE /api/admin/keyplaces`, the key places, for admins

Admin requests send the token as `X-Atlas-Token` or `Authorization: Bearer <token>`.

## Feats

When the separate Feats plugin writes its export at `featsExport`, a Leaderboard tab appears with the
Feats totals, the top players with their last unlock time, recent unlocks with the rarity of each
feat and a rare tag under 5 percent, the world milestones and the server firsts. Feats unlocks,
milestones and firsts are written to the timeline event log, and each day's stats carry a Feats
summary. Atlas reads the file only. A file that cannot be read is logged once and the last good copy
stays on the map. Without the file the tab stays hidden.

## Chronicle

When the separate Chronicle plugin writes its stats export at `chronicleExport`, a Chronicle tab
appears next to the Stats tab with the players online, the headline server totals such as time
played, kills and blocks broken, and the top players of each board. With `chronicleUrl` set the tab
links to the Chronicle web page. Hidden players are shown as "A player", whether Atlas or Chronicle
hides them. The tab follows the `stats` visibility. Atlas checks the file every five seconds by its
modification time and size, reads it only, logs a file that cannot be read once and keeps the last
good copy. Without the file the tab stays hidden and the Stats tab is unchanged.

Chronicle can also share the login with Atlas through `dev.ryvrook.atlas.api.SessionApi`, see
[docs/API.md](docs/API.md#session-api-for-plugins) and [Reverse proxy](#reverse-proxy).

## Works with TrackerAPI

Atlas works with [TrackerAPI](https://www.curseforge.com/hytale/mods/trackerapi/), the shared tracking plugin behind Feats and Chronicle. When TrackerAPI
1.0.0 or newer is installed, Atlas takes block breaks, block places and deaths from it for the heat
layer and the kill feed, and registers none of its own systems for them, so the server counts each
event once. Without TrackerAPI Atlas tracks them itself as before. The heat and the kill history carry
on in the same files either way. Set `tracker` to `never` to keep Atlas's own systems, or to `always`
to rely on TrackerAPI alone. After installing, updating or removing TrackerAPI, restart the server or
reload Atlas as the log asks.

## BetterMap compatibility

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

## Factions snapshot

Atlas can show territories, factions, nations, sieges and faction events from an optional factions
snapshot file, a companion file written by a separate plugin. You do not need that plugin to run
Atlas. Atlas reads the file at `dominionSnapshot` and never links against the plugin that writes it.
Without the file Atlas runs standalone with terrain, players, markers and zone names.

The file is reloaded when its modification time changes, checked once a second. A missing file is
logged once with the path Atlas looked at, and a file that fails to parse is logged at most once a
minute. Atlas reads snapshot version 1. A file with another version is still read and Atlas logs one
warning. Nothing from the file reaches browsers unless Atlas lists the field.

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
- Player `x`, `y` and `z` may be null or left out. Such players count as online, follow the hidden player rule, and are sent without a position, never at 0, 0.
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

## Building from source

Atlas builds with Gradle and requires JDK 25. When no JDK 25 is installed, the foojay toolchain
resolver configured in `settings.gradle.kts` lets Gradle download one. The Hytale server artifact is
fetched from the Hytale Maven repository, and `gradle.properties` selects the channel and version.

```sh
./gradlew build
```

The jar lands in `build/libs/Atlas-<version>.jar`. `./gradlew installMod` copies it into your local
Hytale `UserData/Mods` folder, or into another folder with `-PmodsDir=/path/to/server/mods`.

The version is set once, in `build.gradle.kts`. Gradle writes it into `manifest.json` and into the
version Atlas reports. `./gradlew build` runs the JUnit 5 tests under `src/test/java`, and
`./gradlew test` runs them alone.

## Contributing

Issues and pull requests are welcome. Please keep each change focused on one thing, describe what it
fixes or adds, and make sure `./gradlew build` passes with its tests. A change to an API shape must
keep version 1 intact, see [docs/API.md](docs/API.md). [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
covers the packages, the tests, the headless smoke test, tile internals, the thread model, marker
merging, the timeline and the BetterMap details.

Changes between versions and upgrade notes are listed in [CHANGELOG.md](CHANGELOG.md).

## License

Atlas is released under the [MIT License](LICENSE). Copyright (c) 2026 ryvrook.

Atlas bundles [Leaflet](https://leafletjs.com/) 1.9.4, which is released under the BSD-2-Clause
license. Its license text is in
[src/main/resources/web/vendor/LICENSE](src/main/resources/web/vendor/LICENSE).

The bundled `leaflet.js` has its trailing source map comment removed, so its hash differs from the official 1.9.4 file.
