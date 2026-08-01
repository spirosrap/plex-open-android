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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DeviceCache {
    private final File dir;
    private final Gson gson;

    DeviceCache(Context context, Gson gson) {
        this.dir = new File(context.getFilesDir(), "device-cache");
        this.gson = gson;
    }

    Entry status(Models.MediaItem item) {
        Entry entry = readEntry(item);
        return isPlayable(entry) ? entry : null;
    }

    List<Models.MediaItem> offlineItems() {
        File[] files = dir.listFiles((file, name) -> name.endsWith(".json"));
        if (files == null) {
            return new ArrayList<>();
        }
        Map<String, Entry> newestByRatingKey = new LinkedHashMap<>();
        for (File file : files) {
            Entry entry = readEntry(file);
            if (entry == null || entry.ratingKey == null || entry.ratingKey.isEmpty() || !isPlayable(entry)) {
                continue;
            }
            Entry previous = newestByRatingKey.get(entry.ratingKey);
            if (previous == null || entry.savedAt > previous.savedAt) {
                newestByRatingKey.put(entry.ratingKey, entry);
            }
        }
        List<Entry> entries = new ArrayList<>(newestByRatingKey.values());
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.savedAt).reversed());
        List<Models.MediaItem> items = new ArrayList<>();
        for (Entry entry : entries) {
            Models.MediaItem item = entry.mediaItem == null ? new Models.MediaItem() : entry.mediaItem;
            item.ratingKey = entry.ratingKey;
            if (item.title == null || item.title.isEmpty()) {
                item.title = entry.title;
            }
            if (!"movie".equals(item.type) && !"episode".equals(item.type)) {
                item.type = "movie";
            }
            if (item.partKey == null || item.partKey.isEmpty()) {
                item.partKey = "offline:" + entry.ratingKey;
            }
            item.streamUrl = null;
            item.compatibleStreamUrl = null;
            item.downloadOriginalUrl = null;
            item.playback = null;
            item.savedPlayback = null;
            item.subtitles = new ArrayList<>();
            items.add(item);
        }
        return items;
    }

    Entry save(PlexApiClient api, Models.MediaItem item, PlexApiClient.ProgressListener listener) throws IOException {
        if (item == null || item.savedPlayback == null || !item.savedPlayback.ready || item.savedPlayback.streamUrl == null) {
            throw new IOException("Server saved copy is not ready");
        }
        if (item.savedPlayback.id == null || item.savedPlayback.id.isEmpty()) {
            throw new IOException("Missing saved playback id");
        }
        if (item.ratingKey == null || item.ratingKey.isEmpty()) {
            throw new IOException("Missing Plex item id");
        }
        ensureDir();
        Entry previous = readEntry(item);
        String id = cacheId(item.ratingKey);
        long savedAt = System.currentTimeMillis();
        String generation = Long.toString(savedAt, 36);
        File video = new File(dir, id + "-" + generation + ".mp4");
        File tmp = new File(dir, id + "-" + generation + ".tmp.mp4");
        deleteQuietly(tmp);

        try {
            api.downloadToFile(item.savedPlayback.streamUrl, tmp, listener);
        } catch (IOException error) {
            deleteQuietly(tmp);
            throw error;
        }
        if (!tmp.isFile() || tmp.length() <= 0) {
            deleteQuietly(tmp);
            throw new IOException("Downloaded offline copy is empty");
        }
        replaceFile(tmp, video);

        Entry entry = new Entry();
        entry.id = id;
        entry.sourceSavedId = item.savedPlayback.id;
        entry.ratingKey = item.ratingKey;
        entry.title = item.displayTitle();
        entry.videoFile = video.getName();
        entry.videoBytes = video.length();
        entry.bytes = entry.videoBytes;
        entry.savedAt = savedAt;
        entry.mediaItem = item;
        entry.subtitles = new ArrayList<>();

        List<Models.Subtitle> subtitles = supportedSubtitles(item);
        for (int index = 0; index < subtitles.size(); index++) {
            Models.Subtitle subtitle = subtitles.get(index);
            if (subtitle.subtitleUrl == null || subtitle.subtitleUrl.isEmpty()) {
                continue;
            }
            File subtitleFile = new File(dir, id + "-" + generation + "-" + index + ".vtt");
            File subtitleTmp = new File(dir, id + "-" + generation + "-" + index + ".tmp.vtt");
            deleteQuietly(subtitleTmp);
            try {
                api.downloadToFile(subtitle.subtitleUrl, subtitleTmp, null);
            } catch (IOException ignored) {
                deleteQuietly(subtitleTmp);
                continue;
            }
            if (!subtitleTmp.isFile() || subtitleTmp.length() <= 0) {
                deleteQuietly(subtitleTmp);
                continue;
            }
            replaceFile(subtitleTmp, subtitleFile);
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

        try {
            writeEntry(entry);
        } catch (IOException error) {
            deleteQuietly(video);
            for (LocalSubtitle subtitle : entry.subtitles) {
                deleteQuietly(new File(dir, subtitle.file));
            }
            throw error;
        }
        deleteSupersededFiles(previous, entry);
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
        if (item == null) {
            return null;
        }
        if (item.ratingKey != null && !item.ratingKey.isEmpty()) {
            Entry stable = readEntry(new File(dir, cacheId(item.ratingKey) + ".json"));
            if (stable != null && item.ratingKey.equals(stable.ratingKey)) {
                return stable;
            }
            Entry migrated = newestEntryForRatingKey(item.ratingKey);
            if (migrated != null) {
                return migrated;
            }
        }
        if (item.savedPlayback == null || item.savedPlayback.id == null) {
            return null;
        }
        return readEntry(new File(dir, item.savedPlayback.id + ".json"));
    }

    private Entry newestEntryForRatingKey(String ratingKey) {
        File[] files = dir.listFiles((file, name) -> name.endsWith(".json"));
        if (files == null) {
            return null;
        }
        Entry newest = null;
        for (File file : files) {
            Entry candidate = readEntry(file);
            if (candidate == null || !ratingKey.equals(candidate.ratingKey)) {
                continue;
            }
            if (newest == null || candidate.savedAt > newest.savedAt) {
                newest = candidate;
            }
        }
        return newest;
    }

    private Entry readEntry(File meta) {
        if (!meta.isFile()) {
            return null;
        }
        try (FileReader reader = new FileReader(meta)) {
            Entry entry = gson.fromJson(reader, Entry.class);
            if (entry != null) {
                entry.metaFile = meta.getName();
                if (entry.subtitles == null) {
                    entry.subtitles = new ArrayList<>();
                }
            }
            return entry;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void writeEntry(Entry entry) throws IOException {
        ensureDir();
        File meta = new File(dir, entry.id + ".json");
        File tmp = new File(dir, entry.id + ".tmp.json");
        deleteQuietly(tmp);
        try (FileWriter writer = new FileWriter(tmp, false)) {
            gson.toJson(entry, writer);
        }
        replaceFile(tmp, meta);
        entry.metaFile = meta.getName();
    }

    private void ensureDir() throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create device cache");
        }
    }

    private static String cacheId(String ratingKey) {
        String safe = ratingKey.replaceAll("[^A-Za-z0-9._-]", "_");
        return "rating-" + safe;
    }

    private boolean isPlayable(Entry entry) {
        if (entry == null || entry.videoFile == null || entry.videoFile.isEmpty()) {
            return false;
        }
        File video = new File(dir, entry.videoFile);
        return video.isFile()
                && video.length() > 0
                && (entry.videoBytes <= 0 || video.length() == entry.videoBytes);
    }

    private static void replaceFile(File source, File target) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteSupersededFiles(Entry previous, Entry replacement) {
        if (previous == null) {
            return;
        }
        Set<String> keep = new HashSet<>();
        keep.add(replacement.videoFile);
        keep.add(replacement.metaFile);
        for (LocalSubtitle subtitle : replacement.subtitles) {
            keep.add(subtitle.file);
        }
        deleteUnlessKept(previous.videoFile, keep);
        if (previous.subtitles != null) {
            for (LocalSubtitle subtitle : previous.subtitles) {
                deleteUnlessKept(subtitle.file, keep);
            }
        }
        deleteUnlessKept(previous.metaFile, keep);
    }

    private void deleteUnlessKept(String name, Set<String> keep) {
        if (name != null && !name.isEmpty() && !keep.contains(name)) {
            deleteQuietly(new File(dir, name));
        }
    }

    private void deleteEntry(Entry entry) {
        deleteQuietly(new File(dir, entry.videoFile));
        if (entry.subtitles != null) {
            for (LocalSubtitle subtitle : entry.subtitles) {
                deleteQuietly(new File(dir, subtitle.file));
            }
        }
        deleteQuietly(new File(dir, entry.metaFile == null ? entry.id + ".json" : entry.metaFile));
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
        String sourceSavedId;
        String ratingKey;
        String title;
        String videoFile;
        long videoBytes;
        long bytes;
        long savedAt;
        Models.MediaItem mediaItem;
        List<LocalSubtitle> subtitles = new ArrayList<>();
        transient String metaFile;
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
