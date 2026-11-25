package com.thenathe.litelights.render.feature.weather;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How snow gathers on leaves and under trees while it snows, holds and thaws (SnowCover). */
class SnowCoverTest {
    private static void run(SnowCover snow, float rain, float thunder, float seconds) {
        // In frames of a sixtieth of a second, as the game moves it on.
        for (int frame = 0; frame < Math.round(seconds * 60.0f); frame++) {
            snow.update(1.0f / 60.0f, rain, thunder);
        }
    }

    /** A world joined in clear weather, so the join's catch-up is out of the way. */
    private static SnowCover joinedClear() {
        SnowCover snow = new SnowCover();
        run(snow, 0.0f, 0.0f, SnowCover.JOIN_SECONDS + 1.0f);
        return snow;
    }

    @Test
    void snowfallShowsInAMinuteAndFillsTheCanopiesInTwoAndAHalf() {
        SnowCover snow = joinedClear();
        assertEquals(0.0f, snow.cover());
        run(snow, 1.0f, 0.0f, 60.0f);
        assertEquals(0.4f, snow.cover(), 0.01f, "two fifths after a minute");
        run(snow, 1.0f, 0.0f, 90.0f);
        assertEquals(1.0f, snow.cover(), 0.001f, "full after two and a half minutes");
        run(snow, 1.0f, 0.0f, 300.0f);
        assertEquals(1.0f, snow.cover(), "never more than full");
    }

    @Test
    void blizzardsBuildItFasterAndLightSnowSlower() {
        SnowCover blizzard = joinedClear();
        run(blizzard, 1.0f, 1.0f, 60.0f);
        assertEquals(0.6f, blizzard.cover(), 0.01f, "half again as fast");
        SnowCover light = joinedClear();
        run(light, 0.5f, 0.0f, 60.0f);
        assertEquals(0.2f, light.cover(), 0.01f, "half as fast");
    }

    @Test
    void itHoldsHalfADayThenThawsOverTenMinutes() {
        SnowCover snow = joinedClear();
        run(snow, 1.0f, 0.0f, 200.0f);
        run(snow, 0.0f, 0.0f, SnowCover.HOLD_SECONDS - 1.0f);
        assertEquals(1.0f, snow.cover(), "still all there just before the thaw starts");
        run(snow, 0.0f, 0.0f, 301.0f);
        assertEquals(0.5f, snow.cover(), 0.01f, "half gone five minutes into the thaw");
        run(snow, 0.0f, 0.0f, 300.0f);
        assertEquals(0.0f, snow.cover(), "gone after ten");
    }

    @Test
    void snowStartingAgainBuildsOnWhatIsLeft() {
        SnowCover snow = joinedClear();
        run(snow, 1.0f, 0.0f, 200.0f);
        run(snow, 0.0f, 0.0f, SnowCover.HOLD_SECONDS + 300.0f);
        float left = snow.cover();
        run(snow, 1.0f, 0.0f, 30.0f);
        assertEquals(left + 0.2f, snow.cover(), 0.01f);
        // The hold starts again from the new snow's end.
        run(snow, 0.0f, 0.0f, SnowCover.HOLD_SECONDS - 1.0f);
        assertEquals(left + 0.2f, snow.cover(), 0.01f);
    }

    @Test
    void joiningWhileItSnowsStartsWithTheCanopiesFull() {
        SnowCover snow = new SnowCover();
        run(snow, 0.0f, 0.0f, 1.0f);
        run(snow, 1.0f, 0.0f, 0.1f);
        assertEquals(1.0f, snow.cover(), 0.01f);
        SnowCover late = new SnowCover();
        run(late, 0.0f, 0.0f, SnowCover.JOIN_SECONDS + 1.0f);
        run(late, 1.0f, 0.0f, 0.1f);
        assertTrue(late.cover() < 0.01f, "snow arriving after the join builds up as usual: " + late.cover());
    }

    @Test
    void resetClearsItAndWaitsForTheNextJoin() {
        SnowCover snow = joinedClear();
        run(snow, 1.0f, 0.0f, 60.0f);
        snow.reset();
        assertEquals(0.0f, snow.cover());
        run(snow, 1.0f, 0.0f, 0.1f);
        assertEquals(1.0f, snow.cover(), 0.01f, "a new world joined in the snow");
    }

    @Test
    void pausedFramesChangeNothing() {
        SnowCover snow = joinedClear();
        run(snow, 1.0f, 0.0f, 60.0f);
        float before = snow.cover();
        snow.update(0.0f, 1.0f, 0.0f);
        snow.update(-1.0f, 0.0f, 0.0f);
        assertEquals(before, snow.cover());
    }
}
