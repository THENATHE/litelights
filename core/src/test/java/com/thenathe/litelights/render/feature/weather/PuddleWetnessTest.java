package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.render.feature.weather.PuddleWetness.Fall;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How puddles grow in the rain and dry after it (PuddleWetness). */
class PuddleWetnessTest {
    private static void run(PuddleWetness puddles, Fall fall, float rain, float thunder, float seconds) {
        // In frames of a sixtieth of a second, as the game moves it on.
        for (int frame = 0; frame < Math.round(seconds * 60.0f); frame++) {
            puddles.update(1.0f / 60.0f, fall, rain, thunder);
        }
    }

    /** A world joined in dry weather, so the join's catch-up is out of the way. */
    private static PuddleWetness joinedDry() {
        PuddleWetness puddles = new PuddleWetness();
        run(puddles, Fall.RAIN, 0.0f, 0.0f, PuddleWetness.JOIN_SECONDS + 1.0f);
        return puddles;
    }

    @Test
    void rainFillsThemNoticeablyInAMinuteAndFullyInTwoAndAHalf() {
        PuddleWetness puddles = joinedDry();
        assertEquals(0.0f, puddles.cover());
        run(puddles, Fall.RAIN, 1.0f, 0.0f, 30.0f);
        assertEquals(0.2f, puddles.cover(), 0.01f, "a fifth after thirty seconds");
        run(puddles, Fall.RAIN, 1.0f, 0.0f, 30.0f);
        assertEquals(0.4f, puddles.cover(), 0.01f, "two fifths after a minute");
        run(puddles, Fall.RAIN, 1.0f, 0.0f, 90.0f);
        assertEquals(1.0f, puddles.cover(), 0.001f, "full after two and a half minutes");
        run(puddles, Fall.RAIN, 1.0f, 0.0f, 300.0f);
        assertEquals(1.0f, puddles.cover(), "no further in plain rain");
    }

    @Test
    void thunderstormsFillThemFasterAndFurther() {
        PuddleWetness puddles = joinedDry();
        run(puddles, Fall.RAIN, 1.0f, 1.0f, 60.0f);
        assertEquals(0.6f, puddles.cover(), 0.01f, "half again as fast");
        run(puddles, Fall.RAIN, 1.0f, 1.0f, 300.0f);
        assertEquals(1.3f, puddles.cover(), 0.001f);
        // The thunder passes and the rain goes on: back to a rain's puddles, slowly.
        run(puddles, Fall.RAIN, 1.0f, 0.0f, 30.0f);
        assertTrue(puddles.cover() > 1.1f && puddles.cover() < 1.3f, "shrinking: " + puddles.cover());
        run(puddles, Fall.RAIN, 1.0f, 0.0f, 120.0f);
        assertEquals(1.0f, puddles.cover(), 0.001f);
    }

    @Test
    void lightRainFillsThemMoreSlowly() {
        PuddleWetness puddles = joinedDry();
        run(puddles, Fall.RAIN, 0.5f, 0.0f, 60.0f);
        assertEquals(0.2f, puddles.cover(), 0.01f);
    }

    @Test
    void theyLingerAMinuteThenDryOverFour() {
        PuddleWetness puddles = joinedDry();
        run(puddles, Fall.RAIN, 1.0f, 0.0f, 200.0f);
        run(puddles, Fall.RAIN, 0.0f, 0.0f, 59.0f);
        assertEquals(1.0f, puddles.cover(), "still full for the first minute");
        run(puddles, Fall.RAIN, 0.0f, 0.0f, 121.0f);
        assertEquals(0.5f, puddles.cover(), 0.01f, "half gone two minutes later");
        run(puddles, Fall.RAIN, 0.0f, 0.0f, 121.0f);
        assertEquals(0.0f, puddles.cover(), "dry four minutes after they began to shrink");
    }

    @Test
    void whereItSnowsOrNeverRainsTheyGoQuicklyAndNoneForm() {
        PuddleWetness snowy = joinedDry();
        run(snowy, Fall.SNOW, 1.0f, 0.0f, 120.0f);
        assertEquals(0.0f, snowy.cover(), "snowfall makes no puddles");
        PuddleWetness desert = joinedDry();
        run(desert, Fall.NONE, 1.0f, 0.0f, 120.0f);
        assertEquals(0.0f, desert.cover(), "a desert's rain makes none");

        PuddleWetness walkedIntoSnow = joinedDry();
        run(walkedIntoSnow, Fall.RAIN, 1.0f, 0.0f, 200.0f);
        run(walkedIntoSnow, Fall.SNOW, 1.0f, 0.0f, 30.0f);
        assertEquals(0.5f, walkedIntoSnow.cover(), 0.01f, "freezing over four times as fast, without lingering");
        PuddleWetness walkedIntoDesert = joinedDry();
        run(walkedIntoDesert, Fall.RAIN, 1.0f, 0.0f, 200.0f);
        run(walkedIntoDesert, Fall.NONE, 0.0f, 0.0f, 60.0f);
        assertEquals(0.5f, walkedIntoDesert.cover(), 0.01f, "soaking away twice as fast");
    }

    @Test
    void joiningAWorldInTheRainStartsWithTheRainsPuddles() {
        PuddleWetness puddles = new PuddleWetness();
        // The server's weather arrives a moment after the world.
        run(puddles, Fall.RAIN, 0.0f, 0.0f, 1.0f);
        assertEquals(0.0f, puddles.cover());
        run(puddles, Fall.RAIN, 1.0f, 1.0f, 1.0f / 60.0f);
        assertEquals(1.3f, puddles.cover(), 0.001f, "a thunderstorm's puddles at once");

        PuddleWetness late = new PuddleWetness();
        run(late, Fall.RAIN, 0.0f, 0.0f, PuddleWetness.JOIN_SECONDS + 1.0f);
        run(late, Fall.RAIN, 1.0f, 0.0f, 1.0f / 60.0f);
        assertTrue(late.cover() < 0.01f, "rain starting later fills them from dry: " + late.cover());

        late.reset();
        run(late, Fall.RAIN, 1.0f, 0.0f, 1.0f / 60.0f);
        assertEquals(1.0f, late.cover(), 0.001f, "and after a reset (another world) it catches up again");
    }
}
