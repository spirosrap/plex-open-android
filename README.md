# Plex Open Android

A native Android client for [Plex Open Web](https://github.com/spirosrap/plex-open-web). It talks to the same self-hosted API, but uses Android views, app-private storage, and Media3 playback so browsing and playback feel snappier than the mobile web UI.

## Features

- Password login against a Plex Open Web server.
- Persistent authenticated session cookies.
- Library browsing with continue, recent, all, unwatched, persistent genre filtering, sorting, pagination, and search.
- Native Plex collection browsing with artwork, item counts, and collection-to-movie navigation.
- Native movie collection membership controls backed by the real Plex library.
- Native manual collection creation, rename, and confirmed deletion controls.
- Permanent movie and episode deletion with an exact disk preview and typed irreversible-action confirmation.
- Server-backed My List shared with the web app, with per-library browsing and poster badges.
- Surprise Me selection for opening a random item from the active genre and Unwatched filters.
- Persistent library, view, genre, and sort context across app restarts and upgrades.
- Resume-progress indicators and manual watched/unwatched controls synchronized with Plex.
- Persistent System, Light, and Dark themes available on login and signed-in screens.
- One-tap scanning for the selected Plex library with progress feedback and an automatic result reload.
- TV navigation from show to season to episode.
- Previous/next episode navigation with optional persisted autoplay and a cancellable Up Next countdown.
- Native detail screens for movies and episodes.
- Media3 playback for direct, compatible, and server-saved streams.
- Full-screen playback keeps the display awake until the player closes.
- Resume/progress reporting back to Plex Open Web.
- Plex, sidecar, embedded, and downloaded VTT subtitle playback.
- OpenSubtitles search and download through the Plex Open Web server.
- Server-side saved playback controls.
- Android device save/delete using app-private files for MP4 and VTT copies.
- Original media and available subtitle download as a ZIP in Android Downloads.
- Disk-backed artwork caching, shared in-flight poster requests, and diff-based library rendering for fast repeat browsing.
- Stale-response protection so rapid library, view, sort, and genre changes always leave the newest selection on screen.

## Release notes

Release notes cover user-facing changes and intentionally omit deployment-specific and private details.

### 0.15.0

**Added**

- Added Delete from disk to native movie and episode details when permanent deletion is enabled by the shared server.
- Added a native confirmation view listing every planned file and complete folder, total size, linked copies, and server safety warnings.
- Added an exact `DELETE` phrase requirement before Android enables the permanent action.

**Improved**

- Successful deletion immediately removes the item from the current screen, navigation history, My List, hydrated metadata, resume state, and Android's private playback cache.
- Movie collection screens are marked for refresh after a deleted movie so native collection counts return to authoritative Plex state.
- The confirmation flow remains responsive while the server inspects hardlinks and qBittorrent state, with clear loading, blocked, deleting, and failure states.

**Fixed**

- Android now uses the server's short-lived signed plan instead of trusting client-provided paths or an item ID alone.
- Changed or expired deletion plans are rejected without mutating local app state.
- Active downloads, paths outside approved roots, unsafe movie folders, and incomplete hardlink coverage cannot be confirmed from Android.

### 0.14.0

**Added**

- Added one-call authenticated startup using the shared server response for session state, libraries, filters, My List, and the first media page.
- Added a visible actions button to every manual collection card, including confirmed Delete collection behavior that preserves its movies.
- Added a bounded in-memory metadata cache and background detail prefetch so opening Play after viewing details avoids another network wait.
- Added stale disk-cache fallback for browse and metadata reads when a temporary network failure occurs.
- Successful CI builds now upload the installable debug APK as a 14-day workflow artifact.

**Improved**

- Library changes now request genres and the first media page together instead of waiting for filters before loading movies.
- The initial Android page was reduced from 60 to 30 items, cutting first-load JSON, object creation, and poster queue work while retaining Load more.
- Short-lived server cache headers now activate the existing 128 MB OkHttp disk cache for repeat library and metadata reads.
- Recently hydrated media is reused across details, playback, subtitles, and adjacent actions within the activity.
- Collection deletion removes the card and updates counts immediately without reloading the complete collection library.

**Fixed**

- App launch no longer performs a separate session request before loading the server and library.
- A completed collection deletion cannot remove an item from a different library or newer screen if navigation happened during the request.
- Smart collections do not expose the collection actions button and remain protected by server-side validation.

### 0.13.0

**Added**

- Added a 128 MB HTTP disk cache for server-provided artwork, backed by the web app's immutable poster URLs.
- Added one-call startup through the shared bootstrap API for server identity, libraries, My List keys, and release metadata.
- Added shared in-flight poster loading so repeated artwork is downloaded and decoded only once even when several views request it together.
- Added subtle poster fade-in with a visible title fallback while network or disk decoding is in progress.

**Improved**

- Library updates now use background `AsyncListDiffer` comparisons instead of clearing the adapter and rebinding every visible card.
- Poster cards use a fixed two-by-three measurement layout, eliminating per-bind height changes and avoidable layout passes.
- RecyclerView now keeps a bounded view cache, prefetches the next rows, preserves fixed dimensions, and avoids flickering change animations.
- OkHttp now reuses a larger connection pool, retries recoverable connection failures, limits request concurrency, transparently accepts compressed API responses, and identifies the installed app version.
- Poster loading uses six bounded workers and weak view references, while recycled views are detached from obsolete results.
- Media details open immediately from the already loaded browse record instead of waiting for another metadata request.
- Playback uses the saved-copy status already returned by hydrated metadata and avoids a duplicate server round trip.

**Fixed**

- A slower old library request can no longer replace a newer library, view, sort, or genre selection.
- Repeated Load more taps are ignored while a page is already in flight, preventing duplicate rows and redundant requests.
- Recycled poster views no longer flash artwork from a previous item or remain blank when an image request fails.
- Stable IDs now distinguish metadata records that do not have a rating key instead of treating every such row as the same item.
- Network, image, connection-pool, and cache work is cancelled or closed when the activity is destroyed.

### 0.12.0

**Added**

- Added a New collection action to the native movie collection manager; the current movie is included automatically.
- Added an actions menu for renaming or deleting each manual Plex collection.
- Added native collection-name entry and deletion confirmation dialogs.

**Improved**

- Collection lifecycle changes refresh names, counts, memberships, and the movie's collection total from the shared Plex-backed API.
- Delete confirmation states clearly that movies remain in the Plex library.
- Smart collections stay visible for membership context but do not expose manual lifecycle actions.
- Collection commands and feedback inherit the selected System, Light, or Dark theme.

**Fixed**

- Renaming the collection currently being browsed updates the native screen title immediately.
- Deleting the currently open collection closes stale dialogs and returns to the refreshed previous screen.
- Duplicate, empty, oversized, missing, cross-library, and smart-collection operations are rejected without changing local state.
- Failed lifecycle requests re-enable collection creation and preserve the last confirmed Plex membership list.

### 0.11.0

**Added**

- Added a Collections action to native movie details.
- Added a native checklist for adding or removing a movie from existing Plex collections.

**Improved**

- Membership changes are applied immediately and refreshed from the shared Plex-backed API.
- Movie details show the current collection count, and removing a movie from the open collection removes it from that screen immediately.
- Smart collections remain visible and disabled with a Smart label because Plex manages them automatically.

**Fixed**

- Failed membership changes restore the checkbox and leave the local movie state unchanged.
- Collection controls are limited to movies and never appear on shows, seasons, episodes, or collection directory cards.
- Server-side validation prevents changes to collections outside the movie's Plex library.

### 0.10.0

**Added**

- Added Previous and Next episode actions to native episode details.
- Added native Auto next and Next episode controls to full-screen playback.
- Added a five-second Up Next countdown with an immediate-next action and a one-time Cancel action.

**Improved**

- Episode order follows the shared Plex-backed server API and continues correctly across season boundaries.
- The Auto next preference persists across app restarts and APK upgrades.
- Countdown controls remain available after normal playback controls fade, while non-episode playback stays uncluttered.

**Fixed**

- Manual episode changes stop and report the current playback session before preparing the next stream.
- Auto next stops at the final available episode and silently falls back to normal playback if neighbor metadata is unavailable.
- Android streaming now reports the installed app version in its network user agent.

### 0.9.0

**Added**

- Added a native My List view synchronized with the web app.
- Added Add to My List and Remove from My List actions for movies, shows, and episodes.
- Added My List badges to saved posters.

**Improved**

- My List refreshes from the server when opened so changes from another client appear immediately.
- The six library views use two balanced rows of three controls.
- Saved items retain the normal details, playback, subtitle, download, and watched-state actions.

**Fixed**

- Empty My List libraries now show a specific empty-state message.
- My List disables unrelated sort, genre, and random controls while leaving library scanning available.
- Failed save or remove requests restore the correct action label and leave local state unchanged.

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
