package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.config.PreviewEvent;
import com.thenathe.litelights.util.Hashing;

/**
 * Which nights bring an aurora, a meteor shower or distant fireworks, and when each meteor and
 * firework goes off. Everything is worked out from the world's hashed seed and the world's clocks,
 * never from wall time or a random generator with state, so the same night looks the same to
 * every player on the world and after a restart.
 *
 * <p>A "night" runs from noon to the next noon, so the evening and the small hours after midnight
 * belong to the same night. Within a night, events are placed in fixed slots of game time; each
 * slot holds at most one meteor or one firework burst, decided by a hash of the slot.
 *
 * <p>The settings screen's preview asks for a {@link #preview} night instead: the one event, busier
 * than usual and turned toward where the player is looking.
 */
public final class SkyEventSchedule {
    public static final long DAY_TICKS = 24000L;
    /** A meteor may start in each slot of this many ticks during a shower. */
    public static final int METEOR_SLOT_TICKS = 20;
    /** A firework may burst in each slot of this many ticks during a fireworks night. */
    public static final int FIREWORK_SLOT_TICKS = 16;
    /** How long a meteor and a firework burst stay visible, in ticks. */
    public static final int METEOR_TICKS = 22;
    public static final int FIREWORK_TICKS = 36;

    static final double AURORA_CHANCE = 0.7;
    static final double METEOR_SHOWER_CHANCE = 1.0 / 5.0;
    static final double FIREWORKS_CHANCE = 1.0 / 6.0;
    static final double METEOR_CHANCE_PER_SLOT = 0.45;
    static final double FIREWORK_CHANCE_PER_SLOT = 0.6;
    /** One meteor in this many is a fireball: wider, brighter and with a green head. */
    static final double FIREBALL_CHANCE = 0.1;
    /** Fireworks only in the evening part of the night, in world clock time of day. */
    static final long FIREWORKS_FROM = 13_000L;
    static final long FIREWORKS_UNTIL = 18_000L;
    /** A previewed event is this much busier, so something shows within a second of choosing it. */
    static final float PREVIEW_PACE = 1.5f;
    static final float PREVIEW_AURORA = 0.85f;

    private SkyEventSchedule() {
    }

    /** The night a world clock time belongs to (noon to noon). */
    public static long nightIndex(long clockTicks) {
        return Math.floorDiv(clockTicks - DAY_TICKS / 4, DAY_TICKS);
    }

    /**
     * What one night brings. Azimuths are radians from north (toward -z) turning east (toward +x).
     * {@code auroraBearing} is where the aurora's curtains face (north on a real night),
     * {@code fireworksLift} raises the bursts above their usual place low over the horizon, and
     * {@code pace} scales how many meteors and bursts there are (1 on a real night).
     */
    public record Night(long index, float auroraStrength, boolean meteorShower, float radiantAzimuth, float radiantElevation,
                        boolean fireworks, float fireworksAzimuth, float auroraBearing, float fireworksLift, float pace) {
    }

    public static Night night(long seed, long nightIndex) {
        long hash = mix(seed, nightIndex);
        double auroraRoll = unit(hash);
        float auroraStrength = auroraRoll < AURORA_CHANCE ? (float) (0.45 + 0.55 * unit(mix(hash, 11))) : 0.0f;
        boolean meteors = unit(mix(hash, 1)) < METEOR_SHOWER_CHANCE;
        boolean fireworks = unit(mix(hash, 2)) < FIREWORKS_CHANCE;
        float radiantAzimuth = (float) (unit(mix(hash, 3)) * Math.PI * 2.0);
        float radiantElevation = (float) Math.toRadians(35.0 + 40.0 * unit(mix(hash, 4)));
        float fireworksAzimuth = (float) (unit(mix(hash, 5)) * Math.PI * 2.0);
        return new Night(nightIndex, auroraStrength, meteors, radiantAzimuth, radiantElevation, fireworks, fireworksAzimuth,
            0.0f, 0.0f, 1.0f);
    }

