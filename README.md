# Plex Open Android

A native Android client for [Plex Open Web](https://github.com/spirosrap/plex-open-web). It talks to the same self-hosted API, but uses Android views, app-private storage, and Media3 playback so browsing and playback feel snappier than the mobile web UI.

## Features

- Password login against a Plex Open Web server.
- Persistent authenticated session cookies.
- Library browsing with recent, all, unwatched, sorting, pagination, and search.
- TV navigation from show to season to episode.
- Native detail screens for movies and episodes.
- Media3 playback for direct, compatible, and server-saved streams.
- Resume/progress reporting back to Plex Open Web.
- Plex, sidecar, embedded, and downloaded VTT subtitle playback.
- OpenSubtitles search and download through the Plex Open Web server.
- Server-side saved playback controls.
- Android device save/delete using app-private files for MP4 and VTT copies.

## Server Requirement

This app is a client for Plex Open Web, not a standalone Plex server. Run and configure the server first:

```bash
git clone https://github.com/spirosrap/plex-open-web.git
cd plex-open-web
cp .env.example .env
python3 server.py
```

The Android app asks for the server URL on first launch. Tailnet HTTPS URLs, direct tailnet HTTP URLs, and local LAN URLs are supported. Do not commit Plex tokens, app passwords, OpenSubtitles credentials, or `.env` files.

## Build

Install the Android SDK, then point `local.properties` at it:

```properties
sdk.dir=/path/to/android-sdk
```

Build a debug APK:

```bash
./gradlew assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Device Save

`Save device` downloads the Plex Open Web server's browser-friendly saved MP4 plus supported VTT subtitle files into this Android app's private storage. `Delete device` removes only those Android-local copies. It does not delete the server-side saved copy or the original Plex library media.

## Notes

- Server-side `Save` still happens on the Plex Open Web host.
- Android device saves are capped and pruned like the web app: roughly 12 GB and 14 days.
- HTTP server URLs are allowed because many tailnet and LAN deployments use direct HTTP.

