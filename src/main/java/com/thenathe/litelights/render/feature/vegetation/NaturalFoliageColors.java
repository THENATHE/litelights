package com.thenathe.litelights.render.feature.vegetation;

import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.config.LiteLightsOptions;
import java.util.Arrays;
import net.minecraft.client.Minecraft;

/**
 * "Natural foliage colours": softens the biome grass and foliage tints where they come from, the
 * biome's own colour methods (see {@code mixin.vegetation.BiomeMixin}). Vanilla's and Sodium's
 * chunk builders both get their tints there, so resource-pack colour maps, colour overrides of
 * modded biomes and biome blending all pass through, and the cost is paid only while chunks are
 * built, never per frame.
 *
 * <p>The change is made in OKLab, a perceptual colour space, and only for yellow-green to
 * blue-green hues: very saturated greens (jungle, plains foliage) lose some of their neon edge and
 * lean slightly toward a natural leaf green, while designed colours outside that band (orange
 * autumn biomes, grey pale gardens, badlands browns) are left alone.
 */
public final class NaturalFoliageColors {
    /** Hue the greens lean toward (OKLCh degrees): a mid leaf green. */
    private static final double TARGET_HUE = 132.0;
    private static final double HUE_PULL = 0.15;
    /** Chroma is scaled a little, and chroma above the knee is halved: only neon greens change much. */
    private static final double CHROMA_SCALE = 0.96;
    private static final double CHROMA_KNEE = 0.11;
    private static final double CHROMA_ABOVE_KNEE = 0.5;
    private static final double TARGET_LIGHTNESS = 0.66;
    private static final double LIGHTNESS_PULL = 0.06;
    /** The hue band that is changed: fully between the inner bounds, fading out to the outer ones. */
    private static final double BAND_OUTER_LOW = 80.0;
    private static final double BAND_INNER_LOW = 108.0;
    private static final double BAND_INNER_HIGH = 160.0;
    private static final double BAND_OUTER_HIGH = 185.0;

    /** Small direct-mapped cache: an entry holds the input colour in its high half, the result in its low half. */
    private static final int CACHE_SIZE = 4096;
    private static final long EMPTY = -1L;
    private static final long[] CACHE = new long[CACHE_SIZE];
    private static final float[] SRGB_TO_LINEAR = new float[256];

    private static volatile boolean enabled = true;

    static {
        Arrays.fill(CACHE, EMPTY);
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private NaturalFoliageColors() {
    }

    static void initialize() {
        enabled = LiteLightsOptions.NATURAL_FOLIAGE.on();
        LiteLightsOptions.NATURAL_FOLIAGE.addListener((oldValue, newValue) -> {
            enabled = newValue;
            // Chunks keep the tints they were built with: build them again, with fresh tint caches.
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> minecraft.levelExtractor.allChanged());
        });
        Feature.of("general.natural_foliage").markReady();
    }

    /** The biome grass or foliage colour (0xRRGGBB) to use; unchanged when the option is off. */
    public static int apply(int rgb) {
        if (!enabled) {
            return rgb;
        }
        int key = rgb & 0xFFFFFF;
        int slot = (key * 0x9E3779B1 >>> 20) & (CACHE_SIZE - 1);
        long entry = CACHE[slot];
        if (entry != EMPTY && (int) (entry >>> 32) == key) {
            return (rgb & 0xFF000000) | (int) entry;
        }
        int softened = soften(key);
        // A single long write can't tear, so chunk-builder threads may share the cache without locks.
        CACHE[slot] = ((long) key << 32) | (softened & 0xFFFFFFFFL);
        return (rgb & 0xFF000000) | softened;
    }

    /** The colour change itself, for 0xRRGGBB. Pure, so it can be tested on its own. */
    public static int soften(int rgb) {
        double[] lab = toOklab(rgb);
        double lightness = lab[0];
        double chroma = Math.hypot(lab[1], lab[2]);
        double hue = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        if (hue < 0.0) {
            hue += 360.0;
        }
        double weight = bandWeight(hue);
        if (weight <= 0.0) {
            return rgb;
        }

        double hueToTarget = ((TARGET_HUE - hue + 540.0) % 360.0) - 180.0;
        double newHue = hue + weight * HUE_PULL * hueToTarget;
        double scaled = chroma * CHROMA_SCALE;
        if (scaled > CHROMA_KNEE) {
            scaled = CHROMA_KNEE + (scaled - CHROMA_KNEE) * CHROMA_ABOVE_KNEE;
        }
        double newChroma = chroma + (scaled - chroma) * weight;
        double newLightness = lightness + weight * LIGHTNESS_PULL * (TARGET_LIGHTNESS - lightness);

        double radians = Math.toRadians(newHue);
        return fromOklab(newLightness, newChroma * Math.cos(radians), newChroma * Math.sin(radians));
    }

    /** 1 inside the green band, easing to 0 at its outer edges. */
    static double bandWeight(double hue) {
        double linear;
        if (hue <= BAND_OUTER_LOW || hue >= BAND_OUTER_HIGH) {
            return 0.0;
        } else if (hue < BAND_INNER_LOW) {
            linear = (hue - BAND_OUTER_LOW) / (BAND_INNER_LOW - BAND_OUTER_LOW);
        } else if (hue > BAND_INNER_HIGH) {
            linear = (BAND_OUTER_HIGH - hue) / (BAND_OUTER_HIGH - BAND_INNER_HIGH);
        } else {
            return 1.0;
        }
        return linear * linear * (3.0 - 2.0 * linear);
    }

    // OKLab conversions (Björn Ottosson's published matrices).

    static double[] toOklab(int rgb) {
        double r = SRGB_TO_LINEAR[(rgb >> 16) & 0xFF];
        double g = SRGB_TO_LINEAR[(rgb >> 8) & 0xFF];
        double b = SRGB_TO_LINEAR[rgb & 0xFF];
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[] {
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        };
    }

    static int fromOklab(double lightness, double a, double b) {
        double l = cube(lightness + 0.3963377774 * a + 0.2158037573 * b);
        double m = cube(lightness - 0.1055613458 * a - 0.0638541728 * b);
        double s = cube(lightness - 0.0894841775 * a - 1.2914855480 * b);
        int red = toSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
        int green = toSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
        int blue = toSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
        return red << 16 | green << 8 | blue;
    }

    private static double cube(double value) {
        return value * value * value;
    }

    private static int toSrgb(double linear) {
        double c = linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
        return (int) Math.round(Math.clamp(c, 0.0, 1.0) * 255.0);
    }
}
