package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPipelines;
import com.thenathe.litelights.render.post.PostTexture;
import com.thenathe.litelights.render.post.PostTimings;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * A glowing contour around enchanted items in a hand (first person, third person, other players'
 * and mobs' hands) and around enchanted armour being worn. What gets one is decided as things are
 * submitted ({@link OutlineMarkers}); here the markers are drawn into a mask and the glow is made
 * from it.
 *
 * <ul>
 *   <li><b>First person:</b> just before the hand is drawn, the hand's prepared draws are replayed
 *   through a {@link MarkerMaskPass} into a half-resolution silhouette. The silhouette is blurred
 *   and screen blended over the level in the outline colour; the hand pass then draws the item on
 *   top, so only the glow around it shows. Its size is what the Size and Diffusion settings
 *   describe.</li>
 *   <li><b>In the level:</b> once the level's entity draws are prepared, they are replayed the same
 *   way into a half-resolution silhouette in the level's own depth, each piece in its own colour
 *   (the glow colour, or a trimmed piece's trim colour). After the level is drawn (a world-stage
 *   post pass), the parts the level shows within reach are blurred, each by the first-person blur
 *   scaled to how big it shows on screen ({@link OutlineScale}), and blended in around the item,
 *   leaving its own pixels out.</li>
 * </ul>
 * Nothing runs unless a marker was submitted. Diffusion shapes both: the blur's reach and how the
 * edge falls off (see outline_composite.fsh).
 */
public final class EnchantmentOutline {
    private static final RenderPipeline MASK_BLUR = PostPipelines.fullScreen("outline_mask_blur", "outline_mask_blur",
        PostPipelines.samplers("MaskSampler"), PostPipelines.HALF_FLOAT, 16).build();
    private static final RenderPipeline COMPOSITE = PostPipelines.fullScreen("outline_composite", "outline_composite",
        PostPipelines.samplers("GlowSampler"), PostPipelines.MAIN_TARGET_SCREEN, 32).build();
    private static final RenderPipeline LEVEL_PREPARE = PostPipelines.fullScreen("outline_level_prepare", "outline_level_prepare",
        PostPipelines.samplers("MaskSampler", "SceneDepthSampler"), PostPipelines.HALF_FLOAT, 32).build();
    private static final RenderPipeline LEVEL_SPREAD_ACROSS = PostPipelines.fullScreen("outline_level_spread_across", "outline_level_spread",
            PostPipelines.samplers("InputSampler"), PostPipelines.HALF_FLOAT, 16)
        .withShaderDefine("LITE_OUTLINE_ACROSS").build();
    private static final RenderPipeline LEVEL_SPREAD_DOWN = PostPipelines.fullScreen("outline_level_spread_down", "outline_level_spread",
        PostPipelines.samplers("InputSampler"), PostPipelines.HALF_FLOAT, 16).build();
    private static final RenderPipeline LEVEL_COMPOSITE = PostPipelines.fullScreen("outline_level_composite", "outline_composite",
            PostPipelines.samplers("GlowSampler", "ColourSampler", "MaskSampler", "SceneDepthSampler"), PostPipelines.MAIN_TARGET_SCREEN, 32)
        .withShaderDefine("LITE_OUTLINE_CUT_INTERIOR").build();

    private static final Optional<Vector4fc> CLEAR_COLOUR = Optional.of(new Vector4f(0.0f));
    /** The level blur's widest reach, in mask pixels each way (outline_level_spread.fsh's loop bound). */
    private static final int MAX_TAPS = 64;
    /** The blur reaches this many sigmas out, as the first-person blur does. */
    private static final float TAPS_PER_SIGMA = 2.5f;

    private final LightPostFrame lightPost;
    private final OutlineMarkers markers;
    private final MarkerMaskPass maskPass = new MarkerMaskPass();
    private final PostTexture handMask = new PostTexture("enchantment outline hand mask", GpuFormat.D32_FLOAT, 2);
    private final PostTexture handMaskColour = new PostTexture("enchantment outline hand mask colour", GpuFormat.RGBA8_UNORM, 2);
    private final PostTexture levelMask = new PostTexture("enchantment outline level mask", GpuFormat.D32_FLOAT, 2);
    private final PostTexture levelMaskColour = new PostTexture("enchantment outline level mask colour", GpuFormat.RGBA8_UNORM, 2);
    private final PostTexture glow = new PostTexture("enchantment outline glow", GpuFormat.RGBA16_FLOAT, 2);
    private final PostTexture glowScratch = new PostTexture("enchantment outline scratch", GpuFormat.RGBA16_FLOAT, 2);
    private final PostTexture levelSpread = new PostTexture("enchantment outline level spread", GpuFormat.RGBA16_FLOAT, 2);
    private final PostTexture colour = new PostTexture("enchantment outline colour", GpuFormat.RGBA16_FLOAT, 4);
    private final PostTexture colourScratch = new PostTexture("enchantment outline colour scratch", GpuFormat.RGBA16_FLOAT, 4);
    private final Vector4f values = new Vector4f();
    private final Vector4f shape = new Vector4f();
    private final float[] userColour = new float[3];
    /** The level's silhouette was drawn this frame and waits for its glow. */
    private boolean levelMaskReady;
    /** For QA: frames whose level silhouette had something in it. */
    private long levelFrames;
    /** For QA: frames whose first-person silhouette had something in it. */
    private long handFrames;

    EnchantmentOutline(LightPostFrame lightPost, OutlineMarkers markers) {
        this.lightPost = lightPost;
        this.markers = markers;
    }

    /** From the hand's {@code prepareFrame}, before the hand pass opens. */
    public void beforeHand(FeatureRenderDispatcher.PreparedFrame handFrame) {
        RenderSettings.Outlines outlines = RenderSettingsAccess.current().outlines();
        if (markers.handMarkers() == 0 || !outlines.enchantmentOutlines() || outlines.intensity() <= 0.0f || !lightPost.outlinesAllowed()
            || !RenderFeatureStatus.mayRun(RenderFeature.ENCHANTMENT_OUTLINES)) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (main.getColorTextureView() == null) {
            return;
        }
        PostTimings.begin(PostTimings.OUTLINE);
        try {
            if (drawMask(handFrame, handMask, handMaskColour, main)) {
                handFrames++;
                drawHandGlow(main, outlines);
            }
            RenderFeatureStatus.succeeded(RenderFeature.ENCHANTMENT_OUTLINES);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.ENCHANTMENT_OUTLINES, error);
        }
        PostTimings.end(PostTimings.OUTLINE);
    }

    /**
     * From the level's {@code prepareFrame}, before the level's pass opens: the silhouette of
     * everything in the level that gets an outline, if anything does.
     */
    public void afterLevelFeaturesPrepared(FeatureRenderDispatcher.PreparedFrame levelFrame) {
        levelMaskReady = false;
        if (markers.levelMarkers() == 0) {
            return;
        }
        PostTimings.begin(PostTimings.OUTLINE);
        try {
            levelMaskReady = drawMask(levelFrame, levelMask, levelMaskColour, Minecraft.getInstance().gameRenderer.mainRenderTarget());
            if (levelMaskReady) {
                levelFrames++;
            }
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.ENCHANTMENT_OUTLINES, error);
        }
        PostTimings.end(PostTimings.OUTLINE);
    }

    /**
     * A world-stage post pass: the glow around the level's silhouette drawn this frame. Each mask
     * pixel spreads by its own amount (prepare), across then down (spread); the colours spread
     * evenly at a quarter of the screen; the composite colours the glow and leaves the items'
     * own pixels out.
     */
    void drawLevelGlow(PostContext context) {
        if (!levelMaskReady) {
            return;
        }
        levelMaskReady = false;
        RenderSettings.Outlines outlines = context.settings().outlines();
        PostTimings.begin(PostTimings.OUTLINE);
        try {
            RenderTarget main = context.mainTarget();
            GpuTextureView prepared = glowScratch.ensure(main.width, main.height);
            GpuTextureView spread = levelSpread.ensure(main.width, main.height);
            GpuTextureView strength = glow.ensure(main.width, main.height);
            GpuTextureView colourView = colour.ensure(main.width, main.height);
            GpuTextureView colourScratchView = colourScratch.ensure(main.width, main.height);

            float referenceSigma = 0.5f * glowRadius(outlines, main.height);
            float referenceDistance = OutlineScale.referenceDistance(LiteLightsRenderer.get().projection().unjittered().m11());
            values.set(OutlineMarkers.MASK_CLEAR_DEPTH, (float) OutlineMarkers.REACH, referenceSigma, referenceDistance);
            shape.set(OutlineScale.MIN_SIGMA, 0.0f, 0.0f, 0.0f);
            try (RenderPass pass = openFullScreen(LEVEL_PREPARE, prepared);
                 MemoryStack stack = MemoryStack.stackPush()) {
                pass.setUniform("MaskSampler", levelMask.view(), PostContext.nearestSampler());
                pass.setUniform("SceneDepthSampler", context.sceneDepth(), PostContext.nearestSampler());
                pushAndDraw(pass, stack, values, shape);
            }

            float taps = Math.min(MAX_TAPS, (float) Math.ceil(TAPS_PER_SIGMA * referenceSigma));
            values.set(1.0f / glow.width(), 0.0f, taps, 0.0f);
            runFullScreen(LEVEL_SPREAD_ACROSS, "InputSampler", prepared, spread, values, true);
            values.set(0.0f, 1.0f / glow.height(), taps, 0.0f);
            runFullScreen(LEVEL_SPREAD_DOWN, "InputSampler", spread, strength, values, true);

            // Colours only need to reach as far as the widest glow; bloom's blur spreads about 1.73 steps per sigma.
            float colourStep = TAPS_PER_SIGMA * referenceSigma / 1.73f;
            values.set(colourStep / glow.width(), 0.0f, 0.0f, 0.0f);
            runFullScreen(BloomPass.BLUR, "InputSampler", levelMaskColour.view(), colourScratchView, values, false);
            values.set(0.0f, colourStep / glow.height(), 0.0f, 0.0f);
            runFullScreen(BloomPass.BLUR, "InputSampler", colourScratchView, colourView, values, false);

            setGlowValues(outlines, OutlineMarkers.MASK_CLEAR_DEPTH);
            try (RenderPass pass = openFullScreen(LEVEL_COMPOSITE, main.getColorTextureView());
                 MemoryStack stack = MemoryStack.stackPush()) {
                pass.setUniform("GlowSampler", strength, PostContext.linearSampler());
                pass.setUniform("ColourSampler", colourView, PostContext.linearSampler());
                pass.setUniform("MaskSampler", levelMask.view(), PostContext.nearestSampler());
                pass.setUniform("SceneDepthSampler", context.sceneDepth(), PostContext.nearestSampler());
                pushAndDraw(pass, stack, values, shape);
            }
            RenderFeatureStatus.succeeded(RenderFeature.ENCHANTMENT_OUTLINES);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.ENCHANTMENT_OUTLINES, error);
        }
        PostTimings.end(PostTimings.OUTLINE);
    }

    /** For QA. */
    public long levelFrames() {
        return levelFrames;
    }

    /** For QA. */
    public long handFrames() {
        return handFrames;
    }

    /**
     * Replays prepared draws, keeping only the markers, into a half-resolution mask: the markers'
     * colours, and their depth. False when no marker was drawn after all.
     */
    private boolean drawMask(FeatureRenderDispatcher.PreparedFrame frame, PostTexture depth, PostTexture colours, RenderTarget main) {
        GpuTextureView depthView = depth.ensure(main.width, main.height);
        GpuTextureView colourView = colours.ensure(main.width, main.height);
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "LiteLights enchantment outline mask")
            .withColorAttachment(colourView, CLEAR_COLOUR)
            .withDepthAttachment(depthView, OptionalDouble.of(OutlineMarkers.MASK_CLEAR_DEPTH))
            .build();
        try (RenderPass pass = GuardedRenderPass.open(descriptor)) {
            RenderSystem.bindDefaultUniforms(pass);
            maskPass.begin(pass);
            FeatureRenderDispatcher.renderAllFeatures(maskPass, frame);
        }
        return maskPass.drewAnything();
    }

    private void drawHandGlow(RenderTarget main, RenderSettings.Outlines outlines) {
        GpuTextureView first = glow.ensure(main.width, main.height);
        GpuTextureView second = glowScratch.ensure(main.width, main.height);
        float radius = glowRadius(outlines, main.height);
        float step = Math.max(radius / 4.0f, 0.5f);
        values.set(step / glow.width(), 0.0f, OutlineMarkers.MASK_CLEAR_DEPTH, 0.0f);
        runFullScreen(MASK_BLUR, "MaskSampler", handMask.view(), first, values, true);
        // The vertical half uses bloom's blur, whose outer taps sit 3.2 steps out.
        values.set(0.0f, radius / 3.2f / glow.height(), 0.0f, 0.0f);
        runFullScreen(BloomPass.BLUR, "InputSampler", first, second, values, false);

        setGlowValues(outlines, OutlineMarkers.MASK_CLEAR_DEPTH);
        try (RenderPass pass = openFullScreen(COMPOSITE, main.getColorTextureView());
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setUniform("GlowSampler", second, PostContext.linearSampler());
            pushAndDraw(pass, stack, values, shape);
        }
    }

    /**
     * The first-person glow's reach in pixels. Size is in pixels of a 1080-line screen; the mask is
     * at half resolution, and the horizontal pass spreads four steps each way. Diffusion scales it
     * from half (crisp, 0 %) through the original (50 %) to double (100 %).
     */
    private static float glowRadius(RenderSettings.Outlines outlines, int screenHeight) {
        float diffusionScale = (float) Math.pow(2.0, (outlines.diffusion() - 0.5) * 2.0);
        return outlines.size() * (screenHeight / 1080.0f) * 0.75f * diffusionScale;
    }

    /**
     * The composite's push values: the glow colour (the level's composite uses it only where no
     * marker colour reaches) and intensity, and the edge's shape from diffusion.
     */
    private void setGlowValues(RenderSettings.Outlines outlines, float clearDepth) {
        OutlineColours.linear(outlines.colour(), outlines.saturation(), userColour);
        values.set(userColour[0], userColour[1], userColour[2], outlines.intensity());
        float hardness = Math.clamp(1.0f - 2.0f * outlines.diffusion(), 0.0f, 1.0f);
        float softness = Math.clamp(2.0f * outlines.diffusion() - 1.0f, 0.0f, 1.0f);
        shape.set(hardness, softness, clearDepth, 0.0f);
    }

    private static RenderPass openFullScreen(RenderPipeline pipeline, GpuTextureView output) {
        // Compiled before the pass opens: a pipeline that fails to compile then leaves no pass behind.
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(pipeline);
        RenderPass pass = GuardedRenderPass.open(() -> "LiteLights enchantment outline", output, Optional.empty());
        try {
            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsRenderer.get().bindWorldUniforms(pass);
        } catch (RuntimeException failure) {
            pass.close();
            throw failure;
        }
        return pass;
    }

    private static void pushAndDraw(RenderPass pass, MemoryStack stack, Vector4f first, @Nullable Vector4f second) {
        ByteBuffer push = stack.malloc(second == null ? 16 : 32);
        first.get(0, push);
        if (second != null) {
            second.get(16, push);
        }
        pass.pushConstants(push);
        pass.draw(3, 1, 0, 0);
    }

    private static void runFullScreen(RenderPipeline pipeline, String samplerName, GpuTextureView input, GpuTextureView output,
                                      Vector4f pushValues, boolean nearest) {
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights enchantment outline", output, Optional.empty());
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(samplerName, input, nearest ? PostContext.nearestSampler() : PostContext.linearSampler());
            ByteBuffer push = stack.malloc(16);
            pushValues.get(0, push);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
    }

    static RenderPipeline[] pipelines() {
        return new RenderPipeline[] {MASK_BLUR, BloomPass.BLUR, COMPOSITE, LEVEL_PREPARE, LEVEL_SPREAD_ACROSS, LEVEL_SPREAD_DOWN, LEVEL_COMPOSITE,
            OutlineMarkers.HIDDEN, OutlineMarkers.MASK};
    }
}
