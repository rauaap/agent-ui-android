package com.agentui.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.net.URISyntaxException;

import okhttp3.HttpUrl;

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

    static String resolveUrl(String href, String server) {
        if (isWebUrl(href)) return href;
        if (href == null || !href.startsWith("/shared-assets/") || href.indexOf('\\') >= 0) return null;
        for (int i = 0; i < href.length(); i++) if (Character.isISOControl(href.charAt(i))) return null;
        HttpUrl base = server == null ? null : HttpUrl.parse(server);
        HttpUrl resolved = base == null ? null : base.resolve(href);
        // Reject traversal which normalizes out of the asset route. No arbitrary
        // server-relative links or protocol-relative destinations are enabled.
        return resolved != null && resolved.encodedPath().startsWith("/shared-assets/")
                ? resolved.toString() : null;
    }

    static boolean isActionableUrl(String href) {
        return resolveUrl(href, "https://asset-link.invalid") != null;
    }

    static URLSpan span(String href) {
        return new URLSpan(href) {
            @Override public void onClick(android.view.View view) {
                open(view.getContext(), getURL());
            }
        };
    }

    static void open(Context context, String href) {
        String resolved = resolveUrl(href, new Prefs(context).httpBase());
        if (resolved == null) {
            Toast.makeText(context, "Unable to resolve link. Check the saved server address.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                    .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show();
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
