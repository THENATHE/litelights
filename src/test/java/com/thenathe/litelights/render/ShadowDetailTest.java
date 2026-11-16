package com.thenathe.litelights.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.thenathe.litelights.config.LiteLightsOptions;
import com.thenathe.litelights.render.RenderSettings.ShadowDetail;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ShadowDetailTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    void mapSizesRunFromFastestToUltra() {
        assertEquals(1024, ShadowDetail.FASTEST.mapSize());
        assertEquals(2048, ShadowDetail.FAST.mapSize());
        assertEquals(3072, ShadowDetail.SHARP.mapSize());
        assertEquals(4096, ShadowDetail.SHARPEST.mapSize());
        assertEquals(8192, ShadowDetail.ULTRA.mapSize());
    }

    @Test
    void videoMemoryMatchesTheDescription() {
        // Two 4-byte depth textures for the sun map, one for the mob map.
        assertEquals(12 * MIB, ShadowDetail.FASTEST.videoMemoryBytes());
        assertEquals(144 * MIB, ShadowDetail.SHARPEST.videoMemoryBytes());
        assertEquals(528 * MIB, ShadowDetail.ULTRA.videoMemoryBytes());
        assertEquals(512 * MIB, ShadowDetail.ULTRA.videoMemoryBytes() - 16 * MIB);
    }

    @Test
    void mobMapIsSharperFromSharpestUp() {
        assertEquals(1024, ShadowDetail.SHARP.mobMapSize());
        assertEquals(2048, ShadowDetail.SHARPEST.mobMapSize());
        assertEquals(2048, ShadowDetail.ULTRA.mobMapSize());
    }

    @Test
    void fallbackDropsToTheLargestTierThatFits() {
        assertEquals(ShadowDetail.ULTRA, ShadowDetail.ULTRA.fitting(16384));
        assertEquals(ShadowDetail.SHARPEST, ShadowDetail.ULTRA.fitting(8191));
        assertEquals(ShadowDetail.SHARPEST, ShadowDetail.ULTRA.fitting(4096));
        assertEquals(ShadowDetail.FAST, ShadowDetail.SHARPEST.fitting(2048));
        assertEquals(ShadowDetail.FASTEST, ShadowDetail.SHARPEST.fitting(512));
        assertEquals(ShadowDetail.SHARP, ShadowDetail.ULTRA.lower().lower());
        assertNull(ShadowDetail.FASTEST.lower());
    }

    @Test
    void settingsChoicesMatchTheRendererTiers() {
        assertEquals(
            Arrays.stream(LiteLightsOptions.ShadowDetail.values()).map(Enum::name).toList(),
            Arrays.stream(ShadowDetail.values()).map(Enum::name).toList());
        assertEquals(
            Arrays.stream(LiteLightsOptions.ShadowOffset.values()).map(Enum::name).toList(),
            Arrays.stream(RenderSettings.ShadowOffset.values()).map(Enum::name).toList());
        assertEquals(1.5f, RenderSettings.ShadowOffset.BALANCED.texels());
        assertEquals(ShadowDetail.SHARPEST, RenderSettings.Shadows.DEFAULTS.detail());
    }
}
