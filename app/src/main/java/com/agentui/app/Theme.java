package com.agentui.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/**
 * Colour palette and small view helpers mirroring the web front-end's CSS
 * custom properties, so the native UI matches the original look.
 */
final class Theme {
    private Theme() {}

    static final int BG          = 0xFF100F12;
    static final int PANEL       = 0xFF1A1820;
    static final int PANEL2      = 0xFF221F2A;
    static final int PANEL_HOVER = 0xFF262230;
    static final int LINE        = 0xFF2C2935;
    static final int LINE_SOFT   = 0xFF232029;

    static final int INK   = 0xFFECE8F0;
    static final int MUTED = 0xFF9893A6;
    static final int FAINT = 0xFF6F6A7D;

    static final int ACCENT        = 0xFFD97757;
    static final int ACCENT_STRONG = 0xFFE0875F;
    static final int ACCENT_PRESS  = 0xFFC25F3F;
    static final int ACCENT_SOFT   = 0x24D97757; // ~14% opacity
    static final int ACCENT_LINE   = 0x66D97757; // ~40% opacity

    static final int IDLE     = 0xFF8B8696;
    static final int RUNNING  = 0xFF4ECF9E;
    static final int AWAITING = 0xFFE8B14C;
    static final int DANGER   = 0xFFEF6F63;
    static final int INFO     = 0xFF7EA7E0;

    static final int DANGER_SOFT = 0x22EF6F63;
    static final int DANGER_LINE = 0x66EF6F63;

    static int dp(Context ctx, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics()));
    }

    /** Solid fill with rounded corners and an optional stroke. */
    static GradientDrawable rounded(Context ctx, int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(ctx, radiusDp));
        if (strokeDp > 0) d.setStroke(dp(ctx, strokeDp), strokeColor);
        return d;
    }

    static GradientDrawable rounded(Context ctx, int fill, float radiusDp) {
        return rounded(ctx, fill, radiusDp, Color.TRANSPARENT, 0);
    }

    /** A pill / circle. */
    static GradientDrawable pill(Context ctx, int fill, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(ctx, 999));
        if (strokeDp > 0) d.setStroke(dp(ctx, strokeDp), strokeColor);
        return d;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
