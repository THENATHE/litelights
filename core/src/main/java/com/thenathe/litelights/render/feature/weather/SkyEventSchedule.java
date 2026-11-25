package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.config.PreviewEvent;
import com.thenathe.litelights.util.Hashing;

/**
 * Which nights bring an aurora, a meteor shower or distant fireworks, and when each firework goes
 * off. Everything is worked out from the world's hashed seed and the world's clocks, never from
 * wall time or a random generator with state, so the same night looks the same to every player on
 * the world and after a restart.
 *
 * <p>A "night" runs from noon to the next noon, so the evening and the small hours after midnight
 * belong to the same night. A meteor shower's streaks are drawn by the sky shader beside the
 * falling stars, from the same night number; this class only says which nights have one.
 *
 * <p>A fireworks night's show is cut into beats of game time. Each beat holds one volley at a
 * hashed moment in its first half: usually a single burst, now and then a quick pair or three.
 * The time between one volley and the next is a beat give or take half a beat, so the bursts
 * come irregularly with real pauses between them, and every volley stays inside its own beat.
 *
 * <p>The settings screen's preview asks for a {@link #preview} night instead: the one event, busier
 * than usual and turned toward where the player is looking.
 */
public final class SkyEventSchedule {
    public static final long DAY_TICKS = 24000L;
    /** On a real fireworks night one volley goes up in each beat of this many ticks (9 seconds). */
    public static final int FIREWORK_BEAT_TICKS = 180;
    /** How long a firework burst stays visible, in ticks. */
    public static final int FIREWORK_TICKS = 36;
    /** The most bursts in one volley. */
    public static final int MAX_VOLLEY = 3;
    /** The bursts of a volley follow each other this many ticks apart, give or take. */
    static final int VOLLEY_SPACING_MIN = 8;
    static final int VOLLEY_SPACING_MAX = 20;

    static final double AURORA_CHANCE = 0.7;
    static final double METEOR_SHOWER_CHANCE = 1.0 / 5.0;
    static final double FIREWORKS_CHANCE = 1.0 / 6.0;
    /** How often a volley is a quick pair, and how often three. */
    static final double PAIR_CHANCE = 0.18;
    static final double TRIPLE_CHANCE = 0.07;
    /** Fireworks only in the evening part of the night, in world clock time of day. */
    static final long FIREWORKS_FROM = 13_000L;
    static final long FIREWORKS_UNTIL = 18_000L;
    /** A previewed show's beats are this much shorter, so the preview is busier but still has pauses. */
    static final float PREVIEW_PACE = 2.0f;
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
     * {@code pace} shortens the fireworks' beats (1 on a real night).
     */
    public record Night(long index, float auroraStrength, boolean meteorShower, boolean fireworks, float fireworksAzimuth,
                        float auroraBearing, float fireworksLift, float pace) {
    }

    public static Night night(long seed, long nightIndex) {
        long hash = mix(seed, nightIndex);
        double auroraRoll = unit(hash);
        float auroraStrength = auroraRoll < AURORA_CHANCE ? (float) (0.45 + 0.55 * unit(mix(hash, 11))) : 0.0f;
        boolean meteors = unit(mix(hash, 1)) < METEOR_SHOWER_CHANCE;
        boolean fireworks = unit(mix(hash, 2)) < FIREWORKS_CHANCE;
        float fireworksAzimuth = (float) (unit(mix(hash, 5)) * Math.PI * 2.0);
        return new Night(nightIndex, auroraStrength, meteors, fireworks, fireworksAzimuth, 0.0f, 0.0f, 1.0f);
    }

    /**
     * The settings screen's preview of one event: tonight's timings, but only that event,
     * busier, and placed where the player is looking ({@code bearing}, {@code elevation} in radians)
     * so it shows at once in front of them, whichever way they face.
     */
    public static Night preview(long nightIndex, PreviewEvent.Event kind, float bearing, float elevation) {
        float fireworksLift = (float) Math.clamp(elevation, 0.0, Math.toRadians(18.0));
        return new Night(nightIndex,
            kind == PreviewEvent.Event.AURORA ? PREVIEW_AURORA : 0.0f,
            kind == PreviewEvent.Event.METEOR_SHOWER,
            kind == PreviewEvent.Event.FIREWORKS, bearing, bearing, fireworksLift, PREVIEW_PACE);
    }

    /** Whether a fireworks night's show is on at this time of day. */
    public static boolean fireworksHour(long clockTicks) {
        long timeOfDay = Math.floorMod(clockTicks, DAY_TICKS);
        return timeOfDay >= FIREWORKS_FROM && timeOfDay < FIREWORKS_UNTIL;
    }

    /** How long one beat of the night's show is, in ticks: one volley each. */
    public static int beatTicks(Night night) {
        return Math.round(FIREWORK_BEAT_TICKS / night.pace());
    }

    /** How many bursts the volley of a beat has: 1, or now and then 2 or 3. */
    public static int volleySize(long seed, Night night, long beat) {
        double roll = unit(mix(volleyHash(seed, night, beat), 1));
        if (roll < TRIPLE_CHANCE) {
            return 3;
        }
        return roll < TRIPLE_CHANCE + PAIR_CHANCE ? 2 : 1;
    }

    /**
     * The game tick a burst of a beat's volley goes off: the volley starts somewhere in the first
     * half of the beat and its later bursts follow quickly. The last one still goes off at least a
     * second before the beat ends, so a volley never runs into the next.
     */
    public static long burstTick(long seed, Night night, long beat, int burst) {
        long hash = volleyHash(seed, night, beat);
        int beatTicks = beatTicks(night);
        // In a short preview beat, the volley starts early enough to leave a second's pause before the next.
        int latestStart = Math.min(beatTicks / 2, beatTicks - (MAX_VOLLEY - 1) * VOLLEY_SPACING_MAX - 20);
        long tick = beat * beatTicks + (long) Math.floor(unit(mix(hash, 2)) * latestStart);
        for (int follower = 1; follower <= burst; follower++) {
            tick += VOLLEY_SPACING_MIN + (long) Math.floor(unit(mix(hash, 2 + follower)) * (VOLLEY_SPACING_MAX - VOLLEY_SPACING_MIN + 1));
        }
        return tick;
    }

    /**
     * One burst of a beat's volley: fills {@code out} with its azimuth and elevation (radians), its
     * size (radians), a colour index (0 to 5) and its spark pattern seed.
     */
    public static void firework(long seed, Night night, long beat, int burst, float[] out) {
        long hash = mix(volleyHash(seed, night, beat) ^ 0x62757273L, burst);
        // Low over the horizon in one direction, as if a town far away were celebrating.
        out[0] = (float) (night.fireworksAzimuth() + (unit(mix(hash, 1)) - 0.5) * 0.7);
        out[1] = (float) Math.toRadians(7.0 + 14.0 * unit(mix(hash, 2))) + night.fireworksLift();
        out[2] = (float) Math.toRadians(3.6 + 3.0 * unit(mix(hash, 3)));
        out[3] = (float) Math.floor(unit(mix(hash, 4)) * 6.0);
        out[4] = (float) (mix(hash, 5) & 0xFFFF);
    }

    /**
     * How many ticks a previewed show runs behind the game when it is chosen at {@code chosenTick},
     * so that the first volley of its schedule goes off {@code lead} ticks later.
     */
    public static long previewDelay(long seed, Night night, long chosenTick, int lead) {
        return chosenTick - burstTick(seed, night, 0, 0) + lead;
    }

    private static long volleyHash(long seed, Night night, long beat) {
        return mix(mix(seed, night.index()) ^ 0x66697265L, beat);
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
