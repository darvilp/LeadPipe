package org.schabi.newpipe.info_list

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.PlayerIntentConstants
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.settings.SettingsActivity
import org.schabi.newpipe.util.OnClickGesture
import org.schabi.newpipe.util.SerializedCache
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class QuickEnqueueComposeTest {
    @Test
    fun experimentalRowButtonIsAccessibleAndEnqueuesWithoutOpeningDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val experimentalKey = context.getString(R.string.use_experimental_new_ui_key)
        val originalValue = prefs.all[experimentalKey] as? Boolean
        prefs.edit().putBoolean(experimentalKey, true).apply()
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                val intent = AtomicReference<Intent>()
                lateinit var adapter: InfoListAdapter
                val url = "https://example.invalid/quick-enqueue"
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val capture = object : ContextWrapper(activity) {
                        override fun startForegroundService(service: Intent): ComponentName? {
                            intent.set(service)
                            return service.component
                        }
                    }
                    adapter = InfoListAdapter(capture).apply {
                        setQuickEnqueueEnabled(true)
                        setOnStreamSelectedListener(object : OnClickGesture<StreamInfoItem>() {
                            override fun selected(item: StreamInfoItem) {
                                throw AssertionError("The enqueue button opened details")
                            }
                        })
                        addInfoItemList(listOf(
                            StreamInfoItem(0, url, "Test video", StreamType.VIDEO_STREAM)
                        ))
                    }
                    assertEquals(1, adapter.itemCount)
                    activity.setContentView(RecyclerView(capture).apply {
                        layoutManager = LinearLayoutManager(capture)
                        this.adapter = adapter
                    })
                }

                val automation = instrumentation.uiAutomation
                val description = context.getString(R.string.add_to_queue)
                val button = checkNotNull(awaitButton(description)) {
                    "No accessible queue button in ${automation.rootInActiveWindow?.packageName}"
                }
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                val serviceIntent = checkNotNull(intent.get())
                val key = checkNotNull(serviceIntent.getStringExtra(PlayerIntentConstants.PLAY_QUEUE_KEY))
                val queue = checkNotNull(SerializedCache.getInstance().take(key, PlayQueue::class.java))
                assertEquals(url, checkNotNull(queue.item).url)
                queue.dispose()

                scenario.onActivity {
                    adapter.setQuickEnqueueEnabled(false)
                    adapter.notifySelectionChanged()
                }
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (findButton(automation.rootInActiveWindow, description) != null &&
                    SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(100)
                }
                assertEquals(null, findButton(automation.rootInActiveWindow, description))
            }
        } finally {
            val editor = prefs.edit()
            if (originalValue == null) editor.remove(experimentalKey)
            else editor.putBoolean(experimentalKey, originalValue)
            editor.apply()
        }
    }

    private fun awaitButton(description: String): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            findButton(automation.rootInActiveWindow, description)?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun findButton(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable && node.contentDescription?.toString() == description) return node
        for (index in 0 until node.childCount) {
            findButton(node.getChild(index), description)?.let { return it }
        }
        return null
    }

}
