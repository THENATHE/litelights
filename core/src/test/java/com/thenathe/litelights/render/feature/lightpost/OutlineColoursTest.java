package com.thenathe.litelights.render.feature.lightpost;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.render.RenderSettings;
import org.junit.jupiter.api.Test;

class OutlineColoursTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void aTrimNameColourIsBrightenedToFullKeepingItsHue() {
        float[] out = new float[3];
        // Vanilla's redstone trim: #971607.
        assertTrue(OutlineColours.trim(0x971607, null, 1.0f, out));
        assertEquals(1.0f, out[0], EPSILON);
        assertEquals(0x16 / (float) 0x97, out[1], EPSILON);
        assertEquals(0x07 / (float) 0x97, out[2], EPSILON);
    }

    @Test
    void goldAndDiamondTrimsComeOutGoldAndDiamond() {
        float[] gold = new float[3];
        float[] diamond = new float[3];
        assertTrue(OutlineColours.trim(0xDEB12D, null, 1.0f, gold));
        assertTrue(OutlineColours.trim(0x6EECD2, null, 1.0f, diamond));
        assertEquals(1.0f, gold[0], EPSILON);
        assertTrue(gold[0] > gold[1] && gold[1] > gold[2], "gold is red over green over blue");
        assertEquals(1.0f, diamond[1], EPSILON);
        assertTrue(diamond[1] > diamond[2] && diamond[2] > diamond[0], "diamond is green over blue over red");
    }

    @Test
    void theNameColourWinsOverThePalette() {
        float[] fromName = new float[3];
        float[] withPalette = new float[3];
        OutlineColours.trim(0x416E97, null, 1.0f, fromName);
        OutlineColours.trim(0x416E97, new int[] {0xFFFF0000, 0xFFFF0000}, 1.0f, withPalette);
        assertArrayEquals(fromName, withPalette, EPSILON);
    }

    @Test
    void withoutANameColourThePaletteAverageIsUsed() {
        float[] out = new float[3];
        // A modded material whose name has no colour: its palette's opaque pixels are averaged, the
        // transparent one left out.
        int[] palette = {0xFF804000, 0xFF402000, 0x00FFFFFF};
        assertTrue(OutlineColours.trim(null, palette, 1.0f, out));
        assertArrayEquals(new float[] {1.0f, 0.5f, 0.0f}, out, 0.01f);
    }

    @Test
    void aBlackNameColourFallsBackToThePalette() {
        float[] out = new float[3];
        assertTrue(OutlineColours.trim(0x000000, new int[] {0xFF00FF00}, 1.0f, out));
        assertArrayEquals(new float[] {0.0f, 1.0f, 0.0f}, out, EPSILON);
    }

    @Test
    void noUsableColourMeansTheGlowColourIsKept() {
        float[] out = new float[3];
        assertFalse(OutlineColours.trim(null, null, 1.0f, out));
        assertFalse(OutlineColours.trim(null, new int[0], 1.0f, out));
        assertFalse(OutlineColours.trim(null, new int[] {0x00FF0000}, 1.0f, out));
        assertFalse(OutlineColours.trim(0x000000, new int[] {0xFF000000}, 1.0f, out));
    }

    @Test
    void theSaturationSliderAppliesToTrimColoursAsToTheGlowColour() {
        float[] grey = new float[3];
        assertTrue(OutlineColours.trim(0x971607, null, 0.0f, grey));
        assertEquals(grey[0], grey[1], EPSILON);
        assertEquals(grey[1], grey[2], EPSILON);

        float[] trim = new float[3];
        float[] chosen = new float[3];
        OutlineColours.trim(0xFF4743, null, 0.6f, trim);
        OutlineColours.linear(RenderSettings.GlowColour.RED, 0.6f, chosen);
        // #FF4743 is the RED preset at full brightness, so both come out the same.
        assertArrayEquals(chosen, trim, 0.01f);
    }

    @Test
    void coloursPackAsOpaqueVertexColours() {
        assertEquals(0xFFFF8000, OutlineColours.packed(new float[] {1.0f, 128.0f / 255.0f, 0.0f}));
        assertEquals(0xFF0000FF, OutlineColours.packed(new float[] {-0.2f, 0.0f, 1.5f}));
    }
}
