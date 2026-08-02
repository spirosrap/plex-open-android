package com.spiros.plexopenandroid;

import androidx.media3.common.Player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PictureInPicturePolicyTest {
    @Test
    public void playingOrBufferingVideoCanEnterPictureInPicture() {
        assertTrue(PictureInPicturePolicy.shouldEnter(true, true, Player.STATE_BUFFERING));
        assertTrue(PictureInPicturePolicy.shouldEnter(true, true, Player.STATE_READY));
    }

    @Test
    public void closedPausedIdleOrEndedVideoStaysOutOfPictureInPicture() {
        assertFalse(PictureInPicturePolicy.shouldEnter(false, true, Player.STATE_READY));
        assertFalse(PictureInPicturePolicy.shouldEnter(true, false, Player.STATE_READY));
        assertFalse(PictureInPicturePolicy.shouldEnter(true, true, Player.STATE_IDLE));
        assertFalse(PictureInPicturePolicy.shouldEnter(true, true, Player.STATE_ENDED));
    }

    @Test
    public void dismissedPictureInPictureReleasesOnlyWhenAppDidNotReturn() {
        assertTrue(PictureInPicturePolicy.shouldReleaseAfterDismissal(true, false, false));
        assertFalse(PictureInPicturePolicy.shouldReleaseAfterDismissal(true, true, false));
        assertFalse(PictureInPicturePolicy.shouldReleaseAfterDismissal(true, false, true));
        assertFalse(PictureInPicturePolicy.shouldReleaseAfterDismissal(false, false, false));
    }
}
