package com.thenathe.litelights.render.feature.weather.snow;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Which chunk sections hold blocks that can take fake snow, and which point buckets their columns
 * fall in, per area: noted by the mesh threads as they build (FakeSnow), so when a share passes a
 * step only the sections with a column in the changed buckets are built again. A section's buckets
 * only ever grow while it is known, which at worst builds it once more than needed. Sections far
 * from the camera are forgotten.
 */
final class SnowSections {
    private static final Map<Long, AtomicIntegerArray> BUCKETS = new ConcurrentHashMap<>();
    /** An area the climate doesn't name (no seasonal snow): its sections are built again with any change of shares. */
    private static final int ANY_AREA = SnowShares.AREAS;

    private SnowSections() {
    }

    /** Notes a host's section; {@code counts} adds its column's bucket (the season's snow can reach it now). */
    static void note(BlockPos pos, int area, int bucket, boolean counts) {
        long key = SectionPos.asLong(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
            SectionPos.blockToSectionCoord(pos.getZ()));
        AtomicIntegerArray buckets = BUCKETS.computeIfAbsent(key, unused -> new AtomicIntegerArray(SnowShares.AREAS + 1));
        if (!counts) {
            return;
        }
        int slot = area >= 0 && area < SnowShares.AREAS ? area : ANY_AREA;
        int bit = 1 << bucket;
        int current = buckets.get(slot);
        while ((current & bit) == 0 && !buckets.compareAndSet(slot, current, current | bit)) {
            current = buckets.get(slot);
        }
    }

    /** Builds again every known section with a column in a changed bucket of its area; returns how many. */
    static int rebuild(ClientLevel level, int[] changed) {
        boolean anyChange = false;
        for (int mask : changed) {
            anyChange |= mask != 0;
        }
        if (!anyChange) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<Long, AtomicIntegerArray> entry : BUCKETS.entrySet()) {
            AtomicIntegerArray buckets = entry.getValue();
            boolean hit = buckets.get(ANY_AREA) != 0;
            for (int area = 0; area < SnowShares.AREAS && !hit; area++) {
                hit = (buckets.get(area) & changed[area]) != 0;
            }
            if (hit) {
                dirty(level, entry.getKey());
                count++;
            }
        }
        return count;
    }

    /** Builds every known section again (the season or the climate changed). */
    static int rebuildAll(ClientLevel level) {
        for (long key : BUCKETS.keySet()) {
            dirty(level, key);
        }
        return BUCKETS.size();
    }

    /** Forgets sections further than {@code reach} sections from the camera's, sideways. */
    static void forgetFar(SectionPos camera, int reach) {
        Iterator<Long> keys = BUCKETS.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.next();
            if (Math.abs(SectionPos.x(key) - camera.x()) > reach || Math.abs(SectionPos.z(key) - camera.z()) > reach) {
                keys.remove();
            }
        }
    }

    static void clear() {
        BUCKETS.clear();
    }

    static int known() {
        return BUCKETS.size();
    }

    private static void dirty(ClientLevel level, long key) {
        int x = SectionPos.x(key);
        int y = SectionPos.y(key);
        int z = SectionPos.z(key);
        level.setSectionRangeDirty(x, y, z, x, y, z);
    }
}
