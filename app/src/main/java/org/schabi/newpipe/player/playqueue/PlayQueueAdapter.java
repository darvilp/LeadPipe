package org.schabi.newpipe.player.playqueue;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.player.playqueue.events.MoveEvent;
import org.schabi.newpipe.player.playqueue.events.PlayQueueEvent;
import org.schabi.newpipe.player.playqueue.events.PlayQueueEventType;
import org.schabi.newpipe.util.FallbackViewHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Created by Christian Schabesberger on 01.08.16.
 * <p>
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
 * InfoListAdapter.java is part of NewPipe.
 * </p>
 * <p>
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * </p>
 * <p>
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * </p>
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 * </p>
 */

public class PlayQueueAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String TAG = PlayQueueAdapter.class.toString();

    private static final int ITEM_VIEW_TYPE_ID = 0;
    private static final int FOOTER_VIEW_TYPE_ID = 1;

    private final PlayQueueItemBuilder playQueueItemBuilder;
    private final PlayQueue playQueue;
    private List<PlayerMediaItem> displayedItems = Collections.emptyList();
    private int displayedSelection = RecyclerView.NO_POSITION;
    private boolean showFooter = false;
    private View footer = null;

    private Disposable playQueueReactor;

    public PlayQueueAdapter(final Context context, final PlayQueue playQueue) {
        if (playQueue.getBroadcastReceiver() == null) {
            throw new IllegalStateException("Play Queue has not been initialized.");
        }

        this.playQueueItemBuilder = new PlayQueueItemBuilder(context);
        this.playQueue = playQueue;
        updateRows(null);

        playQueue.getBroadcastReceiver().toObservable().subscribe(getReactor());
    }

    private Observer<PlayQueueEvent> getReactor() {
        return new Observer<PlayQueueEvent>() {
            @Override
            public void onSubscribe(@NonNull final Disposable d) {
                if (playQueueReactor != null) {
                    playQueueReactor.dispose();
                }
                playQueueReactor = d;
            }

            @Override
            public void onNext(@NonNull final PlayQueueEvent playQueueMessage) {
                if (playQueueReactor != null
                        && playQueueMessage.type() != PlayQueueEventType.RECOVERY) {
                    updateRows(playQueueMessage);
                }
            }

            @Override
            public void onError(@NonNull final Throwable e) { }

            @Override
            public void onComplete() {
                dispose();
            }
        };

    }

    private void updateRows(@Nullable final PlayQueueEvent event) {
        final List<PlayerMediaItem> previousItems = displayedItems;
        final int previousSelection = displayedSelection;
        final List<PlayerMediaItem> nextItems;
        final int nextSelection;
        synchronized (playQueue) {
            nextItems = Collections.unmodifiableList(new ArrayList<>(playQueue.getStreams()));
            nextSelection = nextItems.isEmpty() ? RecyclerView.NO_POSITION : playQueue.getIndex();
        }

        // Queue events are delivered after mutations. Publish one row snapshot and notify
        // RecyclerView about the actual differences, even if more edits have already landed.
        displayedItems = nextItems;
        displayedSelection = nextSelection;
        if (event != null && event.type() == PlayQueueEventType.REORDER) {
            // Shuffle can reorder the entire queue, so keep the full refresh for it.
            notifyDataSetChanged();
            return;
        }
        if (event instanceof MoveEvent) {
            final MoveEvent move = (MoveEvent) event;
            if (matchesMove(previousItems, nextItems, previousSelection, nextSelection, move)) {
                // The drag callback tracks the exact from/to pair to keep the viewport anchored.
                notifyItemMoved(move.getFromIndex(), move.getToIndex());
                return;
            }
        }

        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return previousItems.size();
            }

            @Override
            public int getNewListSize() {
                return nextItems.size();
            }

            @Override
            public boolean areItemsTheSame(final int oldPosition, final int newPosition) {
                return previousItems.get(oldPosition).equals(nextItems.get(newPosition));
            }

            @Override
            public boolean areContentsTheSame(final int oldPosition, final int newPosition) {
                final PlayerMediaItem oldItem = previousItems.get(oldPosition);
                final PlayerMediaItem newItem = nextItems.get(newPosition);
                return oldItem == newItem
                        && (oldPosition == previousSelection) == (newPosition == nextSelection);
            }
        }).dispatchUpdatesTo(this);
    }

    private static boolean matchesMove(final List<PlayerMediaItem> before,
                                       final List<PlayerMediaItem> after, final int previousSelection,
                                       final int nextSelection, final MoveEvent move) {
        final int from = move.getFromIndex();
        final int to = move.getToIndex();
        if (before.size() != after.size() || from < 0 || from >= before.size()
                || to < 0 || to >= before.size()
                || positionAfterMove(previousSelection, from, to) != nextSelection) {
            return false;
        }
        for (int position = 0; position < before.size(); position++) {
            if (before.get(position) != after.get(positionAfterMove(position, from, to))) {
                return false;
            }
        }
        return true;
    }

    private static int positionAfterMove(final int position, final int from, final int to) {
        if (position == from) {
            return to;
        }
        if (from < position && position <= to) {
            return position - 1;
        }
        if (to <= position && position < from) {
            return position + 1;
        }
        return position;
    }

    public void dispose() {
        if (playQueueReactor != null) {
            playQueueReactor.dispose();
        }
        playQueueReactor = null;
    }

    public void setSelectedListener(final PlayQueueItemBuilder.OnSelectedListener listener) {
        playQueueItemBuilder.setOnSelectedListener(listener);
    }

    public void unsetSelectedListener() {
        playQueueItemBuilder.setOnSelectedListener(null);
    }

    public void setFooter(final View footer) {
        this.footer = footer;
        notifyItemChanged(displayedItems.size());
    }

    public void showFooter(final boolean show) {
        showFooter = show;
        notifyItemChanged(displayedItems.size());
    }

    public List<PlayerMediaItem> getItems() {
        return displayedItems;
    }

    int queueIndexForRow(final int position) {
        if (position < 0 || position >= displayedItems.size()) {
            return RecyclerView.NO_POSITION;
        }
        final PlayerMediaItem item = displayedItems.get(position);
        synchronized (playQueue) {
            return playQueue.getItem(position) == item ? position : playQueue.indexOf(item);
        }
    }

    @Override
    public int getItemCount() {
        int count = displayedItems.size();
        if (footer != null && showFooter) {
            count++;
        }
        return count;
    }

    @Override
    public int getItemViewType(final int position) {
        if (footer != null && position == displayedItems.size() && showFooter) {
            return FOOTER_VIEW_TYPE_ID;
        }

        return ITEM_VIEW_TYPE_ID;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                      final int type) {
        switch (type) {
            case FOOTER_VIEW_TYPE_ID:
                return new HFHolder(footer);
            case ITEM_VIEW_TYPE_ID:
                return new PlayQueueItemHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.play_queue_item, parent, false));
            default:
                Log.e(TAG, "Attempting to create view holder with undefined type: " + type);
                return new FallbackViewHolder(new View(parent.getContext()));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder,
                                 final int position) {
        if (holder instanceof PlayQueueItemHolder) {
            final PlayQueueItemHolder itemHolder = (PlayQueueItemHolder) holder;
            final PlayerMediaItem item = displayedItems.get(position);

            // Build the list item
            playQueueItemBuilder.buildStreamInfoItem(itemHolder, item);

            // Check if the current item should be selected/highlighted
            itemHolder.itemView.setSelected(position == displayedSelection);
        } else if (holder instanceof HFHolder && position == displayedItems.size()
                && footer != null && showFooter) {
            ((HFHolder) holder).view = footer;
        }
    }

    public static class HFHolder extends RecyclerView.ViewHolder {
        public View view;

        public HFHolder(final View v) {
            super(v);
            view = v;
        }
    }
}
