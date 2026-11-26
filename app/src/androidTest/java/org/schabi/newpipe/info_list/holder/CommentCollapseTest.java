package org.schabi.newpipe.info_list.holder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.util.CommentTextOnTouchListener;
import org.schabi.newpipe.util.ThemeHelper;

@RunWith(AndroidJUnit4.class)
public class CommentCollapseTest {
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=aaaaaaaaaaa";
    private static final String COMMENT = "<b>https://example.com</b> 0:12<br>"
            + "A long comment above the replies. ".repeat(30);

    @Test
    public void linkedCommentBodyCollapsesAndExpandsWithoutLosingSpans() {
        withHolder(false, holder -> {
            holder.updateFromItem(comment(COMMENT), null);
            final TextView body = holder.itemView.findViewById(R.id.itemCommentContentView);
            measure(body);
            assertTrue(body.getLineCount() > 2);
            final String fullComment = body.getText().toString();

            assertTrue(body.performClick());
            assertCollapsed(body);
            assertEquals(fullComment, body.getText().toString());
            assertTrue(((Spanned) body.getText()).getSpans(0, 19, StyleSpan.class).length > 0);
            assertVisibleLinkCanBeTapped(body);

            assertTrue(body.performClick());
            measure(body);
            assertTrue(body.getLineCount() > 2);
            assertEquals(fullComment, body.getText().toString());
            assertTrue(java.util.Arrays.stream(body.getUrls())
                    .anyMatch(link -> link.getURL().equals(VIDEO_URL + "#timestamp=12")));
        });
    }

    @Test
    public void autoCollapseUsesTheNewCommentWhenTheRowIsRebound() {
        withHolder(true, holder -> {
            holder.updateFromItem(comment("Short comment"), null);
            final TextView body = holder.itemView.findViewById(R.id.itemCommentContentView);
            measure(body);
            assertEquals(0, body.getLayout().getEllipsisCount(0));

            holder.updateFromItem(comment(COMMENT), null);
            assertCollapsed(body);
        });
    }

    private static void assertCollapsed(final TextView body) {
        measure(body);
        assertEquals(2, body.getMaxLines());
        assertEquals(2, body.getLineCount());
        assertTrue(body.getLayout().getEllipsisCount(1) > 0);
    }

    private static void assertVisibleLinkCanBeTapped(final TextView body) {
        final long now = SystemClock.uptimeMillis();
        final float x = body.getTotalPaddingLeft() + body.getLayout().getPrimaryHorizontal(3);
        final float y = body.getTotalPaddingTop() + body.getLayout().getLineBottom(0) / 2f;
        final MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        try {
            assertTrue(CommentTextOnTouchListener.INSTANCE.onTouch(body, down));
        } finally {
            down.recycle();
        }
    }

    private static void measure(final TextView body) {
        body.measure(View.MeasureSpec.makeMeasureSpec(650, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        body.layout(0, 0, body.getMeasuredWidth(), body.getMeasuredHeight());
        assertNotNull(body.getLayout());
    }

    private static CommentsInfoItem comment(final String html) {
        final CommentsInfoItem comment = new CommentsInfoItem(0, VIDEO_URL, "Test comment");
        comment.setUploaderName("Test author");
        comment.setCommentText(new Description(html, Description.HTML));
        return comment;
    }

    private static void withHolder(final boolean autoCollapse,
                                   final java.util.function.Consumer<CommentsInfoItemHolder> test) {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            final Context context = instrumentation.getTargetContext();
            final Context themed = new ContextThemeWrapper(context,
                    ThemeHelper.getThemeForService(context, -1));
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            final String key = context.getString(R.string.auto_ellipsize_key);
            final Boolean saved = (Boolean) prefs.getAll().get(key);
            prefs.edit().putBoolean(key, autoCollapse).apply();
            try {
                test.accept(new CommentsInfoItemHolder(new InfoItemBuilder(themed),
                        new FrameLayout(themed)));
            } finally {
                final var edit = prefs.edit();
                if (saved == null) {
                    edit.remove(key);
                } else {
                    edit.putBoolean(key, saved);
                }
                edit.apply();
            }
        });
    }
}
