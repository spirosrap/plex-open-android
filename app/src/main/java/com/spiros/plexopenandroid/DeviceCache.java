package com.spiros.plexopenandroid;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class DeviceCache {
    private static final long MAX_BYTES = 12L * 1024L * 1024L * 1024L;
    private static final long MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L;

    private final File dir;
    private final Gson gson;

    DeviceCache(Context context, Gson gson) {
        this.dir = new File(context.getFilesDir(), "device-cache");
        this.gson = gson;
    }

    Entry status(Models.MediaItem item) {
        Entry entry = readEntry(item);
        if (entry == null) {
            return null;
        }
        File video = new File(dir, entry.videoFile);
        return video.isFile() ? entry : null;
    }

    Entry save(PlexApiClient api, Models.MediaItem item, PlexApiClient.ProgressListener listener) throws IOException {
        if (item == null || item.savedPlayback == null || !item.savedPlayback.ready || item.savedPlayback.streamUrl == null) {
            throw new IOException("Server saved copy is not ready");
        }
        if (item.savedPlayback.id == null || item.savedPlayback.id.isEmpty()) {
            throw new IOException("Missing saved playback id");
        }
        ensureDir();
        String id = item.savedPlayback.id;
        File video = new File(dir, id + ".mp4");
        File tmp = new File(dir, id + ".tmp.mp4");
        deleteQuietly(tmp);

        api.downloadToFile(item.savedPlayback.streamUrl, tmp, listener);
        if (video.exists() && !video.delete()) {
            throw new IOException("Could not replace old device copy");
        }
        if (!tmp.renameTo(video)) {
            throw new IOException("Could not finish device save");
        }

        Entry entry = new Entry();
        entry.id = id;
        entry.ratingKey = item.ratingKey;
        entry.title = item.displayTitle();
        entry.videoFile = video.getName();
        entry.bytes = video.length();
        entry.savedAt = System.currentTimeMillis();
        entry.subtitles = new ArrayList<>();

        List<Models.Subtitle> subtitles = supportedSubtitles(item);
        for (int index = 0; index < subtitles.size(); index++) {
            Models.Subtitle subtitle = subtitles.get(index);
            if (subtitle.subtitleUrl == null || subtitle.subtitleUrl.isEmpty()) {
                continue;
            }
            File subtitleFile = new File(dir, id + "-" + index + ".vtt");
            api.downloadToFile(subtitle.subtitleUrl, subtitleFile, null);
            LocalSubtitle local = new LocalSubtitle();
            local.id = subtitle.id;
            local.label = subtitle.label();
            local.srclang = subtitle.srclang == null || subtitle.srclang.isEmpty() ? "und" : subtitle.srclang;
            local.file = subtitleFile.getName();
            local.selected = subtitle.selected;
            local.defaultValue = subtitle.defaultValue;
            local.forced = subtitle.forced;
            entry.subtitles.add(local);
            entry.bytes += subtitleFile.length();
        }

        writeEntry(entry);
        prune();
        return entry;
    }

    void delete(Models.MediaItem item) {
        Entry entry = readEntry(item);
        if (entry == null) {
            return;
        }
        deleteEntry(entry);
    }

    MediaItem localMediaItem(Models.MediaItem source, Entry entry) {
        File video = new File(dir, entry.videoFile);
        List<MediaItem.SubtitleConfiguration> subtitleConfigurations = new ArrayList<>();
        int preferredSubtitle = preferredSubtitleIndex(entry.subtitles);
        for (int index = 0; index < entry.subtitles.size(); index++) {
            LocalSubtitle subtitle = entry.subtitles.get(index);
            File file = new File(dir, subtitle.file);
            if (!file.isFile()) {
                continue;
            }
            int flags = 0;
            if (subtitle.defaultValue || subtitle.selected || index == preferredSubtitle) {
                flags |= C.SELECTION_FLAG_DEFAULT;
            }
            if (subtitle.forced) {
                flags |= C.SELECTION_FLAG_FORCED;
            }
            subtitleConfigurations.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(subtitle.srclang == null ? "und" : subtitle.srclang)
                    .setLabel(subtitle.label)
                    .setSelectionFlags(flags)
                    .build());
        }
        return new MediaItem.Builder()
                .setUri(Uri.fromFile(video))
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(source.displayTitle()).build())
                .setSubtitleConfigurations(subtitleConfigurations)
                .build();
    }

    private List<Models.Subtitle> supportedSubtitles(Models.MediaItem item) {
        List<Models.Subtitle> result = new ArrayList<>();
        if (item.subtitles == null) {
            return result;
        }
        for (Models.Subtitle subtitle : item.subtitles) {
            if (subtitle.supported && subtitle.subtitleUrl != null && !subtitle.subtitleUrl.isEmpty()) {
                result.add(subtitle);
            }
        }
        return result;
    }

    private int preferredSubtitleIndex(List<LocalSubtitle> subtitles) {
        int greek = -1;
        for (int index = 0; index < subtitles.size(); index++) {
            LocalSubtitle subtitle = subtitles.get(index);
            if (subtitle.selected || subtitle.defaultValue || subtitle.forced) {
                return index;
            }
            String language = subtitle.srclang == null ? "" : subtitle.srclang;
            if (greek < 0 && ("el".equalsIgnoreCase(language) || "ell".equalsIgnoreCase(language) || "gre".equalsIgnoreCase(language))) {
                greek = index;
            }
        }
        if (greek >= 0) {
            return greek;
        }
        return subtitles.isEmpty() ? -1 : 0;
    }

    private Entry readEntry(Models.MediaItem item) {
        if (item == null || item.savedPlayback == null || item.savedPlayback.id == null) {
            return null;
        }
        return readEntry(new File(dir, item.savedPlayback.id + ".json"));
    }

    private Entry readEntry(File meta) {
        if (!meta.isFile()) {
            return null;
        }
        try (FileReader reader = new FileReader(meta)) {
            return gson.fromJson(reader, Entry.class);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void writeEntry(Entry entry) throws IOException {
        ensureDir();
        File meta = new File(dir, entry.id + ".json");
        try (FileWriter writer = new FileWriter(meta, false)) {
            gson.toJson(entry, writer);
        }
    }

    private void ensureDir() throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create device cache");
        }
    }

    private void prune() {
        File[] files = dir.listFiles((file, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Entry> entries = new ArrayList<>();
        for (File file : files) {
            Entry entry = readEntry(file);
            if (entry == null || entry.savedAt <= 0 || now - entry.savedAt > MAX_AGE_MS) {
                if (entry != null) {
                    deleteEntry(entry);
                } else {
                    deleteQuietly(file);
                }
                continue;
            }
            entries.add(entry);
        }
        long total = 0;
        for (Entry entry : entries) {
            total += Math.max(0, entry.bytes);
        }
        entries.sort(Comparator.comparingLong(entry -> entry.savedAt));
        for (Entry entry : entries) {
            if (total <= MAX_BYTES) {
                break;
            }
            total -= Math.max(0, entry.bytes);
            deleteEntry(entry);
        }
    }

    private void deleteEntry(Entry entry) {
        deleteQuietly(new File(dir, entry.videoFile));
        if (entry.subtitles != null) {
            for (LocalSubtitle subtitle : entry.subtitles) {
                deleteQuietly(new File(dir, subtitle.file));
            }
        }
        deleteQuietly(new File(dir, entry.id + ".json"));
        File[] leftovers = dir.listFiles((file, name) -> name.startsWith(entry.id + "-") || name.startsWith(entry.id + ".tmp"));
        if (leftovers != null) {
            for (File leftover : Arrays.asList(leftovers)) {
                deleteQuietly(leftover);
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static final class Entry {
        String id;
        String ratingKey;
        String title;
        String videoFile;
        long bytes;
        long savedAt;
        List<LocalSubtitle> subtitles = new ArrayList<>();
    }

    static final class LocalSubtitle {
        String id;
        String label;
        String srclang;
        String file;
        boolean selected;
        boolean defaultValue;
        boolean forced;
    }
}
