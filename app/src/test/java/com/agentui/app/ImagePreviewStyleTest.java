package com.agentui.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImagePreviewStyleTest {
    @Test public void compactDimensionsMatchCorrectedDesktopDesign() {
        assertEquals(88, ImagePreviewStyle.WIDTH);
        assertEquals(66, ImagePreviewStyle.HEIGHT);
        assertEquals(10, ImagePreviewStyle.CORNER);
        assertEquals(30, ImagePreviewStyle.ATTACH_SIZE);
        assertEquals(6, ImagePreviewStyle.ATTACH_INSET);
        assertEquals(22, ImagePreviewStyle.REMOVE_SIZE);
    }

    @Test public void portraitCornersBelongToImageNotLetterbox() {
        assertArrayEquals(new float[]{27.5f, 0, 60.5f, 66},
                ImagePreviewStyle.bounds(400, 800, 88, 66), 0.001f);
    }

    @Test public void landscapeIsContainedWithoutCroppingOrDistortion() {
        assertArrayEquals(new float[]{0, 11, 88, 55},
                ImagePreviewStyle.bounds(800, 400, 88, 66), 0.001f);
    }

    @Test public void squareAndDensityScaledBoundsRemainCentered() {
        assertArrayEquals(new float[]{11, 0, 77, 66},
                ImagePreviewStyle.bounds(400, 400, 88, 66), 0.001f);
        assertArrayEquals(new float[]{33, 0, 231, 198},
                ImagePreviewStyle.bounds(400, 400, 264, 198), 0.001f);
    }

    @Test public void undecodedOrUnmeasuredImageHasNoDrawableBounds() {
        assertArrayEquals(new float[]{0, 0, 0, 0}, ImagePreviewStyle.bounds(0, 400, 88, 66), 0);
        assertArrayEquals(new float[]{0, 0, 0, 0}, ImagePreviewStyle.bounds(400, 400, 0, 0), 0);
    }
}
