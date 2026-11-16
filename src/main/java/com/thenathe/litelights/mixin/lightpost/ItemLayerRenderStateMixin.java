package com.thenathe.litelights.mixin.lightpost;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import com.thenathe.litelights.render.feature.lightpost.OutlineMarkers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An outlined item layer also submits its outline marker, and with "Hide shimmer on outlined items"
 * on it is submitted without its glint. Plain item models submit their quads as markers; special
 * models (shields, tridents) submit themselves a second time into a collector that turns them into
 * markers.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
abstract class ItemLayerRenderStateMixin {
    @WrapOperation(method = "submit",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILnet/minecraft/client/resources/model/geometry/ItemQuads;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V"))
    private void litelights$markItem(SubmitNodeCollector collector, PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                                     int overlayCoords, int outlineColor, int[] tintLayers, ItemQuads quads,
                                     ItemStackRenderState.FoilType foilType, Operation<Void> original) {
        OutlineMarkers markers = LightPost.get().markers();
        if (!markers.outlinesItem(foilType != ItemStackRenderState.FoilType.NONE)) {
            original.call(collector, poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
            return;
        }
        ItemStackRenderState.FoilType shownFoil = markers.hidesShimmer() ? ItemStackRenderState.FoilType.NONE : foilType;
        original.call(collector, poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, shownFoil);
        try {
            markers.submitItemMarkers(collector, poseStack, quads);
        } catch (RuntimeException error) {
            // A collector of another mod's that can't take the marker: no outline, the item is drawn.
            RenderFeatureStatus.failed(RenderFeature.ENCHANTMENT_OUTLINES, error);
        }
    }

    @WrapOperation(method = "submit",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/special/SpecialModelRenderer;submit(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V"))
    private void litelights$markSpecialModel(SpecialModelRenderer<Object> renderer, Object argument, PoseStack poseStack,
                                             SubmitNodeCollector collector, int lightCoords, int overlayCoords, boolean hasFoil,
                                             int outlineColor, Operation<Void> original) {
        OutlineMarkers markers = LightPost.get().markers();
        if (!markers.outlinesItem(hasFoil)) {
            original.call(renderer, argument, poseStack, collector, lightCoords, overlayCoords, hasFoil, outlineColor);
            return;
        }
        original.call(renderer, argument, poseStack, collector, lightCoords, overlayCoords, !markers.hidesShimmer(), outlineColor);
        try {
            original.call(renderer, argument, poseStack, markers.markerCollector(collector), lightCoords, overlayCoords, false, 0);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.ENCHANTMENT_OUTLINES, error);
        }
    }
}
