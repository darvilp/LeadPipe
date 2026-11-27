package org.schabi.newpipe.player.playqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem

class QueueSnapshotTest {
    @Test
    fun roundTripsLargeShuffledQueueWithDuplicateVideos() {
        val entries = (0 until 10_000).map {
            PlayerMediaItem.Builder().uuid("entry-$it")
                .url("https://example.com/video/${it % 2}").title("Video ${it % 2}")
                .duration(120).streamType(StreamType.VIDEO_STREAM).build()
        }
        val snapshot = QueueSnapshot(
            entries.reversed(), entries[42].uuid, 23_000, 2, entries.map { it.uuid }, null
        )
        val restored = checkNotNull(QueueSnapshot.decode(snapshot.encode()))
        assertEquals(snapshot, restored)
        assertEquals(entries[42].url, restored.entries.first { it.uuid == restored.selectedId }.url)
        assertEquals(120L, restored.entries.first().duration)
        val queue = checkNotNull(PlayQueue.restore(restored))
        assertEquals(entries[42].uuid, queue.item?.uuid)
        assertEquals(23_000L, queue.getRecoveryPosition(queue.index))
        assertTrue(queue.isShuffled)
        queue.unshuffle()
        assertEquals(entries.map { it.uuid }, queue.streams.map { it.uuid })
        assertEquals(42, queue.index)
    }

    @Test
    fun rejectsInvalidSnapshotsAndReadsAClearMarker() {
        assertNull(QueueSnapshot.decode("{"))
        assertNull(QueueSnapshot.decode("""{"version":2,"entries":[]}"""))
        assertNull(QueueSnapshot.decode("""{"version":1,"entries":[{}]}"""))
        assertEquals(QueueSnapshot.EMPTY, QueueSnapshot.decode(QueueSnapshot.EMPTY.encode()))
    }
}
