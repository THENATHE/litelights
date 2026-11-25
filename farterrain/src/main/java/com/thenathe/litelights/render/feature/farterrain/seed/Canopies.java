package com.thenathe.litelights.render.feature.farterrain.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.WeightedRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomChancePlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

/**
 * Stand-in trees, from each biome's own tree features: how many trees a chunk of the biome gets on
 * average (the count, rarity and chance of each placed tree feature, through random selectors to
 * the trees in them), and each kind's own trunk and leaves and its trunk height, crown height and
 * crown radius as its trunk and foliage placers give them. Trees are then placed at positions of
 * LiteLights' own choosing, a fixed number per chunk from the seed and the chunk, as a trunk with
 * a rounded crown. They read as the biome's forest from far away (its density, its colour, its
 * height); they are not the game's trees, and stand elsewhere than the real ones do.
 *
 * <p>Any mod's biome comes through as long as its trees are the game's tree feature; other tree
 * shapes (a mod's own feature type) are left out.
 *
 * <p>Thread-safe: the biome table is shared by the worker threads.
 */
public final class Canopies {
    /** One kind of tree a biome grows, with how many a chunk gets on average. */
    record TreeKind(double perChunk, BlockState log, BlockState leaves, int trunkHeight, int crownHeight, int crownRadius) {
    }

    private static final int NESTING = 6;
    private static final int SAMPLES = 256;
    private static final int MAX_CROWN_RADIUS = 4;

    private final long seed;
    private final Map<Holder<Biome>, List<TreeKind>> forests = new ConcurrentHashMap<>();

    public Canopies(long seed) {
        this.seed = seed;
    }

    /** The tree kinds a biome grows. */
    List<TreeKind> forest(Holder<Biome> biome) {
        return forests.computeIfAbsent(biome, Canopies::readForest);
    }

    private static List<TreeKind> readForest(Holder<Biome> biome) {
        List<TreeKind> kinds = new ArrayList<>();
        List<HolderSet<PlacedFeature>> steps = biome.value().getGenerationSettings().features();
        int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        if (step < steps.size()) {
            for (Holder<PlacedFeature> placed : steps.get(step)) {
                try {
                    collect(placed.value(), 1.0, kinds, 0);
                } catch (RuntimeException unreadable) {
                    // A mod's placement or feature that can't be read without a level: left out.
                }
            }
        }
        return List.copyOf(kinds);
    }

    private static void collect(PlacedFeature placed, double share, List<TreeKind> kinds, int depth) {
        double count = share * expectedCount(placed.placement());
        if (count <= 0.0 || depth > NESTING) {
            return;
        }
        Feature feature = placed.feature().value();
        switch (feature) {
            case TreeFeature tree -> kinds.add(treeKind(tree, count));
            case RandomSelectorFeature selector -> {
                double left = 1.0;
                for (WeightedPlacedFeature option : selector.features()) {
                    double chance = left * option.chance();
                    collect(option.feature().value(), count * chance, kinds, depth + 1);
                    left -= chance;
                }
                collect(selector.defaultFeature().value(), count * Math.max(0.0, left), kinds, depth + 1);
            }
            case SimpleRandomSelectorFeature selector -> {
                int size = selector.features().size();
                for (Holder<PlacedFeature> option : selector.features()) {
                    collect(option.value(), count / size, kinds, depth + 1);
                }
            }
            case RandomBooleanSelectorFeature selector -> {
                collect(selector.featureTrue().value(), count / 2.0, kinds, depth + 1);
                collect(selector.featureFalse().value(), count / 2.0, kinds, depth + 1);
            }
            case WeightedRandomSelectorFeature selector -> {
                int total = selector.features().unwrap().stream().mapToInt(weighted -> weighted.weight()).sum();
                selector.features().unwrap().forEach(weighted ->
                    collect(weighted.value().value(), count * weighted.weight() / Math.max(1, total), kinds, depth + 1));
            }
            default -> {
            }
        }
    }

    /** How many times a placed feature is tried in a chunk on average: its counts times its chances. */
    static double expectedCount(List<PlacementModifier> placement) {
        RandomSource random = new LegacyRandomSource(0x5EEDL);
        double count = 1.0;
        for (PlacementModifier modifier : placement) {
            switch (modifier) {
                case CountPlacement counted -> count *= average(random, counted);
                case NoiseBasedCountPlacement noise -> count *= Math.max(0, noise.noiseToCountRatio() * noise.noiseOffset());
                case NoiseThresholdCountPlacement noise -> count *= (noise.belowNoise() + noise.aboveNoise()) / 2.0;
                case RarityFilter rarity -> count /= rarity.chance();
                case RandomChancePlacement chance -> count *= chance.chance();
                default -> {
                }
            }
        }
        return count;
    }

    private static double average(RandomSource random, CountPlacement counted) {
        double sum = 0.0;
        for (int i = 0; i < SAMPLES; i++) {
            sum += counted.count().sample(random);
        }
        return sum / SAMPLES;
    }

    private static TreeKind treeKind(TreeFeature tree, double perChunk) {
        RandomSource random = new LegacyRandomSource(0x7EE5L);
        int trunk = 0;
        int crown = 0;
        int radius = 0;
        for (int i = 0; i < 8; i++) {
            int height = tree.trunkPlacer().getTreeHeight(random);
            trunk += height;
            crown += tree.foliagePlacer().foliageHeight(random, height, tree);
            radius += tree.foliagePlacer().foliageRadius(random, height);
        }
        BlockState log = state(tree.trunkProvider(), Blocks.OAK_LOG.defaultBlockState());
        BlockState leaves = state(tree.foliageProvider(), Blocks.OAK_LEAVES.defaultBlockState());
        // A placer's radius is its widest row (a spruce's at the bottom); the stand-in crown narrows
        // toward the top, is never wider than it is tall, and keeps to the size of ordinary trees.
        int crownRadius = Math.clamp(Math.round(radius / 8.0f), 1, MAX_CROWN_RADIUS);
        int crownHeight = Math.max(Math.max(1, crown / 8), 2 * crownRadius - 1);
        return new TreeKind(perChunk, log, leaves, Math.max(1, trunk / 8), crownHeight, crownRadius);
    }

