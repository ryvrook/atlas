# Changelog

Every release keeps the values an existing `atlas.json` already holds. Keys a release adds are
filled in with their defaults and written back on the next start. Changed defaults only apply to
new files.

## Unreleased

- Session tokens are stored as SHA-256 hashes. An existing `sessions.json` is converted on the first
  start, and linked browsers stay linked.
- An `adminToken` shorter than 24 characters is replaced with a new random token, with a warning in
  the log. Wrong admin tokens count against the same per address limit as link codes.
- `/api/health` answers only `ok` and `version` unless the request carries admin access.
- Portal and instance worlds hidden by `showPortalWorlds` now return 404 on tiles, markers and
  other world endpoints for non admins, not only in the world picker.
- The page and its files carry a Content Security Policy, `Referrer-Policy` and
  `X-Content-Type-Options`.
- Players in the factions snapshot may have null or missing coordinates. They are listed without a
  position instead of being dropped.
- At most 64 requests are handled at once, event streams aside, and a client gets 30 seconds to send
  a request.

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
