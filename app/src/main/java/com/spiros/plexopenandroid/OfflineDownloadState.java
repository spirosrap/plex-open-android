package com.spiros.plexopenandroid;

import android.content.Context;
import android.content.SharedPreferences;

final class OfflineDownloadState {
    static final String PREPARING = "preparing";
    static final String DOWNLOADING = "downloading";
    static final String COMPLETE = "complete";
    static final String FAILED = "failed";
    static final String CANCELLED = "cancelled";

    private static final String KEY_RATING_KEY = "offline_download_rating_key";
    private static final String KEY_TITLE = "offline_download_title";
    private static final String KEY_STAGE = "offline_download_stage";
    private static final String KEY_BYTES = "offline_download_bytes";
    private static final String KEY_TOTAL_BYTES = "offline_download_total_bytes";
    private static final String KEY_MESSAGE = "offline_download_message";
    private static final String KEY_UPDATED_AT = "offline_download_updated_at";

    private OfflineDownloadState() {
    }

    static Snapshot read(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PlexApiClient.PREFS, Context.MODE_PRIVATE);
        return new Snapshot(
                prefs.getString(KEY_RATING_KEY, ""),
                prefs.getString(KEY_TITLE, "Offline video"),
                prefs.getString(KEY_STAGE, ""),
                prefs.getLong(KEY_BYTES, 0L),
                prefs.getLong(KEY_TOTAL_BYTES, -1L),
                prefs.getString(KEY_MESSAGE, ""),
                prefs.getLong(KEY_UPDATED_AT, 0L)
        );
    }

    static Snapshot preparing(Context context, String ratingKey, String title) {
        return write(context, ratingKey, title, PREPARING, 0L, -1L, "Preparing offline copy", true);
    }

    static Snapshot downloading(Context context, String ratingKey, String title, long bytes, long totalBytes) {
        return write(context, ratingKey, title, DOWNLOADING, bytes, totalBytes, "Saving offline", false);
    }

    static Snapshot complete(Context context, String ratingKey, String title, long bytes) {
        return write(context, ratingKey, title, COMPLETE, bytes, bytes, "Ready offline", true);
    }

    static Snapshot failed(Context context, String ratingKey, String title, String message) {
        return write(context, ratingKey, title, FAILED, 0L, -1L, cleanMessage(message, "Offline save failed"), true);
    }

    static Snapshot cancelled(Context context, String ratingKey, String title) {
        return write(context, ratingKey, title, CANCELLED, 0L, -1L, "Offline save cancelled", true);
    }

    static void clearIfMatches(Context context, String ratingKey) {
        if (!read(context).matches(ratingKey)) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PlexApiClient.PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_RATING_KEY)
                .remove(KEY_TITLE)
                .remove(KEY_STAGE)
                .remove(KEY_BYTES)
                .remove(KEY_TOTAL_BYTES)
                .remove(KEY_MESSAGE)
                .remove(KEY_UPDATED_AT)
                .apply();
    }

    private static Snapshot write(
            Context context,
            String ratingKey,
            String title,
            String stage,
            long bytes,
            long totalBytes,
            String message,
            boolean durable
    ) {
        long updatedAt = System.currentTimeMillis();
        Snapshot snapshot = new Snapshot(
                cleanMessage(ratingKey, ""),
                cleanMessage(title, "Offline video"),
                stage,
                Math.max(0L, bytes),
                totalBytes,
                cleanMessage(message, ""),
                updatedAt
        );
        SharedPreferences.Editor editor = context.getApplicationContext()
                .getSharedPreferences(PlexApiClient.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RATING_KEY, snapshot.ratingKey)
                .putString(KEY_TITLE, snapshot.title)
                .putString(KEY_STAGE, snapshot.stage)
                .putLong(KEY_BYTES, snapshot.bytes)
                .putLong(KEY_TOTAL_BYTES, snapshot.totalBytes)
                .putString(KEY_MESSAGE, snapshot.message)
                .putLong(KEY_UPDATED_AT, snapshot.updatedAt);
        if (durable) {
            editor.commit();
        } else {
            editor.apply();
        }
        return snapshot;
    }

    private static String cleanMessage(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    static final class Snapshot {
        final String ratingKey;
        final String title;
        final String stage;
        final long bytes;
        final long totalBytes;
        final String message;
        final long updatedAt;

        Snapshot(
                String ratingKey,
                String title,
                String stage,
                long bytes,
                long totalBytes,
                String message,
                long updatedAt
        ) {
            this.ratingKey = ratingKey == null ? "" : ratingKey;
            this.title = title == null || title.trim().isEmpty() ? "Offline video" : title.trim();
            this.stage = stage == null ? "" : stage;
            this.bytes = Math.max(0L, bytes);
            this.totalBytes = totalBytes;
            this.message = message == null ? "" : message;
            this.updatedAt = updatedAt;
        }

        boolean isInProgress() {
            return PREPARING.equals(stage) || DOWNLOADING.equals(stage);
        }

        boolean isTerminal() {
            return COMPLETE.equals(stage) || FAILED.equals(stage) || CANCELLED.equals(stage);
        }

        boolean matches(String candidateRatingKey) {
            return candidateRatingKey != null && !candidateRatingKey.isEmpty() && ratingKey.equals(candidateRatingKey);
        }

        int percent() {
            if (COMPLETE.equals(stage)) {
                return 100;
            }
            if (!DOWNLOADING.equals(stage) || totalBytes <= 0L || bytes <= 0L) {
                return -1;
            }
            return (int) Math.min(99L, Math.max(1L, Math.round(bytes * 100.0d / totalBytes)));
        }

        String buttonLabel() {
            if (PREPARING.equals(stage)) {
                return "Preparing...";
            }
            if (DOWNLOADING.equals(stage)) {
                int percent = percent();
                return percent > 0 ? "Saving " + percent + "%" : "Saving...";
            }
            if (COMPLETE.equals(stage)) {
                return "Offline ready";
            }
            return "Save offline";
        }

        String statusText() {
            if (PREPARING.equals(stage)) {
                return "Preparing " + title + " for offline use...";
            }
            if (DOWNLOADING.equals(stage)) {
                int percent = percent();
                return percent > 0
                        ? "Saving " + title + " offline... " + percent + "%"
                        : "Saving " + title + " offline...";
            }
            if (COMPLETE.equals(stage)) {
                return title + " is ready offline.";
            }
            if (CANCELLED.equals(stage)) {
                return "Offline save cancelled.";
            }
            if (FAILED.equals(stage)) {
                return "Could not save offline: " + cleanMessage(message, "Unknown error");
            }
            return "";
        }
    }
}
