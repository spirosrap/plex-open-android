package com.spiros.plexopenandroid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class PlaybackStreamTest {
    @Test
    public void audioTranscodeUsesSeekableHlsInsteadOfALiveMp4Pipe() {
        Models.MediaItem item = movie("701");
        item.playback.audioTranscodeRequired = true;
        item.playback.directStreamUrl = "/api/stream?partKey=%2Flibrary%2Fparts%2F42%2Ffile.mkv";
        item.playback.compatibleStreamUrl = "/api/stream-compatible?partKey=%2Flibrary%2Fparts%2F42%2Ffile.mkv&ratingKey=701";

        String url = PlaybackStream.urlFor(item);

        assertTrue(url.contains("/api/stream-compatible?"));
        assertTrue(PlaybackStream.isHls(url));
        assertTrue(url.contains("ratingKey=701"));
        assertFalse(url.contains("format=hls&format=hls"));
    }

    @Test
    public void preparedMp4IsPreferredOverLiveTranscode() {
        Models.MediaItem item = movie("701");
        item.playback.audioTranscodeRequired = true;
        item.playback.compatibleStreamUrl = "/api/stream-compatible?partKey=42";
        item.savedPlayback = new Models.SavedPlayback();
        item.savedPlayback.ready = true;
        item.savedPlayback.streamUrl = "/api/saved-stream?id=abc";

        assertEquals("/api/saved-stream?id=abc", PlaybackStream.urlFor(item));
        assertNull(PlaybackStream.hlsFallbackUrl(item, "/api/saved-stream?id=abc"));
    }

    @Test
    public void directPlayKeepsTheOriginalStreamWhenAudioIsCompatible() {
        Models.MediaItem item = movie("8");
        item.playback.directStreamUrl = "/api/stream?partKey=8";
        item.playback.compatibleStreamUrl = "/api/stream?partKey=8";

        assertEquals("/api/stream?partKey=8", PlaybackStream.urlFor(item));
        assertEquals(
                "/api/stream-compatible?partKey=8&format=hls&ratingKey=8",
                PlaybackStream.hlsFallbackUrl(item, "/api/stream?partKey=8")
        );
    }

    @Test
    public void mpegTransportStreamsUseHlsEvenWhenCodecsAreDirectPlayable() {
        Models.MediaItem item = movie("9");
        item.media = new Models.MediaDetails();
        item.media.container = "m2ts";
        item.playback.directStreamUrl = "/api/stream?partKey=9";
        item.playback.compatibleStreamUrl = "/api/stream?partKey=9";

        String url = PlaybackStream.urlFor(item);

        assertEquals("/api/stream-compatible?partKey=9&format=hls&ratingKey=9", url);
        assertTrue(PlaybackStream.containerIsUnseekable(item));
        assertNull(PlaybackStream.hlsFallbackUrl(item, url));
    }

    @Test
    public void hevcDirectPlayDoesNotForceACompatibilityTranscode() {
        Models.MediaItem item = movie("12");
        item.playback.videoTranscodeRequired = true;
        item.playback.compatibilityTranscodeRequired = true;
        item.playback.directStreamUrl = "/api/stream?partKey=12";
        item.playback.compatibleStreamUrl = "/api/stream-compatible?partKey=12&video=h264&ratingKey=12";

        assertEquals("/api/stream?partKey=12", PlaybackStream.urlFor(item));
    }

    @Test
    public void durationPrefersTheItemFieldThenMediaDetails() {
        Models.MediaItem item = new Models.MediaItem();
        assertNull(PlaybackStream.durationMs(item));
        item.media = new Models.MediaDetails();
        item.media.duration = 1_200_000L;
        assertEquals(Long.valueOf(1_200_000L), PlaybackStream.durationMs(item));
        item.duration = 1_234_000L;
        assertEquals(Long.valueOf(1_234_000L), PlaybackStream.durationMs(item));
    }

    @Test
    public void queryHelpersDoNotDuplicateExistingParameters() {
        String url = PlaybackStream.withQueryParam("/api/stream-compatible?partKey=1&format=hls", "format", "hls");
        assertEquals("/api/stream-compatible?partKey=1&format=hls", url);
        assertTrue(PlaybackStream.queryContains(url, "partKey", "1"));
        assertFalse(PlaybackStream.queryContains(url, "ratingKey", null));
    }

    private static Models.MediaItem movie(String ratingKey) {
        Models.MediaItem item = new Models.MediaItem();
        item.type = "movie";
        item.title = "Perfect Days";
        item.ratingKey = ratingKey;
        item.duration = 7_440_000L;
        item.playback = new Models.Playback();
        return item;
    }
}
