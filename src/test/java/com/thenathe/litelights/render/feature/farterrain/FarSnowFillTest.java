package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.client.multiplayer.ClientLevel;
import org.junit.jupiter.api.Test;

/**
 * Far snow's fills (FarSnow, 1.0): merged and encoded on the fill's thread, byte for byte as
 * 0.9.10's render thread did it ({@link OldFarSnowLines}); dropped when the season changes while
 * one runs; colours at most every quarter second, into one buffer; nothing kept of a level left.
 * Driven without a level or the GPU: the fills go to a record of uploads.
 */
class FarSnowFillTest {
    private static final int FLOOR = -64;
    private static final Executor AT_ONCE = Runnable::run;

    /** Almanac's far answers for a made-up world: lines known out to a radius, set by the season. */
    private static final class FakeAlmanac implements FarSnowSource {
        Changes listener;
        int season;
        int knownRadius = 1500;
        long changedTick = -1;

        @Override
        public void listen(Changes changes) {
            listener = changes;
        }

        int lineAt(int x, int z) {
            if (Math.abs(x) > knownRadius || Math.abs(z) > knownRadius) {
                return UNKNOWN;
            }
            int hash = x * 73856093 ^ z * 19349663;
            hash = (hash ^ hash >>> 16) * 0x45D9F3B;
            int bucket = (hash ^ hash >>> 16) & 7;
            return switch (season) {
                case 0 -> bucket < 2 ? NOWHERE : bucket == 7 ? EVERYWHERE : 120 + bucket * 10;
                case 1 -> bucket < 1 ? NOWHERE : bucket >= 6 ? EVERYWHERE : 60 + bucket * 5;
                case 2 -> bucket < 3 ? NOWHERE : bucket == 7 ? EVERYWHERE : 100 + bucket * 7;
                default -> bucket < 2 ? NOWHERE : bucket >= 5 ? EVERYWHERE : 70 + bucket * 3;
            };
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
        public long thawTicks(ClientLevel level) {
            return 6_000L;
        }

        @Override
        public long changedTick(ClientLevel level) {
            return changedTick;
        }

        @Override
        public void snowDepths(ClientLevel level, double[] depths) {
            Arrays.fill(depths, 0.0);
        }

        @Override
        public int subSeason(ClientLevel level) {
            return season;
        }
    }

    /** What reached the textures. */
    private static final class Recorded implements FarSnow.Uploads {
        final List<OldFarSnowLines.Upload> lines = new ArrayList<>();
        final List<ByteBuffer> colours = new ArrayList<>();

        @Override
        public void lines(ByteBuffer texels, FarSnowGrid.Rect rect, int row) {
            byte[] bytes = new byte[texels.limit()];
            texels.get(0, bytes);
            lines.add(new OldFarSnowLines.Upload(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ(), row, bytes));
        }

        @Override
        public void colours(ByteBuffer texels) {
            colours.add(texels);
        }
    }

    /** The new far snow and 0.9.10's lines, told the same things and shown the same frames. */
    private static final class Both {
        final FakeAlmanac almanac = new FakeAlmanac();
        final Recorded recorded = new Recorded();
        final FarSnow snow = new FarSnow(almanac, AT_ONCE, recorded, () -> 0L);
        final OldFarSnowLines old = new OldFarSnowLines(almanac, FLOOR);

        void frame(double cameraX, double cameraZ, long gameTime) {
            snow.update(null, FLOOR, cameraX, cameraZ, gameTime, 0.0f);
            old.frame(cameraX, cameraZ, gameTime);
            assertEquals(old.uploads.size(), recorded.lines.size(), "uploads by tick " + gameTime);
            for (int i = 0; i < old.uploads.size(); i++) {
                OldFarSnowLines.Upload before = old.uploads.get(i);
                OldFarSnowLines.Upload after = recorded.lines.get(i);
                assertEquals(List.of(before.minX(), before.minZ(), before.maxX(), before.maxZ(), before.row()),
                    List.of(after.minX(), after.minZ(), after.maxX(), after.maxZ(), after.row()), "upload " + i + "'s rectangle");
                assertArrayEquals(before.texels(), after.texels(), "upload " + i + "'s texels");
            }
            assertEquals(old.progress, snow.progress(), "progress at tick " + gameTime);
            assertEquals(old.changes, snow.changes(), "changes by tick " + gameTime);
        }

