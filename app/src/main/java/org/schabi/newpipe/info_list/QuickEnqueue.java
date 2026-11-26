package org.schabi.newpipe.info_list;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.NavigationHelper;

/** The row shortcut uses the same playback path as bulk enqueue. */
public final class QuickEnqueue {
    private final Context context;
    private final StreamInfoItem stream;

    private QuickEnqueue(final Context context, final StreamInfoItem stream) {
        this.context = context;
        this.stream = stream;
    }

    @Nullable
    public static QuickEnqueue actionFor(final View row, final StreamInfoItem stream) {
        final StreamType type = stream.getStreamType();
        if (type != StreamType.VIDEO_STREAM && type != StreamType.AUDIO_STREAM
                && type != StreamType.LIVE_STREAM && type != StreamType.AUDIO_LIVE_STREAM) {
            return null;
        }
        return new QuickEnqueue(row.getContext(), stream);
    }

    public void enqueue() {
        // Resolve sparse entries during playback so metadata requests cannot reorder taps.
        NavigationHelper.enqueueOnPlayer(context, new SinglePlayQueue(stream));
    }

    public void enqueueNext() {
        NavigationHelper.enqueueNextOnPlayer(context, new SinglePlayQueue(stream));
    }

    public static void bind(final View row, @Nullable final QuickEnqueue action) {
        final View button = row.findViewById(R.id.itemQuickEnqueue);
        if (button == null) {
            return;
        }
        button.setOnClickListener(action == null ? null : view -> action.enqueue());
        button.setOnLongClickListener(action == null ? null : view -> {
            action.enqueueNext();
            return true;
        });
        button.setLongClickable(action != null);
        button.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action == null) {
            ViewCompat.removeAccessibilityAction(button,
                    AccessibilityActionCompat.ACTION_LONG_CLICK.getId());
        } else {
            ViewCompat.replaceAccessibilityAction(button, AccessibilityActionCompat.ACTION_LONG_CLICK,
                    row.getContext().getString(R.string.enqueue_next_stream),
                    (view, arguments) -> view.performLongClick());
        }
    }
}
