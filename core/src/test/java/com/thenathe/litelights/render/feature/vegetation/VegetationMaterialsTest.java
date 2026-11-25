package com.thenathe.litelights.render.feature.vegetation;

import com.thenathe.litelights.render.material.MaterialIds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VegetationMaterialsTest {
    // Texture v per vertex in the baker's order: top-left, bottom-left, bottom-right, top-right.
    private static final float[] TEXTURE_UPRIGHT = {0.25f, 0.5f, 0.5f, 0.25f};

    @Test
    void crossAndCropQuadsAreUpright() {
        assertTrue(VegetationMaterials.isUpright(TEXTURE_UPRIGHT, new float[] {1.0f, 0.0f, 0.0f, 1.0f}));
        assertTrue(VegetationMaterials.isUpright(TEXTURE_UPRIGHT, new float[] {0.9375f, -0.0625f, -0.0625f, 0.9375f}));
    }

    @Test
    void flatSidewaysAndUpsideDownQuadsAreNot() {
        assertFalse(VegetationMaterials.isUpright(TEXTURE_UPRIGHT, new float[] {0.0625f, 0.0625f, 0.0625f, 0.0625f}), "lily pad");
        assertFalse(VegetationMaterials.isUpright(TEXTURE_UPRIGHT, new float[] {0.0f, 1.0f, 1.0f, 0.0f}), "upside down");
        assertFalse(VegetationMaterials.isUpright(new float[] {0.25f, 0.25f, 0.5f, 0.5f}, new float[] {1.0f, 0.0f, 0.0f, 1.0f}),
            "texture turned on its side");
    }

    @Test
    void conflictingClaimsOnASprite() {
        assertEquals(MaterialIds.LEAVES, VegetationMaterials.combine(MaterialIds.PLANT, MaterialIds.LEAVES));
        assertEquals(MaterialIds.PLANT_STILL, VegetationMaterials.combine(MaterialIds.GRASS_UPPER, MaterialIds.GRASS));
        assertEquals(MaterialIds.PLANT_STILL, VegetationMaterials.combine(MaterialIds.PLANT, MaterialIds.PLANT_STILL));
        assertEquals(MaterialIds.GRASS, VegetationMaterials.combine(MaterialIds.PLANT, MaterialIds.GRASS));
        assertEquals(MaterialIds.PLANT_UPPER, VegetationMaterials.combine(MaterialIds.PLANT_UPPER, MaterialIds.PLANT_UPPER));
    }

    @Test
    void stillPlantsAreFoliageButDoNotSway() {
        assertTrue(VegetationMaterials.isFoliage(MaterialIds.PLANT_STILL));
        assertFalse(VegetationMaterials.sways(MaterialIds.PLANT_STILL));
        assertFalse(VegetationMaterials.isFoliage(MaterialIds.SAND));
    }
}
