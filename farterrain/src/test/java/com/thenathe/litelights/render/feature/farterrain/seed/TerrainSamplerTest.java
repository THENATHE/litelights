package com.thenathe.litelights.render.feature.farterrain.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.render.feature.farterrain.land.BlockKinds;
import com.thenathe.litelights.render.feature.farterrain.land.TileKey;
import com.thenathe.litelights.render.feature.farterrain.land.TileVoxels;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Predicted terrain against the game's own generation for the same seed, in vanilla's overworld
 * loaded from the game's own data the way {@link InstalledGenerationData} loads a server's: every sampled
 * column's height must be the game's noise height ({@code getBaseHeight}, the noise and aquifer
 * step a real chunk starts from) and every column's biome the biome source's.
 */
class TerrainSamplerTest {
    private static final long SEED = 20261125L;
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static PredictionInputs inputs;

    @BeforeAll
    static void load() {
        inputs = VanillaGenerationData.get().inputsFor("minecraft:overworld", SEED).orElseThrow();
    }

    @Test
    void blockColumnsMatchTheGamesNoiseHeights() {
        TerrainColumns columns = new TerrainColumns(inputs, MIN_Y, HEIGHT);
        LevelHeightAccessor heights = LevelHeightAccessor.create(MIN_Y, HEIGHT);
        int mismatched = 0;
        int compared = 0;
        // A few level-0 squares spread over the land and the sea.
        for (int[] origin : new int[][] {{0, 0}, {1024, -2048}, {-3072, 512}, {4096, 4096}}) {
            columns.sample(origin[0], origin[1], 32, 1);
            for (int z = 0; z < 32; z += 3) {
                for (int x = 0; x < 32; x += 3) {
                    int expected = inputs.generator().getBaseHeight(origin[0] + x, origin[1] + z, Heightmap.Types.WORLD_SURFACE_WG, heights,
                        inputs.randomState()) - 1 - MIN_Y;
                    compared++;
                    if (columns.tops[x + z * 32] != expected && !extended(columns, x + z * 32)) {
                        mismatched++;
                    }
                }
            }
        }
        assertEquals(0, mismatched, "columns whose height differs from the game's, of " + compared);
    }

    @Test
    void gridColumnsMatchTheGamesNoiseHeights() {
        TerrainColumns columns = new TerrainColumns(inputs, MIN_Y, HEIGHT);
        LevelHeightAccessor heights = LevelHeightAccessor.create(MIN_Y, HEIGHT);
        int mismatched = 0;
        int compared = 0;
        for (int level = 2; level <= 5; level++) {
            int step = 1 << level;
            int origin = -TileKey.span(level) * 3;
            columns.sample(origin, origin, 33, step);
            for (int z = 0; z < 33; z += 4) {
                for (int x = 0; x < 33; x += 4) {
                    int expected = inputs.generator().getBaseHeight(origin + x * step, origin + z * step, Heightmap.Types.WORLD_SURFACE_WG,
                        heights, inputs.randomState()) - 1 - MIN_Y;
                    compared++;
                    if (columns.tops[x + z * 33] != expected && !extended(columns, x + z * 33)) {
                        mismatched++;
                    }
                }
            }
        }
        assertEquals(0, mismatched, "grid columns whose height differs from the game's, of " + compared);
    }

    @Test
    void biomesAreTheBiomeSourcesAtTheColumnsTop() {
        TerrainColumns columns = new TerrainColumns(inputs, MIN_Y, HEIGHT);
        BiomeResolver source = inputs.biomeSource().createUncachedResolver(inputs.randomState());
        columns.sample(2048, -1024, 33, 16);
        for (int i = 0; i < 33 * 33; i += 7) {
            int x = 2048 + (i % 33) * 16;
            int z = -1024 + (i / 33) * 16;
            int y = MIN_Y + Math.max(columns.tops[i], 0);
            Holder<Biome> expected = source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
            assertEquals(expected, columns.biomes[i], "biome at " + x + ", " + z);
        }
    }

