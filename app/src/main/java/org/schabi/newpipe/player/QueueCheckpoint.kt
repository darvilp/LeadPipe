package org.schabi.newpipe.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.player.playqueue.DurableQueueStore
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.QueueSnapshot
import org.schabi.newpipe.player.playqueue.events.PlayQueueEventType
import org.schabi.newpipe.util.StreamTypeUtil
import java.util.function.Consumer

/** Captures the active player session on the main thread and submits immutable checkpoints. */
internal class QueueCheckpoint(private val player: Player) {
    private val main = Handler(Looper.getMainLooper())
    private val capture = Runnable { save() }
    private var subscription: Disposable? = null
    private var queue: PlayQueue? = null
    private var clearing: PlayQueue? = null
    private var lastRequested: QueueSnapshot? = null
    private var lastCaptureAt = 0L

    fun attach(active: PlayQueue) {
        detach()
        queue = active
        subscription = active.broadcastReceiver?.subscribe { event ->
            if (event.type() != PlayQueueEventType.RECOVERY) request()
        }
        request()
    }

    fun detach() {
        main.removeCallbacks(capture)
        subscription?.dispose()
        subscription = null
        queue = null
        lastRequested = null
    }

    fun request() {
        if (queue == null || queue === clearing) return
        main.removeCallbacks(capture)
        main.post(capture)
    }

    fun tick() {
        if (SystemClock.elapsedRealtime() - lastCaptureAt >= 10_000) request()
    }

    fun save() {
        val active = queue ?: return
        if (active === clearing) return
        val selected = active.item
        val last = lastRequested?.takeIf { it.selectedId == selected?.uuid }
        val engine = player.simpleExoPlayer
        val position = when {
            selected == null || StreamTypeUtil.isLiveStream(selected.streamType) -> 0L
            engine != null && player.isPrepared && engine.currentMediaItem?.mediaId == selected.uuid ->
                engine.contentPosition.coerceAtLeast(0)
            active.getRecoveryPosition(selected) != PlayQueue.RECOVERY_UNSET ->
                active.getRecoveryPosition(selected).coerceAtLeast(0)
            else -> last?.positionMs ?: 0
        }
        val snapshot = active.snapshot(position, player.repeatMode.ordinal)
        lastCaptureAt = SystemClock.elapsedRealtime()
        if (snapshot == lastRequested) return
        lastRequested = snapshot
        DurableQueueStore.write(player.context.applicationContext, snapshot) { saved ->
            if (!saved && lastRequested === snapshot) lastRequested = null
        }
    }

    fun clear(result: Consumer<Boolean>) {
        val target = queue
        if (target == null || target === clearing) {
            result.accept(false)
            return
        }
        DurableQueueStore.cancelLauncherRestore()
        clearing = target
        main.removeCallbacks(capture)
        DurableQueueStore.write(player.context.applicationContext, QueueSnapshot.EMPTY) { saved ->
            clearing = null
            if (queue === target) {
                if (saved) detach() else request()
            }
            result.accept(saved && player.playQueue === target && !target.isDisposed)
        }
    }
}
