package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The clouds' sizes and heights: a wide spread of sizes, and each type at its own height. */
class CloudShapeTest {
    @Test
    void clearSkyCloudsComeInAWideSpreadOfSizes() {
        // 10 km square around the camera, a column every 10 blocks.
        int side = 1_000;
        double step = 10.0;
        CloudField field = TestSkies.vanilla(TestSkies.TICK, 0.0f, 0.0f);
        boolean[] heaped = new boolean[side * side];
        CloudField.Area area = field.area(TestSkies.TICK, -5_000.0 - field.driftX(TestSkies.TICK), -5_000.0 - field.driftZ(TestSkies.TICK),
            5_000.0 - field.driftX(TestSkies.TICK), 5_000.0 - field.driftZ(TestSkies.TICK));
        CloudColumn column = new CloudColumn();
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                area.column(-5_000.0 + x * step - field.driftX(TestSkies.TICK), -5_000.0 + z * step - field.driftZ(TestSkies.TICK), step, column);
                heaped[x + z * side] = solidHeapedCloud(column);
            }
        }
        double[] diameters = cloudDiameters(heaped, side, step);
        Arrays.sort(diameters);
        double smallDecile = percentile(diameters, 0.1);
        double median = percentile(diameters, 0.5);
        double largeDecile = percentile(diameters, 0.9);
        double largest = diameters[diameters.length - 1];
        System.out.println(String.format(Locale.ROOT,
            "cloud sizes, clear sky, 10 km square: %d heaped clouds; diameters 10th percentile %.0f, median %.0f, 90th %.0f, 99th %.0f, largest %.0f blocks",
            diameters.length, smallDecile, median, largeDecile, percentile(diameters, 0.99), largest));
        // Fewer than 1.1.0's 246 (they are larger and taller now, and more of them join), but still many.
        assertTrue(diameters.length >= 50, "only " + diameters.length + " clouds");
        assertTrue(largeDecile >= 3.0 * smallDecile, "the 90th percentile should be three times the 10th");
        assertTrue(largest >= 10.0 * smallDecile, "the largest cloud should be ten times the 10th percentile");
    }

    @Test
    void eachTypeStaysWithinItsHeights() {
        for (CloudType type : CloudType.values()) {
            CloudField field = TestSkies.single(type, 1_500.0, 0.7f, 1.0f, 0.6f);
            CloudFormation formation = field.sky().formations().getFirst();
            float thickness = formation.top() - formation.base();
            CloudType.Puffs puffs = type.puffs();
            CloudType.Sheet sheet = type.sheet();
            float lowestAllowed = formation.base() - type.heights().raggedBottom()
                - (puffs != null ? puffs.bottomSag() * thickness + 3.0f
                    : 0.03f * thickness * (1.0f + sheet.thicknessVariation()) + sheet.baseWaves()) - 1.0f;
            float highestAllowed = puffs != null
                ? formation.base() + thickness * (1.0f + puffs.lumpiness() + 0.6f * puffs.billows()) + 1.0f
                : formation.base() + thickness * (1.0f + sheet.thicknessVariation()) * (1.0f + 0.6f * sheet.billows()) + 1.0f;
            Heights measured = measure(field, type);
            assertTrue(measured.count > 100, type + " showed no cloud");
            assertTrue(measured.lowest >= lowestAllowed, type + " reached down to " + measured.lowest + ", below " + lowestAllowed);
            assertTrue(measured.highest <= highestAllowed, type + " reached up to " + measured.highest + ", above " + highestAllowed);
        }
    }

    /**
     * The user's levels (2026-11-25), as how far up each type reaches: cumulonimbus through all four,
     * nimbostratus from the first through the third, cumulus and stratus low, stratocumulus in the
     * second, the alto clouds in the third and the cirro clouds in the fourth. A cloud's billows may
     * rise a little over its level's top, as real tops do; its body stays within its levels.
     */
    @Test
    void eachTypeReachesItsLevels() {
        for (CloudType type : CloudType.values()) {
            Heights measured = measure(TestSkies.single(type, 1_500.0, 0.7f, 1.0f, 0.6f), type);
            float lowestLevel = CloudType.levelBottom(type.lowestLevel());
            float highestLevel = CloudType.levelTop(type.highestLevel());
            float overshoot = 0.15f * (CloudType.levelTop(type.highestLevel()) - CloudType.levelBottom(type.highestLevel()));
            // The lowest base: a thunderstorm's solid spans are mostly its anvil's.
            float bottom = measured.lowest - TestSkies.BASE;
            float highest = measured.highest - TestSkies.BASE;
            assertTrue(bottom >= lowestLevel - overshoot && bottom < CloudType.levelTop(type.lowestLevel()),
                type + "'s base, " + bottom + " over the floor, is not in level " + type.lowestLevel());
            assertTrue(highest <= highestLevel + overshoot, type + " reaches up to " + highest + ", over level " + type.highestLevel());
            assertTrue(highest > CloudType.levelBottom(type.highestLevel()), type + " reaches only " + highest + ", short of level "
                + type.highestLevel());
        }
        assertEquals(4, CloudType.CUMULONIMBUS.highestLevel() - CloudType.CUMULONIMBUS.lowestLevel() + 1, "a thunderstorm spans every level");
        assertEquals(3, CloudType.NIMBOSTRATUS.highestLevel());
    }

    @Test
    void wispyHighFluffyLowAndStormsTallest() {
        Map<CloudType, Heights> heights = new EnumMap<>(CloudType.class);
        for (CloudType type : CloudType.values()) {
            heights.put(type, measure(TestSkies.single(type, 1_500.0, 0.7f, 1.0f, 0.6f), type));
        }
        for (Map.Entry<CloudType, Heights> entry : heights.entrySet()) {
            Heights h = entry.getValue();
            System.out.println(String.format(Locale.ROOT,
                "cloud heights, %s: bottoms %.0f to %.0f (median %.0f, spread %.1f), tops median %.0f, highest %.0f",
                entry.getKey().id(), h.lowest, h.highestBottom, h.medianBottom, h.bottomSpread, h.medianTop, h.highest));
        }
        Heights cumulus = heights.get(CloudType.CUMULUS);
        assertTrue(heights.get(CloudType.CIRRUS).medianBottom > heights.get(CloudType.ALTOCUMULUS).medianTop, "cirrus above altocumulus");
        assertTrue(heights.get(CloudType.ALTOCUMULUS).medianBottom > cumulus.medianTop, "altocumulus above cumulus");
        assertTrue(heights.get(CloudType.NIMBOSTRATUS).medianBottom < cumulus.medianBottom, "rain clouds hang lower than fair cumulus");
        assertTrue(heights.get(CloudType.STRATUS).medianBottom < cumulus.medianBottom, "stratus lower than cumulus");
        assertTrue(heights.get(CloudType.CONGESTUS).highest > cumulus.highest, "congestus taller than cumulus");
        assertTrue(heights.get(CloudType.CUMULONIMBUS).highest > TestSkies.BASE + 800.0f, "thunderstorms tower");
        assertTrue(heights.get(CloudType.CUMULONIMBUS).highest > heights.get(CloudType.CONGESTUS).highest, "thunderstorms tallest");
        // Not flat underneath.
        assertTrue(cumulus.bottomSpread >= 2.0, "cumulus bottoms should vary, spread " + cumulus.bottomSpread);
        assertTrue(heights.get(CloudType.NIMBOSTRATUS).bottomSpread >= 5.0, "a rain deck's base should be ragged");
    }

    private static boolean solidHeapedCloud(CloudColumn column) {
        for (int span = 0; span < column.spanCount(); span++) {
            if (column.type(span).puffs() != null && column.density(span) >= 0.5f) {
                return true;
            }
        }
        return false;
    }

    /** The diameter of each connected patch of cloud, as the circle of the same area. */
    private static double[] cloudDiameters(boolean[] cloud, int side, double step) {
        boolean[] seen = new boolean[cloud.length];
        List<Double> diameters = new ArrayList<>();
        int[] stack = new int[cloud.length];
        for (int start = 0; start < cloud.length; start++) {
            if (!cloud[start] || seen[start]) {
                continue;
            }
            int size = 0;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int index = stack[--top];
                size++;
                int x = index % side;
                int z = index / side;
                int[] neighbours = {x > 0 ? index - 1 : -1, x < side - 1 ? index + 1 : -1, z > 0 ? index - side : -1, z < side - 1 ? index + side : -1};
                for (int next : neighbours) {
                    if (next >= 0 && cloud[next] && !seen[next]) {
                        seen[next] = true;
                        stack[top++] = next;
                    }
                }
            }
            diameters.add(2.0 * Math.sqrt(size * step * step / Math.PI));
        }
        return diameters.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double percentile(double[] sorted, double share) {
        return sorted[Math.min(sorted.length - 1, (int) (share * sorted.length))];
    }

    private static final class Heights {
        int count;
        float lowest = Float.POSITIVE_INFINITY;
        float highest = Float.NEGATIVE_INFINITY;
        float highestBottom = Float.NEGATIVE_INFINITY;
        double medianBottom;
        double medianTop;
        double bottomSpread;
    }

    /**
     * Every span of a grid over the formation, 4 km square, a column every 20 blocks; the medians
     * and spread over its solid part (half the type's most density or more: cirrus is never solid).
     */
    private static Heights measure(CloudField field, CloudType type) {
        float solid = 0.5f * (type.sheet() != null ? type.sheet().opacity() : 1.0f);
        Heights heights = new Heights();
        List<Float> bottoms = new ArrayList<>();
        List<Float> tops = new ArrayList<>();
        CloudField.Area area = field.area(TestSkies.TICK, -2_000.0, -2_000.0, 2_000.0, 2_000.0);
        CloudColumn column = new CloudColumn();
        for (double z = -2_000.0; z <= 2_000.0; z += 20.0) {
            for (double x = -2_000.0; x <= 2_000.0; x += 20.0) {
                area.column(x, z, 8.0, column);
                for (int span = 0; span < column.spanCount(); span++) {
                    heights.count++;
                    heights.lowest = Math.min(heights.lowest, column.bottom(span));
                    heights.highest = Math.max(heights.highest, column.top(span));
                    if (column.density(span) >= solid) {
                        heights.highestBottom = Math.max(heights.highestBottom, column.bottom(span));
                        bottoms.add(column.bottom(span));
                        tops.add(column.top(span));
                    }
                }
            }
        }
        if (!bottoms.isEmpty()) {
            bottoms.sort(null);
            tops.sort(null);
            heights.medianBottom = bottoms.get(bottoms.size() / 2);
            heights.medianTop = tops.get(tops.size() / 2);
            double mean = bottoms.stream().mapToDouble(Float::doubleValue).average().orElse(0.0);
            heights.bottomSpread = Math.sqrt(bottoms.stream().mapToDouble(b -> (b - mean) * (b - mean)).average().orElse(0.0));
        }
        return heights;
    }
}
