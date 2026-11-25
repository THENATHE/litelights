package com.thenathe.litelights.render.feature.lightpost;

import com.thenathe.litelights.render.RenderSettings;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColourGradeMathTest {
    private static final float[] NEUTRAL = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0};

    private final ColourGradeMath math = new ColourGradeMath();

    private float[] parametersFor(float[] grade) {
        float[] parameters = new float[ColourGradeMath.PARAMETER_COUNT];
        math.computeParameters(grade, parameters);
        return parameters;
    }

    private static float[] with(int slider, float value) {
        float[] grade = NEUTRAL.clone();
        grade[slider] = value;
        return grade;
    }

    private static float[] graded(float[] parameters, float r, float g, float b) {
        float[] rgb = {r, g, b};
        ColourGradeMath.applyGrade(parameters, rgb);
        return rgb;
    }

    @Test
    void neutralGradeLeavesEveryColourAlone() {
        float[] parameters = parametersFor(NEUTRAL);
        assertEquals(1.0f, parameters[ColourGradeMath.ROLLOFF_MAX], "no roll-off at neutral");
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            float r = random.nextFloat();
            float g = random.nextFloat();
            float b = random.nextFloat();
            assertArrayEquals(new float[] {r, g, b}, graded(parameters, r, g, b), 2.0e-5f);
        }
        assertArrayEquals(new float[] {0, 0, 0}, graded(parameters, 0, 0, 0), 1.0e-7f);
        assertArrayEquals(new float[] {1, 1, 1}, graded(parameters, 1, 1, 1), 2.0e-5f);
    }

    @Test
    void exposureIsInStopsAndRollsOffAtWhite() {
        float[] parameters = parametersFor(with(0, 1.0f));
        assertEquals(0.2f, graded(parameters, 0.1f, 0.1f, 0.1f)[0], 1.0e-4f, "one stop doubles the light");
        assertTrue(parameters[ColourGradeMath.ROLLOFF_MAX] > 1.9f, "white would reach two, so the roll-off switches on");
        float[] white = graded(parameters, 1, 1, 1);
        assertEquals(1.0f, white[0], 1.0e-4f, "the brightest input lands exactly on white");
        float[] nearWhite = graded(parameters, 0.9f, 0.9f, 0.9f);
        assertTrue(nearWhite[0] < white[0] && nearWhite[0] > 0.8f, "bright values keep their order below white");
    }

    @Test
    void negativeExposureNeedsNoRolloff() {
        float[] parameters = parametersFor(with(0, -1.0f));
        assertEquals(1.0f, parameters[ColourGradeMath.ROLLOFF_MAX]);
        assertEquals(0.4f, graded(parameters, 0.8f, 0.8f, 0.8f)[1], 1.0e-4f);
    }

    @Test
    void contrastTurnsAroundMidGrey() {
        for (float contrast : new float[] {-1.0f, 0.5f, 1.0f}) {
            float[] parameters = parametersFor(with(1, contrast));
            assertEquals(ColourGradeMath.MID_GREY, graded(parameters, 0.18f, 0.18f, 0.18f)[0], 1.0e-4f);
        }
        float[] more = parametersFor(with(1, 1.0f));
        assertTrue(graded(more, 0.05f, 0.05f, 0.05f)[0] < 0.05f, "more contrast darkens darks");
    }

    @Test
    void zeroSaturationGivesGreysOfTheSameLuminance() {
        float[] parameters = parametersFor(with(5, -1.0f));
        float[] grey = graded(parameters, 0.6f, 0.2f, 0.1f);
        assertEquals(grey[0], grey[1], 1.0e-6f);
        assertEquals(grey[1], grey[2], 1.0e-6f);
        assertEquals(ColourGradeMath.luma(0.6f, 0.2f, 0.1f), grey[0], 1.0e-5f);
    }

    @Test
    void hueShiftTurnsPrimariesAndKeepsGrey() {
        float[] parameters = parametersFor(with(7, 120.0f));
        assertArrayEquals(new float[] {0, 1, 0}, graded(parameters, 1, 0, 0), 1.0e-4f);
        assertArrayEquals(new float[] {0.5f, 0.5f, 0.5f}, graded(parameters, 0.5f, 0.5f, 0.5f), 1.0e-4f);
        float[] fullTurn = parametersFor(with(7, 180.0f));
        float[] halfTurnTwice = graded(fullTurn, 0.3f, 0.3f, 0.3f);
        assertArrayEquals(new float[] {0.3f, 0.3f, 0.3f}, halfTurnTwice, 1.0e-4f, "greys never change hue");
    }

    @Test
    void warmTemperatureMovesRedUpAndBlueDownAtTheSameBrightness() {
        float[] parameters = parametersFor(with(8, 1.0f));
        float[] warm = graded(parameters, 0.4f, 0.4f, 0.4f);
        assertTrue(warm[0] > warm[1] && warm[1] > warm[2]);
        assertEquals(0.4f, ColourGradeMath.luma(warm[0], warm[1], warm[2]), 2.0e-3f);
    }

    @Test
    void channelStrengthScalesItsChannel() {
        float[] parameters = parametersFor(with(10, 0.5f));
        assertArrayEquals(new float[] {0.25f, 0.5f, 0.5f}, graded(parameters, 0.5f, 0.5f, 0.5f), 1.0e-4f);
    }

    @Test
    void shadowAndHighlightCurvesStayMonotonic() {
        for (float slider : new float[] {-1.0f, 1.0f}) {
            float previousShadows = -1.0f;
            float previousHighlights = -1.0f;
            for (int step = 0; step <= 1000; step++) {
                float luma = step / 1000.0f;
                float shadows = ColourGradeMath.tones(luma, slider, 1.0f, 0.0f);
                float highlights = ColourGradeMath.tones(luma, 0.0f, 1.0f, slider);
                assertTrue(shadows >= previousShadows - 1.0e-6f, "shadows " + slider + " at " + luma);
                assertTrue(highlights >= previousHighlights - 1.0e-6f, "highlights " + slider + " at " + luma);
                previousShadows = shadows;
                previousHighlights = highlights;
            }
            assertEquals(0.0f, ColourGradeMath.tones(0.0f, slider, 1.0f, slider), 1.0e-6f, "black stays black");
            assertEquals(1.0f, ColourGradeMath.tones(1.0f, slider, 1.0f, slider), 1.0e-5f, "white stays white");
        }
        assertTrue(ColourGradeMath.tones(0.2f, 0.0f, (float) Math.pow(2.0, -0.7), 0.0f) > 0.2f, "positive midtones brighten");
    }

    @Test
    void rolloffKeepsSlopeAndOrder() {
        assertEquals(0.95f, ColourGradeMath.rolloff(0.95f, 1.0f), "off when nothing passes white");
        assertEquals(0.5f, ColourGradeMath.rolloff(0.5f, 3.0f), "below the knee untouched");
        assertEquals(1.0f, ColourGradeMath.rolloff(3.0f, 3.0f), 1.0e-6f);
        float justAbove = ColourGradeMath.rolloff(ColourGradeMath.ROLLOFF_KNEE + 1.0e-3f, 3.0f);
        assertEquals(ColourGradeMath.ROLLOFF_KNEE + 1.0e-3f, justAbove, 1.0e-5f, "slope 1 at the knee");
        float previous = 0.0f;
        for (int step = 0; step <= 300; step++) {
            float value = ColourGradeMath.rolloff(step / 100.0f, 3.0f);
            assertTrue(value >= previous && value <= 1.0f);
            previous = value;
        }
    }

    @Test
    void srgbConversionsRoundTripEveryByte() {
        for (int value = 0; value < 256; value++) {
            float encoded = value / 255.0f;
            float back = ColourGradeMath.linearToSrgb(ColourGradeMath.srgbToLinear(encoded));
            assertEquals(value, Math.round(back * 255.0f));
        }
    }

    @Test
    void timeOfDayWeightsSumToOneAndPeakAtTheirTimes() {
        float[] weights = new float[4];
        for (int time = 0; time < 24_000; time += 50) {
            ColourGradeMath.timeOfDayWeights(time, weights);
            float sum = weights[0] + weights[1] + weights[2] + weights[3];
            assertEquals(1.0f, sum, 1.0e-5f, "at " + time);
        }
        ColourGradeMath.timeOfDayWeights(0, weights);
        assertArrayEquals(new float[] {1, 0, 0, 0}, weights, "sunrise is dawn");
        ColourGradeMath.timeOfDayWeights(6_000, weights);
        assertArrayEquals(new float[] {0, 1, 0, 0}, weights, "noon is midday");
        ColourGradeMath.timeOfDayWeights(12_500, weights);
        assertArrayEquals(new float[] {0, 0, 1, 0}, weights, "sunset is dusk");
        ColourGradeMath.timeOfDayWeights(18_000, weights);
        assertArrayEquals(new float[] {0, 0, 0, 1}, weights, "midnight is night");
        ColourGradeMath.timeOfDayWeights(11_000, weights);
        assertTrue(weights[1] > 0 && weights[2] > 0, "afternoon fades midday into dusk");
        ColourGradeMath.timeOfDayWeights(23_000, weights);
        assertTrue(weights[3] > 0 && weights[0] > 0, "before sunrise night fades into dawn");
    }

    @Test
    void dayTimeFollowsTheSun() {
        assertEquals(6_000.0, ColourGradeMath.dayTimeFromSunTurns(0.0f), 1.0e-6);
        assertEquals(12_000.0, ColourGradeMath.dayTimeFromSunTurns(0.25f), 1.0e-3);
        assertEquals(18_000.0, ColourGradeMath.dayTimeFromSunTurns(0.5f), 1.0e-3);
        assertEquals(0.0, ColourGradeMath.dayTimeFromSunTurns(0.75f), 1.0e-3);
    }

    @Test
    void perTimeOfDayModeBlendsTheGrades() {
        RenderSettings.Grade brightDawn = new RenderSettings.Grade(
            1.0f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0);
        RenderSettings.ColorGrading grading = new RenderSettings.ColorGrading(RenderSettings.GradeSchedule.BY_TIME_OF_DAY,
            RenderSettings.Grade.NEUTRAL, brightDawn, RenderSettings.Grade.NEUTRAL, RenderSettings.Grade.NEUTRAL,
            RenderSettings.Grade.NEUTRAL);
        float[] grade = new float[ColourGradeMath.GRADE_VALUES];
        math.gradeAt(grading, 0.0, grade);
        assertEquals(1.0f, grade[0], 1.0e-6f);
        math.gradeAt(grading, 6_000.0, grade);
        assertEquals(0.0f, grade[0], 1.0e-6f);
        assertEquals(1.0f, grade[10], 1.0e-6f, "channel strengths blend around one, not zero");
        math.gradeAt(grading, 1_500.0, grade);
        assertTrue(grade[0] > 0.0f && grade[0] < 1.0f, "half way between dawn and midday");

        RenderSettings.ColorGrading wholeDay = new RenderSettings.ColorGrading(RenderSettings.GradeSchedule.ALL_DAY,
            brightDawn, RenderSettings.Grade.NEUTRAL, RenderSettings.Grade.NEUTRAL, RenderSettings.Grade.NEUTRAL,
            RenderSettings.Grade.NEUTRAL);
        math.gradeAt(wholeDay, 18_000.0, grade);
        assertEquals(1.0f, grade[0], 1.0e-6f, "whole-day mode ignores the time");
    }

    @Test
    void outlineColourSaturation() {
        float[] colour = new float[3];
        OutlineColours.linear(RenderSettings.GlowColour.GOLD, 0.0f, colour);
        assertEquals(colour[0], colour[1], 1.0e-6f);
        assertEquals(colour[1], colour[2], 1.0e-6f);
        OutlineColours.linear(RenderSettings.GlowColour.ENCHANT, 1.0f, colour);
        assertTrue(colour[2] > colour[0] && colour[0] > colour[1], "enchant purple: blue, then red, then green");
    }
}
