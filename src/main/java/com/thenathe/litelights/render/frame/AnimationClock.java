package com.thenathe.litelights.render.frame;

/**
 * A clock that runs on real time but stands still while the game is paused, so an animation picks
 * up where it left off when the pause menu closes. In multiplayer the game never pauses, so there
 * it is plain real time.
 *
 * <p>Pure logic: the caller passes the time in nanoseconds ({@code System.nanoTime()} in the game).
 */
public final class AnimationClock {
    /** Seconds are handed on wrapped at this, so a float keeps millisecond steps after hours of play. */
    public static final double WRAP_SECONDS = 3600.0;

    private long lastNanos;
    private boolean started;
    private double seconds;
    private float frameSeconds;

    /** Moves the clock on to {@code nowNanos}; while paused the time that passes is skipped. */
    public void advance(long nowNanos, boolean paused) {
        long passed = started ? nowNanos - lastNanos : 0L;
        lastNanos = nowNanos;
        started = true;
        if (paused || passed <= 0L) {
            frameSeconds = 0.0f;
            return;
        }
        frameSeconds = (float) (passed / 1.0e9);
        seconds = (seconds + passed / 1.0e9) % WRAP_SECONDS;
    }

    /** Seconds the clock has run, wrapped at {@link #WRAP_SECONDS}. */
    public double seconds() {
        return seconds;
    }

    /** Seconds the clock moved on in the last {@link #advance}: 0 while paused and on the first call. */
    public float frameSeconds() {
        return frameSeconds;
    }
}
