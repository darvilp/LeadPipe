package org.schabi.newpipe.player.playqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem

class PlayQueueUndoTest {
    @Test
    fun restoresExactDuplicateBesideItsNeighborsAfterAnotherEdit() {
        val first = item("first", "same-video")
        val removed = item("duplicate", "same-video")
        val next = item("next")
        val playing = item("playing")
        val added = item("added")
        val queue = SinglePlayQueue(3, listOf(first, removed, next, playing))
        queue.setRecovery(1, 7200)

        val undo = checkNotNull(queue.removeWithUndo(1))
        assertEquals(listOf(first, next, playing), queue.streams)
        queue.append(added)
        queue.move(3, 0)

        assertTrue(queue.undoRemoval(undo))
        assertEquals(listOf(added, first, removed, next, playing), queue.streams)
        assertSame(removed, queue.getItem(2))
        assertSame(playing, queue.item)
        assertEquals(7200L, queue.getRecoveryPosition(removed))
        assertFalse(queue.undoRemoval(undo))
    }

    @Test
    fun restoresShuffledAndOriginalOrders() {
        val first = item("first")
        val removed = item("removed")
        val playing = item("playing")
        val snapshot = QueueSnapshot(
            listOf(playing, removed, first), playing.uuid, 0, 0,
            listOf(first.uuid, playing.uuid, removed.uuid), null,
        )
        val queue = checkNotNull(PlayQueue.restore(snapshot))

        assertTrue(queue.undoRemoval(checkNotNull(queue.removeWithUndo(1))))
        assertEquals(snapshot.entries, queue.streams)
        val undo = checkNotNull(queue.removeWithUndo(1))
        queue.unshuffle()
        assertTrue(queue.undoRemoval(undo))
        assertEquals(listOf(first, playing, removed), queue.streams)
        assertSame(playing, queue.item)
    }

    @Test
    fun onlyTheLatestRemovalCanBeUndoneAndItCanRestoreAnEmptyQueue() {
        val first = item("first")
        val last = item("last")
        val queue = SinglePlayQueue(0, listOf(first, last))
        val replaced = checkNotNull(queue.removeWithUndo(0))
        val current = checkNotNull(queue.removeWithUndo(0))
        assertTrue(queue.isEmpty)
        assertTrue(queue.hasPendingRemovalUndo())
        assertEquals(QueueSnapshot.EMPTY, queue.snapshot(0, 0))
        assertFalse(queue.undoRemoval(replaced))

        assertTrue(queue.undoRemoval(current))
        assertSame(last, queue.item)
        assertFalse(queue.hasPendingRemovalUndo())
        val expired = checkNotNull(queue.removeWithUndo(0))
        assertTrue(queue.discardRemovalUndo(expired))
        assertFalse(queue.undoRemoval(expired))

        val other = SinglePlayQueue(0, listOf(first))
        val stale = checkNotNull(other.removeWithUndo(0))
        assertFalse(queue.undoRemoval(stale))
        other.dispose()
        assertFalse(other.undoRemoval(stale))
    }

    @Test
    fun undoKeepsTheExistingAutomaticTail() {
        val first = item("first")
        val playing = item("playing")
        val tail = item("automatic")
        val queue = SinglePlayQueue(1, listOf(first, playing))
        queue.appendAutoQueued(listOf(tail))

        assertTrue(queue.undoRemoval(checkNotNull(queue.removeWithUndo(0))))
        assertEquals(listOf(first, playing, tail), queue.streams)
        assertEquals(tail.uuid, queue.snapshot(0, 0).automaticTailId)
    }

    private fun item(id: String, video: String = id) = PlayerMediaItem.Builder()
        .uuid(id).url("https://example.com/$video").title(id).duration(30)
        .streamType(StreamType.VIDEO_STREAM).build()
}
