package com.thenathe.litelights.render.feature.water.wake;

/**
 * How hard each kind of thing disturbs the water: a boat, a swimmer, something falling in. The game
 * side ({@link WakeSources}) and the offline prototype both go through here, so the pictures and the
 * game agree. Speeds are in blocks per tick (a boat at full speed on water does about 0.4, a
 * swimming player about 0.2); {@code strength} is the Wake strength setting (1 by default).
 */
public final class WakeShapes {
    /** Slower than this, in blocks per tick, leaves no wake. */
    public static final double MINIMUM_SPEED = 0.02;

    private WakeShapes() {
    }

    /** A boat moved from (fromX, fromZ) to (toX, toZ), facing (headingX, headingZ). */
    public static void boat(WakeSourceList sources, double fromX, double fromZ, double toX, double toZ,
                            double headingX, double headingZ, double speed, double strength) {
        double pace = pace(speed, 0.3);
        if (pace <= 0.0) {
            return;
        }
        sources.addHull(fromX, fromZ, toX, toZ, headingX, headingZ, 0.55, 0.65,
            0.55 * pace * strength, 0.06 * pace * strength, Math.min(1.0, 0.4 * pace * strength));
    }

    /** A player or mob swimming at the surface; {@code width} is its hitbox width. */
    public static void swimmer(WakeSourceList sources, double fromX, double fromZ, double toX, double toZ,
                               double headingX, double headingZ, double speed, double width, double strength) {
        double pace = pace(speed, 0.2);
        if (pace <= 0.0) {
            return;
        }
        double size = Math.clamp(width / 0.6, 0.5, 3.0);
        sources.addHull(fromX, fromZ, toX, toZ, headingX, headingZ, 0.15 * size, 0.32 * size,
            0.4 * pace * strength * Math.sqrt(size), 0.045 * pace * strength, Math.min(1.0, 0.4 * pace * strength));
    }

    /**
     * Something went into the water at (x, z) at {@code verticalSpeed} blocks per tick downward (as
     * for the jump splash: a block's fall arrives at about 0.4, ten blocks at 1.2).
     */
    public static void entry(WakeSourceList sources, double x, double z, double width, double verticalSpeed, double strength) {
        double speed = Math.abs(verticalSpeed);
        if (speed < 0.1) {
            return;
        }
        double size = Math.clamp(width / 0.6, 0.4, 3.0);
        double depth = Math.clamp(0.5 + speed * 1.6, 0.6, 2.5) * Math.sqrt(size) * strength;
        sources.addRing(x, z, 0.3 + 0.35 * size, depth, Math.min(1.0, (0.3 + 0.4 * speed) * strength));
    }

    /** A boat's paddle dipping into the water at (x, z). */
    public static void paddle(WakeSourceList sources, double x, double z, double strength) {
        sources.addRing(x, z, 0.3, 0.35 * strength, Math.min(1.0, 0.35 * strength));
    }

    /** 0 below the minimum speed, rising to 1 at {@code full} blocks per tick, and a little past it. */
    static double pace(double speed, double full) {
        if (speed < MINIMUM_SPEED) {
            return 0.0;
        }
        return Math.min(1.25, (speed - MINIMUM_SPEED) / (full - MINIMUM_SPEED));
    }
}
