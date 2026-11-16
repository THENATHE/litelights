package com.thenathe.litelights.overlays;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OverlayRendererTest {
    @Test
    void continuityDrawsWhenInstalled() {
        assertEquals(OverlayRenderer.CONTINUITY, OverlayRenderer.choose(true, false).renderer());
    }

    @Test
    void turnsOffWithoutContinuity() {
        OverlayRenderer.Choice choice = OverlayRenderer.choose(false, false);
        assertFalse(choice.active());
        assertEquals(OverlayRenderer.OffReason.NO_CONTINUITY, choice.reason());
    }

    @Test
    void standsAsideForTheStandaloneModWhateverElseIsInstalled() {
        for (boolean continuity : new boolean[] {false, true}) {
            OverlayRenderer.Choice choice = OverlayRenderer.choose(continuity, true);
            assertFalse(choice.active());
            assertEquals(OverlayRenderer.OffReason.STANDALONE_INSTALLED, choice.reason());
        }
    }
}
