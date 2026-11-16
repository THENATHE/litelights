package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.water.WaterRenderer;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPass;
import com.thenathe.litelights.render.post.PostPipelines;
import com.thenathe.litelights.render.post.PostTexture;
import java.nio.ByteBuffer;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Bloom, read from the finished level: emissive pixels (the mask the surface hook leaves in alpha)
 * and the very brightest ones, at a quarter of the screen size and again at an eighth, each
 * blurred. The tonemap adds both levels. Six small passes, none at full resolution.
 *
 * <p>Water and other translucent surfaces overwrite the mask of what lies behind them. When water
 * took its copy of the opaque scene this frame, the prefilter also reads that copy's colour and
 * alpha, so lava under water still glows, scaled by how much of it shows through.
 *
 * <p>The prefilter runs with the world passes, before the hand; the blurs wait for the final stage.
 * By then the hand is drawn, so a held item hides the glow of whatever is behind it: the hand's
 * pixels are taken out of the prefiltered level and replaced by the hand's own glow (none, unless it
 * marks a glowing texel), and the tonemap adds no glow from the level on top of the hand.
 */
final class BloomPass implements PostPass {
    private static final RenderPipeline PREFILTER = PostPipelines.fullScreen("bloom_prefilter", "bloom_prefilter",
        PostPipelines.samplers("SceneSampler", "DepthSampler", "OpaqueColorSampler", "OpaqueAlphaSampler"), PostPipelines.HALF_FLOAT, 16).build();
    private static final RenderPipeline DOWNSAMPLE = PostPipelines.fullScreen("bloom_downsample", "bloom_downsample",
        PostPipelines.samplers("InputSampler"), PostPipelines.HALF_FLOAT, 16).build();
    static final RenderPipeline BLUR = PostPipelines.fullScreen("blur", "blur",
        PostPipelines.samplers("InputSampler"), PostPipelines.HALF_FLOAT, 16).build();
    private static final RenderPipeline HAND_COVER = PostPipelines.fullScreen("bloom_hand_cover", "bloom_hand_cover",
        PostPipelines.samplers("PrefilterSampler", "SceneSampler", "HandDepthSampler"), PostPipelines.HALF_FLOAT, 16).build();

    /** Vanilla clears the hand's depth to this before drawing it (reversed depth: the far end). */
    static final float HAND_DEPTH_CLEAR = 0.0f;

    /** Merely bright pixels (not emissive) start to glow above this luminance... */
    private static final float BRIGHT_THRESHOLD = 0.96f;
    /** ...and glow this much at full white: enough for the sun, too little for snow or clouds. */
    private static final float BRIGHT_WEIGHT = 0.25f;

    private final LightPostFrame lightPost;
    private final PostTexture near = new PostTexture("bloom near", GpuFormat.RGBA16_FLOAT, 4);
    private final PostTexture nearCovered = new PostTexture("bloom near, hand covered", GpuFormat.RGBA16_FLOAT, 4);
    private final PostTexture nearScratch = new PostTexture("bloom near scratch", GpuFormat.RGBA16_FLOAT, 4);
    private final PostTexture wide = new PostTexture("bloom wide", GpuFormat.RGBA16_FLOAT, 8);
    private final PostTexture wideScratch = new PostTexture("bloom wide scratch", GpuFormat.RGBA16_FLOAT, 8);
    private final Vector4f values = new Vector4f();
    /** The level was read into {@code near} this frame and waits for the final stage. */
    private boolean prefilteredThisFrame;
    private boolean drawnThisFrame;
    /** The depth the hand was drawn with this frame, or null if no hand was drawn. */
    private @Nullable GpuTextureView handDepth;

    BloomPass(LightPostFrame lightPost) {
        this.lightPost = lightPost;
    }

