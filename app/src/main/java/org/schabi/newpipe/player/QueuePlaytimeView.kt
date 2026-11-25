package org.schabi.newpipe.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player as PlaybackPlayer
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.R
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.events.PlayQueueEvent
import org.schabi.newpipe.player.playqueue.events.PlayQueueEventType
import org.schabi.newpipe.util.Localization

class QueuePlaytimeView(context: Context, attrs: AttributeSet?) : AppCompatTextView(context, attrs) {
    private var queue: PlayQueue? = null
    private var queueBroadcast: Flowable<PlayQueueEvent>? = null
    private var queueEvents: Disposable? = null
    private var exoPlayer: ExoPlayer? = null
    private var playtime: QueuePlaytime? = null
    private var lastElapsed: QueuePlaytime.Elapsed? = null
    private var totalLabel = ""

    private val playbackListener = object : PlaybackPlayer.Listener {
        override fun onEvents(player: PlaybackPlayer, events: PlaybackPlayer.Events) {
            if (events.containsAny(
                    PlaybackPlayer.EVENT_POSITION_DISCONTINUITY,
                    PlaybackPlayer.EVENT_MEDIA_ITEM_TRANSITION,
                    PlaybackPlayer.EVENT_TIMELINE_CHANGED,
                )
            ) {
                render()
            }
        }
    }

    fun update(player: Player?) {
        val currentQueue = player?.playQueue
        val broadcast = currentQueue?.broadcastReceiver
        if (currentQueue !== queue || broadcast !== queueBroadcast) {
            clearQueue()
            queue = currentQueue
            queueBroadcast = broadcast
            if (currentQueue != null) {
                refreshQueue(currentQueue)
                queueEvents = broadcast
                    ?.filter {
                        it.type() != PlayQueueEventType.RECOVERY &&
                            it.type() != PlayQueueEventType.INIT
                    }
                    ?.subscribe({
                        refreshQueue(currentQueue)
                        render()
                    }, { error -> Log.e("QueuePlaytimeView", "Couldn't observe play queue", error) })
            }
        }
        val currentPlayer = player?.simpleExoPlayer
        if (currentPlayer !== exoPlayer) {
            exoPlayer?.removeListener(playbackListener)
            exoPlayer = currentPlayer
            exoPlayer?.addListener(playbackListener)
        }
        render()
    }

    fun clearQueue() {
        queueEvents?.dispose()
        queueEvents = null
        queue = null
        queueBroadcast = null
        exoPlayer?.removeListener(playbackListener)
        exoPlayer = null
        playtime = null
        lastElapsed = null
        text = null
    }

    private fun refreshQueue(queue: PlayQueue) {
        val snapshot = synchronized(queue) {
            QueuePlaytime.calculate(queue.streams, queue.index)
        }
        playtime = snapshot
        lastElapsed = null
        totalLabel = if (snapshot.hasUnknownDurations && snapshot.totalSeconds == 0L) {
            context.getString(R.string.play_queue_total_unknown)
        } else {
            val label = if (queue.isFullyLoaded) {
                R.string.play_queue_total_time
            } else {
                R.string.play_queue_loaded_time
            }
            context.getString(
                label, formatDuration(snapshot.totalSeconds, !snapshot.hasUnknownDurations)
            )
        }
    }

    private fun render() {
        val snapshot = playtime ?: return
        val selectedId = snapshot.selected?.uuid
        // A queue selection can change before the player's current media item does.
        val positionMs = exoPlayer?.takeIf {
            selectedId != null && it.currentMediaItem?.mediaId == selectedId
        }?.currentPosition
        val elapsed = snapshot.elapsed(positionMs)
        if (elapsed != lastElapsed) {
            val elapsedLabel = context.getString(
                R.string.play_queue_elapsed_time, formatDuration(elapsed.seconds, elapsed.complete)
            )
            text = context.getString(R.string.play_queue_time_summary, elapsedLabel, totalLabel)
            lastElapsed = elapsed
        }
    }

    private fun formatDuration(seconds: Long, complete: Boolean): String =
        Localization.getDurationString(seconds, complete, false)
}
