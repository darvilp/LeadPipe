package org.schabi.newpipe.fragments.detail.tabs

import android.widget.CompoundButton
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockSegment
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.fragments.list.sponsorblock.SponsorBlockFragment
import org.schabi.newpipe.fragments.list.sponsorblock.SponsorBlockFragmentListener

@RunWith(AndroidJUnit4::class)
class DetailTabsLifecycleTest {
    @Test
    fun retainedSponsorBlockTabUsesTheCurrentHost() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
                val tabsKey = activity.getString(R.string.video_tabs_key)
                val enabledKey = activity.getString(R.string.sponsor_block_enable_key)
                val skippingKey = activity.getString(R.string.sponsor_block_skipping_enabled_key)
                val oldTabs = preferences.getStringSet(tabsKey, null)
                val oldEnabled = preferences.all[enabledKey] as Boolean?
                val oldSkipping = preferences.all[skippingKey] as Boolean?
                preferences.edit().putStringSet(tabsKey, setOf(DetailTab.SPONSOR_BLOCK.prefValue!!))
                    .putBoolean(enabledKey, true).putBoolean(skippingKey, true).apply()

                try {
                    val state = DetailTabsState()
                    val info = StreamInfo(ServiceList.YouTube.serviceId, "fixture",
                        "https://example.invalid/video", "Fixture")
                    val oldHost = TestHost(info)
                    val original = Fragment(R.layout.fragment_empty)
                    val manager = activity.supportFragmentManager
                    manager.beginTransaction().add(android.R.id.content, original).commitNow()
                    val oldPager = ViewPager2(activity)
                    val oldController = DetailTabsController(
                        original, oldPager, TabLayout(activity), null, state, oldHost)
                    val itemId = try {
                        oldController.showInfo(info)
                        state.tabs.single().itemId
                    } finally {
                        oldController.release()
                        manager.beginTransaction().remove(original).commitNow()
                    }
                    assertNull(original.context)

                    val current = Fragment(R.layout.fragment_empty)
                    manager.beginTransaction().add(android.R.id.content, current).commitNow()
                    val pager = ViewPager2(activity)
                    val currentHost = TestHost(info)
                    val controller = DetailTabsController(
                        current, pager, TabLayout(activity), null, state, currentHost)
                    try {
                        val adapter = pager.adapter as DetailTabsAdapter
                        val position = adapter.positionOf(DetailTab.SPONSOR_BLOCK)
                        assertTrue(position >= 0)
                        assertEquals(itemId, adapter.getItemId(position))
                        val editor = adapter.createFragment(position) as SponsorBlockFragment
                        assertEquals(0, oldHost.listenerRequests)
                        current.childFragmentManager.beginTransaction()
                            .add(editor, "sponsorblock-test").commitNow()
                        assertEquals(1, currentHost.listenerRequests)
                        assertTrue(editor.requireView()
                            .findViewById<CompoundButton>(R.id.skipping_is_enabled_switch).isChecked)
                    } finally {
                        controller.release()
                        manager.beginTransaction().remove(current).commitNow()
                    }
                } finally {
                    preferences.edit().putStringSet(tabsKey, oldTabs).apply {
                        if (oldEnabled == null) remove(enabledKey) else putBoolean(enabledKey, oldEnabled)
                        if (oldSkipping == null) remove(skippingKey) else putBoolean(skippingKey, oldSkipping)
                    }.apply()
                }
            }
        }
    }

    private class TestHost(override val currentInfo: StreamInfo) :
        DetailTabsController.Host, SponsorBlockFragmentListener {
        override val serviceId get() = currentInfo.serviceId
        override val isFullscreen = false
        var listenerRequests = 0
            private set

        override val sponsorBlockListener: SponsorBlockFragmentListener
            get() {
                listenerRequests++
                return this
            }

        override fun onSkippingEnabledChanged(newValue: Boolean) = Unit
        override fun onRequestNewPendingSegment(startTime: Int, endTime: Int) = Unit
        override fun onRequestClearPendingSegment() = Unit
        override fun onRequestSubmitPendingSegment(newSegment: SponsorBlockSegment) = Unit
        override fun onSeekToRequested(positionMillis: Long) = Unit
    }
}
