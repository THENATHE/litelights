package com.thenathe.litelights.render.feature.water;

/**
 * The shore field's distance and swell strength as {@code shaders/include/water/common.glsl} reads
 * them at a point ({@code lite_shore_field}, its blend between column centres,
 * {@code lite_shore_near_distance}, exact within a block of the waterline, and
 * {@code lite_shore_corner_strength}), from a window of {@link ShoreField} texels. Points are in
 * columns from the window's corner. Keep the two in step.
 */
final class ShoreDistanceMirror {
    private final int[] texels;

    ShoreDistanceMirror(int[] texels) {
        this.texels = texels;
    }

    private int texel(int x, int z) {
        return texels[(x & (ShoreField.SIZE - 1)) + (z & (ShoreField.SIZE - 1)) * ShoreField.SIZE];
    }

    private float sample(int x, int z) {
        return ShoreField.distance(texel(x, z));
    }

    private float swell(int x, int z) {
        return ShoreField.swell(texel(x, z));
    }

    /** The distance blended between the four column centres around the point, as before corners. */
    float blended(double x, double z) {
        return blend(x, z, false);
    }

    /** The swell's strength blended the same way, as before corners. */
    float blendedStrength(double x, double z) {
        return blend(x, z, true);
    }

    private float blend(double x, double z, boolean strength) {
        double localX = x - 0.5;
        double localZ = z - 0.5;
        int baseX = (int) Math.floor(localX);
        int baseZ = (int) Math.floor(localZ);
        float fx = (float) (localX - baseX);
        float fz = (float) (localZ - baseZ);
        float[] corners = new float[4];
        for (int i = 0; i < 4; i++) {
            int cx = baseX + (i & 1);
            int cz = baseZ + (i >> 1);
            corners[i] = strength ? swell(cx, cz) : sample(cx, cz);
        }
        return mix(mix(corners[0], corners[1], fx), mix(corners[2], corners[3], fx), fz);
    }

    /** The swell's strength the waves use: the blend, following the blocks round corners. */
    float strength(double x, double z) {
        float blended = blendedStrength(x, z);
        int baseX = (int) Math.floor(x - 0.5);
        int baseZ = (int) Math.floor(z - 0.5);
        int landColumns = 0;
        float onLand = 0.0f;
        float onWater = 0.0f;
        boolean[] land = new boolean[4];
        for (int i = 0; i < 4; i++) {
            int cx = baseX + (i & 1);
            int cz = baseZ + (i >> 1);
            land[i] = sample(cx, cz) < 0.0f;
            if (land[i]) {
                landColumns++;
                onLand += swell(cx, cz);
            } else {
                onWater += swell(cx, cz);
            }
        }
        boolean corner = landColumns == 1 || landColumns == 3 || (landColumns == 2 && land[0] == land[3]);
        if (!corner) {
            return blended;
        }
        float across = Math.clamp(distance(x, z) + 0.5f, 0.0f, 1.0f);
        return mix(onLand / landColumns, onWater / (4 - landColumns), across);
    }

    /** The distance the waves use: the blend, made exact near the waterline. */
    float distance(double x, double z) {
        float blended = blended(x, z);
        if (Math.abs(blended) > 2.0f) {
            return blended;
        }
        int ownX = (int) Math.floor(x);
        int ownZ = (int) Math.floor(z);
        float fx = (float) (x - ownX);
        float fz = (float) (z - ownZ);
        boolean onLand = sample(ownX, ownZ) < 0.0f;
        float nearest = 2.0f;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                boolean land = sample(ownX + dx, ownZ + dz) < 0.0f;
                if (land != onLand) {
                    float outsideX = Math.max(Math.abs(fx - dx - 0.5f) - 0.5f, 0.0f);
                    float outsideZ = Math.max(Math.abs(fz - dz - 0.5f) - 0.5f, 0.0f);
                    nearest = Math.min(nearest, (float) Math.sqrt(outsideX * outsideX + outsideZ * outsideZ));
                }
            }
        }
        float exact = onLand ? -nearest : nearest;
        return mix(exact, blended, smoothstep(0.75f, 1.0f, nearest));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