    private static BlockState state(Holder<BlockStateProvider> provider, BlockState fallback) {
        return provider.value() instanceof SimpleStateProvider(BlockState state) ? state : fallback;
    }

    /**
     * Puts trees on sampled columns. At step 1 each tree is written into the columns it stands in;
     * on a coarse grid a column takes a tree's crown when the tree's crown reaches within half a
     * step of it, so a coarse voxel holds leaves where trees would make it solid if the land were
     * seen, as near as its corners can say.
     */
    void grow(TerrainColumns columns) {
        int step = columns.step;
        int span = (columns.size - 1) * step + 1;
        int reach = step == 1 ? 0 : step / 2;
        int margin = 4 + reach;
        int minChunkX = Math.floorDiv(columns.originX - margin, 16);
        int minChunkZ = Math.floorDiv(columns.originZ - margin, 16);
        int maxChunkX = Math.floorDiv(columns.originX + span + margin, 16);
        int maxChunkZ = Math.floorDiv(columns.originZ + span + margin, 16);
        RandomSource random = new LegacyRandomSource(0L);
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                random.setSeed(seed ^ (chunkX * 341873128712L + chunkZ * 132897987541L) ^ 0x7EE5L);
                int centreX = chunkX * 16 + 8;
                int centreZ = chunkZ * 16 + 8;
                List<TreeKind> forest = forest(columns.zoomedBiome(centreX, columns.minY() + heightAt(columns, centreX, centreZ), centreZ));
                for (TreeKind kind : forest) {
                    int trees = (int) kind.perChunk;
                    if (random.nextDouble() < kind.perChunk - trees) {
                        trees++;
                    }
                    for (int i = 0; i < trees; i++) {
                        int x = chunkX * 16 + random.nextInt(16);
                        int z = chunkZ * 16 + random.nextInt(16);
                        plant(columns, kind, x, z, reach);
                    }
                }
            }
        }
    }

    private static void plant(TerrainColumns columns, TreeKind kind, int x, int z, int reach) {
        int ground = heightAt(columns, x, z);
        if (ground < 0) {
            return;
        }
        BlockState soil = blockAt(columns, x, ground, z);
        // The ground a sapling takes: dirt, grass, podzol, mycelium, mud and moss (grass left #dirt in 26.3).
        if (soil == null || !soil.is(BlockTags.SUBSTRATE_OVERWORLD)) {
            return;
        }
        int trunkTop = ground + kind.trunkHeight;
        int crownBottom = trunkTop - kind.crownHeight + 1;
        int crownTop = trunkTop + 1;
        int radius = kind.crownRadius + reach;
        int step = columns.step;
        int firstX = Math.floorDiv(x - radius - columns.originX + step - 1, step);
        int lastX = Math.floorDiv(x + radius - columns.originX, step);
        int firstZ = Math.floorDiv(z - radius - columns.originZ + step - 1, step);
        int lastZ = Math.floorDiv(z + radius - columns.originZ, step);
        for (int gz = Math.max(0, firstZ); gz <= Math.min(columns.size - 1, lastZ); gz++) {
            for (int gx = Math.max(0, firstX); gx <= Math.min(columns.size - 1, lastX); gx++) {
                int blockX = columns.originX + gx * step;
                int blockZ = columns.originZ + gz * step;
                int dx = blockX - x;
                int dz = blockZ - z;
                int index = gx + gz * columns.size;
                BlockState[] blocks = columns.columns[index];
                if (dx == 0 && dz == 0) {
                    for (int row = ground + 1; row <= trunkTop && row < blocks.length; row++) {
                        if (blocks[row].isAir()) {
                            blocks[row] = kind.log;
                        }
                    }
                }
                for (int row = crownBottom; row <= crownTop && row < blocks.length; row++) {
                    // Full width in the lower part of the crown, narrowing to about a third at the top.
                    float fromTop = (crownTop - row) / (float) Math.max(1, crownTop - crownBottom);
                    int rowRadius = Math.round(kind.crownRadius * Math.min(1.0f, 0.35f + fromTop)) + reach;
                    if (row < 0 || dx * dx + dz * dz > rowRadius * rowRadius + rowRadius) {
                        continue;
                    }
                    if (blocks[row].isAir()) {
                        blocks[row] = kind.leaves;
                    }
                }
                columns.tops[index] = Math.max(columns.tops[index], Math.min(crownTop, blocks.length - 1));
            }
        }
    }

    /** The top row at a block position: the column there at step 1, the nearest grid column on a grid. */
    private static int heightAt(TerrainColumns columns, int x, int z) {
        int index = nearest(columns, x, z);
        return index < 0 ? -1 : columns.tops[index];
    }

    private static BlockState blockAt(TerrainColumns columns, int x, int row, int z) {
        int index = nearest(columns, x, z);
        return index < 0 || row < 0 ? null : columns.columns[index][row];
    }

    private static int nearest(TerrainColumns columns, int x, int z) {
        int gx = Math.round((x - columns.originX) / (float) columns.step);
        int gz = Math.round((z - columns.originZ) / (float) columns.step);
        if (gx < 0 || gz < 0 || gx >= columns.size || gz >= columns.size) {
            return -1;
        }
        return gx + gz * columns.size;
    }
}
