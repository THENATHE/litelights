package com.thenathe.litelights.render.feature.water;

import java.util.Arrays;

/**
 * The state of the sea for the ocean waves (Ocean waves, Experimental), worked out once a frame
 * on the CPU: how rough it is, and the trains of swell the shader adds up.
 *
 * <p><b>Out at sea</b> the swell travels with LiteLights' wind (particles-weather's {@code Wind}, the
 * one rain, snow and swaying plants follow). The wind's heading turns continuously, but a wave
 * pattern can't: turning a pattern of long crests about the anchor would sweep the crests far from
 * it sideways, and its wave vector must stay a whole multiple of 2&pi;/2048 to repeat with the
 * 2048-block anchor. So the swell is made of trains in sixteen fixed directions, each a whole
 * multiple, and the wind picks the two or three nearest its heading with weights that change
 * smoothly as it turns: the sea turns by crossfading, never jumps.
 *
 * <p><b>Near the shore</b> the shader turns the waves to come straight in (from the shore field's
 * distance, {@link ShoreField}); those crests keep the swell's own frequency, as refracting waves
 * do, at a shorter wavelength.
 *
 * <p><b>Time.</b> Each train's phase is advanced here, in doubles, by a clock that runs faster in
 * rough weather, so a change of speed never makes the pattern jump. The clock wraps at an hour, and
 * every train's frequency is a whole number of cycles an hour, so the wrap is seamless too.
 *
 * <p>No Minecraft types here, so it is unit-tested.
 */
public final class OceanSwell {
    public static final int TRAINS = 4;
    private static final int DIRECTIONS = 16;
    /** A direction joins the swell when the wind is within this of it, in radians. */
    private static final double SPREAD = Math.toRadians(30.0);
    private static final double WAVELENGTH = 12.0;
    private static final double SHORE_WAVELENGTH = 8.0;
    /** Seconds between crests at the calmest, before the rough-weather speed-up. */
    private static final double PERIOD = 4.8;
    private static final double ANCHOR_PERIOD = 2048.0;
    private static final double CLOCK_WRAP = 3600.0;
    private static final double TAU = Math.PI * 2.0;
    /** The shore phase counts whole waves up to this many, for the shader's per-wave run-up. */
    private static final double SHORE_PHASE_WRAP = TAU * 1024.0;
    private static final double EASE_SECONDS = 8.0;
    /** A frame longer than this (a pause, a hitch) moves the sea only this far. */
    private static final double LONGEST_STEP = 0.25;

    private static final double[] WAVE_X = new double[DIRECTIONS];
    private static final double[] WAVE_Z = new double[DIRECTIONS];
    private static final double[] FREQUENCY = new double[DIRECTIONS];
    private static final double[] PHASE_OFFSET = new double[DIRECTIONS];
    private static final double SHORE_FREQUENCY = TAU * Math.round(CLOCK_WRAP / PERIOD) / CLOCK_WRAP;

    static {
        for (int direction = 0; direction < DIRECTIONS; direction++) {
            double angle = direction * TAU / DIRECTIONS;
            // Each direction a little longer or shorter, so the trains never line up in step.
            double wavelength = WAVELENGTH * (0.92 + 0.16 * fraction(direction * 0.6180339887));
            WAVE_X[direction] = Math.round(Math.cos(angle) * ANCHOR_PERIOD / wavelength) * TAU / ANCHOR_PERIOD;
            WAVE_Z[direction] = Math.round(Math.sin(angle) * ANCHOR_PERIOD / wavelength) * TAU / ANCHOR_PERIOD;
            // Deep water: the period grows with the square root of the wavelength.
            double period = PERIOD * Math.sqrt(wavelength / WAVELENGTH);
            FREQUENCY[direction] = TAU * Math.round(CLOCK_WRAP / period) / CLOCK_WRAP;
            PHASE_OFFSET[direction] = TAU * fraction(direction * 0.3819660113);
        }
    }

    private final float[] trains = new float[TRAINS * 4];
    private final double[] weights = new double[DIRECTIONS];
    private boolean started;
    private double clock;
    private double shorePhase;
    private float seaState;
    private float windX = 1.0f;
    private float windZ;

