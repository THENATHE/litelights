package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.frame.CelestialLight;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.WeatherPreview;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * The settings screen's time-of-day and weather previews for vanilla's own sky colours (the
 * Vanilla sky style): rewrites the frame's sky render state as it would be at the previewed time
 * and in the previewed weather. The world's time and weather, and everything vanilla works out
 * from them, are left alone; only what this frame's sky draws changes. The curves are the
 * overworld's classic day and weather curves, written out here.
 */
public final class VanillaSkyPreview {
    private static final Vector3f SKY = new Vector3f();
    private static final Vector4f SUNRISE = new Vector4f();
    private static final Vector3f GREY = new Vector3f();

    private VanillaSkyPreview() {
    }

    public static void apply(ClientLevel level, Camera camera, SkyRenderState state) {
        RenderSettings settings = RenderSettingsAccess.current();
        boolean timePreview = settings.hasPreview();
        boolean weatherPreview = WeatherPreview.active();
        if (!timePreview && !weatherPreview || state.skybox != DimensionType.Skybox.OVERWORLD || !DimensionGate.allows(Aspect.SKY)) {
            return;
        }
        // The rain and thunder vanilla's colours already carry: the player's share of them with Almanac.
        float shownRain = level.getRainLevel(1.0f);
        float shownThunder = level.getThunderLevel(1.0f);
        float rain = WeatherPreview.rain(shownRain);
        float thunder = WeatherPreview.thunder(shownThunder);
        // Vanilla dims the sun, moon and stars by this; the previewed rain dims them the same way.
        state.rainBrightness = 1.0f - rain;

        if (!timePreview) {
            // The world's own time: vanilla's colours already carry the real weather, so only the
            // part of the previewed weather beyond it is added.
            if (state.skyColor != null) {
                state.skyColor = SKY.set(state.skyColor);
                greyForWeather(SKY, Math.max(0.0f, rain - shownRain), Math.max(0.0f, thunder - shownThunder));
            }
            return;
        }

        float sunAngle = CelestialLight.sunAngleForDayTime(settings.previewDayTime());
        state.sunAngle = sunAngle;
        state.moonAngle = sunAngle + (float) Math.PI;
        state.starAngle = sunAngle;

        float facing = (float) Math.cos(sunAngle);
        float brightness = LiteMath.clamp01(facing * 2.0f + 0.5f);
        Biome biome = level.getBiome(camera.blockPosition()).value();
        Vector3fc base = biome.getAttributes().applyModifier(EnvironmentAttributes.SKY_COLOR,
            level.dimensionType().attributes().applyModifier(EnvironmentAttributes.SKY_COLOR, EnvironmentAttributes.SKY_COLOR.defaultValue()));
        state.skyColor = SKY.set(base).mul(brightness);
        greyForWeather(SKY, rain, thunder);

        float stars = LiteMath.clamp01(1.0f - (facing * 2.0f + 0.25f));
        state.starBrightness = stars * stars * 0.5f;

        // The sunrise and sunset glow: strongest with the sun on the horizon, gone 24 degrees off it.
        if (facing >= -0.4f && facing <= 0.4f) {
            float along = facing / 0.4f * 0.5f + 0.5f;
            float alpha = 1.0f - (1.0f - (float) Math.sin(along * Math.PI)) * 0.99f;
            alpha *= alpha;
            SUNRISE.set(along * 0.3f + 0.7f, along * along * 0.7f + 0.2f, 0.2f, alpha);
        } else {
            SUNRISE.set(0.0f);
        }
        state.sunriseAndSunsetColor = SUNRISE;
    }

    /**
     * Rain turns the sky toward a grey of 60 % of its luminance, thunder further toward one of
     * 20 %, each by up to three quarters (the classic overworld weather curve).
     */
    static void greyForWeather(Vector3f colour, float rain, float thunder) {
        if (rain > 0.0f) {
            float grey = SkyFogMath.luminance(colour.x, colour.y, colour.z) * 0.6f;
            colour.lerp(GREY.set(grey), rain * 0.75f);
        }
        if (thunder > 0.0f) {
            float grey = SkyFogMath.luminance(colour.x, colour.y, colour.z) * 0.2f;
            colour.lerp(GREY.set(grey), thunder * 0.75f);
        }
    }
}
