package com.spiros.plexopenandroid;

import java.util.Locale;

final class PlaybackStream {
    private PlaybackStream() {
    }

    static String urlFor(Models.MediaItem item) {
        if (item == null) {
            return null;
        }
        if (isReadySaved(item)) {
            return item.savedPlayback.streamUrl;
        }
        if (shouldUseCompatibleStream(item)) {
            String compatible = compatibleUrl(item);
            if (compatible != null) {
                return hlsCompatibleUrl(compatible, item);
            }
        }
        if (item.playback != null && item.playback.directStreamUrl != null) {
            return item.playback.directStreamUrl;
        }
        String compatible = compatibleUrl(item);
        if (compatible != null) {
            return compatible;
        }
        return item.streamUrl;
    }

    static String hlsFallbackUrl(Models.MediaItem item, String currentUrl) {
        if (item == null || isReadySaved(item)) {
            return null;
        }
        String compatible = compatibleUrl(item);
        if (compatible == null) {
            compatible = item.streamUrl;
        }
        if (compatible == null) {
            return null;
        }
        String hls = hlsCompatibleUrl(compatible, item);
        if (hls.equals(currentUrl) || isHls(currentUrl)) {
            return null;
        }
        return hls;
    }

    static boolean isHls(String path) {
        return queryContains(path, "format", "hls");
    }

    static Long durationMs(Models.MediaItem item) {
        if (item == null) {
            return null;
        }
        if (item.duration != null && item.duration > 0L) {
            return item.duration;
        }
        if (item.media != null && item.media.duration != null && item.media.duration > 0L) {
            return item.media.duration;
        }
        return null;
    }

    static boolean shouldUseCompatibleStream(Models.MediaItem item) {
        Models.Playback playback = item == null ? null : item.playback;
        if (playback != null && playback.audioTranscodeRequired) {
            return true;
        }
        return containerIsUnseekable(item);
    }

    static boolean containerIsUnseekable(Models.MediaItem item) {
        String container = item == null || item.media == null ? null : item.media.container;
        if (container == null || container.isEmpty()) {
            return false;
        }
        switch (container.toLowerCase(Locale.US)) {
            case "mpegts":
            case "ts":
            case "m2ts":
            case "mts":
            case "vob":
            case "mpg":
            case "mpeg":
            case "wtv":
                return true;
            default:
                return false;
        }
    }

    static String hlsCompatibleUrl(String url, Models.MediaItem item) {
        String converted = toCompatibleEndpoint(url);
        converted = withQueryParam(converted, "format", "hls");
        if (item != null && item.ratingKey != null && !item.ratingKey.isEmpty()
                && !queryContains(converted, "ratingKey", null)) {
            converted = withQueryParam(converted, "ratingKey", item.ratingKey);
        }
        return converted;
    }

    static String toCompatibleEndpoint(String url) {
        if (url == null) {
            return null;
        }
        if (url.contains("/api/stream-compatible")) {
            return url;
        }
        String marker = "/api/stream?";
        int index = url.indexOf(marker);
        if (index >= 0) {
            return url.substring(0, index) + "/api/stream-compatible?" + url.substring(index + marker.length());
        }
        return url;
    }

    static String withQueryParam(String url, String name, String value) {
        if (url == null || url.isEmpty() || name == null || name.isEmpty() || value == null) {
            return url;
        }
        if (queryContains(url, name, null)) {
            return url;
        }
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + name + "=" + value;
    }

    static boolean queryContains(String url, String name, String expectedValue) {
        if (url == null || name == null || name.isEmpty()) {
            return false;
        }
        int queryAt = url.indexOf('?');
        if (queryAt < 0 || queryAt + 1 >= url.length()) {
            return false;
        }
        String query = url.substring(queryAt + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        String prefix = name + "=";
        for (String part : query.split("&")) {
            if (expectedValue == null) {
                if (part.equals(name) || part.startsWith(prefix)) {
                    return true;
                }
            } else if (part.equals(prefix + expectedValue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReadySaved(Models.MediaItem item) {
        return item.savedPlayback != null && item.savedPlayback.ready && item.savedPlayback.streamUrl != null;
    }

    private static String compatibleUrl(Models.MediaItem item) {
        if (item.playback != null && item.playback.compatibleStreamUrl != null) {
            return item.playback.compatibleStreamUrl;
        }
        return item.compatibleStreamUrl;
    }
}
