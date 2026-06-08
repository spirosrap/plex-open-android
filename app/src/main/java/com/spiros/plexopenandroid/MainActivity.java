package com.spiros.plexopenandroid;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends android.app.Activity {
    private static final int PAGE_SIZE = 60;
    private static final long PROGRESS_INTERVAL_MS = 15_000L;
    private static final int IMMERSIVE_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final ArrayDeque<ScreenState> backStack = new ArrayDeque<>();
    private final List<Models.MediaItem> currentItems = new ArrayList<>();

    private PlexApiClient api;
    private ImageLoader imageLoader;
    private DeviceCache deviceCache;
    private MediaAdapter adapter;
    private GridLayoutManager gridLayoutManager;
    private SharedPreferences prefs;

    private LinearLayout root;
    private LinearLayout librariesRow;
    private TextView titleView;
    private TextView subtitleView;
    private TextView statusView;
    private Button recentButton;
    private Button allButton;
    private Button unwatchedButton;
    private Button loadMoreButton;
    private Button backButton;
    private Spinner sortSpinner;

    private List<Models.Library> libraries = new ArrayList<>();
    private Models.Library selectedLibrary;
    private String currentTitle = "Library";
    private String viewMode = "all";
    private String sortMode = "addedAt:desc";
    private int loadedCount = 0;
    private int totalCount = 0;
    private boolean libraryMode = false;
    private boolean suppressSortEvent = false;

    private Dialog playerDialog;
    private LinearLayout playerControls;
    private PlayerView playerView;
    private ExoPlayer player;
    private Models.MediaItem playerItem;
    private TextView playbackModeView;
    private Button saveButton;
    private Button deleteSavedButton;
    private Button saveDeviceButton;
    private Button deleteDeviceButton;
    private Button resizeButton;
    private Button closeOverlayButton;
    private boolean usingSavedPlayback = false;
    private boolean usingDevicePlayback = false;
    private boolean fillVideo = true;
    private Runnable hidePlayerControlsRunnable;
    private Runnable progressTicker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyFullscreen();
        api = new PlexApiClient(this);
        imageLoader = new ImageLoader(api);
        deviceCache = new DeviceCache(this, api.gson());
        prefs = getSharedPreferences(PlexApiClient.PREFS, MODE_PRIVATE);

        if (api.hasBaseUrl()) {
            checkExistingSession();
        } else {
            showLogin(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFullscreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyFullscreen();
        }
    }

    @Override
    protected void onDestroy() {
        stopProgressReporting();
        releasePlayer();
        imageLoader.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (playerDialog != null && playerDialog.isShowing()) {
            playerDialog.dismiss();
            return;
        }
        if (!backStack.isEmpty()) {
            restoreScreen(backStack.pop());
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (gridLayoutManager != null) {
            gridLayoutManager.setSpanCount(spanCount());
        }
    }

    private void checkExistingSession() {
        showLoadingShell("Connecting...");
        runTask(null, () -> api.get("/api/me", Models.MeResponse.class), me -> {
            if (me != null && me.authenticated) {
                showApp();
            } else {
                showLogin(null);
            }
        }, error -> showLogin(error.getMessage()));
    }

    private void showLogin(@Nullable String message) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(colorPaper());

        TextView mark = text("PO", 28, true);
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(colorAccent());
        root.addView(mark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Plex Open", 28, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = text("Connect to your Plex Open Web server", 14, false);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(colorMuted());
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.setMargins(0, dp(4), 0, dp(22));
        root.addView(hint, hintParams);

        EditText url = edit("Server URL");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(api.baseUrl());
        root.addView(url, fieldParams());

        EditText password = edit("Password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        root.addView(password, fieldParams());

        TextView error = text(message == null ? "" : message, 13, false);
        error.setTextColor(Color.rgb(170, 36, 36));
        root.addView(error, fieldParams());

        Button signIn = button("Sign in");
        root.addView(signIn, fieldParams());

        View.OnClickListener login = view -> {
            String serverUrl = url.getText().toString();
            String pass = password.getText().toString();
            if (serverUrl.trim().isEmpty()) {
                error.setText("Enter the server URL.");
                return;
            }
            signIn.setEnabled(false);
            error.setText("Signing in...");
            api.clearSession();
            api.setBaseUrl(serverUrl);
            runTask(null, () -> {
                JsonObject payload = new JsonObject();
                payload.addProperty("password", pass);
                Models.LoginResponse response = api.post("/api/login", payload, Models.LoginResponse.class);
                if (response == null || !response.authenticated) {
                    throw new IOException("Sign in failed");
                }
                return response;
            }, ok -> showApp(), throwable -> {
                signIn.setEnabled(true);
                error.setText(throwable.getMessage());
            });
        };
        signIn.setOnClickListener(login);
        password.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                login.onClick(signIn);
                return true;
            }
            return false;
        });
        setContentView(root);
        applyFullscreen();
    }

    private void showLoadingShell(String message) {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setGravity(Gravity.CENTER);
        shell.setBackgroundColor(colorPaper());
        ProgressBar progress = new ProgressBar(this);
        TextView label = text(message, 16, true);
        label.setGravity(Gravity.CENTER);
        shell.addView(progress);
        shell.addView(label);
        setContentView(shell);
        applyFullscreen();
    }

    private void showApp() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorPaper());
        root.setPadding(dp(12), dp(12), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("Plex Open", 22, true);
        brand.setTextColor(colorInk());
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button logout = button("Sign out");
        logout.setOnClickListener(v -> logout());
        header.addView(logout);
        root.addView(header);

        subtitleView = text("Media server", 13, false);
        subtitleView.setTextColor(colorMuted());
        root.addView(subtitleView);

        HorizontalScrollView libraryScroll = new HorizontalScrollView(this);
        libraryScroll.setHorizontalScrollBarEnabled(false);
        librariesRow = new LinearLayout(this);
        librariesRow.setOrientation(LinearLayout.HORIZONTAL);
        libraryScroll.addView(librariesRow);
        root.addView(libraryScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        backButton = button("<");
        backButton.setOnClickListener(v -> {
            if (!backStack.isEmpty()) {
                restoreScreen(backStack.pop());
            }
        });
        nav.addView(backButton, new LinearLayout.LayoutParams(dp(44), dp(40)));
        titleView = text(currentTitle, 22, true);
        nav.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(nav);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText search = edit("Search");
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        Button searchButton = button("Search");
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1));
        searchRow.addView(searchButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(searchRow);

        View.OnClickListener doSearch = v -> search(search.getText().toString());
        searchButton.setOnClickListener(doSearch);
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch.onClick(searchButton);
                return true;
            }
            return false;
        });

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        recentButton = button("Recent");
        allButton = button("All");
        unwatchedButton = button("Unwatched");
        recentButton.setOnClickListener(v -> changeView("recent"));
        allButton.setOnClickListener(v -> changeView("all"));
        unwatchedButton.setOnClickListener(v -> changeView("unwatched"));
        toolbar.addView(recentButton);
        toolbar.addView(allButton);
        toolbar.addView(unwatchedButton);

        sortSpinner = new Spinner(this);
        ArrayAdapter<String> sortAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{
                "Recently added", "Title", "Year", "Recently watched"
        });
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sortSpinner.setAdapter(sortAdapter);
        sortSpinner.setSelection(sortIndexFor(sortMode));
        sortSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (suppressSortEvent) {
                    return;
                }
                String next = sortValueAt(position);
                if (!next.equals(sortMode)) {
                    sortMode = next;
                    if (selectedLibrary != null && libraryMode) {
                        loadLibrary(false);
                    }
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        toolbar.addView(sortSpinner, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(toolbar);

        statusView = text("", 13, false);
        statusView.setTextColor(colorMuted());
        root.addView(statusView);

        RecyclerView recycler = new RecyclerView(this);
        recycler.setHasFixedSize(false);
        gridLayoutManager = new GridLayoutManager(this, spanCount());
        recycler.setLayoutManager(gridLayoutManager);
        adapter = new MediaAdapter(imageLoader, this::openItem);
        recycler.setAdapter(adapter);
        root.addView(recycler, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        loadMoreButton = button("Load more");
        loadMoreButton.setOnClickListener(v -> loadLibrary(true));
        root.addView(loadMoreButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        setContentView(root);
        applyFullscreen();
        updateToolbarState();
        loadServerAndLibraries();
    }

    private void loadServerAndLibraries() {
        runTask("Loading server...", () -> {
            LoadedStart start = new LoadedStart();
            start.server = api.get("/api/server", Models.ServerInfo.class);
            Models.LibrariesResponse response = api.get("/api/libraries", Models.LibrariesResponse.class);
            start.libraries = response == null || response.libraries == null ? new ArrayList<>() : response.libraries;
            return start;
        }, start -> {
            if (start.server != null && start.server.friendlyName != null) {
                subtitleView.setText(start.server.friendlyName);
            }
            libraries = start.libraries;
            renderLibraries();
            if (!libraries.isEmpty()) {
                selectLibrary(libraries.get(0));
            } else {
                setStatus("No libraries found.");
            }
        });
    }

    private void renderLibraries() {
        librariesRow.removeAllViews();
        for (Models.Library library : libraries) {
            Button item = button(library.label());
            item.setOnClickListener(v -> selectLibrary(library));
            if (selectedLibrary != null && library.key != null && library.key.equals(selectedLibrary.key)) {
                item.setTextColor(Color.WHITE);
                item.setBackgroundColor(colorInk());
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            params.setMargins(0, dp(8), dp(8), dp(8));
            librariesRow.addView(item, params);
        }
    }

    private void selectLibrary(Models.Library library) {
        selectedLibrary = library;
        currentTitle = library.label();
        libraryMode = true;
        loadedCount = 0;
        totalCount = 0;
        backStack.clear();
        renderLibraries();
        loadLibrary(false);
    }

    private void changeView(String mode) {
        if (mode.equals(viewMode)) {
            return;
        }
        viewMode = mode;
        if (selectedLibrary != null) {
            backStack.clear();
            libraryMode = true;
            currentTitle = selectedLibrary.label();
            loadLibrary(false);
        }
        updateToolbarState();
    }

    private void loadLibrary(boolean append) {
        if (selectedLibrary == null || selectedLibrary.key == null) {
            return;
        }
        int start = append ? loadedCount : 0;
        String path = "/api/library/" + enc(selectedLibrary.key)
                + "?view=" + enc(viewMode)
                + "&sort=" + enc(sortMode)
                + "&start=" + start
                + "&limit=" + PAGE_SIZE;
        runTask(append ? "Loading more..." : "Loading " + selectedLibrary.label() + "...", () -> api.get(path, Models.LibraryResponse.class), response -> {
            if (!append) {
                currentItems.clear();
            }
            if (response != null && response.items != null) {
                currentItems.addAll(response.items);
                loadedCount = currentItems.size();
                totalCount = response.totalSize == null ? currentItems.size() : response.totalSize;
            }
            libraryMode = true;
            currentTitle = selectedLibrary.label();
            renderCurrent();
        });
    }

    private void openItem(Models.MediaItem item) {
        if (item.canOpen()) {
            openChildren(item);
        } else {
            openDetails(item);
        }
    }

    private void openChildren(Models.MediaItem item) {
        if (item.ratingKey == null) {
            return;
        }
        pushScreen();
        runTask("Opening " + item.displayTitle() + "...", () -> api.get("/api/children/" + enc(item.ratingKey), Models.ChildrenResponse.class), response -> {
            currentItems.clear();
            if (response != null && response.items != null) {
                currentItems.addAll(response.items);
            }
            currentTitle = item.displayTitle();
            libraryMode = false;
            loadedCount = currentItems.size();
            totalCount = currentItems.size();
            renderCurrent();
        });
    }

    private void search(String query) {
        String text = query == null ? "" : query.trim();
        if (text.length() < 2) {
            setStatus("Search needs at least two characters.");
            return;
        }
        pushScreen();
        runTask("Searching...", () -> api.get("/api/search?query=" + enc(text), Models.SearchResponse.class), response -> {
            currentItems.clear();
            if (response != null && response.items != null) {
                currentItems.addAll(response.items);
            }
            currentTitle = "Search";
            libraryMode = false;
            loadedCount = currentItems.size();
            totalCount = currentItems.size();
            renderCurrent();
        });
    }

    private void openDetails(Models.MediaItem item) {
        if (item.ratingKey == null) {
            showDetailsDialog(item);
            return;
        }
        runTask("Loading details...", () -> hydrate(item), this::showDetailsDialog);
    }

    private void showDetailsDialog(Models.MediaItem item) {
        Dialog dialog = new Dialog(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(16), dp(16), dp(16), dp(16));
        shell.setBackgroundColor(colorPaper());

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shell.addView(poster, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        if (item.posterUrl != null) {
            imageLoader.load(item.posterUrl, poster);
        }

        TextView title = text(item.displayTitle(), 22, true);
        title.setPadding(0, dp(12), 0, 0);
        shell.addView(title);

        TextView meta = text(item.metaLine(), 13, false);
        meta.setTextColor(colorMuted());
        shell.addView(meta);

        TextView summary = text(Models.nonEmpty(item.summary, ""), 14, false);
        summary.setPadding(0, dp(12), 0, dp(12));
        shell.addView(summary);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (item.canPlay()) {
            Button play = button("Play");
            play.setOnClickListener(v -> {
                dialog.dismiss();
                playItem(item);
            });
            actions.addView(play, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (item.canOpen()) {
            Button open = button("Open");
            open.setOnClickListener(v -> {
                dialog.dismiss();
                openChildren(item);
            });
            actions.addView(open, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (item.canPlay()) {
            Button subtitles = button("Subtitles");
            subtitles.setOnClickListener(v -> openSubtitleDialog(item));
            actions.addView(subtitles, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        shell.addView(actions);

        Button close = button("Close");
        close.setOnClickListener(v -> dialog.dismiss());
        shell.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(shell);
        dialog.setContentView(scrollView);
        dialog.show();
        sizeDialog(dialog, 0.94f, 0.88f);
    }

    private void playItem(Models.MediaItem item) {
        runTask("Preparing playback...", () -> {
            Models.MediaItem hydrated = hydrate(item);
            refreshSavedPlayback(hydrated);
            return hydrated;
        }, this::showPlayer);
    }

    private void showPlayer(Models.MediaItem item) {
        playerItem = item;
        fillVideo = true;
        playerDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        playerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(Color.BLACK);

        playerControls = new LinearLayout(this);
        playerControls.setOrientation(LinearLayout.HORIZONTAL);
        playerControls.setGravity(Gravity.CENTER_VERTICAL);
        playerControls.setPadding(dp(8), dp(6), dp(8), dp(6));
        playerControls.setBackgroundColor(Color.argb(180, 20, 20, 20));

        playbackModeView = text("", 12, true);
        playbackModeView.setTextColor(Color.WHITE);
        playerControls.addView(playbackModeView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        saveButton = compactButton("Save");
        deleteSavedButton = compactButton("Delete saved");
        saveDeviceButton = compactButton("Save device");
        deleteDeviceButton = compactButton("Delete device");
        resizeButton = compactButton("Fit");
        Button subtitles = compactButton("Find");
        Button close = compactButton("X");
        closeOverlayButton = compactButton("X");

        saveButton.setOnClickListener(v -> saveServerCopy(true));
        deleteSavedButton.setOnClickListener(v -> deleteServerCopy());
        saveDeviceButton.setOnClickListener(v -> saveDeviceCopy());
        deleteDeviceButton.setOnClickListener(v -> deleteDeviceCopy());
        resizeButton.setOnClickListener(v -> {
            fillVideo = !fillVideo;
            applyPlayerResizeMode();
            showPlayerControlsTemporarily();
        });
        subtitles.setOnClickListener(v -> {
            showPlayerControlsTemporarily();
            openSubtitleDialog(playerItem);
        });
        close.setOnClickListener(v -> playerDialog.dismiss());

        playerControls.addView(saveButton);
        playerControls.addView(deleteSavedButton);
        playerControls.addView(saveDeviceButton);
        playerControls.addView(deleteDeviceButton);
        playerControls.addView(resizeButton);
        playerControls.addView(subtitles);
        playerControls.addView(close);

        playerView = new PlayerView(this);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setShowSubtitleButton(true);
        playerView.setOnTouchListener((view, event) -> {
            showPlayerControlsTemporarily();
            return false;
        });
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            if (visibility == View.VISIBLE) {
                showPlayerControlsTemporarily();
            } else {
                setPlayerControlsVisible(false);
            }
        });
        applyPlayerResizeMode();
        shell.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        closeOverlayButton.setOnClickListener(v -> playerDialog.dismiss());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.RIGHT);
        closeParams.setMargins(0, dp(10), dp(10), 0);
        shell.addView(closeOverlayButton, closeParams);
        // Keep playback clean: the player is closed with Back, and secondary
        // actions stay off-screen instead of occupying the video surface.

        playerDialog.setContentView(shell);
        playerDialog.setOnDismissListener(dialog -> {
            reportProgress("stopped", true);
            stopProgressReporting();
            cancelPlayerControlsHide();
            releasePlayer();
            playerControls = null;
            closeOverlayButton = null;
            playerDialog = null;
        });
        playerDialog.show();
        Window window = playerDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            applyFullscreen(window);
        }
        showPlayerControlsTemporarily();
        playPreferredSource(resumeTimeFor(item), true);
    }

    private void playPreferredSource(long resumeMs, boolean autoplay) {
        if (playerItem == null) {
            return;
        }
        DeviceCache.Entry deviceEntry = deviceCache.status(playerItem);
        try {
            if (deviceEntry != null) {
                usingDevicePlayback = true;
                usingSavedPlayback = false;
                playbackModeView.setText("Device");
                playMedia(deviceCache.localMediaItem(playerItem, deviceEntry), null, resumeMs, autoplay);
            } else {
                String stream = streamUrlFor(playerItem);
                if (stream == null) {
                    throw new IOException("No playable stream");
                }
                usingDevicePlayback = false;
                usingSavedPlayback = playerItem.savedPlayback != null && playerItem.savedPlayback.ready && stream.equals(playerItem.savedPlayback.streamUrl);
                playbackModeView.setText(usingSavedPlayback ? "Saved" : "Live");
                playMedia(streamingMediaItem(playerItem, stream), stream, resumeMs, autoplay);
            }
            updatePlayerControls();
        } catch (IOException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void playMedia(androidx.media3.common.MediaItem mediaItem, @Nullable String remoteUrl, long resumeMs, boolean autoplay) throws IOException {
        releasePlayer();
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("PlexOpenAndroid/0.1");
        if (remoteUrl != null) {
            String cookie = api.cookieHeaderFor(remoteUrl);
            if (!cookie.isEmpty()) {
                Map<String, String> headers = new HashMap<>();
                headers.put("Cookie", cookie);
                httpFactory.setDefaultRequestProperties(headers);
            }
        }
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    startProgressReporting();
                    schedulePlayerControlsHide();
                } else {
                    reportProgress("paused", false);
                    stopProgressReporting();
                    showPlayerControlsTemporarily();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    reportProgress("ended", true);
                    stopProgressReporting();
                }
            }
        });
        playerView.setPlayer(player);
        applyPlayerResizeMode();
        player.setMediaItem(mediaItem);
        player.prepare();
        if (resumeMs > 0) {
            player.seekTo(resumeMs);
        }
        player.setPlayWhenReady(autoplay);
    }

    private androidx.media3.common.MediaItem streamingMediaItem(Models.MediaItem item, String streamPath) throws IOException {
        List<androidx.media3.common.MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        List<Models.Subtitle> supportedSubtitles = supportedSubtitles(item);
        int preferredSubtitle = preferredSubtitleIndex(supportedSubtitles);
        for (int index = 0; index < supportedSubtitles.size(); index++) {
            Models.Subtitle subtitle = supportedSubtitles.get(index);
            if (subtitle.subtitleUrl != null && !subtitle.subtitleUrl.isEmpty()) {
                int flags = 0;
                if (subtitle.selected || subtitle.defaultValue || index == preferredSubtitle) {
                    flags |= C.SELECTION_FLAG_DEFAULT;
                }
                if (subtitle.forced) {
                    flags |= C.SELECTION_FLAG_FORCED;
                }
                subtitles.add(new androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(api.absoluteUrl(subtitle.subtitleUrl)))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(subtitle.srclang == null ? "und" : subtitle.srclang)
                        .setLabel(subtitle.label())
                        .setSelectionFlags(flags)
                        .build());
            }
        }
        return new androidx.media3.common.MediaItem.Builder()
                .setUri(api.absoluteUrl(streamPath))
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(item.displayTitle()).build())
                .setSubtitleConfigurations(subtitles)
                .build();
    }

    private List<Models.Subtitle> supportedSubtitles(Models.MediaItem item) {
        List<Models.Subtitle> result = new ArrayList<>();
        if (item == null || item.subtitles == null) {
            return result;
        }
        for (Models.Subtitle subtitle : item.subtitles) {
            if (subtitle.supported && subtitle.subtitleUrl != null && !subtitle.subtitleUrl.isEmpty()) {
                result.add(subtitle);
            }
        }
        return result;
    }

    private int preferredSubtitleIndex(List<Models.Subtitle> subtitles) {
        int greek = -1;
        for (int index = 0; index < subtitles.size(); index++) {
            Models.Subtitle subtitle = subtitles.get(index);
            if (subtitle.selected || subtitle.defaultValue || subtitle.forced) {
                return index;
            }
            String language = subtitle.srclang == null ? "" : subtitle.srclang;
            String code = subtitle.languageCode == null ? "" : subtitle.languageCode;
            if (greek < 0 && ("el".equalsIgnoreCase(language) || "ell".equalsIgnoreCase(code) || "gre".equalsIgnoreCase(code))) {
                greek = index;
            }
        }
        if (greek >= 0) {
            return greek;
        }
        return subtitles.isEmpty() ? -1 : 0;
    }

    private void saveServerCopy(boolean switchWhenReady) {
        if (playerItem == null) {
            return;
        }
        long resume = currentPositionMs();
        boolean autoplay = player != null && player.isPlaying();
        runTask("Saving server copy...", () -> waitForSavedPlayback(playerItem), saved -> {
            playerItem.savedPlayback = saved;
            Toast.makeText(this, "Saved server copy is ready.", Toast.LENGTH_SHORT).show();
            if (switchWhenReady) {
                playPreferredSource(resume, autoplay);
            } else {
                updatePlayerControls();
            }
        });
    }

    private void deleteServerCopy() {
        if (playerItem == null || playerItem.ratingKey == null) {
            return;
        }
        long resume = currentPositionMs();
        boolean autoplay = player != null && player.isPlaying();
        runTask("Deleting saved copy...", () -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("ratingKey", playerItem.ratingKey);
            payload.addProperty("action", "delete");
            Models.SavedPlaybackResponse response = api.post("/api/saved-playback", payload, Models.SavedPlaybackResponse.class);
            return response == null ? null : response.savedPlayback;
        }, saved -> {
            playerItem.savedPlayback = saved;
            Toast.makeText(this, "Deleted server saved copy.", Toast.LENGTH_SHORT).show();
            if (usingSavedPlayback) {
                playPreferredSource(resume, autoplay);
            } else {
                updatePlayerControls();
            }
        });
    }

    private void saveDeviceCopy() {
        if (playerItem == null) {
            return;
        }
        long resume = currentPositionMs();
        boolean autoplay = player != null && player.isPlaying();
        runTask("Saving to device...", () -> {
            Models.SavedPlayback saved = waitForSavedPlayback(playerItem);
            playerItem.savedPlayback = saved;
            return deviceCache.save(api, playerItem, (bytes, total) -> {
                if (total > 0) {
                    int percent = (int) Math.min(99, Math.max(1, bytes * 100 / total));
                    main.post(() -> setStatus("Saving to device... " + percent + "%"));
                }
            });
        }, entry -> {
            Toast.makeText(this, "Saved on this device.", Toast.LENGTH_SHORT).show();
            playPreferredSource(resume, autoplay);
        });
    }

    private void deleteDeviceCopy() {
        if (playerItem == null) {
            return;
        }
        long resume = currentPositionMs();
        boolean autoplay = player != null && player.isPlaying();
        deviceCache.delete(playerItem);
        Toast.makeText(this, "Deleted device copy.", Toast.LENGTH_SHORT).show();
        if (usingDevicePlayback) {
            playPreferredSource(resume, autoplay);
        } else {
            updatePlayerControls();
        }
    }

    private Models.SavedPlayback waitForSavedPlayback(Models.MediaItem item) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        Models.SavedPlaybackResponse started = api.post("/api/saved-playback", payload, Models.SavedPlaybackResponse.class);
        Models.SavedPlayback saved = started == null ? null : started.savedPlayback;
        int attempts = 0;
        while (saved != null && "saving".equals(saved.state) && attempts < 720) {
            attempts += 1;
            Thread.sleep(2500);
            Models.SavedPlaybackResponse status = api.get("/api/saved-playback?ratingKey=" + enc(item.ratingKey), Models.SavedPlaybackResponse.class);
            saved = status == null ? null : status.savedPlayback;
        }
        if (saved == null || !saved.ready) {
            throw new IOException(saved != null && saved.message != null ? saved.message : "Saved copy is not ready");
        }
        return saved;
    }

    private void updatePlayerControls() {
        if (playerItem == null || saveButton == null) {
            return;
        }
        boolean savedReady = playerItem.savedPlayback != null && playerItem.savedPlayback.ready;
        DeviceCache.Entry deviceEntry = deviceCache.status(playerItem);
        saveButton.setEnabled(playerItem.ratingKey != null && playerItem.partKey != null);
        saveButton.setText(savedReady ? (usingSavedPlayback ? "Saved" : "Play saved") : "Save");
        deleteSavedButton.setVisibility(savedReady ? View.VISIBLE : View.GONE);
        saveDeviceButton.setVisibility(savedReady && deviceEntry == null ? View.VISIBLE : View.GONE);
        deleteDeviceButton.setVisibility(deviceEntry != null ? View.VISIBLE : View.GONE);
    }

    private void applyPlayerResizeMode() {
        if (playerView != null) {
            playerView.setResizeMode(fillVideo ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        if (resizeButton != null) {
            resizeButton.setText(fillVideo ? "Fit" : "Fill");
        }
    }

    private void showPlayerControlsTemporarily() {
        setPlayerControlsVisible(true);
        schedulePlayerControlsHide();
    }

    private void setPlayerControlsVisible(boolean visible) {
        if (playerControls != null) {
            playerControls.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (closeOverlayButton != null) {
            closeOverlayButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void schedulePlayerControlsHide() {
        cancelPlayerControlsHide();
        hidePlayerControlsRunnable = () -> setPlayerControlsVisible(false);
        main.postDelayed(hidePlayerControlsRunnable, 2200L);
    }

    private void cancelPlayerControlsHide() {
        if (hidePlayerControlsRunnable != null) {
            main.removeCallbacks(hidePlayerControlsRunnable);
            hidePlayerControlsRunnable = null;
        }
    }

    private void openSubtitleDialog(Models.MediaItem item) {
        if (item == null || item.ratingKey == null) {
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(16), dp(16), dp(16), dp(16));
        shell.setBackgroundColor(colorPaper());

        TextView title = text("Find subtitles", 22, true);
        shell.addView(title);

        Spinner language = new Spinner(this);
        ArrayAdapter<String> languages = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"Greek", "English", "All languages"});
        languages.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        language.setAdapter(languages);
        shell.addView(language, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        EditText query = edit("Title or release");
        query.setText(item.subtitleQueryTitle());
        shell.addView(query, fieldParams());

        Button search = button("Search");
        shell.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TextView status = text("", 13, false);
        status.setTextColor(colorMuted());
        shell.addView(status);

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button close = button("Close");
        close.setOnClickListener(v -> dialog.dismiss());
        shell.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        search.setOnClickListener(v -> {
            results.removeAllViews();
            status.setText("Searching...");
            String lang = languageValue(language.getSelectedItemPosition());
            String path = "/api/subtitle-search?ratingKey=" + enc(item.ratingKey)
                    + "&language=" + enc(lang)
                    + "&query=" + enc(query.getText().toString());
            runTask(null, () -> api.get(path, Models.SubtitleSearchResponse.class), response -> {
                results.removeAllViews();
                if (response == null || !response.configured) {
                    status.setText(response == null ? "Subtitle search unavailable." : Models.nonEmpty(response.message, "Subtitle search unavailable."));
                    return;
                }
                List<Models.SubtitleResult> found = response.results == null ? Collections.emptyList() : response.results;
                status.setText(found.size() + " subtitles found");
                for (Models.SubtitleResult result : found) {
                    results.addView(subtitleResultRow(item, result, lang, dialog));
                }
            }, error -> status.setText(error.getMessage()));
        });

        dialog.setContentView(shell);
        dialog.show();
        sizeDialog(dialog, 0.96f, 0.90f);
    }

    private View subtitleResultRow(Models.MediaItem item, Models.SubtitleResult result, String language, Dialog dialog) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView title = text(result.title(), 15, true);
        TextView meta = text(result.meta(), 12, false);
        meta.setTextColor(colorMuted());
        Button download = button("Download");
        download.setOnClickListener(v -> {
            download.setEnabled(false);
            download.setText("Saving...");
            runTask(null, () -> {
                JsonObject payload = new JsonObject();
                payload.addProperty("ratingKey", item.ratingKey);
                payload.addProperty("fileId", result.fileId);
                payload.addProperty("language", language);
                Models.SubtitleDownloadResponse response = api.post("/api/subtitle-download", payload, Models.SubtitleDownloadResponse.class);
                if (response == null || !response.ok) {
                    throw new IOException(response == null ? "Subtitle save failed" : Models.nonEmpty(response.message, Models.nonEmpty(response.error, "Subtitle save failed")));
                }
                return hydrate(item);
            }, hydrated -> {
                item.subtitles = hydrated.subtitles;
                if (playerItem != null && item.ratingKey.equals(playerItem.ratingKey)) {
                    playerItem = hydrated;
                    long resume = currentPositionMs();
                    boolean autoplay = player != null && player.isPlaying();
                    playPreferredSource(resume, autoplay);
                }
                Toast.makeText(this, "Subtitle saved.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }, error -> {
                download.setEnabled(true);
                download.setText("Retry");
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            });
        });
        row.addView(title);
        row.addView(meta);
        row.addView(download, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return row;
    }

    private Models.MediaItem hydrate(Models.MediaItem item) throws IOException {
        if (item.ratingKey == null) {
            return item;
        }
        Models.ItemResponse response = api.get("/api/metadata/" + enc(item.ratingKey), Models.ItemResponse.class);
        return response != null && response.item != null ? response.item : item;
    }

    private void refreshSavedPlayback(Models.MediaItem item) throws IOException {
        if (item.ratingKey == null) {
            return;
        }
        Models.SavedPlaybackResponse response = api.get("/api/saved-playback?ratingKey=" + enc(item.ratingKey), Models.SavedPlaybackResponse.class);
        if (response != null && response.savedPlayback != null) {
            item.savedPlayback = response.savedPlayback;
        }
    }

    private String streamUrlFor(Models.MediaItem item) {
        if (item.savedPlayback != null && item.savedPlayback.ready && item.savedPlayback.streamUrl != null) {
            return item.savedPlayback.streamUrl;
        }
        if (item.playback != null) {
            if (item.playback.audioTranscodeRequired && item.playback.compatibleStreamUrl != null) {
                return item.playback.compatibleStreamUrl;
            }
            if (item.playback.directStreamUrl != null) {
                return item.playback.directStreamUrl;
            }
            if (item.playback.compatibleStreamUrl != null) {
                return item.playback.compatibleStreamUrl;
            }
        }
        if (item.compatibleStreamUrl != null) {
            return item.compatibleStreamUrl;
        }
        return item.streamUrl;
    }

    private void reportProgress(String state, boolean force) {
        if (playerItem == null || playerItem.ratingKey == null || player == null) {
            return;
        }
        long position = currentPositionMs();
        long duration = durationMs();
        rememberLocalProgress(playerItem.ratingKey, position, duration);
        if (!force && position < 60_000L) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", playerItem.ratingKey);
        payload.addProperty("timeMs", position);
        payload.addProperty("durationMs", duration);
        payload.addProperty("state", state);
        io.execute(() -> {
            try {
                api.post("/api/playback-progress", payload, Models.PlaybackProgressResponse.class);
            } catch (IOException ignored) {
                // Progress is best effort.
            }
        });
    }

    private void startProgressReporting() {
        stopProgressReporting();
        progressTicker = () -> {
            reportProgress("playing", false);
            main.postDelayed(progressTicker, PROGRESS_INTERVAL_MS);
        };
        main.postDelayed(progressTicker, PROGRESS_INTERVAL_MS);
    }

    private void stopProgressReporting() {
        if (progressTicker != null) {
            main.removeCallbacks(progressTicker);
            progressTicker = null;
        }
    }

    private long currentPositionMs() {
        return player == null ? 0L : Math.max(0L, player.getCurrentPosition());
    }

    private long durationMs() {
        if (player != null && player.getDuration() != C.TIME_UNSET) {
            return Math.max(0L, player.getDuration());
        }
        if (playerItem != null && playerItem.duration != null) {
            return playerItem.duration;
        }
        if (playerItem != null && playerItem.media != null && playerItem.media.duration != null) {
            return playerItem.media.duration;
        }
        return 0L;
    }

    private long resumeTimeFor(Models.MediaItem item) {
        long plex = item.viewOffset == null ? 0L : item.viewOffset;
        long local = prefs.getLong("progress:" + item.ratingKey, 0L);
        return Math.max(plex, local);
    }

    private void rememberLocalProgress(String ratingKey, long timeMs, long durationMs) {
        if (ratingKey == null || durationMs <= 0) {
            return;
        }
        long remaining = durationMs - timeMs;
        if (timeMs >= durationMs * 0.9 || remaining <= 120_000L) {
            prefs.edit().remove("progress:" + ratingKey).apply();
        } else {
            prefs.edit().putLong("progress:" + ratingKey, timeMs).apply();
        }
    }

    private void releasePlayer() {
        if (playerView != null) {
            playerView.setPlayer(null);
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void logout() {
        runTask("Signing out...", () -> {
            api.post("/api/logout", new JsonObject(), Models.MeResponse.class);
            api.clearSession();
            return true;
        }, ok -> showLogin(null), error -> {
            api.clearSession();
            showLogin(null);
        });
    }

    private void renderCurrent() {
        adapter.submit(currentItems);
        titleView.setText(currentTitle);
        updateToolbarState();
        int shown = currentItems.size();
        if (shown == 0) {
            setStatus("No items.");
        } else if (libraryMode && totalCount > shown) {
            setStatus(shown + " of " + totalCount);
        } else {
            setStatus(shown + " items");
        }
        loadMoreButton.setVisibility(libraryMode && totalCount > shown ? View.VISIBLE : View.GONE);
    }

    private void updateToolbarState() {
        if (backButton != null) {
            backButton.setVisibility(backStack.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
        styleModeButton(recentButton, "recent".equals(viewMode));
        styleModeButton(allButton, "all".equals(viewMode));
        styleModeButton(unwatchedButton, "unwatched".equals(viewMode));
        if (sortSpinner != null) {
            suppressSortEvent = true;
            sortSpinner.setSelection(sortIndexFor(sortMode));
            suppressSortEvent = false;
        }
    }

    private void styleModeButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setTextColor(selected ? Color.WHITE : colorInk());
        button.setBackgroundColor(selected ? colorAccent() : Color.rgb(232, 230, 222));
    }

    private void setStatus(String message) {
        if (statusView != null) {
            statusView.setText(message == null ? "" : message);
        }
    }

    private void pushScreen() {
        ScreenState state = new ScreenState();
        state.title = currentTitle;
        state.items = new ArrayList<>(currentItems);
        state.selectedLibrary = selectedLibrary;
        state.viewMode = viewMode;
        state.sortMode = sortMode;
        state.loadedCount = loadedCount;
        state.totalCount = totalCount;
        state.libraryMode = libraryMode;
        backStack.push(state);
    }

    private void restoreScreen(ScreenState state) {
        currentTitle = state.title;
        currentItems.clear();
        currentItems.addAll(state.items);
        selectedLibrary = state.selectedLibrary;
        viewMode = state.viewMode;
        sortMode = state.sortMode;
        loadedCount = state.loadedCount;
        totalCount = state.totalCount;
        libraryMode = state.libraryMode;
        renderLibraries();
        renderCurrent();
    }

    private <T> void runTask(@Nullable String busy, Task<T> task, Success<T> success) {
        runTask(busy, task, success, error -> {
            setStatus(error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private <T> void runTask(@Nullable String busy, Task<T> task, Success<T> success, Failure failure) {
        if (busy != null) {
            setStatus(busy);
        }
        io.execute(() -> {
            try {
                T result = task.call();
                main.post(() -> success.accept(result));
            } catch (Throwable error) {
                main.post(() -> failure.accept(error));
            }
        });
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(colorInk());
        if (bold) {
            textView.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return textView;
    }

    private EditText edit(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setTextColor(colorInk());
        editText.setHintTextColor(colorMuted());
        editText.setSingleLine(false);
        return editText;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(colorInk());
        return button;
    }

    private Button compactButton(String label) {
        Button button = button(label);
        button.setTextSize(11);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.setMargins(0, dp(4), 0, dp(8));
        return params;
    }

    private void sizeDialog(Dialog dialog, float widthFraction, float heightFraction) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        applyFullscreen(window);
        int width = (int) (getResources().getDisplayMetrics().widthPixels * widthFraction);
        int height = (int) (getResources().getDisplayMetrics().heightPixels * heightFraction);
        window.setLayout(width, height);
    }

    private void applyFullscreen() {
        applyFullscreen(getWindow());
    }

    private void applyFullscreen(Window window) {
        if (window == null) {
            return;
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private int spanCount() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp <= 0) {
            return 2;
        }
        return Math.max(2, widthDp / 170);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int colorPaper() {
        return Color.rgb(250, 250, 247);
    }

    private int colorInk() {
        return Color.rgb(21, 21, 21);
    }

    private int colorMuted() {
        return Color.rgb(93, 92, 86);
    }

    private int colorAccent() {
        return Color.rgb(229, 160, 13);
    }

    private static int sortIndexFor(String value) {
        switch (value) {
            case "titleSort":
                return 1;
            case "year:desc":
                return 2;
            case "lastViewedAt:desc":
                return 3;
            case "addedAt:desc":
            default:
                return 0;
        }
    }

    private static String sortValueAt(int index) {
        switch (index) {
            case 1:
                return "titleSort";
            case 2:
                return "year:desc";
            case 3:
                return "lastViewedAt:desc";
            case 0:
            default:
                return "addedAt:desc";
        }
    }

    private static String languageValue(int index) {
        switch (index) {
            case 1:
                return "en";
            case 2:
                return "all";
            case 0:
            default:
                return "el";
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            return "";
        }
    }

    private interface Task<T> extends Callable<T> {
    }

    private interface Success<T> {
        void accept(T value);
    }

    private interface Failure {
        void accept(Throwable error);
    }

    private static final class LoadedStart {
        Models.ServerInfo server;
        List<Models.Library> libraries;
    }

    private static final class ScreenState {
        String title;
        List<Models.MediaItem> items;
        Models.Library selectedLibrary;
        String viewMode;
        String sortMode;
        int loadedCount;
        int totalCount;
        boolean libraryMode;
    }
}
