package com.thenathe.litelights.render.material;

import com.thenathe.litelights.render.feature.vegetation.VegetationMaterials;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AmethystBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides which material a block's textures are. Blocks are judged by tags and type first, so
 * modded blocks that use the vanilla tags and classes are covered; sprite names are the fallback
 * for textures no block claims (water and lava come from fluid models, not block models).
 */
public final class MaterialClassifier {
    private MaterialClassifier() {
    }

    public static int forBlock(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BaseFireBlock || state.is(BlockTags.FIRE)) {
            return MaterialIds.FIRE;
        }
        if (state.is(BlockTags.LEAVES)) {
            return MaterialIds.LEAVES;
        }
        if (block instanceof VegetationBlock || state.is(BlockTags.FLOWERS) || state.is(BlockTags.CROPS)
            || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.CAVE_VINES)) {
            return VegetationMaterials.forPlant(state);
        }
        if (block instanceof IceBlock || state.is(BlockTags.ICE)) {
            return MaterialIds.ICE;
        }
        if (block instanceof TransparentBlock || block instanceof StainedGlassBlock || block instanceof StainedGlassPaneBlock
            || block instanceof HalfTransparentBlock || state.is(BlockTags.IMPERMEABLE)) {
            return MaterialIds.GLASS;
        }
        if (state.getLightEmission() >= 10) {
            return MaterialIds.GLOWING;
        }
        // Amethyst in every form (budding, clusters and buds are amethyst blocks too), and the two
        // gem blocks that read as cut crystal. Quartz looks like smooth stone, and glowstone glows.
        if (block instanceof AmethystBlock || state.is(Blocks.DIAMOND_BLOCK) || state.is(Blocks.EMERALD_BLOCK)) {
            return MaterialIds.CRYSTAL;
        }
        if (isOre(state)) {
            // Which rock the texture is drawn on is decided later, from the textures (OreRocks).
            return MaterialIds.ORE_STONE;
        }
        if (state.is(BlockTags.SNOW)) {
            return MaterialIds.SNOW;
        }
        if (state.is(BlockTags.SAND)) {
            return MaterialIds.SAND;
        }
        if (block instanceof FarmlandBlock) {
            return MaterialIds.FARMLAND;
        }
        return MaterialIds.DEFAULT;
    }

    /** Vanilla's ores by their tag, and modded ones by the Conventional {@code c:ores} tag. */
    static boolean isOre(BlockState state) {
        return state.is(BlockTags.ORES) || state.is(ConventionalBlockTags.ORES) || state.is(Blocks.NETHER_QUARTZ_ORE);
    }

    /** By sprite path alone, for sprites no block claimed and for metals, which have no tag. */
    public static int forSpriteName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.startsWith("water_")) {
            return MaterialIds.WATER;
        }
        if (name.startsWith("lava_")) {
            return MaterialIds.LAVA;
        }
        if (name.contains("fire_")) {
            return MaterialIds.FIRE;
        }
        if (isMetal(name)) {
            return MaterialIds.METAL;
        }
        // Connected-texture sprites no block model names directly, such as Continuity's connected
        // glass (continuity:optifine/ctm/default/glass/white/12): their folder says what they are.
        if (path.contains("glass")) {
            return MaterialIds.GLASS;
        }
        return MaterialIds.DEFAULT;
    }

    static boolean isMetal(String name) {
        return name.startsWith("iron_block") || name.startsWith("gold_block") || name.startsWith("netherite_block")
            || name.contains("copper") && !name.contains("ore")
            || name.startsWith("iron_bars") || name.startsWith("iron_door") || name.startsWith("iron_trapdoor")
            || name.startsWith("chain") || name.startsWith("anvil") || name.startsWith("chipped_anvil") || name.startsWith("damaged_anvil")
            || name.startsWith("lantern") || name.startsWith("soul_lantern") || name.startsWith("cauldron") || name.startsWith("hopper")
            || name.startsWith("heavy_weighted") || name.startsWith("light_weighted");
    }

    /**
     * The material of a sprite that glowing block states use, given what the other rules claimed
     * for it: it glows only if no state that doesn't glow uses it too (water and lava names still
     * win, as in {@link #combine}).
     */
    public static int withGlow(int claimed, boolean alsoUsedWithoutGlow) {
        return alsoUsedWithoutGlow ? claimed : combine(MaterialIds.GLOWING, claimed);
    }

    /**
     * Combines a block's material with the name rule: water and lava names always win, metal names
     * win over the default, and otherwise the block decides.
     */
    public static int combine(int blockMaterial, int nameMaterial) {
        if (nameMaterial == MaterialIds.WATER || nameMaterial == MaterialIds.LAVA) {
            return nameMaterial;
        }
        if (VegetationMaterials.isFoliage(blockMaterial) && VegetationMaterials.isFoliage(nameMaterial)) {
            return VegetationMaterials.combine(blockMaterial, nameMaterial);
        }
        if (blockMaterial == MaterialIds.DEFAULT) {
            return nameMaterial;
        }
        return blockMaterial;
    }
}