    /** World stage: reads the level's glowing pixels into {@code near}, before the hand is drawn. */
    @Override
    public void draw(PostContext context) {
        prefilteredThisFrame = false;
        drawnThisFrame = false;
        handDepth = null;
        if (!lightPost.bloomWanted(context.settings())) {
            return;
        }
        try {
            near.ensure(context.width(), context.height());
            prefilter(context, near.view());
            prefilteredThisFrame = true;
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.BLOOM, error);
        }
    }

    /** The hand is about to be drawn with this depth texture (vanilla clears it first). */
    void handDrawn(GpuTextureView depth) {
        handDepth = depth;
    }

    /** The hand's depth this frame, for the tonemap once {@link #finish} drew bloom, or null if no hand was drawn. */
    @Nullable GpuTextureView handDepth() {
        return drawnThisFrame ? handDepth : null;
    }

    /**
     * Final stage, before the tonemap: takes the hand out of the prefiltered level, then blurs it
     * and makes the wide level. Returns whether there is bloom to add this frame.
     */
    boolean finish(PostContext context) {
        if (!prefilteredThisFrame) {
            return false;
        }
        prefilteredThisFrame = false;
        try {
            int width = context.width();
            int height = context.height();
            nearScratch.ensure(width, height);
            wide.ensure(width, height);
            wideScratch.ensure(width, height);

            GpuTextureView source = near.view();
            if (handDepth != null) {
                coverWithHand(context, nearCovered.ensure(width, height), handDepth);
                source = nearCovered.view();
            }
            blur(context, source, near, nearScratch);
            values.set(1.0f / near.width(), 1.0f / near.height(), 0.0f, 0.0f);
            run(context, DOWNSAMPLE, "InputSampler", near.view(), wide.view(), values);
            blur(context, wide.view(), wide, wideScratch);

            drawnThisFrame = true;
            RenderFeatureStatus.succeeded(RenderFeature.BLOOM);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.BLOOM, error);
        }
        return drawnThisFrame;
    }

    /** The prefiltered level with the hand's pixels replaced by the hand's own glow. */
    private void coverWithHand(PostContext context, GpuTextureView output, GpuTextureView depth) {
        values.set(HAND_DEPTH_CLEAR, 0.0f, 0.0f, 0.0f);
        try (RenderPass pass = context.openFullScreen(() -> "LiteLights bloom hand cover", HAND_COVER, output);
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setUniform("PrefilterSampler", near.view(), PostContext.nearestSampler());
            pass.setUniform("SceneSampler", context.sceneColor(), PostContext.nearestSampler());
            pass.setUniform("HandDepthSampler", depth, PostContext.nearestSampler());
            ByteBuffer push = stack.malloc(16);
            values.get(0, push);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
    }

    private void prefilter(PostContext context, GpuTextureView output) {
        GpuTextureView opaqueColor = WaterRenderer.get().opaqueSceneColor();
        GpuTextureView opaqueAlpha = WaterRenderer.get().opaqueSceneAlpha();
        boolean hasOpaqueCopy = opaqueColor != null && opaqueAlpha != null;
        values.set(hasOpaqueCopy ? 1.0f : 0.0f, 0.0f, BRIGHT_THRESHOLD, BRIGHT_WEIGHT);
        try (RenderPass pass = context.openFullScreen(() -> "LiteLights bloom prefilter", PREFILTER, output);
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setUniform("SceneSampler", context.sceneColor(), PostContext.linearSampler());
            pass.setUniform("DepthSampler", context.sceneDepth(), PostContext.nearestSampler());
            // Without a copy the shader never reads these; the scene stands in so both are bound.
            pass.setUniform("OpaqueColorSampler", hasOpaqueCopy ? opaqueColor : context.sceneColor(), PostContext.nearestSampler());
            pass.setUniform("OpaqueAlphaSampler", hasOpaqueCopy ? opaqueAlpha : context.sceneColor(), PostContext.nearestSampler());
            ByteBuffer push = stack.malloc(16);
            values.get(0, push);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
    }

    /** Horizontal from the source into the scratch texture, then vertical into the level. */
    private void blur(PostContext context, GpuTextureView source, PostTexture level, PostTexture scratch) {
        values.set(1.0f / level.width(), 0.0f, 0.0f, 0.0f);
        run(context, BLUR, "InputSampler", source, scratch.view(), values);
        values.set(0.0f, 1.0f / level.height(), 0.0f, 0.0f);
        run(context, BLUR, "InputSampler", scratch.view(), level.view(), values);
    }

    static void run(PostContext context, RenderPipeline pipeline, String samplerName, GpuTextureView input, GpuTextureView output,
                    Vector4f pushValues) {
        try (RenderPass pass = context.openFullScreen(() -> "LiteLights bloom", pipeline, output);
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setUniform(samplerName, input, PostContext.linearSampler());
            ByteBuffer push = stack.malloc(16);
            pushValues.get(0, push);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
    }

    GpuTextureView nearView() {
        return near.view();
    }

    GpuTextureView wideView() {
        return wide.view();
    }

    static RenderPipeline[] pipelines() {
        return new RenderPipeline[] {PREFILTER, HAND_COVER, DOWNSAMPLE, BLUR, ToneGradePass.pipeline()};
    }
}
