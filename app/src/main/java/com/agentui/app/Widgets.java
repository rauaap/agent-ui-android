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
import android.widget.EditText;
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
        int fg = statusColor(status);
        int fill;
        int stroke;
        switch (status) {
            case "running":
                fill = Theme.withAlpha(fg, 0x1F); stroke = Theme.withAlpha(fg, 0x47);
                break;
            case "awaiting_approval":
                fill = Theme.withAlpha(fg, 0x21); stroke = Theme.withAlpha(fg, 0x4D);
                break;
            default:
                fill = Theme.withAlpha(fg, 0x1F); stroke = Theme.withAlpha(fg, 0x38);
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

    static int statusColor(String status) {
        switch (status) {
            case "running": return Theme.RUNNING;
            case "awaiting_approval": return Theme.AWAITING;
            default: return Theme.IDLE;
        }
    }

    /**
     * Just the badge's dot, for a header too narrow for the label. The status
     * word stays available to screen readers.
     */
    static TextView statusDot(Context ctx, String status) {
        TextView t = text(ctx, "●", statusColor(status), 11, false);
        t.setContentDescription(status.replace("_", " "));
        return t;
    }

    /**
     * A small pill label — the status badge's shape without its status
     * vocabulary, for markers like MISSING or WORKTREE.
     */
    static TextView tag(Context ctx, String label, int color) {
        TextView t = text(ctx, label, color, 10, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.05f);
        t.setBackground(Theme.pill(ctx,
                Theme.withAlpha(color, 0x22), Theme.withAlpha(color, 0x66), 1));
        int padH = Theme.dp(ctx, 10);
        int padV = Theme.dp(ctx, 4);
        t.setPadding(padH, padV, padH, padV);
        return t;
    }

    /** The small caption above a form input. */
    static TextView fieldLabel(Context ctx, String s) {
        TextView t = text(ctx, s, Theme.MUTED, 13, true);
        margins(t, 0, 0, 0, Theme.dp(ctx, 7));
        return t;
    }

    /** A single-line form input in the app's panel styling. */
    static EditText field(Context ctx, String hint, int inputType, boolean mono) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextColor(Theme.INK);
        e.setHintTextColor(Theme.FAINT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (mono) e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setBackground(Theme.rounded(ctx, Theme.PANEL2, 10, Theme.LINE, 1));
        int p = Theme.dp(ctx, 12);
        e.setPadding(p, 0, p, 0);
        e.setMinHeight(Theme.dp(ctx, 44));
        e.setLayoutParams(lp(MATCH, WRAP));
        return e;
    }

    /** A path input: monospace, and no autocorrect chewing on the slashes. */
    static EditText pathField(Context ctx, String hint) {
        return field(ctx, hint,
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_URI,
                true);
    }

    static View spacer(Context ctx, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(lp(MATCH, Theme.dp(ctx, dp)));
        return v;
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

    /**
     * A session or worktree id as {@code #42}, monospace and faint. Tapping it
     * copies the bare number, which is what an agent's tool call takes.
     *
     * @param what "Session" or "Worktree", for the confirmation toast
     */
    static TextView idLabel(Context ctx, String id, String what, float sp) {
        TextView t = mono(ctx, "#" + id, Theme.FAINT, sp);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> copyId(ctx, id, what));
        return t;
    }

    /** Put a bare id on the clipboard and say which kind it was. */
    static void copyId(Context ctx, String id, String what) {
        copy(ctx, what + " ID", id, what + " ID copied");
    }

    /** Put {@code text} on the clipboard under {@code label} and confirm with {@code toast}. */
    static void copy(Context ctx, String label, String text, String toast) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
        android.widget.Toast.makeText(ctx, toast, android.widget.Toast.LENGTH_SHORT).show();
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
