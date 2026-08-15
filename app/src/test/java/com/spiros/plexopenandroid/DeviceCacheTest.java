package com.spiros.plexopenandroid;

import com.google.gson.Gson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

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

    private static void writeEntry(
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
    }
}
