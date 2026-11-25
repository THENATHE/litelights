package com.thenathe.litelights.clouds.field;

/**
 * The lumps and holes of a layer of cloud (the sheet types and the anvil), in cloud space. How
 * much of the layer is cloud follows the amount asked for: the noise is turned into an even share
 * first, so an amount of 0.4 covers about 40 % of the sky whatever the noise's own spread.
 */
final class SheetLayer {
    private static final int OCTAVES = 4;
    private static final Quantiles QUANTILES = new Quantiles();

    private final CloudType.Sheet sheet;
    private final long seed;
    private final double cos;
    private final double sin;
    private final double alongSize;
    private final double acrossSize;

    /**
     * @param angle the formation's long axis, along which the lumps are stretched
     * @param scale multiplies the type's feature size (the formation's size scale)
     */
    SheetLayer(CloudType.Sheet sheet, long seed, float angle, double scale) {
        this.sheet = sheet;
        this.seed = seed;
        this.cos = StrictMath.cos(angle);
        this.sin = StrictMath.sin(angle);
        this.acrossSize = sheet.featureSize() * scale;
        this.alongSize = acrossSize * sheet.streak();
    }

    /** How solid the layer is here, 0..1, for an amount of 0..1 (the share of sky to cover). */
    double density(double cloudX, double cloudZ, double amount, double detail) {
        return density(share(cloudX, cloudZ, detail), amount);
    }

    /** Where this place falls in the layer's noise, as a share 0..1 of all places (higher is cloudier). */
    double share(double cloudX, double cloudZ, double detail) {
        double along = (cloudX * cos + cloudZ * sin) / alongSize;
        double across = (cloudZ * cos - cloudX * sin) / acrossSize;
        return QUANTILES.share(CloudNoise.fractal(seed, along, across, OCTAVES, detail / acrossSize));
    }

    /** How solid the layer is at a place's {@link #share}, for an amount of 0..1. */
    double density(double share, double amount) {
        // The cut runs from just above the highest share (amount 0: nothing) to just below the
        // lowest (amount 1: everything), so its soft band never lets cloud through where none is asked for.
        double softness = sheet.softness();
        double cut = 1.0 - amount * (1.0 + softness);
        return FieldMath.smoothstep(cut, cut + softness, share) * sheet.opacity();
    }

    /**
     * How far inside the layer's edge a place is, 0 where it turns solid to 1 a few times the
     * edge's width further in: the layer's top rounds off over that band, so a hole's rim is a
     * rounded shoulder rather than a cliff.
     */
    double inside(double share, double amount) {
        double softness = sheet.softness();
        double cut = 1.0 - amount * (1.0 + softness);
        return FieldMath.smoothstep(cut + softness * 0.5, cut + softness * 4.0, share);
    }

    /** The thickness here as a share of the layer's usual thickness, about 1 - v to 1 + v. */
    double thickness(double cloudX, double cloudZ) {
        double noise = CloudNoise.fractal(seed + 1, cloudX / (acrossSize * 2.0), cloudZ / (acrossSize * 2.0), 2, 0.0);
        return 1.0 + sheet.thicknessVariation() * noise;
    }

    /** The rounded heads on the layer's top, 0..1 (about 0.4 on average), in cloud space. */
    double billows(double cloudX, double cloudZ, double detail) {
        return CloudNoise.billows(seed + 3, cloudX, cloudZ, sheet.billowSize(), detail);
    }

    /** The long waves in the layer's base, -1..1. */
    double baseWaves(double cloudX, double cloudZ) {
        double size = acrossSize * 0.4;
        return CloudNoise.gradient(seed + 4, cloudX / size, cloudZ / size);
    }

    /** How far the ragged base hangs here, 0..1 of the type's most: a little almost everywhere, a lot in places. */
    double ragged(double cloudX, double cloudZ, double detail) {
        double share = QUANTILES.share(CloudNoise.fractal(seed + 2, cloudX / 48.0, cloudZ / 48.0, OCTAVES, detail / 48.0));
        return share * Math.sqrt(share);
    }

    /**
     * The fractal noise's spread, measured once: turns a noise value into the share of all values
     * below it, so a threshold on the share covers that share of the sky.
     */
    static final class Quantiles {
        private static final int BINS = 512;
        private static final double LOWEST = -1.0;
        private static final double HIGHEST = 1.0;
        private final double[] below = new double[BINS + 1];

        Quantiles() {
            int[] counts = new int[BINS];
            int samples = 0;
            for (int z = 0; z < 180; z++) {
                for (int x = 0; x < 180; x++) {
                    // Steps that fit no lattice, so the samples cover every part of a cell.
                    double value = CloudNoise.fractal(0x5EED5L, x * 0.413, z * 0.377, OCTAVES, 0.0);
                    counts[bin(value)]++;
                    samples++;
                }
            }
            double total = 0.0;
            for (int i = 0; i < BINS; i++) {
                below[i] = total / samples;
                total += counts[i];
            }
            below[BINS] = 1.0;
        }

        /** The share of noise values below {@code value}, 0..1. */
        double share(double value) {
            double position = (value - LOWEST) / (HIGHEST - LOWEST) * BINS;
            if (position <= 0.0) {
                return 0.0;
            }
            if (position >= BINS) {
                return 1.0;
            }
            int index = (int) position;
            return FieldMath.lerp(below[index], below[index + 1], position - index);
        }

        private static int bin(double value) {
            int index = (int) ((value - LOWEST) / (HIGHEST - LOWEST) * BINS);
            return Math.clamp(index, 0, BINS - 1);
        }
    }
}
