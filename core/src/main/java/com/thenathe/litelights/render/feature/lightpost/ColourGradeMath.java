package com.thenathe.litelights.render.feature.lightpost;

import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import java.util.Arrays;

/**
 * The colour grade's maths, kept free of the renderer so it can be unit tested. The tonemap
 * shader ({@code post/tonemap.fsh}) runs {@link #applyGrade} per pixel with the parameters
 * {@link #computeParameters} produces; the two must stay in step.
 *
 * <p>Order, on linear colour: exposure, a colour matrix (hue shift, white balance and the
 * per-channel strength and hue), contrast around mid grey, shadows / midtones / highlights on the
 * luminance in perceptual (sRGB-like) units, saturation and vibrance, and finally a highlight
 * roll-off that only switches on when the grade can push colours past white. Every slider at its
 * neutral value gives back the input exactly (to float precision).
 *
 * <p>An instance holds the scratch arrays for the per-frame steps, so a frame allocates nothing;
 * the per-pixel steps are static.
 */
public final class ColourGradeMath {
    /**
     * Number of floats in the shader's push constants (six vec4): {@link #computeParameters} writes
     * the first 18, the caller 18 and 19 (bloom and flags), {@link #computeCurve} 20 to 23.
     */
    public static final int PARAMETER_COUNT = 24;
    public static final int GRADE_VALUES = 16;

    static final float MID_GREY = 0.18f;
    static final float ROLLOFF_KNEE = 0.8f;

    // Indices into the parameter array, matching the vec4 layout in tonemap.fsh.
    static final int EXPOSURE = 3;
    static final int CONTRAST = 7;
    static final int PIVOT = 11;
    static final int SHADOWS = 12;
    static final int MIDTONES = 13;
    static final int HIGHLIGHTS = 14;
    static final int SATURATION = 15;
    static final int VIBRANCE = 16;
    static final int ROLLOFF_MAX = 17;
    static final int CURVE_EXPOSURE = 20;
    static final int CURVE_EXPANSION = 21;
    static final int CURVE_NORMALISE = 22;
    static final int CURVE_BLEND = 23;

    /** Curve numbers as the shader reads them from the flags (bits 4 and 5). */
    public static final int CURVE_VANILLA_FRIENDLY = 0;
    public static final int CURVE_ACES = 1;
    public static final int CURVE_AGX = 2;
    public static final int CURVE_HABLE = 3;

