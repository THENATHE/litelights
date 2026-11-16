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
 * A quad lying flat on the water: a splash's foam ring, its ripples, and foam drifting away from
 * where a waterfall lands. It grows from {@code startSize} to {@code endSize} (easing out) and
 * fades as it goes; a delay keeps it hidden for its first ticks, so ripples follow one another.
 */
final class SurfaceParticle extends SingleQuadParticle {
    private final TextureAtlasSprite[] frames;
    private final float startSize;
    private final float endSize;
    private final int delay;
    private final float startAlpha;
    private final Quaternionf flat = new Quaternionf();
    /** Where {@link #getLightCoords} reads the light, kept so drawing doesn't allocate a position every frame. */
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

    SurfaceParticle(ClientLevel level, double x, double y, double z, WaterParticleSprites.Set set, float startSize, float endSize,
                    int lifetime, int delay, float startAlpha, int tint) {
        super(level, x, y, z, WaterParticleSprites.frames(set)[0]);
        this.frames = WaterParticleSprites.frames(set);
        this.startSize = startSize;
        this.endSize = endSize;
        this.delay = delay;
        this.startAlpha = startAlpha;
        this.lifetime = lifetime + delay;
        this.hasPhysics = false;
        this.gravity = 0.0f;
        this.friction = 0.92f;
        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        this.roll = random.nextFloat() * Mth.TWO_PI;
        this.oRoll = roll;
        setColor(LiteMath.red(tint), LiteMath.green(tint), LiteMath.blue(tint));
        setAlpha(0.0f);
    }

    /** Slides along the surface (foam drifting away from a waterfall). */
    SurfaceParticle drift(double dx, double dz) {
        this.xd = dx;
        this.zd = dz;
        return this;
    }

    private float progress(float partialTick) {
        return Mth.clamp((age + partialTick - delay) / (float) (lifetime - delay), 0.0f, 1.0f);
    }

    @Override
    public void tick() {
        super.tick();
        if (removed) {
            return;
        }
        float progress = progress(0.0f);
        setSprite(WaterParticleSprites.at(frames, progress));
        setAlpha(age < delay ? 0.0f : startAlpha * (float) Math.pow(1.0 - progress, 1.5));
    }

    @Override
    public float getQuadSize(float partialTick) {
        float eased = 1.0f - (1.0f - progress(partialTick)) * (1.0f - progress(partialTick));
        return Mth.lerp(eased, startSize, endSize);
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        if (alpha <= 0.0f) {
            return;
        }
        // Lying on the water: the quad's face turned to point straight up, then spun by its roll.
        flat.rotationX(-Mth.HALF_PI).rotateZ(roll);
        extractRotatedQuad(state, camera, flat, partialTick);
    }

    /**
     * Lying on the water, it is lit like the water's top face: by the block above the surface. Its
     * own block is the water, whose light can lag behind or sit a level lower, which drew the ring
     * dark against the water around it.
     */
    @Override
    protected int getLightCoords(float partialTick) {
        BlockPos above = lightPos.set(x, y + 1.0, z);
        return level.hasChunkAt(above) ? LightCoordsUtil.getLightCoords(level, above) : 0;
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
