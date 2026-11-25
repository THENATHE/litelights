package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which storms are drawn and how much: storms that come as storms do are whole, one that appears
 * whole in view closes in at the deck's pace, one cleared away while alive breaks up at its pace,
 * and a new view draws what stands in it at once.
 */
class StormPresenceTest {
    private static final double TICK = 50_000.0;

    /** A still storm 2 km east of the camera, born {@code age} ticks ago, living 30,000. */
    private static RegionalStorm storm(long id, double age) {
        return new RegionalStorm(id, 2_000.0, 0.0, TICK - age, 30_000.0, 0.0, 0.0, 600.0, 1.0, 0.0, 0.8f, false, 320, 0.29, 0.8, 1.0f);
    }

    private static float presenceOf(StormPresence presence, long id) {
        return presence.storms().stream().filter(storm -> storm.id() == id).map(RegionalStorm::presence).findFirst().orElse(0.0f);
    }

    @Test
    void theFirstListIsDrawnWhole() {
        StormPresence presence = new StormPresence();
        presence.update(List.of(storm(1L, 15_000.0)), TICK, 0.0, 0.0, 8_192.0);
        assertEquals(1.0f, presenceOf(presence, 1L));
    }

    @Test
    void aStormBornNowNeedsNoFadingIn() {
        StormPresence presence = new StormPresence();
        presence.update(List.of(), TICK, 0.0, 0.0, 8_192.0);
        presence.update(List.of(storm(2L, 20.0)), TICK, 0.0, 0.0, 8_192.0);
        assertEquals(1.0f, presenceOf(presence, 2L));
    }

    @Test
    void aStormDriftingInFromOutOfSightIsWhole() {
        StormPresence presence = new StormPresence();
        presence.update(List.of(), TICK, 0.0, 0.0, 8_192.0);
        presence.update(List.of(storm(3L, 15_000.0)), TICK, 0.0, 0.0, 1_000.0);
        assertEquals(1.0f, presenceOf(presence, 3L));
    }

    @Test
    void aStormPlacedWholeInViewClosesInAtTheDecksPace() {
        StormPresence presence = new StormPresence();
        presence.update(List.of(), TICK, 0.0, 0.0, 8_192.0);
        RegionalStorm placed = storm(4L, 15_000.0);
        presence.update(List.of(placed), TICK, 0.0, 0.0, 8_192.0);
        assertEquals(0.0f, presenceOf(presence, 4L));
        for (int tick = 0; tick < 350; tick++) {
            presence.tick();
        }
        assertEquals(0.5f, presenceOf(presence, 4L), 0.01f);
        for (int tick = 0; tick < 400; tick++) {
            presence.tick();
        }
        assertEquals(1.0f, presenceOf(presence, 4L));
    }

    @Test
    void aStormClearedAwayBreaksUpAndAStormThatEndedIsGone() {
        StormPresence presence = new StormPresence();
        presence.update(List.of(storm(5L, 15_000.0), storm(6L, 29_990.0)), TICK, 0.0, 0.0, 8_192.0);
        // Both leave the list: 5 is cleared while alive, 6 has lived out its life by the next ask.
        presence.update(List.of(), TICK + 20.0, 0.0, 0.0, 8_192.0);
        assertEquals(0.0f, presenceOf(presence, 6L));
        assertEquals(1.0f, presenceOf(presence, 5L));
        for (int tick = 0; tick < 1_200; tick++) {
            presence.tick();
        }
        assertEquals(0.5f, presenceOf(presence, 5L), 0.01f);
        for (int tick = 0; tick < 1_300; tick++) {
            presence.tick();
        }
        assertTrue(presence.storms().isEmpty());
    }

    @Test
    void aNewViewHasNothingLeftOverFromTheLastToBreakUp() {
        StormPresence presence = new StormPresence();
        presence.update(List.of(storm(8L, 15_000.0)), TICK, 0.0, 0.0, 8_192.0);
        presence.startAfresh();
        presence.update(List.of(), TICK, 50_000.0, 0.0, 8_192.0);
        assertTrue(presence.storms().isEmpty());
    }

    @Test
    void aNewViewIsDrawnWhole() {
        StormPresence presence = new StormPresence();
        presence.update(List.of(), TICK, 0.0, 0.0, 8_192.0);
        presence.startAfresh();
        presence.update(List.of(storm(7L, 15_000.0)), TICK, 0.0, 0.0, 8_192.0);
        assertEquals(1.0f, presenceOf(presence, 7L));
    }
}
