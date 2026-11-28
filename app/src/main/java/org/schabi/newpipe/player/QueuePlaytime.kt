package org.schabi.newpipe.player

import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.util.StreamTypeUtil.isLiveStream

internal class QueuePlaytime private constructor(
    val selected: PlayerMediaItem?,
    val totalSeconds: Long,
    val hasUnknownDurations: Boolean,
    private val secondsBeforeSelected: Long,
    private val hasUnknownDurationsBeforeSelected: Boolean,
    private val secondsAfterSelected: Long,
    private val hasUnknownDurationsAfterSelected: Boolean,
) {
    data class Estimate(val seconds: Long, val complete: Boolean)

    fun elapsed(positionMs: Long?): Estimate {
        val item = selected ?: return Estimate(0, true)
        // A live stream's position is relative to its moving DVR window.
        val positionSeconds = positionMs?.takeIf { it >= 0 && !isLiveStream(item.streamType) }
            ?.div(1000)
        val currentSeconds = positionSeconds?.let {
            if (item.duration > 0) it.coerceAtMost(item.duration) else it
        } ?: 0L
        return Estimate(
            secondsBeforeSelected + currentSeconds,
            !hasUnknownDurationsBeforeSelected && positionSeconds != null,
        )
    }

    fun remaining(positionMs: Long?): Estimate {
        val item = selected ?: return Estimate(0, true)
        val duration = knownDuration(item)
        val current = if (duration != null && positionMs != null && positionMs >= 0) {
            duration - (positionMs / 1000).coerceAtMost(duration)
        } else {
            null
        }
        return Estimate(
            secondsAfterSelected + (current ?: 0),
            !hasUnknownDurationsAfterSelected && current != null,
        )
    }

    companion object {
        private fun knownDuration(item: PlayerMediaItem): Long? =
            item.duration.takeIf { it > 0 && !isLiveStream(item.streamType) }

        fun calculate(items: List<PlayerMediaItem>, selectedIndex: Int): QueuePlaytime {
            var total = 0L
            var unknown = false
            var beforeSelected = 0L
            var unknownBeforeSelected = false
            var afterSelected = 0L
            var unknownAfterSelected = false
            items.forEachIndexed { index, item ->
                val duration = knownDuration(item)
                if (duration != null) {
                    total += duration
                    if (index < selectedIndex) beforeSelected += duration
                    if (index > selectedIndex) afterSelected += duration
                } else {
                    unknown = true
                    if (index < selectedIndex) unknownBeforeSelected = true
                    if (index > selectedIndex) unknownAfterSelected = true
                }
            }
            return QueuePlaytime(
                items.getOrNull(selectedIndex), total, unknown, beforeSelected, unknownBeforeSelected,
                afterSelected, unknownAfterSelected,
            )
        }
    }
}
