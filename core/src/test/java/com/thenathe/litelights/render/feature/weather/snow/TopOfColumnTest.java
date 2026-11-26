package com.thenathe.litelights.render.feature.weather.snow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The visual snow's fake snow only on the top of a column (FakeSnow.topOfColumn, far-snow-115):
 * in a stack only the top block, nothing under a block that stops snow, and a door or a tall
 * plant counted as the one block it is.
 */
class TopOfColumnTest {
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final BlockGetter world = (BlockGetter) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {BlockGetter.class},
        (proxy, method, args) -> switch (method.getName()) {
            case "getBlockState" -> blocks.getOrDefault((BlockPos) args[0], Blocks.AIR.defaultBlockState());
            case "getFluidState" -> blocks.getOrDefault((BlockPos) args[0], Blocks.AIR.defaultBlockState()).getFluidState();
            case "getBlockEntity" -> null;
            case "getHeight" -> 384;
            case "getMinY" -> -64;
            default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
        });

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private BlockState put(int y, BlockState state) {
        blocks.put(new BlockPos(0, y, 0), state);
        return state;
    }

    private boolean top(int y) {
        BlockPos pos = new BlockPos(0, y, 0);
        return FakeSnow.topOfColumn(world, pos, world.getBlockState(pos));
    }

    @Test
    void inAStackOfFencesOnlyTheTopOneTakesSnow() {
        for (int y = 1; y <= 3; y++) {
            put(y, Blocks.OAK_FENCE.defaultBlockState());
        }
        assertFalse(top(1));
        assertFalse(top(2));
        assertTrue(top(3));
    }

    @Test
    void aWallUnderAFenceAndASlabUnderAStairTakeNone() {
        put(1, Blocks.COBBLESTONE_WALL.defaultBlockState());
        put(2, Blocks.OAK_FENCE.defaultBlockState());
        put(40, Blocks.STONE_SLAB.defaultBlockState());
        put(41, Blocks.OAK_STAIRS.defaultBlockState());
        assertFalse(top(1));
        assertTrue(top(2), "the fence on top does");
        assertFalse(top(40));
        assertTrue(top(41));
    }

    @Test
    void aBlockTwoAboveOrACanopyHigherUpKeepsTheSnowOff() {
        put(1, Blocks.OAK_FENCE.defaultBlockState());
        put(3, Blocks.STONE.defaultBlockState());
        assertFalse(top(1));
        put(10, Blocks.OAK_FENCE.defaultBlockState());
        put(14, Blocks.SPRUCE_LEAVES.defaultBlockState());
        assertFalse(top(10), "under a tree");
    }

    @Test
    void aTorchOnTopDoesntStopIt() {
        put(1, Blocks.OAK_FENCE.defaultBlockState());
        put(2, Blocks.TORCH.defaultBlockState());
        assertTrue(top(1));
        assertTrue(top(2));
    }

    @Test
    void aDoorOrATallPlantIsOneBlock() {
        put(1, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        put(2, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        assertTrue(top(1));
        put(40, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        put(41, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
        assertTrue(top(40));
    }

    private boolean ground(int y) {
        BlockPos pos = new BlockPos(0, y, 0);
        return FakeSnow.groundOpen(world, pos, world.getBlockState(pos));
    }

    @Test
    void snowReachesTheGroundAtTheFootOfAStackOfThinBlocks() {
        for (int y = 1; y <= 4; y++) {
            put(y, Blocks.OAK_FENCE.defaultBlockState());
        }
        assertFalse(top(1), "the bottom post's own surfaces take none");
        assertTrue(ground(1), "the ground at its foot does");
        put(20, Blocks.COBBLESTONE_WALL.defaultBlockState());
        put(21, Blocks.GLASS_PANE.defaultBlockState());
        put(22, Blocks.LANTERN.defaultBlockState());
        assertTrue(ground(20), "a wall under a pane under a lantern");
        put(40, Blocks.OAK_FENCE.defaultBlockState());
        put(41, Blocks.STONE_SLAB.defaultBlockState());
        assertFalse(ground(40), "a slab over it keeps the snow off");
    }
}
