package com.thenathe.litelights.overlays.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedFoldersTest {
    @Test
    void answersOnlyForItsOwnPaths() {
        GeneratedFolders folders = GeneratedFolders.CONTINUITY;
        assertTrue(folders.holds("optifine/ctm/000_minecraft_grass_block_spill0.properties"));
        assertTrue(folders.holds("textures/overlay/minecraft/grass_block/spill0/3.png"));
        assertFalse(folders.holds("textures/block/some_litelights_texture.png"));
        assertFalse(folders.holds("shaders/core/terrain.fsh"));
        assertTrue(folders.overlaps("textures"), "listing a parent folder reaches the tiles");
        assertTrue(folders.overlaps("optifine/ctm"));
        assertFalse(folders.overlaps("models"));
        assertFalse(folders.overlaps("shaders"));
        assertFalse(GeneratedFolders.NONE.holds("optifine/ctm/a.properties"));
    }
}
