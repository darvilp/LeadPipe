package org.schabi.newpipe.player

import com.google.android.exoplayer2.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemainingPlaybackTimeTest {
    @Test
    fun subtractsMediaPositionFromDuration() {
        assertEquals(1_800_000L, remainingPlaybackTimeMillis(2_700_000, 900_000))
    }

    @Test
    fun clampsThePositionAtBothEnds() {
        assertEquals(30_000L, remainingPlaybackTimeMillis(30_000, -500))
        assertEquals(0L, remainingPlaybackTimeMillis(30_000, 32_000))
    }

    @Test
    fun unknownDurationsDoNotProduceARemainingTime() {
        assertNull(remainingPlaybackTimeMillis(C.TIME_UNSET, 5_000))
        assertNull(remainingPlaybackTimeMillis(0, 5_000))
    }
}
