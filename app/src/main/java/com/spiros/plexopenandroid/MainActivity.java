package com.spiros.plexopenandroid;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public final class MainActivity extends android.app.Activity {
    private static final int PAGE_SIZE = 60;
    private static final long PROGRESS_INTERVAL_MS = 15_000L;
    private static final String PREF_LIBRARY_KEY = "browse_library_key";
    private static final String PREF_VIEW_MODE = "browse_view_mode";
    private static final String PREF_SORT_MODE = "browse_sort_mode";
    private static final String PREF_GENRE_PREFIX = "browse_genre_";
    private static final String PREF_AUTOPLAY_NEXT = "playback_autoplay_next";
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
    private ThemePalette palette;
    private String themeMode;

    private LinearLayout root;
    private LinearLayout librariesRow;
    private TextView titleView;
    private TextView subtitleView;
    private TextView statusView;
    private Button continueButton;
    private Button recentButton;
    private Button allButton;
    private Button unwatchedButton;
    private Button collectionsButton;
    private Button myListButton;
    private Button loadMoreButton;
    private Button backButton;
    private Button scanButton;
    private Button surpriseButton;
    private Spinner genreSpinner;
    private Spinner sortSpinner;

    private List<Models.Library> libraries = new ArrayList<>();
    private List<Models.Genre> genres = new ArrayList<>();
    private final Set<String> myListKeys = new HashSet<>();
    private Models.Library selectedLibrary;
    private String currentTitle = "Library";
    private String viewMode = "all";
    private String sortMode = "addedAt:desc";
    private String genreKey = "";
    private int loadedCount = 0;
    private int totalCount = 0;
    private boolean libraryMode = false;
    private boolean suppressSortEvent = false;
    private boolean suppressGenreEvent = false;
    private boolean genresLoading = false;
    private boolean scanInProgress = false;
    private boolean surpriseInProgress = false;

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
    private LinearLayout episodeContinuationControls;
    private Switch autoplayNextSwitch;
    private Button nextEpisodeButton;
    private Button cancelAutoplayNextButton;
    private Models.EpisodeNeighborsResponse playerNeighbors;
    private boolean usingSavedPlayback = false;
    private boolean usingDevicePlayback = false;
    private boolean fillVideo = true;
    private Runnable hidePlayerControlsRunnable;
    private Runnable progressTicker;
    private Runnable autoplayNextRunnable;
    private int autoplayNextSeconds = 0;
    private boolean playerOverlayControlsVisible = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        prefs = getSharedPreferences(PlexApiClient.PREFS, MODE_PRIVATE);
        viewMode = normalizeViewMode(prefs.getString(PREF_VIEW_MODE, "all"));
        sortMode = normalizeSortMode(prefs.getString(PREF_SORT_MODE, "addedAt:desc"));
        themeMode = ThemePalette.normalize(prefs.getString(ThemePalette.PREF_KEY, ThemePalette.SYSTEM));
        palette = ThemePalette.from(themeMode, getResources().getConfiguration());
        setTheme(palette.dark ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        applyFullscreen();
        api = new PlexApiClient(this);
        imageLoader = new ImageLoader(api);
        deviceCache = new DeviceCache(this, api.gson());

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
        cancelAutoplayNextCountdown();
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

        TextView version = text("Version " + BuildConfig.VERSION_NAME, 12, false);
        version.setGravity(Gravity.CENTER);
        version.setTextColor(colorMuted());
        root.addView(version, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = text("Connect to your Plex Open Web server", 14, false);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(colorMuted());
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.setMargins(0, dp(4), 0, dp(22));
        root.addView(hint, hintParams);

        TextView themeLabel = text("Color theme", 13, true);
        themeLabel.setTextColor(colorMuted());
        root.addView(themeLabel);
        Spinner theme = themeSpinner();
        root.addView(theme, fieldParams());

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
        error.setTextColor(palette.danger);
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
        progress.setIndeterminateTintList(ColorStateList.valueOf(colorAccent()));
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
        LinearLayout brandBlock = new LinearLayout(this);
        brandBlock.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("Plex Open", 22, true);
        brand.setTextColor(colorInk());
        brandBlock.addView(brand);
        TextView version = text("Version " + BuildConfig.VERSION_NAME, 12, false);
        version.setTextColor(colorMuted());
        brandBlock.addView(version);
        header.addView(brandBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button logout = button("Sign out");
        logout.setOnClickListener(v -> logout());
        header.addView(logout);
        root.addView(header);

        subtitleView = text("Media server", 13, false);
        subtitleView.setTextColor(colorMuted());
        root.addView(subtitleView);

        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView themeLabel = text("Color theme", 13, true);
        themeLabel.setTextColor(colorMuted());
        themeRow.addView(themeLabel, new LinearLayout.LayoutParams(0, dp(44), 1));
        Spinner theme = themeSpinner();
        themeRow.addView(theme, new LinearLayout.LayoutParams(dp(156), dp(44)));
        root.addView(themeRow);

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
        scanButton = compactButton("Scan");
        scanButton.setContentDescription("Scan current Plex library");
        scanButton.setOnClickListener(v -> scanCurrentLibrary());
        nav.addView(scanButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
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
        toolbar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout primaryViewButtons = new LinearLayout(this);
        primaryViewButtons.setOrientation(LinearLayout.HORIZONTAL);
        primaryViewButtons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout secondaryViewButtons = new LinearLayout(this);
        secondaryViewButtons.setOrientation(LinearLayout.HORIZONTAL);
        secondaryViewButtons.setGravity(Gravity.CENTER_VERTICAL);
        continueButton = button("Continue");
        recentButton = button("Recent");
        allButton = button("All");
        unwatchedButton = button("Unwatched");
        collectionsButton = button("Collections");
        myListButton = button("My List");
        continueButton.setOnClickListener(v -> changeView("continue"));
        recentButton.setOnClickListener(v -> changeView("recent"));
        allButton.setOnClickListener(v -> changeView("all"));
        unwatchedButton.setOnClickListener(v -> changeView("unwatched"));
        collectionsButton.setOnClickListener(v -> changeView("collections"));
        myListButton.setOnClickListener(v -> changeView("mylist"));
        primaryViewButtons.addView(continueButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        primaryViewButtons.addView(recentButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        primaryViewButtons.addView(allButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        secondaryViewButtons.addView(unwatchedButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        secondaryViewButtons.addView(collectionsButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        secondaryViewButtons.addView(myListButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        toolbar.addView(primaryViewButtons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        toolbar.addView(secondaryViewButtons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        genreSpinner = themedSpinner(new String[]{"All genres"});
        genreSpinner.setContentDescription("Genre");
        genreSpinner.setPrompt("Genre");
        genreSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (suppressGenreEvent || genresLoading) {
                    return;
                }
                String next = genreValueAt(position);
                if (!next.equals(genreKey)) {
                    genreKey = next;
                    persistBrowseContext();
                    if (selectedLibrary != null && libraryMode) {
                        loadLibrary(false);
                    }
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        toolbar.addView(genreSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        sortSpinner = themedSpinner(new String[]{
                "Recently added", "Title", "Year", "Recently watched"
        });
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
                    persistBrowseContext();
                    if (selectedLibrary != null && libraryMode) {
                        loadLibrary(false);
                    }
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(Gravity.CENTER_VERTICAL);
        sortRow.addView(sortSpinner, new LinearLayout.LayoutParams(0, dp(44), 1));
        surpriseButton = button("Surprise me");
        surpriseButton.setOnClickListener(v -> surpriseMe());
        sortRow.addView(surpriseButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        toolbar.addView(sortRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        root.addView(toolbar);

        statusView = text("", 13, false);
        statusView.setTextColor(colorMuted());
        root.addView(statusView);

        RecyclerView recycler = new RecyclerView(this);
        recycler.setHasFixedSize(false);
        gridLayoutManager = new GridLayoutManager(this, spanCount());
        recycler.setLayoutManager(gridLayoutManager);
        adapter = new MediaAdapter(imageLoader, this::openItem, palette);
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
            try {
                Models.MyListResponse myList = api.get("/api/my-list?keysOnly=1", Models.MyListResponse.class);
                start.myListKeys = myList == null || myList.ratingKeys == null ? new ArrayList<>() : myList.ratingKeys;
            } catch (IOException ignored) {
                start.myListKeys = new ArrayList<>();
            }
            return start;
        }, start -> {
            if (start.server != null && start.server.friendlyName != null) {
                subtitleView.setText(start.server.friendlyName);
            }
            myListKeys.clear();
            myListKeys.addAll(start.myListKeys);
            libraries = start.libraries;
            if (!libraries.isEmpty()) {
                String preferredKey = prefs.getString(PREF_LIBRARY_KEY, "");
                Models.Library preferred = null;
                for (Models.Library library : libraries) {
                    if (library.key != null && library.key.equals(preferredKey)) {
                        preferred = library;
                        break;
                    }
                }
                selectLibrary(preferred == null ? libraries.get(0) : preferred);
            } else {
                renderLibraries();
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
                item.setTextColor(palette.onAccent);
                item.setBackgroundTintList(ColorStateList.valueOf(colorAccent()));
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            params.setMargins(0, dp(8), dp(8), dp(8));
            librariesRow.addView(item, params);
        }
    }

    private void selectLibrary(Models.Library library) {
        selectedLibrary = library;
        genreKey = normalizeGenreKey(prefs.getString(PREF_GENRE_PREFIX + library.key, ""));
        genres.clear();
        currentTitle = library.label();
        libraryMode = true;
        loadedCount = 0;
        totalCount = 0;
        backStack.clear();
        persistBrowseContext();
        renderLibraries();
        loadGenresThenLibrary(library);
    }

    private void loadGenresThenLibrary(Models.Library library) {
        if (library == null || library.key == null) {
            return;
        }
        genresLoading = true;
        renderGenreSpinner();
        updateToolbarState();
        runTask("Loading filters for " + library.label() + "...", () ->
                api.get("/api/library/" + enc(library.key) + "/genres", Models.GenresResponse.class), response -> {
            if (selectedLibrary == null || !library.key.equals(selectedLibrary.key)) {
                return;
            }
            genres = response == null || response.genres == null ? new ArrayList<>() : response.genres;
            if (!genreKey.isEmpty() && !hasGenre(genreKey)) {
                genreKey = "";
                persistBrowseContext();
            }
            genresLoading = false;
            renderGenreSpinner();
            updateToolbarState();
            loadLibrary(false);
        }, error -> {
            if (selectedLibrary == null || !library.key.equals(selectedLibrary.key)) {
                return;
            }
            genres = new ArrayList<>();
            genresLoading = false;
            renderGenreSpinner();
            updateToolbarState();
            loadLibrary(false);
        });
    }

    private void changeView(String mode) {
        if (mode.equals(viewMode)) {
            return;
        }
        viewMode = mode;
        persistBrowseContext();
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
                + "&genre=" + enc(activeGenreKey())
                + "&start=" + start
                + "&limit=" + PAGE_SIZE;
        runTask(append ? "Loading more..." : "Loading " + selectedLibrary.label() + "...", () -> api.get(path, Models.LibraryResponse.class), response -> {
            if ("mylist".equals(viewMode) && response != null && response.ratingKeys != null) {
                myListKeys.clear();
                myListKeys.addAll(response.ratingKeys);
            }
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

    private void scanCurrentLibrary() {
        if (selectedLibrary == null || selectedLibrary.key == null || scanInProgress) {
            return;
        }
        Models.Library library = selectedLibrary;
        JsonObject payload = new JsonObject();
        payload.addProperty("sectionKey", library.key);
        scanInProgress = true;
        updateToolbarState();
        runTask("Starting scan for " + library.label() + "...", () ->
                api.post("/api/library-scan", payload, Models.LibraryScanResponse.class), response -> {
            setStatus("Plex is scanning " + library.label() + ". Results will refresh shortly.");
            Toast.makeText(this, "Library scan started.", Toast.LENGTH_SHORT).show();
            main.postDelayed(() -> {
                scanInProgress = false;
                updateToolbarState();
                if (libraryMode && selectedLibrary != null && library.key.equals(selectedLibrary.key)) {
                    loadLibrary(false);
                }
            }, 3000L);
        }, error -> {
            scanInProgress = false;
            updateToolbarState();
            setStatus("Could not start scan: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void surpriseMe() {
        if (selectedLibrary == null || selectedLibrary.key == null || surpriseInProgress) {
            return;
        }
        Models.Library library = selectedLibrary;
        String genre = activeGenreKey();
        String path = "/api/random-item?sectionKey=" + enc(library.key);
        if (!genre.isEmpty()) {
            path += "&genre=" + enc(genre);
        }
        if ("unwatched".equals(viewMode)) {
            path += "&unwatched=1";
        }
        String randomPath = path;
        surpriseInProgress = true;
        updateToolbarState();
        runTask("Choosing from " + library.label() + "...", () ->
                api.get(randomPath, Models.ItemResponse.class), response -> {
            surpriseInProgress = false;
            updateToolbarState();
            Models.MediaItem item = response == null ? null : response.item;
            if (item == null) {
                setStatus("This library has no items to choose from.");
                return;
            }
            setStatus("Surprise pick: " + item.displayTitle() + ".");
            showDetailsDialog(item);
        }, error -> {
            surpriseInProgress = false;
            updateToolbarState();
            setStatus("Could not choose an item: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
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

        LinearLayout primaryActions = new LinearLayout(this);
        primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        if (item.canPlay()) {
            Button play = button("Play");
            play.setOnClickListener(v -> {
                dialog.dismiss();
                playItem(item);
            });
            primaryActions.addView(play, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (item.canOpen()) {
            Button open = button("Open");
            open.setOnClickListener(v -> {
                dialog.dismiss();
                openChildren(item);
            });
            primaryActions.addView(open, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (primaryActions.getChildCount() > 0) {
            shell.addView(primaryActions);
        }

        LinearLayout secondaryActions = new LinearLayout(this);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        if (item.canPlay()) {
            Button subtitles = button("Subtitles");
            subtitles.setOnClickListener(v -> openSubtitleDialog(item));
            secondaryActions.addView(subtitles, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (item.downloadOriginalUrl != null && !item.downloadOriginalUrl.isEmpty()) {
            Button download = button("Download");
            download.setOnClickListener(v -> downloadOriginal(item));
            secondaryActions.addView(download, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (secondaryActions.getChildCount() > 0) {
            shell.addView(secondaryActions);
        }

        if (item.canPlay() && item.ratingKey != null) {
            Button watchState = button(item.viewCount != null && item.viewCount > 0 ? "Mark unwatched" : "Mark watched");
            watchState.setOnClickListener(v -> updateWatchState(dialog, item, watchState));
            shell.addView(watchState, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        if (item.ratingKey != null && ("movie".equals(item.type) || "show".equals(item.type) || "episode".equals(item.type))) {
            boolean saved = myListKeys.contains(item.ratingKey);
            Button myList = button(saved ? "Remove from My List" : "Add to My List");
            myList.setOnClickListener(v -> updateMyList(dialog, item, myList, !saved));
            shell.addView(myList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        LinearLayout episodeActions = null;
        Button previousEpisode = null;
        Button nextEpisode = null;
        if (item.ratingKey != null && "episode".equals(item.type)) {
            episodeActions = new LinearLayout(this);
            episodeActions.setOrientation(LinearLayout.HORIZONTAL);
            episodeActions.setVisibility(View.GONE);
            previousEpisode = button("Previous episode");
            nextEpisode = button("Next episode");
            previousEpisode.setVisibility(View.GONE);
            nextEpisode.setVisibility(View.GONE);
            episodeActions.addView(previousEpisode, new LinearLayout.LayoutParams(0, dp(44), 1));
            episodeActions.addView(nextEpisode, new LinearLayout.LayoutParams(0, dp(44), 1));
            shell.addView(episodeActions);
        }

        Button close = button("Close");
        close.setOnClickListener(v -> dialog.dismiss());
        shell.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(shell);
        dialog.setContentView(scrollView);
        dialog.show();
        sizeDialog(dialog, 0.94f, 0.88f);
        if (episodeActions != null) {
            loadDetailsEpisodeActions(dialog, item, episodeActions, previousEpisode, nextEpisode);
        }
    }

    private void loadDetailsEpisodeActions(
            Dialog dialog,
            Models.MediaItem item,
            LinearLayout actions,
            Button previousButton,
            Button nextButton
    ) {
        io.execute(() -> {
            try {
                Models.EpisodeNeighborsResponse neighbors = api.get(
                        "/api/episode-neighbors?ratingKey=" + enc(item.ratingKey),
                        Models.EpisodeNeighborsResponse.class
                );
                main.post(() -> {
                    if (!dialog.isShowing() || neighbors == null) {
                        return;
                    }
                    if (neighbors.previous != null) {
                        previousButton.setText("Previous " + neighbors.previous.episodeCode());
                        previousButton.setContentDescription("Play " + neighbors.previous.displayTitle());
                        previousButton.setVisibility(View.VISIBLE);
                        previousButton.setOnClickListener(v -> {
                            dialog.dismiss();
                            playItem(neighbors.previous);
                        });
                    }
                    if (neighbors.next != null) {
                        nextButton.setText("Next " + neighbors.next.episodeCode());
                        nextButton.setContentDescription("Play " + neighbors.next.displayTitle());
                        nextButton.setVisibility(View.VISIBLE);
                        nextButton.setOnClickListener(v -> {
                            dialog.dismiss();
                            playItem(neighbors.next);
                        });
                    }
                    actions.setVisibility(neighbors.previous == null && neighbors.next == null ? View.GONE : View.VISIBLE);
                });
            } catch (IOException ignored) {
                // Episode details remain usable when adjacent metadata is temporarily unavailable.
            }
        });
    }

    private void updateMyList(Dialog dialog, Models.MediaItem item, Button button, boolean saved) {
        if (item.ratingKey == null) {
            return;
        }
        button.setEnabled(false);
        button.setText("Updating...");
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        payload.addProperty("saved", saved);
        runTask("Updating My List...", () ->
                api.post("/api/my-list", payload, Models.MyListResponse.class), response -> {
            myListKeys.clear();
            if (response != null && response.ratingKeys != null) {
                myListKeys.addAll(response.ratingKeys);
            }
            item.inMyList = saved;
            dialog.dismiss();
            if (libraryMode && "mylist".equals(viewMode)) {
                loadLibrary(false);
            } else {
                renderCurrent();
                setStatus(item.displayTitle() + (saved ? " added to" : " removed from") + " My List.");
            }
        }, error -> {
            button.setEnabled(true);
            button.setText(saved ? "Add to My List" : "Remove from My List");
            setStatus("Could not update My List: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void updateWatchState(Dialog dialog, Models.MediaItem item, Button button) {
        if (item.ratingKey == null) {
            return;
        }
        boolean watched = item.viewCount == null || item.viewCount == 0;
        String idleLabel = watched ? "Mark watched" : "Mark unwatched";
        button.setEnabled(false);
        button.setText("Updating...");
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        payload.addProperty("watched", watched);
        runTask("Updating watched state...", () ->
                api.post("/api/watch-state", payload, Models.WatchStateResponse.class), response -> {
            Models.MediaItem refreshed = response == null ? null : response.item;
            item.viewCount = watched ? Math.max(1, refreshed == null || refreshed.viewCount == null ? 0 : refreshed.viewCount) : 0;
            item.viewOffset = watched ? 0L : refreshed == null || refreshed.viewOffset == null ? 0L : refreshed.viewOffset;
            prefs.edit().remove("progress:" + item.ratingKey).apply();
            if (playerItem != null && item.ratingKey.equals(playerItem.ratingKey)) {
                playerItem.viewCount = item.viewCount;
                playerItem.viewOffset = item.viewOffset;
            }
            dialog.dismiss();
            boolean reloadFilteredView = libraryMode && ("continue".equals(viewMode) || "unwatched".equals(viewMode));
            if (reloadFilteredView) {
                loadLibrary(false);
            } else {
                renderCurrent();
                setStatus(item.displayTitle() + " marked " + (watched ? "watched." : "unwatched."));
            }
            Toast.makeText(this, watched ? "Marked watched." : "Marked unwatched.", Toast.LENGTH_SHORT).show();
        }, error -> {
            button.setEnabled(true);
            button.setText(idleLabel);
            setStatus("Could not update watched state: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void playItem(Models.MediaItem item) {
        runTask("Preparing playback...", () -> {
            Models.MediaItem hydrated = hydrate(item);
            refreshSavedPlayback(hydrated);
            return hydrated;
        }, this::showPlayer);
    }

    private void showPlayer(Models.MediaItem item) {
        cancelAutoplayNextCountdown();
        playerItem = item;
        playerNeighbors = null;
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

        episodeContinuationControls = new LinearLayout(this);
        episodeContinuationControls.setOrientation(LinearLayout.HORIZONTAL);
        episodeContinuationControls.setGravity(Gravity.CENTER_VERTICAL);
        episodeContinuationControls.setPadding(dp(8), dp(4), dp(8), dp(4));
        episodeContinuationControls.setBackgroundColor(Color.argb(210, 20, 20, 20));
        autoplayNextSwitch = new Switch(this);
        autoplayNextSwitch.setText("Auto next");
        autoplayNextSwitch.setTextColor(Color.WHITE);
        autoplayNextSwitch.setTextSize(12);
        autoplayNextSwitch.setChecked(prefs.getBoolean(PREF_AUTOPLAY_NEXT, true));
        nextEpisodeButton = compactButton("Next episode");
        cancelAutoplayNextButton = compactButton("Cancel");
        nextEpisodeButton.setVisibility(View.GONE);
        cancelAutoplayNextButton.setVisibility(View.GONE);
        episodeContinuationControls.addView(autoplayNextSwitch);
        episodeContinuationControls.addView(nextEpisodeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
        ));
        episodeContinuationControls.addView(cancelAutoplayNextButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
        ));
        episodeContinuationControls.setVisibility(View.GONE);

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
        autoplayNextSwitch.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(PREF_AUTOPLAY_NEXT, checked).apply();
            if (!checked) {
                cancelAutoplayNextCountdown();
            }
        });
        nextEpisodeButton.setOnClickListener(v -> {
            if (playerNeighbors != null && playerNeighbors.next != null) {
                playAdjacentEpisode(playerNeighbors.next, false);
            }
        });
        cancelAutoplayNextButton.setOnClickListener(v -> cancelAutoplayNextCountdown());
        close.setOnClickListener(v -> playerDialog.dismiss());

        playerControls.addView(saveButton);
        playerControls.addView(deleteSavedButton);
        playerControls.addView(saveDeviceButton);
        playerControls.addView(deleteDeviceButton);
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
        FrameLayout.LayoutParams resizeParams = new FrameLayout.LayoutParams(dp(72), dp(52), Gravity.TOP | Gravity.RIGHT);
        resizeParams.setMargins(0, dp(10), dp(70), 0);
        shell.addView(resizeButton, resizeParams);
        closeOverlayButton.setOnClickListener(v -> playerDialog.dismiss());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.RIGHT);
        closeParams.setMargins(0, dp(10), dp(10), 0);
        shell.addView(closeOverlayButton, closeParams);
        FrameLayout.LayoutParams continuationParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(52),
                Gravity.BOTTOM | Gravity.LEFT
        );
        continuationParams.setMargins(dp(10), 0, dp(10), dp(82));
        shell.addView(episodeContinuationControls, continuationParams);
        // Keep playback clean: the player is closed with Back, and secondary
        // actions stay off-screen instead of occupying the video surface.

        playerDialog.setContentView(shell);
        playerDialog.setOnDismissListener(dialog -> {
            cancelAutoplayNextCountdown();
            reportProgress("stopped", true);
            stopProgressReporting();
            cancelPlayerControlsHide();
            releasePlayer();
            playerControls = null;
            closeOverlayButton = null;
            episodeContinuationControls = null;
            autoplayNextSwitch = null;
            nextEpisodeButton = null;
            cancelAutoplayNextButton = null;
            playerNeighbors = null;
            playerItem = null;
            usingSavedPlayback = false;
            usingDevicePlayback = false;
            playerOverlayControlsVisible = false;
            playerDialog = null;
            renderCurrent();
        });
        playerDialog.show();
        Window window = playerDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            applyFullscreen(window);
        }
        playerOverlayControlsVisible = true;
        showPlayerControlsTemporarily();
        playPreferredSource(resumeTimeFor(item), true);
        loadPlayerEpisodeNeighbors(item);
    }

    private void loadPlayerEpisodeNeighbors(Models.MediaItem item) {
        playerNeighbors = null;
        updateEpisodeContinuationControls();
        if (item == null || item.ratingKey == null || !"episode".equals(item.type)) {
            return;
        }
        String requestedKey = item.ratingKey;
        io.execute(() -> {
            try {
                Models.EpisodeNeighborsResponse neighbors = api.get(
                        "/api/episode-neighbors?ratingKey=" + enc(requestedKey),
                        Models.EpisodeNeighborsResponse.class
                );
                main.post(() -> {
                    if (playerItem != null && requestedKey.equals(playerItem.ratingKey)) {
                        playerNeighbors = neighbors;
                        updateEpisodeContinuationControls();
                    }
                });
            } catch (IOException ignored) {
                // Playback remains available when adjacent metadata cannot be loaded.
            }
        });
    }

    private void updateEpisodeContinuationControls() {
        if (episodeContinuationControls == null || autoplayNextSwitch == null) {
            return;
        }
        boolean episode = playerItem != null && "episode".equals(playerItem.type);
        boolean countdown = autoplayNextRunnable != null;
        episodeContinuationControls.setVisibility(
                episode && (playerOverlayControlsVisible || countdown) ? View.VISIBLE : View.GONE
        );
        boolean savedAutoplay = prefs.getBoolean(PREF_AUTOPLAY_NEXT, true);
        if (autoplayNextSwitch.isChecked() != savedAutoplay) {
            autoplayNextSwitch.setChecked(savedAutoplay);
        }
        Models.MediaItem next = playerNeighbors == null ? null : playerNeighbors.next;
        nextEpisodeButton.setVisibility(next == null ? View.GONE : View.VISIBLE);
        cancelAutoplayNextButton.setVisibility(countdown ? View.VISIBLE : View.GONE);
        if (next != null) {
            String code = next.episodeCode();
            String label = code.isEmpty() ? "Next episode" : "Next " + code;
            if (countdown) {
                label += " in " + autoplayNextSeconds + "s";
            }
            nextEpisodeButton.setText(label);
            nextEpisodeButton.setContentDescription("Play " + next.displayTitle());
        }
    }

    private void cancelAutoplayNextCountdown() {
        if (autoplayNextRunnable != null) {
            main.removeCallbacks(autoplayNextRunnable);
            autoplayNextRunnable = null;
        }
        autoplayNextSeconds = 0;
        updateEpisodeContinuationControls();
    }

    private void scheduleAutoplayNext() {
        cancelAutoplayNextCountdown();
        Models.MediaItem next = playerNeighbors == null ? null : playerNeighbors.next;
        if (next == null || !prefs.getBoolean(PREF_AUTOPLAY_NEXT, true)) {
            return;
        }
        String currentKey = playerItem == null ? null : playerItem.ratingKey;
        autoplayNextSeconds = 5;
        autoplayNextRunnable = new Runnable() {
            @Override
            public void run() {
                if (playerDialog == null
                        || playerItem == null
                        || currentKey == null
                        || !currentKey.equals(playerItem.ratingKey)
                        || !prefs.getBoolean(PREF_AUTOPLAY_NEXT, true)) {
                    cancelAutoplayNextCountdown();
                    return;
                }
                if (autoplayNextSeconds <= 0) {
                    autoplayNextRunnable = null;
                    updateEpisodeContinuationControls();
                    playAdjacentEpisode(next, true);
                    return;
                }
                updateEpisodeContinuationControls();
                autoplayNextSeconds -= 1;
                main.postDelayed(this, 1000L);
            }
        };
        main.post(autoplayNextRunnable);
    }

    private void playAdjacentEpisode(Models.MediaItem item, boolean ended) {
        if (item == null || playerDialog == null) {
            return;
        }
        cancelAutoplayNextCountdown();
        if (!ended) {
            reportProgress("stopped", true);
        }
        stopProgressReporting();
        releasePlayer();
        runTask("Preparing " + item.episodeCode() + "...", () -> {
            Models.MediaItem hydrated = hydrate(item);
            refreshSavedPlayback(hydrated);
            return hydrated;
        }, hydrated -> {
            if (playerDialog == null || !playerDialog.isShowing()) {
                return;
            }
            playerItem = hydrated;
            playerNeighbors = null;
            playPreferredSource(0L, true);
            loadPlayerEpisodeNeighbors(hydrated);
            showPlayerControlsTemporarily();
        });
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
                .setUserAgent("PlexOpenAndroid/" + BuildConfig.VERSION_NAME);
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
                    scheduleAutoplayNext();
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

    private void downloadOriginal(Models.MediaItem item) {
        if (item == null || item.downloadOriginalUrl == null || item.downloadOriginalUrl.isEmpty()) {
            return;
        }
        try {
            String url = api.absoluteUrl(item.downloadOriginalUrl);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(item.displayTitle())
                    .setDescription("Original video and subtitles")
                    .setMimeType("application/zip")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, downloadFileName(item));
            String cookies = api.cookieHeaderFor(item.downloadOriginalUrl);
            if (!cookies.isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IOException("Android download service is unavailable");
            }
            manager.enqueue(request);
            Toast.makeText(this, "Download started.", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String downloadFileName(Models.MediaItem item) {
        String base = item.displayTitle().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty()) {
            base = "Plex media";
        }
        return base + " + subtitles.zip";
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
        playerOverlayControlsVisible = visible;
        if (playerControls != null) {
            playerControls.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (closeOverlayButton != null) {
            closeOverlayButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (resizeButton != null) {
            resizeButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        updateEpisodeContinuationControls();
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

        Spinner language = themedSpinner(new String[]{"Greek", "English", "All languages"});
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
        String ratingKey = playerItem.ratingKey;
        boolean watched = "ended".equals(state) || (duration > 0 && (position >= duration * 0.9 || duration - position <= 120_000L));
        long visibleOffset = watched ? 0L : position;
        if (watched) {
            playerItem.viewCount = Math.max(1, playerItem.viewCount == null ? 0 : playerItem.viewCount);
        }
        playerItem.viewOffset = visibleOffset;
        for (Models.MediaItem item : currentItems) {
            if (ratingKey.equals(item.ratingKey)) {
                if (watched) {
                    item.viewCount = playerItem.viewCount;
                }
                item.viewOffset = visibleOffset;
            }
        }
        rememberLocalProgress(ratingKey, position, duration);
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
                Models.PlaybackProgressResponse response = api.post("/api/playback-progress", payload, Models.PlaybackProgressResponse.class);
                if (response != null && response.watched) {
                    prefs.edit().remove("progress:" + ratingKey).apply();
                    main.post(() -> {
                        boolean reloadFilteredView = playerDialog == null
                                && libraryMode
                                && ("continue".equals(viewMode) || "unwatched".equals(viewMode));
                        if (reloadFilteredView) {
                            loadLibrary(false);
                        } else if (playerDialog == null) {
                            renderCurrent();
                        }
                    });
                }
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
        if (item.viewCount != null && item.viewCount > 0) {
            return 0L;
        }
        long plex = item.viewOffset == null ? 0L : item.viewOffset;
        long local = prefs.getLong("progress:" + item.ratingKey, 0L);
        return Math.max(plex, local);
    }

    private void rememberLocalProgress(String ratingKey, long timeMs, long durationMs) {
        if (ratingKey == null || durationMs <= 0) {
            return;
        }
        long remaining = durationMs - timeMs;
        if (timeMs < 10_000L || timeMs >= durationMs * 0.9 || remaining <= 120_000L) {
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
        for (Models.MediaItem item : currentItems) {
            item.inMyList = item.ratingKey != null && myListKeys.contains(item.ratingKey);
        }
        adapter.submit(currentItems);
        titleView.setText(currentTitle);
        updateToolbarState();
        int shown = currentItems.size();
        if (shown == 0) {
            if ("continue".equals(viewMode) && libraryMode) {
                setStatus("Nothing to continue.");
            } else if ("collections".equals(viewMode) && libraryMode) {
                setStatus("No collections.");
            } else if ("mylist".equals(viewMode) && libraryMode) {
                setStatus("My List is empty.");
            } else {
                setStatus("No items.");
            }
        } else {
            int nounCount = libraryMode && totalCount > shown ? totalCount : shown;
            String noun = libraryMode && "collections".equals(viewMode)
                    ? (nounCount == 1 ? " collection" : " collections")
                    : libraryMode && "mylist".equals(viewMode)
                    ? (nounCount == 1 ? " saved item" : " saved items")
                    : (nounCount == 1 ? " item" : " items");
            setStatus(libraryMode && totalCount > shown
                    ? shown + " of " + totalCount + noun
                    : shown + noun);
        }
        loadMoreButton.setVisibility(libraryMode && totalCount > shown ? View.VISIBLE : View.GONE);
    }

    private void updateToolbarState() {
        if (backButton != null) {
            backButton.setVisibility(backStack.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
        if (scanButton != null) {
            scanButton.setVisibility(libraryMode && selectedLibrary != null ? View.VISIBLE : View.GONE);
            scanButton.setEnabled(!scanInProgress);
            scanButton.setText(scanInProgress ? "Scanning..." : "Scan");
        }
        if (surpriseButton != null) {
            surpriseButton.setEnabled(selectedLibrary != null && !surpriseInProgress && !"mylist".equals(viewMode));
            surpriseButton.setText(surpriseInProgress ? "Choosing..." : "Surprise me");
        }
        styleModeButton(continueButton, "continue".equals(viewMode));
        styleModeButton(recentButton, "recent".equals(viewMode));
        styleModeButton(allButton, "all".equals(viewMode));
        styleModeButton(unwatchedButton, "unwatched".equals(viewMode));
        styleModeButton(collectionsButton, "collections".equals(viewMode));
        styleModeButton(myListButton, "mylist".equals(viewMode));
        if (sortSpinner != null) {
            boolean sortingEnabled = !"continue".equals(viewMode)
                    && !"collections".equals(viewMode)
                    && !"mylist".equals(viewMode);
            sortSpinner.setEnabled(sortingEnabled);
            sortSpinner.setAlpha(sortingEnabled ? 1f : 0.5f);
            suppressSortEvent = true;
            sortSpinner.setSelection(sortIndexFor(sortMode));
            suppressSortEvent = false;
        }
        if (genreSpinner != null) {
            boolean genreEnabled = libraryMode
                    && selectedLibrary != null
                    && !genresLoading
                    && !genres.isEmpty()
                    && !"collections".equals(viewMode)
                    && !"mylist".equals(viewMode);
            genreSpinner.setEnabled(genreEnabled);
            genreSpinner.setAlpha(genreEnabled ? 1f : 0.5f);
        }
    }

    private void styleModeButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setTextColor(selected ? palette.onAccent : colorInk());
        button.setBackgroundTintList(ColorStateList.valueOf(selected ? colorAccent() : palette.surface));
    }

    private void persistBrowseContext() {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(PREF_VIEW_MODE, viewMode)
                .putString(PREF_SORT_MODE, sortMode);
        if (selectedLibrary != null && selectedLibrary.key != null) {
            editor.putString(PREF_LIBRARY_KEY, selectedLibrary.key);
            if (genreKey.isEmpty()) {
                editor.remove(PREF_GENRE_PREFIX + selectedLibrary.key);
            } else {
                editor.putString(PREF_GENRE_PREFIX + selectedLibrary.key, genreKey);
            }
        }
        editor.apply();
    }

    private void renderGenreSpinner() {
        if (genreSpinner == null) {
            return;
        }
        String[] labels = new String[genres.size() + 1];
        labels[0] = genresLoading ? "Loading genres..." : "All genres";
        for (int index = 0; index < genres.size(); index++) {
            Models.Genre genre = genres.get(index);
            labels[index + 1] = genre.title == null ? "Genre" : genre.title;
        }
        suppressGenreEvent = true;
        ArrayAdapter<String> adapter = themedAdapter(labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        genreSpinner.setAdapter(adapter);
        genreSpinner.setSelection(genreIndexFor(genreKey), false);
        suppressGenreEvent = false;
    }

    private boolean hasGenre(String value) {
        for (Models.Genre genre : genres) {
            if (genre.key != null && genre.key.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private String activeGenreKey() {
        return "collections".equals(viewMode) || "mylist".equals(viewMode) || !hasGenre(genreKey) ? "" : genreKey;
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
        state.genreKey = genreKey;
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
        genreKey = state.genreKey;
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
        editText.setBackgroundTintList(ColorStateList.valueOf(colorMuted()));
        editText.setSingleLine(false);
        return editText;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(colorInk());
        button.setBackgroundTintList(ColorStateList.valueOf(palette.surface));
        return button;
    }

    private Spinner themedSpinner(String[] labels) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = themedAdapter(labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackgroundTintList(ColorStateList.valueOf(colorMuted()));
        return spinner;
    }

    private ArrayAdapter<String> themedAdapter(String[] labels) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return styleSpinnerView(super.getView(position, convertView, parent), false);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return styleSpinnerView(super.getDropDownView(position, convertView, parent), true);
            }
        };
    }

    private View styleSpinnerView(View view, boolean dropdown) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextColor(colorInk());
            textView.setBackgroundColor(dropdown ? palette.surface : Color.TRANSPARENT);
        }
        return view;
    }

    private Spinner themeSpinner() {
        Spinner spinner = themedSpinner(new String[]{"System", "Light", "Dark"});
        spinner.setContentDescription("Color theme");
        spinner.setPrompt("Color theme");
        spinner.setSelection(ThemePalette.index(themeMode));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String next = ThemePalette.modeAt(position);
                if (!next.equals(themeMode)) {
                    themeMode = next;
                    prefs.edit().putString(ThemePalette.PREF_KEY, next).commit();
                    recreate();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        return spinner;
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
        window.setBackgroundDrawable(new ColorDrawable(colorPaper()));
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
        window.setStatusBarColor(colorPaper());
        window.setNavigationBarColor(colorPaper());
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        int visibility = IMMERSIVE_FLAGS;
        if (!palette.dark) {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(visibility);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(palette.dark ? 0 : lightBars, lightBars);
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
        return palette.paper;
    }

    private int colorInk() {
        return palette.ink;
    }

    private int colorMuted() {
        return palette.muted;
    }

    private int colorAccent() {
        return palette.accent;
    }

    private static String normalizeViewMode(String value) {
        if ("continue".equals(value)
                || "recent".equals(value)
                || "all".equals(value)
                || "unwatched".equals(value)
                || "collections".equals(value)
                || "mylist".equals(value)) {
            return value;
        }
        return "all";
    }

    private static String normalizeSortMode(String value) {
        if ("titleSort".equals(value)
                || "year:desc".equals(value)
                || "lastViewedAt:desc".equals(value)) {
            return value;
        }
        return "addedAt:desc";
    }

    private static String normalizeGenreKey(String value) {
        return value != null && value.matches("\\d+") ? value : "";
    }

    private int genreIndexFor(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < genres.size(); index++) {
            if (value.equals(genres.get(index).key)) {
                return index + 1;
            }
        }
        return 0;
    }

    private String genreValueAt(int index) {
        if (index <= 0 || index > genres.size()) {
            return "";
        }
        return normalizeGenreKey(genres.get(index - 1).key);
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
        List<String> myListKeys;
    }

    private static final class ScreenState {
        String title;
        List<Models.MediaItem> items;
        Models.Library selectedLibrary;
        String viewMode;
        String sortMode;
        String genreKey;
        int loadedCount;
        int totalCount;
        boolean libraryMode;
    }
}
