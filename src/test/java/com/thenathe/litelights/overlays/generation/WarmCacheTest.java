package com.thenathe.litelights.overlays.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.hierarchy.OverlayPlan;
import org.junit.jupiter.api.Test;

/** F9: an export reuses the live build's tiles without replacing them, so the next reload stays warm. */
class WarmCacheTest {
    @Test
    void anExportLeavesTheLiveTilesForTheNextReload() {
        OverlayConfig config = new OverlayConfig();
        live(config);
        export(config, false);
        GenerationReport reload = live(config);
        assertTrue(reload.tileSets() > 50, "a whole default hierarchy: " + reload.tileSets());
        assertEquals(reload.tileSets(), reload.reusedTileSets(), "every set of the reload after an export is reused");
    }

    /** What 0.9.10 did: the export's own sets replaced the live ones, and the next reload drew everything again. */
    @Test
    void anExportThatKeptItsTilesWouldLeaveTheReloadCold() {
        OverlayConfig config = new OverlayConfig();
        live(config);
        export(config, true);
        assertEquals(0, live(config).reusedTileSets());
    }

    private static GenerationReport live(OverlayConfig config) {
        return VanillaSources.build(config, OverlayFormat.VARIETY, 1, 0).report();
    }

    private static void export(OverlayConfig config, boolean keepTiles) {
        OverlayGenerator.Input input = VanillaSources.input(config, OverlayFormat.PLAIN);
        OverlayGenerator.generate(OverlayPlan.from(config, input.eligible()), new VanillaSources(1, 0), input, keepTiles);
    }
}
