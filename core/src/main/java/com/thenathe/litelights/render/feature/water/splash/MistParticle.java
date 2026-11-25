package com.thenathe.litelights.render.feature.water.splash;

import com.thenathe.litelights.render.LiteMath;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Spray mist where falling water lands: rises slowly, swells and thins out. */
final class MistParticle extends SingleQuadParticle {
    private final TextureAtlasSprite[] frames;
    private final float startSize;
    private final float startAlpha;

    MistParticle(ClientLevel level, double x, double y, double z, float size, float alpha, int tint) {
        super(level, x, y, z, WaterParticleSprites.frames(WaterParticleSprites.Set.MIST)[0]);
        this.frames = WaterParticleSprites.frames(WaterParticleSprites.Set.MIST);
        this.startSize = size;
        this.startAlpha = alpha;
        this.quadSize = size;
        this.lifetime = 40 + random.nextInt(30);
        this.gravity = -0.06f;
        this.friction = 0.94f;
        this.hasPhysics = false;
        this.xd = (random.nextFloat() - 0.5f) * 0.04f;
        this.yd = 0.02f + random.nextFloat() * 0.03f;
        this.zd = (random.nextFloat() - 0.5f) * 0.04f;
        this.roll = random.nextFloat() * 6.2831855f;
        this.oRoll = roll;
        setColor(LiteMath.red(tint), LiteMath.green(tint), LiteMath.blue(tint));
        setAlpha(alpha);
    }

    @Override
    public void tick() {
        super.tick();
        if (removed) {
            return;
        }
        float progress = age / (float) lifetime;
        setSprite(WaterParticleSprites.at(frames, progress));
        quadSize = startSize * (1.0f + 1.2f * progress);
        // Fades in quickly, then out slowly.
        float fadeIn = Math.min(1.0f, progress * 6.0f);
        setAlpha(startAlpha * fadeIn * (1.0f - progress));
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
