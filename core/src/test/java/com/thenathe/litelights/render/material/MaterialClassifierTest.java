package com.thenathe.litelights.render.material;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MaterialClassifierTest {
    @Test
    void spriteOnlyGlowingStatesUseGlows() {
        assertEquals(MaterialIds.GLOWING, MaterialClassifier.withGlow(MaterialIds.DEFAULT, false));
        // A lantern's sprite is metal by name, but every state using it glows.
        assertEquals(MaterialIds.GLOWING, MaterialClassifier.withGlow(MaterialIds.METAL, false));
    }

    @Test
    void spriteSharedWithStatesThatDontGlowKeepsTheirMaterial() {
        // The beacon uses the glass sprite; glass itself doesn't glow.
        assertEquals(MaterialIds.GLASS, MaterialClassifier.withGlow(MaterialIds.GLASS, true));
        // A lit furnace shares its sides with the unlit one, which claims nothing.
        assertEquals(MaterialIds.DEFAULT, MaterialClassifier.withGlow(MaterialIds.DEFAULT, true));
    }

    @Test
    void lavaNamesStillWin() {
        assertEquals(MaterialIds.LAVA, MaterialClassifier.withGlow(MaterialIds.LAVA, false));
    }
}
