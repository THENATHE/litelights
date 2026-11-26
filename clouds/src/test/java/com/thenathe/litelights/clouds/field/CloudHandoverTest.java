package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The input both sources fill: its names are a contract, and bad data is refused. */
class CloudHandoverTest {
    @Test
    void theTypeNamesAreTheAgreedList() {
        // These names travel between mods: renaming one breaks a weather mod's sky.
        // New names go at the end (altostratus, cirrocumulus and cirrostratus since 1.1.1).
        assertEquals(List.of("cumulus", "congestus", "cumulonimbus", "nimbostratus", "stratus", "stratocumulus", "altocumulus", "cirrus",
                "altostratus", "cirrocumulus", "cirrostratus"),
            Arrays.stream(CloudType.values()).map(CloudType::id).toList());
        for (CloudType type : CloudType.values()) {
            assertSame(type, CloudType.byId(type.id()));
        }
        assertNull(CloudType.byId("mammatus"));
    }

    @Test
    void everyTypeIsEitherHeapedOrALayer() {
        for (CloudType type : CloudType.values()) {
            assertTrue(type.puffs() != null ^ type.sheet() != null, type + " needs exactly one of puffs and sheet");
        }
    }

    @Test
    void aFormationMovesInAStraightLineFromItsBirth() {
        CloudFormation storm = storm(24_000.0, 1_000.0);
        assertEquals(100.0 + 0.05 * 3_000.0, storm.centreXAt(15_000.0), 1.0e-9);
        assertEquals(-50.0 - 0.02 * 3_000.0, storm.centreZAt(15_000.0), 1.0e-9);
        assertTrue(storm.aliveAt(12_000.0) && storm.aliveAt(36_000.0) && !storm.aliveAt(36_001.0));
    }

    @Test
    void aFormationEverywhereCoversEveryColumn() {
        CloudFormation deck = new CloudFormation(9L, CloudType.NIMBOSTRATUS, 0.0, 0.0, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY,
            0.0f, 0.0f, Double.POSITIVE_INFINITY, 1.0f, 0.0f, 1.0f, 130.0f, 500.0f, 1.0f, 0.4f, Precipitation.BY_BIOME, 0.6f, 1.0f, 1.0f);
        CloudField field = new CloudField(new CloudSky(1L, 192.0f, CloudWind.CALM, List.of(deck)));
        for (double x = -1.0e6; x <= 1.0e6; x += 1.0e5) {
            assertTrue(TestSkies.column(field, 1.0e7, x, -x * 0.5, 8.0).cover() > 0.0f, "no deck at " + x);
        }
    }

    @Test
    void badDataIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> storm(0.0, 1_000.0));
        assertThrows(IllegalArgumentException.class, () -> storm(24_000.0, -5.0));
        assertThrows(IllegalArgumentException.class, () -> new CloudWind(0.0, 0.0, 0.1, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new CloudOptions(8, 4, true));
    }

    @Test
    void theOptionsChooseTheSeed() {
        assertEquals(42L, CloudOptions.DEFAULT.skySeed(42L, 7L));
        assertEquals(7L, new CloudOptions(8, 8_192, false).skySeed(42L, 7L));
    }

    /** A storm as a weather mod might hand one over: born at tick 12,000 for {@code life} ticks. */
    private static CloudFormation storm(double life, double radius) {
        return new CloudFormation(77L, CloudType.CUMULONIMBUS, 100.0, -50.0, 0.05, -0.02, 12_000.0, life,
            0.15f, 0.15f, radius, 2.0f, 0.4f, 0.6f, 160.0f, 1_340.0f, 1.0f, 0.9f, Precipitation.RAIN, 0.9f, 0.8f, 1.0f);
    }
}
