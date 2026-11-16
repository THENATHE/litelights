package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.RenderSettings;
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
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;

/**
 * Sunbeams: camera-facing billboard slices at growing distances, drawn into a texture at a quarter,
 * half or the full screen resolution (Sunbeam quality; Ultra also doubles the slices), each adding the sunlight the air scatters toward the camera where the sun shadow map
 * lets sunlight through. Under water the slices are closer together, the light is tinted and
 * absorbed with distance, and a moving wave pattern cuts it into shafts. The result is screen
 * blended onto the level (by the FXAA pass instead when FXAA is on) through a depth-aware upsample
 * (light_post/rays_composite.glsl), so beams don't spill across silhouettes.
 */
final class LightRaysPass implements PostPass {
    private static final RenderPipeline SLICES = PostPipelines.fullScreen("light_rays", "light_rays",
            PostPipelines.samplers("DepthSampler"), PostPipelines.HALF_FLOAT_ADD, 48)
        .withVertexShader(LiteLightsClient.id("post/light_rays"))
        .build();
    private static final RenderPipeline COMPOSITE = PostPipelines.fullScreen("light_rays_composite", "light_rays_composite",
        PostPipelines.samplers("RaysSampler", "DepthSampler"), PostPipelines.MAIN_TARGET_SCREEN, 32).build();

    private static final Optional<Vector4fc> CLEAR_TO_ZERO = Optional.of(new Vector4f());

    private static final int AIR_SLICES = 10;
    private static final float AIR_FIRST_SLICE = 1.5f;
    private static final float AIR_FARTHEST = 72.0f;
    private static final int WATER_SLICES = 10;
    private static final float WATER_FIRST_SLICE = 0.75f;
    private static final float WATER_FARTHEST = 28.0f;

    private final LightPostFrame lightPost;
    private final FxaaPass fxaa;
    /** Made again only when the quality's resolution changes. */
    private PostTexture rays = new PostTexture("light rays", GpuFormat.RGBA16_FLOAT, RenderSettings.SunbeamQuality.MEDIUM.divisor());
    private int raysDivisor = RenderSettings.SunbeamQuality.MEDIUM.divisor();
    private final Vector4f slices = new Vector4f();
    private final Vector4f light = new Vector4f();
    private final Vector4f lightColour = new Vector4f();
    private final Vector4f medium = new Vector4f();
    private boolean drawnThisFrame;

    LightRaysPass(LightPostFrame lightPost, FxaaPass fxaa) {
        this.lightPost = lightPost;
        this.fxaa = fxaa;
    }

