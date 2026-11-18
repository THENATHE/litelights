package com.thenathe.litelights.render.frame;

import com.thenathe.litelights.config.DimensionOptions.Aspect;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionGateTest {
    @Test
    void weatherNeedsTheGamesWeatherUnderTheOverworldsSky() {
        // Level#canHaveWeather: sky light, no ceiling, not the End.
        assertTrue(DimensionGate.weatherDimension(DimensionType.Skybox.OVERWORLD, true), "the overworld");
        assertFalse(DimensionGate.weatherDimension(DimensionType.Skybox.NONE, false), "the Nether");
        assertFalse(DimensionGate.weatherDimension(DimensionType.Skybox.END, false), "the End");
        // Modded dimensions: rain, its fog and puddles all agree now.
        assertFalse(DimensionGate.weatherDimension(DimensionType.Skybox.OVERWORLD, false), "an overworld sky where the game keeps no weather");
        assertFalse(DimensionGate.weatherDimension(DimensionType.Skybox.NONE, true), "weather with no sky of the overworld's kind");
        assertFalse(DimensionGate.weatherDimension(DimensionType.Skybox.END, true), "the End's sky");
    }

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
