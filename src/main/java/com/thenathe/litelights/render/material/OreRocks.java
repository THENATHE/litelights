package com.thenathe.litelights.render.material;

/**
 * What the rocks that ores are drawn on look like in the textures in use, so Glint on ores and
 * crystals can tell the ore in an ore texture from the rock around it, texel by texel, in any
 * resource pack (shimmer099).
 *
 * <p>Each rock (stone, deepslate, netherrack) gets a profile from its own texture: the range of its
 * texels' luma, and how far their colour strays from the rock's mean colour (the colour with the
 * luma taken out, so a grey rock has none). A texel inside both is rock; an ore texel lies outside
 * at least one (darker or lighter than any rock texel, as coal and quartz do, or coloured, as gold,
 * diamond and redstone are). Ore textures don't reuse the rock's texels in place (vanilla's share
 * about half of them), so a per-texel comparison with the rock texture would leave much of the
 * background marked as ore; the profile holds up there, and in packs whose ores repaint the rock.
 *
 * <p>An ore texture is paired with the rock whose colours most of its texels share: the range alone
 * can't tell, since deepslate's darker greys take in much of stone's. One that shares too few with
 * every rock (ancient debris, a modded ore on its own kind of rock) is left out and keeps the plain
 * stone glimmer. No Minecraft types here, so it is unit-tested against the game's own textures.
 */
public final class OreRocks {
    public static final int STONE = 0;
    public static final int DEEPSLATE = 1;
    public static final int NETHERRACK = 2;
    public static final int COUNT = 3;
    /** No rock fits: not treated as an ore. */
    public static final int NONE = -1;

    /**
     * At least this share of an ore texture's opaque texels must have one of a rock's colours for the
     * texture to count as that rock's ore. Vanilla's ores share 0.62 to 0.87 of theirs with their
     * rock, at most 0.40 with the other rocks; ancient debris 0.07 with netherrack.
     */
    static final float LEAST_ROCK_SHARE = 0.45f;
    /** Colours are compared on a grid of 64 steps a channel; one step either way still matches (about 0.016). */
    private static final int PALETTE_BITS = 6;
    private static final int PALETTE_STEPS = 1 << PALETTE_BITS;
    /** Texels with less alpha than this are left out (cut-out holes, an overlay's empty texels). */
    private static final int LEAST_ALPHA = 128;
    private static final float LUMA_RED = 0.2126f;
    private static final float LUMA_GREEN = 0.7152f;
    private static final float LUMA_BLUE = 0.0722f;

    /**
     * One rock's colours: the luma range and colour spread the shader tests texels against, and
     * which colours of a coarse grid its texels have, for pairing ores with it. A rock whose
     * texture couldn't be read has none ({@link #missing}).
     */
    public record Profile(float lumaLow, float lumaHigh, float red, float green, float blue, float radius, boolean[] palette) {
        static final Profile MISSING = new Profile(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, new boolean[PALETTE_STEPS * PALETTE_STEPS * PALETTE_STEPS]);

        boolean missing() {
            return lumaLow > lumaHigh;
        }

        /** How far a colour (0..1 per channel) lies outside the rock's: 0 or less inside it. */
        public float outside(float r, float g, float b) {
            float luma = luma(r, g, b);
            float lumaOutside = Math.max(lumaLow - luma, luma - lumaHigh);
            float dr = r - luma - red;
            float dg = g - luma - green;
            float db = b - luma - blue;
            float colourOutside = (float) Math.sqrt(dr * dr + dg * dg + db * db) - radius;
            return Math.max(lumaOutside, colourOutside);
        }

