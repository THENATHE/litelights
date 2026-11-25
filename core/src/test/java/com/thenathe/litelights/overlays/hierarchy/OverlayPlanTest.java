package com.thenathe.litelights.overlays.hierarchy;

import com.thenathe.litelights.overlays.config.BlockEntry;
import com.thenathe.litelights.overlays.config.DefaultHierarchy;
import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.generation.EdgeStyle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who spills onto whom with the default hierarchy, and masonry's own rules (0.1.7). */
class OverlayPlanTest {
    private static final String STONE = "minecraft:stone";
    private static final String STONE_BRICKS = "minecraft:stone_bricks";
    private static final String BRICKS = "minecraft:bricks";
    private static final String SMOOTH_STONE = "minecraft:smooth_stone";

    @Test
    void brickMasonryMergesBothWaysAndSpillsOntoPlainMasonryWhereverItIsListed() {
        OverlayPlan plan = OverlayPlan.from(new OverlayConfig(), eligible());
        OverlayPlan.Source stoneBricks = source(plan, STONE_BRICKS);
        OverlayPlan.Source bricks = source(plan, BRICKS);

        assertEquals(EdgeStyle.BRICKS, stoneBricks.style());
        assertTrue(stoneBricks.blendWith().contains(BRICKS) && bricks.blendWith().contains(STONE_BRICKS));
        assertTrue(stoneBricks.blendWith().contains("minecraft:deepslate_tiles"));
        assertFalse(stoneBricks.spillOnto().contains(BRICKS));
        // Smooth stone is listed before stone bricks, yet still receives from them.
        assertTrue(stoneBricks.spillOnto().contains(SMOOTH_STONE));
        assertTrue(stoneBricks.spillOnto().contains("minecraft:polished_andesite"));
        // Masonry is the hardest group: it never spills onto soil, sand or plants.
        assertFalse(stoneBricks.spillOnto().contains("minecraft:dirt"));
        assertFalse(stoneBricks.spillOnto().contains("minecraft:grass_block"));
        assertFalse(stoneBricks.spillOnto().contains("minecraft:sand"));
        assertNull(find(plan, SMOOTH_STONE), "plain masonry receives only");
    }

    /** Where brick masonry meets rock, the bricks merge into the rock; soil and plants still spill onto bricks. */
    @Test
    void brickMasonryMergesIntoRock() {
        OverlayPlan plan = OverlayPlan.from(new OverlayConfig(), eligible());

        assertTrue(source(plan, STONE_BRICKS).spillOnto().contains(STONE));
        assertTrue(source(plan, "minecraft:polished_blackstone_bricks").spillOnto().contains("minecraft:blackstone"));
        assertTrue(source(plan, "minecraft:nether_bricks").spillOnto().contains("minecraft:netherrack"));
        assertTrue(source(plan, STONE_BRICKS).spillOnto().contains("minecraft:terracotta"));
        assertFalse(source(plan, STONE).spillOnto().contains(STONE_BRICKS), "rock no longer spills over bricks");
        assertTrue(source(plan, STONE).spillOnto().contains(SMOOTH_STONE), "rock still spills onto plain masonry");
        assertTrue(source(plan, "minecraft:grass_block").spillOnto().contains(STONE_BRICKS));
        assertTrue(source(plan, "minecraft:sand").spillOnto().contains(BRICKS));
    }

    @Test
    void thePlayersChoicesStillDecide() {
        OverlayConfig config = new OverlayConfig();
        config.find(STONE_BRICKS).style = BlockEntry.RECEIVE_ONLY;
        config.find(BRICKS).style = "jagged";
        OverlayPlan plan = OverlayPlan.from(config, eligible());

        assertNull(find(plan, STONE_BRICKS));
        assertTrue(source(plan, "minecraft:deepslate_bricks").spillOnto().contains(STONE_BRICKS));
        assertTrue(source(plan, STONE).spillOnto().contains(STONE_BRICKS), "receive-only stone bricks take rock again");
        // Jagged bricks follow the ordinary rules: no blend with the Bricks blocks, and rock spills onto them.
        assertFalse(source(plan, "minecraft:deepslate_bricks").blendWith().contains(BRICKS));
        assertTrue(source(plan, STONE).spillOnto().contains(BRICKS));
        assertFalse(source(plan, BRICKS).spillOnto().contains(STONE));

        config.forceFallbackStyle = true;
        OverlayPlan forced = OverlayPlan.from(config, eligible());
        assertEquals(EdgeStyle.ORGANIC, source(forced, "minecraft:deepslate_bricks").style());
        assertFalse(source(forced, "minecraft:deepslate_bricks").spillOnto().contains(STONE));
    }

    @Test
    void aGroupRenamedAwayFromRockStopsTakingBricks() {
        OverlayConfig config = new OverlayConfig();
        config.groups.stream().filter(group -> group.name.equals("Stone")).forEach(group -> group.name = "My rock");
        OverlayPlan plan = OverlayPlan.from(config, eligible());
        assertFalse(source(plan, STONE_BRICKS).spillOnto().contains(STONE));
        assertTrue(source(plan, STONE).spillOnto().contains(STONE_BRICKS));
        assertTrue(source(plan, STONE_BRICKS).spillOnto().contains("minecraft:deepslate"), "Deep rock is still rock");
    }

    private static EligibleBlocks eligible() {
        List<String> ids = DefaultHierarchy.blockIds();
        List<String> spillable = new ArrayList<>(ids);
        spillable.remove("minecraft:mud");
        return EligibleBlocks.of(ids, spillable);
    }

    private static OverlayPlan.Source find(OverlayPlan plan, String blockId) {
        return plan.sources().stream().filter(source -> source.blockId().equals(blockId)).findFirst().orElse(null);
    }

    private static OverlayPlan.Source source(OverlayPlan plan, String blockId) {
        OverlayPlan.Source source = find(plan, blockId);
        assertTrue(source != null, blockId + " isn't a source");
        return source;
    }
}
