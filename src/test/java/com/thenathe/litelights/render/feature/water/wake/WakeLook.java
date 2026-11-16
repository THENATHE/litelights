package com.thenathe.litelights.render.feature.water.wake;

/**
 * The water shader's reading and drawing of the wake field (shaders/include/water/common.glsl,
 * {@code lite_wake}, and surface.glsl, {@code lite_wake_colour}), in Java, for tests and the offline
 * prototype. Keep the two in step. The water's own colour is a fixed lake blue seen from above, so
 * only the wake changes the picture.
 */
final class WakeLook {
    static final double TILT = 0.6;
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final double[] WATER = {0.17, 0.33, 0.62};
    private static final double[] FOAM = {0.90, 0.95, 1.0};
    /** Toward the light, flattened onto the water (the sun from the south-east in the morning). */
    private static final double LIGHT_X = 0.55;
    private static final double LIGHT_Z = 0.35;

    /** height, slope x, slope z, foam, reach. */
    static double[] sample(WakeField field, double worldX, double worldZ, double partialTick) {
        double localX = worldX * WakeField.CELLS_PER_BLOCK - 0.5;
        double localZ = worldZ * WakeField.CELLS_PER_BLOCK - 0.5;
        int baseX = (int) Math.floor(localX);
        int baseZ = (int) Math.floor(localZ);
        int inWindowX = baseX - field.originBlockX() * WakeField.CELLS_PER_BLOCK;
        int inWindowZ = baseZ - field.originBlockZ() * WakeField.CELLS_PER_BLOCK;
        if (inWindowX < 0 || inWindowZ < 0 || inWindowX > WakeField.SIZE - 2 || inWindowZ > WakeField.SIZE - 2) {
            return new double[5];
        }
        double fx = localX - baseX;
        double fz = localZ - baseZ;
        double[] a = values(field, baseX, baseZ, partialTick);
        double[] b = values(field, baseX + 1, baseZ, partialTick);
        double[] c = values(field, baseX, baseZ + 1, partialTick);
        double[] d = values(field, baseX + 1, baseZ + 1, partialTick);
        double height = mix(mix(a[0], b[0], fx), mix(c[0], d[0], fx), fz);
        double slopeX = mix(b[0] - a[0], d[0] - c[0], fz) * WakeField.CELLS_PER_BLOCK;
        double slopeZ = mix(c[0] - a[0], d[0] - b[0], fx) * WakeField.CELLS_PER_BLOCK;
        double foam = mix(mix(a[1], b[1], fx), mix(c[1], d[1], fx), fz);
        int toEdge = Math.min(Math.min(inWindowX, inWindowZ), Math.min(WakeField.SIZE - 2 - inWindowX, WakeField.SIZE - 2 - inWindowZ));
        double reach = smoothstep(0.0, 24.0, toEdge);
        return new double[] {height, slopeX, slopeZ, foam, reach};
    }

    /** The colour of the texel at a world position, 0xRRGGBB. */
    static int colour(WakeField field, double worldX, double worldZ, int texelsPerBlock) {
        double[] wake = sample(field, worldX, worldZ, 1.0);
        int texelX = (int) Math.floor(worldX * texelsPerBlock);
        int texelZ = (int) Math.floor(worldZ * texelsPerBlock);
        double dither = (BAYER[(texelX & 3) + (texelZ & 3) * 4] + 0.5) / 16.0;
        double lit = Math.clamp(0.5 + wake[0] * 2.0 - (wake[1] * LIGHT_X + wake[2] * LIGHT_Z) * 0.9, 0.0, 1.0);
        double bands = Math.floor(lit * 4.0 + dither) / 4.0;
        double shade = 1.0 + (bands - 0.5) * 0.5 * wake[4];
        double[] seen = {WATER[0] * shade, WATER[1] * shade, WATER[2] * shade};
        double lace = valueNoise(worldX * 3.0, worldZ * 3.0);
        double amount = wake[3] * 1.4 - 0.25 + (lace - 0.5) * 0.6;
        double foamTexel = amount >= 0.3 + 0.4 * dither ? 1.0 : 0.0;
        double cover = foamTexel * 0.9 * wake[4];
        int rgb = 0;
        for (int channel = 0; channel < 3; channel++) {
            double value = seen[channel] + (FOAM[channel] * 0.9 - seen[channel]) * cover;
            rgb = rgb << 8 | (int) Math.round(Math.clamp(value, 0.0, 1.0) * 255.0);
        }
        return rgb;
    }

    private static double[] values(WakeField field, int cellX, int cellZ, double partialTick) {
        int texel = field.texels()[(cellX & (WakeField.SIZE - 1)) + (cellZ & (WakeField.SIZE - 1)) * WakeField.SIZE];
        double now = ((texel & 255) - 128) / WakeField.HEIGHT_STEPS;
        double before = ((texel >> 8 & 255) - 128) / WakeField.HEIGHT_STEPS;
        double foamNow = (texel >> 16 & 255) / 255.0;
        double foamBefore = (texel >>> 24) / 255.0;
        return new double[] {mix(before, now, partialTick), mix(foamBefore, foamNow, partialTick)};
    }

    private static double valueNoise(double x, double z) {
        int cellX = (int) Math.floor(x);
        int cellZ = (int) Math.floor(z);
        double fx = x - cellX;
        double fz = z - cellZ;
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);
        double a = WakeField.hash(cellX, cellZ, 0);
        double b = WakeField.hash(cellX + 1, cellZ, 0);
        double c = WakeField.hash(cellX, cellZ + 1, 0);
        double d = WakeField.hash(cellX + 1, cellZ + 1, 0);
        return mix(mix(a, b, sx), mix(c, d, sx), sz);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
