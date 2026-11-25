package com.thenathe.litelights.render.feature.weather.snow;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;

/**
 * What the visual snow draws into chunk meshes, as {@link VisualSnow} last published it: read by
 * the mesh threads as one value, so a mesh is built from one consistent state.
 *
 * @param blocks   fake snow on blocks that can't hold snow (Snow on blocks)
 * @param drips    the snowy overlay, snow weeping down block edges (Snow over edges)
 * @param shares   the season's snow, per area and layer
 * @param measured the shares were measured from the loaded land (no Almanac answer): one area
 * @param sprite   the snow layer's texture, from its model; null before the models are baked
 */
public record SnowState(
    boolean blocks,
    boolean drips,
    SnowShares shares,
    boolean measured,
    SnowClimate climate,
    RealSnow.BetterSnow betterSnow,
    @Nullable ClientLevel level,
    @Nullable TextureAtlasSprite sprite
) {
    public static final SnowState OFF = new SnowState(false, false, SnowShares.NONE, true, SnowClimate.VANILLA,
        RealSnow.BetterSnow.ABSENT, null, null);

    /** Whether meshes get any of it: off, the wrapped models are drawn exactly as they were. */
    public boolean drawsAny() {
        return (blocks || drips) && level != null && sprite != null;
    }

    /** The area a biome's shares are read from, given the area the climate names. */
    int areaFor(int climateArea) {
        return measured ? 0 : climateArea;
    }

    /** Whether the meshes differ between this state and another for the same shares. */
    boolean sameLook(SnowState other) {
        return blocks == other.blocks && drips == other.drips && measured == other.measured
            && climate == other.climate && betterSnow.equals(other.betterSnow) && level == other.level
            && sprite == other.sprite;
    }
}
