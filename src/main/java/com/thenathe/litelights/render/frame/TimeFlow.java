package com.thenathe.litelights.render.frame;

/**
 * Notices when the world's time stops running at its normal pace: a jump ({@code /time set},
 * {@code /time add}, sleeping through the night) or a race ({@code /tick rate} above 20,
 * {@code /tick sprint}). Whatever eases toward the time of day or the weather can then snap at once,
 * or keep up, instead of gliding behind.
 *
 * <p>A jump is the day time moving on by much more than the game ticks that passed. A race is a
 * tick rate above the normal 20, or the game time moving on more than half as fast again as 20
 * ticks a second, measured over half a second of real time: a sprint doesn't change the client's
 * tick rate, only how fast the server's time arrives. In normal play neither is ever true and
 * {@link #speed} is exactly 1.
 *
 * <p>Pure logic: the caller reads the level's clocks once per frame.
 */
public final class TimeFlow {
    public static final float NORMAL_TICK_RATE = 20.0f;
    /** The day time may run this many ticks (a game second) ahead of or behind the game time in a frame before it counts as a jump. */
    static final long JUMP_TICKS = 20L;
    /** Game time arriving this many times faster than normal counts as a race. */
    static final float RACE_FACTOR = 1.5f;
    /** Real seconds the game's pace is measured over. */
    static final float WINDOW_SECONDS = 0.5f;
    /**
     * Real seconds a jump keeps the flow hurried for: the sky's sun follows the clock on the next
     * client tick, not in the frame that first reads the new time, so the hold must outlast a tick
     * or two whatever the frame rate.
     */
    static final float JUMP_HOLD_SECONDS = 0.5f;

    private boolean known;
    private long lastDayTime;
    private long lastGameTime;
    private float windowTicks;
    private float windowSeconds;
    private float measuredTickRate = NORMAL_TICK_RATE;
    private boolean jumped;
    private float jumpHold;
    private boolean racing;
    private float speed = 1.0f;

    /**
     * Reads this frame's clocks.
     *
     * @param present     whether there is a level to read
     * @param dayTime     the day time in ticks (the overworld clock's total)
     * @param gameTime    the level's game time in ticks
     * @param tickRate    the client's tick rate ({@code /tick rate})
     * @param frozen      whether ticks are frozen ({@code /tick freeze})
     * @param realSeconds real seconds since the last frame, 0 while the game is paused
     */
    public void update(boolean present, long dayTime, long gameTime, float tickRate, boolean frozen, float realSeconds) {
        jumped = false;
        if (!present) {
            reset();
            return;
        }
        if (!known) {
            known = true;
            lastDayTime = dayTime;
            lastGameTime = gameTime;
            return;
        }
        long dayStep = dayTime - lastDayTime;
        long gameStep = gameTime - lastGameTime;
        lastDayTime = dayTime;
        lastGameTime = gameTime;
        jumped = Math.abs(dayStep - gameStep) > JUMP_TICKS;
        jumpHold = jumped ? JUMP_HOLD_SECONDS : Math.max(0.0f, jumpHold - Math.max(0.0f, realSeconds));

        windowTicks += Math.max(0L, gameStep);
        windowSeconds += Math.max(0.0f, realSeconds);
        if (windowSeconds >= WINDOW_SECONDS) {
            measuredTickRate = windowTicks / windowSeconds;
            windowTicks = 0.0f;
            windowSeconds = 0.0f;
        }
        boolean fastRate = tickRate > NORMAL_TICK_RATE + 0.5f;
        boolean fastArrival = measuredTickRate > NORMAL_TICK_RATE * RACE_FACTOR;
        racing = !frozen && (fastRate || fastArrival);
        speed = racing ? Math.max(tickRate, measuredTickRate) / NORMAL_TICK_RATE : 1.0f;
    }

    /** Forgets the last frame's clocks, as when the level changes. */
    public void reset() {
        known = false;
        jumped = false;
        jumpHold = 0.0f;
        racing = false;
        speed = 1.0f;
        windowTicks = 0.0f;
        windowSeconds = 0.0f;
        measuredTickRate = NORMAL_TICK_RATE;
    }

    /** The day time jumped this frame. */
    public boolean jumped() {
        return jumped;
    }

    /** The day time jumped this frame or less than {@link #JUMP_HOLD_SECONDS} ago. */
    public boolean jumpedRecently() {
        return jumpHold > 0.0f;
    }

    /** The world's time runs faster than normal. */
    public boolean racing() {
        return racing;
    }

    /** How many times faster than normal the world's time runs: exactly 1 unless {@link #racing}. */
    public float speed() {
        return speed;
    }

    /** What eases toward the time of day should snap or hurry this frame: a recent jump or a race. */
    public boolean hurried() {
        return jumpHold > 0.0f || racing;
    }
}
