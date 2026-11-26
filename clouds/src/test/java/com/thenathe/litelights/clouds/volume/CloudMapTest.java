package com.thenathe.litelights.clouds.volume;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.clouds.field.CloudColumn;
import com.thenathe.litelights.clouds.field.CloudField;
import com.thenathe.litelights.clouds.field.CloudFormation;
import com.thenathe.litelights.clouds.field.CloudSky;
import com.thenathe.litelights.clouds.field.CloudType;
import com.thenathe.litelights.clouds.field.CloudWind;
import com.thenathe.litelights.clouds.field.Precipitation;
import com.thenathe.litelights.clouds.field.VanillaWeatherSky;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The column maps the volumetric clouds march through: which band each cloud goes in, and that a map says what the field says. */
class CloudMapTest {
    private static final long SEED = 0x51CE5EEDL;
    private static final float FLOOR = 192.0f;
    private static final double TICK = 30_000.0;

    @Test
    void eachCloudHasItsBand() {
        assertEquals(CloudMap.Band.LOW, CloudMap.Band.of(CloudType.CUMULUS, false));
        assertEquals(CloudMap.Band.LOW, CloudMap.Band.of(CloudType.CONGESTUS, false));
        assertEquals(CloudMap.Band.LOW, CloudMap.Band.of(CloudType.NIMBOSTRATUS, false));
        assertEquals(CloudMap.Band.LOW, CloudMap.Band.of(CloudType.CUMULONIMBUS, false), "a storm's tower is one mass with its deck");
        assertEquals(CloudMap.Band.HIGH, CloudMap.Band.of(CloudType.CUMULONIMBUS, true), "its anvil spreads over the top of the sky");
        assertEquals(CloudMap.Band.SECOND, CloudMap.Band.of(CloudType.STRATOCUMULUS, false));
        assertEquals(CloudMap.Band.MIDDLE, CloudMap.Band.of(CloudType.ALTOCUMULUS, false));
        assertEquals(CloudMap.Band.MIDDLE, CloudMap.Band.of(CloudType.ALTOSTRATUS, false));
        assertEquals(CloudMap.Band.HIGH, CloudMap.Band.of(CloudType.CIRRUS, false));
        assertEquals(CloudMap.Band.HIGH, CloudMap.Band.of(CloudType.CIRROCUMULUS, false));
        assertEquals(CloudMap.Band.HIGH, CloudMap.Band.of(CloudType.CIRROSTRATUS, false));
    }

    @Test
    void anEmptyMapHoldsNoCloudAndKeepsEachBandsBase() {
        CloudMap map = CloudMap.empty(-256, -256, 8);
        for (CloudMap.Band band : CloudMap.Band.values()) {
            assertEquals(0.0f, map.density(band, 100, 200));
            assertEquals(band.usualBase(), map.bottom(band, 100, 200), CloudMap.HEIGHT_RANGE / 255.0f);
            assertEquals(map.bottom(band, 100, 200), map.top(band, 100, 200));
        }
        assertEquals(0.0f, map.envelopeAt(0.0, 100.0, 0.0));
        assertEquals(0.0f, map.coverAround(0.0, 0.0, 32.0, 0.3f));
        assertEquals(-1.0f, map.rainCloudTopAround(0.0, 0.0, 48.0, 0.3f));
    }

    @Test
    void theMapSaysWhatTheFieldSays() {
        CloudField field = new CloudField(VanillaWeatherSky.sky(SEED, FLOOR, TICK, 0.0f, 0.0f, 0.0, 0.0, 4_096.0));
        CloudMap map = build(field, 0.0, 0.0, 8);
        CloudColumn column = new CloudColumn();
        int compared = 0;
        float step = CloudMap.HEIGHT_RANGE / 255.0f;
        for (int z = 0; z < CloudMap.SIZE; z += 7) {
            for (int x = 0; x < CloudMap.SIZE; x += 7) {
                double cloudX = (map.originX() + x + 0.5) * 8.0;
                double cloudZ = (map.originZ() + z + 0.5) * 8.0;
                field.area(TICK, cloudX, cloudZ, cloudX, cloudZ).column(cloudX, cloudZ, 8.0, column);
                for (int span = 0; span < column.spanCount(); span++) {
                    if (column.density(span) < CloudMap.FADING_SPAN) {
                        // A fading span is drawn shrunk toward its middle.
                        continue;
                    }
                    CloudMap.Band band = CloudMap.Band.of(column.type(span), column.anvil(span));
                    // A band joins its spans: its bottom is no higher and its top no lower than any of them.
                    assertTrue(map.bottom(band, x, z) <= column.bottom(span) - FLOOR + step, "bottom at " + x + ", " + z);
                    assertTrue(map.top(band, x, z) >= column.top(span) - FLOOR - step, "top at " + x + ", " + z);
                    assertTrue(map.density(band, x, z) >= column.density(span) - 1.0f / 255.0f);
                    compared++;
                }
            }
        }
        assertTrue(compared > 500, "only " + compared + " spans compared");
    }

