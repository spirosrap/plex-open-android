package com.spiros.plexopenandroid;

import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

final class DurationAwarePlayer extends ForwardingPlayer {
    DurationAwarePlayer(Player player) {
        super(player);
    }

    @Override
    public long getDuration() {
        long duration = super.getDuration();
        if (duration > 0L && duration != C.TIME_UNSET) {
            return duration;
        }
        MediaItem item = getCurrentMediaItem();
        Long metadata = item == null ? null : item.mediaMetadata.durationMs;
        if (metadata != null && metadata > 0L) {
            return metadata;
        }
        return duration;
    }
}
