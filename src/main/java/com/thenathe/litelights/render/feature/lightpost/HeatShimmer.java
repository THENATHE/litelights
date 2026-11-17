package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPass;
import com.thenathe.litelights.render.post.PostPipelines;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import org.lwjgl.system.MemoryStack;

/**
 * Heat shimmer (shimmer099; the idea from Lava Particles | More's heat waves over lava): the picture
 * wavers a little in the air over lava, fire, lit campfires, furnaces and magma blocks, and over
 * open desert and badlands ground around noon in clear weather. One full-screen pass after
 * anti-aliasing (see {@link com.thenathe.litelights.render.post.PostSlot#HEAT_SHIMMER}) traces each
 * pixel's line of sight through the heat around the camera ({@link HeatSources}) in a fixed ten
 * steps, out to 48 blocks, and reads the picture a pixel or two aside where the line crossed hot
 * air (post/heat_shimmer.fsh). Pixels whose line meets no heat are left as they are.
 *
 * <p>Nothing is drawn when no heat is near, so the pass costs nothing away from it. The ripples run
 * on the shared shader clock and stop with the pause screen.
 */
final class HeatShimmer implements PostPass {
    private static final RenderPipeline PIPELINE = PostPipelines.fullScreen("heat_shimmer", "heat_shimmer",
            PostPipelines.samplers("ShimmerScene", "ShimmerDepth", "ShimmerHeat"),
            new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, PostPipelines.WRITE_RGB), 64)
        .build();

    /** No shimmer past this many blocks. */
    private static final float REACH = 48.0f;
    /** Block sources' heat rises this far above them, and open ground's this far; heat_shimmer.fsh has the same. */
    private static final float RISE = 4.0f;
    /** The largest sideways shift, in pixels on a 1080-line screen. */
    private static final float LARGEST_SHIFT = 3.4f;

    private final LightPostFrame lightPost;
    private final HeatSources sources = new HeatSources();
    private boolean ticking;
    private boolean cameraInLava;
    // For QA: client-thread time spent keeping the heat map.
    private long tickNanos;
    private long ticksTimed;

    HeatShimmer(LightPostFrame lightPost) {
        this.lightPost = lightPost;
    }

    static RenderPipeline pipeline() {
        return PIPELINE;
    }

    /** Client tick: keeps the map of heat around the camera while the shimmer is wanted. */
    void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        boolean wanted = level != null && RenderSettingsAccess.current().lighting().heatShimmer()
            && RenderFeatureStatus.mayRun(RenderFeature.HEAT_SHIMMER);
        if (!wanted) {
            if (ticking) {
                sources.forget();
                ticking = false;
            }
            return;
        }
        ticking = true;
        Camera camera = minecraft.gameRenderer.mainCamera();
        double x = camera.position().x;
        double y = camera.position().y;
        double z = camera.position().z;
        cameraInLava = camera.getFluidInCamera() == FogType.LAVA;
        long started = System.nanoTime();
        sources.tick(level, x, y, z, level.dimensionType().hasSkyLight());
        tickNanos += System.nanoTime() - started;
        ticksTimed++;
    }

    void blockChanged(BlockPos position, BlockState oldState, BlockState newState) {
        if (ticking) {
            sources.blockChanged(position, oldState, newState);
        }
    }

    /**
     * How hot open desert ground is now, 0 to 1: around noon (the sun high), in clear weather, in a
     * dimension with the overworld's sky.
     */
    private float desertHeat(FrameState frame) {
        if (frame.dimensionKind != FrameFlags.DIMENSION_OVERWORLD) {
            return 0.0f;
        }
        float noon = LiteMath.smoothstep(0.6f, 0.9f, frame.sunDirection.w);
        float clear = 1.0f - LiteMath.smoothstep(0.0f, 0.3f, frame.weather.x);
        return noon * clear;
    }

    @Override
    public void draw(PostContext context) {
        FrameState frame = context.frame();
        if (!ticking || !context.settings().lighting().heatShimmer() || !RenderFeatureStatus.mayRun(RenderFeature.HEAT_SHIMMER)
            || !DimensionGate.allows(Aspect.EFFECTS) || lightPost.underwater() || cameraInLava) {
            return;
        }
        float desert = sources.anyHotGround() ? desertHeat(frame) : 0.0f;
        if (!sources.anyHeat() && desert <= 0.0f) {
            return;
        }
        try {
            double cameraX = frame.cameraBlockX + (double) frame.cameraFraction.x();
            double cameraY = frame.cameraBlockY + (double) frame.cameraFraction.y();
            double cameraZ = frame.cameraBlockZ + (double) frame.cameraFraction.z();
            GpuTextureView heat = sources.view();
            GpuTextureView scene = context.copyOfScene();
            try (RenderPass pass = context.openFullScreen(() -> "LiteLights heat shimmer", PIPELINE, context.sceneColor());
                 MemoryStack stack = MemoryStack.stackPush()) {
                pass.setUniform("ShimmerScene", scene, PostContext.linearSampler());
                pass.setUniform("ShimmerDepth", context.sceneDepth(), PostContext.nearestSampler());
                pass.setUniform("ShimmerHeat", heat, RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
                ByteBuffer push = stack.malloc(64);
                // Where the camera stands on the wrapping map, as a texture coordinate.
                push.putFloat(0, (float) (Math.floorMod(frame.cameraBlockX, HeatSources.SIZE) + frame.cameraFraction.x()) / HeatSources.SIZE);
                push.putFloat(4, (float) (Math.floorMod(frame.cameraBlockZ, HeatSources.SIZE) + frame.cameraFraction.z()) / HeatSources.SIZE);
                push.putFloat(8, (float) (sources.referenceY() - cameraY));
                push.putFloat(12, desert);
                // The slab and box the heat lies in, camera-relative, so most lines of sight end at once.
                push.putFloat(16, (float) (sources.lowestY() - 1.0 - cameraY));
                push.putFloat(20, (float) (sources.highestY() + RISE - cameraY));
                push.putFloat(24, REACH);
                push.putFloat(28, LARGEST_SHIFT * context.height() / 1080.0f);
                push.putFloat(32, (float) (sources.westX() - 1.0 - cameraX));
                push.putFloat(36, (float) (sources.northZ() - 1.0 - cameraZ));
                push.putFloat(40, (float) (sources.eastX() + 1.0 - cameraX));
                push.putFloat(44, (float) (sources.southZ() + 1.0 - cameraZ));
                push.putFloat(48, 0.0f).putFloat(52, 0.0f).putFloat(56, 0.0f).putFloat(60, 0.0f);
                pass.pushConstants(push);
                pass.draw(3, 1, 0, 0);
            }
            RenderFeatureStatus.succeeded(RenderFeature.HEAT_SHIMMER);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.HEAT_SHIMMER, error);
        }
    }

    /** QA: whether any heat, or open desert ground, is near the camera now. */
    boolean heatNear() {
        return sources.anyHeat();
    }

    boolean hotGroundNear() {
        return sources.anyHotGround();
    }

    double averageTickMillis() {
        return ticksTimed == 0 ? 0.0 : tickNanos / 1.0e6 / ticksTimed;
    }

    void resetTickTimings() {
        tickNanos = 0;
        ticksTimed = 0;
    }
}
