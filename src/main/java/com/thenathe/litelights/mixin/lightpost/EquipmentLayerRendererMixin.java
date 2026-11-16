package com.thenathe.litelights.mixin.lightpost;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import com.thenathe.litelights.render.feature.lightpost.OutlineMarkers;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Worn enchanted armour (and other equipment: elytra, horse and wolf armour) submits an outline
 * marker after each of its layers, cut out with that layer's texture. With "Hide shimmer on
 * outlined items" on, an outlined piece is drawn without its glint: the plain armour render type,
 * and no glint over a trimmed piece.
 */
@Mixin(EquipmentLayerRenderer.class)
abstract class EquipmentLayerRendererMixin {
    private static final String RENDER_LAYERS = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;"
        + "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V";
    private static final String SUBMIT_MODEL = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;"
        + "Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V";

    @Inject(method = RENDER_LAYERS, at = @At("HEAD"))
    private void litelights$beginArmour(EquipmentClientInfo.LayerType layerType, ResourceKey<EquipmentAsset> equipmentAssetId, Model<?> model,
                                        Object state, ItemStack itemStack, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                        @Nullable Identifier playerTextureOverride, int outlineColor, int order, CallbackInfo callback) {
        LightPost.get().markers().beginArmour(itemStack, layerType, poseStack);
    }

    @Inject(method = RENDER_LAYERS, at = @At("RETURN"))
    private void litelights$endArmour(EquipmentClientInfo.LayerType layerType, ResourceKey<EquipmentAsset> equipmentAssetId, Model<?> model,
                                      Object state, ItemStack itemStack, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                      @Nullable Identifier playerTextureOverride, int outlineColor, int order, CallbackInfo callback) {
        LightPost.get().markers().endArmour();
    }

    @WrapOperation(method = RENDER_LAYERS,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorCutoutNoCullGlint(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType litelights$glintLayer(Identifier texture, Operation<RenderType> original) {
        OutlineMarkers markers = LightPost.get().markers();
        markers.armourLayerTexture(texture);
        return markers.outlinesArmour() && markers.hidesShimmer() ? RenderTypes.armorCutoutNoCull(texture) : original.call(texture);
    }

    @WrapOperation(method = RENDER_LAYERS,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorCutoutNoCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType litelights$plainLayer(Identifier texture, Operation<RenderType> original) {
        LightPost.get().markers().armourLayerTexture(texture);
        return original.call(texture);
    }

    /** Each armour layer: submitted as vanilla does, then its marker. */
    @WrapOperation(method = RENDER_LAYERS, at = @At(value = "INVOKE", target = SUBMIT_MODEL, ordinal = 0))
    private void litelights$markLayer(OrderedSubmitNodeCollector collector, Model<?> model, Object state, PoseStack poseStack, RenderType renderType,
                                      int lightCoords, int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping, int outlineColor,
                                      Operation<Void> original) {
        original.call(collector, model, state, poseStack, renderType, lightCoords, overlayCoords, tintedColor, uvMapping, outlineColor);
        LightPost.get().markers().submitArmourMarker(collector, model, state, poseStack, lightCoords, overlayCoords);
    }

    /** The glint over a trimmed piece, left out when the shimmer is hidden. */
    @WrapOperation(method = RENDER_LAYERS, at = @At(value = "INVOKE", target = SUBMIT_MODEL, ordinal = 2))
    private void litelights$trimmedGlint(OrderedSubmitNodeCollector collector, Model<?> model, Object state, PoseStack poseStack,
                                         RenderType renderType, int lightCoords, int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping,
                                         int outlineColor, Operation<Void> original) {
        OutlineMarkers markers = LightPost.get().markers();
        if (renderType == RenderTypes.trimmedArmorGlint() && markers.outlinesArmour() && markers.hidesShimmer()) {
            return;
        }
        original.call(collector, model, state, poseStack, renderType, lightCoords, overlayCoords, tintedColor, uvMapping, outlineColor);
    }
}
