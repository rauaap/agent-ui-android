package com.agentui.app;

import android.content.ActivityNotFoundException;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.net.URISyntaxException;

/** Browser links with tap handling that leaves Android's text selection intact. */
final class MarkdownLinks {
    private MarkdownLinks() {}

    static boolean isWebUrl(String href) {
        if (href == null) return false;
        try {
            URI uri = new URI(href);
            return ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    static void enable(TextView text) {
        // LinkMovementMethod replaces the selection movement method. Instead,
        // intercept only a short, stationary tap on a link; all other gestures
        // continue through the selectable TextView and its scrolling parents.
        int slop = ViewConfiguration.get(text.getContext()).getScaledTouchSlop();
        text.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float downX, downY;
            private long downTime;
            private URLSpan pressed;

            @Override public boolean onTouch(android.view.View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        downTime = event.getEventTime();
                        pressed = linkAt(text, downX, downY);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getX() - downX) > slop
                                || Math.abs(event.getY() - downY) > slop) pressed = null;
                        break;
                    case MotionEvent.ACTION_UP:
                        URLSpan link = pressed;
                        pressed = null;
                        if (link != null
                                && event.getEventTime() - downTime < ViewConfiguration.getLongPressTimeout()
                                && Math.abs(event.getX() - downX) <= slop
                                && Math.abs(event.getY() - downY) <= slop
                                && linkAt(text, event.getX(), event.getY()) == link) {
                            text.cancelLongPress();
                            text.setPressed(false);
                            try {
                                link.onClick(text);
                            } catch (ActivityNotFoundException | SecurityException e) {
                                Toast.makeText(text.getContext(), "Unable to open link", Toast.LENGTH_SHORT).show();
                            }
                            return true;
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_CANCEL:
                        pressed = null;
                        break;
                }
                return false;
            }
        });
    }

    private static URLSpan linkAt(TextView text, float x, float y) {
        Layout layout = text.getLayout();
        if (layout == null || !(text.getText() instanceof Spanned)) return null;
        x += text.getScrollX() - text.getTotalPaddingLeft();
        y += text.getScrollY() - text.getTotalPaddingTop();
        if (y < 0 || y >= layout.getHeight()) return null;
        int line = layout.getLineForVertical((int) y);
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null;
        int offset = layout.getOffsetForHorizontal(line, x);
        Spanned content = (Spanned) text.getText();
        for (URLSpan link : content.getSpans(offset, offset, URLSpan.class)) {
            if (offset >= content.getSpanStart(link) && offset < content.getSpanEnd(link)) return link;
        }
        return null;
    }
}
