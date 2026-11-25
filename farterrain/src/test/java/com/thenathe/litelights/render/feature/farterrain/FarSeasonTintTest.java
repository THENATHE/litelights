package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Which far colours far snow's season tint takes for grass and leaves (lite_far_season_tint in
 * shaders/include/farterrain/far_snow.glsl), mirrored here: vanilla's grass and foliage colours and
 * Almanac's seasonal ones are, sand, dirt, wood, stone, spruce and water aren't. Keep the mirror in
 * step with the shader.
 */
class FarSeasonTintTest {
    // far_snow.glsl's LITE_FAR_TINT_* constants.
    private static final double[] HUES = {12.0, 18.0, 110.0, 118.0};
    private static final double[] LEAST_SATURATION = {0.6, 0.42, 0.22};
    private static final double[] SATURATION_HUES = {40.0, 50.0, 58.0, 66.0};

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double window(double value, double[] edges) {
        return smoothstep(edges[0], edges[1], value) * (1.0 - smoothstep(edges[2], edges[3], value));
    }

    private static double saturation(double[] c) {
        double most = Math.max(c[0], Math.max(c[1], c[2]));
        return most > 1.0e-3 ? (most - Math.min(c[0], Math.min(c[1], c[2]))) / most : 0.0;
    }

    private static double hue(double[] c) {
        double most = Math.max(c[0], Math.max(c[1], c[2]));
        double spread = most - Math.min(c[0], Math.min(c[1], c[2]));
        if (spread < 1.0e-4) {
            return 0.0;
        }
        if (most == c[0]) {
            double h = (c[1] - c[2]) / spread;
            return 60.0 * (h - 6.0 * Math.floor(h / 6.0));
        }
        if (most == c[1]) {
            return 60.0 * ((c[2] - c[0]) / spread + 2.0);
        }
        return 60.0 * ((c[0] - c[1]) / spread + 4.0);
    }

    /** The shader's share of the tint for an unlit colour, before the colours' alpha. */
    private static double plant(double[] albedo) {
        double h = hue(albedo);
        double s = saturation(albedo);
        double least = mix(mix(LEAST_SATURATION[0], LEAST_SATURATION[1], smoothstep(SATURATION_HUES[0], SATURATION_HUES[1], h)),
            LEAST_SATURATION[2], smoothstep(SATURATION_HUES[2], SATURATION_HUES[3], h));
        return window(h, HUES) * smoothstep(least, least + 0.1, s);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** A tint on a grey texture (the far mods average a block's texels), as captured. */
    private static double[] tinted(int rgb, double grey) {
        return new double[] {(rgb >> 16 & 0xFF) / 255.0 * grey, (rgb >> 8 & 0xFF) / 255.0 * grey, (rgb & 0xFF) / 255.0 * grey};
    }

    private static double[] block(int rgb) {
        return tinted(rgb, 1.0);
    }

    /** Almanac's palette steps: the summer colour moved part of the way toward another. */
    private static int mix(int from, int to, double amount) {
        int result = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double a = from >> shift & 0xFF;
            double b = to >> shift & 0xFF;
            result |= (int) Math.round(a + (b - a) * amount) << shift;
        }
        return result;
    }

    private static void taken(String what, double[] colour) {
        assertTrue(plant(colour) > 0.9, what + ": " + plant(colour));
    }

    private static void leftAlone(String what, double[] colour) {
        assertTrue(plant(colour) < 0.1, what + ": " + plant(colour));
    }

    @Test
    void vanillasGrassAndLeavesAreTinted() {
        int[] grass = {0x91BD59, 0x79C05A, 0x59C93C, 0xBFB755, 0x83BB6D, 0x6A7039, 0xB6DB61, 0x507A32};
        for (int colour : grass) {
            taken("grass " + Integer.toHexString(colour), tinted(colour, 0.6));
        }
        int[] foliage = {0x77AB2F, 0x59AE30, 0x30BB0B, 0xAEA42A, 0x80A755};
        for (int colour : foliage) {
            taken("leaves " + Integer.toHexString(colour), tinted(colour, 0.45));
        }
    }

    @Test
    void almanacsSeasonalColoursAreTinted() {
        int plainsGrass = 0x91BD59;
        int plainsFoliage = 0x77AB2F;
        taken("mid fall grass", tinted(mix(plainsGrass, 0xA89A4A, 0.55), 0.6));
        taken("mid winter grass", tinted(mix(plainsGrass, 0x868A66, 0.6), 0.6));
        taken("early fall leaves", tinted(mix(plainsFoliage, 0xD89A24, 0.7), 0.45));
        taken("mid fall leaves", tinted(mix(plainsFoliage, 0xD2661C, 0.85), 0.45));
        taken("late fall leaves", tinted(mix(plainsFoliage, 0xA2562A, 0.8), 0.45));
        taken("early winter leaves", tinted(mix(plainsFoliage, 0x6E5233, 0.7), 0.45));
        taken("mid winter leaves", tinted(mix(plainsFoliage, 0x5F5E3E, 0.6), 0.45));
        taken("dappled forest grass", tinted(0xDF6827, 0.6));
    }

    @Test
    void groundStoneWoodAndConifersAreLeftAsCaptured() {
        leftAlone("sand", block(0xDBD3A0));
        leftAlone("sand's map colour", block(0xF7E9A3));
        leftAlone("birch planks", block(0xC0AF79));
        leftAlone("sandstone", block(0xD8CB9B));
        leftAlone("dirt", block(0x866043));
        leftAlone("coarse dirt", block(0x77553B));
        leftAlone("oak planks", block(0xA2834F));
        leftAlone("stone", block(0x7F7F7F));
        leftAlone("gravel", block(0x837E7E));
        leftAlone("snow", block(0xF0FBFB));
        leftAlone("water", block(0x3F76E4));
        leftAlone("spruce leaves", tinted(0x619961, 0.45));
        leftAlone("snowy taiga grass", tinted(0x80B497, 0.6));
    }
}
