package com.thenathe.litelights.render.feature.clouds;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.clouds.volume.CloudMap;
import com.thenathe.litelights.clouds.volume.CloudNoiseVolume;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostPipelines;
import com.thenathe.litelights.render.post.PostTexture;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws the volumetric Dynamic clouds into the main target, before translucent terrain: a march
 * through the clouds at a fraction of the screen's resolution ({@code shaders/clouds/volume_march.fsh}),
 * blended with the frames before so its steps' pattern averages out ({@code volume_resolve.fsh}),
 * laid over the picture at full resolution keeping the edges of what stands in front
 * ({@code volume_composite.fsh}), then the cloud's depth written where it hides more than half of
 * what is behind ({@code volume_depth.fsh}), so water, glass and particles drawn after sort against
 * it as they did against the cubes. How fine the march is comes from the Cloud quality row
 * ({@link Quality}). Render thread only.
 */
final class CloudVolume implements AutoCloseable {
    /**
     * One step of the Cloud quality row.
     *
     * @param divisor      the march's resolution: the screen's divided by this
     * @param steps        the most steps along a line of sight
     * @param growth       how much longer each step gets with distance (a share of the distance)
     * @param lightSamples how many times the cloud is read toward the sun at each step
     * @param shortest     the shortest step, in blocks
     * @param detailReach  how far out the finest heads are worn in, in blocks
     */
    record Quality(int divisor, int steps, float growth, int lightSamples, float shortest, float detailReach) {
        static Quality of(RenderSettings.CloudQuality quality) {
            return switch (quality) {
                case LOW -> new Quality(4, 40, 0.07f, 1, 10.0f, 800.0f);
                case MEDIUM -> new Quality(2, 56, 0.05f, 2, 8.0f, 1_500.0f);
                case HIGH -> new Quality(2, 88, 0.035f, 3, 6.0f, 2_500.0f);
                case ULTRA -> new Quality(1, 128, 0.025f, 4, 4.0f, 4_000.0f);
            };
        }
    }

    /** How much light a block of solid low cloud takes away: about 22 blocks of sight deep in one (0.05 until a little more softness was asked for). */
    static final float EXTINCTION = 0.045f;
    /** The shape noise repeats every this many blocks; its billows are a quarter of it (about 80 blocks). */
    static final float NOISE_PERIOD = 320.0f;
    /** How much of each frame's march goes into what is shown; the rest is the frames before. */
    private static final float NEW_SHARE = 0.18f;
    /** A camera that moved farther than this in a frame starts the blend afresh, in blocks. */
    private static final double MOVED_TOO_FAR = 48.0;
    /** Clouds not drawn for this long (another choice for a while, a menu) start the blend afresh. */
    private static final long PAUSE_NANOS = 250_000_000L;
    /** The near map is used whole within this share of its half-width from its middle, and has handed over to the far map by this. */
    private static final float NEAR_WHOLE = 0.7f;
    private static final float NEAR_HANDED_OVER = 0.9f;

    private static final BindGroupLayout INFO_LAYOUT = BindGroupLayout.builder()
        .withUniform("CloudVolumeInfo", UniformType.UNIFORM_BUFFER)
        .build();
    private static final int INFO_SIZE = new Std140SizeCalculator().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();
    private static final int PUSH_BYTES = 16;
    /** The picture times what shows through the cloud, plus the cloud's light; alpha (bloom's mask) kept. */
    private static final BlendFunction OVER = new BlendFunction(BlendFactor.ONE, BlendFactor.SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE);

