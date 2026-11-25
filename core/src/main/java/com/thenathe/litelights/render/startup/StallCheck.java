package com.thenathe.litelights.render.startup;

/**
 * Decides when the render thread counts as stuck: no new frame for longer than the limit. Says so
 * at most once per game session. Times are {@code System.nanoTime} values.
 */
final class StallCheck {
    private final long limitNanos;
    private boolean warned;

    StallCheck(long limitNanos) {
        this.limitNanos = limitNanos;
    }

    /** True the first time the frame that started at {@code frameStart} has run past the limit. */
    boolean shouldWarn(long now, long frameStart) {
        if (warned || now - frameStart <= limitNanos) {
            return false;
        }
        warned = true;
        return true;
    }
}
