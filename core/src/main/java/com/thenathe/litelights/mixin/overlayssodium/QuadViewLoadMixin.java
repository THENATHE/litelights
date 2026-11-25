package com.thenathe.litelights.mixin.overlayssodium;

import com.thenathe.litelights.overlays.sodium.QuadFacingFix;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.model.QuadViewImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 0.9.2's {@code QuadViewImpl.load()} reads a quad back from a mesh and marks its geometry
 * valid, but leaves {@code normalFace}, the side Sodium files the quad under for block face
 * culling, as the previous quad left it. Continuity's overlays reach the chunk this way, so they
 * were filed under the block's last drawn side and vanished whenever that side was culled.
 *
 * <p>The face normal stored with the quad is valid when it is loaded (Sodium computes it before a
 * quad is written into a mesh), and the facing derived from it is exactly what Sodium computes for
 * every other quad. Only {@link QuadFacingMixinPlugin} lets this in; see {@link QuadFacingFix}.
 */
@Mixin(value = QuadViewImpl.class, remap = false)
abstract class QuadViewLoadMixin {
    @Shadow
    protected ModelQuadFacing normalFace;

    @Inject(method = "load", at = @At("TAIL"), require = 0)
    private void litelights$takeOwnFacing(CallbackInfo info) {
        if (!QuadFacingFix.running()) {
            return;
        }
        try {
            ModelQuadFacing own = ModelQuadFacing.fromPackedNormal(((QuadViewImpl) (Object) this).packedFaceNormal());
            QuadFacingFix.noteCopied(own != normalFace);
            normalFace = own;
        } catch (LinkageError | RuntimeException failure) {
            QuadFacingFix.failed(failure);
        }
    }
}
