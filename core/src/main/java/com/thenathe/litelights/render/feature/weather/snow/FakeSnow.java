package com.thenathe.litelights.render.feature.weather.snow;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * How many layers of fake snow a block gets: the rule both the chunk meshes ({@link SnowGeometry})
 * and the water stream's column map (the snowy grass and the fanning) ask, so the two always agree.
 *
 * <p>A spot takes the season's snow where the snow would reach it (straight under the sky: full sky
 * light, as vanilla's snow lies on the highest block rain stops on) and the season makes it cold
 * enough there. How many layers is the layer rule ({@link SnowLayerRule}) on its area's shares at
 * the column's point, so the fake snow builds a second and third layer, and thaws, with the real
 * snow around it, by the same shares and the same 90 % rule. Where the season has no snow on that
 * area's open ground at all, yet real snow lies on two or more sides (snow that lies whatever the
 * season, or a world's own generated snow), the spot follows its shallowest neighbour instead.
 *
 * <p><b>Only the top of a column</b> (far-snow-115, the owner): fake snow goes only where snow would
 * really settle, on the highest block of its column that stops snow (as the game's weather counts
 * it: a block that blocks movement, or a fluid) with nothing over it, so in a stack of fences, walls,
 * panes, slabs or stairs only the top one is snowed ({@link #topOfColumn}). Snow at a block's base
 * lies on the ground under it, so it needs a full top there, and snow falls past the thin blocks
 * of a stack to reach it ({@link #groundOpen}, visual-snow-117).
 */
public final class FakeSnow {
    private static final int FULL_SKY = 15;
    /**
     * How many blocks over a spot are looked through for something that stops the snow. A chunk
     * mesh's region reaches at least this far above any of its blocks; anything that blocks the sky's
     * light higher up already rules the spot out by its light.
     */
    static final int COLUMN_REACH = 15;

    private FakeSnow() {
    }

    /**
     * Layers of the season's snow at {@code pos} where it would reach {@code openTo} (the space whose
     * sky light says whether the sky is straight above), or 0.
     *
     * @param biome  the biome at {@code pos}, as the caller has it (a mesh's region, or the level)
     * @param record whether a chunk mesh is being built, so the section notes the column's point
     */
    public static int layers(SnowState snow, BlockAndLightGetter world, Holder<Biome> biome, BlockPos pos, BlockPos openTo,
                             boolean record) {
        ClientLevel level = snow.level();
        if (level == null) {
            return 0;
        }
        boolean open = world.getBrightness(LightLayer.SKY, openTo) >= FULL_SKY;
        boolean cold = open && snow.climate().coldEnough(level, biome, pos);
        int area = snow.areaFor(snow.climate().area(level, biome));
        double point = SnowLayerRule.point(pos.getX(), pos.getZ());
        if (record) {
            // Every host's section is noted, so a change of season or of a roof finds it; only a spot
            // the season's snow can reach now counts its point, so a share passing a step builds
            // again only the sections where it can change what is drawn.
            SnowSections.note(pos, area, SnowLayerRule.bucket(point), cold);
        }
        if (!open) {
            return 0;
        }
        int layers = cold ? snow.shares().layersAt(area, point) : 0;
        if (layers == 0 && !snow.shares().seasonal(area)) {
            // Snow that lies whatever the season (a snowfield's own, under Almanac in summer) lies
            // here too, warm or not, when it lies on two sides or more.
            layers = RealSnow.shallowestOfTwoOrMoreBeside(world, pos);
        }
        return Math.min(layers, SnowLayerRule.MOST_LAYERS);
    }

    /**
     * Layers of fake snow at the base of a block that stands in its space (a plant, a fence), or 0.
     * Where real snow is already there (LambdaBetterGrass's included) none is drawn, so the two never
     * double; where LambdaBetterGrass draws none, the visual snow's own rule fills in, so the two
     * leave no gap between them.
     */
    public static int atBase(SnowState snow, BlockAndLightGetter world, Holder<Biome> biome, BlockPos pos, BlockState state,
                             boolean record) {
        if (RealSnow.layersAt(world, pos, state, snow.betterSnow()) > 0) {
            return 0;
        }
        if (!groundOpen(world, pos, state) || !world.getBlockState(pos.below()).isFaceSturdy(world, pos.below(), Direction.UP)) {
            return 0;
        }
        return layers(snow, world, biome, pos, pos, record);
    }

    /**
     * Whether nothing over a block stops the snow falling onto it: in the {@link #COLUMN_REACH}
     * blocks above, no block the game's weather heightmap counts (its blocks-motion tag, or a solid
     * block while the tags aren't bound) and no fluid, the upper half of the block itself (a door, a
     * tall plant) aside.
     */
    static boolean topOfColumn(BlockGetter world, BlockPos pos, BlockState state) {
        return nothingStops(world, pos, state, false);
    }

    /**
     * Whether snow reaches the ground at a thin block's foot (visual-snow-117, the owner): as
     * {@link #topOfColumn}, but snow falls past thin blocks standing over it (fences, walls, panes,
     * bars, gates, doors, lanterns, plants: the blocks that take snow at their base), so the ground
     * at the foot of a stack of posts is snowed like the ground beside it, while only the stack's top
     * takes snow on its own surfaces. A block that covers the column (stone, a slab) still keeps it off.
     */
    static boolean groundOpen(BlockGetter world, BlockPos pos, BlockState state) {
        return nothingStops(world, pos, state, true);
    }

    private static boolean nothingStops(BlockGetter world, BlockPos pos, BlockState state, boolean pastThinBlocks) {
        BlockPos.MutableBlockPos above = pos.mutable();
        for (int step = 1; step <= COLUMN_REACH; step++) {
            above.move(Direction.UP);
            BlockState over = world.getBlockState(above);
            if (step == 1 && over.is(state.getBlock())
                && over.getValueOrElse(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER) == DoubleBlockHalf.UPPER) {
                continue;
            }
            if (pastThinBlocks && SnowHosts.cached(over).base()) {
                continue;
            }
            if (over.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP) || over.isSolid() || !over.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Layers of fake snow on the upward surfaces of a block real snow can't lie on, or 0. */
    public static int onTop(SnowState snow, BlockAndLightGetter world, Holder<Biome> biome, BlockPos pos, boolean record) {
        BlockPos above = pos.above();
        BlockState over = world.getBlockState(above);
        if (!over.getFluidState().isEmpty() || RealSnow.vanillaLayers(over) > 0 || over.isSolidRender()) {
            return 0;
        }
        if (!topOfColumn(world, pos, world.getBlockState(pos))) {
            return 0;
        }
        return layers(snow, world, biome, pos, above, record);
    }
}
