package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.config.PreviewEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireworksShowTest {
    private static final long SEED = 0x1234_5678_9ABCL;
    private static final int SEA = 63;
    private static final float CLOUD_FLOOR = 192.0f;
    private static final FireworksShow.Ground UNKNOWN_LAND = (x, z, radius) -> FireworksShow.Ground.UNKNOWN;

    private static SkyEventSchedule.Night fireworksNight() {
        for (long index = 0; ; index++) {
            SkyEventSchedule.Night night = SkyEventSchedule.night(SEED, index);
            if (night.fireworks()) {
                return night;
            }
        }
    }

    @Test
    void aShowIsPlacedInTheNightsDirectionAtItsDistance() {
        SkyEventSchedule.Night night = fireworksNight();
        FireworksShow.Place place = FireworksShow.place(night, 1_000.0, -2_000.0, UNKNOWN_LAND, SEA, CLOUD_FLOOR, false);
        assertNotNull(place);
        double fromX = FireworksShow.anchor(1_000.0);
        double fromZ = FireworksShow.anchor(-2_000.0);
        double dx = place.x() - fromX;
        double dz = place.z() - fromZ;
        assertEquals(night.fireworksDistance(), Math.hypot(dx, dz), 0.01);
        assertEquals(0.0, Math.IEEEremainder(night.fireworksAzimuth() - Math.atan2(dx, -dz), Math.PI * 2.0), 1.0e-4,
            "0 is north (-z), turning east (+x)");
        assertEquals(SEA, place.ground());
        assertEquals(CLOUD_FLOOR - FireworksShow.UNDER_CLOUDS, place.ceiling());
    }

    @Test
    void playersStandingTogetherSeeTheSamePlaceAndOthersTheirOwn() {
        SkyEventSchedule.Night night = fireworksNight();
        FireworksShow.Place one = FireworksShow.place(night, 300.0, 300.0, UNKNOWN_LAND, SEA, CLOUD_FLOOR, false);
        FireworksShow.Place beside = FireworksShow.place(night, 370.0, 450.0, UNKNOWN_LAND, SEA, CLOUD_FLOOR, false);
        assertEquals(one, beside, "both in the same square of 256 blocks");
        // Twenty squares east.
        FireworksShow.Place far = FireworksShow.place(night, 300.0 + 5_120.0, 300.0, UNKNOWN_LAND, SEA, CLOUD_FLOOR, false);
        assertEquals(5_120.0, far.x() - one.x(), 0.01, "as far apart as the players are, at the same bearing from each");
        assertEquals(one.z(), far.z(), 0.01);
    }

    @Test
    void aPreviewedShowIsInFrontOfTheCameraItself() {
        float bearing = 1.2f;
        SkyEventSchedule.Night preview = SkyEventSchedule.preview(3, PreviewEvent.Event.FIREWORKS, bearing);
        FireworksShow.Place place = FireworksShow.place(preview, 10.3, 20.7, UNKNOWN_LAND, SEA, CLOUD_FLOOR, true);
        assertEquals(FireworksShow.PREVIEW_DISTANCE, Math.hypot(place.x() - 10.3, place.z() - 20.7), 0.01);
        assertEquals(0.0, Math.IEEEremainder(bearing - Math.atan2(place.x() - 10.3, -(place.z() - 20.7)), Math.PI * 2.0), 1.0e-4);
    }

    /** Every burst of a long stretch of the night's show, placed. */
    private static void eachBurst(SkyEventSchedule.Night night, FireworksShow.Place place, BurstCheck check) {
        float[] firework = new float[6];
        double[] burst = new double[4];
        for (long beat = 0; beat < 2_000; beat++) {
            for (int index = 0; index < SkyEventSchedule.volleySize(SEED, night, beat); index++) {
                SkyEventSchedule.firework(SEED, night, beat, index, firework);
                check.accept(firework, FireworksShow.burst(place, firework, burst), burst);
            }
        }
    }

    private interface BurstCheck {
        void accept(float[] firework, boolean placed, double[] burst);
    }

    @Test
    void burstsStayUnderTheCloudsAndOverTheLand() {
        SkyEventSchedule.Night night = fireworksNight();
        for (int ground : new int[] {SEA, 80, 100}) {
            for (float floor : new float[] {CLOUD_FLOOR, 240.0f, 320.0f}) {
                FireworksShow.Place place = new FireworksShow.Place(0.0, 0.0, 0.7f, ground, floor - FireworksShow.UNDER_CLOUDS);
                eachBurst(night, place, (firework, placed, burst) -> {
                    assertTrue(placed, "room for every burst over " + ground + " under " + floor);
                    double radius = burst[3];
                    assertTrue(burst[1] + radius <= place.ceiling() + 1.0e-3, "the highest spark stays under the clouds");
                    assertTrue(burst[1] - FireworksShow.REACH_BELOW * radius >= ground + FireworksShow.CLEARANCE - 1.0e-3,
                        "the lowest spark, fallen at the end, stays over the land");
                    assertTrue(burst[1] - ground <= FireworksShow.HIGHEST_OVER_LAND + 1.0e-3, "a town's height, not the sky's");
                });
            }
        }
    }

    @Test
    void withNoCloudsThereIsNoCeilingButATownsHeight() {
        SkyEventSchedule.Night night = fireworksNight();
        FireworksShow.Place place = FireworksShow.place(night, 0.0, 0.0, UNKNOWN_LAND, SEA, Float.POSITIVE_INFINITY, false);
        assertTrue(Float.isInfinite(place.ceiling()));
        double[] highest = {0.0};
        eachBurst(night, place, (firework, placed, burst) -> {
            assertTrue(placed);
            assertEquals(firework[2], burst[3], 1.0e-6, "every burst at its own size");
            highest[0] = Math.max(highest[0], burst[1]);
        });
        assertTrue(highest[0] > SEA + 140.0 && highest[0] <= SEA + FireworksShow.HIGHEST_OVER_LAND + 1.0e-3, "up to " + highest[0]);
    }

    @Test
    void burstsSpreadOverTheTownAndKeepTheirSizesUnderOrdinaryClouds() {
        SkyEventSchedule.Night night = fireworksNight();
        FireworksShow.Place place = new FireworksShow.Place(500.0, -800.0, 0.0f, SEA, CLOUD_FLOOR - FireworksShow.UNDER_CLOUDS);
        double[] spread = {0.0, 0.0};
        eachBurst(night, place, (firework, placed, burst) -> {
            assertEquals(firework[2], burst[3], 1.0e-6, "room for the largest bursts over the sea under the default clouds");
            // Placed due north: across the line of sight is x, along it z.
            spread[0] = Math.max(spread[0], Math.abs(burst[0] - place.x()));
            spread[1] = Math.max(spread[1], Math.abs(burst[2] - place.z()));
        });
        assertTrue(spread[0] <= FireworksShow.SPREAD_ACROSS + 1.0e-6 && spread[0] > FireworksShow.SPREAD_ACROSS * 0.9);
        assertTrue(spread[1] <= FireworksShow.SPREAD_ALONG + 1.0e-6 && spread[1] > FireworksShow.SPREAD_ALONG * 0.9);
    }

    @Test
    void underLowCloudsBurstsGoLowerAndSmallerOrNotAtAll() {
        SkyEventSchedule.Night night = fireworksNight();
        float squeezed = FireworksShow.ROOM_NEEDED + 20.0f;
        FireworksShow.Place low = new FireworksShow.Place(0.0, 0.0, 0.0f, 100, 100 + squeezed);
        boolean[] shrank = {false};
        eachBurst(night, low, (firework, placed, burst) -> {
            assertTrue(placed);
            assertTrue(burst[1] + burst[3] <= low.ceiling() + 1.0e-3);
            assertTrue(burst[1] - FireworksShow.REACH_BELOW * burst[3] >= low.ground() + FireworksShow.CLEARANCE - 1.0e-3);
            shrank[0] |= burst[3] < firework[2] - 1.0e-3;
        });
        assertTrue(shrank[0], "the larger bursts shrink to fit");
        FireworksShow.Place none = new FireworksShow.Place(0.0, 0.0, 0.0f, 100, 100 + FireworksShow.ROOM_NEEDED - 1.0f);
        eachBurst(night, none, (firework, placed, burst) -> assertTrue(!placed, "no room: no burst"));
    }

    @Test
    void aShowOverAMountainMovesAsideAndNoneWhereAllIsMountain() {
        SkyEventSchedule.Night night = fireworksNight();
        double fromX = FireworksShow.anchor(0.0);
        double fromZ = FireworksShow.anchor(0.0);
        double ownX = fromX + Math.sin(night.fireworksAzimuth()) * night.fireworksDistance();
        double ownZ = fromZ - Math.cos(night.fireworksAzimuth()) * night.fireworksDistance();
        // A mountain 400 blocks round the night's own place, up to y 170.
        FireworksShow.Ground mountain = (x, z, radius) -> Math.hypot(x - ownX, z - ownZ) < 400.0 + radius ? 170 : 64;
        FireworksShow.Place moved = FireworksShow.place(night, 0.0, 0.0, mountain, SEA, CLOUD_FLOOR, false);
        assertNotNull(moved);
        assertEquals(64, moved.ground(), "over the low land beside the mountain");
        assertTrue(Math.hypot(moved.x() - ownX, moved.z() - ownZ) > 400.0);
        FireworksShow.Ground range = (x, z, radius) -> 175;
        assertNull(FireworksShow.place(night, 0.0, 0.0, range, SEA, CLOUD_FLOOR, false), "no room under the clouds anywhere near");
        assertNotNull(FireworksShow.place(night, 0.0, 0.0, range, SEA, Float.POSITIVE_INFINITY, false), "with no clouds there is room");
    }

    @Test
    void theLandIsGuessedFromTheSeaAndThePlayersGroundWithRoomKept() {
        assertEquals(SEA, FireworksShow.guessedGround(SEA, 40, CLOUD_FLOOR), "a player in a cave or a valley: the sea");
        assertEquals(72, FireworksShow.guessedGround(SEA, 72, CLOUD_FLOOR), "a player on low land: their ground");
        int high = FireworksShow.guessedGround(SEA, 150, CLOUD_FLOOR);
        assertTrue(high < 150 && CLOUD_FLOOR - FireworksShow.UNDER_CLOUDS - high >= FireworksShow.ROOM_WANTED - 1.0f,
            "a player on a mountain: low enough for the largest bursts: " + high);
        assertEquals(150, FireworksShow.guessedGround(SEA, 150, Float.POSITIVE_INFINITY), "with no clouds, their ground");
        assertEquals(SEA, FireworksShow.guessedGround(SEA, 150, 120.0f), "never under the sea");
    }

    @Test
    void theRainbowShowsOverFarThingsAndNotOverNearOnes() {
        assertEquals(0.0f, RainbowSchedule.shownOver(5.0));
        assertEquals(0.0f, RainbowSchedule.shownOver(RainbowSchedule.NEAR_NONE));
        assertEquals(1.0f, RainbowSchedule.shownOver(RainbowSchedule.NEAR_FULL));
        assertEquals(1.0f, RainbowSchedule.shownOver(Double.POSITIVE_INFINITY), "the sky");
        float previous = 0.0f;
        for (double distance = 300.0; distance <= 1_500.0; distance += 50.0) {
            float shown = RainbowSchedule.shownOver(distance);
            assertTrue(shown >= previous, "growing with distance");
            previous = shown;
        }
        assertEquals(0.5f, RainbowSchedule.shownOver(900.0), 1.0e-3);
    }
}
