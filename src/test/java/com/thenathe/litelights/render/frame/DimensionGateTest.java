package com.thenathe.litelights.render.frame;

import com.thenathe.litelights.config.DimensionOptions.Aspect;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionGateTest {
    @Test
    void everythingAllowedClearsNothing() {
        assertEquals(0, DimensionGate.gatedBits(aspect -> true));
    }

    @Test
    void eachSwitchClearsOnlyItsOwnBits() {
        for (Aspect off : Aspect.values()) {
            int cleared = DimensionGate.gatedBits(aspect -> aspect != off);
            assertEquals(DimensionGate.bitsOf(off), cleared, off.name());
        }
    }

    @Test
    void skyAndFogBitsSitUnderTheRightSwitches() {
        int sky = DimensionGate.bitsOf(Aspect.SKY);
        int effects = DimensionGate.bitsOf(Aspect.EFFECTS);
        int weather = DimensionGate.bitsOf(Aspect.WEATHER);
        assertTrue((sky & FrameFlags.FEATURE_LITE_SKY) != 0);
        assertTrue((sky & FrameFlags.FEATURE_LITE_CLOUDS) != 0);
        assertTrue((effects & FrameFlags.FEATURE_LITE_FOG) != 0);
        assertTrue((effects & FrameFlags.FEATURE_GROUND_MIST) != 0);
        assertTrue((weather & FrameFlags.FEATURE_STORM_FOG) != 0);
        assertTrue((DimensionGate.bitsOf(Aspect.SHADOWS) & FrameFlags.FEATURE_SHADOWS) != 0);
    }

    @Test
    void switchesNeverShareABit() {
        int seen = 0;
        for (Aspect aspect : Aspect.values()) {
            int bits = DimensionGate.bitsOf(aspect);
            assertEquals(0, seen & bits, aspect + " shares a bit with another switch");
            seen |= bits;
        }
    }

    @Test
    void allOffClearsEverySwitchBit() {
        int all = 0;
        for (Aspect aspect : Aspect.values()) {
            all |= DimensionGate.bitsOf(aspect);
        }
        assertEquals(all, DimensionGate.gatedBits(aspect -> false));
    }
}
