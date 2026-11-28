package org.schabi.newpipe.player.playqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem

class PlayQueueMoveNextTest {
    @Test
    fun movesTheSpecificDuplicateFromEitherSideOfTheCurrentEntry() {
        val earlier = item("earlier", "same-video")
        val later = item("later", "same-video")
        val playing = item("playing")
        val next = item("next")
        val queue = SinglePlayQueue(1, listOf(earlier, playing, next, later))

        assertFalse(queue.canMoveToPlayNext(playing))
        assertFalse(queue.moveToPlayNext(next))
        assertTrue(queue.moveToPlayNext(later))
        assertEquals(listOf(earlier, playing, later, next), queue.streams)
        assertTrue(queue.moveToPlayNext(earlier))
        assertEquals(listOf(playing, earlier, later, next), queue.streams)
        assertSame(playing, queue.item)

        queue.remove(1)
        assertFalse(queue.moveToPlayNext(earlier))
    }

    @Test
    fun movingAnEarlierItemWorksWhenTheCurrentItemIsLast() {
        val first = item("first")
        val earlier = item("earlier")
        val playing = item("playing")
        val queue = SinglePlayQueue(2, listOf(first, earlier, playing))

        assertTrue(queue.moveToPlayNext(first))
        assertEquals(listOf(earlier, playing, first), queue.streams)
        assertSame(playing, queue.item)
    }

    @Test
    fun moveKeepsTheOriginalShuffleOrderAndPromotesAnAutomaticTail() {
        val first = item("first")
        val playing = item("playing")
        val later = item("later")
        val tail = item("tail")
        val snapshot = QueueSnapshot(
            listOf(playing, later, first, tail), playing.uuid, 0, 0,
            listOf(first.uuid, playing.uuid, later.uuid, tail.uuid), tail.uuid,
        )
        val queue = checkNotNull(PlayQueue.restore(snapshot))

        assertTrue(queue.moveToPlayNext(tail))
        val edited = queue.snapshot(0, 0)
        assertEquals(listOf(playing, tail, later, first), edited.entries)
        assertEquals(snapshot.originalOrder, edited.originalOrder)
        assertNull(edited.automaticTailId)
        val restored = checkNotNull(PlayQueue.restore(edited))
        restored.unshuffle()
        assertEquals(listOf(first, playing, later, tail), restored.streams)
        assertSame(playing, restored.item)
    }

    private fun item(id: String, video: String = id) =
        PlayerMediaItem.Builder().uuid(id).url("https://example.com/$video").title(id).build()
}
