package com.agentui.app;

/** Shared desktop-aligned thumbnail/control dimensions, expressed in Android dp. */
final class ImagePreviewStyle {
    static final int WIDTH = 88;
    static final int HEIGHT = 66;
    static final int CORNER = 10;
    static final int ATTACH_SIZE = 30;
    static final int ATTACH_INSET = 6;
    static final int REMOVE_SIZE = 22;
    static final int GAP = 6;

    /** Centered actual-image bounds, not the letterbox: no aspect distortion or cropping. */
    static float[] bounds(int imageWidth, int imageHeight, int width, int height) {
        if (imageWidth <= 0 || imageHeight <= 0 || width <= 0 || height <= 0)
            return new float[]{0, 0, 0, 0};
        float scale = Math.min((float) width / imageWidth, (float) height / imageHeight);
        float w = imageWidth * scale, h = imageHeight * scale;
        float x = (width - w) / 2, y = (height - h) / 2;
        return new float[]{x, y, x + w, y + h};
    }
}