    private static final float[][] PROBE_COLOURS = {
        {1, 1, 1}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 1, 1}, {1, 0, 1}, {1, 1, 0}
    };

    private final float[] weights = new float[4];
    private final float[] gradeScratch = new float[GRADE_VALUES];
    private final float[] matrix = new float[9];
    private final float[] channels = new float[9];
    private final float[] turn = new float[9];
    private final float[] partial = new float[9];
    private final float[] balance = new float[9];
    private final float[] probe = new float[3];

    // --- Time of day ---------------------------------------------------------------------------

    /**
     * Day times (0 to 23999, sunrise at 0, noon at 6000) where each time of day's grade is at full
     * strength, as {start, end} ranges in order: dawn, midday, dusk, night. Between ranges the two
     * neighbours cross-fade.
     */
    private static final float[][] FULL_RANGES = {
        {23_500f, 24_500f},   // dawn, around sunrise (wraps past midnight's 0)
        {2_500f, 10_000f},    // midday
        {12_000f, 13_000f},   // dusk, around sunset
        {14_500f, 22_000f},   // night
    };

    /**
     * Weights of dawn, midday, dusk and night for a day time, summing to 1. Written into
     * {@code weights} (length 4) so a frame allocates nothing.
     */
    public static void timeOfDayWeights(double dayTime, float[] weights) {
        float time = (float) (((dayTime % 24_000.0) + 24_000.0) % 24_000.0);
        Arrays.fill(weights, 0.0f);
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) % 4;
            float start = FULL_RANGES[index][0];
            float end = FULL_RANGES[index][1];
            float nextStart = FULL_RANGES[next][0];
            if (within(time, start, end)) {
                weights[index] = 1.0f;
                return;
            }
            float gap = wrap(nextStart - end);
            float into = wrap(time - end);
            if (into < gap) {
                float blend = LiteMath.smoothstep(0.0f, 1.0f, into / gap);
                weights[index] = 1.0f - blend;
                weights[next] = blend;
                return;
            }
        }
        weights[1] = 1.0f;
    }

    /** The day time a frame's sun position stands for, from LiteFrame's day fraction (0 at noon). */
    public static double dayTimeFromSunTurns(float sunTurns) {
        return ((sunTurns * 24_000.0 + 6_000.0) % 24_000.0 + 24_000.0) % 24_000.0;
    }

    private static boolean within(float time, float start, float end) {
        return wrap(time - start) <= wrap(end - start);
    }

    private static float wrap(float value) {
        return ((value % 24_000f) + 24_000f) % 24_000f;
    }

    // --- Blending grades -----------------------------------------------------------------------

    /** The 16 sliders of a grade, in record order, into {@code out}. */
    public static void read(RenderSettings.Grade grade, float[] out) {
        out[0] = grade.exposure();
        out[1] = grade.contrast();
        out[2] = grade.shadows();
        out[3] = grade.midtones();
        out[4] = grade.highlights();
        out[5] = grade.saturation();
        out[6] = grade.vibrance();
        out[7] = grade.hueShift();
        out[8] = grade.temperature();
        out[9] = grade.tint();
        out[10] = grade.redStrength();
        out[11] = grade.greenStrength();
        out[12] = grade.blueStrength();
        out[13] = grade.redHue();
        out[14] = grade.greenHue();
        out[15] = grade.blueHue();
    }

    /**
     * The grade that applies at a day time: the whole-day grade, or the four times of day blended
     * by {@link #timeOfDayWeights}. Slider values blend linearly, which for hues is fine within
     * their ±180 degree range.
     */
    public void gradeAt(RenderSettings.ColorGrading grading, double dayTime, float[] out) {
        if (grading.schedule() != RenderSettings.GradeSchedule.BY_TIME_OF_DAY) {
            read(grading.allDay(), out);
            return;
        }
        timeOfDayWeights(dayTime, weights);
        Arrays.fill(out, 0, GRADE_VALUES, 0.0f);
        for (int index = 0; index < 4; index++) {
            if (weights[index] <= 0.0f) {
                continue;
            }
            read(timeOfDay(grading, index), gradeScratch);
            for (int value = 0; value < GRADE_VALUES; value++) {
                out[value] += gradeScratch[value] * weights[index];
            }
        }
    }

    private static RenderSettings.Grade timeOfDay(RenderSettings.ColorGrading grading, int index) {
        return switch (index) {
            case 0 -> grading.dawn();
            case 1 -> grading.midday();
            case 2 -> grading.dusk();
            default -> grading.night();
        };
    }

    // --- Shader parameters ---------------------------------------------------------------------

    /**
     * Turns 16 slider values (record order, settings units) into the tonemap's grade parameters.
     * Writes {@link #PARAMETER_COUNT} floats; indices 18 and 19 are left for the caller (bloom
     * intensity and flags).
     */
    public void computeParameters(float[] grade, float[] params) {
        colourMatrix(grade[7], grade[8], grade[9], grade[10], grade[11], grade[12], grade[13], grade[14], grade[15], matrix);
        for (int row = 0; row < 3; row++) {
            params[row * 4] = matrix[row * 3];
            params[row * 4 + 1] = matrix[row * 3 + 1];
            params[row * 4 + 2] = matrix[row * 3 + 2];
        }
        params[EXPOSURE] = (float) Math.pow(2.0, grade[0]);
        params[CONTRAST] = 1.0f + 0.75f * grade[1];
        params[PIVOT] = MID_GREY;
        params[SHADOWS] = grade[2];
        params[MIDTONES] = (float) Math.pow(2.0, -0.7 * grade[3]);
        params[HIGHLIGHTS] = grade[4];
        params[SATURATION] = 1.0f + grade[5];
        params[VIBRANCE] = grade[6];
        params[ROLLOFF_MAX] = 1.0f;
        params[ROLLOFF_MAX] = brightestOutput(params);
    }

    /**
     * The colour matrix, row-major: hue shift times white balance times the channel mixer. Each
     * input channel's column is that primary, turned by its own hue and scaled by its strength.
     */
    void colourMatrix(float hueShift, float temperature, float tint, float redStrength, float greenStrength,
                      float blueStrength, float redHue, float greenHue, float blueHue, float[] out) {
        mixChannel(0, redHue, redStrength);
        mixChannel(1, greenHue, greenStrength);
        mixChannel(2, blueHue, blueStrength);
        // White balance: warm moves red up and blue down, tint moves green against magenta. The
        // gains are scaled so white keeps its luminance.
        float red = 1.0f + 0.22f * temperature + 0.08f * tint;
        float green = 1.0f - 0.16f * tint;
        float blue = 1.0f - 0.22f * temperature + 0.08f * tint;
        float whiteLuma = luma(red, green, blue);
        Arrays.fill(balance, 0.0f);
        balance[0] = red / whiteLuma;
        balance[4] = green / whiteLuma;
        balance[8] = blue / whiteLuma;
        hueRotation(hueShift, turn);
        multiply(balance, channels, partial);
        multiply(turn, partial, out);
    }

    /** One column of the channel mixer: the primary, turned by its hue and scaled by its strength. */
    private void mixChannel(int column, float hue, float strength) {
        hueRotation(hue, turn);
        for (int row = 0; row < 3; row++) {
            channels[row * 3 + column] = turn[row * 3 + column] * strength;
        }
    }

    /** Rotation about the grey axis by an angle in degrees: turns hue, keeps greys grey. */
    static void hueRotation(float degrees, float[] out) {
        double angle = Math.toRadians(degrees);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float third = (1.0f - cos) / 3.0f;
        float root = (float) Math.sqrt(1.0 / 3.0) * sin;
        out[0] = cos + third;
        out[1] = third - root;
        out[2] = third + root;
        out[3] = third + root;
        out[4] = cos + third;
        out[5] = third - root;
        out[6] = third - root;
        out[7] = third + root;
        out[8] = cos + third;
    }

    private static void multiply(float[] a, float[] b, float[] out) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                float sum = 0.0f;
                for (int k = 0; k < 3; k++) {
                    sum += a[row * 3 + k] * b[k * 3 + column];
                }
                out[row * 3 + column] = sum;
            }
        }
    }

    /**
     * The largest channel value the grade can produce from in-range input (before the roll-off),
     * found by grading the corners of the colour cube; every step is monotonic, so the corners
     * bound everything in between. At most 1 means the roll-off stays off.
     */
    float brightestOutput(float[] params) {
        float saved = params[ROLLOFF_MAX];
        params[ROLLOFF_MAX] = 1.0f;
        float brightest = 0.0f;
        for (float[] corner : PROBE_COLOURS) {
            System.arraycopy(corner, 0, probe, 0, 3);
            applyGrade(params, probe);
            brightest = Math.max(brightest, Math.max(probe[0], Math.max(probe[1], probe[2])));
        }
        params[ROLLOFF_MAX] = saved;
        return brightest <= 1.0001f ? 1.0f : brightest;
    }

    // --- Tone curves (same steps as tonemap.fsh) -----------------------------------------------

    /**
     * One curve's fixed shape. The picture is display-referred, so before a film curve runs its
     * brightest tones are stretched back out (as if white had been {@code 1 / (1 - expansion)}
     * times brighter in the scene), the curve is scaled by {@code exposure}, and the result is
     * divided by the curve's own white, so black and white stay where they are. {@code blend} mixes
     * the curve with the unchanged picture.
     *
     * <ul>
     *   <li>ACES-style: Narkowicz's published fit of the ACES film curve, with the exposure that keeps
     *   mid grey at mid grey and half of it mixed in: deeper shadows, richer highlights.</li>
     *   <li>AgX-style: the published AgX formulation (inset matrix, log2 encoding from -12.47 to +4.03
     *   stops, its default contrast curve as the usual sixth-order fit, outset matrix, 2.2 display
     *   power), mid grey kept, 60 % mixed in: gentle contrast, bright colours turn toward white.</li>
     *   <li>Hable filmic: John Hable's published filmic curve (A 0.15, B 0.50, C 0.10, D 0.20, E 0.02,
     *   F 0.30) at exposure 1 on the picture as it is: lifted shadows and midtones, a soft shoulder.</li>
     * </ul>
     * The exposures were found by bisection so that mid grey (0.18) maps to itself at white 1
     * (checked in ColourGradeMathTest).
     */
    record CurveShape(float exposure, float expansion, float blend) {
    }

    static final CurveShape ACES_SHAPE = new CurveShape(0.6015f, 0.875f, 0.5f);
    static final CurveShape AGX_SHAPE = new CurveShape(0.5903f, 0.875f, 0.6f);
    static final CurveShape HABLE_SHAPE = new CurveShape(1.0f, 0.0f, 1.0f);

    static CurveShape shape(int curve) {
        return switch (curve) {
            case CURVE_ACES -> ACES_SHAPE;
            case CURVE_AGX -> AGX_SHAPE;
            case CURVE_HABLE -> HABLE_SHAPE;
            default -> null;
        };
    }

    public static int curveNumber(RenderSettings.ToneCurve curve) {
        return switch (curve) {
            case VANILLA_FRIENDLY -> CURVE_VANILLA_FRIENDLY;
            case ACES -> CURVE_ACES;
            case AGX -> CURVE_AGX;
            case HABLE -> CURVE_HABLE;
        };
    }

    /**
     * The curve's values for the shader: exposure, expansion, 1 / the curve's white, and blend.
     * {@code white} is the brightest value the grade can produce (1 without grading). The
     * Vanilla-friendly curve has no parameters; its roll-off uses {@link #ROLLOFF_MAX}.
     */
    public void computeCurve(int curve, float white, float[] params) {
        CurveShape shape = shape(curve);
        if (shape == null) {
            params[CURVE_EXPOSURE] = 1.0f;
            params[CURVE_EXPANSION] = 0.0f;
            params[CURVE_NORMALISE] = 1.0f;
            params[CURVE_BLEND] = 0.0f;
            return;
        }
        params[CURVE_EXPOSURE] = shape.exposure();
        params[CURVE_EXPANSION] = shape.expansion();
        params[CURVE_BLEND] = shape.blend();
        params[CURVE_NORMALISE] = 1.0f;
        float sceneWhite = shape.exposure() * white / (1.0f - shape.expansion());
        float curveWhite = switch (curve) {
            case CURVE_ACES -> aces(sceneWhite);
            case CURVE_HABLE -> hable(sceneWhite);
            default -> {
                probe[0] = probe[1] = probe[2] = sceneWhite;
                agx(probe);
                yield luma(probe[0], probe[1], probe[2]);
            }
        };
        params[CURVE_NORMALISE] = 1.0f / curveWhite;
    }

    /**
     * Puts one graded linear colour through the curve, in place: the shader's toneCurve, for tests
     * and the CPU checks. {@code white} is the same brightest value {@link #computeCurve} was given;
     * the Vanilla-friendly curve is the roll-off.
     */
    public static void applyCurve(int curve, float[] params, float white, float[] rgb) {
        float maxInput = Math.max(white, 1.0f);
        if (curve == CURVE_VANILLA_FRIENDLY) {
            for (int channel = 0; channel < 3; channel++) {
                rgb[channel] = rolloff(rgb[channel], maxInput);
            }
            return;
        }
        float exposure = params[CURVE_EXPOSURE];
        float expansion = params[CURVE_EXPANSION];
        float normalise = params[CURVE_NORMALISE];
        float blend = params[CURVE_BLEND];
        float[] curved = new float[3];
        for (int channel = 0; channel < 3; channel++) {
            curved[channel] = exposure * expand(rgb[channel], expansion, maxInput);
        }
        if (curve == CURVE_AGX) {
            agx(curved);
        } else {
            for (int channel = 0; channel < 3; channel++) {
                curved[channel] = curve == CURVE_ACES ? aces(curved[channel]) : hable(curved[channel]);
            }
        }
        for (int channel = 0; channel < 3; channel++) {
            float plain = rolloff(rgb[channel], maxInput);
            rgb[channel] = plain + (Math.clamp(curved[channel] * normalise, 0.0f, 1.0f) - plain) * blend;
        }
    }

    /** Stretches the brightest tones back out: identity near black, {@code white / (1 - expansion)} at white. */
    static float expand(float value, float expansion, float white) {
        float clamped = Math.clamp(value, 0.0f, white);
        return clamped / (1.0f - expansion * clamped / white);
    }

    /** Narkowicz's fit of the ACES film curve. */
    static float aces(float x) {
        return x * (2.51f * x + 0.03f) / (x * (2.43f * x + 0.59f) + 0.14f);
    }

    /** Hable's filmic curve, without its own white scale (the caller normalises). */
    static float hable(float x) {
        final float a = 0.15f;
        final float b = 0.50f;
        final float c = 0.10f;
        final float d = 0.20f;
        final float e = 0.02f;
        final float f = 0.30f;
        return (x * (a * x + c * b) + d * e) / (x * (a * x + b) + d * f) - e / f;
    }

    private static final float AGX_MIN_EV = -12.47393f;
    private static final float AGX_MAX_EV = 4.026069f;
    /** AgX's inset and outset matrices, row-major. */
    private static final float[] AGX_INSET = {
        0.842479062f, 0.078433600f, 0.079223745f,
        0.042328242f, 0.878468636f, 0.079166127f,
        0.042375655f, 0.078433600f, 0.879142974f,
    };
    private static final float[] AGX_OUTSET = {
        1.196879005f, -0.098020881f, -0.099029744f,
        -0.052896852f, 1.151903130f, -0.098961177f,
        -0.052971636f, -0.098043450f, 1.151073673f,
    };

    /** AgX on a linear colour, in place: inset, log encoding, the contrast curve, outset, back to linear. */
    static void agx(float[] rgb) {
        float r = AGX_INSET[0] * rgb[0] + AGX_INSET[1] * rgb[1] + AGX_INSET[2] * rgb[2];
        float g = AGX_INSET[3] * rgb[0] + AGX_INSET[4] * rgb[1] + AGX_INSET[5] * rgb[2];
        float b = AGX_INSET[6] * rgb[0] + AGX_INSET[7] * rgb[1] + AGX_INSET[8] * rgb[2];
        r = agxContrast(agxEncode(r));
        g = agxContrast(agxEncode(g));
        b = agxContrast(agxEncode(b));
        float outR = AGX_OUTSET[0] * r + AGX_OUTSET[1] * g + AGX_OUTSET[2] * b;
        float outG = AGX_OUTSET[3] * r + AGX_OUTSET[4] * g + AGX_OUTSET[5] * b;
        float outB = AGX_OUTSET[6] * r + AGX_OUTSET[7] * g + AGX_OUTSET[8] * b;
        rgb[0] = (float) Math.pow(Math.max(outR, 0.0f), 2.2);
        rgb[1] = (float) Math.pow(Math.max(outG, 0.0f), 2.2);
        rgb[2] = (float) Math.pow(Math.max(outB, 0.0f), 2.2);
    }

    private static float agxEncode(float value) {
        float stops = (float) (Math.log(Math.max(value, 1.0e-10f)) / Math.log(2.0));
        return (Math.clamp(stops, AGX_MIN_EV, AGX_MAX_EV) - AGX_MIN_EV) / (AGX_MAX_EV - AGX_MIN_EV);
    }

    private static float agxContrast(float x) {
        float x2 = x * x;
        float x4 = x2 * x2;
        return 15.5f * x4 * x2 - 40.14f * x4 * x + 31.96f * x4 - 6.868f * x2 * x + 0.4298f * x2 + 0.1191f * x - 0.00232f;
    }

    // --- The grade itself (same steps as tonemap.fsh) ------------------------------------------

    /** Grades one linear colour in place, ending with the Vanilla-friendly roll-off. */
    public static void applyGrade(float[] params, float[] rgb) {
        gradeColour(params, rgb);
        float maxInput = params[ROLLOFF_MAX];
        rgb[0] = rolloff(rgb[0], maxInput);
        rgb[1] = rolloff(rgb[1], maxInput);
        rgb[2] = rolloff(rgb[2], maxInput);
    }

    /** The grade's steps before the tone curve, on one linear colour in place. */
    public static void gradeColour(float[] params, float[] rgb) {
        float exposure = params[EXPOSURE];
        float r = rgb[0] * exposure;
        float g = rgb[1] * exposure;
        float b = rgb[2] * exposure;

        float mixedR = Math.max(params[0] * r + params[1] * g + params[2] * b, 0.0f);
        float mixedG = Math.max(params[4] * r + params[5] * g + params[6] * b, 0.0f);
        float mixedB = Math.max(params[8] * r + params[9] * g + params[10] * b, 0.0f);

        float pivot = params[PIVOT];
        float contrast = params[CONTRAST];
        r = contrastCurve(mixedR, pivot, contrast);
        g = contrastCurve(mixedG, pivot, contrast);
        b = contrastCurve(mixedB, pivot, contrast);

        float luma = luma(r, g, b);
        float tonedLuma = tones(luma, params[SHADOWS], params[MIDTONES], params[HIGHLIGHTS]);
        if (luma > 1.0e-5f) {
            float scale = tonedLuma / luma;
            r *= scale;
            g *= scale;
            b *= scale;
        } else {
            r = tonedLuma;
            g = tonedLuma;
            b = tonedLuma;
        }

        luma = luma(r, g, b);
        float peak = Math.max(r, Math.max(g, b));
        float chroma = peak > 1.0e-5f ? (peak - Math.min(r, Math.min(g, b))) / peak : 0.0f;
        float colourfulness = params[SATURATION] * (1.0f + params[VIBRANCE] * (1.0f - chroma));
        rgb[0] = Math.max(luma + (r - luma) * colourfulness, 0.0f);
        rgb[1] = Math.max(luma + (g - luma) * colourfulness, 0.0f);
        rgb[2] = Math.max(luma + (b - luma) * colourfulness, 0.0f);
    }

    static float contrastCurve(float value, float pivot, float contrast) {
        return value <= 0.0f ? 0.0f : pivot * (float) Math.pow(value / pivot, contrast);
    }

    /**
     * Shadows, midtones and highlights on a luminance, in perceptual units. Shadows weigh
     * p(1-p)^3 (no effect on black or white, most around a quarter grey), highlights p^2(1-p)
     * (most around two thirds), midtones a gamma. Each weight is small enough that the curve stays
     * monotonic at both ends of its slider.
     */
    static float tones(float luma, float shadows, float midtonesExponent, float highlights) {
        float p = linearToSrgb(luma);
        if (p < 1.0f) {
            float q = 1.0f - p;
            p += shadows * 0.95f * p * q * q * q;
            p += highlights * 0.9f * p * p * q;
        }
        p = (float) Math.pow(Math.max(p, 0.0f), midtonesExponent);
        return srgbToLinear(p);
    }

    /**
     * Highlight roll-off: identity below the knee and whenever nothing can pass white; above the
     * knee, a curve with slope 1 at the knee that reaches exactly 1 at the brightest possible input.
     */
    static float rolloff(float value, float maxInput) {
        if (maxInput <= 1.0f || value <= ROLLOFF_KNEE) {
            return value;
        }
        float span = 1.0f - ROLLOFF_KNEE;
        float t = (value - ROLLOFF_KNEE) / span;
        float s = (maxInput - ROLLOFF_KNEE) / span;
        float curve = t * (1.0f + t / (s * s)) / (1.0f + t);
        return ROLLOFF_KNEE + span * curve;
    }

    static float luma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    public static float srgbToLinear(float value) {
        return value <= 0.04045f ? value / 12.92f : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    public static float linearToSrgb(float value) {
        value = Math.max(value, 0.0f);
        return value <= 0.0031308f ? value * 12.92f : 1.055f * (float) Math.pow(value, 1.0 / 2.4) - 0.055f;
    }
}
