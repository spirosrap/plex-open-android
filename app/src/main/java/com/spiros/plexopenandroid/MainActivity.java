package com.spiros.plexopenandroid;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
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
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

@UnstableApi
public final class MainActivity extends android.app.Activity {
    private static final int PAGE_SIZE = 30;
    private static final int VISIBLE_METADATA_PREFETCH_COUNT = 6;
    private static final long PROGRESS_INTERVAL_MS = 15_000L;
    private static final long PLAYER_CONTROLS_TIMEOUT_MS = 8_000L;
    private static final String PREF_LIBRARY_KEY = "browse_library_key";
    private static final String PREF_VIEW_MODE = "browse_view_mode";
    private static final String PREF_SORT_MODE = "browse_sort_mode";
    private static final String PREF_GENRE_PREFIX = "browse_genre_";
    private static final String PREF_AUTOPLAY_NEXT = "playback_autoplay_next";
    private static final String PREF_PLAYBACK_SPEED = "playback_speed";
    private static final String OFFLINE_LIBRARY_KEY = DownloadsLibrary.KEY;
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
    private final Map<String, Models.MediaItem> hydratedItems = Collections.synchronizedMap(
            new LinkedHashMap<String, Models.MediaItem>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Models.MediaItem> eldest) {
                    return size() > 64;
                }
            }
    );
    private final ConcurrentHashMap<String, FutureTask<Models.MediaItem>> hydrationRequests = new ConcurrentHashMap<>();

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
    private HorizontalScrollView modeScroll;
    private TextView titleView;
    private TextView subtitleView;
    private TextView statusView;
    private Button continueButton;
    private Button recentButton;
    private Button allButton;
    private Button unwatchedButton;
    private Button collectionsButton;
    private Button myListButton;
    private Button queueButton;
    private Button loadMoreButton;
    private Button backButton;
    private Button scanButton;
    private Button surpriseButton;
    private Spinner genreSpinner;
    private Spinner sortSpinner;

    private List<Models.Library> libraries = new ArrayList<>();
    private List<Models.Genre> genres = new ArrayList<>();
    private final Set<String> myListKeys = new HashSet<>();
    private final Set<String> playQueueKeys = new HashSet<>();
    private Models.Library selectedLibrary;
    private String currentTitle = "Library";
    private String currentCollectionRatingKey;
    private String viewMode = "all";
    private String sortMode = "addedAt:desc";
    private String genreKey = "";
    private int loadedCount = 0;
    private int totalCount = 0;
    private boolean libraryMode = false;
    private boolean mediaDeletionEnabled = false;
    private boolean suppressSortEvent = false;
    private boolean suppressGenreEvent = false;
    private boolean genresLoading = false;
    private boolean scanInProgress = false;
    private boolean surpriseInProgress = false;
    private boolean collectionLibraryRefreshPending = false;
    private int libraryRequestGeneration = 0;
    private boolean libraryLoadingMore = false;
    private boolean offlineMode = false;
    private boolean offlineReconnectInProgress = false;
    private boolean offlineMetadataRefreshInProgress = false;
    private OfflineConnectionStatus.Cause lastOfflineCause;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback offlineStatusNetworkCallback;
    private Runnable offlineStatusRefreshRunnable;
    private Runnable metadataPrefetchRunnable;

    private FrameLayout playerLayer;
    private LinearLayout playerControls;
    private PlayerView playerView;
    private ExoPlayer player;
    private MediaSession mediaSession;
    private Models.MediaItem playerItem;
    private TextView playbackModeView;
    private Button saveButton;
    private Button deleteSavedButton;
    private Button saveDeviceButton;
    private Button deleteDeviceButton;
    private Button resizeButton;
    private Button playbackSpeedButton;
    private Button restartOverlayButton;
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
    private boolean restartingPlayback = false;
    private boolean activityResumed = false;
    private boolean pictureInPictureActive = false;
    private Runnable pictureInPictureDismissalRunnable;

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
        registerOfflineStatusNetworkCallback();

        if (api.hasBaseUrl()) {
            checkExistingSession();
        } else {
            showLogin(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        cancelPictureInPictureDismissalCheck();
        applyFullscreen();
        scheduleOfflineStatusRefresh();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isPlayerOpen()) {
            if (pictureInPictureActive || isInPictureInPictureMode()) {
                schedulePictureInPictureDismissalCheck();
            } else if (player != null) {
                player.pause();
            }
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureIfPossible();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(
            boolean isInPictureInPictureMode,
            Configuration newConfig
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        pictureInPictureActive = isInPictureInPictureMode;
        updatePlayerPictureInPictureUi(isInPictureInPictureMode);
        if (!isInPictureInPictureMode) {
            schedulePictureInPictureDismissalCheck();
        }
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
        if (metadataPrefetchRunnable != null) {
            main.removeCallbacks(metadataPrefetchRunnable);
            metadataPrefetchRunnable = null;
        }
        if (offlineStatusRefreshRunnable != null) {
            main.removeCallbacks(offlineStatusRefreshRunnable);
            offlineStatusRefreshRunnable = null;
        }
        cancelPictureInPictureDismissalCheck();
        if (connectivityManager != null && offlineStatusNetworkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(offlineStatusNetworkCallback);
            } catch (RuntimeException ignored) {
                // The callback may already be unregistered during process teardown.
            }
            offlineStatusNetworkCallback = null;
        }
        if (playerLayer != null || player != null) {
            closePlayer(false);
        } else {
            releasePlayer();
        }
        imageLoader.shutdown();
        io.shutdownNow();
        api.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (isPlayerOpen()) {
            closePlayer();
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
            gridLayoutManager.setInitialPrefetchItemCount(spanCount() * 2);
        }
    }

    private void checkExistingSession() {
        checkExistingSession(false);
    }

    private void checkExistingSession(boolean preserveOfflineScreen) {
        List<Models.MediaItem> offlineItems = deviceCache.offlineItems();
        OfflineConnectionStatus.Cause offlineCause = currentOfflineCause();
        if (offlineCause != null) {
            lastOfflineCause = offlineCause;
            String message = offlineMessage(offlineCause);
            if (offlineItems.isEmpty()) {
                showLogin(message + " No offline titles are saved on this Pixel.");
            } else {
                showOfflineFallback(offlineItems, message, preserveOfflineScreen);
            }
            return;
        }
        offlineReconnectInProgress = true;
        boolean requireLiveServer = preserveOfflineScreen || offlineMode;
        if (preserveOfflineScreen && statusView != null) {
            setStatus("Reconnecting to Plex...");
        } else {
            showLoadingShell("Connecting...");
        }
        String path = bootstrapPath();
        runTask(null, () -> {
            if (requireLiveServer) {
                return new StartupSnapshot(api.getNetwork(path, Models.BootstrapResponse.class), false);
            }
            Models.BootstrapResponse cached = api.getCached(path, Models.BootstrapResponse.class, 7);
            if (cached != null && cached.authenticated) {
                return new StartupSnapshot(cached, true);
            }
            return new StartupSnapshot(api.get(path, Models.BootstrapResponse.class), false);
        }, snapshot -> {
            offlineReconnectInProgress = false;
            Models.BootstrapResponse start = snapshot.response;
            if (start != null && start.authenticated) {
                offlineMode = false;
                showApp(start);
                if (snapshot.cached) {
                    refreshBootstrap(path);
                }
            } else if (!offlineItems.isEmpty()) {
                String message = offlineMessageAfterServerFailure();
                showOfflineFallback(
                        offlineItems,
                        message,
                        preserveOfflineScreen
                );
            } else {
                showLogin(null);
            }
        }, error -> {
            offlineReconnectInProgress = false;
            List<Models.MediaItem> latestOfflineItems = deviceCache.offlineItems();
            String message = offlineMessageAfterServerFailure();
            if (latestOfflineItems.isEmpty()) {
                showLogin(message + " No offline titles are saved on this Pixel.");
            } else {
                showOfflineFallback(
                        latestOfflineItems,
                        message,
                        preserveOfflineScreen
                );
            }
        });
    }

    private void showOfflineFallback(
            List<Models.MediaItem> items,
            String message,
            boolean preserveOfflineScreen
    ) {
        if (preserveOfflineScreen && offlineMode && root != null && statusView != null) {
            setStatus(offlineStatus(message, items.size()));
            return;
        }
        showOfflineApp(items, message);
    }

    private void showOfflineApp(List<Models.MediaItem> items, String message) {
        offlineMode = true;
        offlineReconnectInProgress = false;
        viewMode = "all";
        genreKey = "";

        Models.Library library = DownloadsLibrary.create("Offline");

        Models.LibraryResponse page = new Models.LibraryResponse();
        page.library = OFFLINE_LIBRARY_KEY;
        page.view = "all";
        page.start = 0;
        page.limit = items.size();
        page.size = items.size();
        page.totalSize = items.size();
        page.items = items;

        Models.BrowseResponse browse = new Models.BrowseResponse();
        browse.library = OFFLINE_LIBRARY_KEY;
        browse.page = page;

        Models.ServerInfo server = new Models.ServerInfo();
        server.friendlyName = "Offline on this Pixel";

        Models.BootstrapResponse start = new Models.BootstrapResponse();
        start.authenticated = true;
        start.server = server;
        start.libraries.add(library);
        start.selectedLibraryKey = OFFLINE_LIBRARY_KEY;
        start.browse = browse;
        showApp(start);
        setStatus(offlineStatus(message, items.size()));
        updateToolbarState();
    }

    private String offlineStatus(String message, int count) {
        return message + " " + count + (count == 1 ? " title is" : " titles are") + " ready offline.";
    }

    private boolean isAirplaneMode() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    private boolean isNetworkAvailable() {
        if (isAirplaneMode()) {
            return false;
        }
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private boolean usesTailnetServer() {
        String host = Uri.parse(api.baseUrl()).getHost();
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(".ts.net");
    }

    private boolean isVpnActive() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private void registerOfflineStatusNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return;
        }
        offlineStatusNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleOfflineStatusRefresh();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                scheduleOfflineStatusRefresh();
            }

            @Override
            public void onLost(Network network) {
                scheduleOfflineStatusRefresh();
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(offlineStatusNetworkCallback);
        } catch (RuntimeException ignored) {
            offlineStatusNetworkCallback = null;
        }
    }

    private void scheduleOfflineStatusRefresh() {
        main.post(() -> {
            if (isDestroyed()) {
                return;
            }
            if (offlineStatusRefreshRunnable != null) {
                main.removeCallbacks(offlineStatusRefreshRunnable);
            }
            offlineStatusRefreshRunnable = () -> {
                offlineStatusRefreshRunnable = null;
                if (!isDestroyed()) {
                    refreshOfflineConnectionStatus();
                }
            };
            main.postDelayed(offlineStatusRefreshRunnable, 500L);
        });
    }

    private void refreshOfflineConnectionStatus() {
        if (!offlineMode || statusView == null || deviceCache == null) {
            return;
        }
        OfflineConnectionStatus.Cause cause = currentOfflineCause();
        String message;
        if (cause != null) {
            lastOfflineCause = cause;
            message = offlineMessage(cause);
        } else if (lastOfflineCause == OfflineConnectionStatus.Cause.PLEX_UNAVAILABLE) {
            message = offlineMessage(lastOfflineCause);
        } else {
            lastOfflineCause = null;
            message = OfflineConnectionStatus.readyToReconnectMessage(usesTailnetServer());
        }
        setStatus(offlineStatus(message, deviceCache.offlineItems().size()));
    }

    private OfflineConnectionStatus.Cause currentOfflineCause() {
        boolean airplaneMode = isAirplaneMode();
        boolean internetAvailable = !airplaneMode && isNetworkAvailable();
        return OfflineConnectionStatus.preflight(
                airplaneMode,
                internetAvailable,
                usesTailnetServer(),
                isVpnActive()
        );
    }

    private String offlineMessage(OfflineConnectionStatus.Cause cause) {
        return OfflineConnectionStatus.message(cause, usesTailnetServer());
    }

    private String offlineMessageAfterServerFailure() {
        OfflineConnectionStatus.Cause cause = currentOfflineCause();
        if (cause == null) {
            cause = OfflineConnectionStatus.Cause.PLEX_UNAVAILABLE;
        }
        lastOfflineCause = cause;
        return offlineMessage(cause);
    }

    private void reconnectNow() {
        OfflineConnectionStatus.Cause offlineCause = currentOfflineCause();
        if (offlineCause != null) {
            setStatus(offlineMessage(offlineCause));
            return;
        }
        checkExistingSession(true);
    }

    private void openTailscale() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
        if (intent == null) {
            Toast.makeText(this, "Tailscale is not installed.", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(intent);
    }

    private void refreshBootstrap(String path) {
        runTask(null, () -> api.get(path, Models.BootstrapResponse.class), this::applyBootstrap, error -> {
            setStatus("Showing the last available library.");
        });
    }

    private void showLogin(@Nullable String message) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(colorPaper());

        TextView mark = text("PO", 15, true);
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(palette.onAccent);
        mark.setBackground(roundedBackground(colorAccent(), colorAccent(), 8));
        LinearLayout.LayoutParams loginMarkParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        loginMarkParams.setMargins(0, 0, 0, dp(12));
        root.addView(mark, loginMarkParams);

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
        root.addView(error, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        Button signIn = button("Sign in");
        stylePrimaryButton(signIn);
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
            }, ok -> showApp(null), throwable -> {
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

    private void showApp(Models.BootstrapResponse startup) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorPaper());
        root.setPadding(dp(10), dp(8), dp(10), dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("PO", 12, true);
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(palette.onAccent);
        mark.setBackground(roundedBackground(colorAccent(), colorAccent(), 7));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        markParams.setMargins(0, 0, dp(10), 0);
        header.addView(mark, markParams);
        LinearLayout brandBlock = new LinearLayout(this);
        brandBlock.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("Plex Open", 20, true);
        brand.setTextColor(colorInk());
        brandBlock.addView(brand);
        subtitleView = text("Media server  |  v" + BuildConfig.VERSION_NAME, 11, false);
        subtitleView.setTextColor(colorMuted());
        brandBlock.addView(subtitleView);
        header.addView(brandBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button appMenu = compactButton("...");
        appMenu.setTextSize(18);
        appMenu.setContentDescription("App options");
        appMenu.setOnClickListener(this::showAppMenu);
        header.addView(appMenu, new LinearLayout.LayoutParams(dp(44), dp(40)));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        HorizontalScrollView libraryScroll = new HorizontalScrollView(this);
        libraryScroll.setHorizontalScrollBarEnabled(false);
        librariesRow = new LinearLayout(this);
        librariesRow.setOrientation(LinearLayout.HORIZONTAL);
        libraryScroll.addView(librariesRow);
        root.addView(libraryScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        backButton = compactButton("<");
        backButton.setContentDescription("Back");
        backButton.setOnClickListener(v -> {
            if (!backStack.isEmpty()) {
                restoreScreen(backStack.pop());
            }
        });
        nav.addView(backButton, new LinearLayout.LayoutParams(dp(44), dp(40)));
        titleView = text(currentTitle, 20, true);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        nav.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        scanButton = compactButton("Scan");
        scanButton.setContentDescription("Scan current Plex library");
        scanButton.setOnClickListener(v -> scanCurrentLibrary());
        nav.addView(scanButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText search = edit("Search");
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        Button searchButton = button("Search");
        stylePrimaryButton(searchButton);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        searchParams.setMargins(0, 0, dp(6), 0);
        searchRow.addView(search, searchParams);
        searchRow.addView(searchButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        LinearLayout.LayoutParams searchRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        searchRowParams.setMargins(0, dp(2), 0, dp(6));
        root.addView(searchRow, searchRowParams);

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
        modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout modeButtons = new LinearLayout(this);
        modeButtons.setOrientation(LinearLayout.HORIZONTAL);
        modeButtons.setGravity(Gravity.CENTER_VERTICAL);
        continueButton = button("Continue");
        recentButton = button("Recent");
        allButton = button("All");
        unwatchedButton = button("Unwatched");
        collectionsButton = button("Collections");
        myListButton = button("My List");
        queueButton = button("Queue");
        continueButton.setOnClickListener(v -> changeView("continue"));
        recentButton.setOnClickListener(v -> changeView("recent"));
        allButton.setOnClickListener(v -> changeView("all"));
        unwatchedButton.setOnClickListener(v -> changeView("unwatched"));
        collectionsButton.setOnClickListener(v -> changeView("collections"));
        myListButton.setOnClickListener(v -> changeView("mylist"));
        queueButton.setOnClickListener(v -> changeView("queue"));
        for (Button modeButton : new Button[]{continueButton, recentButton, allButton, unwatchedButton, collectionsButton, myListButton, queueButton}) {
            LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            modeParams.setMargins(0, 0, dp(6), 0);
            modeButtons.addView(modeButton, modeParams);
        }
        modeScroll.addView(modeButtons);
        LinearLayout.LayoutParams modeScrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        modeScrollParams.setMargins(0, 0, 0, dp(5));
        toolbar.addView(modeScroll, modeScrollParams);

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
        LinearLayout.LayoutParams genreParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        genreParams.setMargins(0, 0, dp(5), 0);
        sortRow.addView(genreSpinner, genreParams);
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        sortParams.setMargins(0, 0, dp(5), 0);
        sortRow.addView(sortSpinner, sortParams);
        surpriseButton = compactButton("Surprise");
        surpriseButton.setOnClickListener(v -> surpriseMe());
        sortRow.addView(surpriseButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        toolbar.addView(sortRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        root.addView(toolbar);

        statusView = text("", 13, false);
        statusView.setTextColor(colorMuted());
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(statusView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        RecyclerView recycler = new RecyclerView(this);
        recycler.setHasFixedSize(true);
        recycler.setItemViewCacheSize(18);
        recycler.setClipToPadding(false);
        recycler.setPadding(0, 0, 0, dp(8));
        gridLayoutManager = new GridLayoutManager(this, spanCount());
        gridLayoutManager.setInitialPrefetchItemCount(spanCount() * 2);
        recycler.setLayoutManager(gridLayoutManager);
        recycler.setItemAnimator(null);
        adapter = new MediaAdapter(imageLoader, new MediaAdapter.Listener() {
            @Override
            public void onItemSelected(Models.MediaItem item) {
                openItem(item);
            }

            @Override
            public void onCollectionActions(View anchor, Models.MediaItem item) {
                showLibraryCollectionActions(anchor, item);
            }
        }, palette);
        recycler.setAdapter(adapter);
        root.addView(recycler, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        loadMoreButton = button("Load more");
        loadMoreButton.setOnClickListener(v -> loadLibrary(true));
        root.addView(loadMoreButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        setContentView(root);
        applyFullscreen();
        updateToolbarState();
        if (startup == null) {
            loadServerAndLibraries();
        } else {
            applyBootstrap(startup);
        }
    }

    private void loadServerAndLibraries() {
        runTask("Loading library...", () -> api.get(bootstrapPath(), Models.BootstrapResponse.class), this::applyBootstrap);
    }

    private void showAppMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        android.view.MenuItem system = menu.getMenu().add(1, 1, 1, "Theme: System");
        android.view.MenuItem light = menu.getMenu().add(1, 2, 2, "Theme: Light");
        android.view.MenuItem dark = menu.getMenu().add(1, 3, 3, "Theme: Dark");
        menu.getMenu().setGroupCheckable(1, true, true);
        int selected = ThemePalette.index(themeMode) + 1;
        system.setChecked(selected == 1);
        light.setChecked(selected == 2);
        dark.setChecked(selected == 3);
        if (offlineMode) {
            menu.getMenu().add(2, 12, 8, "Open Tailscale");
            menu.getMenu().add(2, 11, 9, "Reconnect now");
        }
        menu.getMenu().add(2, 10, 10, "Sign out");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 10) {
                logout();
                return true;
            }
            if (item.getItemId() == 11) {
                reconnectNow();
                return true;
            }
            if (item.getItemId() == 12) {
                openTailscale();
                return true;
            }
            if (item.getGroupId() == 1) {
                String next = ThemePalette.modeAt(item.getItemId() - 1);
                if (!next.equals(themeMode)) {
                    themeMode = next;
                    prefs.edit().putString(ThemePalette.PREF_KEY, next).apply();
                    recreate();
                }
                return true;
            }
            return false;
        });
        menu.show();
    }

    private String bootstrapPath() {
        String libraryKey = DownloadsLibrary.serverLibraryKey(prefs.getString(PREF_LIBRARY_KEY, ""));
        String savedGenre = libraryKey == null || libraryKey.isEmpty()
                ? ""
                : normalizeGenreKey(prefs.getString(PREF_GENRE_PREFIX + libraryKey, ""));
        if ("collections".equals(viewMode) || "mylist".equals(viewMode) || "queue".equals(viewMode)) {
            savedGenre = "";
        }
        return "/api/bootstrap?includeBrowse=1"
                + "&libraryKey=" + enc(libraryKey)
                + "&view=" + enc(viewMode)
                + "&sort=" + enc(sortMode)
                + "&genre=" + enc(savedGenre)
                + "&start=0&limit=" + pageSizeForView(viewMode);
    }

    private void applyBootstrap(Models.BootstrapResponse start) {
        if (start == null || !start.authenticated) {
            showLogin(null);
            return;
        }
        if (start.server != null && start.server.friendlyName != null) {
            subtitleView.setText(start.server.friendlyName + "  |  v" + BuildConfig.VERSION_NAME);
        }
        mediaDeletionEnabled = start.mediaDeletionEnabled;
        myListKeys.clear();
        if (start.ratingKeys != null) {
            myListKeys.addAll(start.ratingKeys);
        }
        playQueueKeys.clear();
        if (start.queueRatingKeys != null) {
            playQueueKeys.addAll(start.queueRatingKeys);
        }
        refreshOfflineMetadata();
        libraries = start.libraries == null ? new ArrayList<>() : new ArrayList<>(start.libraries);
        if (!offlineMode) {
            libraries.removeIf(DownloadsLibrary::matches);
            libraries.add(0, DownloadsLibrary.create("Downloads"));
        }
        if (libraries.isEmpty()) {
            renderLibraries();
            setStatus("No libraries found.");
            return;
        }
        String persistedLibraryKey = prefs.getString(PREF_LIBRARY_KEY, "");
        String selectedKey = !offlineMode && DownloadsLibrary.isKey(persistedLibraryKey)
                ? persistedLibraryKey
                : Models.nonEmpty(start.selectedLibraryKey, persistedLibraryKey);
        Models.Library preferred = null;
        for (Models.Library library : libraries) {
            if (library.key != null && library.key.equals(selectedKey)) {
                preferred = library;
                break;
            }
        }
        selectLibrary(preferred == null ? libraries.get(0) : preferred, start.browse);
    }

    private void renderLibraries() {
        librariesRow.removeAllViews();
        for (Models.Library library : libraries) {
            Button item = button(library.label());
            item.setOnClickListener(v -> selectLibrary(library));
            boolean selected = selectedLibrary != null
                    && library.key != null
                    && library.key.equals(selectedLibrary.key);
            styleButton(
                    item,
                    selected ? colorAccent() : palette.surface,
                    selected ? palette.onAccent : colorInk(),
                    selected ? colorAccent() : palette.line
            );
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            params.setMargins(0, dp(6), dp(8), dp(6));
            librariesRow.addView(item, params);
        }
    }

    private void selectLibrary(Models.Library library) {
        selectLibrary(library, null);
    }

    private void selectLibrary(Models.Library library, Models.BrowseResponse initialBrowse) {
        libraryRequestGeneration++;
        libraryLoadingMore = false;
        selectedLibrary = library;
        genreKey = DownloadsLibrary.matches(library)
                ? ""
                : normalizeGenreKey(prefs.getString(PREF_GENRE_PREFIX + library.key, ""));
        genres.clear();
        currentTitle = library.label();
        libraryMode = true;
        loadedCount = 0;
        totalCount = 0;
        backStack.clear();
        persistBrowseContext();
        renderLibraries();
        if (initialBrowse != null && library.key.equals(initialBrowse.library)) {
            applyBrowseResponse(library, initialBrowse, libraryRequestGeneration);
        } else {
            loadBrowse(library);
        }
    }

    private void loadBrowse(Models.Library library) {
        if (library == null || library.key == null) {
            return;
        }
        if (localCatalogActive()) {
            loadLibrary(false);
            return;
        }
        int generation = libraryRequestGeneration;
        genresLoading = true;
        renderGenreSpinner();
        updateToolbarState();
        String requestedGenre = "collections".equals(viewMode)
                || "mylist".equals(viewMode)
                || "queue".equals(viewMode) ? "" : genreKey;
        String path = "/api/browse/" + enc(library.key)
                + "?view=" + enc(viewMode)
                + "&sort=" + enc(sortMode)
                + "&genre=" + enc(requestedGenre)
                + "&start=0&limit=" + pageSizeForView(viewMode);
        runTask("Loading " + library.label() + "...", () ->
                api.get(path, Models.BrowseResponse.class), response -> {
            if (generation != libraryRequestGeneration || selectedLibrary == null || !library.key.equals(selectedLibrary.key)) {
                return;
            }
            applyBrowseResponse(library, response, generation);
        }, error -> {
            if (generation != libraryRequestGeneration || selectedLibrary == null || !library.key.equals(selectedLibrary.key)) {
                return;
            }
            genres = new ArrayList<>();
            genresLoading = false;
            renderGenreSpinner();
            updateToolbarState();
            loadLibrary(false);
        });
    }

    private void applyBrowseResponse(Models.Library library, Models.BrowseResponse response, int generation) {
        if (generation != libraryRequestGeneration || selectedLibrary == null || !library.key.equals(selectedLibrary.key)) {
            return;
        }
        genres = response == null || response.genres == null ? new ArrayList<>() : response.genres;
        boolean invalidGenre = !genreKey.isEmpty() && !hasGenre(genreKey);
        if (invalidGenre) {
            genreKey = "";
            persistBrowseContext();
        }
        genresLoading = false;
        renderGenreSpinner();
        updateToolbarState();
        if (invalidGenre || response == null || response.page == null) {
            loadLibrary(false);
            return;
        }
        applyLibraryResponse(response.page, false, library, viewMode);
    }

    private void changeView(String mode) {
        if (localCatalogActive()) {
            return;
        }
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

    private void applyLibraryResponse(
            Models.LibraryResponse response,
            boolean append,
            Models.Library requestedLibrary,
            String requestedView
    ) {
        libraryLoadingMore = false;
        loadMoreButton.setEnabled(true);
        loadMoreButton.setText("Load more");
        if ("mylist".equals(requestedView) && response != null && response.ratingKeys != null) {
            myListKeys.clear();
            myListKeys.addAll(response.ratingKeys);
        }
        if ("queue".equals(requestedView) && response != null && response.queueRatingKeys != null) {
            playQueueKeys.clear();
            playQueueKeys.addAll(response.queueRatingKeys);
        }
        if (!append) {
            currentItems.clear();
            currentCollectionRatingKey = null;
        }
        if (response != null && response.items != null) {
            currentItems.addAll(response.items);
        }
        loadedCount = currentItems.size();
        totalCount = response == null || response.totalSize == null ? loadedCount : response.totalSize;
        libraryMode = true;
        currentTitle = requestedLibrary.label();
        if ("collections".equals(requestedView)) {
            collectionLibraryRefreshPending = false;
        }
        renderCurrent();
    }

    private void loadLibrary(boolean append) {
        if (selectedLibrary == null || selectedLibrary.key == null) {
            return;
        }
        if (localCatalogActive()) {
            currentItems.clear();
            currentItems.addAll(deviceCache.offlineItems());
            loadedCount = currentItems.size();
            totalCount = currentItems.size();
            currentTitle = offlineMode ? "Offline" : "Downloads";
            libraryMode = true;
            renderCurrent();
            return;
        }
        if (append && libraryLoadingMore) {
            return;
        }
        Models.Library requestedLibrary = selectedLibrary;
        String requestedView = viewMode;
        String requestedSort = sortMode;
        String requestedGenre = activeGenreKey();
        int generation = append ? libraryRequestGeneration : ++libraryRequestGeneration;
        libraryLoadingMore = append;
        if (append) {
            loadMoreButton.setEnabled(false);
            loadMoreButton.setText("Loading...");
        }
        int start = append ? loadedCount : 0;
        String path = "/api/library/" + enc(requestedLibrary.key)
                + "?view=" + enc(requestedView)
                + "&sort=" + enc(requestedSort)
                + "&genre=" + enc(requestedGenre)
                + "&start=" + start
                + "&limit=" + pageSizeForView(requestedView);
        runTask(append ? "Loading more..." : "Loading " + selectedLibrary.label() + "...", () -> api.get(path, Models.LibraryResponse.class), response -> {
            if (!isCurrentLibraryRequest(generation, requestedLibrary, requestedView, requestedSort, requestedGenre)) {
                return;
            }
            applyLibraryResponse(response, append, requestedLibrary, requestedView);
        }, error -> {
            if (!isCurrentLibraryRequest(generation, requestedLibrary, requestedView, requestedSort, requestedGenre)) {
                return;
            }
            libraryLoadingMore = false;
            loadMoreButton.setEnabled(true);
            loadMoreButton.setText("Load more");
            setStatus("Could not load media: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private boolean isCurrentLibraryRequest(
            int generation,
            Models.Library library,
            String requestedView,
            String requestedSort,
            String requestedGenre
    ) {
        return generation == libraryRequestGeneration
                && selectedLibrary != null
                && library.key.equals(selectedLibrary.key)
                && requestedView.equals(viewMode)
                && requestedSort.equals(sortMode)
                && requestedGenre.equals(activeGenreKey());
    }

    private void scanCurrentLibrary() {
        if (localCatalogActive() || selectedLibrary == null || selectedLibrary.key == null || scanInProgress) {
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
        if (localCatalogActive()) {
            if (currentItems.isEmpty()) {
                setStatus(offlineMode ? "No offline titles are available." : "No downloads are available.");
                return;
            }
            int index = (int) Math.floorMod(System.nanoTime(), currentItems.size());
            Models.MediaItem item = currentItems.get(index);
            setStatus((offlineMode ? "Offline pick: " : "Downloaded pick: ") + item.displayTitle() + ".");
            showDetailsDialog(item);
            return;
        }
        if ("queue".equals(viewMode)) {
            for (Models.MediaItem item : currentItems) {
                if (item != null && item.canPlay()) {
                    setStatus("Playing queue from " + item.displayTitle() + ".");
                    playItem(item);
                    return;
                }
            }
            setStatus("Play Queue is empty.");
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

    private void showLibraryCollectionActions(View anchor, Models.MediaItem item) {
        if (item == null || !"collection".equals(item.type) || item.smart || item.ratingKey == null) {
            return;
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Delete collection");
        menu.setOnMenuItemClickListener(menuItem -> {
            new AlertDialog.Builder(this)
                    .setTitle("Delete " + item.displayTitle() + "?")
                    .setMessage("The collection will be removed. Its movies will remain in your Plex library.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, which) -> deleteLibraryCollection(item))
                    .show();
            return true;
        });
        menu.show();
    }

    private void deleteLibraryCollection(Models.MediaItem item) {
        if (selectedLibrary == null || selectedLibrary.key == null || item.ratingKey == null || item.smart) {
            return;
        }
        String requestedSection = selectedLibrary.key;
        int requestedGeneration = libraryRequestGeneration;
        JsonObject payload = new JsonObject();
        payload.addProperty("action", "delete");
        payload.addProperty("sectionKey", requestedSection);
        payload.addProperty("collectionRatingKey", item.ratingKey);
        runTask("Deleting " + item.displayTitle() + "...", () -> api.post(
                "/api/collection-management",
                payload,
                Models.CollectionActionResponse.class
        ), response -> {
            if (selectedLibrary == null
                    || !requestedSection.equals(selectedLibrary.key)
                    || requestedGeneration != libraryRequestGeneration) {
                setStatus("Collection deleted.");
                Toast.makeText(this, "Collection deleted.", Toast.LENGTH_SHORT).show();
                return;
            }
            currentItems.removeIf(candidate -> item.ratingKey.equals(candidate.ratingKey));
            loadedCount = currentItems.size();
            totalCount = Math.max(0, totalCount - 1);
            collectionLibraryRefreshPending = false;
            renderCurrent();
            setStatus("Deleted " + item.displayTitle() + ". Movies remain in the library.");
            Toast.makeText(this, "Collection deleted.", Toast.LENGTH_SHORT).show();
        });
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
            currentCollectionRatingKey = "collection".equals(item.type) ? item.ratingKey : null;
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
        if (localCatalogActive()) {
            String needle = text.toLowerCase(Locale.ROOT);
            List<Models.MediaItem> matches = new ArrayList<>();
            for (Models.MediaItem item : deviceCache.offlineItems()) {
                String title = item.displayTitle().toLowerCase(Locale.ROOT);
                if (title.contains(needle)) {
                    matches.add(item);
                }
            }
            pushScreen();
            currentItems.clear();
            currentItems.addAll(matches);
            currentTitle = offlineMode ? "Offline search" : "Downloads search";
            currentCollectionRatingKey = null;
            libraryMode = false;
            loadedCount = currentItems.size();
            totalCount = currentItems.size();
            renderCurrent();
            return;
        }
        pushScreen();
        runTask("Searching...", () -> api.get("/api/search?query=" + enc(text), Models.SearchResponse.class), response -> {
            currentItems.clear();
            if (response != null && response.items != null) {
                currentItems.addAll(response.items);
            }
            currentTitle = "Search";
            currentCollectionRatingKey = null;
            libraryMode = false;
            loadedCount = currentItems.size();
            totalCount = currentItems.size();
            renderCurrent();
        });
    }

    private void openDetails(Models.MediaItem item) {
        showDetailsDialog(item);
        prefetchMetadata(item);
    }

    private void prefetchMetadata(Models.MediaItem item) {
        if (localCatalogActive() || item == null || item.ratingKey == null || hydratedItems.containsKey(item.ratingKey)) {
            return;
        }
        io.execute(() -> {
            try {
                hydrate(item);
            } catch (IOException ignored) {
                // Details already opened from browse data and remain usable when prefetch fails.
            }
        });
    }

    private void prefetchMetadataBatch(List<Models.MediaItem> candidates) {
        if (localCatalogActive()) {
            return;
        }
        List<Models.MediaItem> pending = new ArrayList<>();
        StringBuilder keys = new StringBuilder();
        for (Models.MediaItem item : candidates) {
            if (item == null
                    || item.ratingKey == null
                    || hydratedItems.containsKey(item.ratingKey)
                    || hydrationRequests.containsKey(item.ratingKey)) {
                continue;
            }
            if (keys.length() > 0) {
                keys.append(',');
            }
            keys.append(item.ratingKey);
            pending.add(item);
        }
        if (pending.isEmpty()) {
            return;
        }
        String path = "/api/metadata-batch?ratingKeys=" + enc(keys.toString());
        io.execute(() -> {
            try {
                Models.MetadataBatchResponse response = api.get(path, Models.MetadataBatchResponse.class);
                if (response == null || response.items == null) {
                    return;
                }
                Map<String, Models.MediaItem> browseItems = new LinkedHashMap<>();
                for (Models.MediaItem item : pending) {
                    browseItems.put(item.ratingKey, item);
                }
                for (Models.MediaItem hydrated : response.items) {
                    if (hydrated == null || hydrated.ratingKey == null) {
                        continue;
                    }
                    Models.MediaItem browseItem = browseItems.get(hydrated.ratingKey);
                    hydratedItems.put(
                            hydrated.ratingKey,
                            browseItem == null ? hydrated : mergeBrowseState(hydrated, browseItem)
                    );
                }
            } catch (IOException ignored) {
                // A later Details or Play tap retries only the selected item.
            }
        });
    }

    private void refreshOfflineMetadata() {
        if (offlineMode || offlineMetadataRefreshInProgress || !isNetworkAvailable()) {
            return;
        }
        List<String> ratingKeys = deviceCache.metadataKeysNeedingRefresh();
        if (ratingKeys.isEmpty()) {
            return;
        }
        offlineMetadataRefreshInProgress = true;
        io.execute(() -> {
            try {
                for (int start = 0; start < ratingKeys.size(); start += 12) {
                    List<String> batch = ratingKeys.subList(start, Math.min(start + 12, ratingKeys.size()));
                    try {
                        Models.MetadataBatchResponse response = api.get(
                                "/api/metadata-batch?ratingKeys=" + enc(TextUtils.join(",", batch)),
                                Models.MetadataBatchResponse.class
                        );
                        if (response == null || response.items == null) {
                            continue;
                        }
                        for (Models.MediaItem item : response.items) {
                            try {
                                deviceCache.updateMetadata(api, item);
                            } catch (IOException ignored) {
                                // A missing poster must not interrupt other offline metadata repairs.
                            }
                        }
                    } catch (IOException ignored) {
                        // Retry incomplete entries on the next connected app launch.
                    }
                }
            } finally {
                main.post(() -> offlineMetadataRefreshInProgress = false);
            }
        });
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

        long resumeMs = resumeTimeFor(item);
        TextView meta = text(item.metaLine(resumeMs), 13, false);
        meta.setTextColor(colorMuted());
        shell.addView(meta);

        TextView summary = text(Models.nonEmpty(item.summary, ""), 14, false);
        summary.setPadding(0, dp(12), 0, dp(12));
        shell.addView(summary);

        LinearLayout primaryActions = new LinearLayout(this);
        primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        if (item.canPlay()) {
            Button play = button(resumeMs > 0
                    ? "Resume " + formatPlaybackPosition(resumeMs)
                    : "Play");
            stylePrimaryButton(play);
            play.setOnClickListener(v -> {
                dialog.dismiss();
                playItem(item);
            });
            primaryActions.addView(play, new LinearLayout.LayoutParams(0, dp(44), 1));
            if (resumeMs > 0) {
                Button startOver = button("Start over");
                startOver.setOnClickListener(v -> {
                    dialog.dismiss();
                    playItem(item, true);
                });
                primaryActions.addView(startOver, new LinearLayout.LayoutParams(0, dp(44), 1));
            }
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
        if (item.canPlay() && item.ratingKey != null) {
            boolean offlineReady = deviceCache.status(item) != null;
            Button offline = button(offlineReady ? "Offline ready" : "Save offline");
            offline.setEnabled(!offlineReady);
            offline.setOnClickListener(v -> saveOfflineFromDetails(dialog, item, offline));
            secondaryActions.addView(offline, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (!localCatalogActive() && item.canPlay()) {
            Button subtitles = button("Subtitles");
            subtitles.setOnClickListener(v -> openSubtitleDialog(item));
            secondaryActions.addView(subtitles, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (!localCatalogActive() && item.downloadOriginalUrl != null && !item.downloadOriginalUrl.isEmpty()) {
            Button download = button("Original ZIP");
            download.setOnClickListener(v -> downloadOriginal(item));
            secondaryActions.addView(download, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (secondaryActions.getChildCount() > 0) {
            shell.addView(secondaryActions);
        }

        if (!localCatalogActive() && item.canPlay() && item.ratingKey != null) {
            Button watchState = button(item.viewCount != null && item.viewCount > 0 ? "Mark unwatched" : "Mark watched");
            watchState.setOnClickListener(v -> updateWatchState(dialog, item, watchState));
            shell.addView(watchState, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        if (!localCatalogActive()
                && item.ratingKey != null
                && ("movie".equals(item.type) || "show".equals(item.type) || "episode".equals(item.type))) {
            boolean saved = myListKeys.contains(item.ratingKey);
            Button myList = button(saved ? "Remove from My List" : "Add to My List");
            myList.setOnClickListener(v -> updateMyList(dialog, item, myList, !saved));
            shell.addView(myList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        if (!localCatalogActive() && item.ratingKey != null && item.canPlay()) {
            boolean queued = playQueueKeys.contains(item.ratingKey);
            Button queue = button(queued ? "Remove from Queue" : "Add to Queue");
            queue.setOnClickListener(v -> updatePlayQueue(dialog, item, queue, !queued));
            shell.addView(queue, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        if (!localCatalogActive() && item.ratingKey != null && "movie".equals(item.type)) {
            Button collections = button(collectionButtonLabel(item));
            collections.setOnClickListener(v -> openCollectionMembershipDialog(dialog, item, collections));
            shell.addView(collections, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        if (!localCatalogActive()
                && item.ratingKey != null
                && ("movie".equals(item.type) || "show".equals(item.type))) {
            LinearLayout metadataActions = new LinearLayout(this);
            metadataActions.setOrientation(LinearLayout.HORIZONTAL);
            Button fixMatch = button("Fix match");
            fixMatch.setOnClickListener(v -> openMediaMatchDialog(dialog, item));
            metadataActions.addView(fixMatch, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button refreshMetadata = button("Refresh metadata");
            refreshMetadata.setOnClickListener(v -> confirmMetadataRefresh(dialog, item, refreshMetadata));
            metadataActions.addView(refreshMetadata, new LinearLayout.LayoutParams(0, dp(44), 1));
            shell.addView(metadataActions);
        }

        LinearLayout episodeActions = null;
        Button previousEpisode = null;
        Button nextEpisode = null;
        if (!localCatalogActive() && item.ratingKey != null && "episode".equals(item.type)) {
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

        if (!localCatalogActive()
                && mediaDeletionEnabled
                && item.ratingKey != null
                && ("movie".equals(item.type) || "episode".equals(item.type))) {
            Button deleteMedia = button("Delete from disk");
            deleteMedia.setTextColor(palette.danger);
            deleteMedia.setOnClickListener(v -> loadMediaDeletePlan(dialog, item, deleteMedia));
            shell.addView(deleteMedia, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
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

    private void openMediaMatchDialog(Dialog detailsDialog, Models.MediaItem item) {
        MatchDialogState state = new MatchDialogState();
        state.detailsDialog = detailsDialog;
        state.item = item;
        state.dialog = new Dialog(this);
        state.dialog.setTitle("Fix match");

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(16), dp(16), dp(16), dp(16));
        shell.setBackgroundColor(colorPaper());

        TextView heading = text("Fix match for " + item.displayTitle(), 22, true);
        shell.addView(heading);
        TextView explanation = text("Search Plex metadata, replace the full match, or use only a result's poster.", 13, false);
        explanation.setTextColor(colorMuted());
        explanation.setPadding(0, dp(4), 0, dp(12));
        shell.addView(explanation);

        TextView queryLabel = text("Title or external ID", 13, true);
        shell.addView(queryLabel);
        state.query = edit("Movie, TV show, IMDb, TMDB, or TVDB ID");
        state.query.setSingleLine(true);
        state.query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        state.query.setText(Models.nonEmpty(item.title, item.displayTitle()));
        shell.addView(state.query, fieldParams());

        TextView yearLabel = text("Year", 13, true);
        shell.addView(yearLabel);
        state.year = edit("Optional year");
        state.year.setSingleLine(true);
        state.year.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        state.year.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (item.year != null) {
            state.year.setText(String.valueOf(item.year));
        }
        shell.addView(state.year, fieldParams());

        state.languageCodes = new ArrayList<>();
        List<String> languageLabels = new ArrayList<>();
        Models.Library library = libraryForItem(item);
        addMatchLanguage(state.languageCodes, languageLabels, library == null ? null : library.language);
        addMatchLanguage(state.languageCodes, languageLabels, "el-GR");
        addMatchLanguage(state.languageCodes, languageLabels, "en-US");
        TextView languageLabel = text("Language", 13, true);
        shell.addView(languageLabel);
        state.language = themedSpinner(languageLabels.toArray(new String[0]));
        state.language.setContentDescription("Metadata language");
        shell.addView(state.language, fieldParams());

        LinearLayout commands = new LinearLayout(this);
        commands.setOrientation(LinearLayout.HORIZONTAL);
        state.search = button("Search");
        state.search.setOnClickListener(v -> searchMediaMatches(state));
        commands.addView(state.search, new LinearLayout.LayoutParams(0, dp(44), 1));
        state.close = button("Close");
        state.close.setOnClickListener(v -> state.dialog.dismiss());
        commands.addView(state.close, new LinearLayout.LayoutParams(0, dp(44), 1));
        shell.addView(commands);

        state.status = text("", 13, false);
        state.status.setTextColor(colorMuted());
        state.status.setPadding(0, dp(10), 0, dp(4));
        shell.addView(state.status);
        state.results = new LinearLayout(this);
        state.results.setOrientation(LinearLayout.VERTICAL);
        shell.addView(state.results);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(shell);
        state.dialog.setContentView(scroll);
        state.dialog.show();
        sizeDialog(state.dialog, 0.96f, 0.92f);
        searchMediaMatches(state);
    }

    private Models.Library libraryForItem(Models.MediaItem item) {
        if (item == null || item.librarySectionID == null) {
            return selectedLibrary;
        }
        for (Models.Library library : libraries) {
            if (item.librarySectionID.equals(library.key)) {
                return library;
            }
        }
        return selectedLibrary;
    }

    private void addMatchLanguage(List<String> codes, List<String> labels, String value) {
        String code = value == null ? "" : value.trim();
        if (code.isEmpty() || codes.contains(code)) {
            return;
        }
        codes.add(code);
        if ("el-GR".equalsIgnoreCase(code)) {
            labels.add("Greek");
        } else if ("en-US".equalsIgnoreCase(code)) {
            labels.add("English");
        } else {
            labels.add(code);
        }
    }

    private void setMatchBusy(MatchDialogState state, boolean busy, String message) {
        state.busy = busy;
        state.dialog.setCancelable(!busy);
        state.query.setEnabled(!busy);
        state.year.setEnabled(!busy);
        state.language.setEnabled(!busy);
        state.search.setEnabled(!busy);
        state.close.setEnabled(!busy);
        state.search.setText(busy ? "Working..." : "Search");
        if (message != null) {
            state.status.setText(message);
        }
    }

    private void searchMediaMatches(MatchDialogState state) {
        if (state.busy || !state.dialog.isShowing() || state.item.ratingKey == null) {
            return;
        }
        String query = state.query.getText().toString().trim();
        if (query.isEmpty()) {
            state.status.setText("Enter a title or external ID.");
            state.query.requestFocus();
            return;
        }
        String year = state.year.getText().toString().trim();
        if (!year.isEmpty()) {
            try {
                int parsed = Integer.parseInt(year);
                if (parsed < 1800 || parsed > 2200) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException error) {
                state.status.setText("Enter a valid four-digit year.");
                state.year.requestFocus();
                return;
            }
        }
        int languageIndex = Math.max(0, state.language.getSelectedItemPosition());
        String language = state.languageCodes.get(Math.min(languageIndex, state.languageCodes.size() - 1));
        String path = "/api/media-match?ratingKey=" + enc(state.item.ratingKey)
                + "&title=" + enc(query)
                + "&language=" + enc(language);
        if (!year.isEmpty()) {
            path += "&year=" + enc(year);
        }
        String requestPath = path;
        int generation = ++state.generation;
        state.results.removeAllViews();
        setMatchBusy(state, true, "Searching Plex metadata...");
        runTask(null, () -> api.get(requestPath, Models.MediaMatchResponse.class), response -> {
            if (!state.dialog.isShowing() || generation != state.generation) {
                return;
            }
            setMatchBusy(state, false, "");
            renderMediaMatchResults(state, response);
        }, error -> {
            if (!state.dialog.isShowing() || generation != state.generation) {
                return;
            }
            setMatchBusy(state, false, "Could not search: " + error.getMessage());
        });
    }

    private void renderMediaMatchResults(MatchDialogState state, Models.MediaMatchResponse response) {
        state.results.removeAllViews();
        List<Models.MediaMatchCandidate> candidates = response == null || response.results == null
                ? Collections.emptyList()
                : response.results;
        if (candidates.isEmpty()) {
            state.status.setText("No matching titles found.");
            return;
        }
        state.status.setText(candidates.size() + (candidates.size() == 1 ? " match" : " matches"));
        for (Models.MediaMatchCandidate candidate : candidates) {
            LinearLayout result = new LinearLayout(this);
            result.setOrientation(LinearLayout.VERTICAL);
            result.setPadding(0, dp(10), 0, dp(10));

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            FrameLayout posterFrame = new FrameLayout(this);
            posterFrame.setBackgroundColor(palette.poster);
            TextView fallback = text("show".equals(candidate.type) ? "TV" : "Film", 13, true);
            fallback.setTextColor(palette.posterText);
            fallback.setGravity(Gravity.CENTER);
            posterFrame.addView(fallback, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setContentDescription(Models.nonEmpty(candidate.name, "Match") + " poster");
            posterFrame.addView(poster, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            if (candidate.posterUrl != null) {
                imageLoader.load(candidate.posterUrl, poster);
            }
            LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(dp(84), dp(126));
            posterParams.setMargins(0, 0, dp(12), 0);
            top.addView(posterFrame, posterParams);

            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(Models.nonEmpty(candidate.name, "Untitled"), 17, true);
            copy.addView(name);
            List<String> metadata = new ArrayList<>();
            if (candidate.year != null) metadata.add(String.valueOf(candidate.year));
            metadata.add("show".equals(candidate.type) ? "TV show" : "Movie");
            if (candidate.best) metadata.add("Best match");
            if (candidate.current) metadata.add("Current");
            if (candidate.posterCanApply) metadata.add("Poster available");
            TextView meta = text(Models.join(metadata, "  "), 12, false);
            meta.setTextColor(colorMuted());
            copy.addView(meta);
            if (candidate.summary != null && !candidate.summary.trim().isEmpty()) {
                TextView summary = text(candidate.summary, 13, false);
                summary.setPadding(0, dp(6), 0, 0);
                summary.setMaxLines(4);
                summary.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(summary);
            }
            top.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            result.addView(top);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(dp(96), dp(8), 0, 0);
            Button useMatch = compactButton(candidate.current ? "Refresh match" : "Use match");
            useMatch.setOnClickListener(v -> confirmMediaMatch(state, candidate, false));
            actions.addView(useMatch, new LinearLayout.LayoutParams(0, dp(40), 1));
            if (candidate.posterCanApply && candidate.posterUrl != null) {
                Button usePoster = compactButton("Use poster");
                usePoster.setOnClickListener(v -> confirmMediaMatch(state, candidate, true));
                actions.addView(usePoster, new LinearLayout.LayoutParams(0, dp(40), 1));
            }
            result.addView(actions);
            state.results.addView(result);

            View divider = new View(this);
            divider.setBackgroundColor(palette.surfaceMuted);
            state.results.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1)
            ));
        }
    }

    private void confirmMediaMatch(
            MatchDialogState state,
            Models.MediaMatchCandidate candidate,
            boolean posterOnly
    ) {
        if (state.busy) {
            return;
        }
        String year = candidate.year == null ? "" : " (" + candidate.year + ")";
        String message = posterOnly
                ? "Use this poster for " + state.item.displayTitle()
                + "? Only the poster will change; Plex will keep the title, description, match, watch state, collections, and video."
                : (candidate.current ? "Refresh metadata from " : "Use ")
                + candidate.name + year + " for " + state.item.displayTitle()
                + "? Plex will update the title, poster, description, and related metadata.";
        new AlertDialog.Builder(this)
                .setTitle(posterOnly ? "Use poster?" : (candidate.current ? "Refresh match?" : "Use match?"))
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(posterOnly ? "Use poster" : (candidate.current ? "Refresh" : "Use match"),
                        (dialog, which) -> applyMediaMatch(state, candidate, posterOnly))
                .show();
    }

    private void applyMediaMatch(
            MatchDialogState state,
            Models.MediaMatchCandidate candidate,
            boolean posterOnly
    ) {
        if (state.busy || state.item.ratingKey == null) {
            return;
        }
        setMatchBusy(state, true, posterOnly ? "Applying poster..." : "Applying Plex match...");
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", state.item.ratingKey);
        String path;
        if (posterOnly) {
            payload.addProperty("posterUrl", candidate.posterUrl);
            path = "/api/media-poster";
        } else {
            payload.addProperty("guid", candidate.guid);
            payload.addProperty("name", candidate.name);
            if (candidate.year != null) payload.addProperty("year", candidate.year);
            path = "/api/media-match";
        }
        runTask(null, () -> {
            Models.MediaMetadataResponse response = api.post(path, payload, Models.MediaMetadataResponse.class);
            Models.ItemResponse refreshed = api.get(
                    "/api/metadata/" + enc(state.item.ratingKey)
                            + "?refresh=1&android=" + System.currentTimeMillis(),
                    Models.ItemResponse.class
            );
            if (response != null && refreshed != null && refreshed.item != null) {
                response.item = refreshed.item;
            }
            return response;
        }, response -> {
            if (response != null && response.item != null) {
                applyMetadataItem(state.item, response.item);
            }
            state.dialog.dismiss();
            if (state.detailsDialog != null && state.detailsDialog.isShowing()) {
                state.detailsDialog.dismiss();
            }
            renderCurrent();
            String message = posterOnly
                    ? "Poster updated for " + state.item.displayTitle() + "."
                    : candidate.current
                    ? "Plex metadata refreshed for " + state.item.displayTitle() + "."
                    : "Matched as " + state.item.displayTitle() + ".";
            setStatus(message);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }, error -> {
            if (state.dialog.isShowing()) {
                setMatchBusy(state, false, "Could not apply selection: " + error.getMessage());
            }
            setStatus("Could not update metadata: " + error.getMessage());
        });
    }

    private void confirmMetadataRefresh(Dialog detailsDialog, Models.MediaItem item, Button button) {
        new AlertDialog.Builder(this)
                .setTitle("Refresh metadata?")
                .setMessage("Ask Plex to refresh " + item.displayTitle()
                        + " from its current match? Its poster, description, ratings, cast, and other metadata may change."
                        + " The video file, watch state, and collections will stay unchanged.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Refresh", (dialog, which) -> refreshMetadata(detailsDialog, item, button))
                .show();
    }

    private void refreshMetadata(Dialog detailsDialog, Models.MediaItem item, Button button) {
        if (item.ratingKey == null) {
            return;
        }
        button.setEnabled(false);
        button.setText("Refreshing...");
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        runTask("Refreshing metadata...", () -> {
            Models.MediaMetadataResponse response = api.post(
                    "/api/media-refresh",
                    payload,
                    Models.MediaMetadataResponse.class
            );
            Models.ItemResponse refreshed = api.get(
                    "/api/metadata/" + enc(item.ratingKey)
                            + "?refresh=1&android=" + System.currentTimeMillis(),
                    Models.ItemResponse.class
            );
            if (response != null && refreshed != null && refreshed.item != null) {
                response.item = refreshed.item;
            }
            return response;
        }, response -> {
            if (response != null && response.item != null) {
                applyMetadataItem(item, response.item);
            }
            detailsDialog.dismiss();
            renderCurrent();
            String message = response != null && response.pending
                    ? "Plex accepted the refresh for " + item.displayTitle() + ". Updates may continue in the background."
                    : "Metadata updated for " + item.displayTitle() + ".";
            setStatus(message);
            Toast.makeText(this, "Metadata refresh complete.", Toast.LENGTH_SHORT).show();
        }, error -> {
            if (detailsDialog.isShowing()) {
                button.setEnabled(true);
                button.setText("Refresh metadata");
            }
            setStatus("Could not refresh metadata: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void applyMetadataItem(Models.MediaItem target, Models.MediaItem source) {
        if (target == null || source == null) {
            return;
        }
        target.key = source.key;
        target.type = source.type;
        target.guid = source.guid;
        target.title = source.title;
        target.sortTitle = source.sortTitle;
        target.year = source.year;
        target.summary = source.summary;
        target.tagline = source.tagline;
        target.contentRating = source.contentRating;
        target.rating = source.rating;
        target.audienceRating = source.audienceRating;
        target.duration = source.duration;
        target.durationText = source.durationText;
        target.viewOffset = source.viewOffset;
        target.addedAt = source.addedAt;
        target.addedDate = source.addedDate;
        target.updatedAt = source.updatedAt;
        target.viewCount = source.viewCount;
        target.lastViewedAt = source.lastViewedAt;
        target.lastViewedDate = source.lastViewedDate;
        target.originallyAvailableAt = source.originallyAvailableAt;
        target.librarySectionID = source.librarySectionID;
        target.librarySectionTitle = source.librarySectionTitle;
        target.parentRatingKey = source.parentRatingKey;
        target.grandparentRatingKey = source.grandparentRatingKey;
        target.parentTitle = source.parentTitle;
        target.grandparentTitle = source.grandparentTitle;
        target.index = source.index;
        target.parentIndex = source.parentIndex;
        target.leafCount = source.leafCount;
        target.viewedLeafCount = source.viewedLeafCount;
        target.childCount = source.childCount;
        target.subtype = source.subtype;
        target.smart = source.smart;
        target.thumb = source.thumb;
        target.art = source.art;
        target.posterUrl = source.posterUrl;
        target.artUrl = source.artUrl;
        target.partKey = source.partKey;
        target.streamUrl = source.streamUrl;
        target.compatibleStreamUrl = source.compatibleStreamUrl;
        target.downloadOriginalUrl = source.downloadOriginalUrl;
        target.playback = source.playback;
        target.savedPlayback = source.savedPlayback;
        target.subtitles = source.subtitles;
        target.collections = source.collections;
        target.media = source.media;
        target.guids = source.guids;
        target.imdb = source.imdb;
        target.tmdb = source.tmdb;
        target.tvdb = source.tvdb;
        target.inMyList = target.ratingKey != null && myListKeys.contains(target.ratingKey);
        target.inPlayQueue = target.ratingKey != null && playQueueKeys.contains(target.ratingKey);
        if (target.ratingKey != null) {
            hydratedItems.put(target.ratingKey, target);
        }
    }

    private void loadMediaDeletePlan(Dialog detailsDialog, Models.MediaItem item, Button deleteButton) {
        if (item == null || item.ratingKey == null || !mediaDeletionEnabled) {
            return;
        }
        deleteButton.setEnabled(false);
        deleteButton.setText("Inspecting disk...");
        runTask(null, () -> api.get(
                "/api/media-delete?ratingKey=" + enc(item.ratingKey),
                Models.MediaDeletePlan.class
        ), plan -> {
            if (!detailsDialog.isShowing()) {
                return;
            }
            deleteButton.setEnabled(true);
            deleteButton.setText("Delete from disk");
            showMediaDeleteConfirmation(detailsDialog, item, deleteButton, plan);
        }, error -> {
            if (!detailsDialog.isShowing()) {
                return;
            }
            deleteButton.setEnabled(true);
            deleteButton.setText("Delete from disk");
            setStatus("Could not inspect files: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void showMediaDeleteConfirmation(
            Dialog detailsDialog,
            Models.MediaItem item,
            Button deleteButton,
            Models.MediaDeletePlan plan
    ) {
        if (plan == null || plan.confirmationToken == null) {
            Toast.makeText(this, "The server did not return a deletion plan.", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(4), dp(22), 0);

        String fileLabel = plan.fileCount + (plan.fileCount == 1 ? " file" : " files");
        String folderLabel = plan.folderCount > 0
                ? " and " + plan.folderCount + (plan.folderCount == 1 ? " complete folder" : " complete folders")
                : "";
        TextView summary = text(
                fileLabel + folderLabel + " (" + Models.nonEmpty(plan.totalSizeText, "unknown size")
                        + ") will be permanently removed.",
                15,
                false
        );
        summary.setPadding(0, 0, 0, dp(10));
        content.addView(summary);

        List<String> paths = new ArrayList<>();
        if (plan.folders != null) {
            for (String path : plan.folders) {
                paths.add("Folder: " + path);
            }
        }
        if (plan.files != null) {
            for (String path : plan.files) {
                paths.add("File: " + path);
            }
        }
        if (!paths.isEmpty()) {
            TextView pathList = text(Models.join(paths, "\n"), 12, false);
            pathList.setTypeface(Typeface.MONOSPACE);
            pathList.setTextColor(colorMuted());
            pathList.setPadding(0, 0, 0, dp(10));
            content.addView(pathList);
        }

        TextView warningView = text("", 13, false);
        if (plan.warnings != null && !plan.warnings.isEmpty()) {
            List<String> warnings = new ArrayList<>();
            for (String warning : plan.warnings) {
                warnings.add("- " + warning);
            }
            warningView.setText(Models.join(warnings, "\n"));
            warningView.setTextColor(palette.danger);
            warningView.setPadding(0, 0, 0, dp(10));
            content.addView(warningView);
        }

        TextView prompt = text(
                plan.canDelete ? "Type DELETE to confirm" : "Deletion is currently blocked",
                13,
                true
        );
        content.addView(prompt);
        EditText confirmation = edit("DELETE");
        confirmation.setSingleLine(true);
        confirmation.setAllCaps(true);
        confirmation.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        confirmation.setEnabled(plan.canDelete);
        content.addView(confirmation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView status = text(plan.canDelete ? "" : Models.nonEmpty(plan.blockReason, "Disk deletion is currently blocked."), 13, false);
        if (!plan.canDelete) {
            status.setTextColor(palette.danger);
        }
        status.setPadding(0, dp(8), 0, 0);
        content.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog confirmDialog = new AlertDialog.Builder(this)
                .setTitle("Delete " + item.displayTitle() + "?")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete permanently", null)
                .create();
        confirmDialog.setOnShowListener(ignored -> {
            Button positive = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setTextColor(palette.danger);
            positive.setEnabled(false);
            confirmation.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence value, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence value, int start, int before, int count) {
                    positive.setEnabled(plan.canDelete && "DELETE".contentEquals(value.toString().trim()));
                }

                @Override
                public void afterTextChanged(Editable value) {
                }
            });
            positive.setOnClickListener(v -> executeMediaDelete(
                    detailsDialog,
                    confirmDialog,
                    item,
                    deleteButton,
                    confirmation,
                    status,
                    plan
            ));
            if (plan.canDelete) {
                confirmation.requestFocus();
                Window window = confirmDialog.getWindow();
                if (window != null) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                }
            }
        });
        confirmDialog.show();
    }

    private void executeMediaDelete(
            Dialog detailsDialog,
            AlertDialog confirmDialog,
            Models.MediaItem item,
            Button deleteButton,
            EditText confirmation,
            TextView status,
            Models.MediaDeletePlan plan
    ) {
        if (!"DELETE".equals(confirmation.getText().toString().trim())) {
            confirmation.setError("Type DELETE exactly");
            return;
        }
        Button positive = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = confirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        positive.setEnabled(false);
        negative.setEnabled(false);
        confirmation.setEnabled(false);
        status.setText("Deleting original files from disk...");
        status.setTextColor(colorMuted());
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        payload.addProperty("confirmationToken", plan.confirmationToken);
        payload.addProperty("confirmation", confirmation.getText().toString().trim());
        runTask(null, () -> api.post(
                "/api/media-delete",
                payload,
                Models.MediaDeleteResponse.class
        ), response -> {
            confirmDialog.dismiss();
            detailsDialog.dismiss();
            applyDeletedMedia(item);
            String scan = response != null && response.scanStarted ? " Plex is scanning the library." : "";
            setStatus("Deleted " + item.displayTitle() + " from disk." + scan);
            Toast.makeText(this, "Deleted from disk.", Toast.LENGTH_LONG).show();
        }, error -> {
            status.setText(error.getMessage());
            status.setTextColor(palette.danger);
            confirmation.setEnabled(true);
            negative.setEnabled(true);
            positive.setEnabled(false);
            deleteButton.setEnabled(true);
            confirmation.setText("");
            confirmation.requestFocus();
        });
    }

    private void applyDeletedMedia(Models.MediaItem item) {
        if (item == null || item.ratingKey == null) {
            return;
        }
        String ratingKey = item.ratingKey;
        boolean removed = currentItems.removeIf(candidate -> ratingKey.equals(candidate.ratingKey));
        if (removed) {
            loadedCount = currentItems.size();
            totalCount = libraryMode ? Math.max(0, totalCount - 1) : currentItems.size();
        }
        for (ScreenState screen : backStack) {
            int before = screen.items == null ? 0 : screen.items.size();
            if (screen.items != null) {
                screen.items.removeIf(candidate -> ratingKey.equals(candidate.ratingKey));
            }
            if (screen.items != null && screen.items.size() < before) {
                screen.loadedCount = screen.items.size();
                screen.totalCount = screen.libraryMode
                        ? Math.max(0, screen.totalCount - 1)
                        : screen.items.size();
            }
        }
        myListKeys.remove(ratingKey);
        playQueueKeys.remove(ratingKey);
        hydratedItems.remove(ratingKey);
        prefs.edit().remove("progress:" + ratingKey).apply();
        deviceCache.delete(item);
        if ("movie".equals(item.type)) {
            collectionLibraryRefreshPending = true;
        }
        renderCurrent();
    }

    private String collectionButtonLabel(Models.MediaItem item) {
        int count = item.collections == null ? 0 : item.collections.size();
        return count > 0 ? "Collections (" + count + ")" : "Collections";
    }

    private void openCollectionMembershipDialog(Dialog detailsDialog, Models.MediaItem item, Button detailsButton) {
        Dialog dialog = new Dialog(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(16), dp(16), dp(16), dp(16));
        shell.setBackgroundColor(colorPaper());

        TextView title = text("Collections for " + item.displayTitle(), 21, true);
        shell.addView(title);

        TextView status = text("Loading collections...", 13, false);
        status.setTextColor(colorMuted());
        status.setPadding(0, dp(6), 0, dp(8));
        shell.addView(status);

        Button create = button("New collection");
        stylePrimaryButton(create);
        shell.addView(create, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button close = button("Close");
        close.setOnClickListener(v -> dialog.dismiss());
        shell.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        dialog.setContentView(shell);
        dialog.show();
        sizeDialog(dialog, 0.94f, 0.82f);
        create.setOnClickListener(v -> showCollectionNamePrompt(
                "New collection",
                "",
                name -> manageCollection(
                        dialog,
                        detailsDialog,
                        item,
                        detailsButton,
                        create,
                        list,
                        status,
                        "create",
                        null,
                        name
                )
        ));

        runTask(null, () -> api.get(
                "/api/collection-membership?ratingKey=" + enc(item.ratingKey),
                Models.CollectionMembershipResponse.class
        ), response -> {
            if (!dialog.isShowing()) {
                return;
            }
            applyCollectionMembershipItem(item, response, detailsButton);
            renderCollectionMembershipRows(dialog, detailsDialog, item, detailsButton, create, list, status, response);
        }, error -> {
            if (dialog.isShowing()) {
                status.setText("Could not load collections: " + error.getMessage());
                status.setTextColor(palette.danger);
            }
        });
    }

    private void renderCollectionMembershipRows(
            Dialog dialog,
            Dialog detailsDialog,
            Models.MediaItem item,
            Button detailsButton,
            Button createButton,
            LinearLayout list,
            TextView status,
            Models.CollectionMembershipResponse response
    ) {
        list.removeAllViews();
        List<Models.CollectionMembership> collections = response == null || response.collections == null
                ? Collections.emptyList()
                : response.collections;
        if (collections.isEmpty()) {
            status.setText("This library has no collections.");
            status.setTextColor(colorMuted());
            return;
        }
        int selected = 0;
        for (Models.CollectionMembership collection : collections) {
            if (collection.member) {
                selected++;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            CheckBox checkbox = new CheckBox(this);
            checkbox.setText(collection.title + (collection.editable ? "" : " (Smart)"));
            checkbox.setTextColor(collection.editable ? colorInk() : colorMuted());
            checkbox.setButtonTintList(ColorStateList.valueOf(colorAccent()));
            checkbox.setChecked(collection.member);
            checkbox.setEnabled(collection.editable);
            checkbox.setContentDescription(
                    (collection.member ? "Remove from " : "Add to ") + collection.title
            );
            row.addView(checkbox, new LinearLayout.LayoutParams(0, dp(48), 1));

            TextView count = text(
                    collection.childCount + (collection.childCount == 1 ? " movie" : " movies"),
                    12,
                    false
            );
            count.setTextColor(colorMuted());
            row.addView(count);

            if (collection.editable) {
                Button actions = compactButton("...");
                actions.setContentDescription("Actions for " + collection.title);
                actions.setOnClickListener(v -> showCollectionActions(
                        actions,
                        dialog,
                        detailsDialog,
                        item,
                        detailsButton,
                        createButton,
                        list,
                        status,
                        collection
                ));
                row.addView(actions, new LinearLayout.LayoutParams(dp(48), dp(44)));
            }
            list.addView(row);

            checkbox.setOnClickListener(v -> updateCollectionMembership(
                    dialog,
                    detailsDialog,
                    item,
                    detailsButton,
                    createButton,
                    list,
                    status,
                    collection,
                    checkbox,
                    checkbox.isChecked()
            ));
        }
        status.setText(selected + " of " + collections.size() + " collections selected.");
        status.setTextColor(colorMuted());
    }

    private void updateCollectionMembership(
            Dialog dialog,
            Dialog detailsDialog,
            Models.MediaItem item,
            Button detailsButton,
            Button createButton,
            LinearLayout list,
            TextView status,
            Models.CollectionMembership collection,
            CheckBox checkbox,
            boolean member
    ) {
        checkbox.setEnabled(false);
        status.setText((member ? "Adding to " : "Removing from ") + collection.title + "...");
        status.setTextColor(colorMuted());
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        payload.addProperty("collectionRatingKey", collection.ratingKey);
        payload.addProperty("member", member);
        runTask(null, () -> api.post(
                "/api/collection-membership",
                payload,
                Models.CollectionMembershipResponse.class
        ), response -> {
            if (!dialog.isShowing()) {
                return;
            }
            applyCollectionMembershipItem(item, response, detailsButton);
            collectionLibraryRefreshPending = true;
            if (!member && collection.ratingKey != null && collection.ratingKey.equals(currentCollectionRatingKey)) {
                currentItems.removeIf(candidate -> item.ratingKey.equals(candidate.ratingKey));
                loadedCount = currentItems.size();
                totalCount = currentItems.size();
                renderCurrent();
            }
            renderCollectionMembershipRows(
                    dialog,
                    detailsDialog,
                    item,
                    detailsButton,
                    createButton,
                    list,
                    status,
                    response
            );
            status.setText((member ? "Added to " : "Removed from ") + collection.title + ".");
            status.setTextColor(colorAccent());
        }, error -> {
            if (!dialog.isShowing()) {
                return;
            }
            checkbox.setChecked(!member);
            checkbox.setEnabled(true);
            status.setText("Could not update " + collection.title + ": " + error.getMessage());
            status.setTextColor(palette.danger);
        });
    }

    private void showCollectionActions(
            View anchor,
            Dialog dialog,
            Dialog detailsDialog,
            Models.MediaItem item,
            Button detailsButton,
            Button createButton,
            LinearLayout list,
            TextView status,
            Models.CollectionMembership collection
    ) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Rename");
        menu.getMenu().add("Delete");
        menu.setOnMenuItemClickListener(menuItem -> {
            if ("Rename".contentEquals(menuItem.getTitle())) {
                showCollectionNamePrompt(
                        "Rename collection",
                        collection.title,
                        name -> manageCollection(
                                dialog,
                                detailsDialog,
                                item,
                                detailsButton,
                                createButton,
                                list,
                                status,
                                "rename",
                                collection,
                                name
                        )
                );
                return true;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Delete " + collection.title + "?")
                    .setMessage("The collection will be removed. Its movies will remain in your Plex library.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (confirmDialog, which) -> manageCollection(
                            dialog,
                            detailsDialog,
                            item,
                            detailsButton,
                            createButton,
                            list,
                            status,
                            "delete",
                            collection,
                            null
                    ))
                    .show();
            return true;
        });
        menu.show();
    }

    private void showCollectionNamePrompt(String heading, String initialValue, CollectionNameAction action) {
        EditText input = edit("Collection name");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(initialValue);
        input.setSelection(input.getText().length());
        input.setPadding(dp(20), dp(8), dp(20), dp(8));
        AlertDialog nameDialog = new AlertDialog.Builder(this)
                .setTitle(heading)
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        nameDialog.setOnShowListener(ignored -> {
            nameDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String title = input.getText().toString().trim();
                if (title.isEmpty()) {
                    input.setError("Enter a collection name");
                    return;
                }
                action.accept(title);
                nameDialog.dismiss();
            });
            input.requestFocus();
            Window window = nameDialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        nameDialog.show();
    }

    private void manageCollection(
            Dialog dialog,
            Dialog detailsDialog,
            Models.MediaItem item,
            Button detailsButton,
            Button createButton,
            LinearLayout list,
            TextView status,
            String action,
            Models.CollectionMembership collection,
            String title
    ) {
        createButton.setEnabled(false);
        status.setText(collectionOperationStatus(action, collection, title));
        status.setTextColor(colorMuted());
        JsonObject payload = new JsonObject();
        payload.addProperty("action", action);
        payload.addProperty("ratingKey", item.ratingKey);
        if (collection != null) {
            payload.addProperty("collectionRatingKey", collection.ratingKey);
        }
        if (title != null) {
            payload.addProperty("title", title);
        }
        runTask(null, () -> api.post(
                "/api/collection-management",
                payload,
                Models.CollectionMembershipResponse.class
        ), response -> {
            if (!dialog.isShowing()) {
                return;
            }
            applyCollectionMembershipItem(item, response, detailsButton);
            collectionLibraryRefreshPending = true;
            if ("rename".equals(action)
                    && collection != null
                    && collection.ratingKey != null
                    && collection.ratingKey.equals(currentCollectionRatingKey)) {
                currentTitle = title;
                renderCurrent();
            }
            if ("delete".equals(action)
                    && collection != null
                    && collection.ratingKey != null
                    && collection.ratingKey.equals(currentCollectionRatingKey)) {
                dialog.dismiss();
                detailsDialog.dismiss();
                if (!backStack.isEmpty()) {
                    restoreScreen(backStack.pop());
                } else {
                    loadLibrary(false);
                }
                setStatus("Deleted " + collection.title + ". Movies remain in the library.");
                return;
            }
            createButton.setEnabled(true);
            renderCollectionMembershipRows(
                    dialog,
                    detailsDialog,
                    item,
                    detailsButton,
                    createButton,
                    list,
                    status,
                    response
            );
            if ("create".equals(action)) {
                status.setText("Created " + title + " and added " + item.displayTitle() + ".");
            } else if ("rename".equals(action)) {
                status.setText("Renamed " + collection.title + " to " + title + ".");
            } else {
                status.setText("Deleted " + collection.title + ". Movies remain in the library.");
            }
            status.setTextColor(colorAccent());
        }, error -> {
            if (!dialog.isShowing()) {
                return;
            }
            createButton.setEnabled(true);
            status.setText("Could not update collection: " + collectionErrorMessage(error.getMessage()));
            status.setTextColor(palette.danger);
        });
    }

    private String collectionOperationStatus(
            String action,
            Models.CollectionMembership collection,
            String title
    ) {
        if ("create".equals(action)) {
            return "Creating " + title + "...";
        }
        if ("rename".equals(action)) {
            return "Renaming " + collection.title + "...";
        }
        return "Deleting " + collection.title + "...";
    }

    private String collectionErrorMessage(String message) {
        if ("collection_title_already_exists".equals(message)) {
            return "A collection with this name already exists.";
        }
        if ("invalid_collection_title".equals(message)) {
            return "Enter a collection name between 1 and 120 characters.";
        }
        if ("smart_collection_read_only".equals(message)) {
            return "Smart collections are managed automatically by Plex.";
        }
        if ("collection_not_found".equals(message)) {
            return "This collection no longer exists in Plex.";
        }
        return message;
    }

    private void applyCollectionMembershipItem(
            Models.MediaItem item,
            Models.CollectionMembershipResponse response,
            Button detailsButton
    ) {
        if (response != null && response.item != null) {
            item.collections = response.item.collections == null
                    ? new ArrayList<>()
                    : response.item.collections;
        }
        detailsButton.setText(collectionButtonLabel(item));
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

    private void updatePlayQueue(Dialog dialog, Models.MediaItem item, Button button, boolean queued) {
        if (item.ratingKey == null) {
            return;
        }
        button.setEnabled(false);
        button.setText("Updating...");
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        payload.addProperty("queued", queued);
        runTask("Updating Play Queue...", () ->
                api.post("/api/play-queue", payload, Models.PlayQueueResponse.class), response -> {
            playQueueKeys.clear();
            if (response != null && response.queueRatingKeys != null) {
                playQueueKeys.addAll(response.queueRatingKeys);
            }
            item.inPlayQueue = queued;
            dialog.dismiss();
            if (libraryMode && "queue".equals(viewMode)) {
                loadLibrary(false);
            } else {
                renderCurrent();
                setStatus(item.displayTitle() + (queued ? " added to" : " removed from") + " Play Queue.");
            }
        }, error -> {
            button.setEnabled(true);
            button.setText(queued ? "Add to Queue" : "Remove from Queue");
            setStatus("Could not update Play Queue: " + error.getMessage());
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
        playItem(item, false);
    }

    private void playItem(Models.MediaItem item, boolean startFromBeginning) {
        if (deviceCache.status(item) != null) {
            if (startFromBeginning) {
                clearResumeProgress(item);
                syncOfflineRestart(item);
            }
            showPlayer(item, startFromBeginning);
            return;
        }
        if (localCatalogActive()) {
            loadLibrary(false);
            setStatus("That download is no longer complete on this Pixel.");
            Toast.makeText(this, "Download unavailable. Nothing was streamed.", Toast.LENGTH_LONG).show();
            return;
        }
        runTask(startFromBeginning ? "Starting over..." : "Preparing playback...", () -> {
            Models.MediaItem hydrated = hydrate(item);
            if (startFromBeginning) {
                JsonObject payload = new JsonObject();
                payload.addProperty("ratingKey", hydrated.ratingKey);
                payload.addProperty("timeMs", 0);
                payload.addProperty("durationMs", hydrated.duration == null ? 0L : hydrated.duration);
                payload.addProperty("state", "restarted");
                api.post("/api/playback-progress", payload, Models.PlaybackProgressResponse.class);
                hydrated.viewOffset = 0L;
            }
            if (hydrated.savedPlayback == null) {
                refreshSavedPlayback(hydrated);
            }
            return hydrated;
        }, hydrated -> {
            if (startFromBeginning) {
                clearResumeProgress(hydrated);
            }
            showPlayer(hydrated, startFromBeginning);
        });
    }

    private void syncOfflineRestart(Models.MediaItem item) {
        if (item == null || item.ratingKey == null || offlineMode || !isNetworkAvailable()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        payload.addProperty("timeMs", 0);
        payload.addProperty("durationMs", item.duration == null ? 0L : item.duration);
        payload.addProperty("state", "restarted");
        runTask(null, () -> api.post(
                "/api/playback-progress",
                payload,
                Models.PlaybackProgressResponse.class
        ), response -> { }, error -> { });
    }

    private void clearResumeProgress(Models.MediaItem item) {
        if (item == null || item.ratingKey == null) {
            return;
        }
        String ratingKey = item.ratingKey;
        prefs.edit().remove("progress:" + ratingKey).apply();
        item.viewOffset = 0L;
        Models.MediaItem cached = hydratedItems.get(ratingKey);
        if (cached != null) {
            cached.viewOffset = 0L;
        }
        for (Models.MediaItem candidate : currentItems) {
            if (ratingKey.equals(candidate.ratingKey)) {
                candidate.viewOffset = 0L;
            }
        }
    }

    private void showPlayer(Models.MediaItem item) {
        showPlayer(item, false);
    }

    private void showPlayer(Models.MediaItem item, boolean startFromBeginning) {
        cancelAutoplayNextCountdown();
        playerItem = item;
        playerNeighbors = null;
        long initialResumeMs = startFromBeginning ? 0L : resumeTimeFor(item);
        fillVideo = true;
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(Color.BLACK);
        shell.setClickable(true);
        shell.setFocusable(true);
        shell.setElevation(dp(32));

        playerControls = new LinearLayout(this);
        playerControls.setOrientation(LinearLayout.HORIZONTAL);
        playerControls.setGravity(Gravity.CENTER_VERTICAL);
        playerControls.setPadding(dp(8), dp(6), dp(8), dp(6));
        playerControls.setBackgroundColor(Color.argb(180, 20, 20, 20));

        playbackModeView = text("", 12, true);
        playbackModeView.setTextColor(Color.WHITE);
        playerControls.addView(playbackModeView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        saveButton = compactButton("Save offline");
        deleteSavedButton = compactButton("Delete stream");
        saveDeviceButton = compactButton("Prepare stream");
        deleteDeviceButton = compactButton("Delete offline");
        resizeButton = compactButton("Fit");
        resizeButton.setContentDescription("Fit video");
        playbackSpeedButton = compactButton(playbackSpeedLabel());
        playbackSpeedButton.setContentDescription("Playback speed");
        restartOverlayButton = compactButton("Start over");
        restartOverlayButton.setContentDescription("Start playback from the beginning");
        restartOverlayButton.setVisibility(initialResumeMs > 0 ? View.VISIBLE : View.GONE);
        Button subtitles = compactButton("Find");
        Button close = compactButton("X");
        closeOverlayButton = compactButton("X");
        closeOverlayButton.setContentDescription("Close player");

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

        saveButton.setOnClickListener(v -> saveDeviceCopy());
        deleteSavedButton.setOnClickListener(v -> deleteServerCopy());
        saveDeviceButton.setOnClickListener(v -> saveServerCopy(true));
        deleteDeviceButton.setOnClickListener(v -> deleteDeviceCopy());
        resizeButton.setOnClickListener(v -> {
            fillVideo = !fillVideo;
            applyPlayerResizeMode();
            showPlayerControlsTemporarily();
        });
        keepPlayerControlTouchable(resizeButton);
        playbackSpeedButton.setOnClickListener(this::showPlaybackSpeedMenu);
        keepPlayerControlTouchable(playbackSpeedButton);
        restartOverlayButton.setOnClickListener(v -> restartCurrentPlayback());
        keepPlayerControlTouchable(restartOverlayButton);
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
            Models.MediaItem next = nextContinuationItem();
            if (next != null) {
                playFollowingItem(next, false);
            }
        });
        cancelAutoplayNextButton.setOnClickListener(v -> cancelAutoplayNextCountdown());
        close.setOnClickListener(v -> closePlayer());

        playerControls.addView(saveButton);
        playerControls.addView(deleteDeviceButton);
        playerControls.addView(saveDeviceButton);
        playerControls.addView(deleteSavedButton);
        playerControls.addView(subtitles);
        playerControls.addView(close);

        playerView = new PlayerView(this);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setShowSubtitleButton(true);
        playerView.setControllerShowTimeoutMs((int) PLAYER_CONTROLS_TIMEOUT_MS);
        playerView.setOnTouchListener((view, event) -> {
            showPlayerControlsTemporarily();
            return false;
        });
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            if (visibility == View.VISIBLE) {
                showPlayerControlsTemporarily();
            }
        });
        applyPlayerResizeMode();
        shell.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        closeOverlayButton.setOnClickListener(v -> closePlayer());
        keepPlayerControlTouchable(closeOverlayButton);
        LinearLayout playerOverlayActions = new LinearLayout(this);
        playerOverlayActions.setOrientation(LinearLayout.HORIZONTAL);
        playerOverlayActions.setGravity(Gravity.CENTER_VERTICAL);
        playerOverlayActions.setElevation(dp(16));
        playerOverlayActions.addView(restartOverlayButton, new LinearLayout.LayoutParams(dp(104), dp(52)));
        playerOverlayActions.addView(playbackSpeedButton, new LinearLayout.LayoutParams(dp(68), dp(52)));
        playerOverlayActions.addView(resizeButton, new LinearLayout.LayoutParams(dp(72), dp(52)));
        playerOverlayActions.addView(closeOverlayButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        FrameLayout.LayoutParams overlayActionsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(52),
                Gravity.TOP | Gravity.LEFT
        );
        overlayActionsParams.setMargins(dp(20), dp(38), 0, 0);
        shell.addView(playerOverlayActions, overlayActionsParams);
        installPlayerOverlayInsets(shell, overlayActionsParams);
        FrameLayout.LayoutParams continuationParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(52),
                Gravity.BOTTOM | Gravity.LEFT
        );
        continuationParams.setMargins(dp(10), 0, dp(10), dp(82));
        shell.addView(episodeContinuationControls, continuationParams);
        // Keep playback clean: the player is closed with Back, and secondary
        // actions stay off-screen instead of occupying the video surface.

        playerLayer = shell;
        ViewGroup content = findViewById(android.R.id.content);
        content.addView(shell, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            applyFullscreen(window);
            shell.requestApplyInsets();
        }
        playerOverlayControlsVisible = true;
        showPlayerControlsTemporarily();
        playPreferredSource(initialResumeMs, true);
        loadPlayerEpisodeNeighbors(item);
    }

    private boolean isPlayerOpen() {
        return playerLayer != null && playerLayer.getParent() != null;
    }

    private void closePlayer() {
        closePlayer(true);
    }

    private void closePlayer(boolean renderLibrary) {
        if (playerLayer == null && player == null) {
            return;
        }
        cancelAutoplayNextCountdown();
        cancelPictureInPictureDismissalCheck();
        reportProgress("stopped", true);
        restartingPlayback = false;
        stopProgressReporting();
        cancelPlayerControlsHide();
        releasePlayer();

        FrameLayout layer = playerLayer;
        playerLayer = null;
        if (layer != null && layer.getParent() instanceof ViewGroup) {
            ((ViewGroup) layer.getParent()).removeView(layer);
        }
        Window window = getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        playerControls = null;
        playerView = null;
        playbackModeView = null;
        saveButton = null;
        deleteSavedButton = null;
        saveDeviceButton = null;
        deleteDeviceButton = null;
        resizeButton = null;
        restartOverlayButton = null;
        playbackSpeedButton = null;
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
        pictureInPictureActive = false;
        if (renderLibrary && !isDestroyed()) {
            renderCurrent();
        }
    }

    private void schedulePictureInPictureDismissalCheck() {
        cancelPictureInPictureDismissalCheck();
        pictureInPictureDismissalRunnable = () -> {
            pictureInPictureDismissalRunnable = null;
            if (PictureInPicturePolicy.shouldReleaseAfterDismissal(
                    isPlayerOpen(),
                    activityResumed,
                    isInPictureInPictureMode()
            )) {
                closePlayer(false);
            }
        };
        main.postDelayed(pictureInPictureDismissalRunnable, 750L);
    }

    private void cancelPictureInPictureDismissalCheck() {
        if (pictureInPictureDismissalRunnable != null) {
            main.removeCallbacks(pictureInPictureDismissalRunnable);
            pictureInPictureDismissalRunnable = null;
        }
    }

    private boolean supportsPictureInPicture() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private boolean isPictureInPicturePlaybackActive() {
        return PictureInPicturePolicy.shouldEnter(
                isPlayerOpen(),
                player != null && player.getPlayWhenReady(),
                player == null ? Player.STATE_IDLE : player.getPlaybackState()
        );
    }

    private void enterPictureInPictureIfPossible() {
        if (!supportsPictureInPicture()
                || isInPictureInPictureMode()
                || !isPictureInPicturePlaybackActive()) {
            return;
        }
        try {
            enterPictureInPictureMode(buildPictureInPictureParams(false));
        } catch (RuntimeException ignored) {
            // PiP can be disabled for this app in Android settings.
        }
    }

    private void updatePictureInPictureParams() {
        if (!supportsPictureInPicture() || !isPlayerOpen()) {
            return;
        }
        try {
            setPictureInPictureParams(buildPictureInPictureParams(isPictureInPicturePlaybackActive()));
        } catch (RuntimeException ignored) {
            // Keep full-screen playback available if Android rejects PiP parameters.
        }
    }

    private PictureInPictureParams buildPictureInPictureParams(boolean autoEnter) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(pictureInPictureAspectRatio());
        if (playerView != null) {
            Rect source = new Rect();
            if (playerView.getGlobalVisibleRect(source) && !source.isEmpty()) {
                builder.setSourceRectHint(source);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
            builder.setSeamlessResizeEnabled(true);
        }
        return builder.build();
    }

    private Rational pictureInPictureAspectRatio() {
        if (player != null) {
            VideoSize size = player.getVideoSize();
            if (size.width > 0 && size.height > 0 && size.pixelWidthHeightRatio > 0f) {
                int adjustedWidth = Math.max(1, Math.round(size.width * size.pixelWidthHeightRatio));
                float ratio = adjustedWidth / (float) size.height;
                if (ratio >= 0.45f && ratio <= 2.35f) {
                    return new Rational(adjustedWidth, size.height);
                }
            }
        }
        return new Rational(16, 9);
    }

    private void updatePlayerPictureInPictureUi(boolean inPictureInPicture) {
        if (!isPlayerOpen()) {
            return;
        }
        if (playerView != null) {
            playerView.setUseController(!inPictureInPicture);
        }
        if (inPictureInPicture) {
            cancelPlayerControlsHide();
            setPlayerControlsVisible(false);
            if (episodeContinuationControls != null) {
                episodeContinuationControls.setVisibility(View.GONE);
            }
        } else {
            applyFullscreen();
            showPlayerControlsTemporarily();
        }
    }

    private void restartCurrentPlayback() {
        Models.MediaItem item = playerItem;
        Button restartButton = restartOverlayButton;
        if (item == null || item.ratingKey == null || player == null || restartButton == null || !restartButton.isEnabled()) {
            return;
        }
        restartingPlayback = true;
        stopProgressReporting();
        restartButton.setEnabled(false);
        restartButton.setText("Starting...");
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        payload.addProperty("timeMs", 0);
        payload.addProperty("durationMs", durationMs());
        payload.addProperty("state", "restarted");
        runTask(null, () -> api.post(
                "/api/playback-progress",
                payload,
                Models.PlaybackProgressResponse.class
        ), response -> {
            if (playerItem == null
                    || !item.ratingKey.equals(playerItem.ratingKey)
                    || player == null
                    || restartOverlayButton != restartButton) {
                restartingPlayback = false;
                if (player != null && player.isPlaying()) {
                    startProgressReporting();
                }
                return;
            }
            clearResumeProgress(item);
            player.seekTo(0L);
            player.play();
            restartingPlayback = false;
            startProgressReporting();
            restartButton.setText("Start over");
            restartButton.setEnabled(true);
            restartButton.setVisibility(View.GONE);
            showPlayerControlsTemporarily();
            setStatus(item.displayTitle() + " restarted from the beginning.");
        }, error -> {
            restartingPlayback = false;
            if (player != null && player.isPlaying()) {
                startProgressReporting();
            }
            if (restartOverlayButton == restartButton) {
                restartButton.setText("Start over");
                restartButton.setEnabled(true);
            }
            setStatus("Could not start over: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void loadPlayerEpisodeNeighbors(Models.MediaItem item) {
        playerNeighbors = null;
        updateEpisodeContinuationControls();
        if (localCatalogActive() || item == null || item.ratingKey == null || !"episode".equals(item.type)) {
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

    private Models.MediaItem nextQueuedItem() {
        if (!libraryMode || !"queue".equals(viewMode) || playerItem == null || playerItem.ratingKey == null) {
            return null;
        }
        boolean foundCurrent = false;
        for (Models.MediaItem item : currentItems) {
            if (item == null || item.ratingKey == null) {
                continue;
            }
            if (foundCurrent && item.canPlay()) {
                return item;
            }
            if (playerItem.ratingKey.equals(item.ratingKey)) {
                foundCurrent = true;
            }
        }
        return null;
    }

    private Models.MediaItem nextContinuationItem() {
        Models.MediaItem queued = nextQueuedItem();
        if (libraryMode && "queue".equals(viewMode)) {
            return queued;
        }
        return playerNeighbors == null ? null : playerNeighbors.next;
    }

    private void updateEpisodeContinuationControls() {
        if (episodeContinuationControls == null || autoplayNextSwitch == null) {
            return;
        }
        if (isInPictureInPictureMode()) {
            episodeContinuationControls.setVisibility(View.GONE);
            return;
        }
        boolean episode = playerItem != null && "episode".equals(playerItem.type);
        boolean queuePlayback = libraryMode && "queue".equals(viewMode);
        Models.MediaItem queuedNext = nextQueuedItem();
        boolean countdown = autoplayNextRunnable != null;
        episodeContinuationControls.setVisibility(
                ((episode && !queuePlayback) || queuedNext != null) && (playerOverlayControlsVisible || countdown)
                        ? View.VISIBLE
                        : View.GONE
        );
        boolean savedAutoplay = prefs.getBoolean(PREF_AUTOPLAY_NEXT, true);
        if (autoplayNextSwitch.isChecked() != savedAutoplay) {
            autoplayNextSwitch.setChecked(savedAutoplay);
        }
        Models.MediaItem next = queuedNext != null
                ? queuedNext
                : (queuePlayback || playerNeighbors == null ? null : playerNeighbors.next);
        nextEpisodeButton.setVisibility(next == null ? View.GONE : View.VISIBLE);
        cancelAutoplayNextButton.setVisibility(countdown ? View.VISIBLE : View.GONE);
        if (next != null) {
            String code = next.episodeCode();
            String label = queuedNext != null ? "Next queued" : code.isEmpty() ? "Next episode" : "Next " + code;
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
        Models.MediaItem next = nextContinuationItem();
        if (next == null || !prefs.getBoolean(PREF_AUTOPLAY_NEXT, true)) {
            return;
        }
        String currentKey = playerItem == null ? null : playerItem.ratingKey;
        autoplayNextSeconds = 5;
        autoplayNextRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPlayerOpen()
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
                    playFollowingItem(next, true);
                    return;
                }
                updateEpisodeContinuationControls();
                autoplayNextSeconds -= 1;
                main.postDelayed(this, 1000L);
            }
        };
        main.post(autoplayNextRunnable);
    }

    private void playFollowingItem(Models.MediaItem item, boolean ended) {
        if (item == null || !isPlayerOpen()) {
            return;
        }
        cancelAutoplayNextCountdown();
        if (!ended) {
            reportProgress("stopped", true);
        }
        stopProgressReporting();
        releasePlayer();
        runTask("Preparing " + item.displayTitle() + "...", () -> {
            Models.MediaItem hydrated = hydrate(item);
            if (hydrated.savedPlayback == null) {
                refreshSavedPlayback(hydrated);
            }
            return hydrated;
        }, hydrated -> {
            if (!isPlayerOpen()) {
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
                playbackModeView.setText("Offline");
                playMedia(deviceCache.localMediaItem(playerItem, deviceEntry), null, resumeMs, autoplay);
            } else if (localCatalogActive()) {
                throw new IOException("Download unavailable. Nothing was streamed.");
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
            if (localCatalogActive()) {
                closePlayer();
                loadLibrary(false);
            }
        }
    }

    private void playMedia(androidx.media3.common.MediaItem mediaItem, @Nullable String remoteUrl, long resumeMs, boolean autoplay) throws IOException {
        releasePlayer();
        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(api.httpClient())
                .setUserAgent("PlexOpenAndroid/" + BuildConfig.VERSION_NAME);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 60_000, 500, 1_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
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
                updatePictureInPictureParams();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    reportProgress("ended", true);
                    stopProgressReporting();
                    scheduleAutoplayNext();
                }
                updatePictureInPictureParams();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updatePictureInPictureParams();
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                updatePictureInPictureParams();
            }
        });
        mediaSession = new MediaSession.Builder(this, player).build();
        playerView.setPlayer(player);
        applyPlayerResizeMode();
        if (resumeMs > 0) {
            player.setMediaItem(mediaItem, resumeMs);
        } else {
            player.setMediaItem(mediaItem);
        }
        player.setPlayWhenReady(autoplay);
        player.setPlaybackSpeed(playbackSpeed());
        player.prepare();
        updatePictureInPictureParams();
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
        runTask("Preparing stream copy...", () -> waitForSavedPlayback(playerItem), saved -> {
            playerItem.savedPlayback = saved;
            Toast.makeText(this, "Stream copy is ready. It still uses internet data.", Toast.LENGTH_LONG).show();
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
        runTask("Deleting stream copy...", () -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("ratingKey", playerItem.ratingKey);
            payload.addProperty("action", "delete");
            Models.SavedPlaybackResponse response = api.post("/api/saved-playback", payload, Models.SavedPlaybackResponse.class);
            return response == null ? null : response.savedPlayback;
        }, saved -> {
            playerItem.savedPlayback = saved;
            Toast.makeText(this, "Deleted stream copy.", Toast.LENGTH_SHORT).show();
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
        runTask("Saving offline...", () -> {
            Models.SavedPlayback saved = waitForSavedPlayback(playerItem);
            playerItem.savedPlayback = saved;
            return deviceCache.save(api, playerItem, (bytes, total) -> {
                if (total > 0) {
                    int percent = (int) Math.min(99, Math.max(1, bytes * 100 / total));
                    main.post(() -> setStatus("Saving offline... " + percent + "%"));
                }
            });
        }, entry -> {
            Toast.makeText(this, "Saved offline. Video playback will use this device.", Toast.LENGTH_LONG).show();
            playPreferredSource(resume, autoplay);
        });
    }

    private void saveOfflineFromDetails(Dialog dialog, Models.MediaItem item, Button button) {
        if (item == null || item.ratingKey == null || deviceCache.status(item) != null) {
            button.setText("Offline ready");
            button.setEnabled(false);
            return;
        }
        button.setEnabled(false);
        button.setText("Preparing...");
        runTask("Preparing offline copy...", () -> {
            Models.MediaItem hydrated = hydrate(item);
            Models.SavedPlayback saved = waitForSavedPlayback(hydrated);
            hydrated.savedPlayback = saved;
            deviceCache.save(api, hydrated, (bytes, total) -> {
                if (total > 0) {
                    int percent = (int) Math.min(99, Math.max(1, bytes * 100 / total));
                    main.post(() -> {
                        setStatus("Saving offline... " + percent + "%");
                        if (dialog.isShowing()) {
                            button.setText(percent + "%");
                        }
                    });
                }
            });
            return hydrated;
        }, hydrated -> {
            item.savedPlayback = hydrated.savedPlayback;
            button.setText("Offline ready");
            button.setEnabled(false);
            setStatus(item.displayTitle() + " is ready offline.");
            Toast.makeText(this, "Saved offline. Video playback will use this device.", Toast.LENGTH_LONG).show();
        }, error -> {
            if (dialog.isShowing()) {
                button.setText("Save offline");
                button.setEnabled(true);
            }
            setStatus("Could not save offline: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void deleteDeviceCopy() {
        if (playerItem == null) {
            return;
        }
        long resume = currentPositionMs();
        boolean autoplay = player != null && player.isPlaying();
        deviceCache.delete(playerItem);
        Toast.makeText(this, "Deleted offline copy.", Toast.LENGTH_SHORT).show();
        if (localCatalogActive()) {
            if (isPlayerOpen()) {
                closePlayer();
            }
            loadLibrary(false);
            return;
        }
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
        boolean offlineReady = deviceEntry != null;
        boolean localCatalog = localCatalogActive();
        saveButton.setEnabled(!localCatalog && playerItem.ratingKey != null && !offlineReady);
        saveButton.setText(offlineReady ? "Offline ready" : "Save offline");
        deleteDeviceButton.setVisibility(offlineReady ? View.VISIBLE : View.GONE);
        saveDeviceButton.setVisibility(!localCatalog && !savedReady ? View.VISIBLE : View.GONE);
        saveDeviceButton.setEnabled(playerItem.ratingKey != null && playerItem.partKey != null);
        saveDeviceButton.setText("Prepare stream");
        deleteSavedButton.setVisibility(!localCatalog && savedReady ? View.VISIBLE : View.GONE);
        deleteSavedButton.setText("Delete stream");
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
            Toast.makeText(this, "Original ZIP download started. Use Save offline for offline playback.", Toast.LENGTH_LONG).show();
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

    private float playbackSpeed() {
        float speed = prefs.getFloat(PREF_PLAYBACK_SPEED, 1f);
        return speed == 0.75f || speed == 1f || speed == 1.25f || speed == 1.5f || speed == 2f
                ? speed
                : 1f;
    }

    private String playbackSpeedLabel() {
        float speed = playbackSpeed();
        return speed == (int) speed ? (int) speed + "x" : speed + "x";
    }

    private void setPlaybackSpeed(float speed) {
        prefs.edit().putFloat(PREF_PLAYBACK_SPEED, speed).apply();
        if (player != null) {
            player.setPlaybackSpeed(speed);
        }
        if (playbackSpeedButton != null) {
            playbackSpeedButton.setText(playbackSpeedLabel());
        }
        showPlayerControlsTemporarily();
    }

    private void showPlaybackSpeedMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        float selected = playbackSpeed();
        float[] speeds = new float[]{0.75f, 1f, 1.25f, 1.5f, 2f};
        for (int index = 0; index < speeds.length; index++) {
            float speed = speeds[index];
            android.view.MenuItem item = menu.getMenu().add(1, index + 1, index, speed == 1f ? "Normal" : speed + "x");
            item.setCheckable(true);
            item.setChecked(speed == selected);
        }
        menu.getMenu().setGroupCheckable(1, true, true);
        menu.setOnMenuItemClickListener(item -> {
            int index = item.getItemId() - 1;
            if (index < 0 || index >= speeds.length) {
                return false;
            }
            setPlaybackSpeed(speeds[index]);
            return true;
        });
        menu.show();
    }

    private void showPlayerControlsTemporarily() {
        if (isInPictureInPictureMode()) {
            setPlayerControlsVisible(false);
            return;
        }
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
        if (playbackSpeedButton != null) {
            playbackSpeedButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (restartOverlayButton != null) {
            boolean canRestart = playerItem != null
                    && Math.max(resumeTimeFor(playerItem), currentPositionMs()) >= 10_000L;
            restartOverlayButton.setVisibility(visible && canRestart ? View.VISIBLE : View.GONE);
        }
        updateEpisodeContinuationControls();
    }

    private void schedulePlayerControlsHide() {
        cancelPlayerControlsHide();
        hidePlayerControlsRunnable = () -> setPlayerControlsVisible(false);
        main.postDelayed(hidePlayerControlsRunnable, PLAYER_CONTROLS_TIMEOUT_MS);
    }

    private void keepPlayerControlTouchable(Button button) {
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                cancelPlayerControlsHide();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                schedulePlayerControlsHide();
            }
            return false;
        });
    }

    private void installPlayerOverlayInsets(
            View shell,
            FrameLayout.LayoutParams overlayActionsParams
    ) {
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            int safeTop = insets.getStableInsetTop();
            int safeLeft = insets.getStableInsetLeft();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                safeTop = Math.max(safeTop, insets.getDisplayCutout().getSafeInsetTop());
                safeLeft = Math.max(safeLeft, insets.getDisplayCutout().getSafeInsetLeft());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.graphics.Insets gestures = insets.getSystemGestureInsets();
                safeTop = Math.max(safeTop, gestures.top);
                safeLeft = Math.max(safeLeft, gestures.left);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets hiddenBars = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()
                );
                android.graphics.Insets gestures = insets.getInsets(WindowInsets.Type.systemGestures());
                safeTop = Math.max(safeTop, Math.max(hiddenBars.top, gestures.top));
                safeLeft = Math.max(safeLeft, Math.max(hiddenBars.left, gestures.left));
            }
            int topMargin = Math.max(dp(38), safeTop + dp(8));
            int leftMargin = Math.max(dp(20), safeLeft + dp(8));
            overlayActionsParams.topMargin = topMargin;
            overlayActionsParams.leftMargin = leftMargin;
            view.requestLayout();
            return insets;
        });
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
        stylePrimaryButton(search);
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
                hydratedItems.remove(item.ratingKey);
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
        String ratingKey = item.ratingKey;
        Models.MediaItem cached = hydratedItems.get(ratingKey);
        if (cached != null) {
            return mergeBrowseState(cached, item);
        }
        FutureTask<Models.MediaItem> created = new FutureTask<>(() -> {
            Models.ItemResponse response = api.get(
                    "/api/metadata/" + enc(ratingKey),
                    Models.ItemResponse.class
            );
            Models.MediaItem hydrated = response != null && response.item != null ? response.item : item;
            hydratedItems.put(ratingKey, hydrated);
            return hydrated;
        });
        FutureTask<Models.MediaItem> request = hydrationRequests.putIfAbsent(ratingKey, created);
        if (request == null) {
            request = created;
            created.run();
        }
        try {
            return mergeBrowseState(request.get(), item);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Metadata request interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Could not load metadata", cause);
        } finally {
            hydrationRequests.remove(ratingKey, request);
        }
    }

    private Models.MediaItem mergeBrowseState(Models.MediaItem hydrated, Models.MediaItem browseItem) {
        hydrated.viewCount = browseItem.viewCount;
        hydrated.viewOffset = browseItem.viewOffset;
        hydrated.inMyList = browseItem.inMyList;
        hydrated.inPlayQueue = browseItem.inPlayQueue;
        return hydrated;
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
        if (restartingPlayback || playerItem == null || playerItem.ratingKey == null || player == null) {
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
        if (offlineMode || !isNetworkAvailable()) {
            return;
        }
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
                        boolean reloadFilteredView = !isPlayerOpen()
                                && libraryMode
                                && ("continue".equals(viewMode) || "unwatched".equals(viewMode));
                        if (reloadFilteredView) {
                            loadLibrary(false);
                        } else if (!isPlayerOpen()) {
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
        long local = prefs.getLong("progress:" + item.ratingKey, 0L);
        return item.resumeOffset(local);
    }

    private String formatPlaybackPosition(long timeMs) {
        long totalSeconds = Math.max(0L, timeMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
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
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
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
            item.inPlayQueue = item.ratingKey != null && playQueueKeys.contains(item.ratingKey);
        }
        adapter.submit(currentItems);
        titleView.setText(currentTitle);
        updateToolbarState();
        int shown = currentItems.size();
        boolean connectedDownloads = !offlineMode && downloadsLibrarySelected();
        if (shown == 0) {
            if (connectedDownloads) {
                setStatus("No downloads saved on this Pixel.");
            } else if ("continue".equals(viewMode) && libraryMode) {
                setStatus("Nothing to continue.");
            } else if ("collections".equals(viewMode) && libraryMode) {
                setStatus("No collections.");
            } else if ("mylist".equals(viewMode) && libraryMode) {
                setStatus("My List is empty.");
            } else if ("queue".equals(viewMode) && libraryMode) {
                setStatus("Play Queue is empty.");
            } else {
                setStatus("No items.");
            }
        } else if (connectedDownloads) {
            setStatus(shown == 1
                    ? "1 download ready on this Pixel."
                    : shown + " downloads ready on this Pixel.");
        } else {
            int nounCount = libraryMode && totalCount > shown ? totalCount : shown;
            String noun = libraryMode && "collections".equals(viewMode)
                    ? (nounCount == 1 ? " collection" : " collections")
                    : libraryMode && "mylist".equals(viewMode)
                    ? (nounCount == 1 ? " saved item" : " saved items")
                    : libraryMode && "queue".equals(viewMode)
                    ? (nounCount == 1 ? " queued item" : " queued items")
                    : (nounCount == 1 ? " item" : " items");
            setStatus(libraryMode && totalCount > shown
                    ? shown + " of " + totalCount + noun
                    : shown + noun);
        }
        loadMoreButton.setVisibility(libraryMode && totalCount > shown ? View.VISIBLE : View.GONE);
        loadMoreButton.setEnabled(!libraryLoadingMore);
        loadMoreButton.setText(libraryLoadingMore ? "Loading..." : "Load more");
        scheduleVisibleMetadataPrefetch();
    }

    private void scheduleVisibleMetadataPrefetch() {
        if (localCatalogActive()) {
            metadataPrefetchRunnable = null;
            return;
        }
        if (metadataPrefetchRunnable != null) {
            main.removeCallbacks(metadataPrefetchRunnable);
        }
        List<Models.MediaItem> candidates = new ArrayList<>();
        for (Models.MediaItem item : currentItems) {
            if (item != null
                    && item.ratingKey != null
                    && ("movie".equals(item.type) || "episode".equals(item.type))
                    && !hydratedItems.containsKey(item.ratingKey)) {
                candidates.add(item);
                if (candidates.size() >= VISIBLE_METADATA_PREFETCH_COUNT) {
                    break;
                }
            }
        }
        if (candidates.isEmpty()) {
            metadataPrefetchRunnable = null;
            return;
        }
        metadataPrefetchRunnable = () -> {
            metadataPrefetchRunnable = null;
            prefetchMetadataBatch(candidates);
        };
        main.postDelayed(metadataPrefetchRunnable, 220L);
    }

    private void updateToolbarState() {
        boolean localCatalog = localCatalogActive();
        if (backButton != null) {
            backButton.setVisibility(backStack.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (scanButton != null) {
            scanButton.setVisibility(!localCatalog && libraryMode && selectedLibrary != null ? View.VISIBLE : View.GONE);
            scanButton.setEnabled(!scanInProgress);
            scanButton.setText(scanInProgress ? "Scanning..." : "Scan");
        }
        if (surpriseButton != null) {
            boolean queueView = "queue".equals(viewMode);
            surpriseButton.setEnabled(localCatalog
                    ? !currentItems.isEmpty()
                    : selectedLibrary != null
                    && !surpriseInProgress
                    && !"mylist".equals(viewMode)
                    && (!queueView || !currentItems.isEmpty()));
            surpriseButton.setText(localCatalog
                    ? "Surprise me"
                    : queueView ? "Play queue" : surpriseInProgress ? "Choosing..." : "Surprise me");
        }
        if (modeScroll != null) {
            modeScroll.setVisibility(localCatalog ? View.GONE : View.VISIBLE);
        }
        styleModeButton(continueButton, "continue".equals(viewMode));
        styleModeButton(recentButton, "recent".equals(viewMode));
        styleModeButton(allButton, "all".equals(viewMode));
        styleModeButton(unwatchedButton, "unwatched".equals(viewMode));
        styleModeButton(collectionsButton, "collections".equals(viewMode));
        styleModeButton(myListButton, "mylist".equals(viewMode));
        if (queueButton != null) {
            queueButton.setText(playQueueKeys.isEmpty() ? "Queue" : "Queue (" + playQueueKeys.size() + ")");
        }
        styleModeButton(queueButton, "queue".equals(viewMode));
        for (Button modeButton : new Button[]{
                continueButton,
                recentButton,
                allButton,
                unwatchedButton,
                collectionsButton,
                myListButton,
                queueButton
        }) {
            if (modeButton != null) {
                modeButton.setEnabled(!localCatalog);
            }
        }
        if (sortSpinner != null) {
            sortSpinner.setVisibility(localCatalog ? View.GONE : View.VISIBLE);
            boolean sortingEnabled = !localCatalog
                    && !"continue".equals(viewMode)
                    && !"collections".equals(viewMode)
                    && !"mylist".equals(viewMode)
                    && !"queue".equals(viewMode);
            sortSpinner.setEnabled(sortingEnabled);
            sortSpinner.setAlpha(sortingEnabled ? 1f : 0.5f);
            suppressSortEvent = true;
            sortSpinner.setSelection(sortIndexFor(sortMode));
            suppressSortEvent = false;
        }
        if (genreSpinner != null) {
            genreSpinner.setVisibility(localCatalog ? View.GONE : View.VISIBLE);
            boolean genreEnabled = libraryMode
                    && !localCatalog
                    && selectedLibrary != null
                    && !genresLoading
                    && !genres.isEmpty()
                    && !"collections".equals(viewMode)
                    && !"mylist".equals(viewMode)
                    && !"queue".equals(viewMode);
            genreSpinner.setEnabled(genreEnabled);
            genreSpinner.setAlpha(genreEnabled ? 1f : 0.5f);
        }
    }

    private void styleModeButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        styleButton(
                button,
                selected ? colorAccent() : palette.surface,
                selected ? palette.onAccent : colorInk(),
                selected ? colorAccent() : palette.line
        );
    }

    private void persistBrowseContext() {
        if (offlineMode) {
            return;
        }
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
        return "collections".equals(viewMode)
                || "mylist".equals(viewMode)
                || "queue".equals(viewMode)
                || !hasGenre(genreKey) ? "" : genreKey;
    }

    private boolean downloadsLibrarySelected() {
        return DownloadsLibrary.matches(selectedLibrary);
    }

    private boolean localCatalogActive() {
        return offlineMode || downloadsLibrarySelected();
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
        state.collectionRatingKey = currentCollectionRatingKey;
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
        currentCollectionRatingKey = state.collectionRatingKey;
        renderLibraries();
        if (collectionLibraryRefreshPending && libraryMode && "collections".equals(viewMode)) {
            currentItems.clear();
            loadedCount = 0;
            totalCount = 0;
            renderCurrent();
            loadLibrary(false);
            return;
        }
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
        editText.setBackground(roundedBackground(palette.surface, palette.line, 7));
        editText.setPadding(dp(12), 0, dp(12), 0);
        editText.setSingleLine(false);
        return editText;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setPadding(dp(12), 0, dp(12), 0);
        styleButton(button, palette.surface, colorInk(), palette.line);
        return button;
    }

    private void styleButton(Button button, int fill, int textColor, int stroke) {
        button.setTextColor(textColor);
        GradientDrawable content = roundedShape(fill, stroke, 7);
        GradientDrawable mask = roundedShape(Color.WHITE, Color.TRANSPARENT, 7);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(palette.dark ? 54 : 38, 255, 255, 255)),
                content,
                mask
        ));
    }

    private void stylePrimaryButton(Button button) {
        styleButton(button, colorAccent(), palette.onAccent, colorAccent());
    }

    private Drawable roundedBackground(int fill, int stroke, int radiusDp) {
        return roundedShape(fill, stroke, radiusDp);
    }

    private GradientDrawable roundedShape(int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (Color.alpha(stroke) > 0) {
            shape.setStroke(dp(1), stroke);
        }
        return shape;
    }

    private Spinner themedSpinner(String[] labels) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = themedAdapter(labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(roundedBackground(palette.surface, palette.line, 7));
        spinner.setPopupBackgroundDrawable(roundedBackground(palette.surface, palette.line, 7));
        spinner.setPadding(dp(10), 0, dp(10), 0);
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
            textView.setTextSize(13);
            if (dropdown) {
                textView.setPadding(dp(12), dp(12), dp(12), dp(12));
            }
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
                    prefs.edit().putString(ThemePalette.PREF_KEY, next).apply();
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
        if (isInPictureInPictureMode()) {
            return;
        }
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
                || "mylist".equals(value)
                || "queue".equals(value)) {
            return value;
        }
        return "all";
    }

    private static int pageSizeForView(String view) {
        return "queue".equals(view) ? 100 : PAGE_SIZE;
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

    private interface CollectionNameAction {
        void accept(String title);
    }

    private static final class MatchDialogState {
        Dialog dialog;
        Dialog detailsDialog;
        Models.MediaItem item;
        EditText query;
        EditText year;
        Spinner language;
        List<String> languageCodes;
        Button search;
        Button close;
        TextView status;
        LinearLayout results;
        boolean busy;
        int generation;
    }

    private static final class StartupSnapshot {
        final Models.BootstrapResponse response;
        final boolean cached;

        StartupSnapshot(Models.BootstrapResponse response, boolean cached) {
            this.response = response;
            this.cached = cached;
        }
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
        String collectionRatingKey;
    }
}
