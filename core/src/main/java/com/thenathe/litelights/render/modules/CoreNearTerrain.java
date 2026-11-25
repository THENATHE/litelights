package com.thenathe.litelights.render.modules;

import com.thenathe.litelights.api.NearTerrain;
import com.thenathe.litelights.render.compat.SodiumSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;

/**
 * Where the game's own terrain is. Whether a section is drawn comes from vanilla's
 * LevelRenderer.isSectionCompiledAndVisible, which Sodium implements for its own renderer, so the
 * answer is right with either and no Sodium class is named.
 */
final class CoreNearTerrain implements NearTerrain {
    /** The fade the check measures against: a section counts once it is that far into a fade of this length. */
    private static final long FADE_MILLIS = 750L;

    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    @Override
    public int renderDistanceChunks() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance();
    }

    @Override
    public boolean sectionDrawn(int sectionX, int sectionY, int sectionZ) {
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        probe.set((sectionX << 4) + 8, (sectionY << 4) + 8, (sectionZ << 4) + 8);
        return renderer.isSectionCompiledAndVisible(probe, FADE_MILLIS);
    }

    @Override
    public boolean sodium() {
        return SodiumSupport.isLoaded();
    }
}
