# Privacy policy — own.audio Music for Android

own.audio Music is a player for music servers you choose: Subsonic and OpenSubsonic servers,
Jellyfin, and own.audio. This page says what the app keeps and what it sends. Last updated
21 November 2026.

## What the app collects

Nothing. There is no account with us, no analytics, no advertising, no crash reporting, no
tracking, and no server of ours that the app reports to. We, the makers of the app, receive no
data from it.

## What the app sends, and to whom

- **To the servers you add**, and only to them: your sign-in, requests for your library, the
  music and covers you play, your favorites, ratings and playlists, and the songs you have
  played (servers use these for play counts and "recently played"). What a server does with
  them is up to whoever runs it — you, a friend, or a hosting service with its own policy (for
  own.audio's hosted service: https://www.own.audio/privacy/).
- **To the Cover Art Archive** (coverartarchive.org), only when you identify songs on an
  own.audio server: the covers of the candidate releases are loaded from there.
- **To Google**, only if you choose "Continue with Google" for an own.audio server (in the
  Google Play build; the F-Droid build has no Google code): Android's account picker signs you in,
  and the app passes the result to your server.

## What stays on this device

- The servers you added and how to sign in to them, in one file encrypted with a key kept in
  Android's Keystore. A Subsonic password is kept there, because the protocol needs it for every
  request; only a one-time token made from it is sent. Jellyfin and own.audio keep only a
  sign-in token, never the password.
- Your settings, downloads, covers and saved answers from your servers (so the app works
  offline). Uninstalling the app removes all of it. Downloads can be removed on their own, and
  removing a server removes what the app kept from it.
- Nothing is included in Android's cloud backup: sign-ins are encrypted with a key that never
  leaves this device, and downloads are excluded.

## Children

The app has no age-specific features and collects nothing from anyone.

## Contact

Questions: privacy@own.audio, or an issue at https://github.com/own-audio/music-android/issues.
If this policy changes, the new version is published here, with its history in this
repository.
