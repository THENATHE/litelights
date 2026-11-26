package com.thenathe.litelights.render.feature.weather.snow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Where LambdaBetterGrass's better snow is counted as drawn (RealSnow.layersAt, visual-snow-117):
 * on a block it lists, with snow layers on two sides or more, as seen in game; with one side it
 * draws none, so the visual snow draws its own there.
 */
class BetterSnowSidesTest {
    private static final BlockPos PLANT = new BlockPos(0, 1, 0);
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

    private int layers(RealSnow.BetterSnow betterSnow) {
        return RealSnow.layersAt(world, PLANT, blocks.get(PLANT), betterSnow);
    }

    @Test
    void oneSideOfSnowIsntEnoughForLambdaBetterGrass() {
        RealSnow.BetterSnow on = new RealSnow.BetterSnow(true, Set.of(Blocks.SHORT_GRASS));
        blocks.put(PLANT, Blocks.SHORT_GRASS.defaultBlockState());
        blocks.put(PLANT.east(), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2));
        assertEquals(0, layers(on), "one side: it draws none, so the visual snow draws its own");
        blocks.put(PLANT.south(), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        assertEquals(3, layers(on), "two sides: its layer");
        assertEquals(0, layers(new RealSnow.BetterSnow(false, Set.of(Blocks.SHORT_GRASS))), "its snow off");
        assertEquals(0, layers(new RealSnow.BetterSnow(true, Set.of(Blocks.FERN))), "a block it doesn't list");
    }
}
