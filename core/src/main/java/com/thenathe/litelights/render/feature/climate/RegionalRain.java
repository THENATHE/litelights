package com.thenathe.litelights.render.feature.climate;

import com.thenathe.litelights.render.feature.clouds.DynamicClouds;
import com.thenathe.litelights.render.feature.skyfog.LiteClouds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The rain and thunder falling past the camera under Almanac's regional weather, up to the top of
 * the cloud drawn over it ({@link WeatherCeiling}). Almanac's own levels at the camera stop at its
 * storm's top; these are Almanac's from just under that top while the camera is over it, eased at
 * vanilla's pace as Almanac's are, so the rain reaches the cloud wherever the Cloud height setting
 * puts it. Render.frame.ShownWeather and the Dynamic clouds take them in place of the level's.
 *
 * <p>With no clouds drawn (Clouds off) they stand aside and the level's own, Almanac's rule, are
 * shown as before. Client thread only.
 */
public final class RegionalRain {
    /**
     * How often the storms over the camera are asked for, in client ticks: a storm's top only
     * changes as another storm comes over, and the ask goes through Almanac's storm list.
     */
    private static final int TOP_EVERY = 10;

    private static @Nullable ClientLevel level;
    private static float stormTop = Float.NaN;
    private static int topDue;
    private static float rain;
    private static float previousRain;
    private static float thunder;
    private static float previousThunder;

    private RegionalRain() {
    }

    /** Whether these levels stand in for the level's own at the camera now. */
    public static boolean shows(@Nullable ClientLevel current) {
        return current != null && current == level;
    }

    public static float rain(float partialTicks) {
        return previousRain + (rain - previousRain) * partialTicks;
    }

    public static float thunder(float partialTicks) {
        return previousThunder + (thunder - previousThunder) * partialTicks;
    }

    /** Once a client tick, before the clouds' tick (LocalClimate is registered first). */
    static void tick(Minecraft client) {
        ClientLevel current = client.level;
        if (current == null || !LocalClimate.fromAlmanac() || !AlmanacOverhead.regional(current)) {
            forget();
            return;
        }
        float drawnTop = drawnRainCloudTop(current);
        if (Float.isNaN(drawnTop)) {
            forget();
            return;
        }
        Vec3 camera = client.gameRenderer.mainCamera().position();
        if (current != level || --topDue <= 0) {
            stormTop = AlmanacOverhead.stormTop(current, camera);
            topDue = TOP_EVERY;
        }
        AlmanacOverhead.Falling falling = AlmanacOverhead.falling(current, camera, stormTop);
        float ceiling = WeatherCeiling.of(stormTop, drawnTop);
        float rainTarget = WeatherCeiling.target(falling.rain(), camera.y, ceiling);
        float thunderTarget = WeatherCeiling.target(falling.thunder(), camera.y, ceiling);
        if (current != level) {
            // Joining, or the clouds coming back on: start where the weather is, as vanilla does on joining.
            level = current;
            rain = rainTarget;
            thunder = thunderTarget;
        }
        previousRain = rain;
        previousThunder = thunder;
        rain = WeatherCeiling.ease(rain, rainTarget);
        thunder = WeatherCeiling.ease(thunder, thunderTarget);
    }

    static void forget() {
        level = null;
    }

    /**
     * The top of the cloud drawn over the camera that the rain falls from, in world y: the Dynamic
     * clouds' rain cloud while they pace the weather, else LiteLights' Fancy or Flat layer. Negative
     * infinity where clouds are drawn but none is over the camera; NaN with no clouds drawn.
     */
    private static float drawnRainCloudTop(ClientLevel current) {
        DynamicClouds clouds = DynamicClouds.get();
        if (clouds.pacesWeather(current)) {
            return clouds.rainCloudCeiling();
        }
        return LiteClouds.get().rainLayerTop();
    }
}
