package com.agentui.app;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

/**
 * A ScrollView for the transcript: it follows new content while the reader is
 * at the bottom and otherwise holds their place.
 *
 * <p>A plain ScrollView only remembers an offset from the top, so it neither
 * follows content as it grows nor keeps the bottom edge still when it gets
 * shorter (the keyboard opening, the footer growing). Instead this keeps one
 * piece of state, whether the reader is stuck to the bottom, and applies it on
 * every layout, whatever caused it: stuck snaps to the end, unstuck keeps the
 * same content at the bottom edge. Only scrolling outside of layout, which is
 * the reader dragging or flinging, changes it.
 */
final class StickyScrollView extends ScrollView {
    private boolean stuck = true;
    private boolean inLayout;
    private int lastHeight;
    private Runnable onPositionChanged;

    StickyScrollView(Context ctx) {
        super(ctx);
    }

    /** Called after every scroll or layout, e.g. to show or hide a jump button. */
    void setOnPositionChanged(Runnable r) {
        onPositionChanged = r;
    }

    /** Stick to the bottom and jump there. */
    void scrollToEnd() {
        stuck = true;
        scrollTo(0, scrollRange());
    }

    /**
     * Stop following the bottom so a change the reader just made in place,
     * such as expanding a card, isn't scrolled out from under them. Landing
     * back on the bottom afterwards sticks again.
     */
    void release() {
        stuck = false;
    }

    private int scrollRange() {
        if (getChildCount() == 0) return 0;
        int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.max(0, getChildAt(0).getHeight() - viewport);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int oldY = getScrollY();
        int oldHeight = lastHeight;
        inLayout = true;
        super.onLayout(changed, l, t, r, b);
        int height = getHeight();
        if (stuck) {
            scrollTo(0, scrollRange());
        } else if (oldHeight != 0 && oldHeight != height) {
            // Anchor the bottom edge: the keyboard and footer take height off
            // the bottom, so shift by the same amount the viewport changed.
            scrollTo(0, oldY + oldHeight - height);
        }
        inLayout = false;
        lastHeight = height;
        // Landing on the end by any route, even content shrinking under the
        // reader, puts them at the bottom, so follow from there.
        if (!canScrollVertically(1)) stuck = true;
        if (onPositionChanged != null) onPositionChanged.run();
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        // Layout's own offset changes are applied above; anything else is the
        // reader moving, which decides whether to follow the bottom.
        if (!inLayout) stuck = !canScrollVertically(1);
        if (onPositionChanged != null) onPositionChanged.run();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // ScrollView animates a focused child back into view here, which would
        // fight the positioning in onLayout. Resizes are handled there instead.
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
        // A focused text block asks to show its cursor whenever its text is
        // replaced, which would pull the view up off the bottom it follows.
        return !stuck && super.requestChildRectangleOnScreen(child, rect, immediate);
    }
}
