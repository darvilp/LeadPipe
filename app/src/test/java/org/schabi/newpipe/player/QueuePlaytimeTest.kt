package org.schabi.newpipe.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem

class QueuePlaytimeTest {
    @Test
    fun elapsedIncludesEarlierItemsAndTracksSeeking() {
        val playtime = QueuePlaytime.calculate(listOf(item(120), item(60), item(300)), 1)

        assertEquals(480L, playtime.totalSeconds)
        assertFalse(playtime.hasUnknownDurations)
        assertEquals(QueuePlaytime.Elapsed(137, true), playtime.elapsed(17_900))
        assertEquals(QueuePlaytime.Elapsed(125, true), playtime.elapsed(5_000))
        assertEquals(QueuePlaytime.Elapsed(180, true), playtime.elapsed(80_000))
    }

    @Test
    fun reorderingChangesElapsedAndAppendingChangesTotal() {
        val first = item(120)
        val selected = item(60)
        val playtime = QueuePlaytime.calculate(listOf(selected, first, item(30)), 0)

        assertEquals(selected, playtime.selected)
        assertEquals(210L, playtime.totalSeconds)
        assertEquals(QueuePlaytime.Elapsed(5, true), playtime.elapsed(5_000))
    }

    @Test
    fun liveAndUnknownDurationsLeaveALowerBound() {
        val live = PlayerMediaItem.Builder()
            .streamType(StreamType.LIVE_STREAM)
            .duration(900)
            .build()
        val items = listOf(item(-1), item(120), live, item(0))
        val beforeLive = QueuePlaytime.calculate(items, 1)
        val duringLive = QueuePlaytime.calculate(items, 2)
        val unknownDuration = QueuePlaytime.calculate(items, 3)

        assertEquals(120L, duringLive.totalSeconds)
        assertTrue(duringLive.hasUnknownDurations)
        assertEquals(QueuePlaytime.Elapsed(30, false), beforeLive.elapsed(30_000))
        assertEquals(QueuePlaytime.Elapsed(120, false), duringLive.elapsed(30_000))
        assertEquals(QueuePlaytime.Elapsed(150, false), unknownDuration.elapsed(30_000))
    }

    @Test
    fun unknownItemsAfterSelectionDoNotMakeElapsedPartial() {
        val playtime = QueuePlaytime.calculate(listOf(item(120), item(60), item(0)), 1)

        assertTrue(playtime.hasUnknownDurations)
        assertEquals(QueuePlaytime.Elapsed(135, true), playtime.elapsed(15_000))
        assertEquals(QueuePlaytime.Elapsed(120, false), playtime.elapsed(null))
        assertEquals(QueuePlaytime.Elapsed(120, false), playtime.elapsed(-1))
    }

    @Test
    fun aLargeLoadedQueueIsNotCappedOrLimitedToIntMilliseconds() {
        val longVideo = item(300)
        val playtime = QueuePlaytime.calculate(List(10_000) { longVideo }, 9_999)

        assertEquals(3_000_000L, playtime.totalSeconds)
        assertEquals(QueuePlaytime.Elapsed(2_999_730L, true), playtime.elapsed(30_000))
    }

    @Test
    fun emptyQueueHasNoSelectedItemOrTime() {
        val playtime = QueuePlaytime.calculate(emptyList(), 0)

        assertEquals(null, playtime.selected)
        assertEquals(0L, playtime.totalSeconds)
        assertEquals(QueuePlaytime.Elapsed(0, true), playtime.elapsed(null))
    }

    private fun item(duration: Long): PlayerMediaItem =
        PlayerMediaItem.Builder().streamType(StreamType.VIDEO_STREAM).duration(duration).build()
}
