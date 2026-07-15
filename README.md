# Plex Open Android

A native Android client for [Plex Open Web](https://github.com/spirosrap/plex-open-web). It talks to the same self-hosted API, but uses Android views, app-private storage, and Media3 playback so browsing and playback feel snappier than the mobile web UI.

## Features

- Password login against a Plex Open Web server.
- Persistent authenticated session cookies.
- Library browsing with continue, recent, all, unwatched, persistent genre filtering, sorting, pagination, and search.
- Native Plex collection browsing with artwork, item counts, and collection-to-movie navigation.
- Surprise Me selection for opening a random item from the active genre and Unwatched filters.
- Persistent library, view, genre, and sort context across app restarts and upgrades.
- Resume-progress indicators and manual watched/unwatched controls synchronized with Plex.
- Persistent System, Light, and Dark themes available on login and signed-in screens.
- One-tap scanning for the selected Plex library with progress feedback and an automatic result reload.
- TV navigation from show to season to episode.
- Native detail screens for movies and episodes.
- Media3 playback for direct, compatible, and server-saved streams.
- Full-screen playback keeps the display awake until the player closes.
- Resume/progress reporting back to Plex Open Web.
- Plex, sidecar, embedded, and downloaded VTT subtitle playback.
- OpenSubtitles search and download through the Plex Open Web server.
- Server-side saved playback controls.
- Android device save/delete using app-private files for MP4 and VTT copies.
- Original media and available subtitle download as a ZIP in Android Downloads.

## Release notes

Release notes cover user-facing changes and intentionally omit deployment-specific and private details.

### 0.8.0

**Added**

- Added a native genre selector populated from the active Plex library.
- Added an independent saved genre selection for every library.

**Improved**

- Genre filters work with paging, sorting, Continue, Recent, All, and Unwatched views.
- Surprise Me now respects the selected genre and limits picks to unwatched media from the Unwatched view.
- The genre and sort controls use separate stable rows for easier selection on phones.

**Fixed**

- Switching libraries no longer carries an unrelated genre identifier into the new library.
- Removed or invalid saved genres fall back to All genres instead of producing an empty library.
- Collections temporarily disables the unrelated genre filter while preserving it for other views.

### 0.7.0

**Added**

- Added a Surprise Me action beside the sort control that opens a random item from the selected library.
- Added persistent restoration of the last library, view, and sort selection.

**Improved**

- Random picks open immediately with the usual details, playback, subtitle, save, and download actions.
- Browsing context survives activity recreation, theme changes, app restarts, and APK upgrades.
- The sort and Surprise Me controls share a stable action row without crowding the five view buttons.

**Fixed**

- Reopening the app no longer always resets to the first library and default view.
- Removed libraries and invalid or obsolete saved values now fall back safely to available defaults.
- Restored Continue and Collections views correctly disable the unrelated sort control.

### 0.6.0

**Added**

- Added a Collections view for every Plex library, including collection artwork, badges, and item counts.
- Collection cards open directly into their movies using the existing native back stack.

**Improved**

- All five library views remain visible in a balanced two-row control instead of hiding Collections off-screen.
- Collection lists are alphabetical, paged through the shared server API, and clearly distinguished from playable media.
- Collection children retain the normal movie details, playback, subtitle, save, and download actions.

**Fixed**

- Collection directories are no longer opened as unsupported media details.
- Collection composite posters now load through the server's corrected image proxy.

### 0.5.0

**Added**

- Added persisted System, Light, and Dark theme selection on the login and signed-in screens.
- Added a complete dark palette for browsing, cards, forms, dialogs, subtitle search, and controls.

**Improved**

- System mode follows Android's current light or dark appearance.
- Theme changes recreate only the activity and preserve the server URL, authenticated session, saved playback files, and device downloads.
- Transient status and navigation bars use icons with the correct contrast for the selected theme.

**Fixed**

- Native spinner values and dropdown rows remain readable in Dark theme.
- Selected libraries, view tabs, buttons, fields, poster fallbacks, and progress indicators retain clear contrast in both themes.

### 0.4.0

**Added**

- Added a Continue view for in-progress media and the next available TV episodes in each library.
- Added Mark watched and Mark unwatched actions to movie and episode details.
- Added resume-progress bars to poster cards and progress status to media metadata.

**Improved**

- View tabs now scroll cleanly on narrow screens instead of competing with the sort menu.
- Detail actions are split into readable rows for more reliable tapping.
- Continue and Unwatched automatically reload after playback or a manual watched-state change.

**Fixed**

- Watched items no longer resume from stale local playback positions.
- Very short playback attempts under ten seconds no longer create unwanted resume points.
- Manual watched-state changes clear stale device resume data and immediately refresh media cards.
- Continue excludes fully watched entries even when Plex retains an old On Deck offset.

### 0.3.0

**Added**

- Added one-tap scanning for the selected Plex library.
- Added scan status and result messages in the library view.

**Improved**

- The Scan button is disabled while a scan is running to prevent duplicate requests.
- The selected library automatically reloads after Plex accepts the scan.

**Fixed**

- Failed scans restore the Scan button and display the error in both the status area and a toast.

### 0.2.2 - 0.2.4

**Maintenance**

- Updated release metadata only; these releases did not change user-facing behavior.

### 0.2.1

**Fixed**

- The screen now stays awake during full-screen video playback.
- Normal screen timeout behavior resumes after the player closes.

### 0.2.0

**Added**

- Added visible app version information to the login and main app screens.
- Added original-media and subtitle ZIP downloads through Android's Download Manager.
- Added storage permission handling for Android versions that require it.

**Improved**

- Added a floating Fit/Fill video resize control that follows the player controls.
- Improved subtitle selection for streaming and device playback by preferring selected, default, or forced tracks, followed by Greek or English tracks.
- Improved player controls with a dedicated close affordance and touch-to-reveal behavior.

**Fixed**

- Restored reliable access to the player close control.
- Fixed subtitle tracks starting with an unintended language when a preferred track is available.

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

The separate `Download` action saves a ZIP containing the untouched original video and available subtitles in the public Android Downloads folder.

## Notes

- Server-side `Save` still happens on the Plex Open Web host.
- Android device saves are capped and pruned like the web app: roughly 12 GB and 14 days.
- HTTP server URLs are allowed because many tailnet and LAN deployments use direct HTTP.
