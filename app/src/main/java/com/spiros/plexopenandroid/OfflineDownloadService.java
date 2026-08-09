package com.spiros.plexopenandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OfflineDownloadService extends Service {
    static final String ACTION_STATUS = "com.spiros.plexopenandroid.OFFLINE_DOWNLOAD_STATUS";

    private static final String ACTION_SAVE = "com.spiros.plexopenandroid.action.SAVE_OFFLINE";
    private static final String ACTION_CANCEL = "com.spiros.plexopenandroid.action.CANCEL_OFFLINE";
    private static final String EXTRA_RATING_KEY = "rating_key";
    private static final String EXTRA_TITLE = "title";
    private static final String CHANNEL_ID = "offline-downloads";
    private static final int NOTIFICATION_ID = 4201;
    private static final long WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L;

    private final Object taskLock = new Object();
    private ExecutorService executor;
    private PlexApiClient api;
    private DeviceCache deviceCache;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private volatile String activeRatingKey;
    private volatile String activeTitle;
    private volatile Thread workerThread;
    private volatile boolean cancelRequested;
    private long lastProgressUpdateAt;
    private int lastProgressPercent = Integer.MIN_VALUE;

    static boolean enqueue(Context context, String ratingKey, String title) {
        if (ratingKey == null || ratingKey.trim().isEmpty()) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        OfflineDownloadState.Snapshot current = OfflineDownloadState.read(appContext);
        if (current.isInProgress() && !current.matches(ratingKey)) {
            return false;
        }
        if (!current.isInProgress()) {
            OfflineDownloadState.preparing(appContext, ratingKey, title);
        }
        Intent intent = saveIntent(appContext, ratingKey, title);
        try {
            appContext.startForegroundService(intent);
            return true;
        } catch (RuntimeException error) {
            OfflineDownloadState.failed(appContext, ratingKey, title, error.getMessage());
            return false;
        }
    }

    static void resumePending(Context context) {
        Context appContext = context.getApplicationContext();
        OfflineDownloadState.Snapshot pending = OfflineDownloadState.read(appContext);
        if (!pending.isInProgress() || pending.ratingKey.isEmpty()) {
            return;
        }
        try {
            appContext.startForegroundService(saveIntent(appContext, pending.ratingKey, pending.title));
        } catch (RuntimeException error) {
            OfflineDownloadState.failed(
                    appContext,
                    pending.ratingKey,
                    pending.title,
                    error.getMessage()
            );
        }
    }

    private static Intent saveIntent(Context context, String ratingKey, String title) {
        return new Intent(context, OfflineDownloadService.class)
                .setAction(ACTION_SAVE)
                .putExtra(EXTRA_RATING_KEY, ratingKey)
                .putExtra(EXTRA_TITLE, title);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        api = new PlexApiClient(this, false);
        deviceCache = new DeviceCache(this, api.gson());
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelCurrent(intent.getStringExtra(EXTRA_RATING_KEY));
            return START_NOT_STICKY;
        }

        OfflineDownloadState.Snapshot pending = OfflineDownloadState.read(this);
        String ratingKey = intent == null ? pending.ratingKey : intent.getStringExtra(EXTRA_RATING_KEY);
        String title = intent == null ? pending.title : intent.getStringExtra(EXTRA_TITLE);
        if (ratingKey == null || ratingKey.trim().isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (title == null || title.trim().isEmpty()) {
            title = pending.matches(ratingKey) ? pending.title : "Offline video";
        }

        synchronized (taskLock) {
            if (activeRatingKey != null) {
                publish(OfflineDownloadState.read(this));
                return START_REDELIVER_INTENT;
            }
            activeRatingKey = ratingKey;
            activeTitle = title;
            cancelRequested = false;
            lastProgressUpdateAt = 0L;
            lastProgressPercent = Integer.MIN_VALUE;
        }

        OfflineDownloadState.Snapshot initial = pending.isInProgress() && pending.matches(ratingKey)
                ? pending
                : OfflineDownloadState.preparing(this, ratingKey, title);
        startForeground(NOTIFICATION_ID, notification(initial));
        sendStatusBroadcast();
        acquireWakeLock();
        String requestedTitle = title;
        executor.execute(() -> runDownload(ratingKey, requestedTitle));
        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        failAndStop("Android stopped the long-running offline save. Tap Save offline to retry.");
    }

    @Override
    public void onDestroy() {
        cancelRequested = true;
        api.cancelAllCalls();
        Thread thread = workerThread;
        if (thread != null) {
            thread.interrupt();
        }
        executor.shutdownNow();
        releaseWakeLock();
        api.shutdown();
        super.onDestroy();
    }

    private void runDownload(String ratingKey, String requestedTitle) {
        workerThread = Thread.currentThread();
        OfflineDownloadState.Snapshot terminal;
        try {
            throwIfCancelled();
            deviceCache.cleanupInterruptedDownloads();
            Models.ItemResponse response = api.getNetwork(
                    "/api/metadata/" + enc(ratingKey),
                    Models.ItemResponse.class
            );
            if (response == null || response.item == null) {
                throw new IOException("Could not load title details");
            }
            Models.MediaItem item = response.item;
            String title = item.displayTitle();
            activeTitle = title;
            publish(OfflineDownloadState.preparing(this, ratingKey, title));
            throwIfCancelled();

            DeviceCache.Entry existing = deviceCache.status(item);
            if (existing != null) {
                terminal = OfflineDownloadState.complete(this, ratingKey, title, existing.bytes);
            } else {
                Models.SavedPlayback saved = waitForSavedPlayback(item);
                throwIfCancelled();
                item.savedPlayback = saved;
                DeviceCache.Entry entry = deviceCache.save(api, item, (bytes, total) -> {
                    if (!cancelRequested) {
                        publishProgress(ratingKey, title, bytes, total);
                    }
                });
                throwIfCancelled();
                deviceCache.cleanupInterruptedDownloads();
                terminal = OfflineDownloadState.complete(this, ratingKey, title, entry.bytes);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            terminal = cancelRequested
                    ? OfflineDownloadState.cancelled(this, ratingKey, activeTitle)
                    : OfflineDownloadState.failed(this, ratingKey, activeTitle, "Offline save interrupted");
        } catch (IOException | RuntimeException error) {
            terminal = cancelRequested
                    ? OfflineDownloadState.cancelled(this, ratingKey, activeTitle)
                    : OfflineDownloadState.failed(this, ratingKey, activeTitle, error.getMessage());
        } finally {
            workerThread = null;
        }
        finishTask(terminal);
    }

    private Models.SavedPlayback waitForSavedPlayback(Models.MediaItem item) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("ratingKey", item.ratingKey);
        Models.SavedPlaybackResponse started = api.post(
                "/api/saved-playback",
                payload,
                Models.SavedPlaybackResponse.class
        );
        Models.SavedPlayback saved = started == null ? null : started.savedPlayback;
        int attempts = 0;
        while (saved != null && "saving".equals(saved.state) && attempts < 720) {
            throwIfCancelled();
            attempts += 1;
            Thread.sleep(2500L);
            Models.SavedPlaybackResponse status = api.getNetwork(
                    "/api/saved-playback?ratingKey=" + enc(item.ratingKey),
                    Models.SavedPlaybackResponse.class
            );
            saved = status == null ? null : status.savedPlayback;
        }
        if (saved == null || !saved.ready) {
            throw new IOException(saved != null && saved.message != null
                    ? saved.message
                    : "Saved copy is not ready");
        }
        return saved;
    }

    private void publishProgress(String ratingKey, String title, long bytes, long total) {
        OfflineDownloadState.Snapshot candidate = new OfflineDownloadState.Snapshot(
                ratingKey,
                title,
                OfflineDownloadState.DOWNLOADING,
                bytes,
                total,
                "Saving offline",
                System.currentTimeMillis()
        );
        int percent = candidate.percent();
        long now = System.currentTimeMillis();
        if (percent == lastProgressPercent && now - lastProgressUpdateAt < 1000L) {
            return;
        }
        lastProgressPercent = percent;
        lastProgressUpdateAt = now;
        publish(OfflineDownloadState.downloading(this, ratingKey, title, bytes, total));
    }

    private void publish(OfflineDownloadState.Snapshot snapshot) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification(snapshot));
        }
        sendStatusBroadcast();
    }

    private void finishTask(OfflineDownloadState.Snapshot terminal) {
        synchronized (taskLock) {
            activeRatingKey = null;
            activeTitle = null;
        }
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_DETACH);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification(terminal));
        }
        sendStatusBroadcast();
        stopSelf();
    }

    private void cancelCurrent(String ratingKey) {
        String currentRatingKey = activeRatingKey;
        if (currentRatingKey == null || !currentRatingKey.equals(ratingKey)) {
            stopSelf();
            return;
        }
        cancelRequested = true;
        api.cancelAllCalls();
        Thread thread = workerThread;
        if (thread != null) {
            thread.interrupt();
        } else {
            OfflineDownloadState.Snapshot current = OfflineDownloadState.read(this);
            finishTask(OfflineDownloadState.cancelled(this, current.ratingKey, current.title));
        }
    }

    private void failAndStop(String message) {
        cancelRequested = false;
        api.cancelAllCalls();
        Thread thread = workerThread;
        if (thread != null) {
            thread.interrupt();
        }
        OfflineDownloadState.Snapshot current = OfflineDownloadState.read(this);
        OfflineDownloadState.Snapshot failed = OfflineDownloadState.failed(
                this,
                current.ratingKey,
                current.title,
                message
        );
        stopForeground(STOP_FOREGROUND_DETACH);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification(failed));
        }
        sendStatusBroadcast();
        stopSelf();
    }

    private void throwIfCancelled() throws InterruptedException {
        if (cancelRequested || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Offline save cancelled");
        }
    }

    private Notification notification(OfflineDownloadState.Snapshot state) {
        boolean inProgress = state.isInProgress();
        int smallIcon = OfflineDownloadState.COMPLETE.equals(state.stage)
                ? android.R.drawable.stat_sys_download_done
                : OfflineDownloadState.FAILED.equals(state.stage)
                ? android.R.drawable.stat_notify_error
                : android.R.drawable.stat_sys_download;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(smallIcon)
                .setContentTitle(state.title)
                .setContentText(state.statusText())
                .setContentIntent(openAppIntent())
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(inProgress)
                .setOngoing(inProgress)
                .setAutoCancel(!inProgress)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (inProgress) {
            int percent = state.percent();
            builder.setProgress(100, Math.max(0, percent), percent < 0);
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    cancelIntent(state.ratingKey)
            ).build());
        }
        return builder.build();
    }

    private PendingIntent openAppIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) {
            intent = new Intent(Intent.ACTION_MAIN).setPackage(getPackageName());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent cancelIntent(String ratingKey) {
        Intent intent = new Intent(this, OfflineDownloadService.class)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_RATING_KEY, ratingKey);
        return PendingIntent.getService(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void createNotificationChannel() {
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Offline downloads",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Progress for movies and episodes saved on this device");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":offline-download"
        );
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void sendStatusBroadcast() {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return value == null ? "" : value;
        }
    }
}
