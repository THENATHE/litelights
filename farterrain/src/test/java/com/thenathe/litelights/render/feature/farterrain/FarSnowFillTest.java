package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.client.multiplayer.ClientLevel;
import org.junit.jupiter.api.Test;

/**
 * Far snow's fills (FarSnow): the far snow lines and bare lines merged and encoded on the fill's
 * thread, only where they changed; dropped when the season changes while one runs; colours at most
 * every quarter second, into one buffer; nothing kept of a level left (the cover's rules are
 * FarSnowCoverTest's). Driven without a level or the GPU: the fills go to a record of uploads.
 */
class FarSnowFillTest {
    private static final int FLOOR = -64;
    private static final Executor AT_ONCE = Runnable::run;

    /** Almanac's far answers for a made-up world: lines known out to a radius, set by the season. */
    private static final class FakeAlmanac implements FarSnowSource {
        Changes listener;
        int season;
        int knownRadius = 1500;

        @Override
        public void listen(Changes changes) {
            listener = changes;
        }

        int bucket(int x, int z) {
            int hash = x * 73856093 ^ z * 19349663;
            hash = (hash ^ hash >>> 16) * 0x45D9F3B;
            return (hash ^ hash >>> 16) & 7;
        }

        boolean known(int x, int z) {
            return Math.abs(x) <= knownRadius && Math.abs(z) <= knownRadius;
        }

        int lineAt(int x, int z) {
            if (!known(x, z)) {
                return UNKNOWN;
            }
            int bucket = bucket(x, z);
            return season == 0 ? (bucket < 2 ? NOWHERE : bucket == 7 ? EVERYWHERE : 120 + bucket * 10)
                : (bucket < 1 ? NOWHERE : bucket >= 6 ? EVERYWHERE : 60 + bucket * 5);
        }

        /** Above the line, or unknown on a few columns (snow-block biomes while they melt). */
        int bareAt(int x, int z) {
            if (!known(x, z) || bucket(x, z) == 3) {
                return UNKNOWN;
            }
            int line = lineAt(x, z);
            return line == EVERYWHERE || line == NOWHERE ? line : line + 40;
        }

