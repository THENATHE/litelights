package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.feature.vegetation.WindField;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How falling leaves ride the wind (FallingLeaves, LeafWind). */
class LeafWindTest {
    @Test
    void leavesFeelTheGustsTheSwayingPlantsFeel() {
        for (float x = -300.0f; x < 300.0f; x += 37.0f) {
            for (float seconds = 0.0f; seconds < 3600.0f; seconds += 311.0f) {
                float plants = WindField.gust(Math.floorMod((int) x, 256) + (x - (float) Math.floor(x)), 11.0f, seconds, 0.0f)[2];
                assertEquals(plants, LeafWind.gust(x, 11.0, seconds), 1.0e-3, "at x " + x + ", " + seconds + " s");
            }
        }
    }

    @Test
    void theGustsRangeFromATenthToFull() {
        float least = 1.0f;
        float most = 0.0f;
        for (int i = 0; i < 20000; i++) {
            float gust = LeafWind.gust(i * 0.731, i * 1.37, i * 0.29);
            least = Math.min(least, gust);
            most = Math.max(most, gust);
        }
        assertTrue(least >= 0.099f && least < 0.2f, "least " + least);
        assertTrue(most <= 1.001f && most > 0.9f, "most " + most);
    }

    @Test
    void calmDaysAreZeroAndTheStrongestStormsOne() {
        assertEquals(0.0f, LeafWind.strength(0.03f, 0.0f));
        assertEquals(0.0f, LeafWind.strength(0.0f, -0.04f));
        assertEquals(1.0f, LeafWind.strength(0.0f, 0.35f));
        assertEquals(0.5f, LeafWind.strength(0.17f, 0.0f), 1.0e-6f);
    }

    @Test
    void onACalmDayLeavesFallNearlyStraight() {
        // Vanilla's leaves fall about 0.02 to 0.06 blocks a tick; the calm wind moves them a sixth of that at most.
        float drift = LeafWind.CALM * LeafWind.carry(0.0f, 1.0f);
        assertTrue(drift <= 0.01f, "drift " + drift);
        assertEquals(1.0f, LeafWind.release(0.0f, 1.0f));
        assertEquals(0.0, LeafWind.lift(0.0f, 1.0f, -0.03));
    }

    @Test
    void stormsCarryLeavesSidewaysAndShedMoreInGusts() {
        float drift = LeafWind.STORM * LeafWind.carry(1.0f, 1.0f);
        assertTrue(drift > 0.2f, "drift " + drift);
        assertTrue(LeafWind.carry(1.0f, 1.0f) > LeafWind.carry(1.0f, 0.1f) * 2.0f, "gusts carry more than lulls");
        assertEquals(LeafWind.MOST_LEAVES, LeafWind.release(1.0f, 1.0f));
        assertTrue(LeafWind.release(1.0f, 0.1f) < 1.3f);
    }

    @Test
    void aGustSlowsTheFallButNeverLiftsALeafUp() {
        assertEquals(0.012, LeafWind.lift(1.0f, 1.0f, -0.05), 1.0e-6);
        assertEquals(0.006, LeafWind.lift(1.0f, 1.0f, -0.01), 1.0e-6);
        assertEquals(0.0, LeafWind.lift(1.0f, 1.0f, 0.0));
        assertEquals(0.0, LeafWind.lift(1.0f, 1.0f, 0.02));
    }

    @Test
    void aLeafDropsClearOfItsCanopyBeforeTheWindTakesIt() {
        assertEquals(0.0f, LeafWind.lettingGo(0));
        assertTrue(LeafWind.lettingGo(LeafWind.LETTING_GO_TICKS / 4) < 0.2f);
        assertEquals(1.0f, LeafWind.lettingGo(LeafWind.LETTING_GO_TICKS));
        assertEquals(1.0f, LeafWind.lettingGo(299));
    }
}
