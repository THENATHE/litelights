package com.thenathe.litelights.render.frame;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.TickRateManager;

/**
 * The clocks LiteLights' animations and easings run on, read once per frame on the render thread
 * before any feature's frame update.
 *
 * <ul>
 *   <li>{@link #animationSeconds}: the shaders' clock (waves, foam, caustics, sway, glint, the sky's
 *   events, fog drift), real time that stands still while the game is paused.</li>
 *   <li>{@link #frameSeconds}: this frame's step of that clock, for easings that follow it.</li>
 *   <li>{@link #gameSeconds}: the same step in the world's time, for what follows the weather:
 *   faster while the world's time races ({@link TimeFlow}), exactly the real step otherwise.</li>
 *   <li>{@link #timeHurried}: the day time jumped or races, so what follows the time of day (the sun's
 *   shadow map) should snap rather than glide.</li>
 * </ul>
 */
public final class FrameClock {
    private static final AnimationClock ANIMATION = new AnimationClock();
    private static final TimeFlow TIME = new TimeFlow();
    private static ClientLevel lastLevel;
    private static long frameNumber;

    private FrameClock() {
    }

    /** Called first thing in every frame the game renders, menus included. */
    public static void frameStarted() {
        frameNumber++;
    }

    /**
     * How many frames have started since the game opened. GPU timers stamp their results with it,
     * so a time read back a few frames late can be matched to the frame it measured.
     */
    public static long frameNumber() {
        return frameNumber;
    }

    /** Called once per frame by the renderer, before the features' frame updates. */
    public static void update(Minecraft minecraft) {
        ANIMATION.advance(System.nanoTime(), minecraft.isPaused());
        ClientLevel level = minecraft.level;
        if (level != lastLevel) {
            TIME.reset();
            lastLevel = level;
        }
        if (level == null) {
            TIME.update(false, 0L, 0L, TimeFlow.NORMAL_TICK_RATE, false, 0.0f);
            return;
        }
        TickRateManager ticks = level.tickRateManager();
        TIME.update(true, level.getOverworldClockTime(), level.getGameTime(), ticks.tickrate(), ticks.isFrozen(),
            ANIMATION.frameSeconds());
    }

    /** The world was left: the world's clock starts afresh, and the level isn't kept. */
    public static void onWorldLeft() {
        TIME.reset();
        lastLevel = null;
    }

    /** The shaders' clock in seconds, wrapped at an hour; stands still while the game is paused. */
    public static float animationSeconds() {
        return (float) ANIMATION.seconds();
    }

    /** Real seconds this frame moved the animation clock on: 0 while the game is paused. */
    public static float frameSeconds() {
        return ANIMATION.frameSeconds();
    }

    /**
     * Seconds of the world's time in this frame, from a real step already capped by the caller:
     * {@code realStep} times how much faster than normal the world's time runs (1 in normal play).
     */
    public static float gameSeconds(float realStep) {
        return realStep * TIME.speed();
    }

    /** The day time jumped in the last half second, or the world's time races. */
    public static boolean timeHurried() {
        return TIME.hurried();
    }

    /** The day time jumped in the last half second. */
    public static boolean timeJumped() {
        return TIME.jumpedRecently();
    }

    public static boolean timeRacing() {
        return TIME.racing();
    }

    public static float timeSpeed() {
        return TIME.speed();
    }
}
