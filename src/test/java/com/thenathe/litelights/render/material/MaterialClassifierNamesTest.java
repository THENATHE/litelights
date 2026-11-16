package com.thenathe.litelights.render.material;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The sprite-name rules for sprites no block model claims. */
class MaterialClassifierNamesTest {
    @Test
    void connectedGlassSpritesAreGlass() {
        // Continuity's connected glass: weather on glass and glint need them to stay glass.
        assertEquals(MaterialIds.GLASS, MaterialClassifier.forSpriteName("optifine/ctm/default/glass/white/12"));
        assertEquals(MaterialIds.GLASS, MaterialClassifier.forSpriteName("block/glass_pane_top"));
    }

    @Test
    void fluidAndMetalNamesStillWin() {
        assertEquals(MaterialIds.WATER, MaterialClassifier.forSpriteName("block/water_still"));
        assertEquals(MaterialIds.METAL, MaterialClassifier.forSpriteName("block/iron_block"));
        assertEquals(MaterialIds.DEFAULT, MaterialClassifier.forSpriteName("block/stone"));
    }
}
