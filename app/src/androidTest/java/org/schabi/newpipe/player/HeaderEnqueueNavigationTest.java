package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.info_list.InfoListAdapter;
import org.schabi.newpipe.info_list.ItemViewMode;
import org.schabi.newpipe.util.ThemeHelper;

import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.info_list.QuickEnqueue;
import org.schabi.newpipe.player.PlayerService.PlayerType;
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.SerializedCache;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class HeaderEnqueueNavigationTest {
    @Test
    public void upstreamEnqueueEntryPointsPreserveEveryActivePlayerMode() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (final PlayerType activeType : PlayerType.values()) {
                checkEnqueue(context, activeType, false);
                checkEnqueue(context, activeType, true);
            }
        });
    }

    private static Player createPlayer(final Context context) {
        final Service service = new LocalService(context);
        final PlayerServiceInterface serviceInterface = (PlayerServiceInterface)
                Proxy.newProxyInstance(PlayerServiceInterface.class.getClassLoader(),
                        new Class<?>[]{PlayerServiceInterface.class}, (proxy, method, args) -> {
                            if ("getInstance".equals(method.getName())) {
                                return service;
                            }
                            if ("isLandscape".equals(method.getName())) {
                                return false;
                            }
                            throw new AssertionError("Unexpected service call: " + method);
                        });
        final Player player = new Player(serviceInterface);
        prepareView(player, context);
        return player;
    }

    private static void checkEnqueue(final Context context, final PlayerType activeType,
                                     final boolean next) {
        final Player player = createPlayer(context);
        final PlayQueue queue = new SinglePlayQueue(
                Arrays.asList(item("first"), item("second"), item("third")), 0);
        queue.init();
        try {
            player.setPlayQueue(queue);
            player.setPlayerType(activeType);
            player.setAudioOnly(activeType == PlayerType.AUDIO);
            final CapturingContext capture = new CapturingContext(context);
            final PlayerHolder holder = PlayerHolder.getInstance();
            final Player previousPlayer = setHolderPlayer(holder, player);
            final Intent intent;
            try {
                if (next) {
                    NavigationHelper.enqueueNextOnPlayer(capture,
                            new SinglePlayQueue(item("queued")));
                } else {
                    NavigationHelper.enqueueOnPlayer(capture,
                            new SinglePlayQueue(item("queued")));
                }
                intent = capture.intent;
                assertNotNull(intent);
                assertEquals(activeType.ordinal(), intent.getIntExtra(
                        PlayerIntentConstants.PLAYER_TYPE, -1));
            } finally {
                setHolderPlayer(holder, previousPlayer);
            }

            new PlayerStartController(player).handleIntent(intent);

            assertSame(queue, player.getPlayQueue());
            assertEquals(activeType, player.getPlayerType());
            assertEquals(activeType == PlayerType.AUDIO, player.isAudioOnly());
            assertEquals(0, queue.getIndex());
            assertEquals(4, queue.size());
            assertEquals("https://example.invalid/queued",
                    queue.getItem(next ? 1 : 3).getUrl());
        } finally {
            try {
                player.destroy();
            } finally {
                queue.dispose();
                player.getTrackSelector().release();
            }
        }
    }

    @Test
    public void missingSessionRequestsBackgroundPlayback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            final PlayerHolder holder = PlayerHolder.getInstance();
            final Player previousPlayer = setHolderPlayer(holder, null);
            try {
                for (final boolean next : new boolean[]{false, true}) {
                    final CapturingContext capture = new CapturingContext(context);
                    if (next) {
                        NavigationHelper.enqueueNextOnPlayer(capture,
                                new SinglePlayQueue(item("queued")));
                    } else {
                        NavigationHelper.enqueueOnPlayer(capture,
                                new SinglePlayQueue(item("queued")));
                    }
                    assertNotNull(capture.intent);
                    assertEquals(PlayerType.AUDIO.ordinal(), capture.intent.getIntExtra(
                            PlayerIntentConstants.PLAYER_TYPE, -1));
                    final String cacheKey = capture.intent.getStringExtra(
                            PlayerIntentConstants.PLAY_QUEUE_KEY);
                    final PlayQueue queued = SerializedCache.getInstance().take(
                            cacheKey, PlayQueue.class);
                    assertNotNull(queued);
                    queued.dispose();
                }
            } finally {
                setHolderPlayer(holder, previousPlayer);
            }
        });
    }

    @Test
    public void quickEnqueueOnRecycledRowStartsInBackgroundAndHidesDuringSelection() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            final CapturingContext capture = new CapturingContext(context);
            final Context themed = new ContextThemeWrapper(capture,
                    ThemeHelper.getThemeForService(context, -1));
            final var prefs = PreferenceManager.getDefaultSharedPreferences(context);
            final String experimentalKey = context.getString(R.string.use_experimental_new_ui_key);
            final boolean hadPreference = prefs.contains(experimentalKey);
            final boolean wasExperimental = prefs.getBoolean(experimentalKey, false);
            prefs.edit().putBoolean(experimentalKey, false).apply();
            final PlayerHolder holder = PlayerHolder.getInstance();
            final Player previousPlayer = setHolderPlayer(holder, null);
            try {
                final InfoListAdapter adapter = new InfoListAdapter(themed);
                adapter.setQuickEnqueueEnabled(true);
                adapter.setOnStreamSelectedListener(new OnClickGesture<>() {
                    @Override
                    public void selected(final StreamInfoItem stream) {
                        throw new AssertionError("The quick action opened the video");
                    }
                });
                adapter.setInfoItemList(List.of(item("first"), item("recycled")));
                for (final ItemViewMode mode : ItemViewMode.values()) {
                    adapter.setItemViewMode(mode);
                    final RecyclerView.ViewHolder row = adapter.onCreateViewHolder(
                            new FrameLayout(themed), adapter.getItemViewType(0));
                    adapter.onBindViewHolder(row, 0);
                    adapter.onBindViewHolder(row, 1);
                    final View button = row.itemView.findViewById(R.id.itemQuickEnqueue);
                    assertEquals(View.VISIBLE, button.getVisibility());
                    assertTrue(button.isFocusable());
                    assertEquals(context.getString(R.string.add_to_queue),
                            button.getContentDescription());
                    assertTrue(button.performClick());
                    assertEquals(PlayerType.AUDIO.ordinal(), capture.intent.getIntExtra(
                            PlayerIntentConstants.PLAYER_TYPE, -1));
                    final PlayQueue queue = SerializedCache.getInstance().take(
                            capture.intent.getStringExtra(PlayerIntentConstants.PLAY_QUEUE_KEY),
                            PlayQueue.class);
                    assertEquals("https://example.invalid/recycled", queue.getItem().getUrl());
                    queue.dispose();

                    assertTrue(button.performLongClick());
                    assertTrue(capture.intent.getBooleanExtra(PlayerIntentConstants.ENQUEUE_NEXT,
                            false));
                    assertEquals(PlayerType.AUDIO.ordinal(), capture.intent.getIntExtra(
                            PlayerIntentConstants.PLAYER_TYPE, -1));
                    final PlayQueue next = SerializedCache.getInstance().take(
                            capture.intent.getStringExtra(PlayerIntentConstants.PLAY_QUEUE_KEY),
                            PlayQueue.class);
                    assertEquals("https://example.invalid/recycled", next.getItem().getUrl());
                    next.dispose();

                    adapter.setQuickEnqueueEnabled(false);
                    adapter.onBindViewHolder(row, 1, List.of(InfoListAdapter.SELECTION_PAYLOAD));
                    assertEquals(View.GONE, button.getVisibility());
                    assertFalse(button.performClick());
                    assertFalse(button.performLongClick());
                    adapter.setQuickEnqueueEnabled(true);
                    adapter.onBindViewHolder(row, 1, List.of(InfoListAdapter.SELECTION_PAYLOAD));
                    assertEquals(View.VISIBLE, button.getVisibility());
                }
            } finally {
                setHolderPlayer(holder, previousPlayer);
                final var editor = prefs.edit();
                if (hadPreference) {
                    editor.putBoolean(experimentalKey, wasExperimental);
                } else {
                    editor.remove(experimentalKey);
                }
                editor.apply();
            }
        });
    }

    @Test
    public void quickEnqueueAppendsInTapOrderAndLongPressInsertsAfterCurrent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            final CapturingContext capture = new CapturingContext(context);
            final Context themed = new ContextThemeWrapper(capture,
                    ThemeHelper.getThemeForService(context, -1));
            final Player player = createPlayer(context);
            final PlayQueue active = new SinglePlayQueue(List.of(item("playing"), item("next")), 0);
            active.init();
            final PlayerHolder holder = PlayerHolder.getInstance();
            final Player previousPlayer = setHolderPlayer(holder, player);
            try {
                player.setPlayQueue(active);
                player.setPlayerType(PlayerType.POPUP);
                final var startController = new PlayerStartController(player);
                final View row = LayoutInflater.from(themed).inflate(R.layout.list_stream_item,
                        new FrameLayout(themed), false);
                final View button = row.findViewById(R.id.itemQuickEnqueue);
                for (final String name : List.of("a", "sparse-b", "c")) {
                    final StreamInfoItem stream = item(name);
                    stream.setDuration(-1);
                    QuickEnqueue.bind(row, QuickEnqueue.actionFor(row, stream));
                    assertTrue(button.performClick());
                    startController.handleIntent(capture.intent);
                }
                assertSame(active, player.getPlayQueue());
                assertEquals(PlayerType.POPUP, player.getPlayerType());
                assertEquals(0, active.getIndex());
                assertEquals(List.of("playing", "next", "a", "sparse-b", "c"),
                        active.getStreams().stream().map(item -> item.getTitle()).toList());

                QuickEnqueue.bind(row, QuickEnqueue.actionFor(row, item("priority")));
                assertTrue(button.performLongClick());
                startController.handleIntent(capture.intent);
                assertSame(active, player.getPlayQueue());
                assertEquals(PlayerType.POPUP, player.getPlayerType());
                assertEquals(0, active.getIndex());
                assertEquals(List.of("playing", "priority", "next", "a", "sparse-b", "c"),
                        active.getStreams().stream().map(item -> item.getTitle()).toList());
            } finally {
                setHolderPlayer(holder, previousPlayer);
                try {
                    player.destroy();
                } finally {
                    active.dispose();
                    player.getTrackSelector().release();
                }
            }
        });
    }

    private static void prepareView(final Player player, final Context context) {
        try {
            final Field binding = Player.class.getDeclaredField("binding");
            binding.setAccessible(true);
            binding.set(player, PlayerBinding.inflate(LayoutInflater.from(
                    new ContextThemeWrapper(context, ThemeHelper.getThemeForService(context, -1)))));
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Player setHolderPlayer(final PlayerHolder holder, final Player player) {
        try {
            final Field field = PlayerHolder.class.getDeclaredField("player");
            field.setAccessible(true);
            final Player previous = (Player) field.get(holder);
            field.set(holder, player);
            return previous;
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class CapturingContext extends ContextWrapper {
        private Intent intent;

        CapturingContext(final Context base) {
            super(base);
        }

        @Override
        public ComponentName startForegroundService(final Intent service) {
            intent = service;
            return service.getComponent();
        }
    }

    private static StreamInfoItem item(final String name) {
        final StreamInfoItem item = new StreamInfoItem(0,
                "https://example.invalid/" + name, name, StreamType.VIDEO_STREAM);
        item.setUploaderName("Uploader");
        return item;
    }

    private static final class LocalService extends Service {
        LocalService(final Context context) {
            attachBaseContext(context);
        }

        @Override
        public IBinder onBind(final Intent intent) {
            return null;
        }
    }
}
