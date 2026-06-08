package com.agentui.app;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small factory helpers for the programmatic, framework-only UI. */
final class Widgets {
    private Widgets() {}

    static LinearLayout column(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static TextView text(Context ctx, CharSequence s, int color, float sp, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView mono(Context ctx, CharSequence s, int color, float sp) {
        TextView t = text(ctx, s, color, sp, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    /** A pill-shaped status badge coloured per status, like the web .status-badge. */
    static TextView statusBadge(Context ctx, String status) {
        TextView t = new TextView(ctx);
        int fg;
        int fill;
        int stroke;
        switch (status) {
            case "running":
                fg = Theme.RUNNING; fill = Theme.withAlpha(Theme.RUNNING, 0x1F); stroke = Theme.withAlpha(Theme.RUNNING, 0x47);
                break;
            case "awaiting_approval":
                fg = Theme.AWAITING; fill = Theme.withAlpha(Theme.AWAITING, 0x21); stroke = Theme.withAlpha(Theme.AWAITING, 0x4D);
                break;
            default:
                fg = Theme.IDLE; fill = Theme.withAlpha(Theme.IDLE, 0x1F); stroke = Theme.withAlpha(Theme.IDLE, 0x38);
                break;
        }
        t.setText("● " + status.replace("_", " ").toUpperCase());
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAllCaps(true);
        t.setLetterSpacing(0.05f);
        t.setBackground(Theme.pill(ctx, fill, stroke, 1));
        int padH = Theme.dp(ctx, 10);
        int padV = Theme.dp(ctx, 4);
        t.setPadding(padH, padV, padH, padV);
        return t;
    }

    /** Primary (accent) button. */
    static TextView primaryButton(Context ctx, String label) {
        return button(ctx, label, 0xFF1A0F0A, Theme.ACCENT, Theme.ACCENT, 0);
    }

    static TextView ghostButton(Context ctx, String label) {
        return button(ctx, label, Theme.INK, Theme.PANEL2, Theme.LINE, 1);
    }

    static TextView dangerButton(Context ctx, String label) {
        return button(ctx, label, Theme.DANGER, Theme.DANGER_SOFT, Theme.DANGER_LINE, 1);
    }

    static TextView button(Context ctx, String label, int fg, int fill, int stroke, float strokeDp) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Theme.rounded(ctx, fill, 10, stroke, strokeDp));
        int padH = Theme.dp(ctx, 16);
        b.setPadding(padH, 0, padH, 0);
        b.setMinimumHeight(Theme.dp(ctx, 44));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /**
     * Pad a full-screen root for the system bars (status/navigation) and the
     * on-screen keyboard. With targetSdk 35+ the window draws edge-to-edge and
     * {@code adjustResize} no longer reflows the layout, so we consume the
     * insets ourselves: the top inset keeps the header clear of the status bar,
     * and the IME inset lifts the composer above the keyboard.
     */
    static void fitSystemWindows(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
    }

    static void margins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams base = v.getLayoutParams();
        LinearLayout.LayoutParams p = base instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) base
                : new LinearLayout.LayoutParams(
                        base != null ? base.width : WRAP,
                        base != null ? base.height : WRAP);
        p.setMargins(l, t, r, b);
        v.setLayoutParams(p);
    }
}
