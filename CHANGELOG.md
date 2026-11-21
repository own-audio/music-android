# Changelog

All notable changes to own.audio Music for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

- A privacy policy (PRIVACY.md), linked from Settings → About.
- The music engine on Media3: playback links asked for when a song opens and renewed when they expire (never in a loop), the storage-route retry, a listening cache keyed by song rather than by link, a gapless queue whose edits never touch the playing song, crossfade on two players, ReplayGain from the server or the file's own tags (boosts through the equalizer's input gain), the 6-band equalizer, audio focus only from the first play, the decoder let go 30 s after a pause, and the last queue kept for the next launch.
- F7: the app shell and the own.audio screens — welcome, own.audio account (sign up, sign in, two-factor, Google in the Play build), Try the Demo Library, Add Server, Settings (servers, accent color, language, about, licences, logs), the server page (status and Check Now, sign in again, delete account, name, connection, addresses), Family with invitations and QR codes, Devices and Listening. Settings rows now use the text colour on their cards.
- M1 and M2: the music library layer — one model over all servers, the catalogue on the phone in Room, and own.audio as a source fed by the sync snapshot and change feed (a failed re-sync leaves the old catalogue usable; a cut snapshot resumes where it stopped).
- The player's logic, ported from the iOS app with its tests: the play queue (shuffle, repeat, Play Next, editing), ReplayGain, the equalizer presets, concert chapters, the play-counting rule, the sleep timer, the storage-route fallback and the audio payload check (which also recognises Matroska/WebM correctly — the iOS check never could).
- F3: `:core:servers` — server records in one encrypted file (the key stays in the Android Keystore), never writing stale tokens back; address scopes, racing by server identity and per-network memory; the network monitor (metered, Data Saver); the offline switch; saved answers for offline use.
- F2: `:kit:ownaudio`, the own.audio /api/v2 client: discovery (needs server 1.0.4), sign-in with password, two-factor and Google, rotating tokens refreshed one at a time, the catalogue, lists, smart playlists, state, playback links, sync snapshot and changes, live events, uploads, household, identify, narration and summaries. No /api/v1 calls.
- F1: the look of the iOS app — tokens, theme, accent choice, the animated own.audio mark, the rotating wordmark, the hero, shared components (cover art, mini player, offline banner, settings rows, download buttons, badges, QR code, Google button, accent picker), a debug design catalogue with screenshot tests, and the launcher icon from the iOS icon's construction.
- `:kit:subsonic` and `:kit:jellyfin`: the Subsonic/OpenSubsonic and Jellyfin clients, ported from the iOS app, with Quick Connect; Ampache is recognised and reported as not accepting token sign-in.
- F0: the build (Gradle 9.9.2, AGP 9.5.2, Kotlin 2.4.21, compileSdk 37), `play` and `foss` flavours, the module layout, five empty tabs in English and Czech, CI, the APK size check and the drift check against the sibling apps.
- Implementation plan and the shared Android foundation document.
