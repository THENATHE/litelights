package com.thenathe.litelights.render.feature.water.splash;

import com.thenathe.litelights.render.LiteMath;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

/**
 * The Advanced splash's square ring, lying on the water around the exact landing point. Its quad
 * never changes size: the ring spreads by stepping through frames drawn at one pixel per sixteenth
 * of a block, so it keeps the blocks' pixel size as it grows. Square to the world's axes, like the
 * blocks around it, and fading as it goes.
 */
final class SquareRingParticle extends SingleQuadParticle {
    /** Half the side of the 88-pixel sprite, in blocks. */
    private static final float HALF_SIDE = 88.0f / 32.0f;
    private static final float FIRST_REACH_PIXELS = 3.0f;
    private static final float PIXELS_PER_FRAME = 2.0f;

    private final TextureAtlasSprite[] frames;
    private final float reachPixels;
    private final int delay;
    private final float startAlpha;
    private final Quaternionf flat = new Quaternionf().rotationX(-Mth.HALF_PI);

    SquareRingParticle(ClientLevel level, double x, double y, double z, float reachBlocks, int lifetime, int delay,
                       float startAlpha, int tint) {
        super(level, x, y, z, WaterParticleSprites.frames(WaterParticleSprites.Set.SQUARE_RING)[0]);
        this.frames = WaterParticleSprites.frames(WaterParticleSprites.Set.SQUARE_RING);
        this.reachPixels = reachBlocks * 16.0f;
        this.delay = delay;
        this.startAlpha = startAlpha;
        this.lifetime = lifetime + delay;
        this.quadSize = HALF_SIDE;
        this.hasPhysics = false;
        this.gravity = 0.0f;
        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        setColor(LiteMath.red(tint), LiteMath.green(tint), LiteMath.blue(tint));
        setAlpha(0.0f);
    }

    private float progress(float partialTick) {
        return Mth.clamp((age + partialTick - delay) / (float) (lifetime - delay), 0.0f, 1.0f);
    }

    @Override
    public void tick() {
        super.tick();
        if (!removed) {
            float progress = progress(0.0f);
            setAlpha(age < delay ? 0.0f : startAlpha * (float) Math.pow(1.0 - progress, 1.3));
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
        // Spreads quickly at first and slows down, as a ring on water does.
        float progress = progress(partialTick);
        float eased = 1.0f - (1.0f - progress) * (1.0f - progress);
        float reach = Mth.lerp(eased, FIRST_REACH_PIXELS, reachPixels);
        int frame = Math.clamp(Math.round((reach - FIRST_REACH_PIXELS) / PIXELS_PER_FRAME), 0, frames.length - 1);
        setSprite(frames[frame]);
        extractRotatedQuad(state, camera, flat, partialTick);
    }

    /**
     * Lying on the water, it is lit like the water's top face: by the block above the surface. Its
     * own block is the water, whose light can lag behind or sit a level lower, which drew the ring
     * dark against the water around it.
     */
    @Override
    protected int getLightCoords(float partialTick) {
        BlockPos above = BlockPos.containing(x, y + 1.0, z);
        return level.hasChunkAt(above) ? LightCoordsUtil.getLightCoords(level, above) : 0;
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
