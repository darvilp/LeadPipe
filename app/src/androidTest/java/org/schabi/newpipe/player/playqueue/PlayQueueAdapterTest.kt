package org.schabi.newpipe.player.playqueue

import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.FutureTask

@RunWith(AndroidJUnit4::class)
class PlayQueueAdapterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rapidRemovalAndUndoKeepRowsConsistentWithNotifications() {
        val changes = mutableListOf<String>()
        val (queue, adapter) = onMain {
            val queue = SinglePlayQueue((0..2).map {
                StreamInfoItem(0, "https://example.invalid/$it", "Row $it", StreamType.VIDEO_STREAM)
            }, 0)
            queue.init()
            val adapter = PlayQueueAdapter(instrumentation.targetContext, queue)
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    changes.add("remove $positionStart $itemCount")
                }

                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    changes.add("insert $positionStart $itemCount")
                }

                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                    changes.add("move $fromPosition $toPosition $itemCount")
                }
            })
            queue to adapter
        }
        try {
            instrumentation.waitForIdleSync()
            onMain {
                val removed = checkNotNull(queue.removeWithUndo(2))
                assertTrue(queue.undoRemoval(removed))
                checkNotNull(queue.removeWithUndo(1))

                // RecyclerView can read these rows before the deferred queue events are delivered.
                assertEquals(2, queue.size())
                assertEquals(emptyList<String>(), changes)
                assertEquals(3, adapter.itemCount)
                assertEquals(listOf("Row 0", "Row 1", "Row 2"), adapter.items.map { it.title })
                assertEquals(RecyclerView.NO_POSITION, adapter.queueIndexForRow(1))
                assertEquals(1, adapter.queueIndexForRow(2))
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(listOf("remove 1 1"), changes)
                assertEquals(2, adapter.itemCount)
                assertEquals(listOf("Row 0", "Row 2"), adapter.items.map { it.title })
                changes.clear()
                queue.move(0, 1)
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(listOf("move 0 1 1"), changes)
                assertEquals(listOf("Row 2", "Row 0"), adapter.items.map { it.title })
            }
        } finally {
            onMain {
                adapter.dispose()
                queue.dispose()
            }
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(action)
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
