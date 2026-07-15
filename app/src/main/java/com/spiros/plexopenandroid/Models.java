package com.spiros.plexopenandroid;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Models {
    private Models() {
    }

    static final class MeResponse {
        boolean authenticated;
        boolean authRequired;
        String serverName;
    }

    static final class LoginResponse {
        boolean authenticated;
        String error;
        String message;
    }

    static final class ServerInfo {
        String friendlyName;
        String machineIdentifier;
        String platform;
        String version;
        String updatedAt;
    }

    static final class LibrariesResponse {
        List<Library> libraries = new ArrayList<>();
    }

    static final class Library {
        String key;
        String title;
        String type;

        String label() {
            return nonEmpty(title, "Library");
        }
    }

    static final class LibraryResponse {
        String library;
        String view;
        int start;
        int limit;
        Integer size;
        Integer totalSize;
        List<MediaItem> items = new ArrayList<>();
    }

    static final class GenresResponse {
        String library;
        List<Genre> genres = new ArrayList<>();
    }

    static final class Genre {
        String key;
        String title;
    }

    static final class LibraryScanResponse {
        boolean ok;
        boolean scanStarted;
        String sectionKey;
        String libraryTitle;
        Integer plexStatus;
    }

    static final class ItemResponse {
        MediaItem item;
    }

    static final class ChildrenResponse {
        String parentTitle;
        String parentRatingKey;
        List<MediaItem> items = new ArrayList<>();
    }

    static final class SearchResponse {
        String query;
        List<MediaItem> items = new ArrayList<>();
    }

    static final class SavedPlaybackResponse {
        SavedPlayback savedPlayback;
        MediaItem item;
    }

    static final class PlaybackProgressResponse {
        boolean ok;
        boolean watched;
        boolean progressSaved;
        long timeMs;
        long durationMs;
        String state;
    }

    static final class WatchStateResponse {
        boolean ok;
        boolean watched;
        MediaItem item;
    }

    static final class SubtitleSearchResponse {
        boolean configured;
        String message;
        String error;
        List<SubtitleResult> results = new ArrayList<>();
    }

    static final class SubtitleDownloadResponse {
        boolean ok;
        boolean configured;
        String savedName;
        String sourceName;
        Integer remainingDownloads;
        Subtitle subtitle;
        String message;
        String error;
    }

    static final class MediaItem {
        String ratingKey;
        String key;
        String type;
        String title;
        String sortTitle;
        Integer year;
        String summary;
        String tagline;
        String contentRating;
        Float rating;
        Float audienceRating;
        Long duration;
        String durationText;
        Long viewOffset;
        Long addedAt;
        String addedDate;
        Long updatedAt;
        Integer viewCount;
        Long lastViewedAt;
        String lastViewedDate;
        String originallyAvailableAt;
        String librarySectionID;
        String librarySectionTitle;
        String parentRatingKey;
        String grandparentRatingKey;
        String parentTitle;
        String grandparentTitle;
        Integer index;
        Integer parentIndex;
        Integer leafCount;
        Integer viewedLeafCount;
        Integer childCount;
        String thumb;
        String art;
        String posterUrl;
        String artUrl;
        String partKey;
        String streamUrl;
        String compatibleStreamUrl;
        String downloadOriginalUrl;
        Playback playback;
        SavedPlayback savedPlayback;
        List<Subtitle> subtitles = new ArrayList<>();
        MediaDetails media;
        List<Guid> guids = new ArrayList<>();
        String imdb;
        String tmdb;
        String tvdb;

        boolean canOpen() {
            return "show".equals(type) || "season".equals(type) || "collection".equals(type);
        }

        boolean canPlay() {
            return ("movie".equals(type) || "episode".equals(type)) && partKey != null;
        }

        String displayTitle() {
            if ("episode".equals(type)) {
                String code = episodeCode();
                if (grandparentTitle != null && !grandparentTitle.isEmpty()) {
                    return code.isEmpty() ? grandparentTitle + " - " + nonEmpty(title, "Episode") : grandparentTitle + " " + code + " - " + nonEmpty(title, "Episode");
                }
                return code.isEmpty() ? nonEmpty(title, "Episode") : code + " - " + nonEmpty(title, "Episode");
            }
            return nonEmpty(title, "Untitled");
        }

        String cardTitle() {
            if ("episode".equals(type)) {
                String code = episodeCode();
                return code.isEmpty() ? nonEmpty(title, "Episode") : code + " " + nonEmpty(title, "Episode");
            }
            return displayTitle();
        }

        String episodeCode() {
            if (parentIndex == null && index == null) {
                return "";
            }
            String season = parentIndex == null ? "" : String.format(Locale.US, "S%02d", parentIndex);
            String episode = index == null ? "" : String.format(Locale.US, "E%02d", index);
            return season + episode;
        }

        String metaLine() {
            List<String> parts = new ArrayList<>();
            if ("collection".equals(type)) {
                int count = childCount == null ? (leafCount == null ? 0 : leafCount) : childCount;
                return count + (count == 1 ? " item" : " items");
            }
            if (year != null) {
                parts.add(String.valueOf(year));
            }
            if (durationText != null && !durationText.isEmpty()) {
                parts.add(durationText);
            }
            if (media != null && media.videoResolution != null && !media.videoResolution.isEmpty()) {
                parts.add(media.videoResolution);
            }
            if (viewCount != null && viewCount > 0) {
                parts.add("Watched");
            } else if (progressPercent() > 0) {
                parts.add(progressPercent() + "% watched");
            }
            return join(parts, "  ");
        }

        int progressPercent() {
            if ((viewCount != null && viewCount > 0) || duration == null || duration <= 0 || viewOffset == null || viewOffset < 10_000L) {
                return 0;
            }
            return Math.min(99, Math.max(1, Math.round((viewOffset * 100f) / duration)));
        }

        String subtitleQueryTitle() {
            if (media != null && media.file != null && !media.file.isEmpty()) {
                int dot = media.file.lastIndexOf('.');
                return dot > 0 ? media.file.substring(0, dot) : media.file;
            }
            if ("episode".equals(type) && grandparentTitle != null) {
                String code = episodeCode();
                return code.isEmpty() ? grandparentTitle : grandparentTitle + " " + code;
            }
            return nonEmpty(title, "");
        }
    }

    static final class Playback {
        String audioCodec;
        String videoCodec;
        String directStreamUrl;
        String compatibleStreamUrl;
        boolean audioTranscodeRequired;
        String audioTranscodeReason;
    }

    static final class SavedPlayback {
        String id;
        String state;
        boolean ready;
        String streamUrl;
        Long bytes;
        Long updatedAt;
        Long startedAt;
        String message;
        MediaDetails media;
    }

    static final class MediaDetails {
        String partId;
        String partKey;
        String container;
        String videoCodec;
        String audioCodec;
        String videoResolution;
        Integer bitrate;
        String file;
        Long size;
        Long duration;
    }

    static final class Subtitle {
        String id;
        String key;
        String partId;
        String streamId;
        Integer streamIndex;
        String codec;
        String language;
        String languageCode;
        String srclang;
        String title;
        String displayTitle;
        String label;
        @SerializedName("default")
        boolean defaultValue;
        boolean selected;
        boolean forced;
        boolean hearingImpaired;
        boolean external;
        boolean embedded;
        boolean supported;
        String source;
        String subtitleUrl;

        String label() {
            return nonEmpty(label, nonEmpty(displayTitle, nonEmpty(language, "Subtitle")));
        }
    }

    static final class SubtitleResult {
        String id;
        String subtitleId;
        String fileId;
        String fileName;
        String language;
        String languageName;
        String release;
        Integer downloads;
        Float fps;
        Float rating;
        Integer votes;
        boolean hearingImpaired;
        boolean trusted;
        boolean foreignPartsOnly;
        boolean aiTranslated;
        String uploadDate;

        String title() {
            return nonEmpty(release, nonEmpty(fileName, "Subtitle"));
        }

        String meta() {
            List<String> parts = new ArrayList<>();
            parts.add(nonEmpty(languageName, nonEmpty(language, "Language")));
            if (rating != null) {
                parts.add(String.format(Locale.US, "%.1f", rating));
            }
            if (downloads != null) {
                parts.add(downloads + " downloads");
            }
            if (trusted) {
                parts.add("trusted");
            }
            if (hearingImpaired) {
                parts.add("SDH");
            }
            if (aiTranslated) {
                parts.add("AI");
            }
            return join(parts, "  ");
        }
    }

    static final class Guid {
        String id;
        String source;
        String value;
    }

    static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    static String join(List<String> parts, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(part);
        }
        return builder.toString();
    }
}