    @Override
    public void draw(PostContext context) {
        drawnThisFrame = false;
        boolean underwater = lightPost.underwater();
        RenderFeature feature = underwater ? RenderFeature.UNDERWATER_RAYS : RenderFeature.LIGHT_RAYS;
        float intensity = intensity(context.settings().lighting(), underwater) * lightPost.thunderClear();
        if (intensity <= 0.0f || !RenderFeatureStatus.mayRun(feature) || !lightPost.lightAllowed() || !lightPost.overworldSky()
            || lightPost.sunPower() <= 0.01f || (!underwater && (!lightPost.sunMap() || lightPost.cameraSkyLight() == 0))) {
            return;
        }
        try {
            RenderSettings.SunbeamQuality quality = context.settings().lighting().sunbeamQuality();
            setUp(context.frame(), underwater, intensity * lightPost.sunPower(), context.settings().shadows().reach(), quality.sliceFactor());
            GpuTextureView target = texture(quality.divisor()).ensure(context.width(), context.height());
            try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights light rays", target, CLEAR_TO_ZERO);
                 MemoryStack stack = MemoryStack.stackPush()) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(SLICES));
                context.bindUniforms(pass);
                pass.setUniform("DepthSampler", context.sceneDepth(), PostContext.nearestSampler());
                ByteBuffer push = stack.malloc(48);
                slices.get(0, push);
                light.get(16, push);
                medium.get(32, push);
                pass.pushConstants(push);
                pass.draw(3, (int) slices.x, 0, 0);
            }
            drawnThisFrame = true;
            if (!fxaa.willRun(context.settings())) {
                composite(context);
            }
            RenderFeatureStatus.succeeded(feature);
        } catch (RuntimeException error) {
            drawnThisFrame = false;
            RenderFeatureStatus.failed(feature, error);
        }
    }

    private PostTexture texture(int divisor) {
        if (divisor != raysDivisor) {
            rays.close();
            rays = new PostTexture("light rays", GpuFormat.RGBA16_FLOAT, divisor);
            raysDivisor = divisor;
        }
        return rays;
    }

    private static float intensity(RenderSettings.Lighting lighting, boolean underwater) {
        if (underwater) {
            return lighting.underwaterSunbeams() ? lighting.underwaterSunbeamStrength() : 0.0f;
        }
        return lighting.sunbeams() ? lighting.sunbeamStrength() : 0.0f;
    }

    /**
     * Slice spacing, light colour and the medium. In air the beams are faint and scatter mostly
     * forward, strongest near dawn and dusk when the sun is low; under water they are denser, blue
     * green, fade with distance and scatter more evenly.
     */
    private void setUp(FrameState frame, boolean underwater, float strength, int shadowDistance, int sliceFactor) {
        // More slices split the same air more finely: each adds its own stretch, so the sum keeps
        // its brightness.
        if (underwater) {
            int count = WATER_SLICES * sliceFactor;
            float ratio = (float) Math.pow(WATER_FARTHEST / WATER_FIRST_SLICE, 1.0 / count);
            slices.set(count, WATER_FIRST_SLICE, ratio, WATER_FARTHEST);
            light.set(0.015f * strength, 0.0f, 0.0f, 0.45f);
            lightColour.set(frame.sunColor.x * 0.55f, frame.sunColor.y * 0.9f, frame.sunColor.z, 0.0f);
            medium.set(1.0f, 0.07f, 1.0f, 0.35f);
            return;
        }
        float farthest = Math.min(AIR_FARTHEST, shadowDistance * 0.9f);
        int count = AIR_SLICES * sliceFactor;
        float ratio = (float) Math.pow(farthest / AIR_FIRST_SLICE, 1.0 / count);
        slices.set(count, AIR_FIRST_SLICE, ratio, farthest);
        // A low sun lights a longer path of air and makes the beams stand out against the shade.
        float lowSun = 1.0f - Math.clamp(frame.sunDirection.w, 0.0f, 1.0f);
        light.set(0.006f * strength * (0.45f + 0.55f * lowSun), 0.0f, 0.0f, 0.65f);
        lightColour.set(frame.sunColor.x, frame.sunColor.y, frame.sunColor.z, 0.0f);
        medium.set(1.0f, 0.0f, 0.0f, 0.05f);
    }

    private void composite(PostContext context) {
        try (RenderPass pass = context.openFullScreen(() -> "LiteLights light rays composite", COMPOSITE, context.sceneColor());
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setUniform("RaysSampler", rays.view(), PostContext.nearestSampler());
            pass.setUniform("DepthSampler", context.sceneDepth(), PostContext.nearestSampler());
            ByteBuffer push = stack.malloc(32);
            push.putFloat(0, 1.0f / rays.width());
            push.putFloat(4, 1.0f / rays.height());
            push.putFloat(8, shadeGate() ? 1.0f : 0.0f);
            push.putFloat(12, 0.0f);
            lightColour.get(16, push);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
    }

    /** In air, rays show only along view lines that pass through shade (see light_rays.fsh). */
    boolean shadeGate() {
        return !lightPost.underwater();
    }

    /** Light rays were drawn this frame and still need adding to the level (by FXAA). */
    boolean drawnThisFrame() {
        return drawnThisFrame;
    }

    GpuTextureView raysView() {
        return rays.view();
    }

    /** The colour the scattered light takes when added to the level. */
    Vector4f lightColour() {
        return lightColour;
    }

    int raysWidth() {
        return rays.width();
    }

    int raysHeight() {
        return rays.height();
    }

    static RenderPipeline[] pipelines() {
        return new RenderPipeline[] {SLICES, COMPOSITE};
    }
}
