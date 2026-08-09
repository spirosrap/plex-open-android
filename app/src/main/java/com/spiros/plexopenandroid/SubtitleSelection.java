package com.spiros.plexopenandroid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SubtitleSelection {
    static final String OFF = "off";

    private SubtitleSelection() {
    }

    static List<Models.Subtitle> supported(Models.MediaItem item) {
        List<Models.Subtitle> result = new ArrayList<>();
        if (item == null || item.subtitles == null) {
            return result;
        }
        for (Models.Subtitle subtitle : item.subtitles) {
            if (subtitle != null
                    && subtitle.supported
                    && subtitle.subtitleUrl != null
                    && !subtitle.subtitleUrl.trim().isEmpty()) {
                result.add(subtitle);
            }
        }
        return result;
    }

    static String identity(Models.Subtitle subtitle, int fallbackIndex) {
        if (subtitle == null) {
            return OFF;
        }
        if (notEmpty(subtitle.selectionKey)) {
            return subtitle.selectionKey;
        }
        if (notEmpty(subtitle.streamId)) {
            return "stream:" + subtitle.streamId;
        }
        if (notEmpty(subtitle.key)) {
            return "key:" + subtitle.key;
        }
        if (notEmpty(subtitle.id)) {
            return "id:" + subtitle.id;
        }
        if (notEmpty(subtitle.subtitleUrl)) {
            return "url:" + subtitle.subtitleUrl;
        }
        return "index:" + Math.max(0, fallbackIndex);
    }

    static int preferredIndex(List<Models.Subtitle> subtitles, String rememberedChoice) {
        if (OFF.equals(rememberedChoice)) {
            return -1;
        }
        if (notEmpty(rememberedChoice)) {
            for (int index = 0; index < subtitles.size(); index++) {
                if (rememberedChoice.equals(identity(subtitles.get(index), index))) {
                    return index;
                }
            }
        }
        for (int index = 0; index < subtitles.size(); index++) {
            if (subtitles.get(index).selected) {
                return index;
            }
        }
        for (int index = 0; index < subtitles.size(); index++) {
            Models.Subtitle subtitle = subtitles.get(index);
            if (subtitle.defaultValue || subtitle.forced) {
                return index;
            }
        }
        int greek = languageIndex(subtitles, "el", "ell", "gre");
        if (greek >= 0) {
            return greek;
        }
        int english = languageIndex(subtitles, "en", "eng");
        return english >= 0 ? english : (subtitles.isEmpty() ? -1 : 0);
    }

    static void markSelected(List<Models.Subtitle> subtitles, String choice) {
        for (int index = 0; index < subtitles.size(); index++) {
            Models.Subtitle subtitle = subtitles.get(index);
            subtitle.selected = !OFF.equals(choice) && choice.equals(identity(subtitle, index));
        }
    }

    static String detail(Models.Subtitle subtitle) {
        List<String> parts = new ArrayList<>();
        String source = subtitle.source == null ? "" : subtitle.source.toLowerCase(Locale.US);
        if (subtitle.embedded || "embedded".equals(source)) {
            parts.add("Embedded");
        } else if (source.contains("opensubtitles")) {
            parts.add("Downloaded");
        } else if ("device".equals(source)) {
            parts.add("Offline copy");
        } else if (subtitle.external || "local".equals(source) || "plex".equals(source)) {
            parts.add("Sidecar");
        } else {
            parts.add("Plex");
        }
        if (notEmpty(subtitle.codec)) {
            parts.add(subtitle.codec.toUpperCase(Locale.US));
        }
        if (subtitle.forced) {
            parts.add("Forced");
        }
        if (subtitle.hearingImpaired) {
            parts.add("SDH");
        }
        return Models.join(parts, "  ");
    }

    private static int languageIndex(List<Models.Subtitle> subtitles, String... codes) {
        for (int index = 0; index < subtitles.size(); index++) {
            Models.Subtitle subtitle = subtitles.get(index);
            String srclang = normalized(subtitle.srclang);
            String languageCode = normalized(subtitle.languageCode);
            for (String code : codes) {
                if (code.equals(srclang) || code.equals(languageCode)) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
