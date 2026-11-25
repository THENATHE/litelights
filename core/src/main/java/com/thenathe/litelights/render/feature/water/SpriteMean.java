package com.thenathe.litelights.render.feature.water;

/**
 * The average colour of a fluid texture, every frame included, for the Enhanced water colours:
 * the shader keeps each texel's colour relative to this average, so a brighter or coloured
 * resource pack keeps its pixel pattern without shifting the water's overall colour. Texels are
 * weighted by their alpha, so fully clear texels don't pull the average toward black.
 *
 * <p>No Minecraft types here, so it is unit-tested.
 */
public final class SpriteMean {
    /** Vanilla's water texture is a mid grey; a texture that can't be read counts as that. */
    public static final float[] FALLBACK = {0.7f, 0.7f, 0.7f};

    private SpriteMean() {
    }

    /** Linear-in-texture red, green and blue (0..1) of pixels packed as 0xAABBGGRR. */
    public static float[] of(int[] abgrPixels) {
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;
        double weight = 0.0;
        for (int pixel : abgrPixels) {
            double alpha = ((pixel >>> 24) & 0xFF) / 255.0;
            red += (pixel & 0xFF) * alpha;
            green += ((pixel >>> 8) & 0xFF) * alpha;
            blue += ((pixel >>> 16) & 0xFF) * alpha;
            weight += alpha;
        }
        if (weight <= 0.0) {
            return FALLBACK.clone();
        }
        double scale = 1.0 / (255.0 * weight);
        return new float[] {(float) (red * scale), (float) (green * scale), (float) (blue * scale)};
    }
}
