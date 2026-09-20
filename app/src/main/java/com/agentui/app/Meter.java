package com.agentui.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A single ratio against a limit, drawn as one horizontal track. The fill
 * carries severity and the unfilled track is the same colour held back to a
 * wash, so the bar reads as one state end to end rather than as two unrelated
 * colours meeting in the middle.
 *
 * <p>The number always sits beside it: colour alone never carries the reading.
 */
final class Meter extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float fraction;
    private int color = Theme.ACCENT;

    Meter(Context ctx) {
        super(ctx);
    }

    /** @param fraction 0–1; callers clamp, this does not rescale. */
    void set(float fraction, int color) {
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float height = getHeight();
        float width = getWidth();
        if (height <= 0 || width <= 0) return;
        float radius = height / 2f;

        paint.setColor(Theme.withAlpha(color, 0x24));
        rect.set(0, 0, width, height);
        canvas.drawRoundRect(rect, radius, radius, paint);

        if (fraction <= 0f) return;
        // A non-zero share always draws at least a full round cap: a quota
        // that has been touched must not look identical to an untouched one.
        float filled = Math.max(height, fraction * width);
        paint.setColor(color);
        rect.set(0, 0, Math.min(filled, width), height);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
}
