package com.thenathe.litelights.render.feature.lightpost;

import com.thenathe.litelights.render.RenderSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tone curves: their fixed points, their shape and the parameters the shader gets. */
class ToneCurveTest {
    private static final int[] FILM_CURVES = {ColourGradeMath.CURVE_ACES, ColourGradeMath.CURVE_AGX, ColourGradeMath.CURVE_HABLE};

    private final ColourGradeMath math = new ColourGradeMath();

    private float[] curved(int curve, float white, float r, float g, float b) {
        float[] params = new float[ColourGradeMath.PARAMETER_COUNT];
        math.computeCurve(curve, white, params);
        float[] rgb = {r, g, b};
        ColourGradeMath.applyCurve(curve, params, white, rgb);
        return rgb;
    }

    private float grey(int curve, float value) {
        return curved(curve, 1.0f, value, value, value)[1];
    }

    @Test
    void vanillaFriendlyLeavesAnUngradedPictureAlone() {
        for (float value = 0.0f; value <= 1.0f; value += 0.05f) {
            assertArrayEquals(new float[] {value, value * 0.5f, 0.0f},
                curved(ColourGradeMath.CURVE_VANILLA_FRIENDLY, 1.0f, value, value * 0.5f, 0.0f), 1.0e-6f);
        }
    }

    @Test
    void everyCurveKeepsBlackAndWhite() {
        for (int curve : FILM_CURVES) {
            assertEquals(0.0f, grey(curve, 0.0f), 0.002f, "black, curve " + curve);
            assertEquals(1.0f, grey(curve, 1.0f), 0.002f, "white, curve " + curve);
        }
    }

    @Test
    void acesAndAgxKeepMidGrey() {
        assertEquals(0.18f, grey(ColourGradeMath.CURVE_ACES, 0.18f), 0.002f);
        assertEquals(0.18f, grey(ColourGradeMath.CURVE_AGX, 0.18f), 0.002f);
    }

    @Test
    void curvesHaveTheirOwnShape() {
        // ACES-style: deeper shadows and brighter upper midtones than the plain picture.
        assertTrue(grey(ColourGradeMath.CURVE_ACES, 0.03f) < 0.03f * 0.9f);
        assertTrue(grey(ColourGradeMath.CURVE_ACES, 0.5f) > 0.5f * 1.05f);
        // Hable filmic: lifted shadows and midtones.
        assertTrue(grey(ColourGradeMath.CURVE_HABLE, 0.03f) > 0.03f);
        assertTrue(grey(ColourGradeMath.CURVE_HABLE, 0.2f) > 0.2f);
        // No two curves agree.
        assertNotEquals(grey(ColourGradeMath.CURVE_ACES, 0.4f), grey(ColourGradeMath.CURVE_AGX, 0.4f), 0.002f);
        assertNotEquals(grey(ColourGradeMath.CURVE_AGX, 0.4f), grey(ColourGradeMath.CURVE_HABLE, 0.4f), 0.002f);
    }

    @Test
    void curvesRiseSteadilyWithoutCrushingShadows() {
        for (int curve : FILM_CURVES) {
            float previous = -1.0f;
            for (int step = 0; step <= 200; step++) {
                float value = grey(curve, step / 200.0f);
                assertTrue(value >= previous - 1.0e-6f, "curve " + curve + " falls at " + step);
                previous = value;
            }
            // A dark grey (sRGB 0.1) keeps at least half its brightness in sRGB terms.
            float dark = ColourGradeMath.srgbToLinear(0.1f);
            assertTrue(ColourGradeMath.linearToSrgb(grey(curve, dark)) > 0.05f, "curve " + curve + " crushes shadows");
        }
    }

    @Test
    void agxTurnsBrightSaturatedColoursTowardWhite() {
        float[] plain = {1.0f, 0.2f, 0.1f};
        float[] agx = curved(ColourGradeMath.CURVE_AGX, 1.0f, 1.0f, 0.2f, 0.1f);
        float plainSpread = plain[0] - plain[2];
        float agxSpread = agx[0] - agx[2];
        assertTrue(agxSpread < plainSpread, "AgX keeps a bright red as saturated as before: " + agxSpread);
        assertTrue(agx[0] <= 1.0f && agx[1] <= 1.0f && agx[2] <= 1.0f);
    }

    @Test
    void gradesPastWhiteStayInRange() {
        for (int curve : FILM_CURVES) {
            float[] rgb = curved(curve, 3.0f, 3.0f, 1.5f, 0.2f);
            for (float channel : rgb) {
                assertTrue(channel >= 0.0f && channel <= 1.0f, "curve " + curve + " left the range: " + channel);
            }
            assertEquals(1.0f, curved(curve, 3.0f, 3.0f, 3.0f, 3.0f)[1], 0.002f, "the grade's brightest white, curve " + curve);
        }
    }

    @Test
    void settingsChoicesMapToShaderNumbers() {
        assertEquals(ColourGradeMath.CURVE_VANILLA_FRIENDLY, ColourGradeMath.curveNumber(RenderSettings.ToneCurve.VANILLA_FRIENDLY));
        assertEquals(ColourGradeMath.CURVE_ACES, ColourGradeMath.curveNumber(RenderSettings.ToneCurve.ACES));
        assertEquals(ColourGradeMath.CURVE_AGX, ColourGradeMath.curveNumber(RenderSettings.ToneCurve.AGX));
        assertEquals(ColourGradeMath.CURVE_HABLE, ColourGradeMath.curveNumber(RenderSettings.ToneCurve.HABLE));
        // The shader takes the number from two bits of its flags.
        assertTrue(ColourGradeMath.CURVE_HABLE <= 3);
    }

    @Test
    void parametersFitThePushConstants() {
        float[] params = new float[ColourGradeMath.PARAMETER_COUNT];
        math.computeCurve(ColourGradeMath.CURVE_AGX, 1.0f, params);
        assertEquals(24, ColourGradeMath.PARAMETER_COUNT);
        assertTrue(ColourGradeMath.PARAMETER_COUNT * 4 <= 128, "push constants are 128 bytes at most");
        assertTrue(params[ColourGradeMath.CURVE_NORMALISE] > 0.0f);
    }
}
