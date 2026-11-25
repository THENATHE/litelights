package com.thenathe.litelights.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.thenathe.litelights.render.LiteLightsRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's round shadow under entities. While LiteLights draws Shadows with Creature shadows, the
 * mob map already gives every entity a real shadow, so vanilla's "Entity Shadows" option reads as
 * off for this check: the render state gets no shadow pieces, exactly as with the option off, and
 * vanilla's saved option is never changed. Outside that (either switch off, or no sun to cast them)
 * the option is read as it is.
 */
@Mixin(EntityRenderer.class)
abstract class EntityShadowMixin {
    @ModifyExpressionValue(method = "extractShadow",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
    private Object litelights$hideRoundShadow(Object entityShadows) {
        return LiteLightsRenderer.get().drawsCreatureShadows() ? Boolean.FALSE : entityShadows;
    }
}
