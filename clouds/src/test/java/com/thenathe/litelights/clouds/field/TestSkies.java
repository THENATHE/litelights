package com.thenathe.litelights.clouds.field;

import java.util.List;

/** Skies for the cloud field's tests. */
final class TestSkies {
    static final long SEED = 0x51CE5EEDL;
    static final float BASE = 192.0f;
    static final double REACH = 8_192.0;
    /** A day and a quarter into the world: every lattice slot around has formations of all ages. */
    static final double TICK = 30_000.0;

    private TestSkies() {
    }

    /** LiteLights' own sky around the origin. */
    static CloudField vanilla(double tick, float rain, float thunder) {
        return new CloudField(VanillaWeatherSky.sky(SEED, BASE, tick, rain, thunder, 0.0, 0.0, REACH));
    }

    /**
     * A sky of one formation of a type at the origin, at full size at {@link #TICK} (born half a
     * life before), with the type's usual heights, no wind and middling numbers.
     */
    static CloudField single(CloudType type, double radius, float coverage, float sizeScale, float strength) {
        CloudType.Heights heights = type.heights();
        float base = BASE + heights.base();
        float top = type.puffs() != null ? BASE + heights.top() : base + heights.top() - heights.base();
        double life = 40_000.0;
        Precipitation falls = type.precipitates() ? Precipitation.RAIN : Precipitation.NONE;
        CloudFormation formation = new CloudFormation(0x1234L, type, 0.0, 0.0, 0.0, 0.0, TICK - life / 2.0, life,
            0.2f, 0.25f, radius, 1.0f, 0.3f, coverage, base, top, sizeScale, strength, falls, falls == Precipitation.NONE ? 0.0f : 0.8f,
            0.8f, 1.0f);
        return new CloudField(new CloudSky(SEED, BASE, CloudWind.CALM, List.of(formation)));
    }

    /** The column at a world position, from a fresh one-column area. */
    static CloudColumn column(CloudField field, double tick, double worldX, double worldZ, double detail) {
        CloudColumn column = new CloudColumn();
        field.sampleWorld(tick, worldX, worldZ, detail, column);
        return column;
    }

    /**
     * Cloud in bands of the sky: for each band, the integral of {@link CloudColumn#densityAt} over
     * its height, sampled every block. It changes smoothly as clouds grow, even where a span's
     * edge crosses a height.
     */
    static double[] bands(CloudColumn column, float lowest, int bandCount, float bandHeight) {
        double[] amounts = new double[bandCount];
        for (int band = 0; band < bandCount; band++) {
            double sum = 0.0;
            for (int step = 0; step < bandHeight; step++) {
                sum += column.densityAt(lowest + band * bandHeight + step + 0.5f);
            }
            amounts[band] = sum;
        }
        return amounts;
    }
}