    /**
     * The settings screen's preview of one event: tonight's timings, but only that event,
     * busier, and placed where the player is looking ({@code bearing}, {@code elevation} in radians)
     * so it shows at once in front of them, whichever way they face.
     */
    public static Night preview(long nightIndex, PreviewEvent.Event kind, float bearing, float elevation) {
        float radiantElevation = (float) Math.clamp(elevation + Math.toRadians(12.0), Math.toRadians(25.0), Math.toRadians(60.0));
        float fireworksLift = (float) Math.clamp(elevation, 0.0, Math.toRadians(18.0));
        return new Night(nightIndex,
            kind == PreviewEvent.Event.AURORA ? PREVIEW_AURORA : 0.0f,
            kind == PreviewEvent.Event.METEOR_SHOWER, bearing, radiantElevation,
            kind == PreviewEvent.Event.FIREWORKS, bearing, bearing, fireworksLift, PREVIEW_PACE);
    }

    /** Whether a fireworks night's show is on at this time of day. */
    public static boolean fireworksHour(long clockTicks) {
        long timeOfDay = Math.floorMod(clockTicks, DAY_TICKS);
        return timeOfDay >= FIREWORKS_FROM && timeOfDay < FIREWORKS_UNTIL;
    }

    /**
     * A meteor in a slot, if there is one: fills {@code out} with the start azimuth and elevation
     * (radians), how far it travels (radians), its heading, its brightness and 1 for a fireball
     * (else 0), and returns true. Meteors of a shower all travel away from the night's radiant.
     */
    public static boolean meteor(long seed, Night night, long slot, float[] out) {
        long hash = mix(mix(seed, night.index()) ^ 0x6D65746FL, slot);
        if (unit(hash) >= Math.min(0.9, METEOR_CHANCE_PER_SLOT * night.pace())) {
            return false;
        }
        // Start a little away from the radiant, in a random direction around it, and move outward.
        double around = unit(mix(hash, 1)) * Math.PI * 2.0;
        double away = Math.toRadians(8.0 + 30.0 * unit(mix(hash, 2)));
        out[0] = (float) (night.radiantAzimuth() + Math.cos(around) * away / Math.max(0.3, Math.cos(night.radiantElevation())));
        out[1] = (float) Math.max(Math.toRadians(12.0), night.radiantElevation() + Math.sin(around) * away);
        out[2] = (float) Math.toRadians(14.0 + 20.0 * unit(mix(hash, 3)));
        out[3] = (float) around;
        out[4] = (float) (0.75 + 0.25 * unit(mix(hash, 4)));
        out[5] = unit(mix(hash, 6)) < FIREBALL_CHANCE ? 1.0f : 0.0f;
        return true;
    }

    /**
     * A firework burst in a slot, if there is one: fills {@code out} with its azimuth and
     * elevation (radians), its size (radians), a colour index (0 to 5), and its spark pattern seed,
     * and returns true.
     */
    public static boolean firework(long seed, Night night, long slot, float[] out) {
        long hash = mix(mix(seed, night.index()) ^ 0x66697265L, slot);
        if (unit(hash) >= Math.min(0.9, FIREWORK_CHANCE_PER_SLOT * night.pace())) {
            return false;
        }
        // Low over the horizon in one direction, as if a town far away were celebrating.
        out[0] = (float) (night.fireworksAzimuth() + (unit(mix(hash, 1)) - 0.5) * 0.7);
        out[1] = (float) Math.toRadians(7.0 + 14.0 * unit(mix(hash, 2))) + night.fireworksLift();
        out[2] = (float) Math.toRadians(6.0 + 5.0 * unit(mix(hash, 3)));
        out[3] = (float) Math.floor(unit(mix(hash, 4)) * 6.0);
        out[4] = (float) (mix(hash, 5) & 0xFFFF);
        return true;
    }

    /** A well-mixed 64-bit hash of two values (SplitMix64's finaliser over a combination). */
    public static long mix(long a, long b) {
        return Hashing.mix64(a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L);
    }

    /** The top 53 bits of a hash as a number in [0, 1). */
    public static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }
}
