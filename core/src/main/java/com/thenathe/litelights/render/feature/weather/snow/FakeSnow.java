package com.thenathe.litelights.render.feature.weather.snow;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

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
 */
public final class FakeSnow {
    private static final int FULL_SKY = 15;

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
        return layers(snow, world, biome, pos, pos, record);
    }

    /** Layers of fake snow on the upward surfaces of a block real snow can't lie on, or 0. */
    public static int onTop(SnowState snow, BlockAndLightGetter world, Holder<Biome> biome, BlockPos pos, boolean record) {
        BlockPos above = pos.above();
        BlockState over = world.getBlockState(above);
        if (!over.getFluidState().isEmpty() || RealSnow.vanillaLayers(over) > 0 || over.isSolidRender()) {
            return 0;
        }
        return layers(snow, world, biome, pos, above, record);
    }
}
