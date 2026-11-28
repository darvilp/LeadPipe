package org.schabi.newpipe.player.playqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem

class PlayQueueRemovePrefixTest {
    @Test
    fun confirmationRejectsChangedSelectionPrefixAndQueue() {
        val first = item("first")
        val second = item("second")
        val current = item("current")
        val queue = SinglePlayQueue(2, listOf(first, second, current))
        val preview = checkNotNull(queue.previewRemoveBeforeCurrent())

        assertEquals(2, preview.count)
        assertFalse(SinglePlayQueue(2, queue.streams).removeBeforeCurrent(preview))
        queue.setIndex(1)
        assertFalse(queue.removeBeforeCurrent(preview))
        queue.setIndex(2)
        queue.move(0, 1)
        assertFalse(queue.removeBeforeCurrent(preview))
        assertEquals(listOf(second, first, current), queue.streams)
    }

    @Test
    fun removalPreservesSelectionAndClearsHistoryRecoveryAndPendingUndo() {
        val first = item("first")
        val duplicate = item("duplicate")
        val removedWithUndo = item("removed")
        val current = item("current")
        val tail = item("tail")
        val queue = SinglePlayQueue(0, listOf(first, duplicate, current, removedWithUndo))
        val pendingUndo = checkNotNull(queue.removeWithUndo(3))
        queue.setIndex(1)
        queue.setIndex(2)
        queue.setRecovery(0, 2000)
        queue.setRecovery(2, 5000)
        val preview = checkNotNull(queue.previewRemoveBeforeCurrent())
        queue.appendAutoQueued(listOf(tail))

        assertTrue(queue.removeBeforeCurrent(preview))
        assertFalse(queue.removeBeforeCurrent(preview))
        assertEquals(listOf(current, tail), queue.streams)
        assertSame(current, queue.item)
        assertEquals(0, queue.index)
        assertEquals(5000L, queue.getRecoveryPosition(current))
        assertEquals(PlayQueue.RECOVERY_UNSET, queue.getRecoveryPosition(first))
        assertEquals(tail.uuid, queue.snapshot(5000, 0).automaticTailId)
        assertNull(queue.previewRemoveBeforeCurrent())
        assertFalse(queue.previous())
        assertFalse(queue.undoRemoval(pendingUndo))
    }

    @Test
    fun removedEntriesStayGoneWhenRestoringAndUnshuffling() {
        val before = item("before")
        val current = item("current")
        val after = item("after")
        val queue = checkNotNull(PlayQueue.restore(QueueSnapshot(
            listOf(before, current, after), current.uuid, 5000, 0,
            listOf(after.uuid, before.uuid, current.uuid), null,
        )))

        assertTrue(queue.removeBeforeCurrent(checkNotNull(queue.previewRemoveBeforeCurrent())))
        val snapshot = queue.snapshot(5000, 0)
        assertEquals(listOf(after.uuid, current.uuid), snapshot.originalOrder)
        val restored = checkNotNull(PlayQueue.restore(snapshot))
        assertEquals(listOf(current, after), restored.streams)
        assertEquals(5000L, restored.getRecoveryPosition(current))
        restored.unshuffle()
        assertEquals(listOf(after, current), restored.streams)
        assertSame(current, restored.item)
    }

    private fun item(id: String) = PlayerMediaItem.Builder()
        .uuid(id).url("https://example.com/same-video").title(id).duration(30).build()
}
