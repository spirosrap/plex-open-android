package com.spiros.plexopenandroid;

import android.content.Context;
import android.graphics.BitmapFactory;
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
    private static final long MAX_POSTER_BYTES = 12L * 1024L * 1024L;
    private final File dir;
    private final Gson gson;

    DeviceCache(Context context, Gson gson) {
        this(new File(context.getFilesDir(), "device-cache"), gson);
    }

    DeviceCache(File dir, Gson gson) {
        this.dir = dir;
        this.gson = gson;
    }

    Entry status(Models.MediaItem item) {
        Entry entry = readEntry(item);
        return isPlayable(entry) ? entry : null;
    }

    long bytes(Models.MediaItem item) {
        Entry entry = status(item);
        return entry == null ? 0L : entryBytes(entry);
    }

    List<Models.MediaItem> offlineItems() {
        List<Entry> entries = newestPlayableEntries();
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
            item.offlineBytes = entryBytes(entry);
            File poster = localPoster(entry);
            item.posterUrl = poster == null ? null : Uri.fromFile(poster).toString();
            item.artUrl = null;
            items.add(item);
        }
        return items;
    }

    StorageSummary storageSummary() {
        List<Entry> entries = newestPlayableEntries();
        long bytes = 0L;
        for (Entry entry : entries) {
            bytes += entryBytes(entry);
        }
        File volume = dir.exists() ? dir : dir.getParentFile();
        long availableBytes = volume == null ? 0L : Math.max(0L, volume.getUsableSpace());
        return new StorageSummary(entries.size(), bytes, availableBytes);
    }

    private List<Entry> newestPlayableEntries() {
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
        return entries;
    }

    List<String> metadataKeysNeedingRefresh() {
        List<String> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File[] files = dir.listFiles((file, name) -> name.endsWith(".json"));
        if (files == null) {
            return keys;
        }
        for (File file : files) {
            Entry entry = readEntry(file);
            if (entry == null || entry.ratingKey == null || !isPlayable(entry)) {
                continue;
            }
            boolean missingSummary = entry.mediaItem == null
                    || entry.mediaItem.summary == null
                    || entry.mediaItem.summary.trim().isEmpty();
            if ((missingSummary || localPoster(entry) == null) && seen.add(entry.ratingKey)) {
                keys.add(entry.ratingKey);
            }
        }
        return keys;
    }

    void cleanupInterruptedDownloads() {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Set<String> referenced = new HashSet<>();
        for (File file : files) {
            if (!file.getName().endsWith(".json") || file.getName().contains(".tmp.")) {
                continue;
            }
            Entry entry = readEntry(file);
            if (entry == null) {
                continue;
            }
            referenced.add(file.getName());
            addReferenced(referenced, entry.videoFile);
            addReferenced(referenced, entry.posterFile);
            if (entry.subtitles != null) {
                for (LocalSubtitle subtitle : entry.subtitles) {
                    if (subtitle != null) {
                        addReferenced(referenced, subtitle.file);
                    }
                }
            }
        }

        long staleBefore = System.currentTimeMillis() - 10L * 60L * 1000L;
        for (File file : files) {
            String name = file.getName();
            boolean temporary = name.contains(".tmp.") || name.endsWith(".tmp");
            boolean orphanedCacheAsset = name.startsWith("rating-")
                    && !name.endsWith(".json")
                    && !referenced.contains(name);
            if ((temporary || orphanedCacheAsset) && file.lastModified() < staleBefore) {
                deleteQuietly(file);
            }
        }
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

        File poster = downloadPoster(api, item.posterUrl, id, generation);
        if (poster != null) {
            entry.posterFile = poster.getName();
            entry.posterBytes = poster.length();
            entry.bytes += entry.posterBytes;
        }

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
            local.choiceId = SubtitleSelection.identity(subtitle, index);
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
            deleteQuietly(poster);
            for (LocalSubtitle subtitle : entry.subtitles) {
                deleteQuietly(new File(dir, subtitle.file));
            }
            throw error;
        }
        deleteSupersededFiles(previous, entry);
        return entry;
    }

    void updateMetadata(PlexApiClient api, Models.MediaItem item) throws IOException {
        if (item == null || item.ratingKey == null || item.ratingKey.isEmpty()) {
            return;
        }
        Entry entry = readEntry(item);
        if (!isPlayable(entry)) {
            return;
        }
        ensureDir();
        String generation = Long.toString(System.currentTimeMillis(), 36);
        File poster = downloadPoster(api, item.posterUrl, entry.id, generation);
        String previousPoster = entry.posterFile;
        if (poster != null) {
            entry.posterFile = poster.getName();
            entry.posterBytes = poster.length();
        }
        entry.title = item.displayTitle();
        entry.mediaItem = item;
        entry.bytes = entryBytes(entry);
        try {
            writeEntry(entry);
        } catch (IOException error) {
            deleteQuietly(poster);
            throw error;
        }
        if (poster != null && previousPoster != null && !previousPoster.equals(entry.posterFile)) {
            deleteQuietly(new File(dir, previousPoster));
        }
    }

    void updateIntroMetadata(Models.MediaItem item) throws IOException {
        if (item == null || item.ratingKey == null || item.ratingKey.isEmpty()) {
            return;
        }
        Entry entry = readEntry(item);
        if (!isPlayable(entry)) {
            return;
        }
        if (entry.mediaItem == null) {
            entry.mediaItem = item;
        } else {
            entry.mediaItem.introMarker = item.introMarker;
            entry.mediaItem.introAnalysis = item.introAnalysis;
        }
        writeEntry(entry);
    }

    boolean delete(Models.MediaItem item) {
        if (item == null) {
            return true;
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> seenMetadata = new HashSet<>();
        if (item.ratingKey != null && !item.ratingKey.isEmpty()) {
            File[] metadataFiles = dir.listFiles((file, name) -> name.endsWith(".json"));
            if (metadataFiles != null) {
                for (File metadataFile : metadataFiles) {
                    Entry candidate = readEntry(metadataFile);
                    if (candidate != null && item.ratingKey.equals(candidate.ratingKey)) {
                        entries.add(candidate);
                        seenMetadata.add(metadataFile.getName());
                    }
                }
            }
        }
        Entry direct = readEntry(item);
        if (direct != null && seenMetadata.add(Models.nonEmpty(direct.metaFile, direct.id + ".json"))) {
            entries.add(direct);
        }

        boolean complete = true;
        for (Entry entry : entries) {
            complete &= deleteEntry(entry);
        }
        if (item.ratingKey != null && !item.ratingKey.isEmpty()) {
            complete &= deleteCacheArtifacts(cacheId(item.ratingKey));
        }
        return complete && status(item) == null;
    }

    List<Models.Subtitle> subtitles(Models.MediaItem item) {
        Entry entry = status(item);
        return entry == null ? new ArrayList<>() : subtitleModels(entry);
    }

    MediaItem localMediaItem(Models.MediaItem source, Entry entry, String rememberedChoice) {
        File video = new File(dir, entry.videoFile);
        List<MediaItem.SubtitleConfiguration> subtitleConfigurations = new ArrayList<>();
        List<Models.Subtitle> subtitles = subtitleModels(entry);
        int preferredSubtitle = SubtitleSelection.preferredIndex(subtitles, rememberedChoice);
        for (int index = 0; index < subtitles.size(); index++) {
            Models.Subtitle subtitle = subtitles.get(index);
            int flags = 0;
            if (index == preferredSubtitle) {
                flags |= C.SELECTION_FLAG_DEFAULT;
            }
            if (subtitle.forced) {
                flags |= C.SELECTION_FLAG_FORCED;
            }
            subtitleConfigurations.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.subtitleUrl))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(subtitle.srclang == null ? "und" : subtitle.srclang)
                    .setLabel(subtitle.label())
                    .setId(SubtitleSelection.identity(subtitle, index))
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

    private List<Models.Subtitle> subtitleModels(Entry entry) {
        List<Models.Subtitle> result = new ArrayList<>();
        if (entry == null || entry.subtitles == null) {
            return result;
        }
        for (int index = 0; index < entry.subtitles.size(); index++) {
            LocalSubtitle local = entry.subtitles.get(index);
            File file = local == null || local.file == null ? null : new File(dir, local.file);
            if (file == null || !file.isFile()) {
                continue;
            }
            Models.Subtitle subtitle = new Models.Subtitle();
            subtitle.id = local.id;
            subtitle.selectionKey = localChoiceId(local, index);
            subtitle.label = Models.nonEmpty(local.label, "Subtitle");
            subtitle.srclang = Models.nonEmpty(local.srclang, "und");
            subtitle.languageCode = subtitle.srclang;
            subtitle.codec = "vtt";
            subtitle.selected = local.selected;
            subtitle.defaultValue = local.defaultValue;
            subtitle.forced = local.forced;
            subtitle.external = true;
            subtitle.supported = true;
            subtitle.source = "device";
            subtitle.subtitleUrl = Uri.fromFile(file).toString();
            result.add(subtitle);
        }
        return result;
    }

    private String localChoiceId(LocalSubtitle subtitle, int index) {
        if (subtitle.choiceId != null && !subtitle.choiceId.isEmpty()) {
            return subtitle.choiceId;
        }
        if (subtitle.id != null && subtitle.id.matches("\\d+")) {
            return "stream:" + subtitle.id;
        }
        if (subtitle.id != null && !subtitle.id.isEmpty()) {
            return "id:" + subtitle.id;
        }
        return "device:" + index;
    }

    private File downloadPoster(PlexApiClient api, String posterUrl, String id, String generation) {
        if (posterUrl == null || posterUrl.trim().isEmpty()) {
            return null;
        }
        File poster = new File(dir, id + "-" + generation + ".poster");
        File tmp = new File(dir, id + "-" + generation + ".tmp.poster");
        deleteQuietly(tmp);
        try {
            api.downloadToFile(posterUrl, tmp, null);
            if (!isPoster(tmp, tmp.length())) {
                deleteQuietly(tmp);
                return null;
            }
            replaceFile(tmp, poster);
            return poster;
        } catch (IOException ignored) {
            deleteQuietly(tmp);
            deleteQuietly(poster);
            return null;
        }
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

    private static void addReferenced(Set<String> referenced, String name) {
        if (name != null && !name.isEmpty()) {
            referenced.add(name);
        }
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

    private File localPoster(Entry entry) {
        if (entry == null || entry.posterFile == null || entry.posterFile.isEmpty()) {
            return null;
        }
        File poster = new File(dir, entry.posterFile);
        return isPoster(poster, entry.posterBytes) ? poster : null;
    }

    private boolean isPoster(File poster, long expectedBytes) {
        if (!poster.isFile()
                || poster.length() <= 0
                || poster.length() > MAX_POSTER_BYTES
                || (expectedBytes > 0 && poster.length() != expectedBytes)) {
            return false;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(poster.getAbsolutePath(), bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0;
    }

    private long entryBytes(Entry entry) {
        long total = fileBytes(entry.videoFile);
        if (entry.subtitles != null) {
            for (LocalSubtitle subtitle : entry.subtitles) {
                total += subtitle == null ? 0L : fileBytes(subtitle.file);
            }
        }
        total += fileBytes(entry.posterFile);
        total += fileBytes(entry.metaFile == null && entry.id != null ? entry.id + ".json" : entry.metaFile);
        return total;
    }

    private long fileBytes(String name) {
        if (name == null || name.isEmpty()) {
            return 0L;
        }
        File file = new File(dir, name);
        return file.isFile() ? Math.max(0L, file.length()) : 0L;
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
        if (replacement.posterFile != null) {
            keep.add(replacement.posterFile);
        }
        for (LocalSubtitle subtitle : replacement.subtitles) {
            keep.add(subtitle.file);
        }
        deleteUnlessKept(previous.videoFile, keep);
        if (previous.subtitles != null) {
            for (LocalSubtitle subtitle : previous.subtitles) {
                deleteUnlessKept(subtitle.file, keep);
            }
        }
        deleteUnlessKept(previous.posterFile, keep);
        deleteUnlessKept(previous.metaFile, keep);
    }

    private void deleteUnlessKept(String name, Set<String> keep) {
        if (name != null && !name.isEmpty() && !keep.contains(name)) {
            deleteQuietly(new File(dir, name));
        }
    }

    private boolean deleteEntry(Entry entry) {
        boolean complete = deleteNamed(entry.videoFile);
        complete &= deleteNamed(entry.posterFile);
        if (entry.subtitles != null) {
            for (LocalSubtitle subtitle : entry.subtitles) {
                if (subtitle != null) {
                    complete &= deleteNamed(subtitle.file);
                }
            }
        }
        complete &= deleteNamed(entry.metaFile == null && entry.id != null ? entry.id + ".json" : entry.metaFile);
        complete &= deleteCacheArtifacts(entry.id);
        return complete;
    }

    private boolean deleteCacheArtifacts(String id) {
        if (id == null || id.isEmpty()) {
            return true;
        }
        File[] leftovers = dir.listFiles((file, name) -> name.equals(id + ".json")
                || name.startsWith(id + "-")
                || name.startsWith(id + ".tmp"));
        boolean complete = true;
        if (leftovers != null) {
            for (File leftover : Arrays.asList(leftovers)) {
                complete &= deleteFile(leftover);
            }
        }
        return complete;
    }

    private boolean deleteNamed(String name) {
        return name == null || name.isEmpty() || deleteFile(new File(dir, name));
    }

    private static boolean deleteFile(File file) {
        return file == null || !file.exists() || file.delete() || !file.exists();
    }

    private static void deleteQuietly(File file) {
        deleteFile(file);
    }

    static final class StorageSummary {
        final int itemCount;
        final long bytes;
        final long availableBytes;

        StorageSummary(int itemCount, long bytes, long availableBytes) {
            this.itemCount = Math.max(0, itemCount);
            this.bytes = Math.max(0L, bytes);
            this.availableBytes = Math.max(0L, availableBytes);
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
        String posterFile;
        long posterBytes;
        Models.MediaItem mediaItem;
        List<LocalSubtitle> subtitles = new ArrayList<>();
        transient String metaFile;
    }

    static final class LocalSubtitle {
        String id;
        String choiceId;
        String label;
        String srclang;
        String file;
        boolean selected;
        boolean defaultValue;
        boolean forced;
    }
}
