package com.thenathe.litelights.render.feature.farterrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarWaterCaptureTest {
    @Test
    void openGl46CopiesDirectly() {
        assertTrue(FarWaterCapture.hasDirectCopy(true, true, true, true));
    }

    @Test
    void olderCoreWithBothExtensionsCopiesDirectly() {
        assertTrue(FarWaterCapture.hasDirectCopy(false, true, false, true));
    }

    @Test
    void macOsOpenGl41DoesNot() {
        assertFalse(FarWaterCapture.hasDirectCopy(false, false, false, false));
    }

    @Test
    void eitherHalfMissingIsEnough() {
        assertFalse(FarWaterCapture.hasDirectCopy(false, false, true, true));
        assertFalse(FarWaterCapture.hasDirectCopy(false, true, false, false));
    }
}
