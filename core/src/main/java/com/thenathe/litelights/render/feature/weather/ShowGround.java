package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.api.LiteLightsApi;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * What the client knows of the land under a distant fireworks show ({@link FireworksShow.Ground}):
 * the loaded chunks' own heights where the place is within them, and far terrain's land where it is
 * on and knows the place. Client thread.
 */
public final class ShowGround implements FireworksShow.Ground {
    /** Loaded columns are read this far apart over a show's footprint. */
    private static final int STEP = 32;

    private @Nullable ClientLevel level;

    /** The level to read; null reads nothing loaded. */
    public void use(@Nullable ClientLevel current) {
        level = current;
    }

    @Override
    public int highestAround(int x, int z, int radius) {
        int highest = LiteLightsApi.farTerrain().highestLand(x - radius, z - radius, x + radius, z + radius);
        ClientLevel current = level;
        if (current == null) {
            return highest;
        }
        for (int dx = -radius; dx <= radius; dx += STEP) {
            for (int dz = -radius; dz <= radius; dz += STEP) {
                int columnX = x + dx;
                int columnZ = z + dz;
                if (current.getChunkSource().hasChunk(columnX >> 4, columnZ >> 4)) {
                    highest = Math.max(highest, current.getHeight(Heightmap.Types.MOTION_BLOCKING, columnX, columnZ));
                }
            }
        }
        return highest;
    }
}
