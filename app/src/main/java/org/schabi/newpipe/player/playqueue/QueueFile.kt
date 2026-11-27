package org.schabi.newpipe.player.playqueue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import org.schabi.newpipe.player.PlayerIntentConstants
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.NavigationHelper
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors

internal class QueueFile(path: File) {
    private val file = AtomicFile(path)

    fun read(): QueueSnapshot? = try {
        QueueSnapshot.decode(file.readFully().toString(Charsets.UTF_8))
    } catch (_: FileNotFoundException) {
        null
    } catch (error: IOException) {
        Log.w("DurableQueue", "Could not read saved queue", error)
        null
    }

    fun write(snapshot: QueueSnapshot): Boolean {
        return try {
            val contents = snapshot.encode().toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            try {
                output.write(contents)
                file.finishWrite(output)
            } catch (error: IOException) {
                file.failWrite(output)
                throw error
            }
            // AtomicFile can log a failed rename without throwing. For a clear, confirm that
            // the previous queue is gone before telling the player to close.
            snapshot.selectedId != null || read() == QueueSnapshot.EMPTY
        } catch (error: IOException) {
            Log.w("DurableQueue", "Could not save queue", error)
            false
        }
    }
}

/** All reads, writes and clears of the app's active queue run in submission order. */
internal object DurableQueueStore {
    private val disk = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var launcherGeneration = 0L

    private fun file(context: Context) =
        QueueFile(File(context.noBackupFilesDir, "active-play-queue.json"))

    fun read(context: Context, result: (QueueSnapshot?) -> Unit) {
        disk.execute {
            val snapshot = file(context).read()
            main.post { result(snapshot) }
        }
    }

    fun write(context: Context, snapshot: QueueSnapshot, result: (Boolean) -> Unit) {
        disk.execute {
            val saved = file(context).write(snapshot)
            main.post { result(saved) }
        }
    }

    @JvmStatic
    fun cancelLauncherRestore() {
        launcherGeneration++
    }

    fun isLauncherRestoreCurrent(generation: Long): Boolean = generation == launcherGeneration

    @JvmStatic
    fun restoreFromLauncher(activity: AppCompatActivity) {
        if (PlayerHolder.getInstance().isPlayerOpen) return
        val generation = ++launcherGeneration
        val context = activity.applicationContext
        disk.execute {
            val snapshot = file(context).read() ?: return@execute
            val queue = PlayQueue.restore(snapshot) ?: return@execute
            val intent = NavigationHelper.getPlayerIntent(
                context, DeviceUtils.getPlayerServiceClass(), queue, false, false
            ).putExtra(PlayerIntentConstants.RESTORE_DURABLE_QUEUE, true)
                .putExtra(PlayerIntentConstants.QUEUE_RESTORE_GENERATION, generation)
                .putExtra(PlayerIntentConstants.REPEAT_MODE, snapshot.repeatMode)
            main.post {
                if (isLauncherRestoreCurrent(generation) &&
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !activity.isFinishing && !PlayerHolder.getInstance().isPlayerOpen
                ) {
                    try {
                        ContextCompat.startForegroundService(activity, intent)
                    } catch (error: IllegalStateException) {
                        Log.w("DurableQueue", "Could not start restored queue", error)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun afterPendingWrites(action: Runnable) {
        disk.execute { main.post(action) }
    }
}
