package com.thenathe.litelights.render.feature.water.splash;

import com.thenathe.litelights.render.LiteMath;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;

/**
 * A water droplet thrown up by a splash or a waterfall: flies, falls, shrinks through its frames
 * and disappears when it drops back into water or lands.
 */
final class DropletParticle extends SingleQuadParticle {
    private final TextureAtlasSprite[] frames;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    DropletParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, float size, int tint) {
        super(level, x, y, z, WaterParticleSprites.frames(WaterParticleSprites.Set.DROPLET)[0]);
        this.frames = WaterParticleSprites.frames(WaterParticleSprites.Set.DROPLET);
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.gravity = 1.0f;
        this.friction = 0.97f;
        this.quadSize = size;
        this.lifetime = 20 + random.nextInt(12);
        setSize(0.05f, 0.05f);
        setColor(LiteMath.red(tint), LiteMath.green(tint), LiteMath.blue(tint));
    }

    @Override
    public void tick() {
        super.tick();
        if (removed) {
            return;
        }
        setSprite(WaterParticleSprites.at(frames, age / (float) lifetime));
        if (yd < 0.0 && (onGround || isBackInWater())) {
            remove();
        }
    }

    private boolean isBackInWater() {
        cursor.set(x, y, z);
        var fluid = level.getFluidState(cursor);
        return fluid.is(FluidTags.WATER) && y < cursor.getY() + fluid.getHeight(level, cursor);
    }

    @Override
    protected Layer getLayer() {
        return Layer.OPAQUE;
    }
}
