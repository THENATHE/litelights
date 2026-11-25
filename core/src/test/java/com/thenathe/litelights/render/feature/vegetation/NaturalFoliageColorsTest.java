package com.thenathe.litelights.render.feature.vegetation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalFoliageColorsTest {
    private static double chroma(int rgb) {
        double[] lab = NaturalFoliageColors.toOklab(rgb);
        return Math.hypot(lab[1], lab[2]);
    }

    private static double hue(int rgb) {
        double[] lab = NaturalFoliageColors.toOklab(rgb);
        return (Math.toDegrees(Math.atan2(lab[2], lab[1])) + 360.0) % 360.0;
    }

    @Test
    void neonGreensGetSofter() {
        int jungleFoliage = 0x30BB0B;
        int softened = NaturalFoliageColors.soften(jungleFoliage);
        assertTrue(chroma(softened) < chroma(jungleFoliage) * 0.8, "jungle foliage keeps its neon");
        assertTrue(hue(softened) > 120 && hue(softened) < 150, "jungle foliage stops being green");
    }

    @Test
    void designedColoursOutsideTheGreensStayAsTheyAre() {
        assertEquals(0xDF6827, NaturalFoliageColors.soften(0xDF6827), "autumn orange grass (dappled forest)");
        assertEquals(0x9E814D, NaturalFoliageColors.soften(0x9E814D), "badlands foliage");
        assertEquals(0x808080, NaturalFoliageColors.soften(0x808080), "grey");
    }

    @Test
    void mutedGreensBarelyMove() {
        int paleGarden = 0x778272;
        int softened = NaturalFoliageColors.soften(paleGarden);
        for (int shift = 0; shift <= 16; shift += 8) {
            int channelBefore = (paleGarden >> shift) & 0xFF;
            int channelAfter = (softened >> shift) & 0xFF;
            assertTrue(Math.abs(channelBefore - channelAfter) <= 6, "pale garden grass moved too far");
        }
    }

    @Test
    void cachedAnswersMatchAndKeepAlpha() {
        for (int rgb : new int[] {0x91BD59, 0x77AB2F, 0x6A7039, 0x80B497}) {
            int expected = NaturalFoliageColors.soften(rgb);
            assertEquals(0xFF000000 | expected, NaturalFoliageColors.apply(0xFF000000 | rgb));
            assertEquals(0xFF000000 | expected, NaturalFoliageColors.apply(0xFF000000 | rgb));
            assertEquals(expected, NaturalFoliageColors.apply(rgb));
        }
    }
}
