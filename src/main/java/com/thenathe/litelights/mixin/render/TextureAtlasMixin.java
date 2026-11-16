package com.thenathe.litelights.mixin.render;

import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.feature.particles.ParticleSystem;
import com.thenathe.litelights.render.feature.water.WaterRenderer;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Atlases were stitched again (start-up, resource reload): the material texture follows the block
 * atlas layout, water reads its fluid sprites and large textures again, and the particle sprites
 * of water and the particle system have moved.
 */
@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin {
    @Inject(method = "upload", at = @At("TAIL"))
    private void litelights$atlasUploaded(SpriteLoader.Preparations preparations, CallbackInfo callback) {
        TextureAtlas atlas = (TextureAtlas) (Object) this;
        if (atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) {
            LiteLightsRenderer.get().onBlockAtlasUploaded(atlas, preparations.width(), preparations.height(), preparations.regions());
        }
        WaterRenderer.get().onAtlasUploaded(atlas, preparations.regions());
        ParticleSystem.get().onAtlasUploaded(atlas);
    }
}
