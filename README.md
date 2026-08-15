# Plex Open Android

A native Android client for [Plex Open Web](https://github.com/spirosrap/plex-open-web). It talks to the same self-hosted API, but uses Android views, app-private storage, and Media3 playback so browsing and playback feel snappier than the mobile web UI.

## Features

- Password login against a Plex Open Web server.
- Persistent authenticated session cookies.
- Library browsing with continue, recent, all, unwatched, persistent genre filtering, sorting, pagination, and search.
- Native Plex collection browsing with artwork, item counts, and collection-to-movie navigation.
- Native movie collection membership controls backed by the real Plex library.
- Native manual collection creation, rename, and confirmed deletion controls.
- Native Plex Fix Match for movies and TV shows, including metadata search, full-match replacement, poster-only repair, and refresh of the current match.
- One-command metadata refresh for a correctly matched movie or TV show.
- Permanent movie and episode deletion with an exact disk preview and typed irreversible-action confirmation.
- Server-backed My List shared with the web app, with per-library browsing and poster badges.
- Ordered Play Queue shared with the web app, with per-library browsing, one-command playback, badges, and automatic continuation.
- Surprise Me selection for opening a random item from the active genre and Unwatched filters.
- Persistent library, view, genre, and sort context across app restarts and upgrades.
- Resume-progress indicators and manual watched/unwatched controls synchronized with Plex.
- Persistent System, Light, and Dark themes available on login and signed-in screens.
- One-tap scanning for the selected Plex library with progress feedback and an automatic result reload.
- TV navigation from show to season to episode.
- Previous/next episode navigation with optional persisted autoplay and a cancellable Up Next countdown.
- A per-episode Skip Intro overlay that follows native Plex or audio-detected season markers instead of a fixed timer.
- Persistent 0.75x to 2x playback speed from an inset-safe full-screen control.
- Native detail screens for movies and episodes.
- Media3 playback for direct, compatible, and server-saved streams.
- Full-screen playback keeps the display awake until the player closes.
- Active playback automatically enters native Android Picture-in-Picture when Home or another app is opened, with system play/pause, return, and close controls.
- Foreground media playback continues through screen locking and display-off sleep, with Android lock-screen and notification controls.
- Resume/progress reporting back to Plex Open Web.
- Plex, sidecar, embedded, and downloaded VTT subtitle playback.
- A native subtitle chooser with existing-track selection, Off, source details, remembered choices, and OpenSubtitles search.
- OpenSubtitles search and download through the Plex Open Web server.
- Clearly separated remote stream preparation and persistent offline save/remove controls, including confirmed removal from selected-item details.
- Foreground offline downloads continue after leaving the app or turning off the display, with persistent progress and a cancelable Android notification.
- Android offline MP4 and VTT copies use app-private storage, survive server-copy replacement, and are always preferred for playback.
- A permanent Downloads library remains available while connected and provides a local-only catalog for saved movies and episodes.
- Offline saves include a private poster and complete display metadata, so artwork and descriptions remain available without a connection.
- Airplane-mode cold starts open a local Offline library immediately, with local search, Surprise Me, resume positions, and playback that never waits for the server.
- Offline status identifies airplane mode, missing internet, disconnected Tailscale, or an unavailable Plex server and remains local until `Reconnect now` is explicitly selected.
- Original media and available subtitle download as a ZIP in Android Downloads.
- Disk-backed artwork caching, shared in-flight poster requests, and diff-based library rendering for fast repeat browsing.
- Cache-first startup, bounded visible-title metadata warming, and shared playback connections for fast repeat launches and taps.
- Batched metadata warming that prepares the first visible titles through one server request instead of one request per card.
- Stale-response protection so rapid library, view, sort, and genre changes always leave the newest selection on screen.

## Release notes

Release notes cover user-facing changes and intentionally omit deployment-specific and private details.

### 0.21.1

**Added**

- Selecting a downloaded movie or episode now exposes a `Remove offline` action directly in Details; playback is no longer required to remove it.
- Added a confirmation that names the local assets being removed and makes clear that Plex library and server files remain untouched.

**Improved**

- Offline removal deletes every matching app-private generation, including video, poster, subtitles, metadata, and interrupted temporary files.
- The Details action and Downloads library update immediately after removal, and the player removal control now uses the same confirmation.

**Fixed**

- Fixed downloaded titles becoming a disabled `Offline ready` action in Details with no way to remove the device copy.
- Fixed offline deletion reporting success without checking whether the complete local cache was removed.

### 0.21.0

**Added**

- Added a Netflix-style Skip Intro action for TV episodes, powered by the server's per-episode native or Chromaprint marker.
- Added live marker polling so a button can appear during playback as soon as first-time background season analysis produces the current episode's result.
- Added offline marker persistence so a downloaded episode retains its known intro range without needing a connection.

**Improved**

- The Skip Intro action is a stable, inset-safe player overlay that remains independent of the transient Media3 controls and stays out of Picture-in-Picture.
- Seeking lands just after the conservatively detected opening boundary and is clamped before the end of unusually short media.
- Marker results received while playing a downloaded title are written back to its private offline metadata for later airplane-mode sessions.
- Intro timing follows each episode's actual cold-open length rather than applying one duration across a season.

**Fixed**

- Prevented a skip action from appearing on movies, outside the active intro interval, after it has already been used, or for malformed and low-duration markers.

### 0.20.2

**Added**

- Added a dedicated Android foreground data-transfer service for offline movie and episode saves.
- Added a low-priority download notification with live percentage progress, tap-to-return, and a Cancel action.
- Added persisted download state so the player and details dialog immediately restore Preparing, Saving, completed, or retry status after activity recreation.

**Improved**

- Offline saves now keep a partial CPU wake lock while actively transferring, allowing downloads to continue with the app in the background or the display off.
- Android can redeliver an interrupted foreground save request after process reclamation, while reopening the app also resumes a pending request.
- Completing a save no longer restarts or switches the currently playing video; the local copy is preferred the next time the title opens.
- Old partial files and unreferenced cache generations are cleaned conservatively before and after saves to reclaim storage left by interrupted historical attempts.

**Fixed**

- Fixed `MainActivity` teardown cancelling its executor and HTTP calls, which made an offline save appear to stop after leaving the app.
- Fixed an in-progress offline save losing its visible state when the activity was rotated, recreated, or reopened.
- Fixed long-running offline transfers depending on the activity lifecycle instead of Android's foreground-service lifecycle.

### 0.20.1

**Added**

- Added a native subtitle chooser that lists Off plus every available embedded, Plex sidecar, OpenSubtitles, and offline-copy track.
- Added visible source, format, forced, and SDH details for each subtitle choice.
- Kept `Find new subtitles` inside the chooser so existing tracks and subtitle search share one predictable flow.

**Improved**

- Subtitle changes during playback now switch the Media3 text track immediately without restarting the movie or episode.
- Per-title subtitle choices are remembered by Android, including an explicit Off choice and sidecar tracks that Plex cannot persist itself.
- Plex-backed stream choices are also saved through the server so they remain selected in other Plex clients.
- A newly downloaded subtitle is selected automatically and becomes available in the chooser immediately.

**Fixed**

- Fixed the Android `Subtitles` action showing only OpenSubtitles search instead of already available tracks.
- Fixed an explicit Off selection being replaced by the automatic Greek or English fallback on the next playback.

### 0.20.0

**Added**

- Added a dedicated Android foreground media service for movie and episode playback.
- Added Android lock-screen and media-notification controls backed by the same active playback session.

**Improved**

- Playback now owns Android's media wake lock and network wake mode, allowing long online and downloaded sessions to continue after the display turns off.
- The full-screen player and Picture-in-Picture window now act as controllers for the service-owned player instead of owning the video lifecycle themselves.
- Audio focus and unplugged-headphone handling now follow Android media-app conventions.

**Fixed**

- Fixed playback stopping some time after locking the Pixel or turning its screen off.
- Fixed screen locking being mistaken for manually closing the Picture-in-Picture window, which previously released the player.
- Fixed activity teardown being able to destroy an otherwise active background playback session.

### 0.19.9

**Added**

- Added a permanent `Downloads` library beside the connected Plex libraries, showing every verified movie and episode saved on the Pixel.
- Added local search and `Surprise me` support inside Downloads, with a clear count of files ready on the device.
- Remembered Downloads as the selected library across connected app restarts without sending its private device-library key to the server.

**Improved**

- Downloads is a dedicated local-only browsing mode: server filters, scans, metadata warming, stream preparation, and remote-only detail actions are suppressed while it is selected.
- Downloaded playback continues to use the Pixel copy even when internet, Tailscale, and Plex are connected, while the player retains its explicit `Offline` source label.
- Deleting an offline copy from the Downloads player now closes playback and refreshes the local catalog instead of switching to a network source.

**Fixed**

- Fixed an incomplete or externally removed device file falling through from the local catalog into live streaming; Downloads now stops with a clear message and never streams.
- Fixed downloaded TV playback in the local catalog looking up an online next episode that could continue into an undownloaded stream.

### 0.19.8

**Added**

- Added native Android Picture-in-Picture for active movie and episode playback when pressing Home or switching apps.
- Added Android media-session controls so the PiP window exposes system play/pause, return-to-app, and close actions.

**Improved**

- Moved full-screen video onto the activity surface for a smooth transition between the app and the floating PiP window.
- PiP uses the video's aspect ratio, hides full-screen-only controls, preserves subtitles and progress reporting, and keeps both online and offline playback running.
- Playback pauses instead of continuing as hidden audio when PiP is unavailable or disabled in Android settings.

**Fixed**

- Fixed Android's PiP close action leaving audio active after the floating window disappeared; closing now releases the player while retaining the latest local resume position.

### 0.19.7

**Improved**

- Offline status now states whether there is no working internet connection, the internet works but Tailscale is disconnected, or both work but Plex did not respond.
- `Reconnect now` uses the same cause-specific diagnosis instead of showing a generic disconnected message.
- Internet availability is checked before Tailscale, so a broader connection failure is never mislabeled as a tailnet problem.
- The visible cause updates when Android connectivity changes, without starting Tailscale, probing Plex, or reconnecting automatically.

**Fixed**

- Fixed airplane mode and ordinary internet loss sometimes being reported as disconnected Tailscale.

### 0.19.6

**Changed**

- Removed automatic Plex reconnection attempts after network or VPN changes; Offline mode now remains stable until `Reconnect now` is selected.
- Tailscale remains fully user-controlled and is never started, restarted, or enabled by Plex Open.

**Improved**

- The offline status clearly warns when Tailscale may be disconnected while keeping every saved movie available.
- Tailnet server addresses are checked for an active VPN before any request, preventing a stale live-library cache from masking a disconnected Tailscale session.
- `Open Tailscale` remains available as an explicit shortcut, followed by the separate user-controlled `Reconnect now` action.

**Fixed**

- Fixed Plex Open repeatedly probing the private server after airplane mode ended when the user wanted to remain offline.
- Removed the automatic transition away from the Offline library when Android reported a network or VPN change.
- Fixed the explicit `Open Tailscale` shortcut incorrectly reporting that the installed Tailscale app was unavailable.

### 0.19.5

**Added**

- Added Android network and VPN change monitoring so an open Offline library reconnects automatically when the tailnet becomes available.
- Added `Open Tailscale` and `Reconnect now` actions to the offline app menu.

**Improved**

- Reconnection uses a bounded retry sequence while preserving the responsive local library instead of replacing it with a repeated loading screen.
- Live reconnection bypasses stale HTTP responses and only leaves Offline mode after the Plex server answers successfully.
- Offline status now distinguishes airplane mode, ordinary internet loss, and a server or Tailscale connection problem.

**Fixed**

- Fixed the app remaining in Offline mode when airplane mode was disabled before Android had finished restoring Wi-Fi or Tailscale.
- Fixed a manual reconnect briefly appearing successful from an old cached bootstrap even though the private server was still unreachable.

### 0.19.4

**Added**

- Added a private local poster file and complete movie or episode display snapshot to every new Android offline save.
- Added automatic background repair for older offline saves that are missing a description or durable local poster.

**Improved**

- Offline cards and Details load artwork directly from app-private storage instead of depending on a remote image URL or an HTTP cache hit.
- Poster replacement is atomic, size-validated, and included in offline-copy storage accounting and cleanup.

**Fixed**

- Fixed older imported offline movies showing only a title with a blank poster and description in airplane mode.
- Fixed deleting or replacing an offline copy leaving its private poster file behind.

### 0.19.3

**Added**

- Added a dedicated Offline library that is built from verified complete device saves and opens on a cold start without internet access.
- Added local offline search, Surprise Me, and an explicit Reconnect action for returning to the live server when connectivity is available.

**Improved**

- New offline saves retain the title metadata needed to rebuild the local library independently of the server and authenticated HTTP cache.
- Airplane-mode startup treats the device setting as authoritative instead of waiting for radio-state changes or a network timeout, while existing older saves receive a safe local-library fallback entry.
- Network-only browsing, metadata, stream preparation, subtitle search, deletion, and synchronization controls are hidden or disabled while offline.

**Fixed**

- Fixed a valid app-managed offline movie opening the sign-in or disconnected screen when the app was cold-started in airplane mode.
- Fixed background metadata warming and progress synchronization attempting server requests during offline playback.

### 0.19.2

**Added**

- Added `Save offline` directly to movie and episode Details, with an `Offline ready` state when the complete local copy is present.

**Improved**

- Renamed the public file export to `Original ZIP` and explicitly distinguishes it from app-managed offline playback.
- Offline saving from Details reports live download progress and confirms only after the complete video is committed.

**Fixed**

- Fixed a downloaded original-media ZIP being easy to mistake for an app offline save.

### 0.19.1

**Improved**

- Renamed the primary Android action to `Save offline`; it now clearly downloads the complete video and supported subtitles before reporting success.
- Renamed the server-only action to `Prepare stream` and clarified that this copy still uses internet data.
- Offline copies now remain until `Delete offline`, app removal, or Android storage cleanup instead of expiring silently after 14 days.
- Offline playback displays an explicit `Offline` source label.

**Fixed**

- Fixed saved movies falling back to the remote stream when the server regenerated or deleted its prepared stream copy.
- Fixed offline playback waiting for a metadata request before opening the local file on slow mobile data.
- Fixed incomplete or zero-byte device files being treated as playable offline copies.
- Fixed truncated downloads being accepted when the response ended before its declared size.
- Added atomic video, subtitle, and metadata replacement so an interrupted refresh cannot damage an existing offline copy.

### 0.19.0

**Added**

- Added a persistent ordered Play Queue synchronized with the web app.
- Added a Queue view with a live item count, poster badges, and Add to Queue or Remove from Queue actions in movie and episode details.
- Added Play Queue plus queue-aware Auto next with the existing cancellable five-second continuation controls.
- Added a touch-safe full-screen playback-speed control for 0.75x, 1x, 1.25x, 1.5x, and 2x playback.

**Improved**

- Android now warms up to six visible movies or episodes with one batched metadata request and reuses the results for Details, Play, subtitles, and saved-copy checks.
- Media3 starts with a smaller initial playback buffer while retaining a larger steady-state buffer for faster starts without weakening longer playback.
- Queue playback reuses warmed metadata, the existing authenticated OkHttp connection pool, and the same persisted Auto next preference as episode playback.

**Fixed**

- Permanently deleted movies and episodes are removed from the local Queue state immediately as well as from My List, navigation history, and playback caches.
- Fixed duplicate queue additions changing the intended playback order.
- Fixed playback speed resetting when switching among live, server-saved, and device-saved sources or after restarting the app.

### 0.18.0

**Added**

- Added explicit `Resume <time>` and `Start over` actions to native movie and episode details.
- Added a touch-safe in-player Start over control for returning to the beginning without closing the video.
- Added synchronized restart handling that clears both Android's local position and the shared server resume position without changing watched status.

**Improved**

- Active replay progress now takes priority over the older watched label in cards and details.
- Resume calculations consistently use the newer of the Plex and device positions while ignoring near-finished offsets.
- Player actions now share one elevated, inset-safe control strip away from the top-right system bubble area.

**Fixed**

- Fixed watched movies with an active replay position looking completed instead of resumable.
- Fixed starting over briefly and reopening the title returning to the previous resume point.
- Fixed Plex servers that retain a stale raw offset after accepting a restart by honoring the web service's persistent restart state.
- Fixed Fit and Close appearing available while a system bubble could intercept taps in the same corner.

### 0.17.2

**Improved**

- Android now treats a valid Plex playback offset as an active replay even when Plex still retains the movie's watched flag.

**Fixed**

- Fixed previously watched movies such as Arrival restarting from the beginning instead of resuming from their current saved position.
- Fixed Media3 preparation receiving a zero start position for otherwise valid server and local resume offsets.

### 0.17.1

**Improved**

- Full-screen Fit and Close controls now stay clear of display cutouts, hidden status bars, and right-edge system gestures.
- Player overlay controls remain visible for eight seconds and pause their auto-hide timer while a control is being pressed.
- Fit and Close expose descriptive accessibility labels instead of relying only on their short visible text.

**Fixed**

- Fixed the Pixel consuming taps intended for the Fit and Close controls at the top-right of full-screen playback.
- Fixed a control disappearing during a press when its previous auto-hide timer expired.

### 0.17.0

**Added**

- Added cache-first startup that can show the last authenticated library immediately while a fresh server response updates in the background.
- Added bounded metadata warming for the first visible movies and episodes on each screen.
- Added one shared in-flight metadata operation per title so background warming, Details, Play, and subtitle actions reuse the same work.

**Improved**

- Media3 now streams through the app's existing OkHttp client, reusing its authenticated cookies, DNS result, TLS session, connection pool, retries, and warmed server connection.
- Media3 and RecyclerView are updated to their current stable releases for newer playback, codec, networking, and list-rendering fixes.
- Playback uses a lower startup buffer with a larger steady-state buffer, and resumed videos prepare directly at the saved position instead of preparing from zero and seeking afterward.
- The signed-in interface now uses a compact app bar, one scrollable view selector, a single filter row, stable poster tiles, modern fields and buttons, and a dedicated app menu for theme and sign-out controls.
- Light and Dark palettes use clearer neutral surfaces, borders, amber commands, teal progress, and stronger text contrast.
- RecyclerView retains more nearby tiles and applies list changes without layout-heavy transition animations.

**Fixed**

- Fixed a quick Play tap starting a second metadata request while background prefetch was still running.
- Fixed authenticated library cache entries surviving sign-out on disk.
- Fixed resumed playback doing avoidable preparation work at the beginning of the file before moving to the saved position.

### 0.16.0

**Added**

- Added Fix Match to native movie and TV show details.
- Added Plex metadata search by title, external ID, optional year, and Greek or English metadata language.
- Added native match results with posters, summaries, release years, and Best match, Current, and Poster available markers.
- Added separate `Use match`, `Refresh match`, and poster-only `Use poster` commands with explicit confirmations.
- Added a direct `Refresh metadata` command for updating the current Plex match without searching for another title.

**Improved**

- Match and refresh results replace stale titles, descriptions, ratings, and artwork in the native grid and bounded metadata cache immediately.
- Search and mutation controls expose clear working and failure states and block duplicate commands while Plex is responding.
- Poster-only repair preserves the current title, description, metadata match, watch state, collections, and media file.

**Fixed**

- Movies and TV shows can now be repaired without switching to the web client.
- Refreshed metadata no longer leaves the current Android grid showing the previous poster or description.
- Android sends only server-returned poster candidates; the shared server still rejects untrusted artwork hosts and unsupported media types.

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

Fix Match and metadata refresh require Plex Open Web 0.21.0 or newer. Skip Intro requires Plex Open Web 0.25.0 or newer.

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

`Save offline` downloads the complete browser-friendly MP4 plus supported VTT subtitle files into this Android app's private storage. Playback always prefers that local file and does not wait for a network metadata request. Select a downloaded title and use `Remove offline`, or use `Delete offline` in the player, to remove only those Android-local copies after confirmation. Neither action deletes the prepared remote stream or the original Plex library media.

`Prepare stream` creates a browser-friendly copy on the Plex Open Web host. It can improve compatibility and seeking, but playback still travels over the internet and can buffer on a mobile connection.

The separate `Original ZIP` action saves a ZIP containing the untouched original video and available subtitles in the public Android Downloads folder. It is a portable file export and does not mark the title as available in the app's offline player.

## Notes

- Offline saves remain in app-private storage until explicitly deleted, the app is uninstalled, or Android clears the app's storage.
- HTTP server URLs are allowed because many tailnet and LAN deployments use direct HTTP.
