package com.agentui.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Original-byte lifetime and display sizing used by the native dialog. */
public class ImageViewerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void pendingOrFailedUploadCanStillBeViewedFromUnchangedSelectedBytes() throws Exception {
        File file = temp.newFile();
        byte[] original = {0, 1, (byte) 255, 7};
        Files.write(file.toPath(), original);
        SelectedImageFile selected = new SelectedImageFile(file);
        assertArrayEquals(original, selected.read(f -> Files.readAllBytes(f.toPath())));
        assertTrue(file.exists()); // Viewing does not consume/delete the selection.
        assertArrayEquals(original, selected.read(f -> Files.readAllBytes(f.toPath())));
        selected.close();
        assertFalse(file.exists());
    }

    @Test public void releaseWaitsForAnInFlightViewerRead() throws Exception {
        File file = temp.newFile();
        Files.write(file.toPath(), new byte[]{8});
        SelectedImageFile selected = new SelectedImageFile(file);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch reading = new CountDownLatch(1), finish = new CountDownLatch(1), closing = new CountDownLatch(1);
        try {
            Future<byte[]> read = workers.submit(() -> selected.read(f -> {
                reading.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                return Files.readAllBytes(f.toPath());
            }));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            Future<?> close = workers.submit(() -> { closing.countDown(); selected.close(); });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertFalse(close.isDone());
            assertTrue(file.exists());
            finish.countDown();
            assertArrayEquals(new byte[]{8}, read.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);
            assertFalse(file.exists());
        } finally { finish.countDown(); workers.shutdownNow(); }
    }

    @Test public void releaseIsIdempotentAndLateReadsFailInsteadOfUsingDeletedFiles() throws Exception {
        SelectedImageFile selected = new SelectedImageFile(temp.newFile());
        selected.close();
        selected.close();
        assertThrows(IOException.class, () -> selected.read(f -> Files.readAllBytes(f.toPath())));
    }

    @Test public void viewerUsesScreenResolutionRatherThanTheSmallThumbnail() {
        int target = ImageDecodeSize.viewerTarget(1080, 1920);
        assertEquals(1920, target);
        assertEquals(1, ImageDecodeSize.sampleSize(1600, 1200, target));
        assertEquals(32, ImageDecodeSize.sampleSize(1600, 1200, 88));
        assertEquals(target, ImageDecodeSize.viewerTarget(1920, 1080));
    }

    @Test public void hugeImagesHaveBoundedDisplayBitmapsAndPreserveAspectRatio() {
        int target = ImageDecodeSize.viewerTarget(4000, 6000);
        assertEquals(2048, target);
        int sample = ImageDecodeSize.sampleSize(16000, 8000, target);
        assertEquals(8, sample);
        assertTrue(16000 / sample <= target);
        assertEquals(2f, (float) (16000 / sample) / (8000 / sample), 0);
    }

    @Test public void unmeasuredTargetsDoNotCauseInfiniteSamplingOrOverflow() {
        assertEquals(1, ImageDecodeSize.viewerTarget(0, 0));
        assertEquals(1, ImageDecodeSize.sampleSize(1, 1, 0));
        assertEquals(1 << 30, ImageDecodeSize.sampleSize(Integer.MAX_VALUE, 1, 0));
    }
}