        @Override
        public void fillLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines) {
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    lines[z * width + x] = lineAt(originX + x * spacing, originZ + z * spacing);
                }
            }
        }

        @Override
        public void fillBareLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines) {
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    lines[z * width + x] = bareAt(originX + x * spacing, originZ + z * spacing);
                }
            }
        }

        @Override
        public int grassColour(ClientLevel level, int x, int z) {
            return Math.abs(x) > knownRadius ? -1 : 0x91BD59;
        }

        @Override
        public int foliageColour(ClientLevel level, int x, int z) {
            return 0x77AB2F;
        }

        @Override
        public boolean hot(ClientLevel level, int x, int z) {
            return false;
        }

        @Override
        public void snowDepths(ClientLevel level, double[] into) {
            Arrays.fill(into, 0.0);
        }

        /** The texel the far pass should get for a column: the bare line's code low, the line's high. */
        int texel(int x, int z) {
            int line = lineAt(x, z);
            int bare = bareAt(x, z);
            int shownBare = bare == UNKNOWN || line == UNKNOWN ? line : bare;
            return FarSnowGrid.code(shownBare, FLOOR) | FarSnowGrid.code(line, FLOOR) << 16;
        }
    }

    private record Upload(FarSnowGrid.Rect rect, int row, ByteBuffer texels) {
    }

    /** What reached the textures. */
    private static final class Recorded implements FarSnow.Uploads {
        final List<Upload> lines = new ArrayList<>();
        final List<ByteBuffer> colours = new ArrayList<>();

        @Override
        public void lines(ByteBuffer texels, FarSnowGrid.Rect rect, int row) {
            ByteBuffer copy = ByteBuffer.allocate(texels.limit()).order(ByteOrder.LITTLE_ENDIAN);
            copy.put(0, texels, 0, texels.limit());
            lines.add(new Upload(rect, row, copy));
        }

        @Override
        public void colours(ByteBuffer texels) {
            colours.add(texels);
        }

        @Override
        public void places(ByteBuffer texels) {
        }
    }

    /** Every texel of an upload against what the fake Almanac says for its column. */
    private static void assertTexels(FakeAlmanac almanac, Upload upload, int originX, int originZ, int spacing) {
        FarSnowGrid.Rect rect = upload.rect();
        int index = 0;
        for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
            for (int x = rect.minX(); x <= rect.maxX(); x++) {
                int columnX = originX + spacing / 2 + x * spacing;
                int columnZ = originZ + spacing / 2 + z * spacing;
                int expected = almanac.texel(columnX, columnZ);
                int got = upload.texels().getInt(index);
                if (expected != got) {
                    throw new AssertionError("texel (" + x + ", " + z + ") of " + rect + ": expected " + Integer.toHexString(expected)
                        + ", got " + Integer.toHexString(got));
                }
                index += 4;
            }
        }
        assertEquals(index, upload.texels().limit());
    }

    private static final int INNER_ORIGIN = FarSnowGrid.originFor(0, 1024, FarSnowGrid.SIZE * FarSnow.INNER_SPACING);
    private static final int OUTER_ORIGIN = FarSnowGrid.originFor(0, 4096, FarSnowGrid.SIZE * FarSnow.OUTER_SPACING);

    @Test
    void theFillEncodesTheLinesAndTheBareLinesOnlyWhereTheyChanged() {
        FakeAlmanac almanac = new FakeAlmanac();
        Recorded recorded = new Recorded();
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> 0L);
        snow.update(null, FLOOR, 0, 0, 0, 0.0f);
        snow.update(null, FLOOR, 0, 0, 1, 0.0f);
        assertEquals(2, recorded.lines.size(), "both rings placed");
        Upload inner = recorded.lines.get(0);
        Upload outer = recorded.lines.get(1);
        assertEquals(FarSnowGrid.Rect.ALL, inner.rect());
        assertEquals(0, inner.row());
        assertEquals(FarSnowGrid.SIZE, outer.row(), "the outer ring's rows follow the inner's");
        assertTexels(almanac, inner, INNER_ORIGIN, INNER_ORIGIN, FarSnow.INNER_SPACING);
        assertTexels(almanac, outer, OUTER_ORIGIN, OUTER_ORIGIN, FarSnow.OUTER_SPACING);

        // A map tile arrives: only its texels are filled and uploaded again.
        almanac.knownRadius = 3000;
        almanac.listener.changed(null, new FarSnowSource.Area(1536, 0, 2047, 511), true, false, FarSnowSource.Kind.MAP);
        snow.update(null, FLOOR, 0, 0, 2, 0.0f);
        snow.update(null, FLOOR, 0, 0, 3, 0.0f);
        assertEquals(4, recorded.lines.size());
        Upload tile = recorded.lines.get(2);
        assertTrue(tile.rect().width() < 64 && tile.rect().height() < 64, "only the tile's texels: " + tile.rect());
        assertTexels(almanac, tile, INNER_ORIGIN, INNER_ORIGIN, FarSnow.INNER_SPACING);

        // A change of season fills every line again, at once: nothing is animated.
        almanac.season = 1;
        almanac.listener.changed(null, FarSnowSource.Area.EVERYWHERE, true, false, FarSnowSource.Kind.SEASON);
        snow.update(null, FLOOR, 0, 0, 4, 0.0f);
        snow.update(null, FLOOR, 0, 0, 5, 0.0f);
        assertEquals(6, recorded.lines.size());
        assertTexels(almanac, recorded.lines.get(4), INNER_ORIGIN, INNER_ORIGIN, FarSnow.INNER_SPACING);
        assertTexels(almanac, recorded.lines.get(5), OUTER_ORIGIN, OUTER_ORIGIN, FarSnow.OUTER_SPACING);

        // The inner ring moves a step east: filled whole at its new place.
        snow.update(null, FLOOR, 1100, 0, 6, 0.0f);
        snow.update(null, FLOOR, 1100, 0, 7, 0.0f);
        Upload moved = recorded.lines.getLast();
        assertEquals(FarSnowGrid.Rect.ALL, moved.rect());
        assertTexels(almanac, moved, INNER_ORIGIN + 1024, INNER_ORIGIN, FarSnow.INNER_SPACING);
        assertTrue(snow.describe().contains("staleFills=0"), snow.describe());
    }

    @Test
    void aSeasonChangeWhileAFillRunsDropsTheFill() {
        FakeAlmanac almanac = new FakeAlmanac();
        Recorded recorded = new Recorded();
        List<Runnable> queued = new ArrayList<>();
        FarSnow snow = new FarSnow(almanac, queued::add, recorded, () -> 0L);
        snow.update(null, FLOOR, 0, 0, 0, 0.0f);
        runAll(queued);
        snow.update(null, FLOOR, 0, 0, 1, 0.0f);
        assertEquals(2, recorded.lines.size(), "both rings placed");

        // A tile arrives and its fill starts; Almanac's lines turn to the next season's before the
        // fill reads them, and the season's event comes only after it has.
        almanac.listener.changed(null, new FarSnowSource.Area(0, 0, 511, 511), true, false, FarSnowSource.Kind.MAP);
        snow.update(null, FLOOR, 0, 0, 2, 0.0f);
        almanac.season = 1;
        runAll(queued);
        almanac.listener.changed(null, FarSnowSource.Area.EVERYWHERE, true, false, FarSnowSource.Kind.SEASON);
        snow.update(null, FLOOR, 0, 0, 3, 0.0f);
        assertEquals(2, recorded.lines.size(), "the tile's fill, out of date, is dropped");
        assertTrue(snow.describe().contains("staleFills=1"), snow.describe());

        runAll(queued);
        snow.update(null, FLOOR, 0, 0, 4, 0.0f);
        assertEquals(4, recorded.lines.size(), "then every line is filled again");
        assertTexels(almanac, recorded.lines.get(2), INNER_ORIGIN, INNER_ORIGIN, FarSnow.INNER_SPACING);
    }

    @Test
    void coloursAreFilledAtMostEveryQuarterSecondIntoOneBuffer() {
        FakeAlmanac almanac = new FakeAlmanac();
        Recorded recorded = new Recorded();
        long[] now = {0L};
        FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> now[0]);
        // Joining: three seconds at 60 frames a second, a map tile with new colours every frame.
        for (int frame = 0; frame < 180; frame++) {
            almanac.listener.changed(null, new FarSnowSource.Area(frame * 16, 0, frame * 16 + 15, 15), true, true, FarSnowSource.Kind.MAP);
            snow.update(null, FLOOR, 0, 0, frame, 0.0f);
            now[0] += 16_666_667L;
        }
        int fills = recorded.colours.size();
        assertTrue(fills >= 10 && fills <= 13, fills + " colour fills in three seconds");
        for (ByteBuffer texels : recorded.colours) {
            assertSame(recorded.colours.getFirst(), texels, "one staging buffer, reused");
            assertEquals(FarSnow.COLOUR_SIZE * FarSnow.COLOUR_SIZE * 2 * 4, texels.limit());
        }
        // The middle cell, at the camera, is known; the first, 4,096 blocks out, isn't.
        ByteBuffer texels = recorded.colours.getFirst();
        int middle = (FarSnow.COLOUR_SIZE / 2 * FarSnow.COLOUR_SIZE + FarSnow.COLOUR_SIZE / 2) * 4;
        assertEquals(FarSnow.texel(0x91BD59), texels.getInt(middle), "a grass texel, in byte order");
        assertEquals(FarSnow.texel(0x77AB2F), texels.getInt(FarSnow.COLOUR_SIZE * FarSnow.COLOUR_SIZE * 4 + middle), "a foliage texel inside one biome");
        assertEquals(0, texels.getInt(0), "unknown");
    }

    @Test
    void aFillStillRunningWhenTheLevelIsLeftIsNeverTakenIn() {
        FakeAlmanac almanac = new FakeAlmanac();
        Recorded recorded = new Recorded();
        List<Runnable> queued = new ArrayList<>();
        FarSnow snow = new FarSnow(almanac, queued::add, recorded, () -> 0L);
        snow.update(null, FLOOR, 0, 0, 0, 0.0f);
        snow.leaveLevel();
        runAll(queued);
        snow.update(null, FLOOR, 0, 0, 1, 0.0f);
        assertEquals(0, recorded.lines.size(), "the fill from before leaving isn't uploaded");
        assertEquals(0, recorded.colours.size());
        runAll(queued);
        snow.update(null, FLOOR, 0, 0, 2, 0.0f);
        assertEquals(2, recorded.lines.size(), "coming back fills everything again");
    }

    private static void runAll(List<Runnable> queued) {
        List<Runnable> running = List.copyOf(queued);
        queued.clear();
        running.forEach(Runnable::run);
    }
}
