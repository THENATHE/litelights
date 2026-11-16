package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import com.thenathe.litelights.render.post.LitePostChain;
import com.thenathe.litelights.render.post.PostSlot;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

/**
 * The light-post stream: lighting on surfaces (enhanced light, night tone, glint, where light rays
 * land; all in the surface hook), and the post chain's own passes (bloom, light rays, FXAA, the
 * tonemap and grade) plus the enchantment outline around items in hand.
 */
public final class LightPost {
    private static final LightPost INSTANCE = new LightPost();

    private final LightPostFrame frame = new LightPostFrame();
    private final BloomPass bloom = new BloomPass(frame);
    private final FxaaPass fxaa = new FxaaPass();
    private final TaaPass taa = new TaaPass();
    private final LightRaysPass rays = new LightRaysPass(frame, fxaa);
    private final ToneGradePass tonemap = new ToneGradePass(frame, bloom);
    private final OutlineMarkers markers = new OutlineMarkers(frame);
    private final EnchantmentOutline outline = new EnchantmentOutline(frame, markers);
    private boolean rowsChecked;

    private LightPost() {
        fxaa.setRays(rays);
    }

    public static LightPost get() {
        return INSTANCE;
    }

    /** Installs light-post's passes and listeners. Called once from the client entrypoint. */
    public void register() {
        // So a post shader that fails to compile shows on its own row.
        PipelineOwners.claim(RenderFeature.BLOOM, BloomPass.pipelines());
        PipelineOwners.claim(RenderFeature.LIGHT_RAYS, LightRaysPass.pipelines());
        PipelineOwners.claim(RenderFeature.COLOUR_GRADING, ToneGradePass.pipeline());
        PipelineOwners.claim(RenderFeature.FXAA, FxaaPass.pipeline());
        PipelineOwners.claim(RenderFeature.TAA, TaaPass.pipelines());
        PipelineOwners.claim(RenderFeature.ENCHANTMENT_OUTLINES, EnchantmentOutline.pipelines());
        LiteRenderEvents.FRAME_UPDATE.register(frame);
        LiteRenderEvents.FRAME_UPDATE.register((frameState, settings) -> checkRowsOnce());
        LitePostChain.register(PostSlot.BLOOM, bloom);
        LitePostChain.register(PostSlot.LIGHT_RAYS, rays);
        LitePostChain.register(PostSlot.ANTI_ALIASING, fxaa);
        LitePostChain.register(PostSlot.ANTI_ALIASING, taa);
        // After anti-aliasing, like the held items' glow (drawn before the hand), so it stays soft.
        LitePostChain.register(PostSlot.ANTI_ALIASING, outline::drawLevelGlow);
        LitePostChain.register(PostSlot.TONEMAP, tonemap);
    }

    /** Whether TAA wants the opaque world's depth copied before translucent surfaces this frame. */
    public boolean wantsPassBeforeTranslucents() {
        return taa.wantsOpaqueDepth();
    }

    /** After the solid world, before translucent surfaces; no render pass is open. */
    public void beforeTranslucents(RenderTarget mainTarget) {
        if (taa.wantsOpaqueDepth()) {
            taa.beforeTranslucents(mainTarget);
        }
    }

    /** The hand's draws are prepared; its pass, drawn with {@code handDepth}, comes next. */
    public void beforeHand(FeatureRenderDispatcher.PreparedFrame handFrame, GpuTextureView handDepth) {
        bloom.handDrawn(handDepth);
        outline.beforeHand(handFrame);
    }

    public void afterLevelFeaturesPrepared(FeatureRenderDispatcher.PreparedFrame levelFrame) {
        outline.afterLevelFeaturesPrepared(levelFrame);
    }

    /** The enchantment outline's markers, for the mixins that submit them. */
    public OutlineMarkers markers() {
        return markers;
    }

    /** For QA: frames in which something in the level (worn armour, items in hand) was outlined. */
    public long levelOutlineFrames() {
        return outline.levelFrames();
    }

    /** For QA: frames in which an item in the first-person hand was outlined. */
    public long handOutlineFrames() {
        return outline.handFrames();
    }

    /**
     * Takes the stream's settings rows out of "Coming soon" once their pipelines compile, so the
     * options that are off by default (FXAA, TAA, colour grading, terrain glint) can be switched on.
     * A pipeline that fails here shows its reason on its row instead.
     */
    private void checkRowsOnce() {
        if (rowsChecked) {
            return;
        }
        rowsChecked = true;
        RenderPipeline[] rayPipelines = LightRaysPass.pipelines();
        readyIf(RenderFeature.BLOOM, "bloom", BloomPass.pipelines());
        readyIf(RenderFeature.LIGHT_RAYS, "light_rays", rayPipelines);
        readyIf(RenderFeature.UNDERWATER_RAYS, "underwater_rays", rayPipelines);
        readyIf(RenderFeature.COLOUR_GRADING, "colour_grading", ToneGradePass.pipeline());
        readyIf(RenderFeature.FXAA, "fxaa", FxaaPass.pipeline());
        readyIf(RenderFeature.TAA, "taa", TaaPass.pipelines());
        readyIf(RenderFeature.ENCHANTMENT_OUTLINES, "outlines", EnchantmentOutline.pipelines());
    }

    private static void readyIf(RenderFeature feature, String settingsFeature, RenderPipeline... pipelines) {
        try {
            for (RenderPipeline pipeline : pipelines) {
                RenderSystem.getCompiledPipeline(pipeline);
            }
            RenderFeatureStatus.markReady(settingsFeature);
        } catch (RuntimeException error) {
            LiteLightsClient.LOG.error("The {} pipelines didn't compile", settingsFeature, error);
            RenderFeatureStatus.failed(feature, error);
        }
    }
}
