package org.schabi.newpipe.player

import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.util.StreamTypeUtil.isLiveStream

internal class QueuePlaytime private constructor(
    val selected: PlayerMediaItem?,
    val totalSeconds: Long,
    val hasUnknownDurations: Boolean,
    private val secondsBeforeSelected: Long,
    private val hasUnknownDurationsBeforeSelected: Boolean,
) {
    data class Elapsed(val seconds: Long, val complete: Boolean)

    fun elapsed(positionMs: Long?): Elapsed {
        val item = selected ?: return Elapsed(0, true)
        // A live stream's position is relative to its moving DVR window.
        val positionSeconds = positionMs?.takeIf { it >= 0 && !isLiveStream(item.streamType) }
            ?.div(1000)
        val currentSeconds = positionSeconds?.let {
            if (item.duration > 0) it.coerceAtMost(item.duration) else it
        } ?: 0L
        return Elapsed(
            secondsBeforeSelected + currentSeconds,
            !hasUnknownDurationsBeforeSelected && positionSeconds != null,
        )
    }

    companion object {
        fun calculate(items: List<PlayerMediaItem>, selectedIndex: Int): QueuePlaytime {
            var total = 0L
            var unknown = false
            var beforeSelected = 0L
            var unknownBeforeSelected = false
            items.forEachIndexed { index, item ->
                if (item.duration > 0 && !isLiveStream(item.streamType)) {
                    total += item.duration
                    if (index < selectedIndex) beforeSelected += item.duration
                } else {
                    unknown = true
                    if (index < selectedIndex) unknownBeforeSelected = true
                }
            }
            return QueuePlaytime(
                items.getOrNull(selectedIndex), total, unknown, beforeSelected, unknownBeforeSelected
            )
        }
    }
}
