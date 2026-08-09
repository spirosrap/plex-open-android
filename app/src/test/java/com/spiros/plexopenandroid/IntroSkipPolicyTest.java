package com.spiros.plexopenandroid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class IntroSkipPolicyTest {
    private Models.MediaItem episode(long startMs, long endMs) {
        Models.MediaItem item = new Models.MediaItem();
        item.type = "episode";
        item.introMarker = new Models.IntroMarker();
        item.introMarker.type = "intro";
        item.introMarker.startTimeOffset = startMs;
        item.introMarker.endTimeOffset = endMs;
        return item;
    }

    @Test
    public void buttonAppearsOnlyInsideTheIntroWindow() {
        Models.MediaItem item = episode(60_000L, 120_000L);

        assertFalse(IntroSkipPolicy.shouldShow(item, 57_999L, false));
        assertTrue(IntroSkipPolicy.shouldShow(item, 58_000L, false));
        assertTrue(IntroSkipPolicy.shouldShow(item, 90_000L, false));
        assertFalse(IntroSkipPolicy.shouldShow(item, 119_500L, false));
        assertFalse(IntroSkipPolicy.shouldShow(item, 90_000L, true));
    }

    @Test
    public void seekLandsAfterTheMarkerWithoutPassingTheVideoEnd() {
        Models.MediaItem item = episode(60_000L, 120_000L);

        assertEquals(120_200L, IntroSkipPolicy.seekTargetMs(item, 600_000L));
        assertEquals(120_100L, IntroSkipPolicy.seekTargetMs(item, 120_350L));
    }

    @Test
    public void moviesAndInvalidMarkersNeverShowTheButton() {
        Models.MediaItem movie = episode(60_000L, 120_000L);
        movie.type = "movie";
        Models.MediaItem shortMarker = episode(60_000L, 62_000L);

        assertFalse(IntroSkipPolicy.hasMarker(movie));
        assertFalse(IntroSkipPolicy.hasMarker(shortMarker));
    }
}
