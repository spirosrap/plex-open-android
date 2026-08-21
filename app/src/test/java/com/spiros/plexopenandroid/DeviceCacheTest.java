package com.spiros.plexopenandroid;

import com.google.gson.Gson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class DeviceCacheTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void deleteRemovesEveryMatchingGenerationAndAsset() throws Exception {
        File directory = temporaryFolder.newFolder("device-cache");
        Gson gson = new Gson();
        DeviceCache cache = new DeviceCache(directory, gson);
        Models.MediaItem item = new Models.MediaItem();
        item.ratingKey = "42";

        writeEntry(directory, gson, "rating-42", "rating-42.json", "42", "new");
        writeEntry(directory, gson, "legacy-copy", "legacy-copy.json", "42", "old");
        Files.write(new File(directory, "rating-42-stale.tmp.mp4").toPath(), new byte[]{9});

        assertNotNull(cache.status(item));
        assertTrue(cache.delete(item));
        assertNull(cache.status(item));
        assertEquals(0, directory.listFiles() == null ? 0 : directory.listFiles().length);
    }

    @Test
    public void storageSummaryCountsEachPlayableTitleOnce() throws Exception {
        File directory = temporaryFolder.newFolder("storage-summary");
        Gson gson = new Gson();
        DeviceCache cache = new DeviceCache(directory, gson);

        writeEntry(directory, gson, "rating-42", "rating-42.json", "42", "new");
        writeEntry(directory, gson, "legacy-copy", "legacy-copy.json", "42", "old");
        writeEntry(directory, gson, "rating-43", "rating-43.json", "43", "only");

        DeviceCache.StorageSummary summary = cache.storageSummary();

        assertEquals(2, summary.itemCount);
        assertTrue(summary.bytes > 12L);
        assertTrue(summary.availableBytes >= 0L);
    }

    @Test
    public void subtitleSyncAddsADeviceTrackWithoutReplacingTheVideo() throws Exception {
        File directory = temporaryFolder.newFolder("subtitle-sync");
        Gson gson = new Gson();
        DeviceCache cache = new DeviceCache(directory, gson);
        DeviceCache.Entry original = writeEntry(
                directory,
                gson,
                "rating-42",
                "rating-42.json",
                "42",
                "original"
        );
        byte[] originalVideo = Files.readAllBytes(new File(directory, original.videoFile).toPath());

        Models.MediaItem item = new Models.MediaItem();
        item.ratingKey = "42";
        item.title = "Downloaded movie";
        item.subtitles.add(remoteSubtitle("greek-new", "501", "701"));

        int downloaded = cache.syncSubtitles(
                item,
                (source, target) -> Files.write(target.toPath(), "WEBVTT\n\n".getBytes())
        );

        DeviceCache.Entry updated = cache.status(item);
        assertNotNull(updated);
        assertEquals(1, downloaded);
        assertEquals(original.videoFile, updated.videoFile);
        assertArrayEquals(originalVideo, Files.readAllBytes(new File(directory, updated.videoFile).toPath()));
        assertEquals(2, updated.subtitles.size());
        DeviceCache.LocalSubtitle added = updated.subtitles.get(0);
        assertEquals("stream:701", added.choiceId);
        assertEquals("501", added.partId);
        assertEquals("701", added.streamId);
        assertTrue(new File(directory, added.file).isFile());
    }

    @Test
    public void subtitleSyncReusesAnExistingTrackAndRefreshesItsServerIds() throws Exception {
        File directory = temporaryFolder.newFolder("subtitle-resync");
        Gson gson = new Gson();
        DeviceCache cache = new DeviceCache(directory, gson);
        DeviceCache.Entry entry = writeEntry(
                directory,
                gson,
                "rating-42",
                "rating-42.json",
                "42",
                "original"
        );
        DeviceCache.LocalSubtitle existing = entry.subtitles.get(0);
        existing.choiceId = "key:greek-existing";
        existing.key = "greek-existing";
        String existingFile = existing.file;
        try (FileWriter writer = new FileWriter(new File(directory, "rating-42.json"))) {
            gson.toJson(entry, writer);
        }

        Models.MediaItem item = new Models.MediaItem();
        item.ratingKey = "42";
        item.title = "Downloaded movie";
        item.subtitles.add(remoteSubtitle("greek-existing", "502", "702"));

        int downloaded = cache.syncSubtitles(item, (source, target) -> {
            throw new IOException("Existing tracks must not be downloaded again");
        });

        DeviceCache.Entry updated = cache.status(item);
        assertNotNull(updated);
        assertEquals(0, downloaded);
        assertEquals(1, updated.subtitles.size());
        assertEquals(existingFile, updated.subtitles.get(0).file);
        assertEquals("502", updated.subtitles.get(0).partId);
        assertEquals("702", updated.subtitles.get(0).streamId);
    }

    private static DeviceCache.Entry writeEntry(
            File directory,
            Gson gson,
            String id,
            String metadataName,
            String ratingKey,
            String generation
    ) throws Exception {
        DeviceCache.Entry entry = new DeviceCache.Entry();
        entry.id = id;
        entry.ratingKey = ratingKey;
        entry.title = "Downloaded movie";
        entry.videoFile = id + "-" + generation + ".mp4";
        entry.posterFile = id + "-" + generation + ".poster";
        entry.videoBytes = 3L;
        entry.posterBytes = 2L;

        DeviceCache.LocalSubtitle subtitle = new DeviceCache.LocalSubtitle();
        subtitle.file = id + "-" + generation + ".vtt";
        entry.subtitles.add(subtitle);

        Files.write(new File(directory, entry.videoFile).toPath(), new byte[]{1, 2, 3});
        Files.write(new File(directory, entry.posterFile).toPath(), new byte[]{4, 5});
        Files.write(new File(directory, subtitle.file).toPath(), new byte[]{6});
        try (FileWriter writer = new FileWriter(new File(directory, metadataName))) {
            gson.toJson(entry, writer);
        }
        return entry;
    }

    private static Models.Subtitle remoteSubtitle(String key, String partId, String streamId) {
        Models.Subtitle subtitle = new Models.Subtitle();
        subtitle.key = key;
        subtitle.partId = partId;
        subtitle.streamId = streamId;
        subtitle.label = "Greek";
        subtitle.srclang = "el";
        subtitle.supported = true;
        subtitle.subtitleUrl = "/api/local-subtitle?key=" + key;
        return subtitle;
    }
}
