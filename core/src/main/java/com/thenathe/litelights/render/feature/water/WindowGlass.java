package com.thenathe.litelights.render.feature.water;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.WaterloggedTransparentBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which blocks windows are made of, for the glass window map and the wall streaks: full glass
 * blocks (glass, stained and tinted glass, the impermeable tag, modded blocks of the glass class)
 * and glass panes (vanilla's, stained, and modded bars named glass). Iron and copper bars, copper
 * grates, ice, slime and honey are not glass here.
 */
public final class WindowGlass {
    private WindowGlass() {
    }

    /** The block's {@link GlassWindowGrid} kind: {@code FULL}, {@code PANE} with its joined sides, or 0. */
    public static int kind(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof IronBarsBlock) {
            if (!isGlassPane(block)) {
                return 0;
            }
            int kind = GlassWindowGrid.PANE;
            if (state.getValue(CrossCollisionBlock.NORTH)) {
                kind |= GlassWindowGrid.NORTH;
            }
            if (state.getValue(CrossCollisionBlock.EAST)) {
                kind |= GlassWindowGrid.EAST;
            }
            if (state.getValue(CrossCollisionBlock.SOUTH)) {
                kind |= GlassWindowGrid.SOUTH;
            }
            if (state.getValue(CrossCollisionBlock.WEST)) {
                kind |= GlassWindowGrid.WEST;
            }
            return kind;
        }
        boolean glassClass = block instanceof TransparentBlock && !(block instanceof WaterloggedTransparentBlock);
        return glassClass || state.is(BlockTags.IMPERMEABLE) ? GlassWindowGrid.FULL : 0;
    }

    public static boolean isGlass(BlockState state) {
        return kind(state) != 0;
    }

    private static boolean isGlassPane(Block block) {
        return block == Blocks.GLASS_PANE || block instanceof StainedGlassPaneBlock
            || BuiltInRegistries.BLOCK.getKey(block).getPath().contains("glass");
    }
}
