package com.thenathe.litelights.render.feature.weather;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuroraColdnessTest {
    private static final float SNOWY = 0.0f;
    private static final float PLAINS = 0.8f;

    /** Snowy land west of x = 0, plains from there east. */
    private static final AuroraColdness.Land BOUNDARY = (x, z) -> AuroraColdness.coldness(x < 0 ? SNOWY : PLAINS);

    @Test
    void coldnessFollowsTheBiomeTemperature() {
        assertEquals(1.0f, AuroraColdness.coldness(SNOWY));
        assertEquals(1.0f, AuroraColdness.coldness(0.15f), 1e-6f);
        assertEquals(0.5f, AuroraColdness.coldness(0.25f), 1e-6f);
        assertEquals(0.0f, AuroraColdness.coldness(PLAINS));
    }

    @Test
    void spruceForestsTooMildForSnowStillGetADimmerAurora() {
        assertEquals(AuroraColdness.SPRUCE_FOREST, AuroraColdness.coldness(0.6f, true), "the dappled forest");
        assertEquals(AuroraColdness.SPRUCE_FOREST, AuroraColdness.coldness(0.3f, true), "old growth pine taiga, 0.25 before");
        assertEquals(0.75f, AuroraColdness.coldness(0.2f, true), 1e-6f, "windswept forest keeps its own, higher coldness");
        assertEquals(1.0f, AuroraColdness.coldness(-0.5f, true), "snowy taiga stays full");
        assertEquals(0.0f, AuroraColdness.coldness(0.6f, false), "a forest without spruce stays warm");
    }

    @Test
    void landAllOneBiomeGivesThatBiomesColdness() {
        assertEquals(1.0f, AuroraColdness.around(100, -50, (x, z) -> 1.0f), 1e-6f);
        assertEquals(0.0f, AuroraColdness.around(100, -50, (x, z) -> 0.0f), 1e-6f);
    }

    @Test
    void walkingOverABoundaryChangesItBitByBitInsteadOfAtOnce() {
        float before = AuroraColdness.around(-40, 0, BOUNDARY);
        float after = AuroraColdness.around(40, 0, BOUNDARY);
        assertEquals(1.0f, before, 1e-6f);
        assertEquals(0.0f, after, 1e-6f);
        float last = before;
        int changes = 0;
        for (int x = -39; x <= 40; x++) {
            float now = AuroraColdness.around(x, 0, BOUNDARY);
            assertTrue(now <= last, "it only falls walking out of the cold");
            assertTrue(last - now <= 0.2f + 1e-6f, "no step larger than one grid column at x = " + x);
            if (now < last) {
                changes++;
            }
            last = now;
        }
        assertEquals(2 * AuroraColdness.REACH + 1, changes, "one step for each grid column that crosses");
        assertEquals(0.6f, AuroraColdness.around(-1, 0, BOUNDARY), 1e-6f);
    }

    @Test
    void theFirstReadingIsTakenAsItIs() {
        AuroraColdness coldness = new AuroraColdness();
        assertEquals(0.7f, coldness.ease(0.7f));
    }

    @Test
    void aSuddenChangeFadesOverAFewSeconds() {
        AuroraColdness coldness = new AuroraColdness();
        coldness.ease(1.0f);
        float now = 1.0f;
        int ticks = 0;
        while (now > 0.5f) {
            now = coldness.ease(0.0f);
            ticks++;
        }
        assertTrue(ticks >= 20, "half the change takes at least a second, took " + ticks + " ticks");
        for (int tick = ticks; tick < 100; tick++) {
            now = coldness.ease(0.0f);
        }
        assertTrue(now < 0.05f, "nearly gone after five seconds: " + now);
    }

    @Test
    void aResetTakesTheNextReadingAtOnce() {
        AuroraColdness coldness = new AuroraColdness();
        coldness.ease(1.0f);
        coldness.reset();
        assertEquals(0.0f, coldness.ease(0.0f));
    }
}
