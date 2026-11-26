package org.schabi.newpipe.info_list;

import android.view.View;

import androidx.annotation.Nullable;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.NavigationHelper;

/** The row shortcut uses the same playback path as bulk enqueue. */
public final class QuickEnqueue {
    private QuickEnqueue() {
    }

    @Nullable
    public static Runnable actionFor(final View row, final StreamInfoItem stream) {
        final StreamType type = stream.getStreamType();
        if (type != StreamType.VIDEO_STREAM && type != StreamType.AUDIO_STREAM
                && type != StreamType.LIVE_STREAM && type != StreamType.AUDIO_LIVE_STREAM) {
            return null;
        }
        // Resolve sparse entries during playback so metadata requests cannot reorder taps.
        return () -> NavigationHelper.enqueueOnPlayer(row.getContext(), new SinglePlayQueue(stream));
    }

    public static void bind(final View row, @Nullable final Runnable action) {
        final View button = row.findViewById(R.id.itemQuickEnqueue);
        if (button != null) {
            button.setOnClickListener(action == null ? null : view -> action.run());
            button.setVisibility(action == null ? View.GONE : View.VISIBLE);
        }
    }
}
