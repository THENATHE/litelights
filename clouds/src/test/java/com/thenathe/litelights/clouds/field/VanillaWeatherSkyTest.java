package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** What vanilla's rain and thunder do to LiteLights' own sky, and nothing invented beyond them. */
class VanillaWeatherSkyTest {
    @Test
    void clearWeatherHasCloudsAndSkyButNoRain() {
        Survey clear = survey(0.0f, 0.0f, TestSkies.TICK);
        assertTrue(clear.cover > 0.1 && clear.cover < 0.6, "clear sky cover " + clear.cover);
        assertEquals(0.0, clear.raining, "no rain anywhere in clear weather");
        assertEquals(0, clear.stormSpans, "no storm types in clear weather");
    }

    @Test
    void rainClosesADeckOverTheWholeSky() {
        Survey clear = survey(0.0f, 0.0f, TestSkies.TICK);
        Survey rain = survey(1.0f, 0.0f, TestSkies.TICK);
        assertTrue(rain.cover >= 0.95, "rain should cover the sky, covered " + rain.cover);
        assertTrue(rain.raining >= 0.9, "rain should fall nearly everywhere, fell on " + rain.raining);
        // Since the floor (stage 2) both sit just above it; the deck's uneven base (stage 3) lifts it off the floor in waves.
        assertTrue(rain.meanBase < clear.meanBase - 10.0, "rain clouds hang lower: " + rain.meanBase + " against " + clear.meanBase);
        assertTrue(rain.meanStorm > clear.meanStorm, "rain clouds are darker");
    }

    @Test
    void thunderAddsTowers() {
        Survey rain = survey(1.0f, 0.0f, TestSkies.TICK);
        int seedsWithTowers = 0;
        for (int day = 0; day < 6; day++) {
            Survey thunder = survey(1.0f, 1.0f, TestSkies.TICK + day * 24_000.0);
            if (thunder.highestTop > TestSkies.BASE + 800.0f) {
                seedsWithTowers++;
            }
            assertTrue(thunder.meanStorm > rain.meanStorm, "thunder is darker than rain");
        }
        assertTrue(seedsWithTowers >= 5, "towers within reach on " + seedsWithTowers + " of 6 days");
        assertTrue(rain.highestTop < TestSkies.BASE + 800.0f, "no towers in rain without thunder, highest " + rain.highestTop);
    }

    @Test
    void rainFadesItsFormationsInRatherThanMakingNewOnes() {
        // The rain cores exist on the clock alone; the weather only sets how much of them shows.
        CloudSky halfRain = VanillaWeatherSky.sky(TestSkies.SEED, TestSkies.BASE, TestSkies.TICK, 0.5f, 0.0f, 0.0, 0.0, TestSkies.REACH);
        CloudSky fullRain = VanillaWeatherSky.sky(TestSkies.SEED, TestSkies.BASE, TestSkies.TICK, 1.0f, 0.0f, 0.0, 0.0, TestSkies.REACH);
        assertEquals(ids(halfRain, CloudType.NIMBOSTRATUS), ids(fullRain, CloudType.NIMBOSTRATUS));
        assertTrue(!ids(fullRain, CloudType.NIMBOSTRATUS).isEmpty());
        for (CloudFormation formation : halfRain.formations()) {
            if (formation.type() == CloudType.NIMBOSTRATUS) {
                assertEquals(0.5f, formation.presence());
            }
        }
    }

    @Test
    void theCloudsFollowVanillasWeatherSlowlyButStartWhereItIs() {
        CloudWeather weather = new CloudWeather();
        weather.tick(1.0f, 0.5f);
        assertEquals(1.0f, weather.rain(), "joining in the rain: under cloud at once");
        assertEquals(0.5f, weather.thunder());

        CloudWeather clearing = new CloudWeather();
        clearing.tick(0.0f, 0.0f);
        int ticksToCloudOver = 0;
        while (clearing.rain() < 1.0f) {
            clearing.tick(1.0f, 0.0f);
            ticksToCloudOver++;
        }
        int ticksToClear = 0;
        while (clearing.rain() > 0.0f) {
            clearing.tick(0.0f, 0.0f);
            ticksToClear++;
        }
        System.out.println("cloud weather: " + ticksToCloudOver + " ticks to cloud over, " + ticksToClear + " to clear");
        // The user's pace, 2026-11-25: faster than a minute, about 30 to 40 seconds.
        assertTrue(ticksToCloudOver >= 600 && ticksToCloudOver <= 800, "clouding over took " + ticksToCloudOver + " ticks");
        assertTrue(ticksToClear > ticksToCloudOver, "the sky clears more slowly than it clouds over");
    }

    private static String ids(CloudSky sky, CloudType type) {
        StringBuilder ids = new StringBuilder();
        for (CloudFormation formation : sky.formations()) {
            if (formation.type() == type) {
                ids.append(formation.id()).append(' ');
            }
        }
        return ids.toString();
    }

    private static final class Survey {
        double cover;
        double raining;
        int stormSpans;
        double meanBase;
        double meanStorm;
        float highestTop = Float.NEGATIVE_INFINITY;
    }

    /** The sky within the reach, a column every 128 blocks. */
    private static Survey survey(float rain, float thunder, double tick) {
        CloudField field = TestSkies.vanilla(tick, rain, thunder);
        Survey survey = new Survey();
        int columns = 0;
        int covered = 0;
        int raining = 0;
        double bases = 0.0;
        double storms = 0.0;
        int spans = 0;
        double reach = TestSkies.REACH - 200.0;
        CloudField.Area area = field.area(tick, -reach - field.driftX(tick), -reach - field.driftZ(tick),
            reach - field.driftX(tick), reach - field.driftZ(tick));
        CloudColumn column = new CloudColumn();
        for (double z = -reach; z <= reach; z += 128.0) {
            for (double x = -reach; x <= reach; x += 128.0) {
                if (x * x + z * z > reach * reach) {
                    continue;
                }
                area.column(x - field.driftX(tick), z - field.driftZ(tick), 64.0, column);
                columns++;
                covered += column.cover() >= 0.5f ? 1 : 0;
                raining += column.precipitationRate() > 0.0f ? 1 : 0;
                for (int span = 0; span < column.spanCount(); span++) {
                    CloudType type = column.type(span);
                    if (type == CloudType.NIMBOSTRATUS || type == CloudType.CUMULONIMBUS) {
                        survey.stormSpans++;
                    }
                    if (column.density(span) >= 0.5f && !type.skyLayer()) {
                        bases += column.bottom(span);
                        storms += column.storminess(span);
                        spans++;
                        survey.highestTop = Math.max(survey.highestTop, column.top(span));
                    }
                }
            }
        }
        survey.cover = covered / (double) columns;
        survey.raining = raining / (double) columns;
        survey.meanBase = bases / Math.max(1, spans);
        survey.meanStorm = storms / Math.max(1, spans);
        System.out.println(String.format(Locale.ROOT,
            "vanilla sky, rain %.1f thunder %.1f, tick %.0f: cover %.2f, raining on %.2f, mean base %.0f, mean storminess %.2f, highest top %.0f",
            rain, thunder, tick, survey.cover, survey.raining, survey.meanBase, survey.meanStorm, survey.highestTop));
        return survey;
    }
}
