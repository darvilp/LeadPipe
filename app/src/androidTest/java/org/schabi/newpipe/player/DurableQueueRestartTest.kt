package org.schabi.newpipe.player

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.QueueFile
import org.schabi.newpipe.player.playqueue.QueueSnapshot
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.InfoCache
import org.schabi.newpipe.util.NavigationHelper
import java.io.File

/** Run seed, force-stop/reboot, then restore on an isolated emulator. */
@RunWith(AndroidJUnit4::class)
class DurableQueueRestartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val first = "https://www.youtube.com/watch?v=aaaaaaaaaaa"
    private val second = "https://www.youtube.com/watch?v=bbbbbbbbbbb"
    private val snapshot = QueueSnapshot(
        listOf(item("duplicate", first), item("other", second), item("original", first)),
        "duplicate", 5000, RepeatMode.ALL.ordinal,
        listOf("original", "other", "duplicate"), null,
    )
    private val file = QueueFile(File(context.noBackupFilesDir, "active-play-queue.json"))

    @Test
    fun seedCheckpoint() {
        requireProbe()
        assertTrue(file.write(snapshot))
    }

    @Test
    fun launcherRestoresPausedThenEnqueueNextAndClearWork() {
        requireProbe()
        cacheMedia()
        launchMain()
        val player = awaitPlayer()
        await("Saved position restored") {
            player.currentItem?.uuid == "duplicate" && player.currentPosition == 5000L &&
                player.isPrepared
        }
        instrumentation.runOnMainSync {
            assertEquals(listOf("duplicate", "other", "original"), player.playQueue!!.ids())
            assertEquals(RepeatMode.ALL, player.repeatMode)
            assertTrue(player.playQueue!!.isShuffled)
            assertFalse(player.playWhenReady)
            val enqueue = checkNotNull(PlayQueue.restore(snapshot.copy(
                entries = listOf(item("next", second)),
                selectedId = "next", originalOrder = null,
            )))
            NavigationHelper.enqueueNextOnPlayer(context, enqueue)
        }
        await("Enqueue Next preserved the paused queue") {
            player.playQueue?.ids() == listOf("duplicate", "next", "other", "original") &&
                !player.playWhenReady
        }
        instrumentation.runOnMainSync {
            player.queueCheckpoint.clear { cleared ->
                assertTrue(cleared)
                player.service.stopService()
            }
        }
        await("Player closed after clear") { PlayerHolder.getInstance().player == null }
        assertEquals(QueueSnapshot.EMPTY, file.read())
    }

    @Test
    fun coldEnqueueUsesTheSavedQueueBeforeLauncherRestore() {
        requireProbe()
        assertTrue(file.write(snapshot))
        cacheMedia()
        // A foreground activity can start playback, but a non-launcher intent is not a restore.
        launchMain(launcher = false)
        val incoming = checkNotNull(PlayQueue.restore(snapshot.copy(
            entries = listOf(item("appended", second)),
            selectedId = "appended", originalOrder = null,
        )))
        instrumentation.runOnMainSync {
            ContextCompat.startForegroundService(
                context, NavigationHelper.getPlayerEnqueueIntent(
                    context, DeviceUtils.getPlayerServiceClass(), incoming
                ).putExtra(PlayerIntentConstants.PLAYER_TYPE, PlayerService.PlayerType.AUDIO.ordinal)
            )
        }
        val player = awaitPlayer()
        await("Cold enqueue appended to the saved queue") {
            player.playQueue?.ids() == listOf("duplicate", "other", "original", "appended") &&
                !player.playWhenReady
        }
    }

    private fun requireProbe() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("durableRestartProbe") == "true")
    }

    private fun item(id: String, url: String) =
        PlayerMediaItem.Builder().uuid(id).serviceId(0).url(url).title(id).duration(30)
            .streamType(StreamType.VIDEO_STREAM).build()

    private fun PlayQueue.ids() = streams.map { it.uuid }

    private fun launchMain(launcher: Boolean = true) {
        val component = ComponentName(context, MainActivity::class.java)
        val intent = if (launcher) Intent.makeMainActivity(component)
            else Intent().setComponent(component)
        instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun awaitPlayer(): Player {
        var active: Player? = null
        await("Player attached") {
            active = PlayerHolder.getInstance().player
            active?.playQueue != null
        }
        return checkNotNull(active)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(message)
    }

    private fun cacheMedia() {
        val asset = File(context.cacheDir, "durable-queue-fixture.mp4")
        instrumentation.context.assets.open("player-mode-fixture.mp4").use { source ->
            asset.outputStream().use { source.copyTo(it) }
        }
        for (url in listOf(first, second)) {
            val info = StreamInfo(0, url, url, "Saved queue fixture").apply {
                streamType = StreamType.VIDEO_STREAM
                duration = 30
                description = Description.EMPTY_DESCRIPTION
                uploaderName = "Fixture"
                videoStreams = listOf(VideoStream.Builder().setId("fixture")
                    .setContent(Uri.fromFile(asset).toString(), true).setMediaFormat(MediaFormat.MPEG_4)
                    .setResolution("144p").setIsVideoOnly(false).build())
            }
            InfoCache.getInstance().putInfo(0, url, info, InfoType.STREAM)
        }
    }
}
