package com.thenathe.litelights.render.feature.weather.snow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Which blocks Snow over edges weeps down (SnowHosts.takesDrips): never grass and other blocks with
 * a snowy side, snow, glass or leaves, which show snow of their own or have their own look for it
 * (the owner, far-snow-115). By the game's classes here; the tags are bound only in a world.
 */
class SnowDripsRuleTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static void none(Block... blocks) {
        for (Block block : blocks) {
            assertFalse(SnowHosts.takesDrips(block.defaultBlockState()), block + " keeps its own look");
        }
    }

    @Test
    void blocksWithASnowySideGetNone() {
        none(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM);
    }

    @Test
    void snowItselfGetsNone() {
        none(Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW);
    }

    @Test
    void glassAndPanesOfEveryKindGetNone() {
        none(Blocks.GLASS, Blocks.TINTED_GLASS, Blocks.GLASS_PANE);
        Blocks.STAINED_GLASS.forEach(SnowDripsRuleTest::none);
        Blocks.STAINED_GLASS_PANE.forEach(SnowDripsRuleTest::none);
    }

    @Test
    void leavesGetNone() {
        none(Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.CHERRY_LEAVES, Blocks.AZALEA_LEAVES);
    }

    @Test
    void otherBlocksStillWeep() {
        for (Block block : new Block[] {Blocks.STONE, Blocks.DIRT, Blocks.OAK_PLANKS, Blocks.STONE_SLAB, Blocks.OAK_STAIRS,
            Blocks.COBBLESTONE_WALL, Blocks.OAK_FENCE, Blocks.IRON_BARS, Blocks.BRICKS}) {
            assertTrue(SnowHosts.takesDrips(block.defaultBlockState()), block + " weeps");
        }
    }
}
