package org.schabi.newpipe.player;

import static org.junit.Assert.assertNotNull;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.ActivityPlayerQueueControlBinding;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class PlayQueueLayoutTest {
    @Test
    public void playtimeViewIsPresentInBothOrientations() {
        assertNotNull("Portrait queue playtime view",
                inflatePlaytime(Configuration.ORIENTATION_PORTRAIT));
        assertNotNull("Landscape queue playtime view",
                inflatePlaytime(Configuration.ORIENTATION_LANDSCAPE));
    }

    private QueuePlaytimeView inflatePlaytime(final int orientation) {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context target = instrumentation.getTargetContext();
        final Configuration config = new Configuration(target.getResources().getConfiguration());
        config.orientation = orientation;
        final Context themed = new ContextThemeWrapper(target.createConfigurationContext(config),
                R.style.DarkTheme);
        final AtomicReference<QueuePlaytimeView> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(ActivityPlayerQueueControlBinding
                .inflate(LayoutInflater.from(themed)).queuePlaytime));
        return result.get();
    }
}
