package com.thenathe.litelights.render.feature.lightpost;

import com.thenathe.litelights.render.RenderSettings;
import org.jspecify.annotations.Nullable;

/**
 * The enchantment outline's glow colours: the chosen colour, and the colour of an armour trim's
 * material. Both come out at full brightness (the brightest channel 1), as the outline expects.
 */
final class OutlineColours {
    private OutlineColours() {
    }

    /** The colour in linear RGB, moved toward its own grey by the saturation slider (0 grey, 1 full). */
    static void linear(RenderSettings.GlowColour choice, float saturation, float[] out) {
        switch (choice) {
            case ENCHANT -> set(out, 0.66f, 0.38f, 1.0f);
            case BLUE -> set(out, 0.28f, 0.48f, 1.0f);
            case CYAN -> set(out, 0.25f, 0.92f, 1.0f);
            case GREEN -> set(out, 0.35f, 1.0f, 0.42f);
            case GOLD -> set(out, 1.0f, 0.74f, 0.25f);
            case RED -> set(out, 1.0f, 0.28f, 0.26f);
            case WHITE -> set(out, 1.0f, 1.0f, 1.0f);
        }
        saturate(out, saturation);
    }

    /**
     * A trim material's outline colour, from its data: the colour of its name ({@code description}'s
     * text colour, which vanilla, data packs and mods all give their trim materials), or failing
     * that the average of its colour palette (the texture the trim is drawn with, so a resource
     * pack's palette counts). Brightened to full and moved toward grey by the saturation slider like
     * the chosen colour. Returns false when neither gives a colour that isn't black (and then
     * {@code out} holds nothing useful).
     *
     * @param nameRgb the description's text colour as 0xRRGGBB, or null if it has none
     * @param palette the palette's pixels as 0xAARRGGBB, or null if it couldn't be read
     */
    static boolean trim(@Nullable Integer nameRgb, int @Nullable [] palette, float saturation, float[] out) {
        boolean found = nameRgb != null && fromRgb(nameRgb, out) && brightenToFull(out)
            || palette != null && averageOf(palette, out) && brightenToFull(out);
        if (found) {
            saturate(out, saturation);
        }
        return found;
    }

    /** Packs a colour from {@link #linear} or {@link #trim} as an opaque 0xAARRGGBB vertex colour. */
    static int packed(float[] colour) {
        int red = Math.round(Math.clamp(colour[0], 0.0f, 1.0f) * 255.0f);
        int green = Math.round(Math.clamp(colour[1], 0.0f, 1.0f) * 255.0f);
        int blue = Math.round(Math.clamp(colour[2], 0.0f, 1.0f) * 255.0f);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static boolean fromRgb(int rgb, float[] out) {
        set(out, (rgb >> 16 & 255) / 255.0f, (rgb >> 8 & 255) / 255.0f, (rgb & 255) / 255.0f);
        return true;
    }

    /** The average of a palette's opaque pixels; false if it has none. */
    private static boolean averageOf(int[] palette, float[] out) {
        float red = 0.0f;
        float green = 0.0f;
        float blue = 0.0f;
        int counted = 0;
        for (int argb : palette) {
            if ((argb >>> 24) < 128) {
                continue;
            }
            red += (argb >> 16 & 255) / 255.0f;
            green += (argb >> 8 & 255) / 255.0f;
            blue += (argb & 255) / 255.0f;
            counted++;
        }
        if (counted == 0) {
            return false;
        }
        set(out, red / counted, green / counted, blue / counted);
        return true;
    }

    /** Scales the colour so its brightest channel is 1; false for black, which has no hue to keep. */
    private static boolean brightenToFull(float[] colour) {
        float brightest = Math.max(colour[0], Math.max(colour[1], colour[2]));
        if (brightest < 1.0f / 255.0f) {
            return false;
        }
        for (int channel = 0; channel < 3; channel++) {
            colour[channel] /= brightest;
        }
        return true;
    }

    private static void saturate(float[] colour, float saturation) {
        float grey = 0.2126f * colour[0] + 0.7152f * colour[1] + 0.0722f * colour[2];
        for (int channel = 0; channel < 3; channel++) {
            colour[channel] = grey + (colour[channel] - grey) * saturation;
        }
    }

    private static void set(float[] out, float red, float green, float blue) {
        out[0] = red;
        out[1] = green;
        out[2] = blue;
    }
}
