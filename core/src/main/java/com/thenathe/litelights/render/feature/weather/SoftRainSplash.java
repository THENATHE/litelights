package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.LiteLightsOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.WaterDropParticle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla's rain splash bits drawn softer under Vanilla+ and Enhanced (the user's pick from the
 * polish16 prototype). The same shapes, drawn white and tinted with the rain's colour for the
 * weather mode, a fifth smaller and in the translucent layer, instead of saturated blue opaque
 * squares. Under Vanilla+ the rain is vanilla's, which reads darker against the sky, so its splashes
 * stay more opaque; at 55 % they nearly vanished there. With Rain and snow at Vanilla, and while
 * Particle Rain draws the rain, vanilla's splashes are left as they are.
 */
public final class SoftRainSplash extends WaterDropParticle {
    /** Vanilla's rain texture (0x4667C3) 40 % of the way to white, as the glass drops under Vanilla+. */
    private static final float[] VANILLA_PLUS_COLOUR = {0.565f, 0.642f, 0.859f};
    /** LiteLights' rain, 0xC4D2EC. */
    private static final float[] ENHANCED_COLOUR = {0.769f, 0.824f, 0.925f};
    static final float VANILLA_PLUS_ALPHA = 0.8f;
    static final float ENHANCED_ALPHA = 0.55f;

    private SoftRainSplash(ClientLevel level, double x, double y, double z, TextureAtlasSprite sprite, float[] colour, float alpha) {
        super(level, x, y, z, sprite);
        setColor(colour[0], colour[1], colour[2]);
        setAlpha(alpha);
        quadSize *= 0.8f;
    }

    /** The soft splash to draw in place of vanilla's, or null to leave vanilla's as it is. */
    public static @Nullable SoftRainSplash create(ClientLevel level, double x, double y, double z, RandomSource random) {
        LiteLightsOptions.PrecipitationLook look = LiteLightsOptions.PRECIPITATION.get();
        if (look == LiteLightsOptions.PrecipitationLook.VANILLA || WeatherCompat.precipitationHandledElsewhere()) {
            return null;
        }
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
        TextureAtlasSprite sprite = atlas.getSprite(LiteLightsClient.id("rain_splash_soft_" + random.nextInt(4)));
        if (sprite == atlas.missingSprite()) {
            return null;
        }
        boolean vanillaPlus = look == LiteLightsOptions.PrecipitationLook.VANILLA_PLUS;
        return new SoftRainSplash(level, x, y, z, sprite, vanillaPlus ? VANILLA_PLUS_COLOUR : ENHANCED_COLOUR,
            vanillaPlus ? VANILLA_PLUS_ALPHA : ENHANCED_ALPHA);
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        return com.thenathe.litelights.render.feature.particles.ParticleSystem.blendedLayer();
    }
}
