package org.schabi.newpipe.error

import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.grack.nanojson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R

@RunWith(AndroidJUnit4::class)
class ErrorReportExportTest {
    @Test
    fun exportsDeviceBuildAndSourceOrientationInBothFormats() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = context.createConfigurationContext(Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        })
        val info = ErrorInfo(
            arrayOf("Synthetic error for export verification"), UserAction.UI_ERROR,
            ErrorInfo.SERVICE_NONE, "Test request", R.string.general_error,
        )
        ErrorUtil.openActivity(source, info)

        var activity: ErrorActivity? = null
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (activity == null && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ErrorActivity>()
                    .firstOrNull()
            }
            if (activity == null) SystemClock.sleep(100)
        }
        val screen = checkNotNull(activity) { "Error report activity did not resume" }
        var markdown = ""
        var jsonText = ""
        instrumentation.runOnMainSync {
            markdown = export(screen, "buildMarkdown")
            jsonText = export(screen, "buildJson")
            screen.finish()
        }

        val versionCode = PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
        val json = JsonParser.`object`().from(jsonText)
        assertEquals(Build.MANUFACTURER, json.getString("device_manufacturer"))
        assertEquals(Build.MODEL, json.getString("device_model"))
        assertEquals(versionCode, json.getLong("version_code"))
        assertEquals(BuildConfig.BUILD_TYPE, json.getString("build_type"))
        assertEquals("landscape", json.getString("report_orientation"))
        for (line in listOf(
            "__Device:__ ${Build.MANUFACTURER} ${Build.MODEL}",
            "__Version code:__ $versionCode", "__Build type:__ ${BuildConfig.BUILD_TYPE}",
            "__Package:__ ${context.packageName}", "__Report orientation:__ landscape",
            "Synthetic error for export verification",
        )) assertTrue(markdown.contains(line))
    }

    private fun export(activity: ErrorActivity, method: String): String =
        ErrorActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }
            .invoke(activity) as String
}
