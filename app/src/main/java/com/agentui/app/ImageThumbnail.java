package com.agentui.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Transparent thumbnail that rounds the actual image, including portrait/letterboxed edges. */
final class ImageThumbnail extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF destination = new RectF();
    private final RectF source = new RectF();
    private Bitmap bitmap;

    ImageThumbnail(Context context) { super(context); }

    void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        paint.setShader(bitmap == null ? null : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        updateBounds();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateBounds();
    }

    private void updateBounds() {
        if (bitmap == null) return;
        float[] bounds = ImagePreviewStyle.bounds(bitmap.getWidth(), bitmap.getHeight(), getWidth(), getHeight());
        destination.set(bounds[0], bounds[1], bounds[2], bounds[3]);
        source.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        matrix.setRectToRect(source, destination, Matrix.ScaleToFit.FILL);
        paint.getShader().setLocalMatrix(matrix);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || destination.isEmpty()) return;
        float radius = Theme.dp(getContext(), ImagePreviewStyle.CORNER);
        canvas.drawRoundRect(destination, radius, radius, paint);
    }
}
