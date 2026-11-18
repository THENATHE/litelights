package com.thenathe.litelights.render.feature.weather;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** One storm wind for the particles, the sea and the lanterns: rain and thunder, and the strongest of sandstorm and blizzard. */
class StormWindTest {
    @Test
    void rainAndThunderBringSomeWind() {
        assertEquals(0.0f, Wind.stormStrength(0.0f, 0.0f, 0.0f, 0.0f));
        assertEquals(0.2475f, Wind.stormStrength(1.0f, 0.0f, 0.0f, 0.0f), 1.0e-6f);
        assertEquals(0.45f, Wind.stormStrength(1.0f, 1.0f, 0.0f, 0.0f), 1.0e-6f);
    }

    @Test
    void sandstormsAndBlizzardsBlowHardest() {
        assertEquals(1.0f, Wind.stormStrength(1.0f, 1.0f, 0.0f, 1.0f), "a blizzard");
        assertEquals(0.6f, Wind.stormStrength(1.0f, 0.0f, 0.6f, 0.0f), "a sandstorm in rain");
        assertEquals(0.45f, Wind.stormStrength(1.0f, 1.0f, 0.2f, 0.1f), "a weak storm under a thunderstorm's own wind");
    }
}
