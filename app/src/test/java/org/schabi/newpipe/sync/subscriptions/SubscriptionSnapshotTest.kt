package org.schabi.newpipe.sync.subscriptions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SubscriptionSnapshotTest {
    private val origin = "7240d611-efae-46ef-bf8e-d490724ed068"
    private val channel = SnapshotChannel("youtube", "https://www.youtube.com/@leadpipe", "LeadPipe")

    @Test
    fun completeAndEmptySnapshotsRoundTrip() {
        for (channels in listOf(listOf(channel), emptyList())) {
            val snapshot = SubscriptionSnapshot(origin, 3_000_000_000L, channels)
            assertEquals(snapshot, SubscriptionSnapshot.read(ByteArrayInputStream(snapshot.encode().toByteArray())))
        }
    }

    @Test
    fun malformedOrUntrustedSnapshotsAreRejected() {
        val good = SubscriptionSnapshot(origin, 1, listOf(channel)).encode()
        listOf(
            good.dropLast(1),
            good + " junk",
            good.replace("\"version\":1", "\"version\":2"),
            good.replace(origin, "local"),
            good.replace("https://www.youtube.com", "https://example.invalid"),
            good.replace("https://www.youtube.com", "https://www.youtube.com@example.invalid"),
            SubscriptionSnapshot(origin, 1, listOf(channel, channel)).encode(),
            SubscriptionSnapshot(origin, 1, listOf(SnapshotChannel(
                "niconico", "https://example.invalid/https://ch.nicovideo.jp/suspicious", "Example"
            ))).encode()
        ).forEach {
            assertTrue(it, runCatching { SubscriptionSnapshot.decode(it) }.isFailure)
        }
    }

    @Test
    fun builtInChannelsUseStableKeys() {
        val rows = listOf(
            channel,
            SnapshotChannel("bilibili", "https://space.bilibili.com/42", "BiliBili"),
            SnapshotChannel("niconico", "https://www.nicovideo.jp/user/42", "NicoNico")
        )
        val decoded = SubscriptionSnapshot.decode(SubscriptionSnapshot(origin, 1, rows).encode())
        assertEquals(rows, decoded.channels)
        assertEquals(listOf(0, 5, 6), rows.map { SubscriptionSnapshot.serviceId(it.service) })
    }
}
