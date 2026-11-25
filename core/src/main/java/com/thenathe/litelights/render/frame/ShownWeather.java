package com.thenathe.litelights.render.frame;

import com.thenathe.litelights.render.feature.clouds.DynamicClouds;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The rain and thunder to show, as against the server's. With the Dynamic clouds chosen they
 * meet in the middle (clouds.field.CloudWeather): the clouds close at a pace a sky can, and the
 * rain, snow, mist, sky and land darkening and rain sounds ease in and out with them, so the first
 * drops fall under a sky already darkening. With any other clouds this is the level's own rain and
 * thunder, exactly as before.
 *
 * <p>Only what is drawn and heard changes. The server's weather, and every game rule that follows
 * it, never sees these levels. Read on the client and render threads (the same thread).
 */
public final class ShownWeather {
    private ShownWeather() {
    }

    /** The rain to show, 0..1, in place of {@code level.getRainLevel(partialTicks)}. */
    public static float rain(ClientLevel level, float partialTicks) {
        DynamicClouds clouds = DynamicClouds.get();
        if (clouds.pacesWeather(level)) {
            return clouds.shownRain(partialTicks);
        }
        return level.getRainLevel(partialTicks);
    }

    /** The thunder to show, 0..1 (already times the rain, as vanilla's is), in place of {@code level.getThunderLevel}. */
    public static float thunder(ClientLevel level, float partialTicks) {
        DynamicClouds clouds = DynamicClouds.get();
        if (clouds.pacesWeather(level)) {
            return clouds.shownThunder(partialTicks);
        }
        return level.getThunderLevel(partialTicks);
    }

    /**
     * The world's rain to show, in place of {@code real}, the world's own rain level (a seasons mod's,
     * before each player's share): with the Dynamic clouds it eases in and out at the same pace as the
     * rain shown, so a sandstorm or snow gathering on leaves never runs ahead of the clouds.
     */
    public static float worldRain(ClientLevel level, float real) {
        DynamicClouds clouds = DynamicClouds.get();
        if (clouds.pacesWeather(level)) {
            return clouds.shownWorldRain(1.0f);
        }
        return real;
    }

    /** The world's thunder to show (already times the world's rain), in place of {@code real}. */
    public static float worldThunder(ClientLevel level, float real) {
        DynamicClouds clouds = DynamicClouds.get();
        if (clouds.pacesWeather(level)) {
            return clouds.shownWorldThunder(1.0f);
        }
        return real;
    }
}
