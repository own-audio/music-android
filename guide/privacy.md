---
title: Privacy
nav_order: 11
description: "The app collects nothing. There is no account with us, no analytics, no ads and no tracking."
---

# Privacy

The app collects nothing. There is no account with us, no analytics, no ads, no crash reporting
and no tracking.

It talks only to:

- **the servers you add**: your sign-in, requests for your library, the music and covers you
  play, your favorites, ratings and playlists, and, unless you turn off **Report Plays to the
  Server**, what you played;
- **the Cover Art Archive** (coverartarchive.org), only when you identify songs on an own.audio
  server, for the covers of the candidate releases;
- **Apple**, only if you choose **Continue with Apple** for an own.audio server. Apple's page
  opens in your browser; the app gets nothing from Apple directly, the own.audio server does;
- **Google**, only in the Google Play build and only if you choose **Continue with Google** for
  an own.audio server. The F-Droid build has no Google code.

On the phone, your servers and sign-ins are kept in one file, encrypted with a key held in
Android's Keystore. A Subsonic password is kept there because the protocol needs it for every
request, and only a one-time token made from it is sent; Jellyfin and own.audio keep only a
sign-in token. Your settings, downloads and the copy of your library stay on the phone, are not
included in Android's backup, and are deleted with the app. Removing a server removes what the
app kept from it.

When you choose a cover or picture on own.audio, the app uses Android's photo picker, which gives
it only the photo you pick.

The full policy: [PRIVACY.md](https://github.com/own-audio/music-android/blob/main/PRIVACY.md).
Questions: privacy@own.audio, or [an issue on GitHub](https://github.com/own-audio/music-android/issues).
