package com.thenathe.litelights.render.feature.particles;

import net.minecraft.util.RandomSource;

/**
 * Where haze puffs (3D puffs and Classic billboards) spawn when the camera moves fast (fog098). A
 * puff lives ten seconds or more, so a sprinting or flying player outruns the haze spawned around
 * them and sees thin air ahead. Moving, the spawn area's centre leads the camera along its travel and
 * a share of the puffs is placed in a wedge ahead, both growing with speed; flying high, the ring on
 * the ground below widens, so a look down finds a patch of mist rather than a dot of it.
 *
 * <p>Standing still none of this applies and no extra random number is drawn, so each style spawns
 * exactly as before.
 */
public final class HazeLead {
    /** The spawn area's centre runs this many ticks of travel ahead of the camera... */
    private static final float LEAD_TICKS = 30.0f;
    /** ...but never further than this, in blocks. */
    private static final float LONGEST_LEAD = 24.0f;
    /** Speed (blocks a tick) at which the most puffs go ahead: about flying in spectator mode. */
    private static final float FULL_SPEED = 0.8f;
    /** At most this share of the puffs is placed in the wedge ahead. */
    private static final float MOST_AHEAD = 0.6f;
    /** Half the wedge's width, in radians. */
    private static final float WEDGE = (float) Math.toRadians(65.0);
    /** Blocks above the ground before the ring starts to widen, and how much it widens per block above that. */
    private static final float WIDEN_FROM = 8.0f;
    private static final float WIDEN_PER_BLOCK = 0.5f;
    private static final float WIDEST = 36.0f;

    private HazeLead() {
    }

    /** The camera's speed over the ground, in blocks a tick. */
    public static float speed(Surroundings s) {
        return (float) Math.sqrt(s.motionX * s.motionX + s.motionZ * s.motionZ);
    }

    /** How far the spawn area's centre leads the camera along its travel, in blocks (x, z into {@code into}). */
    public static void lead(Surroundings s, double[] into) {
        float speed = speed(s);
        float scale = speed <= 1.0e-4f ? 0.0f : Math.min(LEAD_TICKS, LONGEST_LEAD / speed);
        into[0] = s.motionX * scale;
        into[1] = s.motionZ * scale;
    }

    /** The share of puffs placed ahead at this speed: none standing still or walking slowly. */
    static float aheadShare(float speed) {
        return Math.clamp((speed - 0.08f) / (FULL_SPEED - 0.08f), 0.0f, 1.0f) * MOST_AHEAD;
    }

    /**
     * A direction for one puff around the spawn area's centre, in radians: anywhere round the
     * circle, or for a share of puffs that grows with speed, within the wedge ahead.
     */
    public static float angle(Surroundings s, RandomSource random) {
        float speed = speed(s);
        float share = aheadShare(speed);
        if (share > 0.0f && random.nextFloat() < share) {
            float heading = (float) Math.atan2(s.motionZ, s.motionX);
            return heading + (random.nextFloat() * 2.0f - 1.0f) * WEDGE;
        }
        return random.nextFloat() * (float) (Math.PI * 2.0);
    }

    /** Extra blocks on the spawn ring's outer edge for a camera this high over the ground. */
    public static float widening(double heightAboveGround) {
        return (float) Math.clamp((heightAboveGround - WIDEN_FROM) * WIDEN_PER_BLOCK, 0.0, WIDEST);
    }
}