        /** Whether the rock has this colour, or one a grid step from it in each channel. */
        boolean hasColour(int abgr) {
            int r = quantize(abgr & 0xFF);
            int g = quantize(abgr >>> 8 & 0xFF);
            int b = quantize(abgr >>> 16 & 0xFF);
            for (int dr = -1; dr <= 1; dr++) {
                for (int dg = -1; dg <= 1; dg++) {
                    for (int db = -1; db <= 1; db++) {
                        int nr = r + dr;
                        int ng = g + dg;
                        int nb = b + db;
                        if (nr >= 0 && ng >= 0 && nb >= 0 && nr < PALETTE_STEPS && ng < PALETTE_STEPS && nb < PALETTE_STEPS
                            && palette[paletteIndex(nr, ng, nb)]) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    private OreRocks() {
    }

    /**
     * A rock's profile from its texture's first frame (a square of {@code width} texels at the top;
     * an animated texture has its frames below), pixels packed as 0xAABBGGRR.
     */
    public static Profile profile(int[] abgrPixels, int width) {
        int texels = Math.min(abgrPixels.length, width * width);
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        double sumRed = 0.0;
        double sumGreen = 0.0;
        double sumBlue = 0.0;
        int counted = 0;
        boolean[] palette = new boolean[PALETTE_STEPS * PALETTE_STEPS * PALETTE_STEPS];
        for (int i = 0; i < texels; i++) {
            int pixel = abgrPixels[i];
            if (alpha(pixel) < LEAST_ALPHA) {
                continue;
            }
            float r = red(pixel);
            float g = green(pixel);
            float b = blue(pixel);
            float luma = luma(r, g, b);
            low = Math.min(low, luma);
            high = Math.max(high, luma);
            sumRed += r - luma;
            sumGreen += g - luma;
            sumBlue += b - luma;
            counted++;
            palette[paletteIndex(quantize(pixel & 0xFF), quantize(pixel >>> 8 & 0xFF), quantize(pixel >>> 16 & 0xFF))] = true;
        }
        if (counted == 0) {
            return Profile.MISSING;
        }
        float meanRed = (float) (sumRed / counted);
        float meanGreen = (float) (sumGreen / counted);
        float meanBlue = (float) (sumBlue / counted);
        float radius = 0.0f;
        for (int i = 0; i < texels; i++) {
            int pixel = abgrPixels[i];
            if (alpha(pixel) < LEAST_ALPHA) {
                continue;
            }
            float r = red(pixel);
            float g = green(pixel);
            float b = blue(pixel);
            float luma = luma(r, g, b);
            float dr = r - luma - meanRed;
            float dg = g - luma - meanGreen;
            float db = b - luma - meanBlue;
            radius = Math.max(radius, (float) Math.sqrt(dr * dr + dg * dg + db * db));
        }
        return new Profile(low, high, meanRed, meanGreen, meanBlue, radius, palette);
    }

    /** The rock an ore texture is drawn on: the one whose colours most of its opaque texels share, or {@link #NONE}. */
    public static int pair(int[] abgrPixels, int width, Profile[] rocks) {
        int texels = Math.min(abgrPixels.length, width * width);
        int best = NONE;
        float bestShare = LEAST_ROCK_SHARE;
        for (int rock = 0; rock < rocks.length; rock++) {
            Profile profile = rocks[rock];
            if (profile.missing()) {
                continue;
            }
            int opaque = 0;
            int fitting = 0;
            for (int i = 0; i < texels; i++) {
                int pixel = abgrPixels[i];
                if (alpha(pixel) < LEAST_ALPHA) {
                    continue;
                }
                opaque++;
                if (profile.hasColour(pixel)) {
                    fitting++;
                }
            }
            float share = opaque == 0 ? 0.0f : fitting / (float) opaque;
            if (share >= bestShare) {
                best = rock;
                bestShare = share;
            }
        }
        return best;
    }

    /** The ore material for a rock, or the default material for {@link #NONE}. */
    public static int material(int rock) {
        return rock == NONE ? MaterialIds.DEFAULT : MaterialIds.ORE_STONE + rock;
    }

    /**
     * The profiles as the shader reads them (LiteOreRocks in frame.glsl): two vec4 per rock, the
     * mean colour and its radius, then the luma range. A missing rock gets an empty range, which
     * no texel fits (and no ore is paired with it).
     */
    public static float[] uniforms(Profile[] rocks) {
        float[] values = new float[COUNT * 8];
        for (int rock = 0; rock < COUNT; rock++) {
            Profile profile = rock < rocks.length ? rocks[rock] : Profile.MISSING;
            int at = rock * 8;
            values[at] = profile.red();
            values[at + 1] = profile.green();
            values[at + 2] = profile.blue();
            values[at + 3] = profile.radius();
            values[at + 4] = profile.lumaLow();
            values[at + 5] = profile.lumaHigh();
        }
        return values;
    }

    private static int quantize(int channel) {
        return channel >> (8 - PALETTE_BITS);
    }

    private static int paletteIndex(int r, int g, int b) {
        return (r * PALETTE_STEPS + g) * PALETTE_STEPS + b;
    }

    static float luma(float r, float g, float b) {
        return LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b;
    }

    private static int alpha(int abgr) {
        return abgr >>> 24;
    }

    private static float red(int abgr) {
        return (abgr & 0xFF) / 255.0f;
    }

    private static float green(int abgr) {
        return (abgr >>> 8 & 0xFF) / 255.0f;
    }

    private static float blue(int abgr) {
        return (abgr >>> 16 & 0xFF) / 255.0f;
    }
}
