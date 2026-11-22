# Implementation plan: own.audio Music for Android

**own.audio Music** for Android: a native, open-source (MPL-2.0) music player for **own.audio,
Subsonic/OpenSubsonic servers (Navidrome, Gonic, Airsonic, LMS…) and Jellyfin**, side by side.
Kotlin, Jetpack Compose, Media3, Android 9+ (API 28). applicationId `audio.own.musicplayer`,
home-screen name **own.music**.

It is a port of [own.audio Music for iOS](https://github.com/own-audio/music-ios) (1.0.2): the same
functions, screens, words and look. own.audio servers are spoken to on **`/api/v2` only**.

Read [`docs/android-foundation.md`](docs/android-foundation.md) first. It holds everything the three
Android apps share (build, look, own.audio client, servers, player core, downloads, sync, sign-in,
settings pages, system integrations, tests, release). This plan builds the foundation first (this is
the first of the three apps, so the foundation is written here) and the music app on top of it.

## Progress

| Phase | What | State |
|---|---|---|
| F0 | Repository, build, CI, empty tabs | ☑ 2026-11-21 |
| F1 | Look: tokens, theme, brand, shared composables | ☑ 2026-11-21 |
| F2 | own.audio client `:kit:ownaudio` | ☑ 2026-11-21 |
| F3 | Servers, addresses, network, answer cache | ☑ 2026-11-21 (detection per kind comes with F7) |
| M1 | Music library layer and catalogue database | ◐ built 2026-11-21 (100k tracks: insert 2.3 s, album page 3 ms on the JVM); 600k on a device still to measure |
| M2 | own.audio as a music source | ◐ built 2026-11-21; the demo-server check comes with the screens (M4) |
| F4 + M3 | Player core and the music engine | ◐ engine and service wired 2026-11-21 (release APK 7.25 MB); open: an audio measurement of gapless, concert chapters, the plays outbox (M6) |
| M4 | Browsing screens, mini player, full player | ◐ built 2026-11-21 and played on the demo (notification, background, last queue after relaunch, every player sheet); open: guide pages, the scrubber's look, tablet sidebar and output switcher not yet seen on screen |
| F7 | Welcome, sign-in, server page, family, devices, listening, logs | ◐ built 2026-11-21 for own.audio and tried on the demo; Google waits for the OAuth clients, two-factor and account deletion tested against a fake server only |
| M5 | Playlists and smart playlists | ◐ screens built 2026-11-22 (Playlists, playlist page and edits, Add to Playlist, smart playlists and the rule editor); the full life cycle on a writable own.audio account is still to try; reorder only on own.audio |
| F5 + F6 + M6 | Downloads, offline, sync and outboxes | ◐ built 2026-11-21: an album downloaded, survived a kill, played with no network. Open: a writable account to confirm an offline favourite is kept; per-item results from `/sync/push` so a refused change is undone on screen; the streamed copy swapped for the download at the next pause |
| M7 | Subsonic / OpenSubsonic | ◐ app wired 2026-11-22: Navidrome signed in, 1557 songs read, songs played, play reports; the end-to-end suite is still to write |
| M8 | Jellyfin | ◐ app wired 2026-11-22: signed in, played, Instant Mix, library picker, Quick Connect code shown; the end-to-end suite is still to write |
| M9 | Settings | ☐ |
| M10 | own.audio extras: sharing, covers, concerts | ◐ screens built 2026-11-22; to try on a writable own.audio account with own songs and a concert |
| M11 | Identify (MusicBrainz) | ◐ screens built 2026-11-22; a run from start to accept to undo still to try on a real own.audio server |
| F8 + M12 | Android Auto, widget, deep links, voice, background refresh | ◐ built 2026-11-22: browse tree, search, voice, links and the widget (both sizes) checked on the emulator; Auto on a head unit still to see |
| F9 + M13 | Localization, accessibility, tablets, misuse tests, scale | ◐ built 2026-11-22. Scale on the emulator, 600k tracks: browsing pages 2–330 ms, artists 0.3 s (was 97 s), memory ~140 MB; still open: the first-sync insert (445 s, needs to be much faster), first-snapshot progress on the server page, full-text search for offline song search (~1 s) |
| F10 + M14 | Release: Play, F-Droid, guide | ☐ |

**Where we stopped:** F9/M13 built 2026-11-22. Next: a faster first-sync insert and its progress line, full-text song search, the release phase (F10/M14), the duplicate-server refusal from book/podcast-android, the end-to-end suite.

## Rules for whoever executes this plan

1. One phase at a time, in the order above. Tick it here only when its "Done when" holds.
2. **Read the iOS files named in the phase before writing Kotlin.** The Swift code is the
   specification for behaviour, words and layout. If you change behaviour on purpose, write why in
   this plan under the phase.
3. Every change gets a `CHANGELOG.md` line under `[Unreleased]`. Every change users can see updates
   `guide/` in the same commit (from M4 on).
4. Port the Swift tests together with the code. Keep their names (`PlayQueueTests.swift` →
   `PlayQueueTest.kt`).
5. No `/api/v1` calls, ever. If v2 seems to lack something, check `openapi-v2.json` in the server
   repository first; if it is really missing, add it to "Server work" below and hide the feature.
6. No secrets, internal hosts or test accounts in the repo. Test credentials live in the gitignored
   `test-servers.local.env`.
7. The gate for each phase: `./gradlew testDebugUnitTest lintDebug verifyRoborazziDebug`, the
   drift check (once the siblings exist), CHANGELOG, guide.

---

## Scope

Everything in music-ios 1.0.2, as listed in its `docs/ios-apps-feature-parity.md` (Music column),
except where noted:

| Kept the same | Different on Android, on purpose |
|---|---|
| Five tabs: Home, Library, Playlists, Downloads, Search | Settings is a full-screen page from the gear (iOS: a sheet) |
| own.audio, Subsonic/OpenSubsonic, Jellyfin (+ Quick Connect) | own.audio on v2 only: no Subsonic path into own.audio, no "Talks Through: own.audio · Subsonic API" row |
| Gapless, crossfade 1–12 s, ReplayGain track/album, 6-band EQ with presets, lyrics (synced), format badge | Ogg/Opus/Vorbis play directly (ExoPlayer), so they are in `capabilities.direct` and not transcoded |
| Smart playlists, Ready-Made presets, the rule editor | No Apple sign-in in 1.0 (foundation §10.4) |
| Identify (MusicBrainz), concerts (song marks in one file), family sharing per song/album/playlist, own covers | AirPlay → the system output switcher; CarPlay → Android Auto; Siri → Assistant media search |
| Mobile data Original / Saver / Downloaded Only, Saver 320/192/128 kbps | iOS Low Data Mode → Android Data Saver (caps streams at 128 kbps) |
| Offline mode, Whole Library When Offline, offline change queue | — |
| Family, Devices, Listening, Logs, Accent Color, Report Plays | Language row in Settings (System / English / Čeština) |
| Widget "Recently Played", deep links `ownmusic://` | Glance widget |

Not in scope (not in iOS either): podcasts and audiobooks inside the music app, Instant Mix for
own.audio (the server can't compute it), Ampache (it refuses Subsonic token auth), Cast.

---

## F0 — Repository, build, CI, empty tabs

Foundation §2. Music specifics:

- `gradle.properties`: `app.id=audio.own.musicplayer`, `app.name=own.music`.
- Tabs (placeholders): **Home** (`Home`), **Library** (`LibraryMusic`), **Playlists** (`QueueMusic`),
  **Downloads** (`DownloadForOffline`), **Search** (`Search`).
- Modules: the foundation ones plus `:kit:subsonic`, `:kit:jellyfin`, `:library`, `:player:music`
  (empty for now).
- README in the style of music-ios's (what it is, which servers, screenshots later, build, licence),
  CLAUDE.md, CHANGELOG, `docs/dependencies.md`, `test-servers.local.env.example` listing the
  variables (no values).

**Done when:** foundation F0's list holds.

## F1 — Look

Foundation §3–4. Music specifics: accent `#D63A6A` / `#FF6482`; radii cover 8, mini player 14, library
cards 14, mini cover 6, row cover 4; cover crossfade 300 ms; the wordmark's music words; the icon
from music-ios `Design/AppIcon.svg` (family ring + red note `#FF3B30` / `#FF453A` dark); hero images
from music-ios `App/OwnAudioMusic/Assets.xcassets/OwnAudioHero.imageset`; server logos own.audio,
Jellyfin, OpenSubsonic.

Read: `App/OwnAudioMusic/Core/Theme.swift`, `Brand.swift`, `AccentChoice.swift`,
`Library/LibraryComponents.swift`, `Player/MiniPlayerBar.swift`, `Design/*.svg`.

**Done when:** foundation F1's catalogue screen and screenshots pass.

## F2 — own.audio client

Foundation §5, with the music parts of OwnAudioKit: catalogue (`/artists`, `/contributors/{id}`,
`/albums`, `/albums/{id}`, `/tracks`, `/tracks/{id}`, `/genres`), lists (`/playlists…`, `/search`,
`/tracks/random`, `/me/played`, `/tracks/{id}/lyrics`), smart playlists (`/smart-playlists…`),
ratings and feedback (`/me/ratings…`), identify (§M11), chapters (`PUT /tracks/{id}/chapters`),
visibility (`PUT /tracks/{id}/visibility`; v2 has no album visibility — "Share My Songs" sets each own song), images (`PUT /tracks/{id}/image`, `/playlists/{id}/image`,
`/contributors/{id}/image`).

Read: music-ios `Packages/OwnAudioKit/Sources/OwnAudioKit/*.swift` (all of it) and its tests; book-ios
`Models.swift` for the sorted-key encoder.

**Done when:** foundation F2's tests pass; a live test signs in to the demo server and pages through
its albums.

## F3 — Servers, addresses, network

Foundation §6. Music specifics:

- `ServerKind.OWN_AUDIO, SUBSONIC, JELLYFIN`. `ServerAuth.OwnAudio(userName, tokens?)`,
  `Subsonic(userName, password)`, `Jellyfin(session)`.
- **Detection order** (`AppModel.addServer` in music-ios), per candidate (https first):
  1. hosted web address mapping (`own.audio`, `www.`, `app.` → `https://api.own.audio`);
  2. **own.audio**: `GET /.well-known/own-audio` (must have `api` v2 revision ≥ 16) → v2 password
     sign-in. (iOS checks Jellyfin first and own.audio through v1; Android asks the cheap, unauthenticated
     own.audio discovery first because a Jellyfin server answers 404 there quickly.)
  3. **Jellyfin**: `GET /System/Info/Public` (as typed, then `http://<host>:8096`) →
     `Users/AuthenticateByName`;
  4. **Subsonic**: `ping.view` with token+salt; `.ok` → Subsonic.
  Errors: "Wrong user name or password", "No music server answers at this address", "This own.audio
  server is too old for this app…".
- own.audio servers saved with `http://` move to `https://` at launch if the same id answers there.

Read: music-ios `Packages/MusicLibrary/Sources/MusicLibrary/Addresses.swift`, `ServerStore.swift`,
`ServerSetup.swift`, `App/OwnAudioMusic/Core/NetworkMonitor.swift`, `ClientRegistry.swift`,
`TrackID.swift`; tests `AddressTests`, `ServerStoreTokenTests`, `OwnAudioSignInTests`.

**Done when:** foundation F3's tests pass, plus detection tests for the three kinds.

## M1 — Music library layer and catalogue database

The Kotlin `MusicLibrary`: one set of models and one interface over the three server kinds.

- Models (`library/model`): `Artist`, `Album`, `Song` (+ `Gain`, `chapters`), `SongChapter`,
  `Sharing`, `Playlist`, `PlaylistEntry`, `Genre`, `Lyrics` (lines with `startMs`, `synced`,
  versions), `SearchResult`, `FavoritesResult`, `MusicLibraryInfo`. All ids composite (`TrackId` =
  `"<serverUUID>|<id>"`).
- `MusicSource` interface (port `MusicSource.swift`): artists, artist, albums(sort, offset/cursor),
  album, songs, genres, genre albums, random songs, search, favourites (get/set), rating (get/set),
  playlists CRUD, lyrics, stream link (`playbackLink(songId, maxBitrateKbps?)`), cover link, report
  play, `capabilities`, `albumSorts`.
- `Capabilities`: `RATINGS` (Subsonic, own.audio), `INSTANT_MIX` (Jellyfin), `LOWER_BITRATE` (all),
  `LIBRARY_CHOICE` (Jellyfin), `COUNTS_PLAYS_FROM_REPORTS` (Jellyfin). own.audio-only features are
  separate interfaces the own.audio source implements (`ChangeFeedCatalogue`, `BatchedOfflineEdits`,
  `LiveLibraryUpdates`, `HouseholdSource`, `ArtworkSource`, `SmartPlaylistSource`,
  `ConcertSource`, `IdentifySource`); screens check `source is X` and that the server is signed in.
- `AlbumSort`: `NAME, ARTIST, NEWEST, RECENTLY_PLAYED, MOST_PLAYED, RANDOM, FAVORITES, HIGHEST_RATED`
  (Jellyfin lacks `HIGHEST_RATED`).
- **Room catalogue** (`CatalogueDatabase`, one database, rows keyed by `serverId` + id): `artist`,
  `album`, `track`, `genre`, `playlist`, `playlist_entry`, `album_credit`, `track_credit`,
  `favourite`, `rating`, plus `feed_state(serverId, cursor, syncedAt)`. Indexes for album by artist,
  track by album (disc, track number), title search (`COLLATE NOCASE`; a FTS4 table for title/artist/
  album if `LIKE` is too slow at 600k tracks). Paging with `androidx.paging` for Songs and Albums.
  This replaces iOS's one JSON file per server: a 600k-song library does not fit in memory on a phone.
- Artist album counts and genre counts come from the catalogue (v2 gives none); hidden when unknown.

Read: music-ios `Packages/MusicLibrary/Sources/MusicLibrary/MusicSource.swift`, `Models.swift`,
`RecordMapper.swift`, `App/OwnAudioMusic/Core/Catalogue.swift`, `OfflineLibrary.swift`; tests
`MappingTests`.

**Done when:** the models, interface, Room schema (exported, with a migration test harness) and the
v2 record mapper exist with unit tests; 600k generated tracks insert in under a minute on an emulator
and an album list page loads in under 50 ms.

## M2 — own.audio as a music source

- `OwnAudioSource : MusicSource` over `:kit:ownaudio`, with the catalogue fed by
  `/sync/snapshot?kinds=contributor,album,genre,track,playlist,favourite,rating` then
  `/sync/changes` (foundation §9.1). Browsing reads Room; lists not in the feed (Recently Played,
  Most Played: `GET /me/played?kind=album&order=recent|most`; random: `GET /tracks/random`; search
  online: `GET /search?q&types=artists,albums,tracks&include`) go to the server, offline to Room.
- `RecordMapper`: v2 `Track{title, album_id, credits[], duration_ms, disc_number, track_number,
  genre_ids, image, content_type, owner_id, visibility, chapter_count}` → `Song`; albums are
  **releases** (two editions are two albums); artist line from credits with `join_phrase` and
  `credited_as`; `isOwner = owner_id == me.id`.
- `ListenerState`: favourites and ratings from the feed kinds `favourite.*` and `rating.*`.
- Ratings need the kind (track or album); unknown → try track, then album on 404.
- Playlist removal by **entry id** (a song can be in a playlist twice).
- Cover keys `"<serverUUID>|<recordId>~<imageObjectId>"`, links with `&w=` 64…1024.
- Plays: `POST /me/plays` (online) or `/sync/push` op `play` (offline batch), rule in M3.
- No "now playing" report for own.audio (v2 has none; the server learns from plays and progress).

Read: music-ios `Packages/MusicLibrary/Sources/MusicLibrary/OwnAudioSource.swift`,
`RecordMapper.swift`; `OwnAudioKit/Lists.swift`, `Endpoints.swift`; tests `OwnAudioFeedTests`,
`MappingTests`.

**Done when:** against the demo server, a debug screen lists artists → albums → songs from Room after
one snapshot; a change made on the web console appears after `/sync/changes`; unit tests for the
mapper and the feed.

## F4 + M3 — Player core and the music engine

Foundation §7 for the core. The music engine `:player:music`:

- **ExoPlayer owns the queue** (gapless, notification and Auto get it for free). `MusicPlayer` wraps
  it with the iOS `PlayQueue` semantics: shuffle (keeps the current song), repeat Off → All → One
  (Next overrides Repeat One), **Play Next** (moves the song if already queued), **Add to Queue**,
  move/remove upcoming, jump to a song, a Played list, "Track 1 of 5". Containers
  (`album:<id>`, `playlist:<id>`, `mix:<id>`, scoped by server) for the resume store. Previous more than
  3 s into a song restarts it. Next/previous grey out at the ends.
- **Gapless**: ExoPlayer playlists with `setPauseAtEndOfMediaItems(false)`; gapless metadata (LAME/iTunes
  `iTunSMPB`) is read by ExoPlayer for MP3/AAC; FLAC is gapless by nature. Prefetch the next song into
  the listening cache once the current one has 30 s left, so gapless holds on slow networks. When the
  next song changes (Play Next, shuffle, repeat, queue edits), replace the upcoming items without
  touching the current one (iOS 1.0.1 fixed flicker and false seek reports here).
- **Crossfade** (off by default, 1–12 s, default 4): two ExoPlayer instances ("decks") with volume
  ramps; the next song must be **fully cached** before a fade starts ("end of data so far" is not the
  end of the song); the next song's title and time appear as the fade starts; skip or seek mid-fade
  restores the volume; disabled while the sleep timer is set to End of Track; a fade due while in the
  background starts on time (schedule it on the player's looper, not a UI timer).
- **ReplayGain**: modes Off / Same for Every Song (track, default) / Same for Every Album; falls back to
  the other value; peak protection `min(gain, −20·log10(peak))`; clamp ±24 dB; applied as player
  volume per item (or an `AudioProcessor` gain stage when crossfade owns the volume). Sources:
  OpenSubsonic `replayGain`, Jellyfin `NormalizationGain`, and the file's tags (ExoPlayer exposes ID3
  `TXXX:REPLAYGAIN_*` and Vorbis comments; read them in a `MetadataOutput`). own.audio v2 records carry
  no gain fields: tags only until the server adds them (Server work 1).
- **EQ**: `DynamicsProcessing` (API 28+) with 6 bands at 60/150/400/1000/2400/15000 Hz, ±12 dB,
  preamp; presets Flat, Bass Boost, Treble Boost, Spoken Word, Loudness, High Speed 1, High Speed 2
  (values from `EqPreset.swift`); "Custom" once a slider moved; **Reset**; stored locally, and saved to
  `PATCH /me/settings` `eq[]` with `device_kind=android` for own.audio (one curve per device kind, the
  same 6 bands). Attach to the audio session id of each deck.
- **Streaming**: progressive `CacheDataSource` over the resolver (foundation §7.2–7.3);
  `DefaultLoadControl` with ~120 s max buffer, 60 s resume, 8 s rebuffer (the iOS back-pressure values);
  a seek during a stream must not be reported as a failure or skip to the next song.
- **Mobile data**: Wi-Fi = original; metered + Saver = `max_bitrate_kbps` 320/192/128 (default 192);
  Data Saver caps at 128 whatever the choice; metered + Downloaded Only = offline. Subsonic
  `maxBitRate`+`format=mp3`; Jellyfin non-static `universal` stream with `MaxStreamingBitrate`;
  own.audio `max_bitrate_kbps`. Reduced copies cached as `~reduced`, used only while a smaller stream is
  wanted.
- **Format badge**: "FLAC · 44.1 kHz · 16-bit" / "MP3 320" from `playback-info.source` (own.audio), the
  Subsonic/Jellyfin song fields, or ExoPlayer's `Format`; ReplayGain change appended ("+2.5 dB").
- **Plays** (port `ScrobbleTracker.swift`): counts after **half the song or 240 s** of wall-clock
  listening (seeks don't count), songs under 30 s never; `isEnd` for completed/skipped/replaced, not
  stopped/seeked. Jellyfin also gets session reports (start, progress every 15 s **only while playing**,
  stopped) and counts plays from them, so only offline plays are sent after. Subsonic `scrobble`.
  Pending plays (limit 500) with `client_id` `"<trackId>@<startedAt ms>"`. **Report Plays to the
  Server** switch.
- **Concerts** (track chapters): `setChapters(trackId, chapters)`, next/previous move between chapters
  inside the track (previous restarts after 3 s), past the last → next song; chapter title on the lock
  screen/Auto with the concert as album; resume from server progress `PUT /me/progress/track/{id}`
  every 15 s and on pause, unless within the last 30 s.
- **Offline**: the queue skips songs with no local file; three load failures in a row stop playback.
- **Last queue**: `filesDir/last-queue.json {tracks, currentTrackId, positionMs}` on song change, pause and
  background; shown paused in the mini player after a relaunch; cleared on Stop and at the queue's end.

Read: music-ios `Packages/PlayerEngine/Sources/MusicEngine/*` (`PlaybackEngine.swift`, `PlayQueue.swift`,
`Chapters.swift`, `EqBand.swift`, `EqPreset.swift`, `EqualizerStore.swift`, `PlaybackSettingsStore.swift`,
`Track.swift`), `Sources/PlayerCore/*`, `Packages/ServerSupport/Sources/ServerSupport/ScrobbleTracker.swift`,
`App/OwnAudioMusic/Core/AppModel.swift` (sections Scrobbling, LastQueue, applyStreamQuality); tests
`PlayQueue*Tests`, `ReplayGainTests`, `ChapterNavigationTests`, `SleepTimerTests`, `ScrobbleTrackerTests`,
`StorageRouteTests`, `AudioPayloadCheckTests`.

**Done when:** a debug screen plays an own.audio album gaplessly (measured on a continuous live album:
no gap audible, and no gap over 5 ms at the boundary in a recorded output), crossfades, shuffles,
repeats, uses EQ and ReplayGain from tags, survives a 30 s focus loss mid-song with sound, plays from the
cache after the network drops, and the ported tests pass.

## M4 — Browsing screens, mini player, full player

Every screen as in music-ios; read the Swift file named for each.

- **Root** (`RootView.swift`): loading → welcome (no servers) → tabs; the offline banner and mini player
  above the navigation bar on every tab; switching servers resets all tabs (keyed by the active
  server id) while the queue keeps playing; the shared "That Didn't Work" dialog.
- **Home** (`Library/HomeView.swift`): top bar with the **server menu** (`Dns` + server name, server
  logos; only with more than one server), **Offline Mode** toggle (`Wifi`/`WifiOff`), **Settings**
  (gear). **Shuffle All** (full-width button; 100 random songs; offline shuffles downloads). Shelves
  of 140 dp tiles with a title + chevron opening the full list: **Recently Played**, **Recently Added**
  (offline: **Downloaded Albums**), **Playlists** (cover, name, song count). Empty: "Nothing Downloaded",
  "No Music Yet". Pull to refresh.
- **Library** (`Library/LibraryView.swift`): grid of section cards (min height 88 dp, backgroundAlt,
  radius 14): **Artists**, **Albums**, **Songs**, **Genres**, **Favorites**, **Concerts** (only with
  `modules.track_chapters`), and under the grid **Identify Library** (own.audio with the module, M11).
  Top bar: **Downloads**. Tablets: a permanent sidebar (220–320 dp) with the same sections + Downloads.
- **Artists** (`ArtistsView`): list with picture + album count, a filter field "Filter Artists".
- **Albums** (`AlbumsView`): adaptive grid (min 150 dp, spacing 16) of album tiles; sort menu By Title,
  By Artist, Recently Added, Recently Played, Most Played, Random, Favorites, Top Rated (ratings
  servers only); paged.
- **Songs** (`SongsView`): paged list with **Play** / **Shuffle**.
- **Genres** (`GenresView`): "N albums · M songs"; **Genre**: its albums + **Shuffle** (100 random).
- **Favorites** (`FavoritesView`): segmented **Songs (n) / Albums (n) / Artists (n)**; Play/Shuffle.
- **Concerts** (`ConcertsView`): grid, newest first (`/albums?kind=live`); empty text "A whole concert in
  one file…".
- **Album** (`AlbumDetailView`): cover (radius 8), title, artist (opens the artist), year;
  **Play** / **Shuffle**; songs split by **Disc N**; footer "N songs, M min". Top bar: download button
  (`SetDownloadButton`), star (Favorite/Unfavorite Album), ⋯: Play Next, Add to Queue, Rate (ratings),
  Instant Mix (Jellyfin), Add Album to Playlist…, Choose Album Cover… (M10), Share My Songs with the
  Family / Stop Sharing My Songs (M10), Identify Album (M11). Tablets: everything in the ⋯ menu.
- **Artist** (`ArtistDetailView`): picture, name, Play/Shuffle all albums, album grid; star; own.audio ⋯:
  Choose Picture…, Identify Artist.
- **Song row** (`LibraryComponents.swift` `SongRow`, `Library/SongActions.swift`): track number or cover,
  title, artist, duration (tabular figures); trailing small icons for family-shared (`Group`),
  downloaded, favourite; the playing row shows a speaker instead of its number. Tap plays the list from
  it. ⋯ / long press: Play Next, Add to Queue, Favorite/Unfavorite, Rate (1–5 stars, Clear Rating),
  Instant Mix (Jellyfin, online), Add to Playlist… (with New Playlist…), Choose Cover…, Share with the
  Family / Stop Sharing, Identify… / Undo Identify…, Download / Remove Download, Remove from Playlist.
  Actions that need the server are hidden offline.
- **Search** (`Library/SearchView.swift`): field "Artists, Albums, Songs", asks once typing pauses
  (300 ms), sections Artists / Albums / Songs, tapping a song plays only it; the prompt names the server,
  offline "…you've downloaded"; offline searches downloads or Room.
- **Mini player** (`Player/MiniPlayerBar.swift`): foundation §4; Next disabled on the last song; after a
  relaunch shows the last queue's song paused ("‹title›, play from where you left off").
- **Full player** (`Player/PlayerView.swift`, `PlayerSupport.swift`, `PlayerSheets.swift`), **always
  dark**: black + the cover blurred (Android 12+; a dimmed cover below) with a 0.55 black overlay, white
  controls, cover crossfade 300 ms. Top: **Close** (chevron down), **Stop** (✕). Square cover 280 dp on
  phones up to 400 dp on tablets, radius 8; swipe left/right = next/previous; sleep badge on the cover.
  Title (scrolls once after 2 s when too long) + favourite star; artist · album; format badge; "Track 1
  of 5" or, for a concert, a "Song 2 of 14" button → song list. Scrubber with the buffered part lighter,
  elapsed / −remaining, redrawn on its own ~4×/s. Transport: Previous, Play/Pause (64 dp in 72 dp),
  Next. Error line under the controls. **Tools row** (scrollable, icon + small label, active ones in the
  accent): Lyrics, Shuffle, Repeat, Crossfade, EQ, Sleep, Queue, Identify (M11), Output (the system
  output switcher). Max column width 600 dp.
- Player sheets:
  - **Queue**: Now Playing, Up Next (tap to jump; drag handles to reorder; swipe to remove; "Nothing after
    this track."), Played (greyed; tap to go back).
  - **Sleep Timer**: 5/15/30/45/60 minutes, End of Track; while running: time left, **+10 minutes**,
    **Turn Off Sleep Timer**.
  - **Crossfade**: switch, "Fade: N s" stepper 1–12, **Volume Leveling** Off / Same for Every Song / Same
    for Every Album with its explainer.
  - **Lyrics**: synced lines (current bright and centred, tap to seek), plain text scrolls, **Version**
    picker when several; empty "The server has no lyrics for this song. Servers read them from the file's
    tags or an .lrc file next to it."
  - **Equalizer**: switch, Preamp + six vertical sliders ±12 dB, presets with a check, "Custom",
    **Reset**.
- Keyboard on tablets/Chromebooks: Space, Ctrl+←/→, Esc.

**Done when:** every screen above works against own.audio (demo server) in light, dark, Czech and 200 %
font scale; Roborazzi screenshots recorded; the guide's first pages (getting started, home, library,
player) written.

## F7 — Welcome, sign-in, server page, family, devices, listening, logs

Foundation §10. Music specifics:

- Welcome (`Servers/OnboardingView.swift`): "Your music, from your own server.", "own.audio · Navidrome ·
  Jellyfin"; card "No server? Use own.audio" — "own.audio keeps your music for you. Upload it once and play
  it here."; demo footnote "Sample music on a shared own.audio server, no account needed."; "Or connect
  your own server": **Server address** (`music.example.com`), **Username**, **Password** (may be empty —
  Jellyfin allows it), **Name (optional)**, **Connect**; **Sign In with Quick Connect (Jellyfin)**
  (enabled once an address is typed; M8); Google button only when the typed address is an own.audio server
  offering it (asked 600 ms after typing stops, address longer than 3 characters), with "Or sign in with
  the Google account you use on this own.audio server."
- **Add Server** (`Servers/AddServerView.swift`) from Settings, the same form, footer explaining
  detection and https → http.
- Server page music rows: **Music Library…** (Jellyfin, M8); "Talks Through" only for third-party kinds
  ("Subsonic API", "Jellyfin API", "own.audio API"); **Identify Library** (M11); **Library on This
  Device** "Songs: n · Albums: m".
- Read: `Servers/*.swift`, `Settings/HouseholdView.swift`, `DevicesView.swift`, `ListeningView.swift`,
  `LogsView.swift`.

**Done when:** sign-up, sign-in (password, two-factor, Google in the `play` flavour), the demo, Sign In
Again after `DELETE /me/sessions` on the web, account deletion (on a throwaway account), Family with an
invitation and its QR code, Devices, Listening and Logs all work against own.audio; tests for each view
model.

## M5 — Playlists and smart playlists

- **Playlists** (`Library/PlaylistsView.swift`): own.audio first shows **Smart Playlists** (saved) and
  **Ready-Made** (server presets: Forgotten Favorites, New in the Library, Back in Rotation, What the Family
  Plays, Genre Station — genre picked first), then **Playlists** with song count and "by ‹owner›" for
  others'. Top bar: **New Smart Playlist** (own.audio), **+ New Playlist** (name dialog → Create).
- **Playlist**: cover, name, description, Play/Shuffle, songs, download button; own playlists ⋯ **Edit
  Playlist**: Rename, Choose Cover… (own.audio), Share with the Family / Stop Sharing (own.audio
  household), Delete Playlist (confirm "This deletes it on the server, for every app that uses it.");
  swipe to remove a song (by entry id); drag to reorder (`PUT /playlists/{id}/entries/order` with every
  entry id).
- **Smart playlist** (`SmartPlaylistView.swift`): "25 songs, chosen from 340 that fit"; **New Selection**;
  ⋯: Edit Rule (own), Save as Playlist (`/freeze`), Delete Smart Playlist (own), Save as Smart Playlist
  (on a preset). Empty: "Nothing Fits Right Now".
- **Rule editor** (`SmartRuleEditor.swift`): Name; Share with the Family; Genres (All / pick); Favorites
  Only; Rating at Least; Not Played For; Added in the Last; Played by Others, Not by Me; Tempo From/To
  40–240 BPM; Energy; Length Songs 10–500 / Minutes 15–1440; Order; Energy Over Time (`none`,
  `warmup-sustain-cooldown`, `build`, `unwind`); At Most … per Artist 1–20; Never the Same Artist Twice in a
  Row. A live count via `POST /smart-playlists/resolve`. JSON shape = `SmartRule` in music-ios
  `OwnAudioKit/Smart.swift`.
- Subsonic and Jellyfin playlists (create, rename, add, remove, delete) through their sources (M7, M8).

**Done when:** the playlist life cycle and a smart playlist created, edited, frozen and deleted work
against own.audio; rule JSON round-trip tests.

## F5 + F6 + M6 — Downloads, offline, sync

Foundation §8–9. Music specifics:

- Collections: album, playlist, single song. `SetDownloadButton` on album and playlist pages
  ("Downloading n of m"). A song shared by collections is removed only when none needs it. Covers
  prefetched at 120/300/600/1000 px.
- **Downloads** tab (`Library/DownloadsView.swift`): On This Device (bytes), Downloading (n), **Retry N
  Failed**; sections Albums / Playlists / Songs, each row with its server name; swipe or menu **Remove
  Download**; **Remove All**. A downloaded collection opens offline. Empty text: "Download an album or a
  playlist with the arrow on its page, or a song from its menu…".
- Settings values: Download on Wi-Fi Only (on), Keep at Most (No Limit, 1, 2, 5, 10, 20, 50 GB), Whole
  Library When Offline (off).
- Offline screens: downloads, or with **Whole Library When Offline** the catalogue with the songs not
  downloaded greyed out; Play/enqueue filter to downloaded songs and say "You're offline, and none of these
  songs are downloaded."
- Offline change queue (`Core/PendingChanges.swift`, limit 1000): favourite, rating (with kind),
  add-to-playlist; own.audio favourites/ratings/plays in one `/sync/push` (ops `favourite`,
  `unfavourite`, `rate`, `play`; `at` = when made; `client_id`), playlist edits one call each.
- Live updates (foundation §9.2) trigger a feed sync.

Read: `Core/DownloadManager.swift`, `OfflineLibrary.swift`, `PendingChanges.swift`,
`PendingScrobbles.swift`, `Packages/MusicLibrary/.../EvictionPolicy.swift`; UI tests "download + play
offline", "partly downloaded album offline", "whole library offline", "killed mid-download".

**Done when:** an album downloads on Wi-Fi only, survives the app being killed, plays in airplane mode;
favourites made offline reach the server after reconnecting; eviction keeps the playing song; tests pass.

## M7 — Subsonic / OpenSubsonic

- `:kit:subsonic` (port `Packages/SubsonicKit`): token + salt auth per request (`u`, `t`, `s`,
  `v=1.16.1`, `c=own.audio Music`, `f=json`), OpenSubsonic extensions where offered
  (`getOpenSubsonicExtensions`: `songLyrics`, `transcodeOffset`, `formPost`), endpoints: `ping`,
  `getArtists`, `getArtist`, `getAlbumList2` (all sorts), `getAlbum`, `getSong`, `getGenres`,
  `getSongsByGenre`, `getRandomSongs`, `search3` (album by album when a server lists no songs without a
  term), `getStarred2`, `star`/`unstar`, `setRating`, `getPlaylists`/`getPlaylist`/`createPlaylist`/
  `updatePlaylist`/`deletePlaylist`, `getLyricsBySongId` / `getLyrics`, `stream` (with `maxBitRate`,
  `format=mp3`), `download`, `getCoverArt`, `scrobble`.
- `SubsonicSource : MusicSource`; catalogue by paging into Room (max age 6 h, Wi-Fi only).
- Errors: error 40 is a wrong password, except from a server that names itself Ampache (it answers every
  token sign-in with 40): that, and 41/42, are `TokenAuthUnsupported` → "This server doesn't accept Subsonic
  sign-in with a token". 50 is "not authorized". `serverIdentity()` reads type and version from `ping` even
  when sign-in fails.

Read: music-ios `Packages/SubsonicKit/**`, `MusicLibrary/SubsonicSource.swift`; tests with
`MockURLProtocol` → MockWebServer.

**Done when:** the end-to-end suite passes against a throwaway Navidrome in Docker (tagged test tones
made with ffmpeg are enough; `POST /auth/createAdmin` then `startScan.view`).

## M8 — Jellyfin

- `:kit:jellyfin` (port `Packages/JellyfinKit`): `Authorization: MediaBrowser Client="own.audio Music",
  Device=…, DeviceId=…, Version=…, Token=…`; `System/Info/Public`, `Users/AuthenticateByName`, Quick
  Connect (`QuickConnect/Enabled`, `/Initiate`, `/Connect`, `Users/AuthenticateWithQuickConnect`),
  music libraries (`UserViews`), items, favourites (hearts), instant mix, `NormalizationGain`, `Audio/{id}/
  universal` for lower bitrates, session reports (`Sessions/Playing`, `/Progress`, `/Stopped`),
  `Sessions/Logout` on removal.
- **Quick Connect** screen (`Servers/QuickConnectView.swift`): the code, "Enter this code in a Jellyfin app
  where you're signed in, under Quick Connect in your user settings.", "Waiting for approval…", the error
  state "Quick Connect Didn't Work" + **Try Again**.
- **Music Library** picker (Jellyfin, from the server page): each library + "All Music Libraries".
- Instant Mix in the album and song menus.

**Done when:** the end-to-end suite passes against a Jellyfin test server, Quick Connect included.

## M9 — Settings

`Settings/SettingsView.swift`, in this order:

1. **Servers**: logo + name + "‹kind› · ‹user›", check on the active one; tap → server page; swipe
   **Remove**; **Add a Server**. Footer "The library shows the selected server. Music from any server can
   stay in the queue." own.audio servers first.
2. **Downloads and offline**: Downloads (size), Offline Mode, Download on Wi-Fi Only, Keep at Most, Whole
   Library When Offline, footer.
3. **Streaming**: On Mobile Data Original / **Saver** (default) / Downloaded Only; Saver Quality 320 /
   **192** / 128 kbps (only with Saver).
4. **Playback**: Crossfade, Fade 1–12 s, Volume Leveling, Equalizer (shows the preset name / Custom / Off).
5. **Accent Color**.
6. **Report Plays to the Server** + footer (half a song or 4 minutes; Jellyfin's own rules; Last.fm/
   ListenBrainz through the server).
7. **Language** (Android only).
8. **About**: Version, Source Code and Licence, Privacy, Licences, Logs; footer "own.audio Music is free and
   open source (MPL-2.0). It talks only to the servers you add."

**Done when:** every setting changes what it says (tests per setting), screenshots in both languages.

## M10 — own.audio extras: sharing, covers, concerts

- **Share with the Family** per song, album ("Share My Songs"), playlist: `PUT /tracks/{id}/visibility`,
  `PATCH /playlists/{id} {visibility}`; only with a household and only the owner's items.
- **Your own covers**: `PickVisualMedia` → resize to 1,600 px JPEG → `POST /uploads {kind: track_cover |
  playlist_cover…, size_bytes}` → PUT → `PUT /tracks/{id}/image` (album: every own song's cover),
  `/playlists/{id}/image`, `/contributors/{id}/image` (artist picture, by id).
- **Concerts**: song list sheet (numbered, tap to jump); owner's **Edit Songs**: rename, start time
  (`9:26` / `1:02:03`, port `ChapterTime.swift`), **Start Here**, **Add a Song Here**, swipe delete,
  **Save** (`PUT /tracks/{id}/chapters`).

Read: `Settings/HouseholdView.swift` (sharing parts), `Library/ArtworkPicker.swift`,
`Player/ConcertSheets.swift`, `Core/AppModel+Concerts.swift`, `OwnAudioKit/Concerts.swift`.

**Done when:** each works against own.audio with the owner's songs; hidden for others' songs and on
third-party servers.

## M11 — Identify (MusicBrainz)

The biggest own.audio-only feature. Port `App/OwnAudioMusic/Identify/*` and `OwnAudioKit/Identify.swift`.

- Shown when discovery has `modules.music_identify` / `identify_library` and v2 revision ≥ 16; only
  for the user's own songs. On servers with `modules.billing`, `POST /identify/quote` first and
  "Start".
- **Identify Library** (`IdentifyRunsView`): scope Whole Library or a music folder (`GET
  /library/folders`); **Identify Whole Library** / **Identify This Folder**; **Runs** with state Queued /
  Identifying / Waiting / Ready / Failed and counts (sure, to review, not found, accepted).
- **Run** (`IdentifyRunView`): tabs To Review / Sure / Not Found / Done, grouped by album; Accept … Sure
  Matches; album menu Accept All … Songs, Other Edition…, correct Album/Artist + Search MusicBrainz;
  song actions Accept / Reject / Undo, Other Recording…; the notes (Weak match, Other album, Other artist,
  Not on this edition, Two files, one track); More: Undo Run, Delete Run.
- **Recording picker** (`RecordingPicker`): fields prefilled from tags, **Search MusicBrainz**,
  recordings with covers, **Or Paste a Link** → **Use This Recording**. Candidate covers come from the
  Cover Art Archive (`coverartarchive.org`): list it in `PRIVACY.md` (the iOS PRIVACY.md misses it).
- Identify from the song menu and the player (Identify… / Undo Identify…).
- Live progress: follow `identify` events on `/events`, reload at most every 2 s, poll only when the
  stream is closed or silent for 30 s, reconnect 2 s doubling to 60 s (`IdentifyLive.swift`).
- `402 payment_required` → "There isn't enough credit in the family for this."; `409` on undo of a never
  identified song → hide.

**Done when:** an album run from start to accept to undo works against an own.audio test server with
the owner's songs; the follower's timing rules are unit-tested.

## F8 + M12 — Android Auto, widget, deep links, voice, background refresh

Foundation §11. Music specifics:

- **Auto browse tree** (port `Core/CarPlayContent.swift`): root **Recently Played**, **Recently Added**,
  **Downloads**, **Playlists**, **Artists**, **Favorites**; ≤ 100 items per list; Downloads = a "loose
  songs" row first, then collections by name; Favorites = "all songs" first, then albums; "Add a server on
  your phone" when there is none; errors as one item. Playable albums/playlists play from the first song.
- **Widget** "Recently Played" (port `Widgets/RecentlyPlayedWidget.swift`): small (one) and medium (three):
  the resume entry ("Continue · ‹artist›") + the last albums/playlists; tap → deep link.
- **Deep links**: `ownmusic://resume`, `ownmusic://play/album/<compositeId>`,
  `ownmusic://play/playlist/<compositeId>`, `ownmusic://player`.
- **Voice**: `onPlayFromSearch` / `MEDIA_PLAY_FROM_SEARCH`: "play ‹album›", "play my playlist ‹name›",
  "continue listening" (port the entity queries in `Intents/MusicIntents.swift`), accent-folded matching.
- **Periodic refresh**: sync catalogues, flush plays and changes, refresh the widget.

**Done when:** the browse tree works on the Desktop Head Unit (DHU) or an Automotive emulator, the widget
shows and opens items, deep links and voice search play the right thing.

## F9 + M13 — Localization, accessibility, tablets, misuse tests, scale

- Foundation §12: generate `values-cs` from music-ios `App/OwnAudioMusic/Localizable.xcstrings` (635 keys),
  `Widgets/Localizable.xcstrings`, `AppShortcuts.xcstrings` (shortcut labels).
- Accessibility: TalkBack labels on every icon button (from the iOS accessibility labels), the scrubber's
  value ("1:23 of 4:56"), headings, 48 dp targets, 200 % font scale layouts (Roborazzi), no information by
  colour only.
- Tablets/foldables: Library sidebar, player in a centred column, album grid density, landscape on
  tablets (phones portrait-only, as iOS).
- Misuse suite (foundation §13) adapted to music: hammered player, stop while loading, killed
  mid-download, server removed mid-song, seek then stop, long song past 30 s, interruptions.
- Scale: a 600k-song own.audio library syncs, browses and searches on a mid-range phone; memory under
  200 MB while browsing; first snapshot progress visible on the server page.

**Done when:** the whole end-to-end suite is green against own.audio, Navidrome and Jellyfin; Czech and
200 % screenshots reviewed.

## F10 + M14 — Release

Foundation §14. Store texts from music-ios `AppStore/en-US` and `cs`, rewritten for Android (no iOS
words). Screenshots from a demo-only own.audio server with open-licensed music. The README gets the
Google Play and F-Droid badges. The empty `own-audio/subsonic-android` and `own-audio/jelly-android`
repositories get a pointer README to this one and are archived, as their iOS counterparts were.

---

## Server work this app would like (none blocks 1.0)

1. **ReplayGain values in v2 track records** (`track_gain_db`, `track_peak`, `album_gain_db`,
   `album_peak`, from the measured loudness). Until then leveling on own.audio uses file tags only.
2. **Real `Content-Type` on locally stored files** instead of `application/octet-stream` (the app
   copes; it costs a sniff).
3. **The public demo server on 1.0.4 or later** so its discovery offers the demo account to v2 clients.
4. **Sign in with Apple web callback**, if Apple sign-in on Android is wanted (foundation §10.4).
5. **Fixes to `openapi-v2.json`** found while writing the client: `PushRequest.changes` points at the
   change-feed `Change` schema instead of the push change (`client_id, op, kind, id, at, rating, value,
   position_ms, …`, as in `backend/src/v2/state.rs`); `ProblemBody` lacks `mfa_token`, `mfa_methods`,
   `feature`, `error`, `error_description`; the hosted routes (`/narration/*`, `/summaries/*`,
   `/family/billing`) aren't in the public document; `Discovery.addresses`, every `Page_*.items` and the
   smart rule are untyped `object`s; some times are plain strings (session times, `Family.created_at`,
   `Invite.expires_at`, `Member.joined_at`).
6. **Refresh-token reuse**: on 1.0.5 a refresh token sent again right after rotation got a new pair (a
   grace window). The client doesn't rely on it; the server docs should say how long the window is.

## Open questions for the maintainer

1. Google Play and F-Droid both, or Play only at first? (The plan builds both flavours either way.)
2. Which own.audio test server the end-to-end suite uses by default (the public demo is read-only, so
   favourites, playlists and identify need a writable one).
