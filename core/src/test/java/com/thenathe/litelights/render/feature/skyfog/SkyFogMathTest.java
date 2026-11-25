package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.LiteMath;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyFogMathTest {
    private static final float EPSILON = 1.0e-4f;

    @Test
    void uniformHazeIsDensityTimesDistance() {
        assertEquals(0.5f, FogShaderMirror.hazeDepth(250.0f, 0.0f, 30.0f, 0.002f, 0.0f), EPSILON);
    }

    @Test
    void levelRayAtTheBaseSeesFullDensity() {
        assertEquals(0.002f * 300.0f, FogShaderMirror.hazeDepth(300.0f, 0.0f, 0.0f, 0.002f, 1.0f / 55.0f), EPSILON);
    }

    @Test
    void hazeThinsWithHeight() {
        float low = FogShaderMirror.hazeDepth(200.0f, 0.0f, 0.0f, 0.002f, 1.0f / 55.0f);
        float high = FogShaderMirror.hazeDepth(200.0f, 120.0f, 0.0f, 0.002f, 1.0f / 55.0f);
        assertEquals(low * (float) Math.exp(-120.0 / 55.0), high, EPSILON);
    }

    @Test
    void lookingUpMeetsLessHazeThanLookingAcross() {
        float across = FogShaderMirror.hazeDepth(200.0f, 10.0f, 0.0f, 0.002f, 1.0f / 55.0f);
        float up = FogShaderMirror.hazeDepth(200.0f, 10.0f, 150.0f, 0.002f, 1.0f / 55.0f);
        assertTrue(up < across * 0.5f, "up " + up + " across " + across);
    }

    @Test
    void depthMatchesNumericIntegration() {
        float distance = 180.0f;
        float camera = 25.0f;
        float rise = -20.0f;
        float density = 0.003f;
        float falloff = 0.02f;
        double sum = 0.0;
        int steps = 20_000;
        for (int i = 0; i < steps; i++) {
            double t = (i + 0.5) / steps;
            double height = Math.max(camera + rise * t, 0.0);
            sum += density * Math.exp(-falloff * height) * distance / steps;
        }
        assertEquals(sum, FogShaderMirror.hazeDepth(distance, camera, rise, density, falloff), 1.0e-3);
    }

    @Test
    void belowTheBaseTheHazeStopsThickening() {
        float atBase = FogShaderMirror.hazeDepth(100.0f, 0.0f, 0.0f, 0.002f, 0.02f);
        float deepBelow = FogShaderMirror.hazeDepth(100.0f, -60.0f, 0.0f, 0.002f, 0.02f);
        assertEquals(atBase, deepBelow, EPSILON);
    }

    @Test
    void nearlyLevelRaysDontBlowUp() {
        float level = FogShaderMirror.hazeDepth(100.0f, 10.0f, 0.0f, 0.002f, 0.02f);
        float almost = FogShaderMirror.hazeDepth(100.0f, 10.0f, 1.0e-4f, 0.002f, 0.02f);
        assertEquals(level, almost, 1.0e-5f);
    }

    @Test
    void coverageIsBoundedAndMonotonic() {
        assertEquals(0.0f, FogShaderMirror.coverage(0.0f), EPSILON);
        assertEquals(0.0f, FogShaderMirror.coverage(-3.0f), EPSILON);
        assertTrue(FogShaderMirror.coverage(1.0f) < FogShaderMirror.coverage(2.0f));
        assertTrue(FogShaderMirror.coverage(50.0f) <= 1.0f);
    }

    @Test
    void renderFogStartsInTheLastTenthWithinLimits() {
        assertEquals(192.0f - 19.2f, FogShaderMirror.renderFogStart(192.0f), EPSILON);
        assertEquals(32.0f - 4.0f, FogShaderMirror.renderFogStart(32.0f), EPSILON);
        assertEquals(1024.0f - 64.0f, FogShaderMirror.renderFogStart(1024.0f), EPSILON);
    }

    @Test
    void linearFogMatchesVanilla() {
        assertEquals(0.0f, FogShaderMirror.linearFog(10.0f, 20.0f, 40.0f), EPSILON);
        assertEquals(0.5f, FogShaderMirror.linearFog(30.0f, 20.0f, 40.0f), EPSILON);
        assertEquals(1.0f, FogShaderMirror.linearFog(50.0f, 20.0f, 40.0f), EPSILON);
    }

    @Test
    void approachIsIndependentOfFrameRate() {
        float oneStep = SkyFogMath.approachFactor(0.8f, 1.0f);
        float remaining = 1.0f;
        for (int i = 0; i < 60; i++) {
            remaining *= 1.0f - SkyFogMath.approachFactor(0.8f, 1.0f / 60.0f);
        }
        assertEquals(1.0f - oneStep, remaining, 1.0e-4f);
        assertEquals(0.0f, SkyFogMath.approachFactor(0.8f, 0.0f), EPSILON);
    }

    @Test
    void daylightAndTwilightCurves() {
        assertEquals(0.0f, SkyFogMath.daylight(-0.5f), EPSILON);
        assertEquals(1.0f, SkyFogMath.daylight(0.6f), EPSILON);
        assertTrue(SkyFogMath.daylight(0.0f) > 0.3f && SkyFogMath.daylight(0.0f) < 0.6f);
        assertEquals(1.0f, SkyFogMath.twilight(0.0f), EPSILON);
        assertTrue(SkyFogMath.twilight(0.5f) < 0.01f);
        assertEquals(SkyFogMath.twilight(0.1f), SkyFogMath.twilight(-0.1f), EPSILON);
    }

    @Test
    void derivedHazeKeepsTheHueButStaysCalm() {
        // A neon green modded biome gets a green-leaning haze, not neon.
        int haze = SkyFogMath.derivedHazeColor(0x00FF00, 0x40FF40, 0x00FF80);
        float red = LiteMath.red(haze);
        float green = LiteMath.green(haze);
        float blue = LiteMath.blue(haze);
        assertTrue(green > red && green > blue, "green should lead: " + Integer.toHexString(haze));
        float luma = SkyFogMath.luminance(red, green, blue);
        assertTrue(luma >= 0.44f && luma <= 0.89f, "luma " + luma);
        float spread = Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
        assertTrue(spread < 0.7f, "too saturated: " + Integer.toHexString(haze));
    }

    @Test
    void derivedHazeOfBlackIsAGrey() {
        int haze = SkyFogMath.derivedHazeColor(0x000000, 0x000000, 0x000000);
        assertEquals(LiteMath.red(haze), LiteMath.blue(haze), EPSILON);
        assertTrue(LiteMath.green(haze) >= 0.44f);
    }

    @Test
    void derivedHazeOfAVanillaLikeSkyStaysPaleBlue() {
        int haze = SkyFogMath.derivedHazeColor(0xC0D8FF, 0x78A7FF, 0x3F76E4);
        assertTrue(LiteMath.blue(haze) > LiteMath.red(haze));
        assertTrue(SkyFogMath.luminance(LiteMath.red(haze), LiteMath.green(haze), LiteMath.blue(haze)) > 0.5f);
    }

    @Test
    void mixRgbBlendsEachChannel() {
        assertEquals(0x808080, LiteMath.mixRgb(0x000000, 0xFFFFFF, 0.5f) & 0xFEFEFE);
        assertEquals(0xFF0000, LiteMath.mixRgb(0xFF0000, 0x00FF00, 0.0f));
        assertEquals(0x00FF00, LiteMath.mixRgb(0xFF0000, 0x00FF00, 1.0f));
    }

    @Test
    void stormFogIsClearCloseByAndAsThickFarOff() {
        float density = 0.086f;
        assertEquals(0.0f, SkyFogMath.stormDepth(0.0f, density), 1.0e-6f);
        // A blizzard's snow ten blocks out: well under half the fog an even fog put over it.
        assertTrue(SkyFogMath.stormDepth(10.0f, density) < 0.5f * density * 10.0f);
        // Fifty blocks out about as thick as before, and further thicker, so the distance still closes in.
        assertEquals(density * 50.0f, SkyFogMath.stormDepth(50.0f, density), 0.1f * density * 50.0f);
        assertTrue(SkyFogMath.stormDepth(80.0f, density) > density * 80.0f);
        // The particles' fog cull never drops anything the fog doesn't hide.
        float hidden = 4.2f;
        float beyond = SkyFogMath.stormDistanceBeyond(hidden, density);
        assertTrue(SkyFogMath.stormDepth(beyond, density) >= hidden);
    }

    @Test
    void theSunIsGoneOnlyOnceItHasSet() {
        assertEquals(0.0f, SkyFogMath.sunGone(0.5f));
        assertEquals(0.0f, SkyFogMath.sunGone(0.03f));
        assertTrue(SkyFogMath.sunGone(0.0f) > 0.1f && SkyFogMath.sunGone(0.0f) < 0.4f);
        assertEquals(1.0f, SkyFogMath.sunGone(-0.08f));
        assertEquals(1.0f, SkyFogMath.sunGone(-0.5f));
    }

    @Test
    void hazeLookingDownFromFarAboveIsFiniteAndSmall() {
        // From 900 blocks over the haze's base down to the ground 1,200 blocks away: the old form
        // took exp(16+) times exp(-16-) and, with denormals flushed, came out NaN.
        float falloff = 1.0f / 10.0f;
        float depth = FogShaderMirror.hazeDepth(1200.0f, 900.0f, -900.0f, 0.002f, falloff);
        assertTrue(Float.isFinite(depth), "finite: " + depth);
        float expected = 0.002f * 1200.0f * (float) ((Math.exp(-falloff * 900.0) - 1.0) / (falloff * -900.0));
        assertEquals(expected, depth, 1.0e-6f);
        // The same path looked up instead of down gives the same depth.
        assertEquals(depth, FogShaderMirror.hazeDepth(1200.0f, 0.0f, 900.0f, 0.002f, falloff), 1.0e-6f);
    }
}