    static final RenderPipeline MARCH = PostPipelines.fullScreen("cloud_volume_march", LiteLightsClient.id("clouds/volume_march"),
            PostPipelines.samplers("SceneDepth", "NearMap", "FarMap", "CloudNoise", "CloudLumps"),
            new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, PostPipelines.WRITE_RGBA), 0)
        .withBindGroupLayout(INFO_LAYOUT)
        .build();
    static final RenderPipeline RESOLVE = PostPipelines.fullScreen("cloud_volume_resolve", LiteLightsClient.id("clouds/volume_resolve"),
            PostPipelines.samplers("CloudMarch", "CloudHistory"), new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, PostPipelines.WRITE_RGBA),
            PUSH_BYTES)
        .build();
    static final RenderPipeline COMPOSITE = PostPipelines.fullScreen("cloud_volume_composite", LiteLightsClient.id("clouds/volume_composite"),
            PostPipelines.samplers("CloudMarch", "SceneDepth"), new ColorTargetState(Optional.of(OVER), GpuFormat.RGBA8_UNORM, PostPipelines.WRITE_RGB),
            PUSH_BYTES)
        .build();
    static final RenderPipeline DEPTH = PostPipelines.fullScreen("cloud_volume_depth", LiteLightsClient.id("clouds/volume_depth"),
            PostPipelines.samplers("CloudMarch"), new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE),
            PUSH_BYTES)
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
        .build();

    private final PostTexture[] marches = new PostTexture[5];
    /** Two resolved results a resolution, taking turns: this frame's and the last. */
    private final PostTexture[][] resolved = new PostTexture[5][];
    private int frameNumber;
    private int lastDivisor;
    private int lastWidth;
    private int lastHeight;
    private long lastDrawNanos;
    private double lastCameraX;
    private double lastCameraY;
    private double lastCameraZ;
    private final MappableRingBuffer info = new MappableRingBuffer(() -> "LiteLights cloud volume info",
        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, INFO_SIZE);
    private @Nullable GpuTexture noise;
    private @Nullable GpuTextureView noiseView;
    private @Nullable GpuTexture lumps;
    private @Nullable GpuTextureView lumpsView;
    /** The last draw's resolved clouds, as the composite read them: for what is drawn after the clouds (the distant fireworks). */
    private @Nullable GpuTextureView lastResolved;

    /** The two noise atlases: the billows and warps every cloud takes, and the heaped and storm clouds' lumps. */
    record NoiseAtlases(byte[] billows, byte[] lumps) {
    }

    /** Whether the noise atlases are up (they are made on a worker first). */
    boolean ready() {
        return noiseView != null && lumpsView != null;
    }

    /** Puts the noise atlases up, once. */
    void uploadNoise(NoiseAtlases atlases) {
        if (noise != null) {
            return;
        }
        noise = upload("LiteLights cloud noise", atlases.billows());
        noiseView = RenderSystem.getDevice().createTextureView(noise);
        lumps = upload("LiteLights cloud lumps", atlases.lumps());
        lumpsView = RenderSystem.getDevice().createTextureView(lumps);
    }

    private static GpuTexture upload(String name, byte[] atlas) {
        var device = RenderSystem.getDevice();
        GpuTexture texture = device.createTexture(name, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM,
            CloudNoiseVolume.ATLAS_SIZE, CloudNoiseVolume.ATLAS_SIZE, 1, 1);
        ByteBuffer staging = MemoryUtil.memAlloc(atlas.length);
        try {
            staging.put(atlas).flip();
            device.createCommandEncoder().writeToTexture(texture, staging, 0, 0, 0, 0, CloudNoiseVolume.ATLAS_SIZE, CloudNoiseVolume.ATLAS_SIZE);
        } finally {
            MemoryUtil.memFree(staging);
        }
        return texture;
    }

    /**
     * What one frame's draw needs to know, all camera-relative.
     *
     * @param near      the near map to read (blended between builds), its corner and middle
     * @param floorY    the clouds' floor, camera-relative y
     * @param fadeStart where the reach's fade starts, in blocks
     * @param noiseX    the camera in the noise's frame (cloud space, wrapped by its period)
     */
    record Frame(GpuTextureView near, CloudMap nearMap, double nearCornerX, double nearCornerZ, GpuTextureView far, CloudMap farMap,
                 double farCornerX, double farCornerZ, float floorY, float fadeStart, float reach, float red, float green, float blue,
                 float moonlight, double noiseX, double noiseY, double noiseZ, Quality quality) {
    }

    /**
     * Marches, blends with the frames before, lays the clouds over the main target and writes their
     * depth. Call with no render pass open.
     *
     * @param cameraX the camera in world space: a camera that jumped starts the blend afresh
     */
    void draw(RenderTarget mainTarget, Frame frame, double cameraX, double cameraY, double cameraZ) {
        GpuTextureView colour = mainTarget.getColorTextureView();
        GpuTextureView depth = mainTarget.getDepthTextureView();
        if (colour == null || depth == null || noiseView == null || lumpsView == null) {
            return;
        }
        int divisor = frame.quality.divisor;
        PostTexture march = marches[divisor];
        if (march == null) {
            march = new PostTexture("cloud volume 1/" + divisor, GpuFormat.RGBA32_FLOAT, divisor);
            marches[divisor] = march;
        }
        GpuTextureView marched = march.ensure(mainTarget.width, mainTarget.height);
        if (resolved[divisor] == null) {
            resolved[divisor] = new PostTexture[] {
                new PostTexture("cloud volume resolved A 1/" + divisor, GpuFormat.RGBA32_FLOAT, divisor),
                new PostTexture("cloud volume resolved B 1/" + divisor, GpuFormat.RGBA32_FLOAT, divisor)
            };
        }
        frameNumber = (frameNumber + 1) & 63;
        PostTexture[] turns = resolved[divisor];
        GpuTextureView history = turns[(frameNumber + 1) & 1].ensure(mainTarget.width, mainTarget.height);
        GpuTextureView result = turns[frameNumber & 1].ensure(mainTarget.width, mainTarget.height);
        double moved = Math.abs(cameraX - lastCameraX) + Math.abs(cameraY - lastCameraY) + Math.abs(cameraZ - lastCameraZ);
        long now = System.nanoTime();
        boolean afresh = now - lastDrawNanos > PAUSE_NANOS || divisor != lastDivisor || mainTarget.width != lastWidth
            || mainTarget.height != lastHeight || moved > MOVED_TOO_FAR;
        lastDrawNanos = now;
        lastDivisor = divisor;
        lastWidth = mainTarget.width;
        lastHeight = mainTarget.height;
        lastCameraX = cameraX;
        lastCameraY = cameraY;
        lastCameraZ = cameraZ;
        writeInfo(frame);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights cloud volume march", marched, Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsRenderer.get().bindWorldUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(MARCH));
            pass.setUniform("SceneDepth", depth, nearest);
            pass.setUniform("NearMap", frame.near, linear);
            pass.setUniform("FarMap", frame.far, linear);
            pass.setUniform("CloudNoise", noiseView, linear);
            pass.setUniform("CloudLumps", lumpsView, linear);
            pass.setUniform("CloudVolumeInfo", info.currentBuffer());
            pass.draw(3, 1, 0, 0);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putFloat(0, afresh ? 1.0f : NEW_SHARE).putFloat(4, 0.0f).putFloat(8, 0.0f).putFloat(12, 0.0f);
            try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights cloud volume resolve", result, Optional.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                LiteLightsRenderer.get().bindWorldUniforms(pass);
                pass.setPipeline(RenderSystem.getCompiledPipeline(RESOLVE));
                pass.setUniform("CloudMarch", marched, nearest);
                pass.setUniform("CloudHistory", history, nearest);
                pass.pushConstants(push);
                pass.draw(3, 1, 0, 0);
            }
            push.putFloat(0, divisor);
            try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights cloud volume composite", colour, Optional.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                LiteLightsRenderer.get().bindWorldUniforms(pass);
                pass.setPipeline(RenderSystem.getCompiledPipeline(COMPOSITE));
                pass.setUniform("CloudMarch", result, nearest);
                pass.setUniform("SceneDepth", depth, nearest);
                pass.pushConstants(push);
                pass.draw(3, 1, 0, 0);
            }
            try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights cloud volume depth", colour, Optional.empty(), depth,
                    OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                LiteLightsRenderer.get().bindWorldUniforms(pass);
                pass.setPipeline(RenderSystem.getCompiledPipeline(DEPTH));
                pass.setUniform("CloudMarch", result, nearest);
                pass.pushConstants(push);
                pass.draw(3, 1, 0, 0);
            }
        }
        lastResolved = result;
    }

    /**
     * The clouds the last draw laid over the picture, at the march's resolution ({@link #lastDivisor}):
     * their light, and the distance to their front plus the share of what lies behind that shows
     * through, packed as {@code volume_composite.fsh} reads them. Null before the first draw.
     */
    @Nullable GpuTextureView lastResolved() {
        return lastResolved;
    }

    /** The screen's pixels per marched pixel at the last draw. */
    int lastDivisor() {
        return lastDivisor;
    }

    private void writeInfo(Frame frame) {
        float nearHalf = frame.nearMap.spanBlocks() * 0.5f;
        float nearCentreX = (float) (frame.nearCornerX + nearHalf);
        float nearCentreZ = (float) (frame.nearCornerZ + nearHalf);
        Quality quality = frame.quality;
        info.rotate();
        try (GpuBufferSlice.MappedView view = info.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                .putVec4((float) frame.nearCornerX, (float) frame.nearCornerZ, frame.nearMap.texelBlocks(), nearHalf * NEAR_WHOLE)
                .putVec4((float) frame.farCornerX, (float) frame.farCornerZ, frame.farMap.texelBlocks(), nearHalf * NEAR_HANDED_OVER)
                .putVec4(nearCentreX, nearCentreZ, frame.fadeStart, frame.reach)
                .putVec4(frame.floorY, EXTINCTION, NOISE_PERIOD, quality.detailReach)
                .putVec4(frame.red, frame.green, frame.blue, frame.moonlight)
                .putVec4((float) frame.noiseX, (float) frame.noiseY, (float) frame.noiseZ, frameNumber)
                .putVec4(quality.steps, quality.growth, quality.lightSamples, quality.shortest);
        }
    }

    @Override
    public void close() {
        lastResolved = null;
        for (int i = 0; i < marches.length; i++) {
            if (marches[i] != null) {
                marches[i].close();
                marches[i] = null;
            }
            if (resolved[i] != null) {
                resolved[i][0].close();
                resolved[i][1].close();
                resolved[i] = null;
            }
        }
        info.close();
        if (noiseView != null) {
            noiseView.close();
            noiseView = null;
        }
        if (noise != null) {
            noise.close();
            noise = null;
        }
        if (lumpsView != null) {
            lumpsView.close();
            lumpsView = null;
        }
        if (lumps != null) {
            lumps.close();
            lumps = null;
        }
    }
}
