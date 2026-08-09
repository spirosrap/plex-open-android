package com.spiros.plexopenandroid;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SubtitleSelectionTest {
    @Test
    public void supportedIncludesEmbeddedAndSavedSidecars() {
        Models.MediaItem item = new Models.MediaItem();
        Models.Subtitle embedded = subtitle("170349", null, "el", "Greek");
        embedded.embedded = true;
        Models.Subtitle sidecar = subtitle(null, "movie.el.opensubtitles.srt", "el", "Greek - OpenSubtitles");
        sidecar.external = true;
        sidecar.source = "opensubtitles";
        Models.Subtitle unsupported = subtitle("99", null, "en", "PGS");
        unsupported.supported = false;
        item.subtitles = Arrays.asList(embedded, sidecar, unsupported);

        List<Models.Subtitle> supported = SubtitleSelection.supported(item);

        assertEquals(Arrays.asList(embedded, sidecar), supported);
        assertEquals("stream:170349", SubtitleSelection.identity(embedded, 0));
        assertEquals("key:movie.el.opensubtitles.srt", SubtitleSelection.identity(sidecar, 1));
        assertTrue(SubtitleSelection.detail(embedded).contains("Embedded"));
        assertTrue(SubtitleSelection.detail(sidecar).contains("Downloaded"));
    }

    @Test
    public void rememberedTrackAndOffOverrideAutomaticLanguageChoice() {
        Models.Subtitle english = subtitle("10", null, "en", "English");
        english.selected = true;
        Models.Subtitle greek = subtitle("11", null, "el", "Greek");
        List<Models.Subtitle> subtitles = Arrays.asList(english, greek);

        assertEquals(1, SubtitleSelection.preferredIndex(subtitles, "stream:11"));
        assertEquals(-1, SubtitleSelection.preferredIndex(subtitles, SubtitleSelection.OFF));
    }

    @Test
    public void greekThenEnglishAreSensibleFallbacks() {
        Models.Subtitle french = subtitle("1", null, "fr", "French");
        Models.Subtitle english = subtitle("2", null, "en", "English");
        Models.Subtitle greek = subtitle("3", null, "el", "Greek");

        assertEquals(2, SubtitleSelection.preferredIndex(Arrays.asList(french, english, greek), null));
        assertEquals(1, SubtitleSelection.preferredIndex(Arrays.asList(french, english), null));
    }

    @Test
    public void markingAChoiceClearsEveryOtherTrack() {
        Models.Subtitle english = subtitle("10", null, "en", "English");
        english.selected = true;
        Models.Subtitle greek = subtitle("11", null, "el", "Greek");
        List<Models.Subtitle> subtitles = Arrays.asList(english, greek);

        SubtitleSelection.markSelected(subtitles, "stream:11");
        assertFalse(english.selected);
        assertTrue(greek.selected);

        SubtitleSelection.markSelected(subtitles, SubtitleSelection.OFF);
        assertFalse(english.selected);
        assertFalse(greek.selected);
    }

    private Models.Subtitle subtitle(String streamId, String key, String language, String label) {
        Models.Subtitle subtitle = new Models.Subtitle();
        subtitle.streamId = streamId;
        subtitle.key = key;
        subtitle.srclang = language;
        subtitle.languageCode = language;
        subtitle.label = label;
        subtitle.codec = "srt";
        subtitle.supported = true;
        subtitle.subtitleUrl = "/subtitle/" + label;
        return subtitle;
    }
}
