package com.agentui.app;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A real, accessible copy target fitted into the card's trailing corner. */
final class EventCardLayout extends LinearLayout {
    private final boolean flowing;
    private TextView eventId;
    private int idTop;

    EventCardLayout(Context context) { this(context, false); }

    EventCardLayout(Context context, boolean flowing) {
        super(context);
        setOrientation(VERTICAL);
        this.flowing = flowing;
    }

    void setEventId(long id) {
        if (id <= 0) return;
        eventId = Widgets.idLabel(getContext(), Long.toString(id), "Event", 11);
        eventId.setContentDescription("Event ID " + id + ", tap to copy");
        eventId.setPadding(Theme.dp(getContext(), 16), 0,
                !flowing && getPaddingRight() == 0 ? Theme.dp(getContext(), 11) : 0, 0);
        addView(eventId);
    }

    View lastContentChild() {
        return getChildAt(getChildCount() - (eventId == null ? 1 : 2));
    }

    private View lastVisibleContentChild() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child != eventId && child.getVisibility() != GONE) return child;
        }
        return null;
    }

    private int measureContent(int widthSpec, int heightSpec) {
        int height = getPaddingTop() + getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == eventId || child.getVisibility() == GONE) continue;
            int reserved = !flowing && eventId != null && child == lastVisibleContentChild()
                    ? eventId.getMeasuredWidth() : 0;
            measureChildWithMargins(child, widthSpec, reserved, heightSpec, height);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            height += child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
        }
        return height;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (eventId != null) eventId.measure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int height = measureContent(widthSpec, heightSpec);
        int desiredWidth = getSuggestedMinimumWidth();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == eventId || child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            desiredWidth = Math.max(desiredWidth, child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
        }
        desiredWidth += getPaddingLeft() + getPaddingRight();
        if (eventId != null) desiredWidth += eventId.getMeasuredWidth();
        int width = resolveSize(desiredWidth, widthSpec);
        height = measureContent(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
        layoutContent();
        int contentBottom = height - getPaddingBottom();
        idTop = Math.max(getPaddingTop(), contentBottom - (eventId == null ? 0 : eventId.getMeasuredHeight()));
        if (eventId != null) {
            View last = lastTextOrBlock(this);
            if (last != null) {
                Rect bounds = new Rect(0, 0, last.getWidth(), last.getHeight());
                offsetDescendantRectToMyCoords(last, bounds);
                int occupiedRight = bounds.right;
                // Containers such as Bash output have their own inner padding.
                // Keep the ID inside that inset rather than against the border.
                if (!flowing) {
                    idTop = Math.max(getPaddingTop(), bounds.bottom - last.getPaddingBottom()
                            - eventId.getMeasuredHeight());
                }
                if (last instanceof TextView) {
                    TextView text = (TextView) last;
                    android.text.Layout layout = text.getLayout();
                    if (layout != null && layout.getLineCount() > 0) {
                        int line = layout.getLineCount() - 1;
                        occupiedRight = bounds.left + text.getCompoundPaddingLeft()
                                + (int) Math.ceil(layout.getLineRight(line));
                        idTop = bounds.top + text.getTotalPaddingTop() + layout.getLineBaseline(line)
                                - eventId.getBaseline();
                    }
                }
                // Non-flowing cards already reserve the trailing width during
                // measurement. Only message text may need to wrap the ID below.
                if (flowing && occupiedRight > width - getPaddingRight() - eventId.getMeasuredWidth()) {
                    idTop = contentBottom;
                }
            }
        }
        if (eventId != null) height = Math.max(height,
                idTop + eventId.getMeasuredHeight() + getPaddingBottom());
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    private View lastTextOrBlock(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child == eventId || child.getVisibility() == GONE) continue;
            if (child instanceof LinearLayout) {
                View nested = lastTextOrBlock((ViewGroup) child);
                if (nested != null) return nested;
            }
            return child;
        }
        return null;
    }

    private void layoutContent() {
        int y = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == eventId || child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            y += lp.topMargin;
            int x = getPaddingLeft() + lp.leftMargin;
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            y += child.getMeasuredHeight() + lp.bottomMargin;
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layoutContent();
        if (eventId != null) {
            int x = right - left - getPaddingRight() - eventId.getMeasuredWidth();
            eventId.layout(x, idTop, x + eventId.getMeasuredWidth(), idTop + eventId.getMeasuredHeight());
        }
    }
}
