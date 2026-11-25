package com.thenathe.litelights.render.feature.farterrain.seed;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.material.Fluids;

/**
 * A square of the game's terrain, column by column, worked out from the seed the way the game's
 * own noise step and surface step work it out, without a chunk: the dimension's compiled final
 * density and aquifer give each block's substance (stone, water, lava or air), then the
 * dimension's surface rules (the game's own rule evaluator) give the blocks near each surface
 * their material, with the badlands and iceberg extensions the game adds there. Carvers, features
 * and structures are not run; {@link Canopies} stands in for the trees.
 *
 * <p>Two shapes of square: every block column of an area (level 0 and 1 tiles, sampled as one
 * volume, which is how a chunk is sampled and gives the same values), or every {@code step}
 * blocks on a grid of corners (coarser tiles), each column sampled on its own at a noise-cell
 * corner, so only the cells' corners in that column are computed and nothing is interpolated
 * across the gaps. The game interpolates density within cells of 4 by 8 by 4 blocks (overworld),
 * so a column on a corner is exact in every block of its height.
 *
 * <p>One per worker thread: everything here is scratch room reused tile after tile.
 */
final class TerrainColumns {
    /** At full detail rules are run on every solid block this far under the column's top... */
    private static final int SURFACE_DEPTH = 12;
    /** ...on the blocks this close over a cave or overhang's ceiling (sandstone under sand and the like)... */
    private static final int CEILING_DEPTH = 4;

    private final PredictionInputs inputs;
    private final int minY;
    private final int height;
    private final NoiseGeneratorSettings settings;
    private final Aquifer.FluidPicker fluids;
    private final LevelHeightAccessor heights;
    private final WorldGenerationContext generation;
    private final BlockState defaultBlock;
    private final BiomeResolver resolver;
    private final Long2ObjectOpenHashMap<Holder<Biome>> noiseBiomes = new Long2ObjectOpenHashMap<>();
    private final BiomeManager biomeManager;

    // The square last sampled.
    int size;
    int step;
    /** Blocks a voxel of the tile being made: only the top block of each voxel's rows is ever seen. */
    int voxel = 1;
    int originX;
    int originZ;
    /** Per column (x + z * size), its blocks from minY up. */
    BlockState[][] columns = new BlockState[0][];
    /** Per column, the row of its highest block that isn't air, -1 if none. */
    int[] tops = new int[0];
    /** Per column, the biome at its top, as a chunk stores it (the noise biome of that quart). */
    @SuppressWarnings("unchecked")
    Holder<Biome>[] biomes = new Holder[0];

    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    TerrainColumns(PredictionInputs inputs, int minY, int height) {
        this.inputs = inputs;
        this.minY = minY;
        this.height = height;
        this.settings = inputs.settings();
        this.fluids = fluidPicker(inputs.generator(), settings);
        this.heights = LevelHeightAccessor.create(minY, height);
        this.generation = new WorldGenerationContext(inputs.generator(), heights);
        this.defaultBlock = settings.defaultBlock();
        SamplerContext climateContext = SamplerContext.builder().enableCaches().build();
        BiomeResolver uncached = inputs.biomeSource().createResolver(inputs.randomState().createClimateSampler(climateContext));
        // Columns side by side share their quarts, and the surface rules ask for the zoomed biome
        // of every block they look at (eight quarts each): kept per square, they are worked out once.
        this.resolver = (quartX, quartY, quartZ) -> {
            long key = BlockPos.asLong(quartX, quartY, quartZ);
            Holder<Biome> known = noiseBiomes.get(key);
            if (known == null) {
                known = uncached.getNoiseBiome(quartX, quartY, quartZ);
                noiseBiomes.put(key, known);
            }
            return known;
        };
        this.biomeManager = new BiomeManager(resolver, BiomeManager.obfuscateSeed(inputs.seed()));
    }

    int minY() {
        return minY;
    }

    int height() {
        return height;
    }

