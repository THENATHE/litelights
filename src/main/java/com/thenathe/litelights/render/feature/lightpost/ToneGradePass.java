package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPass;
import com.thenathe.litelights.render.post.PostPipelines;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

/**
 * The single full-resolution tonemap pass, after the hand and the screen overlays: bloom added as
 * light, the colour grade, the tone curve, and alpha back to 1. Runs only when bloom drew this
 * frame, grading is on or a film curve is chosen; with all three off it costs nothing. Bloom's
 * blurs are finished here first (see {@link BloomPass}); the level's glow is not added over the
 * hand, only the hand's own.
 */
final class ToneGradePass implements PostPass {
    private static final RenderPipeline PIPELINE = PostPipelines.fullScreen("tonemap", "tonemap",
        PostPipelines.samplers("SceneSampler", "BloomNearSampler", "BloomWideSampler", "HandDepthSampler"), PostPipelines.MAIN_TARGET,
        ColourGradeMath.PARAMETER_COUNT * 4).build();

    private static final int FLAG_GRADE = 1;
    private static final int FLAG_BLOOM = 2;
    private static final int FLAG_SHOW_BLOOM = 4;
    private static final boolean SHOW_BLOOM = Boolean.getBoolean("litelights.debug.showBloom");
    private static final int FLAG_SHOW_ALPHA = 8;
    private static final boolean SHOW_ALPHA = Boolean.getBoolean("litelights.debug.showAlpha");
    /** The tone curve's number goes in bits 4 and 5 of the flags. */
    private static final int CURVE_SHIFT = 4;
    /** Bit 6: a hand was drawn this frame and HandDepthSampler is its depth. */
    private static final int FLAG_HAND = 64;
    private static final int BLOOM_STRENGTH = 18;
    private static final int FLAGS = 19;

    private final LightPostFrame lightPost;
    private final BloomPass bloom;
    private final ColourGradeMath math = new ColourGradeMath();
    private final float[] grade = new float[ColourGradeMath.GRADE_VALUES];
    private final float[] parameters = new float[ColourGradeMath.PARAMETER_COUNT];

    ToneGradePass(LightPostFrame lightPost, BloomPass bloom) {
        this.lightPost = lightPost;
        this.bloom = bloom;
    }

    @Override
    public void draw(PostContext context) {
        RenderSettings settings = context.settings();
        boolean grading = settings.colorGrading().schedule() != RenderSettings.GradeSchedule.OFF
            && RenderFeatureStatus.mayRun(RenderFeature.COLOUR_GRADING);
        boolean withBloom = bloom.finish(context);
        GpuTextureView handDepth = bloom.handDepth();
        // A film curve runs whether or not the grade does; the Vanilla-friendly one only matters with the grade.
        int curve = RenderFeatureStatus.mayRun(RenderFeature.COLOUR_GRADING)
            ? ColourGradeMath.curveNumber(settings.colorGrading().toneCurve()) : ColourGradeMath.CURVE_VANILLA_FRIENDLY;
        if (!grading && !withBloom && curve == ColourGradeMath.CURVE_VANILLA_FRIENDLY) {
            return;
        }
        try {
            if (grading) {
                // Outside the Overworld there is no day: the midday grade stands for all of it.
                double dayTime = lightPost.overworldSky() ? lightPost.dayTime() : 6_000.0;
                math.gradeAt(settings.colorGrading(), dayTime, grade);
                math.computeParameters(grade, parameters);
            } else {
                // Nothing passes white without the grade, so the roll-off has nothing to do.
                parameters[ColourGradeMath.ROLLOFF_MAX] = 1.0f;
            }
            math.computeCurve(curve, Math.max(parameters[ColourGradeMath.ROLLOFF_MAX], 1.0f), parameters);
            parameters[BLOOM_STRENGTH] = withBloom ? 0.85f * settings.lighting().haloStrength() : 0.0f;
            parameters[FLAGS] = (grading ? FLAG_GRADE : 0) | (withBloom ? FLAG_BLOOM : 0) | (withBloom && SHOW_BLOOM ? FLAG_SHOW_BLOOM : 0)
                | (SHOW_ALPHA ? FLAG_SHOW_ALPHA : 0) | curve << CURVE_SHIFT | (handDepth != null ? FLAG_HAND : 0);

            GpuTextureView scene = context.copyOfScene();
            try (RenderPass pass = context.openFullScreen(() -> "LiteLights tonemap", PIPELINE, context.sceneColor());
                 MemoryStack stack = MemoryStack.stackPush()) {
                pass.setUniform("SceneSampler", scene, PostContext.nearestSampler());
                pass.setUniform("BloomNearSampler", withBloom ? bloom.nearView() : scene, PostContext.linearSampler());
                pass.setUniform("BloomWideSampler", withBloom ? bloom.wideView() : scene, PostContext.linearSampler());
                // Without a hand the flag is off and the shader never reads it; the level's depth stands in.
                pass.setUniform("HandDepthSampler", handDepth != null ? handDepth : context.sceneDepth(), PostContext.nearestSampler());
                ByteBuffer push = stack.malloc(ColourGradeMath.PARAMETER_COUNT * 4);
                for (int index = 0; index < ColourGradeMath.PARAMETER_COUNT; index++) {
                    push.putFloat(index * 4, parameters[index]);
                }
                pass.pushConstants(push);
                pass.draw(3, 1, 0, 0);
            }
            if (grading || curve != ColourGradeMath.CURVE_VANILLA_FRIENDLY) {
                RenderFeatureStatus.succeeded(RenderFeature.COLOUR_GRADING);
            }
        } catch (RuntimeException error) {
            boolean colour = grading || curve != ColourGradeMath.CURVE_VANILLA_FRIENDLY;
            RenderFeatureStatus.failed(colour ? RenderFeature.COLOUR_GRADING : RenderFeature.BLOOM, error);
        }
    }

    static RenderPipeline pipeline() {
        return PIPELINE;
    }
}
