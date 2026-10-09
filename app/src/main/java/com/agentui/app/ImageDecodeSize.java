package com.agentui.app;

/** Display-only bitmap sampling. Never alters the stored/uploaded bytes. */
final class ImageDecodeSize {
    static int sampleSize(int width, int height, int target) {
        int sample = 1;
        int bound = Math.max(1, target);
        while (Math.max(width, height) / sample > bound && sample <= Integer.MAX_VALUE / 2)
            sample *= 2;
        return sample;
    }

    static int viewerTarget(int width, int height) {
        // Fit the full image to the screen, with a bounded display bitmap (at most 16 MiB RGBA).
        return Math.max(1, Math.min(2048, Math.max(width, height)));
    }
}
