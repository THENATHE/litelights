package com.thenathe.litelights.render.feature.water.splash;

import com.thenathe.litelights.LiteLightsClient;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;

/**
 * The splash, foam and mist textures (assets/litelights/textures/particle/, drawn by
 * tools/draw_water_particles.py). The particle atlas stitches every texture in that folder, so no
 * particle types are registered; the sprites are looked up by name and cached until the atlas is
 * rebuilt.
 */
public final class WaterParticleSprites {
    public enum Set {
        DROPLET("splash_droplet", 4),
        RING("splash_ring", 4),
        RIPPLE("splash_ripple", 2),
        FOAM("water_foam", 3),
        MIST("water_mist", 3),
        /** The Advanced splash's square ring: one frame per two pixels of reach, 88 x 88. */
        SQUARE_RING("splash_square", 20),
        /** The Advanced splash's rising water: one frame per height, 48 x 48, standing on the bottom row. */
        CROWN("splash_crown", 12);

        private final String name;
        private final int frames;

        Set(String name, int frames) {
            this.name = name;
            this.frames = frames;
        }
    }

    private static final Map<Set, TextureAtlasSprite[]> CACHE = new EnumMap<>(Set.class);

    private WaterParticleSprites() {
    }

    /** The frames of a set, first to last. */
    public static TextureAtlasSprite[] frames(Set set) {
        TextureAtlasSprite[] frames = CACHE.get(set);
        if (frames == null) {
            TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
            frames = new TextureAtlasSprite[set.frames];
            for (int i = 0; i < set.frames; i++) {
                frames[i] = atlas.getSprite(LiteLightsClient.id(set.name + "_" + i));
            }
            CACHE.put(set, frames);
        }
        return frames;
    }

    /** The frame for a point in a particle's life (0 at birth, 1 at the end). */
    public static TextureAtlasSprite at(TextureAtlasSprite[] frames, float progress) {
        int index = Math.min(frames.length - 1, Math.max(0, (int) (progress * frames.length)));
        return frames[index];
    }

    /** The particle atlas was rebuilt: its sprites are new objects. */
    public static void forget() {
        CACHE.clear();
    }
}
