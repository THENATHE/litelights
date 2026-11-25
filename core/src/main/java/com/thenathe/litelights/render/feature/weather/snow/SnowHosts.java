package com.thenathe.litelights.render.feature.weather.snow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.DoubleStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Which blocks the visual snow draws fake snow on, and how, decided once per block state as models
 * bake. Two ways, often both on one block:
 * <ul>
 *   <li><b>At its base</b> ({@link Host#base}): a snow layer in the block's own space, from the
 *   ground up, as if the block stood in the snow (snowlogged). Plants (flowers, grass, ferns, bushes,
 *   saplings, the lower half of tall plants), and blocks that stand on the ground without filling
 *   their space: fences, walls, panes and bars, fence gates, the lower half of doors, standing
 *   torches and lanterns. These are the kinds of block LambdaBetterGrass's better snow covers, found
 *   by the game's classes rather than its list, so other mods' fences and flowers count too.</li>
 *   <li><b>On its top</b> ({@link Host#tops}): snow on the upward surfaces of the block's own shape
 *   (SnowSurfaces), for blocks real snow can't lie on because their top isn't a full face: stairs
 *   (bottom half), bottom slabs, walls, fences, panes and bars, fence gates and closed bottom
 *   trapdoors.</li>
 * </ul>
 * Real snow layers are hosts of their own ({@link Host#realSnow}), for the snowy overlay. Blocks of
 * other mods are hosts when they are one of these classes. Crops and stems are left out (their
 * farmland can't hold snow), and so is anything holding water.
 */
public final class SnowHosts {
    /** How a block state takes fake snow; {@link #NONE} for most blocks. */
    public record Host(boolean base, SnowSurfaces tops, boolean realSnow) {
        public boolean any() {
            return base || !tops.isEmpty() || realSnow;
        }
    }

    public static final Host NONE = new Host(false, SnowSurfaces.NONE, false);
    private static final Map<BlockState, Host> CACHE = new ConcurrentHashMap<>();

    private SnowHosts() {
    }

    /** {@link #of}, worked out once per block state until the models load again. */
    public static Host cached(BlockState state) {
        return CACHE.computeIfAbsent(state, SnowHosts::of);
    }

    /** The models are loading again (a resource reload): shapes are read afresh. */
    static void forget() {
        CACHE.clear();
    }

    public static Host of(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof SnowLayerBlock) {
            return new Host(false, SnowSurfaces.NONE, true);
        }
        if (!state.getFluidState().isEmpty()) {
            return NONE;
        }
        boolean base = takesSnowAtBase(state, block);
        SnowSurfaces tops = takesSnowOnTop(state, block) ? surfaces(state) : SnowSurfaces.NONE;
        if (tops.fullTop()) {
            tops = SnowSurfaces.NONE;
        }
        return base || !tops.isEmpty() ? new Host(base, tops, false) : NONE;
    }

    private static boolean takesSnowAtBase(BlockState state, Block block) {
        if (block instanceof VegetationBlock) {
            if (block instanceof CropBlock || block instanceof StemBlock) {
                return false;
            }
            return !(block instanceof DoublePlantBlock) || lowerHalf(state);
        }
        if (block instanceof FenceBlock || block instanceof WallBlock || block instanceof IronBarsBlock
            || block instanceof FenceGateBlock) {
            return true;
        }
        if (block instanceof DoorBlock) {
            return lowerHalf(state);
        }
        if (block instanceof BaseTorchBlock) {
            return !(block instanceof WallTorchBlock) && !(block instanceof RedstoneWallTorchBlock);
        }
        if (block instanceof LanternBlock) {
            return !state.getValueOrElse(BlockStateProperties.HANGING, false);
        }
        return false;
    }

    private static boolean takesSnowOnTop(BlockState state, Block block) {
        if (block instanceof StairBlock) {
            return state.getValueOrElse(BlockStateProperties.HALF, Half.TOP) == Half.BOTTOM;
        }
        if (block instanceof SlabBlock) {
            return state.getValueOrElse(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE) == SlabType.BOTTOM;
        }
        if (block instanceof TrapDoorBlock) {
            return !state.getValueOrElse(BlockStateProperties.OPEN, true)
                && state.getValueOrElse(BlockStateProperties.HALF, Half.TOP) == Half.BOTTOM;
        }
        return block instanceof WallBlock || block instanceof FenceBlock || block instanceof IronBarsBlock
            || block instanceof FenceGateBlock;
    }

    private static boolean lowerHalf(BlockState state) {
        return state.getValueOrElse(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER) == DoubleBlockHalf.LOWER;
    }

    /** The block's own outline shape, as the game draws its outline; a shape that needs the world reads as empty there. */
    private static SnowSurfaces surfaces(BlockState state) {
        VoxelShape shape = shapeOf(state);
        if (shape == null || shape.isEmpty()) {
            return SnowSurfaces.NONE;
        }
        double[] boxes = shape.toAabbs().stream()
            .flatMapToDouble(box -> DoubleStream.of(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ))
            .toArray();
        return SnowSurfaces.ofBoxes(boxes);
    }

    private static @Nullable VoxelShape shapeOf(BlockState state) {
        try {
            return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException needsTheWorld) {
            return null;
        }
    }
}
