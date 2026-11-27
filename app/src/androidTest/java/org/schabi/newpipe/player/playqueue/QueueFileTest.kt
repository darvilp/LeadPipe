package org.schabi.newpipe.player.playqueue

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueueFileTest {
    @Test
    fun interruptedWriteLeavesPriorSnapshotAndClearSurvivesReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File.createTempFile("queue-checkpoint-", ".json", context.cacheDir)
        val atomic = AtomicFile(path)
        try {
            assertNull(QueueFile(path).read())
            val entry = PlayerMediaItem.Builder().uuid("a").url("https://example.com/video")
                .streamType(StreamType.VIDEO_STREAM).build()
            val saved = QueueSnapshot(listOf(entry), "a", 5000, 0, null, null)
            assertTrue(QueueFile(path).write(saved))
            atomic.startWrite().use { it.write("{interrupted".toByteArray()) }

            assertEquals(saved, QueueFile(path).read())
            assertTrue(QueueFile(path).write(QueueSnapshot.EMPTY))
            assertEquals(QueueSnapshot.EMPTY, QueueFile(path).read())
        } finally {
            atomic.delete()
        }
    }
}
