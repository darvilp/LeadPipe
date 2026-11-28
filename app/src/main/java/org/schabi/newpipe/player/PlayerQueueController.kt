package org.schabi.newpipe.player

import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import org.schabi.newpipe.QueueItemMenuUtil
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.fragments.OnScrollBelowItemsListener
import org.schabi.newpipe.info_list.StreamSegmentAdapter
import org.schabi.newpipe.info_list.StreamSegmentItem
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueAdapter
import org.schabi.newpipe.player.playqueue.PlayQueueItemBuilder
import org.schabi.newpipe.player.playqueue.PlayQueueItemHolder
import org.schabi.newpipe.player.playqueue.PlayQueueItemTouchCallback
import org.schabi.newpipe.util.StreamTypeUtil

/**
 * Owns the play-queue and stream-segments panels of a [Player].
 *
 * The [PlayQueueAdapter] and [StreamSegmentAdapter] used by the sliding lists, the
 * [ItemTouchHelper] that drives drag-and-drop, whether the queue or the segments panel is
 * currently open, and the item callbacks (select, hold, swipe, drag) all live here. [Player] only
 * keeps delegate methods with the same names, so [PlayerProgressController] and
 * [PlayQueueActivity] are unaffected.
 */
class PlayerQueueController(private val player: Player) {

    private var queueAdapter: PlayQueueAdapter? = null
    private var segmentsAdapter: StreamSegmentAdapter? = null
    private var itemTouchHelper: ItemTouchHelper? = null
    private var removalSnackbar: Snackbar? = null

    private var queueVisible = false
    private var segmentsVisible = false
    internal var showRemainingQueueTime = false
        private set

    internal fun toggleQueueTime() {
        showRemainingQueueTime = !showRemainingQueueTime
        if (queueVisible) updateQueueTime()
    }

    fun initAdapters() {
        val playQueue = player.playQueue ?: return
        queueAdapter?.dispose()
        queueAdapter = PlayQueueAdapter(player.context, playQueue)
        segmentsAdapter = StreamSegmentAdapter(getStreamSegmentListener())
        if (queueVisible) {
            updateQueueTime()
        }
    }

    fun disposeAdapters() {
        dismissRemovalUndo()
        player.binding?.itemsListHeaderDuration?.clearQueue()
        queueAdapter?.let {
            it.unsetSelectedListener()
            it.dispose()
        }
    }

