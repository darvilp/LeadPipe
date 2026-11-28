package org.schabi.newpipe.player

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.exoplayer2.Player as PlaybackPlayer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
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
import org.schabi.newpipe.util.KEY_URL
import org.schabi.newpipe.util.NavigationHelper
import java.io.File
import java.util.concurrent.FutureTask

/** Run seed, force-stop/reboot, then restore on an isolated emulator. */
@RunWith(AndroidJUnit4::class)
class DurableQueueRestartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val first = "https://www.youtube.com/watch?v=aaaaaaaaaaa"
    private val second = "https://www.youtube.com/watch?v=bbbbbbbbbbb"
    private val snapshot = QueueSnapshot(
        listOf(item("other", second), item("duplicate", first), item("original", first)),
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
            assertEquals(listOf("other", "duplicate", "original"), player.playQueue!!.ids())
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
            player.playQueue?.ids() == listOf("other", "duplicate", "next", "original") &&
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
            player.playQueue?.ids() == listOf("other", "duplicate", "original", "appended") &&
                !player.playWhenReady
        }
    }

    @Test
    fun theLastQueueEntryCanBeRestoredWithTheSnackbarAction() {
        requireProbe()
        val entry = snapshot.entries.first { it.uuid == snapshot.selectedId }
        assertTrue(file.write(snapshot.copy(entries = listOf(entry), originalOrder = null)))
        cacheMedia()
        launchMain()
        val player = awaitPlayer()
        await("Single entry prepared at the saved position") {
            player.isPrepared && player.currentPosition == 5000L && !player.playWhenReady
        }
        val fixtureInfo = player.currentStreamInfo.orElseThrow()

        val queue = checkNotNull(player.playQueue)
        val screen = openFullQueue(player)
        instrumentation.runOnMainSync {
            player.queueController.removeWithUndo(queue, 0, screen.findViewById(R.id.main_content))
        }
        await("Last removal leaves a visible Undo action and keeps the service alive") {
            val undo = screen.findViewById<View>(com.google.android.material.R.id.snackbar_action)
            if (!queue.isEmpty || PlayerHolder.getInstance().player !== player ||
                player.isPrepared || undo?.isShown != true
            ) return@await false
            // A fresh source is loaded after Undo. Keep this synthetic URL offline even if
            // activity startup evicted its metadata from the shared cache.
            InfoCache.getInstance().putInfo(entry.serviceId, entry.url, fixtureInfo, InfoType.STREAM)
            undo.performClick()
        }
        await("Undo restores the same entry paused at the original position") {
            player.playQueue === queue && queue.ids() == listOf(entry.uuid) &&
                player.isPrepared && player.currentPosition == 5000L && !player.playWhenReady
        }
    }

    @Test
    fun trimmingAndMovingTheQueueKeepPlaybackOnTheCurrentEntry() {
        requireProbe()
        val initial = checkNotNull(PlayQueue.restore(QueueSnapshot(
            listOf(item("played-1", first), item("played-2", second), item("playing", first),
                item("next", second), item("moved", first)),
            "playing", 5000, RepeatMode.OFF.ordinal, null, null,
        )))
        val player = startOfflineQueue(initial, PlayerService.PlayerType.VIDEO)
        val screen = openFullQueue(player)
        val queue = onMain { checkNotNull(player.playQueue) }
        onMain {
            val summary = screen.findViewById<QueuePlaytimeView>(R.id.queue_playtime)
            val otherSummary = QueuePlaytimeView(screen, null)
            assertEquals(screen.getString(R.string.play_queue_show_remaining, summary.text),
                summary.contentDescription)
            assertTrue(summary.performClick())
            assertEquals(screen.getString(R.string.play_queue_show_elapsed, summary.text),
                summary.contentDescription)
            otherSummary.update(player)
            assertEquals(summary.text.toString(), otherSummary.text.toString())
            otherSummary.clearQueue()
            player.play()
        }

        await("The fixture is playing") { player.isPlaying }
        val interruptions = mutableListOf<Boolean>()
        val listener = object : PlaybackPlayer.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                interruptions.add(isPlaying)
            }
        }
        val startPosition = onMain {
            player.simpleExoPlayer.addListener(listener)
            val before = player.currentPosition
            assertTrue(queue.removeBeforeCurrent(checkNotNull(queue.previewRemoveBeforeCurrent())))
            before
        }
        await("The new timeline keeps advancing the same source") {
            player.simpleExoPlayer.mediaItemCount == 3 &&
                player.simpleExoPlayer.currentMediaItem?.mediaId == "playing" &&
                player.simpleExoPlayer.currentMediaItemIndex == 0 &&
                player.currentPosition >= startPosition + 500L && player.isPlaying
        }
        onMain {
            player.simpleExoPlayer.removeListener(listener)
            assertTrue(interruptions.isEmpty())
            assertEquals(listOf("playing", "next", "moved"), queue.ids())
            assertTrue(queue.moveToPlayNext(checkNotNull(queue.getItem(2))))
            assertEquals(listOf("playing", "moved", "next"), queue.ids())
        }
        await("The moved item becomes next while the current item keeps playing") {
            player.simpleExoPlayer.currentMediaItem?.mediaId == "playing" && player.isPlaying &&
                player.simpleExoPlayer.getMediaItemAt(1).mediaId == "moved"
        }
        onMain { player.playNext() }
        await("Advancing plays the moved entry") {
            player.simpleExoPlayer.currentMediaItem?.mediaId == "moved" && player.isPlaying
        }
        await("The checkpoint records the edited queue") {
            file.read()?.let {
                it.entries.map { entry -> entry.uuid } == listOf("playing", "moved", "next") &&
                    it.selectedId == "moved"
            } == true
        }
    }

    @Test
    fun playingTitlesRevealDetailsWithoutRestartingPlayback() {
        requireProbe()
        val player = startOfflineQueue(
            checkNotNull(PlayQueue.restore(snapshot.copy(originalOrder = null))),
            PlayerService.PlayerType.AUDIO
        )
        val screen = openFullQueue(player)
        val queue = onMain { checkNotNull(player.playQueue) }
        val item = onMain { checkNotNull(queue.item) }
        val engine = onMain { player.simpleExoPlayer }
        val queuePosition = onMain {
            val position = player.currentPosition
            assertTrue(screen.findViewById<View>(R.id.song_name).performClick())
            position
        }

        var host: MainActivity? = null
        await("Queue title opens the current video details") {
            host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull { it.intent.getStringExtra(KEY_URL) == item.url && detailsAreExpanded(it) }
            host != null
        }
        onMain {
            assertSame(queue, player.playQueue)
            assertSame(engine, player.simpleExoPlayer)
            assertTrue(player.audioPlayerSelected())
            assertFalse(player.playWhenReady)
            assertEquals(queuePosition, player.currentPosition)
            NavigationHelper.switchPlayerMode(
                checkNotNull(host), player, PlayerService.PlayerType.VIDEO, true
            )
        }

        await("Main player enters fullscreen") { player.isFullscreen && player.parentActivity != null }
        onMain { player.showControls(0) }
        await("Fullscreen title is visible") { player.binding.titleTextView.isShown }
        val fullscreenPosition = onMain {
            val position = player.currentPosition
            assertTrue(player.binding.titleTextView.performClick())
            position
        }
        await("Fullscreen title reveals the same video's details") {
            !player.isFullscreen && player.parentActivity?.let(::detailsAreExpanded) == true
        }
        onMain {
            assertSame(queue, player.playQueue)
            assertSame(item, queue.item)
            assertSame(engine, player.simpleExoPlayer)
            assertFalse(player.playWhenReady)
            assertEquals(fullscreenPosition, player.currentPosition)
        }
    }

    private fun detailsAreExpanded(host: Activity): Boolean {
        val sheet = host.findViewById<View>(R.id.fragment_player_holder) ?: return false
        return BottomSheetBehavior.from(sheet).state == BottomSheetBehavior.STATE_EXPANDED
    }

    private fun startOfflineQueue(initial: PlayQueue, type: PlayerService.PlayerType): Player {
        val ids = initial.ids()
        val selectedId = checkNotNull(initial.item).uuid
        launchMain(launcher = false)
        val fixtures = cacheMedia()
        onMain {
            ContextCompat.startForegroundService(context, NavigationHelper.getPlayerIntent(
                context, DeviceUtils.getPlayerServiceClass(), initial, false, false,
            ).putExtra(PlayerIntentConstants.PLAYER_TYPE, type.ordinal))
        }
        val player = awaitPlayer()
        await("Requested queue is attached") { player.playQueue?.ids() == ids }
        onMain {
            fixtures.forEach { (url, info) ->
                InfoCache.getInstance().putInfo(0, url, info, InfoType.STREAM)
            }
            if (!player.isPrepared || player.currentStreamInfo.isEmpty) {
                player.reloadPlayQueueManager()
            }
        }
        await("The offline fixture is ready") {
            player.isPrepared && player.currentStreamInfo.isPresent &&
                player.simpleExoPlayer.currentMediaItem?.mediaId == selectedId
        }
        return player
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
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Main activity resumed") {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it is MainActivity }
        }
    }

    private fun awaitPlayer(): Player {
        var active: Player? = null
        await("Player attached") {
            active = PlayerHolder.getInstance().player
            active?.playQueue != null
        }
        return checkNotNull(active)
    }

    private fun openFullQueue(player: Player): PlayQueueActivity {
        context.startActivity(NavigationHelper.getPlayQueueActivityIntent(context)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity: PlayQueueActivity? = null
        await("The full queue is attached to the playing service") {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayQueueActivity>()
                .firstOrNull { it.player === player }
            activity != null
        }
        return checkNotNull(activity)
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(action)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(message)
    }

    private fun cacheMedia(): Map<String, StreamInfo> {
        val asset = File(context.cacheDir, "durable-queue-fixture.mp4")
        instrumentation.context.assets.open("player-mode-fixture.mp4").use { source ->
            asset.outputStream().use { source.copyTo(it) }
        }
        return listOf(first, second).associateWith { url ->
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
            info
        }
    }
}
