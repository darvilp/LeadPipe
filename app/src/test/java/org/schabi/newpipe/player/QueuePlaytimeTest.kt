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
        assertEquals(QueuePlaytime.Estimate(137, true), playtime.elapsed(17_900))
        assertEquals(QueuePlaytime.Estimate(125, true), playtime.elapsed(5_000))
        assertEquals(QueuePlaytime.Estimate(180, true), playtime.elapsed(80_000))
    }

    @Test
    fun reorderingChangesElapsedAndAppendingChangesTotal() {
        val first = item(120)
        val selected = item(60)
        val playtime = QueuePlaytime.calculate(listOf(selected, first, item(30)), 0)

        assertEquals(selected, playtime.selected)
        assertEquals(210L, playtime.totalSeconds)
        assertEquals(QueuePlaytime.Estimate(5, true), playtime.elapsed(5_000))
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
        assertEquals(QueuePlaytime.Estimate(30, false), beforeLive.elapsed(30_000))
        assertEquals(QueuePlaytime.Estimate(120, false), duringLive.elapsed(30_000))
        assertEquals(QueuePlaytime.Estimate(150, false), unknownDuration.elapsed(30_000))
    }

    @Test
    fun unknownItemsAfterSelectionDoNotMakeElapsedPartial() {
        val playtime = QueuePlaytime.calculate(listOf(item(120), item(60), item(0)), 1)

        assertTrue(playtime.hasUnknownDurations)
        assertEquals(QueuePlaytime.Estimate(135, true), playtime.elapsed(15_000))
        assertEquals(QueuePlaytime.Estimate(120, false), playtime.elapsed(null))
        assertEquals(QueuePlaytime.Estimate(120, false), playtime.elapsed(-1))
    }

    @Test
    fun aLargeLoadedQueueIsNotCappedOrLimitedToIntMilliseconds() {
        val longVideo = item(300)
        val playtime = QueuePlaytime.calculate(List(10_000) { longVideo }, 9_999)

        assertEquals(3_000_000L, playtime.totalSeconds)
        assertEquals(QueuePlaytime.Estimate(2_999_730L, true), playtime.elapsed(30_000))
        assertEquals(QueuePlaytime.Estimate(2_999_970L, true),
            QueuePlaytime.calculate(List(10_000) { longVideo }, 0).remaining(30_000))
    }

    @Test
    fun remainingCountsTheCurrentRemainderAndUpcomingEntriesOnly() {
        val playtime = QueuePlaytime.calculate(listOf(item(0), item(60), item(300)), 1)

        assertEquals(QueuePlaytime.Estimate(343, true), playtime.remaining(17_900))
        assertEquals(QueuePlaytime.Estimate(355, true), playtime.remaining(5_000))
        assertEquals(QueuePlaytime.Estimate(300, true), playtime.remaining(65_000))
        assertEquals(QueuePlaytime.Estimate(300, false), playtime.remaining(null))
        assertEquals(QueuePlaytime.Estimate(300, false), playtime.remaining(-1))
    }

    @Test
    fun liveAndUnknownEntriesOnlyContributeKnownRemainingTime() {
        val live = PlayerMediaItem.Builder().streamType(StreamType.LIVE_STREAM).duration(900).build()
        val items = listOf(item(60), live, item(120), item(0))

        assertEquals(QueuePlaytime.Estimate(170, false),
            QueuePlaytime.calculate(items, 0).remaining(10_000))
        assertEquals(QueuePlaytime.Estimate(120, false),
            QueuePlaytime.calculate(items, 1).remaining(850_000))
        assertEquals(QueuePlaytime.Estimate(0, false),
            QueuePlaytime.calculate(items, 3).remaining(15_000))
    }

    @Test
    fun emptyQueueHasNoSelectedItemOrTime() {
        val playtime = QueuePlaytime.calculate(emptyList(), 0)

        assertEquals(null, playtime.selected)
        assertEquals(0L, playtime.totalSeconds)
        assertEquals(QueuePlaytime.Estimate(0, true), playtime.elapsed(null))
        assertEquals(QueuePlaytime.Estimate(0, true), playtime.remaining(null))
    }

    private fun item(duration: Long): PlayerMediaItem =
        PlayerMediaItem.Builder().streamType(StreamType.VIDEO_STREAM).duration(duration).build()
}