    fun removeWithUndo(queue: PlayQueue, index: Int, anchor: View) {
        if (queue !== player.playQueue || !anchor.isAttachedToWindow) return
        val item = queue.getItem(index) ?: return
        val snackbar = Snackbar.make(anchor, R.string.play_queue_item_removed, Snackbar.LENGTH_LONG)
        if (index == queue.index && !StreamTypeUtil.isLiveStream(item.streamType)) {
            player.setRecovery()
        }
        val removal = queue.removeWithUndo(index) ?: return

        dismissRemovalUndo()
        removalSnackbar = snackbar
        snackbar.setAction(R.string.undo) {
            if (queue === player.playQueue) queue.undoRemoval(removal)
        }.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(bar: Snackbar, event: Int) {
                if (removalSnackbar === bar) removalSnackbar = null
                if (queue.discardRemovalUndo(removal) && queue === player.playQueue &&
                    queue.isEmpty && queue.isComplete && !queue.isDisposed
                ) {
                    player.onPlaybackShutdown()
                }
            }
        }).show()
    }

    fun dismissRemovalUndo() {
        removalSnackbar?.dismiss()
        removalSnackbar = null
    }

    fun openQueueItemMenu(
        item: PlayerMediaItem,
        view: View,
        hideDetails: Boolean,
        fragmentManager: FragmentManager?,
        undoHost: View,
    ) {
        val queue = player.playQueue ?: return
        if (queue.indexOf(item) < 0) return
        val moveNext = if (queue.canMoveToPlayNext(item)) Runnable {
            if (player.playQueue === queue) queue.moveToPlayNext(item)
        } else null
        QueueItemMenuUtil.openPopupMenu(
            item, view, hideDetails, fragmentManager, view.context,
            { removeWithUndo(queue, queue.indexOf(item), undoHost) }, moveNext
        )
    }

    fun getPlayQueueAdapter(): PlayQueueAdapter? = queueAdapter

    fun getSegmentAdapter(): StreamSegmentAdapter = segmentsAdapter!!

    fun isQueueVisible(): Boolean = queueVisible

    fun areSegmentsVisible(): Boolean = segmentsVisible

    /**
     * Refreshes the segments list of the currently open segments panel, closing it when the new
     * stream has no segments at all.
     */
    fun onMetadataChanged(info: StreamInfo) {
        if (!segmentsVisible) {
            return
        }
        val adapter = segmentsAdapter ?: return
        if (adapter.setItems(info)) {
            val adapterPosition = getNearestStreamSegmentPosition(
                player.simpleExoPlayer.currentPosition
            )
            adapter.selectSegmentAt(adapterPosition)
            player.binding.itemsList.scrollToPosition(adapterPosition)
        } else {
            closeItemsList()
        }
    }

    fun onQueueClicked() {
        queueVisible = true

        player.hideSystemUIIfNeeded()
        buildQueue()

        player.binding.itemsListHeaderTitle.visibility = View.GONE
        player.binding.itemsListHeaderDuration.visibility = View.VISIBLE
        player.binding.shuffleButton.visibility = View.VISIBLE
        player.binding.repeatButton.visibility = View.VISIBLE
        player.binding.addToPlaylistButton.visibility = View.VISIBLE

        player.hideControls(0L, 0L)
        player.binding.itemsListPanel.requestFocus()
        player.binding.itemsListPanel.animate(
            true, Player.DEFAULT_CONTROLS_DURATION.toLong(), AnimationType.SLIDE_AND_ALPHA
        )

        player.playQueue?.let { player.binding.itemsList.scrollToPosition(it.index) }

        updateQueueTime()
    }

    private fun buildQueue() {
        val adapter = queueAdapter ?: return
        player.binding.itemsList.adapter = adapter
        player.binding.itemsList.isClickable = true
        player.binding.itemsList.isLongClickable = true

        player.binding.itemsList.clearOnScrollListeners()
        player.binding.itemsList.addOnScrollListener(getQueueScrollListener())

        itemTouchHelper = ItemTouchHelper(getItemTouchCallback())
        itemTouchHelper?.attachToRecyclerView(player.binding.itemsList)

        adapter.setSelectedListener(getOnSelectedListener())

        player.binding.itemsListClose.setOnClickListener { closeItemsList() }
    }

    fun onSegmentsClicked() {
        segmentsVisible = true

        player.hideSystemUIIfNeeded()
        buildSegments()

        player.binding.itemsListHeaderTitle.visibility = View.VISIBLE
        player.binding.itemsListHeaderDuration.visibility = View.GONE
        player.binding.itemsListHeaderDuration.clearQueue()
        player.binding.shuffleButton.visibility = View.GONE
        player.binding.repeatButton.visibility = View.GONE
        player.binding.addToPlaylistButton.visibility = View.GONE

        player.hideControls(0L, 0L)
        player.binding.itemsListPanel.requestFocus()
        player.binding.itemsListPanel.animate(
            true, Player.DEFAULT_CONTROLS_DURATION.toLong(), AnimationType.SLIDE_AND_ALPHA
        )

        val adapterPosition = getNearestStreamSegmentPosition(
            player.simpleExoPlayer.currentPosition
        )
        segmentsAdapter?.selectSegmentAt(adapterPosition)
        player.binding.itemsList.scrollToPosition(adapterPosition)
    }

    private fun buildSegments() {
        val adapter = segmentsAdapter ?: return
        player.binding.itemsList.adapter = adapter
        player.binding.itemsList.isClickable = true
        player.binding.itemsList.isLongClickable = false

        player.binding.itemsList.clearOnScrollListeners()
        itemTouchHelper?.attachToRecyclerView(null)

        player.currentStreamInfo.ifPresent { adapter.setItems(it) }

        player.binding.shuffleButton.visibility = View.GONE
        player.binding.repeatButton.visibility = View.GONE
        player.binding.addToPlaylistButton.visibility = View.GONE
        player.binding.itemsListClose.setOnClickListener { closeItemsList() }
    }

    fun closeItemsList() {
        if (queueVisible || segmentsVisible) {
            queueVisible = false
            segmentsVisible = false
            player.binding.itemsListHeaderDuration.clearQueue()

            itemTouchHelper?.attachToRecyclerView(null)

            player.binding.itemsListPanel.animate(
                false, Player.DEFAULT_CONTROLS_DURATION.toLong(),
                AnimationType.SLIDE_AND_ALPHA, 0L
            ) {
                // Even when queueLayout is GONE it receives touch events
                // and ruins normal behavior of the app. This line fixes it
                player.binding.itemsListPanel.translationY =
                    -player.binding.itemsListPanel.height * 5f
            }

            // clear focus, otherwise a white rectangle remains on top of the player
            player.binding.itemsListClose.clearFocus()
            player.binding.playPauseButton.requestFocus()
        }
    }

    fun updateQueueTime() {
        player.binding.itemsListHeaderDuration.update(player)
    }

    fun getNearestStreamSegmentPosition(playbackPosition: Long): Int {
        var nearestPosition = 0
        val segments = player.currentStreamInfo
            .map { it.streamSegments }
            .orElse(emptyList())

        for (i in segments.indices) {
            if (segments[i].startTimeSeconds * 1000L > playbackPosition) {
                break
            }
            nearestPosition++
        }
        return maxOf(0, nearestPosition - 1)
    }

    private fun getQueueScrollListener() = object : OnScrollBelowItemsListener() {
        override fun onScrolledDown(recyclerView: RecyclerView) {
            val playQueue = player.playQueue
            if (playQueue != null && !playQueue.isComplete) {
                playQueue.fetch()
            } else {
                player.binding?.itemsList?.clearOnScrollListeners()
            }
        }
    }

    private fun getStreamSegmentListener(): StreamSegmentAdapter.StreamSegmentListener =
        object : StreamSegmentAdapter.StreamSegmentListener {
            override fun onItemClick(item: StreamSegmentItem, seconds: Int) {
                segmentsAdapter?.selectSegment(item)
                player.seekTo(seconds * 1000L)
                player.triggerProgressUpdate()
            }
        }

    private fun getItemTouchCallback() = object : PlayQueueItemTouchCallback() {
        override fun onMove(sourceIndex: Int, targetIndex: Int) {
            player.playQueue?.move(sourceIndex, targetIndex)
        }

        override fun onSwiped(index: Int) {
            player.playQueue?.let { queue ->
                removeWithUndo(queue, index, player.binding.queueUndoHost)
            }
        }
    }

    private fun getOnSelectedListener() = object : PlayQueueItemBuilder.OnSelectedListener {
        override fun selected(item: PlayerMediaItem, view: View) {
            player.selectQueueItem(item)
        }

        override fun held(item: PlayerMediaItem, view: View) {
            openQueueItemMenu(
                item, view, true, player.parentActivity?.supportFragmentManager,
                player.binding.queueUndoHost
            )
        }

        override fun onStartDrag(viewHolder: PlayQueueItemHolder) {
            itemTouchHelper?.startDrag(viewHolder)
        }
    }
}
