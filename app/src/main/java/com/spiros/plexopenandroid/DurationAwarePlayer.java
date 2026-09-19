package com.spiros.plexopenandroid;

import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

@UnstableApi
final class DurationAwarePlayer extends ForwardingPlayer {
    DurationAwarePlayer(Player player) {
        super(player);
    }

    @Override
    public long getDuration() {
        long known = knownDurationMs();
        return known > 0L ? known : super.getDuration();
    }

    @Override
    public long getContentDuration() {
        return getDuration();
    }

    @Override
    public boolean isCurrentMediaItemSeekable() {
        return super.isCurrentMediaItemSeekable() || knownDurationMs() > 0L;
    }

    @Override
    public boolean isCommandAvailable(int command) {
        return getAvailableCommands().contains(command);
    }

    @Override
    public Player.Commands getAvailableCommands() {
        Player.Commands commands = super.getAvailableCommands();
        if (knownDurationMs() <= 0L) {
            return commands;
        }
        return commands.buildUpon()
                .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_DEFAULT_POSITION)
                .add(COMMAND_SEEK_BACK)
                .add(COMMAND_SEEK_FORWARD)
                .build();
    }

    private long knownDurationMs() {
        long duration = super.getDuration();
        if (duration > 0L && duration != C.TIME_UNSET) {
            return duration;
        }
        duration = super.getContentDuration();
        if (duration > 0L && duration != C.TIME_UNSET) {
            return duration;
        }
        MediaItem item = getCurrentMediaItem();
        Long metadata = item == null ? null : item.mediaMetadata.durationMs;
        if (metadata != null && metadata > 0L) {
            return metadata;
        }
        return 0L;
    }
}
