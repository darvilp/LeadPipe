package org.schabi.newpipe.player.playqueue.events;

public final class RemovePrefixEvent implements PlayQueueEvent {
    private final int count;
    private final int remainingCount;

    public RemovePrefixEvent(final int count, final int remainingCount) {
        this.count = count;
        this.remainingCount = remainingCount;
    }

    @Override
    public PlayQueueEventType type() {
        return PlayQueueEventType.REMOVE_PREFIX;
    }

    public int getCount() {
        return count;
    }

    public int getRemainingCount() {
        return remainingCount;
    }
}
