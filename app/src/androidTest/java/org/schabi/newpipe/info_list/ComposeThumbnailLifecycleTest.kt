package org.schabi.newpipe.info_list

import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.settings.SettingsActivity
import org.schabi.newpipe.util.PicassoHelper
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class ComposeThumbnailLifecycleTest {
    @Test
    fun pendingThumbnailTargetLivesUntilItsRowChanges() {
        val executor = Executors.newSingleThreadExecutor()
        // Keep real Picasso requests pending so cache or network timing cannot complete them.
        val releaseWorker = CountDownLatch(1)
        executor.submit { releaseWorker.await() }
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                val imageUrl = mutableStateOf<String?>("file:///first-thumbnail.png")
                val instance = PicassoHelper::class.java.getDeclaredField("picassoInstance")
                    .apply { isAccessible = true }
                val targetMap = Picasso::class.java.getDeclaredField("targetToAction")
                    .apply { isAccessible = true }
                lateinit var original: Picasso
                lateinit var picasso: Picasso
                lateinit var view: ComposeView
                lateinit var targets: Map<*, *>
                var originalShouldLoadImages = false
                scenario.onActivity { activity ->
                    original = instance.get(null) as Picasso
                    originalShouldLoadImages = PicassoHelper.getShouldLoadImages()
                    picasso = Picasso.Builder(activity.applicationContext).executor(executor).build()
                    instance.set(null, picasso)
                    PicassoHelper.setShouldLoadImages(true)
                    targets = targetMap.get(picasso) as Map<*, *>
                    view = ComposeView(activity).apply {
                        setContent {
                            imageUrl.value?.let { url ->
                                CommonItem(
                                    state = ComposeItemState(
                                        title = "Video",
                                        subtitle = null,
                                        details = null,
                                        imageUrl = url,
                                        durationText = null,
                                        showLiveBadge = false,
                                        showPaidBadge = false,
                                        progress = null,
                                        playlistCount = null,
                                        isChannel = false
                                    ),
                                    isGridLayout = false,
                                    isCardLayout = false,
                                    showDragHandle = false,
                                    onClick = {}
                                )
                            }
                        }
                    }
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(
                        view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                try {
                    val first = awaitTarget(scenario, targets)
                    collectWeakReferences()
                    val firstTarget = first.get()
                    assertNotNull("The visible row lost its pending Picasso target", firstTarget)

                    scenario.onActivity { imageUrl.value = "file:///second-thumbnail.png" }
                    val secondTarget = awaitTarget(scenario, targets, firstTarget).get()
                    assertNotNull("The rebound row lost its pending Picasso target", secondTarget)
                    scenario.onActivity {
                        assertFalse("Rebinding did not cancel the first target", firstTarget in targets)
                        view.disposeComposition()
                        assertFalse("Disposal did not cancel the second target", secondTarget in targets)
                    }
                } finally {
                    scenario.onActivity {
                        view.disposeComposition()
                        (view.parent as ViewGroup).removeView(view)
                        instance.set(null, original)
                        PicassoHelper.setShouldLoadImages(originalShouldLoadImages)
                        picasso.shutdown()
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            releaseWorker.countDown()
        }
    }

    private fun awaitTarget(
        scenario: ActivityScenario<SettingsActivity>,
        targets: Map<*, *>,
        previous: Target? = null
    ): WeakReference<Target> {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var result: WeakReference<Target>? = null
            scenario.onActivity {
                targets.keys.filterIsInstance<Target>().firstOrNull { it !== previous }?.let {
                    result = WeakReference(it)
                }
            }
            result?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("The Compose row did not register a Picasso target")
    }

    private fun collectWeakReferences() {
        val marker = WeakReference(Any())
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            Runtime.getRuntime().gc()
            if (marker.get() == null) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        error("The runtime did not collect an unowned weak reference")
    }
}
