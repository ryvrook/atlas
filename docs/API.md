# Atlas browser API, version 1

This is the reference for the JSON API and the event stream that the Atlas web client uses. Scripts,
bots and other sites may use it too. Every payload is built by Atlas from an explicit field list.
Nothing from the factions snapshot file, the Feats export or the Chronicle export is passed through as
a raw object. The Java session API for other plugins is described at the end, under
[Session API for plugins](#session-api-for-plugins).

## Versioning

`apiVersion` is `1`. It appears at the top level of `GET /api/worlds` and of the `hello` event, and
inside the `server` object of both.

Within version 1 Atlas promises the following.

- No field listed here is removed, renamed or given another type.
- A key listed here is always present. When it has no value it holds `null`.
- New fields, new endpoints, new event names and new values of open ended strings (such as a
  timeline event `kind`) may be added. Clients must ignore what they do not know.
- A change that breaks any of the above raises `apiVersion` to 2.

The web client in the jar always matches the server it came with. A client that sees
`server.version` change in `hello` or `/api/worlds` should reload.

## Conventions

- Every field has one JSON type.
- Every point in time is epoch milliseconds, in a key ending in `At` or named `at`, `now`, `since`
  or `updatedAt`.
- Every duration is a number of seconds in a key ending in `Seconds`. No key ends in `Ms`.
- The second map axis is `z` everywhere, in blocks, chunks and tiles. Tile rows are `[zoom, x, z]`.
- `cx` and `cz` are chunk coordinates. A chunk is 32 blocks.
- Types below use `string`, `int`, `number`, `bool`, `ms` (int epoch milliseconds), `T|null`, `[T]`
  for an array and `{key: T}` for an object with free keys.
- Every JSON answer carries `Access-Control-Allow-Origin: *` and `X-Content-Type-Options: nosniff`.
  Every GET route also answers HEAD.

## Authentication and viewers

A request is public unless it carries a link session or the admin token.

- A browser linked with `/atlas link` sends the session cookie named by `cookieName`,
  `ryvrook_session` by default. Atlas 1.1.0 also accepts the `atlas_session` cookie of 1.0.0 and
  answers it with a `Set-Cookie` that stores the same session under the new name and clears the old
  cookie. Accepting `atlas_session` ends with the next release.
- Scripts send the `adminToken` from `atlas.json` as `X-Atlas-Token` or `Authorization: Bearer <token>`.
- An admin may add `?as=TAG` to any route to see what a member of that faction sees.

A wrong admin token counts against the same limit as link codes, 10 attempts per minute and address,
and further requests that carry a token then get 429.

## Errors

Every error with a body uses one shape.

```
{ok: false, error: string}
```

| Status | When |
| --- | --- |
| 400 | Bad input, such as a malformed date or coordinates that are not whole numbers |
| 403 | The viewer may not see the layer the route belongs to |
| 404 | Unknown world, faction, day or endpoint, a disabled world, or a world hidden from this viewer |
| 405 | A method other than GET or HEAD, or anything but POST on the link route |
| 409 | A timeline export while another one runs. Carries `Retry-After` |
| 415 | A link request that is not JSON |
| 429 | A rate limit. Carries `Retry-After` in seconds |
| 503 | Too many requests at once, a tile render that could not start in time, or a 3D view that just failed. Carries `Retry-After` |

A 3D view that is still being drawn answers 202 with a JSON body, see [3D views](#3d-views).

Tile and image routes answer 404 and 503 without a body, since browsers load them as images. Their
403 still has the JSON body.

## Rate limits

API routes count against `apiRequestsPerMinute` per address (600 by default). Tiles, marker icons and
timeline day tiles count against `tileRequestsPerMinute` (3000 by default). Both are buckets that hold
one minute of requests and refill steadily. IPv6 clients share one bucket per /64. The page and its
static files are not limited.

## Visibility

Each route belongs to a visibility layer from `atlas.json`. A viewer who may not see that layer gets
403. `viewer.layers` in `/api/worlds` and `hello` says which layers apply to the viewer.

**Hidden players.** For a viewer who is not an admin and not the player themself, a hidden player is
never listed in players or offline rows, and wherever a hidden player's name appears elsewhere
(kills, leaderboard rows, recent unlocks, milestones, firsts, marker authors, chat, timeline events)
the name is `"A player"` and the uuid is `null`. Feed text is shared by every viewer and always says
"A player" for hidden players in lines Atlas writes. Players hidden only by the crouching or invisible
rule lose their position but keep their name.

**Hidden worlds.** A world with `hiddenWorld: true` answers 404 to non admins on every route that
names it. Players, kills, feed lines, world events and tile updates in it never reach those viewers.
A world disabled in `atlas.json` is left out of `/api/worlds` for everyone, admins included, and
every route that names it answers 404.

**Positions.** Positions are whole blocks for non admins and two decimals for admins. Offline
positions are snapped to the chunk corner for non admins other than the player themself.

## Shared objects

```
Viewer      {linked: bool, name: string|null, faction: string|null, admin: bool, viewingAs: bool,
             money: bool, layers: {<layer>: bool}}
Server      {apiVersion: 1, title: string, publicUrl: string|null, version: string, now: ms,
             uptimeSeconds: int, betterMap: bool}
Party       {name: string, uuid: string|null, type: string, player: bool, boss: bool}
Kill        {at: ms, world: string|null, victim: Party, killer: Party|null, cause: string|null, ranged: bool,
             x: int|null, z: int|null}
FeedLine    {at: ms, type: string, text: string, source: "atlas"|"dominion", world: string|null}
ChatLine    {at: ms, name: string, tag: string|null, text: string}
Siege       {id: string|null, attacker: string|null, defender: string|null, phase: string|null, world: string,
             chunk: [cx, cz]|null, endsAt: ms|null}
NationRef   {tag: string, name: string, color: string|null}
```

- `viewingAs` is true when an admin views the map as a faction, and `faction` then holds that
  faction's tag, which the client sends back as `?as=`.
- `money` is true when `/api/money` has the viewer's own balance to give, which needs a linked player,
  Coinage and `showOwnMoney`. Not in Atlas 1.6.0 and older.
- `layers` has one key per visibility layer, which are players, paths, lastSeen, territories, points,
  events, zones, factions, nations, stats, terrain, heat, feed, chat, kills, markers, worldevents,
  leaderboard, layers, timeline and iso.
- `Kill.x` and `Kill.z` are the block where the victim died. They are null for kills recorded by older
  releases, when the position could not be read, and when the live map would not have shown a player
  of the kill where they stood (below `hideBelowY`, crouching or invisible under a hide rule, or in their
  own territory with `hidePlayersInOwnTerritory` on). Such places are never stored. For non admins they
  are snapped to the chunk corner, and they are null when either player of the kill is hidden from the
  viewer or the world is one the viewer may not open.
- `Party.uuid` is null for creatures and for hidden players. `Party.type` is the creature role id, or
  `"Player"` for players.
- `FeedLine.world` names the world a line is about (deaths, boss kills and world events written by
  Atlas, or the world of a factions line) and is null otherwise. `source` is `"dominion"` for lines
  from the factions snapshot file.

## Endpoints

### GET /api/worlds

Always 200.

```
{apiVersion: 1, title: string, standalone: bool, defaultWorld: string|null, viewer: Viewer, server: Server,
 tile: {size: int, chunkPixels: int, chunkBlocks: int, blocksPerTile: int, minZoom: int, maxZoom: int,
        levels: {"<zoom>": {pxPerBlock: number, chunksPerTile: int}}},
 worlds: [World]}

World   {name: string, kind: "world"|"portal"|"instance", displayName: string, hiddenWorld: bool,
         portal: Portal|null, players: int|null, spawn: {x: int, y: int, z: int}|null,
         time: {hour: int, dayProgress: number}|null, mapEnabled: bool, exploredChunks: int,
         bounds: {minX: int, minZ: int, maxX: int, maxZ: int}|null,
         border: Circle|null, renderLimit: Circle|null}
Portal  {type: string|null, typeName: string, timeLimit: bool, remainingSeconds: number|null,
         elapsedSeconds: number|null, voidStage: int|null, voidActive: bool, entries: int}
Circle  {shape: "circle", x: number, z: number, radius: number}
```

- `defaultWorld` is the world to open first. Null means the client picks, and it is also null when
  the viewer may not open that world.
- `displayName` follows `worlds.<name>.displayName` in `atlas.json` when set.
- `hiddenWorld` follows `worlds.<name>.hidden` when set, and otherwise is true for portal and
  instance worlds while `showPortalWorlds` is off.
- `players` is null when the viewer may not see players.
- `border` is set only on worlds BetterMap tracks.
- `renderLimit` is the circle outside which tiles answer 404, so the client may draw it as the edge
  of the map.

### GET /api/players

Takes `world` and `trails=1`. Follows `players`, and answers 403 to non admins while `showPlayers` is
off.

```
{at: ms, players: [Player], offline: [Offline]|null}

Player  {uuid: string, name: string, world: string|null, x: number|null, y: number|null, z: number|null,
         located: bool, underground: bool, hidden: bool|null, hiddenReason: string|null,
         faction: string|null, color: string|null, health: number|null, maxHealth: number|null,
         self: bool, source: "live"|"snapshot", trail: [[x, z]]|null}
Offline {uuid: string|null, name: string, faction: string|null, at: ms, world: string,
         x: int|null, z: int|null}
```

- `located` false means online without a known position. `underground` true means below
  `hideBelowY`, with null coordinates.
- `hidden` and `hiddenReason` are null for non admins. For admins `hiddenReason` is `"list"` (the
  hide list), `"dominion"` (the snapshot flag), `"crouching"`, `"invisible"`, or the reason a plugin
  gave through `AtlasApi.setHidden`.
- `trail` is null unless trails were asked for and `paths` is visible.
- `offline` is null unless `lastSeen` is visible.
- Players in a world the viewer may not open are left out, except the viewer themself.

### GET /api/markers/{world}

Follows `markers`.

```
{world: string, at: ms|null, markers: [Marker]}

Marker  {id: string, kind: "spawn"|"poi"|"engine"|"shared"|"personal"|"home"|"death",
         source: "spawn"|"poi"|"warp"|"block"|"other"|"shared"|"personal"|"home"|"death",
         name: string, x: int, y: int|null, z: int, icon: string|null, major: bool, alert: Alert|null,
         tint: string|null, by: {uuid: string|null, name: string|null}|null, own: bool}
Alert   {icon: string|null, durationSeconds: number, timeoutSeconds: number, tint: string|null}
```

`kind` is the marker type the client draws by and `source` says where it came from. `own` is true for
the viewer's personal markers, homes and deaths, which no other viewer ever gets. `by` is null unless
`showMarkerAuthors` is on and an author is known. Duplicates from different sources are listed once.

### GET /api/layers/{world}

Marker layers from plugins, drop folder files and the `/atlas marker` commands. Follows `layers`. 400
without a world. A layer whose own tier the viewer does not meet is left out, as is a layer with no
markers in this world that the viewer may see.

```
{world: string, at: ms, layers: [Layer]}

Layer        {id: string, plugin: string, label: string, source: "api"|"file"|"admin",
              tier: "public"|"linked"|"faction"|"admin", defaultHidden: bool, priority: int,
              minZoom: int|null, maxZoom: int|null, updatedAt: ms, markers: [LayerMarker]}
LayerMarker  {id: string, kind: "point"|"polyline"|"polygon"|"circle", label: string|null, popup: string|null,
              x: number|null, y: number|null, z: number|null, radius: number|null, points: [[x, z]]|null,
              icon: string|null, iconData: string|null, tint: string|null, fill: string|null,
              weight: number|null, faction: string|null}
```

- `id` is `plugin:layer`. Drop folder files are `file:<file name>` and the admin layer is
  `atlas:admin`.
- Layers are sorted by `priority`, highest first, then by id. Higher priority draws on top.
- `minZoom` and `maxZoom` use the zoom levels of `tile.levels`, where a larger number is further out.
  Null means every zoom.
- A `point` uses `x`, `z` and an optional `y`, with `icon` (an engine icon name, loaded from
  `/api/marker-icons/{icon}.png`), `iconData` (a base64 image data URL of at most 8 KB) or neither for
  the default pin. A `circle` uses `x`, `z` and `radius` in blocks. A `polyline` (open) and a
  `polygon` (closed) use `points` as `[x, z]` rows in blocks.
- `popup` is HTML ready to insert. Untrusted text is escaped by the server, with line breaks as
  `<br>`. Only a popup a plugin set through the Java API with `popupHtml` is passed as written.
- `label` is plain text. Set it as text, never as HTML.
- `tint` colours the icon or outline and `fill` the inside of a polygon or circle, as `#rgb`,
  `#rrggbb` or `#rrggbbaa`. `weight` is the outline width in pixels. Null means the client default.
- On a layer with tier `faction`, a marker with a `faction` tag is sent only to members of that
  faction and a marker without one to every faction member. Admins get every marker.

### GET /api/worldevents

Takes `world`. Follows `worldevents`.

```
{events: [WorldEvent]}

WorldEvent {id: string, world: string, name: string, displayName: string, startedAt: ms,
            x: int|null, z: int|null, located: bool}
```

### GET /api/kills

Takes `limit`, 1 to 200, 50 by default. Follows `kills`. Newest last.

```
{kills: [Kill]}
```

### GET /api/leaderboard

Follows `leaderboard`. Answers 200 with `available: false` and empty lists when no Feats export
exists.

```
{available: bool, updatedAt: ms|null, totalFeats: int, totalPoints: int, online: int|null,
 rarityPlayers: int, rarity: {<featId>: number},
 players: [Row], recent: [Unlock], milestones: [Record], firsts: [Record]}

Row     {rank: int, uuid: string|null, name: string, points: int, unlocked: int, lastUnlockAt: ms|null}
Unlock  {at: ms, uuid: string|null, name: string, feat: string, featName: string, tier: string,
         points: int, category: string, hiddenFeat: bool, rarity: number|null}
Record  {feat: string, featName: string, tier: string, at: ms, uuid: string|null, name: string|null}
```

`rarity` is the share of counted players, 0 to 1, who own each feat, empty until Feats counts enough
players. `Unlock.rarity` is the same value for that feat or null. `milestones` and `firsts` are oldest
first. A milestone reached with nobody named has `uuid` and `name` null. Rows are in Feats rank order
and `rank` starts at 1.

A feat Feats keeps hidden, which its recent list marks with `hidden`, keeps its name only for admins
and for the viewer who earned it, as far as the recent list and the firsts show. Everyone else gets
`feat` `"hidden"`, `featName` `"A hidden feat"`, an empty `category`, a null `rarity`, and no entry
for it in `rarity`. Milestones and firsts of that feat are masked the same way. `totalFeats` is the
count the export gives, which grows when other plugins add feats. Masking is not in Atlas 1.6.0 and
older.

### GET /api/territories/{world}

Follows `territories`.

```
{world: string, at: ms|null, territories: [Territory], sieges: [Siege]}

Territory {id: string|null, tag: string, name: string, color: string|null, type: string|null, raidable: bool,
           chunks: [[cx, cz]], breached: [[cx, cz]], zones: [{id: string|null, name: string, chunks: [[cx, cz]]}],
           power: number|null, maxPower: number|null, claims: int|null}
```

Sieges are those of this world that are running or ended under a minute ago.

### GET /api/points/{world}

Answers 403 only when `points`, `events` and `zones` are all hidden.

```
{world: string, points: [Point], zones: [Zone]}

Point {id: string, kind: string, label: string|null, x: int, y: int|null, z: int, color: string|null,
       faction: string|null, expiresAt: ms|null, event: bool,
       holder: string|null, control: string|null, status: string|null}
Zone  {id: string, name: string, x: int, z: int, icon: string|null}
```

`y` is null for non admins. `event` is true for hills, outposts and envoys, which follow `events`.

### GET /api/factions

Follows `factions`. Sorted by value, highest first.

```
{at: ms|null, factions: [Faction]}

Faction {id: string|null, tag: string, name: string, color: string|null, leader: string|null, members: int,
         online: int, claims: int, power: number, maxPower: number, value: number, rank: int, kills: int,
         deaths: int, siegesWon: int, siegesLost: int, level: int, points: int,
         relations: {<tag>: "ALLY"|"TRUCE"|"ENEMY"}, createdAt: ms|null, description: string|null,
         bank: number|null, nation: NationRef|null}
```

`bank` is null unless the viewer is an admin or a member. `nation` is null when the faction has none
or `nations` is hidden.

### GET /api/factions/{tag}

Follows `factions`. 404 when no visible faction has that tag.

```
{faction: Faction, lands: [{world: string, claims: int, x: int, z: int}],
 onlineMembers: [{name: string, world: string|null}], sieges: [Siege]}
```

`lands` and `sieges` are empty when `territories` is hidden. `onlineMembers` follows the players rules.

### GET /api/nations

Follows `nations`.

```
{at: ms|null, nations: [Nation], independent: [FactionBrief]}

Nation       {id: string|null, tag: string, name: string, color: string|null, memberCount: int,
              value: number, claims: int, factions: [FactionBrief]}
FactionBrief {tag: string, name: string, color: string|null, value: number, claims: int, power: number,
              members: int}
```

The `factions` lists are empty when `factions` is hidden.

### GET /api/stats

Follows `stats`.

```
{at: ms, online: int, worlds: int, uptimeSeconds: int, snapshot: {present: bool, at: ms|null},
 factions: {count: int, claims: int, topValue: [Top]|null, topPower: [Top]|null, topKills: [Top]|null}|null,
 grace: {active: bool, endsAt: ms|null}|null, eotw: bool|null, activeSieges: int|null,
 events: Events|null,
 activity: {breaksLastHour: int, placesLastHour: int, breaksLastDay: int, placesLastDay: int}}

Top      {tag: string, name: string, color: string|null, amount: number}
Events   {enabled: bool|null, koths: [EventRow], outposts: [EventRow], envoys: [string], bounties: int,
          season: string|null, seasonEndsAt: ms|null}
EventRow {name: string, status: string|null, holder: string|null, control: string|null, endsAt: ms|null}
```

`factions`, `grace`, `eotw`, `activeSieges` and `events` are null without a factions snapshot file.
The top lists are null when factions are hidden or limited to the viewer's own. `events` is null when
the `events` layer is hidden.

### GET /api/chronicle

Follows `stats`. Answers 200 with `available: false` and empty lists when no Chronicle export exists.
Added in Atlas 1.1.0.

```
{available: bool, updatedAt: ms|null, online: int|null, chronicleVersion: string|null, url: string|null,
 totals: [Total], boards: [Board]}

Total     {key: string, label: string, unit: string, value: number, headline: bool}
Board     {key: string, label: string, unit: string, rows: [BoardRow]}
BoardRow  {rank: int, uuid: string|null, name: string, value: number}
```

`url` is `chronicleUrl` from `atlas.json`, or null when it is empty. `totals` are the server totals of
the export, the headline ones first. `headline` marks players ever seen, time played, kills, deaths,
blocks broken, blocks placed, distance travelled and items crafted. `unit` is `count`, `seconds`,
`metres` or `hitPoints`. Stats Atlas does not know have a label made from their key and the unit
`count`. `boards` holds the top ten of each board that has rows, time played, kills and blocks broken
first, then the rest in the order of the file. A player hidden from the viewer, by Atlas or by
Chronicle, has the name `"A player"` and a null uuid.

### GET /api/money

The viewer's own wallet and net worth in Coinage's primary currency, formatted by Coinage. Answers 200
with `available: false` and nulls unless the viewer is a linked player, not an admin viewing as a
faction, `showOwnMoney` is true and Coinage runs. Never about another player. Not in Atlas 1.6.0 and
older.

```
{available: bool, currency: string|null, balance: string|null, netWorth: string|null}
```

### GET /api/changes/{world}

Takes `since`, an hour ago by default and at most seven days back. Follows `terrain`.

```
{world: string, now: ms, since: ms, tiles: [[zoom, x, z, at]]}
```

### GET /api/heat/{world}

Takes `since` like the changes route. Follows `heat`.

```
{world: string, now: ms, since: ms, bucketSeconds: int, chunks: [[cx, cz, breaks, places]]}
```

Faction limited viewers get only chunks inside their own land.

### GET /api/tiles/{world}/{zoom}/{x}/{z}.png

A 256 pixel PNG at zoom -2 to 4. Follows `terrain`. Carries a strong `ETag` and answers a matching
`If-None-Match` with 304. A tile that does not exist answers 404, and a render that could not start
in time answers 503 with `Retry-After`. A partial tile carries `X-Atlas-Partial: 1` and
`Cache-Control: no-store`. Tile x is the block x divided by the blocks per tile of the zoom, rounded
down, and the same goes for z.

### GET /api/marker-icons/{Name}.png

A marker image from the server's assets, such as `UserB`, `Home` or `Death`. The same for everyone,
no session is looked at. ETag and 304. 404 for unknown names.

### GET /api/health

Answers `{ok: true, version: string}` to everyone. Admins also get the following.

```
{name: string, uptimeSeconds: int, standalone: bool, sseClients: int,
 tileCache: {entries: int, bytes: int, maxBytes: int, queued: int, diskWriteFailures: int,
             lastDiskWriteFailureAt: ms|null},
 render: {queued: int, running: int, done: int, paused: bool, perWorld: {<world>: int},
          budgetLeft: int, budgetPerMinute: int}|null,
 stalledWorlds: [{world: string, updatedAt: ms}],
 bind: {state, attempts, startedAt, nextAttemptAt, error}|null,
 failingTasks: [{task: string, since: ms}],
 diskWriteFailures: [{file: string, at: ms, error: string}],
 status: "ok"|"degraded", degraded: [string]}
```

`budgetLeft` is -1 when the chunk image budget is unlimited. A world counts as stalled when its info
was not refreshed for 30 seconds. `degraded` names each problem found, from `renderBacklog`,
`worldPollStalled`, `bindRetry`, `taskFailing`, `diskWriteFailing` and `tileWriteFailing`.

### POST /api/link

Takes JSON. `{"code": "ABC123"}` links the browser and answers `{ok: true, viewer: Viewer}`.
`{"action": "logout"}` unlinks it and answers `{ok: true}`. Another content type gets 415.

## Timeline

Snapshots of each world's tiles and stats, `timeline.perDay` a day, plus an event log. Dates are
server local ISO dates such as `"2026-10-13"`. Each snapshot is a slot, a date and an hour such as
`"2026-10-13T16"`. A plain date is a slot too. Days kept by Atlas 1.1 and older are plain dates, one slot
each, and wherever a route takes `{slot}` a plain date means the day folder of that date when it holds a
snapshot, as the day list names it, and otherwise the last slot held that day, so addresses from before
1.2 keep working. Every timeline route answers 404 when `timeline.enabled` is false, for an
unknown, disabled or hidden world, and for a slot that holds nothing. Slot tiles and the changed list
follow `terrain`, every other timeline route follows `timeline`. A malformed slot answers 400.

### GET /api/timeline/{world}

```
{world: string, enabled: bool, hour: int, perDay: int, skipUnchanged: bool, hours: [int], nextRunAt: ms|null,
 retainDays: int, today: string, currentSlot: string, days: [Slot]}

Slot    {slot: string, date: string, hour: int|null, window: [int, int], snapshot: bool, pruned: bool,
         takenAt: ms|null, tiles: int, carried: int, changed: int, partial: bool, events: int, stats: bool,
         isoViews: [IsoDay]}
IsoDay  {label: string, x: int, z: int, radius: int, file: string}
IsoKey  IsoDay and {name: string, azimuth: int, scale: int, keyPlace: true, unchanged: bool, sameAs: string|null}
```

`days` holds one row per slot, oldest first, `hour` null for a plain date slot. `hours` are the slot
hours of a day and `currentSlot` the slot a snapshot taken now belongs to. Days that only have events,
such as today before its first snapshot, are one row with `slot` set to the date and `snapshot: false`.
A pruned slot stays listed with `pruned: true` so a slider can show a gap. `nextRunAt` is null while the
timeline is off. `partial` means some tiles were being redrawn during the snapshot and kept their image
from the slot before. `tiles` counts every tile the slot shows, and `carried` how many of those were
missing from the tile folder while the snapshot ran and keep the image of an earlier slot. `changed`
counts the tiles whose image differs from the slot before. A tile carried from the slot before has its
image and is not counted. One carried from an earlier snapshot of the same slot, taken again, is counted
when that image differs from the slot before. For a
slot taken by an older release both are worked out from the earlier slots the same way the tile route does.
`window` is the hours of the day whose events belong to the slot, from its own hour (midnight for the
first slot of a day) to the hour of the next slot that day (24 for the last), and `events` counts the
stored lines in it. A plain date slot on a day that also has slots with an hour comes first, so it
covers midnight up to the first of them. Alone on its day it covers the whole day. Every event of a day
therefore belongs to exactly one row.

`isoViews` lists the 3D views of the slot's day, empty when there are none or the slot was pruned. They
are drawn once a day with the first slot kept that day, and every slot of the day lists them. `file` is the relative
address of the image, `api/timeline/{world}/{slot}/iso/{label}.png`, with the slot that holds it. Labels
are lower case letters, digits, dashes and underscores. Views of server markers are labelled
`marker-<id>`.

`skipUnchanged` is `timeline.skipUnchanged` (added in 1.5). While it is true, a scheduled snapshot
with no block change and no event since the snapshot before is dropped, so a slot hour from `hours`
may be missing from `days`. A block change is a zoom 0 tile whose image differs from the slot before
or that the slot before lacked, see the README. A dropped slot leaves nothing on disk and no `snapshot`
event. The `window` of the slot held before it that day then reaches up to the next slot held, so it
covers the hours of the dropped one with the map they had, and the next slot's window starts at its own
hour. When the dropped slot was the first of its day, the next slot held that day starts at midnight
instead, so those early hours show its later map. A day with every slot dropped and no events has no
row, and no `isoViews` either. Stats do not depend on the windows, see below.

Up to 1.1 the list held one row per day. It still does with `timeline.perDay` at 1, apart from days
kept before the change of a setting.

Key places have four `IsoKey` entries each, one per direction, with `keyPlace: true`, the `name` to show (the
label when the place has none), `azimuth` 0, 90, 180 or 270 and `scale`, and the file
`api/timeline/{world}/{slot}/iso/{label}-{azimuth}.png`. `unchanged: true` means the place did not
change since the render it reuses, and `sameAs` names the slot that render was drawn in, null otherwise. While that slot
is held, `file` points into it, so a browser loads the picture once.

### GET /api/timeline/{world}/{slot}/tiles/{zoom}/{x}/{z}.png

The tile as it was in that slot, with the same coordinates as `/api/tiles`. PNG with a strong `ETag`
and 304. Slots of past days are cached for a year as immutable, today's for five minutes. Snapshots hold
every tile of every zoom, since a tile missing from the live tile folder at that moment keeps the image
it had in the slot before. A slot taken by an older release that lacks a tile answers with the image of the
newest earlier slot that holds it, looking back to the newest complete snapshot or else the oldest slot
held, so the map has no holes. The answer is the same whatever was asked before. The `ETag`
is that image's. A tile no slot up to then held, or a slot without a snapshot, answers 404 without a
body. Counts against the tile rate limit.

### GET /api/timeline/{world}/{slot}/changed

404 when the slot has no snapshot.

```
{world: string, date: string, slot: string, previous: string|null, first: bool, tiles: [[zoom, x, z]]}
```

The tiles whose image differs from the previous snapshot slot of the world, at every zoom, both slots
taken with every tile they show as the tile route serves them. A tile carried over from the slot before
is not listed. `previous` is the slot compared with. For a slot taken by an older release whose previous
slot was pruned, that is the newest earlier slot still held. In the oldest slot held, `first` is true,
`previous` is null and every tile is listed.

### GET /api/timeline/{world}/{slot}/stats

404 when the slot has no stats.

```
{world: string, date: string, slot: string, takenAt: ms, windowSeconds: int, exploredChunks: int, tiles: int, changed: int,
 playersSeen: int, peakOnline: int, onlineNow: int, kills: int, deaths: int, blocksBroken: int,
 blocksPlaced: int, worldEvents: int, events: int, feats: FeatsSummary|null}

FeatsSummary {updatedAt: ms|null, totalFeats: int, totalPoints: int, players: int, rarityCount: int,
              milestones: int, firsts: int,
              top: [{rank: int, uuid: string|null, name: string, points: int, unlocked: int}]}
```

`windowSeconds` is the span before `takenAt` the stats cover, 86400 for the 24 hours before it. When
the snapshot kept before it is more than 24 hours older, as after dropped snapshots or a server that
was down, the span reaches back to that snapshot, up to 604800, seven days, which is what the heat
data keeps. Snapshots taken by releases before 1.5 always hold 86400. The span does not follow the
slot windows, so the stats of slots of one day overlap, and after dropped snapshots no hour falls
outside every stats file. `events` counts the stored events of the whole day. `kills` counts deaths with a player killer
and `deaths` deaths of players, both in this world. `blocksBroken` and `blocksPlaced` come from the
heat data. `feats` is null without a Feats export or when `leaderboard` is hidden from the viewer.
`top` holds at most ten rows in Feats rank order.

### GET /api/timeline/{world}/{slot}/events

```
{world: string, date: string, slot: string, window: [int, int], dropped: bool, items: [TimelineEvent]}

TimelineEvent {at: ms, kind: string, world: string, x: int|null, z: int|null, who: string|null, text: string}
```

Oldest first. `kind` is one of `join`, `leave`, `death`, `kill`, `worldevent.start`,
`worldevent.end`, `feats.unlock`, `feats.milestone`, `feats.first`, `dominion`, `marker.add`,
`marker.remove`, `snapshot` and `dropped`, or another lowercase dotted kind, which clients show as a
plain line. Events are kept per day, and a slot answers the events of its `window`, the same hours
as in the day list, so two slots of a day never list the same event. A plain date that has a slot
folder of its own answers that slot, the hours before the first slot with an hour that day or the whole
day when it is the only one. A plain date without a folder of its own stands for the last slot of the
day. A day with events but no snapshot answers the whole day. A day holds at most 5,000 events, then
one `dropped` event with `dropped: true` and nothing after it.

`x` and `z` are the block where it happened. `join` and `leave` carry where the player stood, `death`
and `kill` where the victim died, and `feats.*` where the player stood when they were online at the
unlock. They are null for events recorded by older releases, for feats of offline players, when the
position could not be read, and when the live map would not have shown the player there. That is below
`hideBelowY`, crouching or invisible under a hide rule, or in their own territory with
`hidePlayersInOwnTerritory` on. Those places are never stored, so admins get null too.

### GET /api/timeline/{world}/events

Events of a time range across days. Takes `from`, `to`, `kind` and `limit`.

```
{world: string, from: ms, to: ms, truncated: bool, items: [TimelineEvent]}
```

`to` defaults to now and `from` to 24 hours before `to`. The range is clamped to 31 days and the
clamped `from` is returned. `kind` is a comma list, each entry matching that kind or the kinds below
it, so `worldevent` matches `worldevent.start`. `limit` is 1 to 5000, 1000 by default. Oldest first,
and when more match, the newest `limit` are kept with `truncated: true`. Values that are not whole
numbers, or `from` after `to`, answer 400.

### GET /api/timeline/{world}/{slot}/iso/{label}.png

A daily 3D view, `{label}-{azimuth}.png` for a key place. A slot without views of its own answers with
the views of its day. PNG with a strong `ETag` and 304. Past days are cached for a year as immutable,
today for five minutes. 404 for an unknown label or slot, 403 when `terrain` or `iso` is hidden, 400 for
a malformed slot, and the timeline 404 rules. Counts against the API rate limit.

### GET /api/timeline/{world}/export

Admins only. Takes `from` and `to`, both dates, inclusive, at most 90 days apart. Streams a zip as it
is built, with `Content-Type: application/zip` and `Content-Disposition: attachment`. The zip holds
`README.txt`, then per day `days/{date}/events.jsonl` and per slot of that day `days/{slot}/tiles.json`,
`stats.json` and `iso/` (its views and `index.json`) when present, then every tile image those slots
name, once, as `blobs/{hash}.png`. Each `tiles.json` names every tile the slot shows, those taken from
earlier slots included, so a slot whose earlier slots are outside the range is still whole.

- 403 for non admins.
- 400 for missing or malformed dates, `from` after `to`, or a longer range.
- 409 with `Retry-After` while another export runs. One runs at a time per server.
- 429 past `tileRequestsPerMinute` per address, next to the API limit.
- The timeline 404 rules.

Events and stats are copied as stored, so player names are not hidden. That is why the route is admin
only.

### Timeline visibility per event

Items are filtered when served. `kill` and `death` follow `kills`, `worldevent.*` follows
`worldevents`, `feats.*` follows `leaderboard`, `marker.*` follows `markers`, `snapshot` and
`dropped` need only `timeline`, and everything else follows `feed`. For non admins a hidden player
other than the viewer is `"A player"` in `who` and in `text`, and every event of a hidden player
loses `x` and `z`, whatever its kind. So does an event whose text names another hidden player, such as
a kill by a hidden player, and an event of a player in another faction while the layer of its kind is
limited to the viewer's faction. `join`, `leave`, `kill`, `death` and `feats.*` are snapped to the
chunk corner for non admins other than the player.

## 3D views

Isometric pictures of an area, one column per block with side faces, trees, water and plants. Views
follow `terrain` and `iso`, and the viewer needs both, otherwise 403. `viewer.layers.iso` tells the
client. A view covers a square of `2r + 1` by `2r + 1` chunks around the chunk that holds `x` and
`z`. Only explored chunks inside the world's `renderLimit` are drawn, anything else in the square is a
flat grey floor. The server never generates terrain for a view.

### GET /api/iso

Always 200. What the server allows, so the client can hide the action.

```
{ok: true, enabled: bool, visible: bool, maxRadius: int, defaultRadius: int, defaultScale: int,
 azimuths: [int], maxScale: {"<radius>": int}}
```

`enabled` is `isoEnabled`. `visible` is true when the viewer may see both `terrain` and `iso`.
`maxScale` gives the largest scale each radius allows, 3 up to radius 5, 2 up to radius 8 and 1 above.

### GET /api/iso/{world}

Takes these parameters.

- `x` and `z`, a block position, required whole numbers.
- `r`, the radius in chunks, 1 to `maxRadius` (at most 16). 4 by default, or `maxRadius` when lower.
- `az`, 0, 90, 180 or 270, 0 by default. 0 looks from the south east, with east running down to the
  right, 90 from the south west, 180 from the north west and 270 from the north east.
- `s`, the pixel scale, 1 to 3, 2 by default. A scale above `maxScale` of the radius is lowered
  silently.

The answers.

- `200` with a PNG on a transparent background, a strong `ETag` (304 on `If-None-Match`),
  `Cache-Control: public, max-age=300` and `X-Atlas-Iso` set to `world,x,z,r,az,s` as drawn, where x
  and z are the centre of the centre chunk and r and s are after clamping. A view drawn while chunks
  were missing carries `X-Atlas-Partial: 1` and `Cache-Control: no-store`.
- `202` while the view is queued or drawing, with `Retry-After` set to `etaSeconds`. Ask again with
  the same query after that time. Asking about a queued view is free.
  ```
  {ok: true, queued: true, etaSeconds: int,
   view: {world: string, x: int, z: int, radius: int, azimuth: int, scale: int}}
  ```
- `429` with `Retry-After` when this address started more than `isoRequestsPerMinute` new renders in
  the last minute (20 by default), or when the render queue is full. Cached views do not count. One
  address holds at most `isoViewsPerAddress` views in the queue (4 by default) and at most
  `isoLargeViewsPerAddress` large ones waiting (2 by default, radius 9 and up). A new view from an
  address at its allowance replaces the oldest of its views that has not started rather than answering
  429. The queue holds 8 browser views and admits 2 large ones at a time.
- `503` with `Retry-After` for a view whose render just failed. It is tried again after 30 seconds.
- `400` for bad parameters, `403` when `terrain` or `iso` is hidden, and `404` when `isoEnabled` is
  false, for an unknown, disabled or hidden world, and for a square with no explored chunk inside the
  render limit.

Every request also counts against the API rate limit. Views are cached and drawn again at least
every six hours, sooner when a chunk inside them is saved.

### GET /api/iso/{world}/marker/{markerId}

Takes `layer`, `r`, `az` and `s`. The same answers for the view centred on a marker the viewer may
see. The id is looked up in `/api/markers/{world}` first, which holds the spawn, engine and shared
markers and the viewer's own personal markers, homes and deaths, then in the marker layers of
`/api/layers/{world}`. `layer`, a layer id such as `atlas:admin`, limits the lookup to that layer.
Without `r`, a polygon, polyline or circle gets a radius that covers it and a point gets radius 4. 404
when no visible marker has that id.

## Key places, admins only

The curated places drawn from all four directions with the first snapshot kept each day, kept in
`iso.keyPlaces` of `atlas.json`. Admins only, by the admin token or a linked session with the admin
permission. `as` is ignored here. Everyone else gets 403. A `POST` or `DELETE` sent with a session
cookie must come from the map itself, so one whose `Origin` names another host gets 403. Every answer
holds the whole list.

```
{ok: true, limit: int, maxRadius: int, defaultRadius: int, keyPlaces: [KeyPlace]}

after a change also  changed: "added"|"moved"|"removed"
after a POST also    keyPlace: KeyPlace

KeyPlace {label: string, name: string, world: string, x: int, z: int, radius: int}
```

`limit` is `isoKeyPlaceLimit`, `maxRadius` the largest radius a place may have, the smaller of 16 and
`isoMaxRadius`, and `name` is empty when the place has none.

### GET /api/admin/keyplaces

The list.

### POST /api/admin/keyplaces

Takes JSON, `{world, x, z, label, radius?, name?}`, at most 4 KB. Adds the place, or moves the one
with the same label where it stands, writes the list into `atlas.json` and applies it at once.
`radius` is 1 to 16 and 6 by default, `label` 1 to 40 lower case letters, digits, dashes and
underscores, `name` at most 60 characters. 415 for another content type, 400 for a bad value or a world
that is not loaded and enabled, 409 when `isoKeyPlaceLimit` places are already there or when
`atlas.json` does not parse or would not be valid with the change, which then stays as it was.

### DELETE /api/admin/keyplaces/{label}

Removes the place. Its pictures stay in the timeline. 404 when there is no place with that label.

## Event stream, GET /api/events

Takes `world` and `as`. Server sent events, each frame with an `id:`, an `event:` and one `data:`
JSON line. The stream sends a `retry:` of 3 to 5 seconds, different per client.

On connect, in this order, come `hello`, `players` with `full: true`, then `feedlog`, `chatlog` and
`killlog` for the layers the viewer may see.

```
hello        {apiVersion: 1, viewer: Viewer, server: Server}
players      {at: ms, hidden: bool, full: bool, players: [Player], left: [string]}
feedlog      {items: [FeedLine]}
chatlog      {items: [ChatLine]}
killlog      {items: [Kill]}
feed         FeedLine
chat         ChatLine
kill         Kill
markers      {world: string, own: bool}
layers       {world: string, layers: [string]}
worldevent   {world: string, id: string, name: string, displayName: string, state: "start"|"end",
              x: int|null, z: int|null, at: ms}
tiles        {world: string, at: ms, tiles: [[zoom, x, z]]}
snapshot     {sections: [string], at: ms|null, present: bool}
leaderboard  {updatedAt: ms|null}
chronicle    {available: bool, updatedAt: ms|null}
worlds       {updatedAt: ms}
timeline     {world: string, date: string, slot: string}
```

- `players` rows follow the Player shape with `trail` null. `full: false` frames list only players
  that changed plus `left`, the uuids that left the viewer's view. `left` is `[]` on full frames.
  `hidden: true` means `players` is hidden for this viewer and the list is empty.
- `markers` says the markers of a world changed, such as shared markers, warps, block markers or
  portal markers. `own: true` means only this viewer's personal markers changed. Refetch
  `/api/markers/{world}`.
- `layers` names the layer ids that changed in a world. Each viewer gets only ids of layers they may
  see now or could see before the change. Refetch `/api/layers/{world}` and replace the named layers.
  A named layer missing from the answer was removed or is no longer visible.
- `snapshot.sections` names the factions snapshot sections that changed, from territories, points,
  factions, nations, events, feed and chat. Refetch the matching routes.
- `leaderboard`, `chronicle`, `worlds` and `timeline` say to refetch `/api/leaderboard`,
  `/api/chronicle`, `/api/worlds` and `/api/timeline/{world}`. `chronicle` goes to viewers who may see
  `stats` when the export appears, changes or goes away, and after a reload that changed
  `chronicleUrl`. `worlds` is also sent when `/atlas reload` changes a world setting.
- `hello` is sent again without a reconnect when the viewer changes, for example after a revoke, a
  new permission or a new faction. Streams check this every 30 seconds.

**Control frames.** `hello`, `markers`, `tiles`, `worlds`, `leaderboard`, `chronicle`, `snapshot`,
`layers` and `timeline` are never dropped from a slow client's queue. When the queue is full the oldest data frame
goes instead. A client that takes no data for 15 seconds is closed.

**Resume.** A browser that reconnects with `Last-Event-ID` within a minute, as the same viewer and
for the same world, gets the frames it missed from the last 200, without a new `hello`. The frames are
replayed as they were built during that minute. Any other reconnect starts over with `hello`.

**Limits.** Each address may hold `sseClientsPerIp` streams (8 by default) and more get 429 with
`Retry-After: 30`. Past 500 streams in total the answer is 503.

## Session API for plugins

`dev.ryvrook.atlas.api.SessionApi` lets a plugin that serves a web page of its own, such as Chronicle,
share one login with the map. It is a Java API in the Atlas jar, versioned on its own and apart from
`apiVersion` and `AtlasApi`. `SessionApi.VERSION` and `version()` are `1` in Atlas 1.1.0.

**Stability.** Within version 1 methods, record components and event kinds are only ever added. None
is removed, renamed or given another meaning. A plugin that binds by reflection checks `version()`
once, looks up only the methods it knows and resolves them again when the Atlas plugin object changes.

**Threads.** Every method may be called from any thread and runs on the caller's thread. None blocks on
IO or touches a world thread. Listeners run on an `Atlas-scheduler` thread, one at a time and in the
order of the changes, never on the caller's thread. A listener that throws is logged and the others
still run.

**Tokens and hashes.** A session token is the cookie value. Atlas keeps only its SHA-256 hash, as 64
lower case hex characters, and every method names a session by that hash. Sessions last 30 days after
they were last seen. Sessions changed through the API are written with the rest of `sessions.json`,
every five minutes and when Atlas stops.

| Member | Descriptor | Thread | What it does |
| --- | --- | --- | --- |
| `static int VERSION` | `I` | Any | The version of the class, `1` |
| `static SessionApi get()` | `()Ldev/ryvrook/atlas/api/SessionApi;` | Any | The running API, null before Atlas starts and after it stops |
| `int version()` | `()I` | Any | The version the running Atlas implements |
| `String issueCode(UUID uuid, String name)` | `(Ljava/util/UUID;Ljava/lang/String;)Ljava/lang/String;` | Any | A six character code, valid five minutes and single use. A null uuid throws `IllegalArgumentException` |
| `Linked redeem(String code)` | `(Ljava/lang/String;)Ldev/ryvrook/atlas/api/SessionApi$Linked;` | Any | The token and session for a code, or null. Fires `REDEEMED` |
| `Session resolve(String tokenHash)` | `(Ljava/lang/String;)Ldev/ryvrook/atlas/api/SessionApi$Session;` | Any | The session of a hash, or null. Refreshes its last seen time at most hourly |
| `boolean revoke(String tokenHash)` | `(Ljava/lang/String;)Z` | Any | Ends a session. True when it existed. Fires `REVOKED` only then |
| `void adopt(Session session, String tokenHash)` | `(Ldev/ryvrook/atlas/api/SessionApi$Session;Ljava/lang/String;)V` | Any | Takes in a session the other plugin redeemed. Fires `ADOPTED` only for a new hash |
| `Set<UUID> hidden()` | `()Ljava/util/Set;` | Any | An unmodifiable copy of every player whose name Atlas hides |
| `Runnable onChange(Consumer<SessionEvent> listener)` | `(Ljava/util/function/Consumer;)Ljava/lang/Runnable;` | Any, listener on `Atlas-scheduler` | Registers a listener and returns the handle that removes it |

```
record Session(UUID uuid, String name, long createdAt, long lastSeen)
record Linked(String token, Session session)
record SessionEvent(SessionEvent.Kind kind, String tokenHash, UUID uuid)
enum SessionEvent.Kind { REDEEMED, ADOPTED, REVOKED }
```

- `REDEEMED` fires when a code is redeemed on the map or through `redeem`, `ADOPTED` when `adopt` added
  a session and `REVOKED` when a browser signs out of the map, `/atlas unlink` runs or `revoke` ended a
  session. A session that expires fires nothing.
- `adopt` ignores a hash that is not 64 lower case hex characters, a null session or player and a
  session last seen more than 30 days ago. A session Atlas already holds keeps the later last seen
  time. Since only a new hash fires `ADOPTED`, two stores that copy each other's changes never loop.
- `hidden()` holds the `/atlas hide` list, players hidden through `AtlasApi.setHidden` and players the
  factions snapshot file marks hidden. The crouching and invisible rules hide only a position and are
  left out.
- `redeem` does not rate limit. The caller limits attempts per address as Atlas does, 10 a minute.
- Listeners are dropped when Atlas stops. Register again once `get()` returns an API after a start.

```java
SessionApi api = SessionApi.get();
if (api != null && api.version() >= 1) {
    Runnable stop = api.onChange(e -> {
        if (e.kind() == SessionApi.SessionEvent.Kind.REVOKED) {
            myStore.revokeHash(e.tokenHash());
        }
    });
}
```

## Removed in version 1

- `GET /api/bosses` and the `boss` event. The route answers 404.
- Heat in `/api/changes`. It has its own route, `/api/heat/{world}`.
- `hidden` on a world, now `hiddenWorld`.
- `uptimeMs` in `server` and `/api/stats`, now `uptimeSeconds`.
- `lastSeen` on offline rows, now `at`.
- Bare arrays in `feedlog`, `chatlog` and `killlog`, now `{items: [...]}`.
- `{"hidden": true}` bodies for hidden layers, now 403 with an error body.
