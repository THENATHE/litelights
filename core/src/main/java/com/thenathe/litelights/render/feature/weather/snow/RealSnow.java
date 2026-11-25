package com.thenathe.litelights.render.feature.weather.snow;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one place that decides whether real snow is already in a block's space, and how deep, before
 * the visual snow draws any of its own there. Fake snow is never drawn over it, and the snowy
 * overlay weeps from it as from fake snow. Today's sources:
 * <ul>
 *   <li>vanilla's snow: a snow layer block (its layers), a snow block (a full eight);</li>
 *   <li>LambdaBetterGrass's better snow, while it is installed and on: on the blocks its resources
 *   list for its snow layer ({@code bettergrass/states}, type {@code layer}), standing beside a snow
 *   layer, it draws a layer as deep as that one (the deepest beside, as it shows).</li>
 * </ul>
 *
 * <p><b>Joinery.</b> The user's planned building mod will keep real snow layers inside a space
 * that also holds a stair, a slab, a flower or a fence, where the world shows only the other block.
 * When it exists, its query ("is there snow here, and how deep") plugs in here, in
 * {@link #layersAt}, as a third source, so LiteLights shows Joinery's snow and draws none of its own
 * over it. Nothing of Joinery's is assumed until then.
 */
public final class RealSnow {
    /** What LambdaBetterGrass's better snow does now, read once a tick on the client thread. */
    public record BetterSnow(boolean on, Set<Block> blocks) {
        public static final BetterSnow ABSENT = new BetterSnow(false, Set.of());

        boolean covers(Block block) {
            return on && blocks.contains(block);
        }
    }

    private RealSnow() {
    }

    /** Layers of real snow in the space at {@code pos}, 0 for none, 8 for a full block of snow. */
    public static int layersAt(BlockGetter level, BlockPos pos, BlockState state, BetterSnow betterSnow) {
        int vanilla = vanillaLayers(state);
        if (vanilla > 0) {
            return vanilla;
        }
        if (betterSnow.covers(state.getBlock())) {
            return deepestBeside(level, pos);
        }
        // Joinery's snow in a shared space goes here, when Joinery exists (see the class description).
        return 0;
    }

    /** Vanilla's snow in a block: a snow layer's layers, or a full block. */
    public static int vanillaLayers(BlockState state) {
        if (state.getBlock() instanceof SnowLayerBlock) {
            return state.getValue(SnowLayerBlock.LAYERS);
        }
        return state.is(Blocks.SNOW_BLOCK) ? SnowLayerRule.MOST_LAYERS : 0;
    }

    /** The deepest snow layer beside a block, on the four sides; 0 with none. */
    public static int deepestBeside(BlockGetter level, BlockPos pos) {
        int deepest = 0;
        BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockState state = level.getBlockState(beside.setWithOffset(pos, side));
            if (state.getBlock() instanceof SnowLayerBlock) {
                deepest = Math.max(deepest, state.getValue(SnowLayerBlock.LAYERS));
            }
        }
        return deepest;
    }

    /** How many of the four sides have a snow layer, and the shallowest of them, for snow that follows its neighbours. */
    public static int shallowestOfTwoOrMoreBeside(BlockGetter level, BlockPos pos) {
        int count = 0;
        int shallowest = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockState state = level.getBlockState(beside.setWithOffset(pos, side));
            if (state.getBlock() instanceof SnowLayerBlock) {
                count++;
                shallowest = Math.min(shallowest, state.getValue(SnowLayerBlock.LAYERS));
            }
        }
        return count >= 2 ? shallowest : 0;
    }
}
