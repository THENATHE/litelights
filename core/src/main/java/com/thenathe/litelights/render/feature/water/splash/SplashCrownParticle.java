package com.thenathe.litelights.render.feature.water.splash;

import com.thenathe.litelights.render.LiteMath;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

/**
 * The Advanced splash's rising water: a plume with two side jets that shoots up from the landing
 * point and falls back. Like the ring, its quad keeps one size and the water grows by stepping
 * through frames drawn at the blocks' pixel size. It stands upright and turns only about the
 * vertical to face the camera, so it never tips over when seen from above.
 */
final class SplashCrownParticle extends SingleQuadParticle {
    /** Half the side of the 48-pixel sprite, in blocks. */
    private static final float HALF_SIDE = 48.0f / 32.0f;

    private final TextureAtlasSprite[] frames;
    private final int peak;
    private final int riseTicks;
    private final Quaternionf upright = new Quaternionf();

    SplashCrownParticle(ClientLevel level, double x, double surfaceY, double z, int peak, int riseTicks, int fallTicks, int tint) {
        // The sprite stands on its bottom row: the quad's centre sits half its height above the water.
        super(level, x, surfaceY + HALF_SIDE - 1.0 / 16.0, z, WaterParticleSprites.frames(WaterParticleSprites.Set.CROWN)[0]);
        this.frames = WaterParticleSprites.frames(WaterParticleSprites.Set.CROWN);
        this.peak = peak;
        this.riseTicks = riseTicks;
        this.lifetime = riseTicks + fallTicks;
        this.quadSize = HALF_SIDE;
        this.hasPhysics = false;
        this.gravity = 0.0f;
        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        setColor(LiteMath.red(tint), LiteMath.green(tint), LiteMath.blue(tint));
        setAlpha(0.95f);
    }

    /** The height the water has reached, as a frame index; rises fast, falls back more slowly. */
    private int frameAt(float time) {
        float height;
        if (time < riseTicks) {
            float rise = time / riseTicks;
            height = 1.0f - (1.0f - rise) * (1.0f - rise);
        } else {
            float fall = Mth.clamp((time - riseTicks) / (float) (lifetime - riseTicks), 0.0f, 1.0f);
            height = 1.0f - fall * fall;
        }
        return Math.clamp(Math.round(height * peak), 0, frames.length - 1);
    }

    @Override
    public void tick() {
        super.tick();
        if (!removed) {
            float left = (lifetime - age) / (float) Math.max(1, lifetime - riseTicks);
            setAlpha(0.95f * Mth.clamp(left * 2.0f, 0.0f, 1.0f));
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        return HALF_SIDE;
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        if (alpha <= 0.0f) {
            return;
        }
        setSprite(frames[frameAt(age + partialTick)]);
        upright.rotationY(Mth.PI - camera.yRot() * Mth.DEG_TO_RAD);
        extractRotatedQuad(state, camera, upright, partialTick);
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
