package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPass;
import com.thenathe.litelights.render.post.PostPipelines;
import com.thenathe.litelights.render.post.PostTexture;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryStack;

/**
 * Temporal anti-aliasing, through core-render's projection seam: the level is drawn with a
 * different sub-pixel jitter each frame ({@link TaaJitter}), and this pass blends each frame into a
 * history of the earlier ones (see post/taa.fsh). Only the level is jittered; the hand and HUD are
 * drawn after this pass and never are.
 *
 * <p>The pass writes the main target and the next history in one go. The history is half-float,
 * so a pixel settles all the way to its colour instead of stopping a few 8-bit steps short (an
 * RGBA8 history blended at a tenth per frame can't move by less than five steps), and its alpha
 * holds each pixel's view depth, which the next frame checks to tell whether the same surface was
 * there. It starts again whenever the picture can't follow on from the last one: a new size, a new
 * level, a jump of the camera, changed settings or the settings screen's time-of-day preview.
 */
final class TaaPass implements PostPass {
    private static final RenderPipeline PIPELINE = PostPipelines.fullScreen("taa", "taa",
            PostPipelines.samplers("SceneSampler", "DepthSampler", "OpaqueDepthSampler", "HistorySampler", "PreviousOpaqueDepthSampler"),
            PostPipelines.MAIN_TARGET, 64)
        .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, PostPipelines.WRITE_RGBA))
        .build();

    /** Weight of the new frame: the history holds roughly the last ten frames. */
    private static final float NEW_FRAME_WEIGHT = 0.1f;
    /** Behind translucent surfaces, which animate (waves, rain on them), a shorter history. */
    private static final float TRANSLUCENT_NEW_FRAME_WEIGHT = 0.2f;
    private static final float SHARPEN = 0.2f;
    /** While the camera moves, up to this much of the new frame, reached at MOVING_PIXELS a frame. */
    private static final float MOVING_NEW_FRAME_WEIGHT = 0.3f;
    private static final float MOVING_PIXELS = 6.0f;
    /** How strongly a moving history is sharpened against the softening of reading it between pixels. */
    private static final float MOVING_HISTORY_SHARPEN = 0.9f;
    /** Debug: never use the history, so each frame shows only itself moved back by its jitter. */
    private static final boolean NO_HISTORY = Boolean.getBoolean("litelights.debug.taaNoHistory");
    /** Two depths further apart than this share (plus 2 cm) belong to different surfaces. */
    private static final float DEPTH_TOLERANCE = 0.03f;
    /** A camera that moved further than this in one frame was teleported: nothing to follow on from. */
    private static final double CAMERA_JUMP_BLOCKS = 6.0;

    private final PostTexture[] histories = {
        new PostTexture("TAA history A", GpuFormat.RGBA16_FLOAT, 1),
        new PostTexture("TAA history B", GpuFormat.RGBA16_FLOAT, 1)
    };
    private final TaaOpaqueDepth opaqueDepth = new TaaOpaqueDepth();
    private final Vector3d lastCamera = new Vector3d();
    private int currentHistory;
    private boolean historyValid;
    private boolean jittering;
    private float jitterX;
    private float jitterY;
    private int frame;
    private Object lastLevel;
    private RenderSettings lastSettings;

    /** TAA is chosen and may run. FXAA stands in while it can't (FxaaPass.willRun). */
    boolean wanted(RenderSettings settings) {
        return settings.general().antiAliasing() == RenderSettings.AntiAliasing.TAA
            && RenderFeatureStatus.mayRun(RenderFeature.TAA);
    }

    /** Whether the opaque depth should be copied before translucent surfaces this frame. */
    boolean wantsOpaqueDepth() {
        return wanted(RenderSettingsAccess.current());
    }

    /** After the solid world, before translucent surfaces (from the water stream's level hook). */
    void beforeTranslucents(RenderTarget mainTarget) {
        try {
            opaqueDepth.take(mainTarget);
        } catch (RuntimeException error) {
            stopJitter();
            RenderFeatureStatus.failed(RenderFeature.TAA, error);
        }
    }

    @Override
    public void draw(PostContext context) {
        if (!wanted(context.settings())) {
            stopJitter();
            return;
        }
        try {
            int width = context.width();
            int height = context.height();
            if (!followsOn(context)) {
                historyValid = false;
            }
            PostTexture previous = histories[currentHistory];
            PostTexture next = histories[1 - currentHistory];
            if (previous.width() != width || previous.height() != height) {
                historyValid = false;
            }
            GpuTextureView previousView = previous.ensure(width, height);
            GpuTextureView nextView = next.ensure(width, height);
            GpuTextureView opaqueNow = opaqueDepth.thisFrame(width, height);
            GpuTextureView opaqueBefore = historyValid ? opaqueDepth.lastFrame(width, height) : null;
            GpuTextureView scene = context.copyOfScene();
            GpuTextureView depth = context.sceneDepth();

            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "LiteLights TAA")
                .withColorAttachment(context.sceneColor())
                .withColorAttachment(nextView)
                .build();
            try (RenderPass pass = GuardedRenderPass.open(descriptor);
                 MemoryStack stack = MemoryStack.stackPush()) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                context.bindUniforms(pass);
                pass.setUniform("SceneSampler", scene, PostContext.linearSampler());
                pass.setUniform("DepthSampler", depth, PostContext.nearestSampler());
                pass.setUniform("OpaqueDepthSampler", opaqueNow != null ? opaqueNow : depth, PostContext.nearestSampler());
                pass.setUniform("HistorySampler", previousView, PostContext.linearSampler());
                pass.setUniform("PreviousOpaqueDepthSampler", opaqueBefore != null ? opaqueBefore : depth, PostContext.nearestSampler());
                ByteBuffer push = stack.malloc(64);
                push.putFloat(0, 1.0f / width).putFloat(4, 1.0f / height);
                push.putFloat(8, historyValid && jittering && !NO_HISTORY ? 1.0f : 0.0f).putFloat(12, NEW_FRAME_WEIGHT);
                push.putFloat(16, jittering ? jitterX / width : 0.0f).putFloat(20, jittering ? jitterY / height : 0.0f);
                push.putFloat(24, SHARPEN).putFloat(28, opaqueNow != null ? 1.0f : 0.0f);
                push.putFloat(32, DEPTH_TOLERANCE).putFloat(36, TRANSLUCENT_NEW_FRAME_WEIGHT);
                push.putFloat(40, opaqueBefore != null ? 1.0f : 0.0f).putFloat(44, 0.0f);
                push.putFloat(48, MOVING_NEW_FRAME_WEIGHT).putFloat(52, MOVING_PIXELS);
                push.putFloat(56, MOVING_HISTORY_SHARPEN).putFloat(60, 0.0f);
                pass.pushConstants(push);
                pass.draw(3, 1, 0, 0);
            }
            currentHistory = 1 - currentHistory;
            opaqueDepth.endFrame();
            historyValid = true;
            nextJitter(width, height);
            RenderFeatureStatus.succeeded(RenderFeature.TAA);
        } catch (RuntimeException error) {
            stopJitter();
            RenderFeatureStatus.failed(RenderFeature.TAA, error);
        }
    }

    /**
     * Whether this frame continues the last one TAA drew: the same level and settings, and
     * a camera that moved rather than jumped. Remembers this frame's for the next.
     */
    private boolean followsOn(PostContext context) {
        FrameState state = context.frame();
        double cameraX = state.cameraBlockX + state.cameraFraction.x;
        double cameraY = state.cameraBlockY + state.cameraFraction.y;
        double cameraZ = state.cameraBlockZ + state.cameraFraction.z;
        Object level = Minecraft.getInstance().level;
        // A new settings snapshot also comes with every change of the time-of-day preview.
        boolean followsOn = level == lastLevel
            && context.settings() == lastSettings
            && lastCamera.distance(cameraX, cameraY, cameraZ) < CAMERA_JUMP_BLOCKS;
        lastLevel = level;
        lastSettings = context.settings();
        lastCamera.set(cameraX, cameraY, cameraZ);
        return followsOn;
    }

    /** Sets the jitter the next frame's level is drawn with. */
    private void nextJitter(int width, int height) {
        frame++;
        jitterX = TaaJitter.x(frame);
        jitterY = TaaJitter.y(frame);
        LiteLightsRenderer.get().projection().setJitter(jitterX, jitterY, width, height);
        jittering = true;
    }

    private void stopJitter() {
        if (jittering) {
            LiteLightsRenderer.get().projection().setJitter(0.0f, 0.0f, 1, 1);
            jittering = false;
        }
        historyValid = false;
        opaqueDepth.forget();
    }

    static RenderPipeline[] pipelines() {
        return new RenderPipeline[] {PIPELINE, TaaOpaqueDepth.PIPELINE};
    }
}
