package com.spiros.plexopenandroid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ModelsTest {
    @Test
    public void watchedReplayKeepsItsResumeProgress() {
        Models.MediaItem item = new Models.MediaItem();
        item.duration = 600_000L;
        item.viewOffset = 120_000L;
        item.viewCount = 1;

        assertEquals(120_000L, item.resumeOffset(0L));
        assertEquals(20, item.progressPercent());
        assertTrue(item.metaLine().contains("20% watched"));
    }

    @Test
    public void newerLocalPositionWinsOverPlexPosition() {
        Models.MediaItem item = new Models.MediaItem();
        item.duration = 600_000L;
        item.viewOffset = 120_000L;

        assertEquals(180_000L, item.resumeOffset(180_000L));
        assertEquals(30, item.progressPercent(180_000L));
    }

    @Test
    public void nearCompletionDoesNotCreateAResumeAction() {
        Models.MediaItem item = new Models.MediaItem();
        item.duration = 600_000L;
        item.viewOffset = 580_000L;

        assertEquals(0L, item.resumeOffset(0L));
        assertEquals(0, item.progressPercent());
    }
}
