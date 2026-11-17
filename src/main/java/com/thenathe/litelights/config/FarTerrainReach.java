package com.thenathe.litelights.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * The shadow reach that makes the sun's shadow map and far shadows read as one set of shadows
 * over Voxy's or Distant Horizons' terrain (the Shadows page's Calculate distance).
 *
 * <p>The map's shadows thin out over the last tenth of the reach, and far shadows fade in over the
 * same band (shadow.glsl, far_terrain.fsh). Only LiteLights' own land has the map, though: the far
 * mod's terrain begins at the render distance and only ever gets far shadows, faded by that band
 * like everything else. A reach past the render distance leaves the far terrain from the render
 * distance to nine tenths of the reach with no shadows at all, a bright ring at the seam; a reach
 * inside it hands over early, on loaded land that could have had the sharp shadows. At the render
 * distance in blocks the hand-over finishes exactly where the far terrain starts. Sodium keeps
 * every section within that circle, so the band lies wholly on loaded land.
 */
public final class FarTerrainReach {
    private FarTerrainReach() {
    }

    /** Whether Voxy or Distant Horizons is installed, the only time the button shows. */
    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("voxy") || FabricLoader.getInstance().isModLoaded("distanthorizons");
    }

    /** The render distance the land is loaded to now, in chunks (a server's view distance can lower it). */
    public static int renderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance();
    }

    /** Sets Reach for the render distance in use now. The screens save it as they do any change. */
    public static void apply() {
        LiteLightsOptions.SHADOW_REACH.set(forRenderDistance(renderDistance()));
    }

    /** The reach for a render distance in chunks, within Reach's range (64 to 512 blocks). */
    public static int forRenderDistance(int chunks) {
        IntOption reach = LiteLightsOptions.SHADOW_REACH;
        int blocks = chunks * 16;
        int snapped = reach.min() + Math.round((blocks - reach.min()) / (float) reach.step()) * reach.step();
        return Math.clamp(snapped, reach.min(), reach.max());
    }
}
