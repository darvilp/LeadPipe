package org.schabi.newpipe.player.playqueue.events;

public final class InsertEvent implements PlayQueueEvent {
    private final int index;

    public InsertEvent(final int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public PlayQueueEventType type() {
        return PlayQueueEventType.INSERT;
    }
}