    @Test
    void surfacesTakeTheirBiomesMaterial() {
        TerrainColumns columns = new TerrainColumns(inputs, MIN_Y, HEIGHT);
        columns.sample(0, 0, 33, 8);
        int grass = 0;
        int land = 0;
        for (int i = 0; i < columns.tops.length; i++) {
            int top = columns.tops[i];
            BlockState state = columns.columns[i][top];
            if (top + MIN_Y > inputs.seaLevel() && state.getFluidState().isEmpty()) {
                land++;
                if (!state.is(inputs.settings().defaultBlock().getBlock())) {
                    grass++;
                }
            }
        }
        // Above the sea the surface rules turn the top of nearly every column into something other
        // than bare stone (grass, sand, snow, gravel); only steep stony peaks keep it.
        assertTrue(land == 0 || grass * 10 >= land * 6, grass + " of " + land + " land columns have a surface block");
    }

    @Test
    void predictedTilesAreKnownAndLandedEverywhere() {
        TerrainSampler sampler = new TerrainSampler(inputs, MIN_Y, HEIGHT, new TableIds(), new Canopies(SEED));
        List<String> timings = new ArrayList<>();
        for (int level = 0; level <= 5; level++) {
            long key = TileKey.of(level, 1, -1);
            long started = System.nanoTime();
            TileVoxels tile = sampler.sample(key);
            double millis = (System.nanoTime() - started) / 1.0e6;
            timings.add(String.format(Locale.ROOT, "L%d %.1f ms", level, millis));
            int withLand = 0;
            for (int z = 0; z < TileVoxels.SIZE; z++) {
                for (int x = 0; x < TileVoxels.SIZE; x++) {
                    assertTrue(tile.known(x, z));
                    if (tile.top(x, z, sampler.ids()) >= 0) {
                        withLand++;
                    }
                }
            }
            assertEquals(TileVoxels.COLUMNS, withLand, "every column has something in it at level " + level);
        }
        System.out.println("Predicted tile times (first ones include warming up): " + timings);
    }

    /** Warm timings per level, when asked for ({@code -Plitelights.farterrain.probe=true}). */
    @Test
    void probeCostPerLevel() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("litelights.farterrain.probe"));
        TerrainSampler sampler = new TerrainSampler(inputs, MIN_Y, HEIGHT, new TableIds(), new Canopies(SEED));
        for (int warm = 0; warm < 6; warm++) {
            sampler.sample(TileKey.of(warm % 4, warm, warm));
        }
        for (int level = 0; level <= 6; level++) {
            int tiles = 6;
            long started = System.nanoTime();
            for (int i = 0; i < tiles; i++) {
                sampler.sample(TileKey.of(level, 7 + i, -3 - i));
            }
            double millis = (System.nanoTime() - started) / 1.0e6 / tiles;
            double chunks = Math.pow(TileKey.span(level) / 16.0, 2);
            System.out.println(String.format(Locale.ROOT, "probe L%d: %.1f ms a tile, %.3f ms per chunk of ground, %.0f chunks a second a thread",
                level, millis, millis / chunks, chunks * 1000.0 / millis));
        }
    }

    /** Columns the game's badlands or iceberg extensions changed, which getBaseHeight doesn't run. */
    private static boolean extended(TerrainColumns columns, int index) {
        int top = columns.tops[index];
        return top >= 0 && (columns.columns[index][top].is(net.minecraft.world.level.block.Blocks.PACKED_ICE)
            || columns.columns[index][top].is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK)
            || columns.columns[index][top].is(net.minecraft.tags.BlockTags.TERRACOTTA));
    }

    /** Ids by a table; water is water, leaves and anything with a solid face solid, the rest air. */
    static final class TableIds implements TileIds {
        private final Object2IntOpenHashMap<BlockState> ids = new Object2IntOpenHashMap<>();
        private final List<BlockState> states = new ArrayList<>(List.of(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
        private final Object2IntOpenHashMap<Holder<Biome>> biomes = new Object2IntOpenHashMap<>();

        @Override
        public synchronized int idOf(BlockState state) {
            if (state.isAir()) {
                return 0;
            }
            return ids.computeIfAbsent(state, key -> {
                states.add(state);
                return states.size() - 1;
            });
        }

        @Override
        public synchronized short biomeIdOf(Holder<Biome> biome) {
            return (short) biomes.computeIfAbsent(biome, key -> biomes.size());
        }

        synchronized BlockState state(int id) {
            return states.get(id);
        }

        @Override
        public synchronized int kind(int id) {
            BlockState state = states.get(id);
            if (state.isAir()) {
                return BlockKinds.AIR;
            }
            if (!state.getFluidState().isEmpty() && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                return BlockKinds.WATER;
            }
            return BlockKinds.SOLID;
        }
    }
}
