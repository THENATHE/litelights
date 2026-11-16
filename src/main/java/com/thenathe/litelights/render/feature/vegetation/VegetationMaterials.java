package com.thenathe.litelights.render.feature.vegetation;

import com.thenathe.litelights.render.material.MaterialIds;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DryVegetationBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherFungusBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.joml.Vector3fc;

/**
 * Splits the plant material into what the sway shader needs to know about a sprite: which switch
 * moves it (grass or plants), whether it is the upper half of a two-block plant (its foot moves
 * with the lower half's top), or whether it must stay still.
 *
 * <p>The shader only sees one vertex at a time, so a plant can sway only if its model is made of
 * upright quads with the texture's top edge at the top: then the texture corner of a vertex says
 * whether it is a foot or a tip. Flat plants (lily pads, flower beds, leaf litter), bush-shaped ones
 * with a lid (azaleas) and hanging ones (cave vines, hanging roots, hanging propagules) stay still.
 */
public final class VegetationMaterials {
    /** How much higher a quad's top edge must be than its bottom edge to count as upright, in blocks. */
    private static final float UPRIGHT_MARGIN = 0.01f;

    private VegetationMaterials() {
    }

    /** The material for a block that core-render's classifier calls a plant. */
    public static int forPlant(BlockState state) {
        Block block = state.getBlock();
        if (staysStill(state, block)) {
            return MaterialIds.PLANT_STILL;
        }
        boolean upperHalf = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        if (isGrass(block)) {
            return upperHalf ? MaterialIds.GRASS_UPPER : MaterialIds.GRASS;
        }
        return upperHalf ? MaterialIds.PLANT_UPPER : MaterialIds.PLANT;
    }

    /**
     * Checks a swaying plant's model: if any quad isn't upright with its texture the right way up,
     * the shader can't tell its feet from its tips, so the plant stays still.
     */
    public static int checkModel(int material, List<BlockStateModelPart> parts) {
        if (!sways(material)) {
            return material;
        }
        for (BlockStateModelPart part : parts) {
            for (Direction direction : Direction.values()) {
                if (!allUpright(part.getQuads(direction))) {
                    return MaterialIds.PLANT_STILL;
                }
            }
            if (!allUpright(part.getQuads(null))) {
                return MaterialIds.PLANT_STILL;
            }
        }
        return material;
    }

    /**
     * Two blocks' claims on one sprite, when both are foliage. Leaves win over plants (leaf motion
     * has no feet to keep still). Plant claims that disagree about where the feet are can't both be
     * right, so the sprite stays still; ones that only disagree about the switch keep the first.
     */
    public static int combine(int claim, int current) {
        if (claim == current) {
            return claim;
        }
        if (claim == MaterialIds.LEAVES || current == MaterialIds.LEAVES) {
            return MaterialIds.LEAVES;
        }
        if (claim == MaterialIds.PLANT_STILL || current == MaterialIds.PLANT_STILL || isUpperHalf(claim) != isUpperHalf(current)) {
            return MaterialIds.PLANT_STILL;
        }
        return current;
    }

    public static boolean isFoliage(int material) {
        return material == MaterialIds.LEAVES || material == MaterialIds.PLANT
            || (material >= MaterialIds.GRASS && material <= MaterialIds.PLANT_STILL);
    }

    static boolean sways(int material) {
        return material == MaterialIds.PLANT || material == MaterialIds.PLANT_UPPER
            || material == MaterialIds.GRASS || material == MaterialIds.GRASS_UPPER;
    }

    private static boolean isUpperHalf(int material) {
        return material == MaterialIds.GRASS_UPPER || material == MaterialIds.PLANT_UPPER;
    }

    private static boolean staysStill(BlockState state, Block block) {
        if (state.is(BlockTags.CAVE_VINES) || block instanceof HangingRootsBlock
            || state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING)) {
            return true;
        }
        // Mushrooms and fungi are stiff.
        return block instanceof MushroomBlock || block instanceof NetherFungusBlock;
    }

    /**
     * Grass, ferns and their dry and underwater kin. Modded grass usually extends one of these
     * classes; otherwise its name decides.
     */
    private static boolean isGrass(Block block) {
        if (block instanceof TallGrassBlock || block instanceof DryVegetationBlock || block instanceof BushBlock
            || block instanceof SeagrassBlock || block instanceof TallSeagrassBlock
            || block == Blocks.TALL_GRASS || block == Blocks.LARGE_FERN) {
            return true;
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath().toLowerCase(Locale.ROOT);
        return path.contains("grass") || path.contains("fern");
    }

    private static boolean allUpright(List<BakedQuad> quads) {
        for (int i = 0, count = quads.size(); i < count; i++) {
            if (!isUpright(quads.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A quad is upright when both vertices on the texture's top edge are higher than both on its
     * bottom edge, and they are its first and last vertices, which is the order the model baker
     * gives every unrotated face (vanilla's terrain shader tells the corners apart by that order).
     * Horizontal quads, and quads with the texture on its side or upside down, are not upright.
     */
    static boolean isUpright(BakedQuad quad) {
        float[] v = new float[4];
        float[] y = new float[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            v[vertex] = UVPair.unpackV(quad.packedUV(vertex));
            Vector3fc position = quad.position(vertex);
            y[vertex] = position.y();
        }
        return isUpright(v, y);
    }

    /** The test on plain numbers: texture v (smaller is higher in the texture) and height per vertex. */
    static boolean isUpright(float[] v, float[] y) {
        float middleV = (v[0] + v[1] + v[2] + v[3]) * 0.25f;
        boolean topEdgeFirstAndLast = v[0] < middleV && v[3] < middleV && v[1] >= middleV && v[2] >= middleV;
        float lowestTop = Math.min(y[0], y[3]);
        float highestBottom = Math.max(y[1], y[2]);
        return topEdgeFirstAndLast && lowestTop > highestBottom + UPRIGHT_MARGIN;
    }
}
