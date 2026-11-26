package org.schabi.newpipe.util;

import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.util.external_communication.InternalUrlsHandler;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class CommentTextOnTouchListener implements View.OnTouchListener {
    public static final CommentTextOnTouchListener INSTANCE = new CommentTextOnTouchListener();

    @Override
    public boolean onTouch(final View v, final MotionEvent event) {
        if (!(v instanceof TextView)) {
            return false;
        }
        final TextView widget = (TextView) v;
        final Object text = widget.getText();
        if (text instanceof Spanned buffer) {

            final int action = event.getAction();

            if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_DOWN) {
                int x = (int) event.getX();
                int y = (int) event.getY();

                x -= widget.getTotalPaddingLeft();
                y -= widget.getTotalPaddingTop();

                x += widget.getScrollX();
                y += widget.getScrollY();

                final Layout layout = widget.getLayout();
                if (layout == null || y < 0 || y >= layout.getHeight()) {
                    return false;
                }
                final int line = layout.getLineForVertical(y);
                if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) {
                    return false;
                }
                final int off = layout.getOffsetForHorizontal(line, x);
                if (layout.getEllipsisCount(line) > 0
                        && off >= layout.getLineStart(line) + layout.getEllipsisStart(line)) {
                    return false;
                }

                final ClickableSpan[] link = buffer.getSpans(off, off,
                        ClickableSpan.class);

                if (link.length != 0) {
                    if (action == MotionEvent.ACTION_UP) {
                        if (link[0] instanceof URLSpan) {
                            final String url = ((URLSpan) link[0]).getURL();
                            if (!InternalUrlsHandler.handleUrlCommentsTimestamp(
                                    new CompositeDisposable(), v.getContext(), url)) {
                                ShareUtils.openUrlInBrowser(v.getContext(), url, false);
                            }
                        }
                    } else if (buffer instanceof Spannable selection) {
                        Selection.setSelection(selection,
                                buffer.getSpanStart(link[0]),
                                buffer.getSpanEnd(link[0]));
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
