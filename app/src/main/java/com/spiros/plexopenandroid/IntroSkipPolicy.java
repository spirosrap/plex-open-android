package com.spiros.plexopenandroid;

final class IntroSkipPolicy {
    private static final long BUTTON_LEAD_MS = 2_000L;
    private static final long BUTTON_END_GUARD_MS = 500L;
    private static final long SEEK_LANDING_PADDING_MS = 200L;

    private IntroSkipPolicy() {
    }

    static boolean hasMarker(Models.MediaItem item) {
        Models.IntroMarker marker = item == null ? null : item.introMarker;
        return item != null
                && "episode".equals(item.type)
                && marker != null
                && "intro".equals(marker.type)
                && marker.startTimeOffset >= 0L
                && marker.endTimeOffset > marker.startTimeOffset + 3_000L;
    }

    static boolean shouldShow(Models.MediaItem item, long positionMs, boolean alreadySkipped) {
        if (!hasMarker(item) || alreadySkipped) {
            return false;
        }
        Models.IntroMarker marker = item.introMarker;
        return positionMs >= Math.max(0L, marker.startTimeOffset - BUTTON_LEAD_MS)
                && positionMs < marker.endTimeOffset - BUTTON_END_GUARD_MS;
    }

    static long seekTargetMs(Models.MediaItem item, long durationMs) {
        if (!hasMarker(item)) {
            return Math.max(0L, durationMs);
        }
        long target = item.introMarker.endTimeOffset + SEEK_LANDING_PADDING_MS;
        return durationMs > 0L ? Math.min(target, Math.max(0L, durationMs - 250L)) : target;
    }

    static boolean analysisFinished(Models.MediaItem item) {
        String state = item == null || item.introAnalysis == null ? null : item.introAnalysis.state;
        return "ready".equals(state)
                || "failed".equals(state)
                || "disabled".equals(state)
                || "unavailable".equals(state);
    }
}
