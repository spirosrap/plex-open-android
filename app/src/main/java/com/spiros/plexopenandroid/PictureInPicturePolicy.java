package com.spiros.plexopenandroid;

import androidx.media3.common.Player;

final class PictureInPicturePolicy {
    private PictureInPicturePolicy() {}

    static boolean shouldEnter(boolean playerOpen, boolean playWhenReady, int playbackState) {
        return playerOpen
                && playWhenReady
                && playbackState != Player.STATE_IDLE
                && playbackState != Player.STATE_ENDED;
    }

    static boolean shouldReleaseAfterDismissal(
            boolean playerOpen,
            boolean activityResumed,
            boolean inPictureInPicture
    ) {
        return playerOpen && !activityResumed && !inPictureInPicture;
    }
}