    /** The noise biome of a block, as a chunk stores it. */
    Holder<Biome> noiseBiome(int blockX, int blockY, int blockZ) {
        return resolver.getNoiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockY), QuartPos.fromBlock(blockZ));
    }

    /** The biome the game's block-level lookups see (zoomed, fuzzed by the seed's hash). */
    Holder<Biome> zoomedBiome(int blockX, int blockY, int blockZ) {
        return biomeManager.getBiome(scratch.set(blockX, blockY, blockZ));
    }

    /**
     * Samples a square of {@code size} by {@code size} columns from (originX, originZ), {@code step}
     * blocks apart. With step 1 the square is one volume; otherwise every column stands on a
     * noise-cell corner (the origin and step must be multiples of the cell width).
     */
    void sample(int originX, int originZ, int size, int step) {
        sample(originX, originZ, size, step, step);
    }

    /** As above, for a tile whose voxels are {@code voxel} blocks: surface rules run only where a voxel's look comes from. */
    void sample(int originX, int originZ, int size, int step, int voxel) {
        this.voxel = voxel;
        this.originX = originX;
        this.originZ = originZ;
        this.size = size;
        this.step = step;
        int count = size * size;
        if (columns.length != count) {
            columns = new BlockState[count][];
            tops = new int[count];
            @SuppressWarnings("unchecked")
            Holder<Biome>[] made = new Holder[count];
            biomes = made;
        }
        for (int i = 0; i < count; i++) {
            if (columns[i] == null || columns[i].length != height) {
                columns[i] = new BlockState[height];
            }
        }
        noiseBiomes.clear();
        DensityVolume volume = new DensityVolume(size, height, size, originX, minY, originZ, step, 1, step);
        try (NoiseChunk noise = new NoiseChunk(inputs.randomState(), null, settings, fluids, Blender.empty(), volume)) {
            DensitySampler.Bound finalDensity = noise.cachingSamplers().get(settings.noiseRouter().finalDensity());
            Aquifer aquifer = noise.aquifer();
            if (step == 1) {
                try (ScopedDensityBuffer density = finalDensity.sampleVolume(volume)) {
                    for (int z = 0; z < size; z++) {
                        for (int x = 0; x < size; x++) {
                            fillColumn(x + z * size, originX + x, originZ + z, density, volume, x, z, aquifer);
                        }
                    }
                }
            } else {
                for (int z = 0; z < size; z++) {
                    for (int x = 0; x < size; x++) {
                        int blockX = originX + x * step;
                        int blockZ = originZ + z * step;
                        DensityVolume column = new DensityVolume(1, height, 1, blockX, minY, blockZ);
                        try (ScopedDensityBuffer density = finalDensity.sampleVolume(column)) {
                            fillColumn(x + z * size, blockX, blockZ, density, column, 0, 0, aquifer);
                        }
                    }
                }
            }
            buildSurfaces(noise);
        }
        for (int i = 0; i < count; i++) {
            int top = tops[i];
            int blockX = originX + (i % size) * step;
            int blockZ = originZ + (i / size) * step;
            biomes[i] = noiseBiome(blockX, minY + Math.max(top, 0), blockZ);
        }
    }

    private void fillColumn(int index, int blockX, int blockZ, ScopedDensityBuffer density, DensityVolume volume, int volumeX, int volumeZ,
                            Aquifer aquifer) {
        BlockState[] column = columns[index];
        int top = -1;
        for (int row = height - 1; row >= 0; row--) {
            int blockY = minY + row;
            float value = density.get(volume.indexUnchecked(volumeX, row, volumeZ));
            BlockState state = aquifer.computeSubstance(blockX, blockY, blockZ, value);
            if (state == null) {
                state = defaultBlock;
            }
            column[row] = state;
            if (top < 0 && !state.isAir()) {
                top = row;
            }
        }
        tops[index] = top;
    }

    /**
     * The game's surface step (MaterialSystem.buildSurface), column by column: the same walk down
     * each column with the same stone depths and water heights, through the dimension's own
     * compiled rule. The gradients the steep rule reads come from the columns either side, as the
     * game takes them from the chunk's heights (within the chunk at step 1, across the grid otherwise).
     */
    private void buildSurfaces(NoiseChunk noise) {
        MaterialSystem system = inputs.randomState().surfaceSystem();
        // A one-block expected volume: the rules' prefilled densities then fall back to sampling
        // per block, which the game does too wherever a block is outside its chunk's volume.
        DensityVolume expected = new DensityVolume(1, 1, 1, originX, minY, originZ);
        MaterialRuleContext context = MaterialContexts.create(system, inputs.randomState(), expected, noise.cachingSamplers(),
            biomeManager::getBiome, generation);
        RuleEvaluator rule = settings.materialRule().value().compile(context);
        int[] heightsBefore = Arrays.copyOf(tops, tops.length);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                surfaceColumn(system, context, rule, x, z, heightsBefore);
            }
        }
    }

    private void surfaceColumn(MaterialSystem system, MaterialRuleContext context, RuleEvaluator rule, int x, int z, int[] heightsBefore) {
        int index = x + z * size;
        BlockState[] blocks = columns[index];
        int blockX = originX + x * step;
        int blockZ = originZ + z * step;
        BlockColumn column = new BlockColumn() {
            @Override
            public BlockState getBlock(int blockY) {
                int row = blockY - minY;
                return row >= 0 && row < height ? blocks[row] : Blocks.AIR.defaultBlockState();
            }

            @Override
            public void setBlock(int blockY, BlockState state) {
                int row = blockY - minY;
                if (row >= 0 && row < height) {
                    blocks[row] = state;
                }
            }
        };
        int startingHeight = minY + tops[index] + 1;
        Holder<Biome> surfaceBiome = zoomedBiome(blockX, startingHeight, blockZ);
        if (surfaceBiome.is(Biomes.ERODED_BADLANDS)) {
            MaterialContexts.erodedBadlands(system, column, blockX, blockZ, startingHeight, heights);
            tops[index] = topRow(blocks);
        }
        int columnHeight = minY + tops[index] + 1;
        MaterialContexts.updateXZ(context, blockX, blockZ, gradientX(heightsBefore, x, z), gradientZ(heightsBefore, x, z));
        int stoneAboveDepth = 0;
        int waterHeight = Integer.MIN_VALUE;
        int nextCeilingStoneY = Integer.MAX_VALUE;
        for (int y = columnHeight; y >= minY; y--) {
            BlockState old = column.getBlock(y);
            if (old.isAir()) {
                stoneAboveDepth = 0;
                waterHeight = Integer.MIN_VALUE;
            } else if (!old.getFluidState().isEmpty()) {
                if (waterHeight == Integer.MIN_VALUE) {
                    waterHeight = y + 1;
                }
            } else {
                if (nextCeilingStoneY >= y) {
                    nextCeilingStoneY = net.minecraft.world.level.dimension.DimensionType.WAY_BELOW_MIN_Y;
                    for (int lookahead = y - 1; lookahead >= minY - 1; lookahead--) {
                        BlockState next = column.getBlock(lookahead);
                        if (next.isAir() || !next.getFluidState().isEmpty()) {
                            nextCeilingStoneY = lookahead + 1;
                            break;
                        }
                    }
                }
                stoneAboveDepth++;
                int stoneBelowDepth = y - nextCeilingStoneY + 1;
                // The rules are the game's own and cost the most of all this; they are only run where
                // a voxel's look comes from: the top block of each voxel's rows (the one a coarse
                // voxel shows), and at full detail every block a face can show.
                if (seen(blocks, x, z, y - minY, stoneAboveDepth, stoneBelowDepth)) {
                    MaterialContexts.updateY(context, stoneAboveDepth, stoneBelowDepth, waterHeight, y);
                    BlockState state = rule.tryApply(blockX, y, blockZ);
                    if (state != null) {
                        column.setBlock(y, state);
                    }
                }
            }
        }
        if (surfaceBiome.is(Biomes.FROZEN_OCEAN) || surfaceBiome.is(Biomes.DEEP_FROZEN_OCEAN)) {
            MaterialContexts.icebergs(system, context.getMinSurfaceLevel(), surfaceBiome.value(), column, scratch, blockX, blockZ, startingHeight);
            tops[index] = topRow(blocks);
        }
    }

    private boolean seen(BlockState[] blocks, int x, int z, int row, int stoneAboveDepth, int stoneBelowDepth) {
        if (voxel > 1) {
            return (row + 1) % voxel == 0 || row + 1 >= height || !solid(blocks[row + 1]);
        }
        return stoneAboveDepth <= SURFACE_DEPTH || stoneBelowDepth <= CEILING_DEPTH || openSideways(x, z, row);
    }

    /** A block with air or fluid beside it (a cliff's face); at the square's edge, taken as open. */
    private boolean openSideways(int x, int z, int row) {
        if (x == 0 || z == 0 || x == size - 1 || z == size - 1) {
            return true;
        }
        return !solid(columns[x - 1 + z * size][row]) || !solid(columns[x + 1 + z * size][row])
            || !solid(columns[x + (z - 1) * size][row]) || !solid(columns[x + (z + 1) * size][row]);
    }

    private static boolean solid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private static int topRow(BlockState[] blocks) {
        for (int row = blocks.length - 1; row >= 0; row--) {
            if (!blocks[row].isAir()) {
                return row;
            }
        }
        return -1;
    }

    /**
     * The game's height difference across a column, two blocks wide, clamped inside the chunk:
     * the steep rule's input. Between grid columns {@code step} apart it is the slope scaled to two blocks.
     */
    private int gradientX(int[] heights, int x, int z) {
        if (step == 1) {
            int chunkStart = x - ((originX + x) & 15);
            int east = Math.min(x + 1, Math.min(chunkStart + 15, size - 1));
            int west = Math.max(x - 1, Math.max(chunkStart, 0));
            return heights[east + z * size] - heights[west + z * size];
        }
        int east = Math.min(x + 1, size - 1);
        int west = Math.max(x - 1, 0);
        return Math.round((heights[east + z * size] - heights[west + z * size]) * 2.0f / ((east - west) * step));
    }

    private int gradientZ(int[] heights, int x, int z) {
        if (step == 1) {
            int chunkStart = z - ((originZ + z) & 15);
            int south = Math.min(z + 1, Math.min(chunkStart + 15, size - 1));
            int north = Math.max(z - 1, Math.max(chunkStart, 0));
            return heights[x + south * size] - heights[x + north * size];
        }
        int south = Math.min(z + 1, size - 1);
        int north = Math.max(z - 1, 0);
        return Math.round((heights[x + south * size] - heights[x + north * size]) * 2.0f / ((south - north) * step));
    }

    /**
     * Snow and ice where the game's freeze-top-layer feature puts them: ice on still water at the
     * top of a column whose biome is too cold to rain there, a snow layer on solid ground where it
     * is cold enough to snow (folded onto the block under it, as far terrain stores a cover).
     */
    void freezeTops(int seaLevel, BlockState snowCover) {
        BlockState ice = Blocks.ICE.defaultBlockState();
        for (int i = 0; i < tops.length; i++) {
            int top = tops[i];
            if (top < 0) {
                continue;
            }
            BlockState[] blocks = columns[i];
            int blockX = originX + (i % size) * step;
            int blockZ = originZ + (i / size) * step;
            int blockY = minY + top;
            Biome biome = zoomedBiome(blockX, blockY, blockZ).value();
            BlockState state = blocks[top];
            if (state.getFluidState().is(Fluids.WATER) && state.getFluidState().isSource()) {
                if (!biome.warmEnoughToRain(scratch.set(blockX, blockY, blockZ), seaLevel)) {
                    blocks[top] = ice;
                }
            } else if (state.getFluidState().isEmpty() && !state.is(Blocks.ICE) && !state.is(Blocks.PACKED_ICE) && !state.is(Blocks.BLUE_ICE)
                && biome.coldEnoughToSnow(scratch.set(blockX, blockY + 1, blockZ), seaLevel)) {
                blocks[top] = snowCover;
            }
        }
    }

    /** The generator's own fluid rule (sea level water, lava deep down), or the game's default one if it can't be read. */
    private static Aquifer.FluidPicker fluidPicker(NoiseBasedChunkGenerator generator, NoiseGeneratorSettings settings) {
        try {
            Field field = NoiseBasedChunkGenerator.class.getDeclaredField("globalFluidPicker");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Supplier<Aquifer.FluidPicker> supplier = (Supplier<Aquifer.FluidPicker>) field.get(generator);
            return supplier.get();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
            Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
            return (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : sea;
        }
    }
}
