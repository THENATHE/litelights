package com.thenathe.litelights.clouds.field;

/**
 * Hashing and 2D gradient noise for the cloud field. Everything here gives the same bits on every
 * machine: the gradients come from {@link StrictMath} once, and the noise itself is only adds,
 * multiplies and floors, so two players with the same seed see the same clouds.
 */
final class CloudNoise {
    private static final int GRADIENTS = 16;
    private static final double[] GRADIENT_X = new double[GRADIENTS];
    private static final double[] GRADIENT_Z = new double[GRADIENTS];
    /** 2D gradient noise with unit gradients peaks near 0.707; this brings it to about -1..1. */
    private static final double NOISE_SCALE = 1.0 / 0.7071;

    static {
        for (int i = 0; i < GRADIENTS; i++) {
            // Offset by half a step so no gradient lies exactly on an axis (axis-aligned streaks).
            double angle = (i + 0.5) * Math.PI * 2.0 / GRADIENTS;
            GRADIENT_X[i] = StrictMath.cos(angle);
            GRADIENT_Z[i] = StrictMath.sin(angle);
        }
    }

    private CloudNoise() {
    }

    /** SplitMix64's finaliser: every input bit reaches every output bit. */
    static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }

    static long hash(long seed, long a) {
        return mix(seed + 0x9E3779B97F4A7C15L * (a + 1));
    }

    static long hash(long seed, long a, long b) {
        return mix(hash(seed, a) + 0xC2B2AE3D27D4EB4FL * (b + 1));
    }

    static long hash(long seed, long a, long b, long c) {
        return mix(hash(seed, a, b) + 0x165667B19E3779F9L * (c + 1));
    }

    /** A hash as a number in [0, 1). */
    static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    /** The next independent hash in a sequence drawn from one seed. */
    static long next(long hash) {
        return mix(hash + 0x9E3779B97F4A7C15L);
    }

    /** Gradient noise, about -1..1, with features about one unit across. */
    static double gradient(long seed, double x, double z) {
        double floorX = Math.floor(x);
        double floorZ = Math.floor(z);
        long cellX = (long) floorX;
        long cellZ = (long) floorZ;
        double fx = x - floorX;
        double fz = z - floorZ;
        double n00 = corner(seed, cellX, cellZ, fx, fz);
        double n10 = corner(seed, cellX + 1, cellZ, fx - 1.0, fz);
        double n01 = corner(seed, cellX, cellZ + 1, fx, fz - 1.0);
        double n11 = corner(seed, cellX + 1, cellZ + 1, fx - 1.0, fz - 1.0);
        double u = fade(fx);
        double v = fade(fz);
        double south = n00 + (n10 - n00) * u;
        double north = n01 + (n11 - n01) * u;
        return (south + (north - south) * v) * NOISE_SCALE;
    }

    /**
     * Fractal noise: {@code octaves} layers of {@link #gradient}, each twice as fine and half as
     * strong as the one before, kept to about -1..1. Layers finer than {@code smallest} units are
     * left out, so far columns don't pay for detail smaller than what will be drawn.
     */
    static double fractal(long seed, double x, double z, int octaves, double smallest) {
        double total = 0.0;
        double weight = 1.0;
        double weights = 0.0;
        double frequency = 1.0;
        for (int octave = 0; octave < octaves; octave++) {
            if (octave > 0 && 1.0 / frequency < smallest) {
                break;
            }
            total += gradient(seed + octave * 0x632BE59BD9B4E019L, x * frequency, z * frequency) * weight;
            weights += weight;
            weight *= 0.5;
            // Slightly over two, so the layers' lattices never line up.
            frequency *= 2.03;
        }
        return total / weights;
    }

    /**
     * Billows: two layers of the noise folded at zero (its absolute value), the second about half
     * as wide, so rounded heads rise between sharp creases, 0..1, about 0.4 on average. The second
     * layer is left out where it would be under two columns wide ({@code smallest} the column's
     * width), as it would only speckle.
     *
     * @param size the heads' width, in blocks
     */
    static double billows(long seed, double x, double z, double size, double smallest) {
        double total = Math.abs(gradient(seed, x / size, z / size));
        double fineSize = size * 0.43;
        if (fineSize < smallest * 2.0) {
            return Math.min(1.0, total);
        }
        total = total * 0.62 + Math.abs(gradient(seed + 0x632BE59BD9B4E019L, x / fineSize, z / fineSize)) * 0.38;
        return Math.min(1.0, total);
    }

    private static double corner(long seed, long cellX, long cellZ, double dx, double dz) {
        int index = (int) (hash(seed, cellX, cellZ) >>> 60);
        return GRADIENT_X[index] * dx + GRADIENT_Z[index] * dz;
    }

    /** The quintic fade, so the noise's slope is continuous across lattice lines. */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }
}