        /** A fill starts in the first frame and is taken in in the second. */
        void twoFrames(double cameraX, double cameraZ, long gameTime) {
            frame(cameraX, cameraZ, gameTime);
            frame(cameraX, cameraZ, gameTime + 1);
        }

        void tilesOutTo(int radius) {
            almanac.knownRadius = radius;
            FarSnowSource.Area area = new FarSnowSource.Area(-radius, -radius, radius, radius);
            almanac.listener.changed(null, area, true, true, FarSnowSource.Kind.MAP);
            old.changed(area, FarSnowSource.Kind.MAP);
        }

        void season(int season, long tick) {
            almanac.season = season;
            almanac.changedTick = tick;
            almanac.listener.changed(null, FarSnowSource.Area.EVERYWHERE, true, false, FarSnowSource.Kind.SEASON);
            old.changed(FarSnowSource.Area.EVERYWHERE, FarSnowSource.Kind.SEASON);
        }
    }

    @Test
    void theFillMergesAndEncodesExactlyAsTheRenderThreadDid() {
        Both both = new Both();
        both.twoFrames(0, 0, 0);
        both.tilesOutTo(3000);
        both.twoFrames(0, 0, 2);
        // A change of season, shown in patches; a tile arriving while it runs.
        both.season(1, 10);
        both.twoFrames(0, 0, 10);
        both.frame(0, 0, 3000);
        both.tilesOutTo(4000);
        both.twoFrames(0, 0, 3001);
        // The inner ring moves while the change is shown, keeping its "before".
        both.twoFrames(1100, 0, 3003);
        // The change ends; the next fill (a tile) settles it first.
        both.twoFrames(1100, 0, 6100);
        both.tilesOutTo(4500);
        both.twoFrames(1100, 0, 6102);
        // Another change, cut short by a third, and both rings moving during it.
        both.season(2, 6200);
        both.twoFrames(1100, 0, 6200);
        both.frame(1100, 0, 7000);
        both.season(3, 7100);
        both.twoFrames(1100, 0, 7100);
        both.twoFrames(4300, 0, 8000);
        both.twoFrames(4300, 0, 14000);
        // One more change uploads both whole rings: the merged state matches everywhere.
        both.season(0, 14100);
        both.twoFrames(4300, 0, 14100);
        assertEquals(4, both.snow.changes());
        assertTrue(both.recorded.lines.size() > 12);
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

        // A tile arrives and its fill starts; Almanac's lines turn to winter's before the fill reads
        // them, and the season's event comes only after it has.
        almanac.listener.changed(null, new FarSnowSource.Area(0, 0, 511, 511), true, false, FarSnowSource.Kind.MAP);
        snow.update(null, FLOOR, 0, 0, 2, 0.0f);
        almanac.season = 1;
        almanac.changedTick = 3;
        runAll(queued);
        almanac.listener.changed(null, FarSnowSource.Area.EVERYWHERE, true, false, FarSnowSource.Kind.SEASON);
        snow.update(null, FLOOR, 0, 0, 3, 0.0f);
        assertEquals(2, recorded.lines.size(), "the tile's fill, out of date, is dropped");
        assertTrue(snow.describe().contains("staleFills=1"), snow.describe());

        runAll(queued);
        snow.update(null, FLOOR, 0, 0, 4, 0.0f);
        assertEquals(1, snow.changes(), "the change animates rather than showing at once");
        assertTrue(snow.progress() < 0.01f, "progress " + snow.progress());
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
