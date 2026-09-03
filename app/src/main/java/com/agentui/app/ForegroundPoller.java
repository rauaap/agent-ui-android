package com.agentui.app;

import android.os.Handler;
import android.os.Looper;

/**
 * Runs a lightweight refresh periodically while its owner is in the foreground.
 * Owners start and stop it from their activity lifecycle; the first load remains
 * explicit so screens can use a different full-load path on resume.
 */
final class ForegroundPoller {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long intervalMs;
    private final Runnable refresh;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refresh.run();
            if (running) handler.postDelayed(this, intervalMs);
        }
    };

    ForegroundPoller(long intervalMs, Runnable refresh) {
        this.intervalMs = intervalMs;
        this.refresh = refresh;
    }

    /** Start with a delay; the activity performs its normal immediate load. */
    void start() {
        if (running) return;
        running = true;
        handler.postDelayed(tick, intervalMs);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }
}
