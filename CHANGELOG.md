# Changelog

All notable changes to own.audio Music for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

- F3: `:core:servers` — server records in one encrypted file (the key stays in the Android Keystore), never writing stale tokens back; address scopes, racing by server identity and per-network memory; the network monitor (metered, Data Saver); the offline switch; saved answers for offline use.
- F2: `:kit:ownaudio`, the own.audio /api/v2 client: discovery (needs server 1.0.4), sign-in with password, two-factor and Google, rotating tokens refreshed one at a time, the catalogue, lists, smart playlists, state, playback links, sync snapshot and changes, live events, uploads, household, identify, narration and summaries. No /api/v1 calls.
- F1: the look of the iOS app — tokens, theme, accent choice, the animated own.audio mark, the rotating wordmark, the hero, shared components (cover art, mini player, offline banner, settings rows, download buttons, badges, QR code, Google button, accent picker), a debug design catalogue with screenshot tests, and the launcher icon from the iOS icon's construction.
- `:kit:subsonic` and `:kit:jellyfin`: the Subsonic/OpenSubsonic and Jellyfin clients, ported from the iOS app, with Quick Connect; Ampache is recognised and reported as not accepting token sign-in.
- F0: the build (Gradle 9.9.2, AGP 9.5.2, Kotlin 2.4.21, compileSdk 37), `play` and `foss` flavours, the module layout, five empty tabs in English and Czech, CI, the APK size check and the drift check against the sibling apps.
- Implementation plan and the shared Android foundation document.