    /**
     * Moves the sea on by one frame.
     *
     * @param heading    the wind's heading in radians (x its cosine, z its sine)
     * @param calmSpeed  the wind's speed without storms, in blocks per tick
     * @param windSpeed  the wind's speed with the storms there are now
     * @param dayTicks   the time of day, 0 at sunrise to 24000
     * @param seconds    real time since the last frame
     */
    public void update(double heading, double calmSpeed, double windSpeed, long dayTicks, double seconds) {
        float target = targetSeaState(calmSpeed, windSpeed, dayTicks);
        double step = Math.clamp(seconds, 0.0, LONGEST_STEP);
        if (!started) {
            started = true;
            seaState = target;
        } else {
            seaState += (float) ((target - seaState) * (1.0 - Math.exp(-step / EASE_SECONDS)));
        }
        double advance = step * (0.8 + 0.5 * seaState);
        clock = (clock + advance) % CLOCK_WRAP;
        shorePhase = (shorePhase + advance * SHORE_FREQUENCY) % SHORE_PHASE_WRAP;
        windX = (float) Math.cos(heading);
        windZ = (float) Math.sin(heading);
        fillTrains(heading);
    }

    /**
     * How rough the sea should be, 0 (glassy) to 1 (a thunderstorm), from the wind. The calm part of
     * the wind drops at dawn and picks up through the afternoon, as a sea breeze does; storms add
     * their strength at any hour.
     */
    static float targetSeaState(double calmSpeed, double windSpeed, long dayTicks) {
        double sinceAfternoon = Math.floorMod(dayTicks - 9000L, 24000L) / 24000.0;
        double breeze = 0.75 + 0.25 * Math.cos(sinceAfternoon * TAU);
        double speed = calmSpeed * breeze + Math.max(0.0, windSpeed - calmSpeed);
        return (float) Math.clamp((speed - 0.012) / 0.17, 0.04, 1.0);
    }

    private void fillTrains(double heading) {
        Arrays.fill(trains, 0.0f);
        double sumOfSquares = 0.0;
        for (int direction = 0; direction < DIRECTIONS; direction++) {
            double apart = Math.abs(Math.IEEEremainder(direction * TAU / DIRECTIONS - heading, TAU));
            weights[direction] = Math.max(0.0, 1.0 - apart / SPREAD);
            sumOfSquares += weights[direction] * weights[direction];
        }
        // Weighted so the sea's overall height stays the same however many trains share it.
        double scale = 1.0 / Math.sqrt(sumOfSquares);
        int train = 0;
        for (int direction = 0; direction < DIRECTIONS && train < TRAINS; direction++) {
            if (weights[direction] <= 0.0) {
                continue;
            }
            double phase = (FREQUENCY[direction] * clock + PHASE_OFFSET[direction]) % TAU;
            trains[train * 4] = (float) WAVE_X[direction];
            trains[train * 4 + 1] = (float) WAVE_Z[direction];
            trains[train * 4 + 2] = (float) phase;
            trains[train * 4 + 3] = (float) (weights[direction] * scale);
            train++;
        }
    }

    /** Train {@code index}: x, z its wave vector in radians per block, its phase now, and its share of the swell (0 = unused). */
    public float trainValue(int index, int component) {
        return trains[index * 4 + component];
    }

    public float seaState() {
        return seaState;
    }

    /** The shore waves' phase now, counting whole waves up to 1024. */
    public float shorePhase() {
        return (float) shorePhase;
    }

    /** The shore waves' wave number, in radians per block. */
    public static float shoreWavenumber() {
        return (float) (TAU / SHORE_WAVELENGTH);
    }

    public float windX() {
        return windX;
    }

    public float windZ() {
        return windZ;
    }

    /** Back to the start, as after leaving a world. */
    public void reset() {
        started = false;
        clock = 0.0;
        shorePhase = 0.0;
    }

    private static double fraction(double value) {
        return value - Math.floor(value);
    }
}