    @Test
    void theLowBandSaysWhichCloudsAreHeapedAndHowStormy() {
        CloudField field = new CloudField(VanillaWeatherSky.sky(SEED, FLOOR, TICK, 1.0f, 1.0f, 0.0, 0.0, 8_192.0));
        CloudMap map = build(field, 0.0, 0.0, 16);
        CloudColumn column = new CloudColumn();
        int heaped = 0;
        int stratus = 0;
        int stormy = 0;
        for (int z = 0; z < CloudMap.SIZE; z += 5) {
            for (int x = 0; x < CloudMap.SIZE; x += 5) {
                double cloudX = (map.originX() + x + 0.5) * 16.0;
                double cloudZ = (map.originZ() + z + 0.5) * 16.0;
                field.area(TICK, cloudX, cloudZ, cloudX, cloudZ).column(cloudX, cloudZ, 16.0, column);
                boolean anyHeaped = false;
                boolean anyStratus = false;
                float storm = 0.0f;
                for (int span = 0; span < column.spanCount(); span++) {
                    if (CloudMap.Band.of(column.type(span), column.anvil(span)) != CloudMap.Band.LOW) {
                        continue;
                    }
                    anyHeaped |= column.type(span) != CloudType.STRATUS;
                    anyStratus |= column.type(span) == CloudType.STRATUS;
                    storm = Math.max(storm, column.storminess(span));
                }
                if (anyHeaped) {
                    assertTrue(map.heaped(CloudMap.Band.LOW, x, z), "heaped at " + x + ", " + z);
                    assertEquals(storm, map.storminess(CloudMap.Band.LOW, x, z), 2.5f / 255.0f);
                    heaped++;
                    stormy += storm > 0.1f ? 1 : 0;
                } else if (anyStratus) {
                    assertTrue(!map.heaped(CloudMap.Band.LOW, x, z), "stratus at " + x + ", " + z);
                    assertEquals(0.0f, map.storminess(CloudMap.Band.LOW, x, z));
                    stratus++;
                }
                assertTrue(!map.heaped(CloudMap.Band.SECOND, x, z));
            }
        }
        assertTrue(heaped > 50 && stormy > 10 && stratus >= 0, heaped + " heaped columns, " + stormy + " stormy");
        // An empty low column reads as heaped and calm, so filtering keeps a heaped cloud heaped out to its rim.
        CloudMap empty = CloudMap.empty(0, 0, 8);
        assertTrue(empty.heaped(CloudMap.Band.LOW, 3, 3));
        assertEquals(0.0f, empty.storminess(CloudMap.Band.LOW, 3, 3));
    }

    @Test
    void stripsBuiltApartMakeTheSameMap() {
        CloudField field = new CloudField(VanillaWeatherSky.sky(SEED, FLOOR, TICK, 1.0f, 1.0f, 0.0, 0.0, 8_192.0));
        CloudMap whole = build(field, 1_000.0, -2_000.0, 32);
        CloudMap.Builder strips = CloudMap.around(1_000.0, -2_000.0, 32);
        // Out of order, as worker threads finish them.
        for (int strip = 15; strip >= 0; strip--) {
            strips.sampleRows(field, TICK, strip * 32, strip * 32 + 32);
        }
        assertArrayEquals(whole.texels(), strips.build().texels());
        assertTrue(whole.sameAs(build(field, 1_000.0, -2_000.0, 32)));
    }

    @Test
    void insideACumulusTheEnvelopeIsSolidAndAboveItClear() {
        CloudFormation cumulus = new CloudFormation(0x1234L, CloudType.CUMULUS, 0.0, 0.0, 0.0, 0.0, TICK - 20_000.0, 40_000.0,
            0.2f, 0.25f, 1_500.0, 1.0f, 0.3f, 0.9f, FLOOR + 24.0f, FLOOR + 345.0f, 1.5f, 0.0f, Precipitation.NONE, 0.0f, 0.0f, 1.0f);
        CloudField field = new CloudField(new CloudSky(SEED, FLOOR, CloudWind.CALM, List.of(cumulus)));
        CloudMap map = build(field, 0.0, 0.0, 8);
        // The column in the map holding the tallest cloud.
        int bestX = 0;
        int bestZ = 0;
        for (int z = 0; z < CloudMap.SIZE; z++) {
            for (int x = 0; x < CloudMap.SIZE; x++) {
                if (map.top(CloudMap.Band.LOW, x, z) > map.top(CloudMap.Band.LOW, bestX, bestZ)) {
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        double cloudX = (map.originX() + bestX + 0.5) * 8.0;
        double cloudZ = (map.originZ() + bestZ + 0.5) * 8.0;
        float bottom = map.bottom(CloudMap.Band.LOW, bestX, bestZ);
        float top = map.top(CloudMap.Band.LOW, bestX, bestZ);
        assertTrue(top - bottom > 100.0f, "the tallest cumulus is only " + (top - bottom) + " blocks tall");
        assertTrue(map.envelopeAt(cloudX, (bottom + top) * 0.5, cloudZ) > 0.5f, "inside the cloud");
        assertEquals(0.0f, map.envelopeAt(cloudX, top + 50.0, cloudZ), "over its top");
        assertTrue(map.coverAround(cloudX, cloudZ, 16.0, 0.3f) > 0.5f);
        assertEquals(top, map.rainCloudTopAround(cloudX, cloudZ, 0.0, 0.3f), 0.01f);
    }

    private static CloudMap build(CloudField field, double centreX, double centreZ, int texelBlocks) {
        CloudMap.Builder builder = CloudMap.around(centreX, centreZ, texelBlocks);
        builder.sampleRows(field, TICK, 0, CloudMap.SIZE);
        return builder.build();
    }
}
