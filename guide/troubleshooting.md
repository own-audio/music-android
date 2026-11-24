---
title: Troubleshooting
nav_order: 12
description: "What to do when own.audio Music for Android can't reach a server, stops accepting your sign-in, or won't play, and how to share the log."
---

# Troubleshooting
{: .no_toc }

1. TOC
{:toc}

## The server no longer accepts the sign-in

If you changed your password, or signed the phone out from another device, the app says "The
server no longer accepts this sign-in." Open **Settings**, tap the server, and tap **Sign In
Again**. If you signed up with Apple or Google, sign in the same way. Your downloads and the library on
the phone stay as they are.

## The server can't be reached

- Open the server's page and tap **Check Now**. It says whether the server answers and accepts
  the sign-in, refuses it, or whether no address answers.
- While the app is on screen it also asks a server that didn't answer again by itself: after 15
  seconds, then less often, at most every 5 minutes, and at once when you come back to the app
  or open the server's page. In the background, or while the phone is locked, it doesn't.
- At home, check the phone is on the same network as the server. Away from home, the server
  needs an address that works from outside, or a VPN such as Tailscale. Add more addresses under
  **Addresses**; see [Servers and family](servers.md#addresses).
- Until the server answers, you can play what is downloaded.

## Adding a server fails

- "The server didn't accept that username and password": check them. For own.audio, use your
  account's email.
- "Couldn't reach the server": check the address, and that the phone can reach it.
- "This own.audio server is too old for this app": the server needs version 1.0.4 or later.
- "This server doesn't accept Subsonic sign-in with a token": Ampache and accounts kept in LDAP
  sign in only with a plain password, which the app doesn't send.
- No **Continue with Google** button: it is only in the Google Play build, and only for an
  own.audio server that offers Google sign-in.
- No **Continue with Apple** button: the own.audio server doesn't offer Sign in with Apple in the
  browser. Use your email and password, or set a password with "Forgot password" on the server's
  web page.
- "Sign in with Apple opens a web browser, and this device has none": install or turn on a
  browser (Chrome, Firefox or another), then try again.
- "That Apple sign-in has expired": the sign-in took too long or was used already. Tap
  **Continue with Apple** again.

## A song won't play

The reason is shown under the player's controls. Offline, only downloaded songs play. On mobile
data with **Downloaded Only** chosen, tap **Stream** in the line above the mini player, or change
**On Mobile Data** in Settings.

## Downloads don't start

**Download on Wi-Fi Only** is on by default, so downloads wait for Wi-Fi. If some failed, tap
**Retry … Failed** on the **Downloads** tab.

## Sharing the log

**Settings → Logs** shows what the app wrote to its log: each request to your servers (without
sign-in tokens), playback trouble, syncing. **Share Log** sends it as a text file, for example with a bug report.
Nothing is sent unless you share it. The log can hold your server's addresses, so look it over before you post it publicly. Attach it to
[an issue on GitHub](https://github.com/own-audio/music-android/issues).
