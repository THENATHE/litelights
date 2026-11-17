package com.thenathe.litelights.render.feature.particles;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import com.thenathe.litelights.render.post.LitePostChain;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPipelines;
import com.thenathe.litelights.render.post.PostSlot;
import com.thenathe.litelights.render.post.PostTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.client.Minecraft;

/**
 * The Mist field (Mist style: Mist field, fog097): instead of puffs, one low layer of drifting mist,
 * traced through right after the level at half resolution (post/mist_field.fsh, eight fixed steps,
 * no loop over data), then laid over the picture at full resolution with an upsample that keeps the
 * edges of what stands in it (post/mist_field_composite.fsh), lit like the puffs, through the
 * lightmap.
 *
 * <p>The spawners still decide where the haze is and how thick: with the field chosen they offer
 * what they would have spawned instead of spawning it ({@link #offer}). Puffs at that rate, size and
 * opacity spread over their spawn area and layer would cover {@link #extinction} of a line of sight
 * per block, so the field fills the air about as much as the puffs did. Kinds that come together
 * (a desert's dust clouds and low drifts) add up into the one layer.
 *
 * <p>Everything moves on with the client ticks (the offers each tick, an easing toward them, the
 * drift in {@link HazeDrift}), so the field stops while the game is paused.
 */
public final class HazeField {
    private static final HazeField INSTANCE = new HazeField();

    /** Share of the gap to this tick's offers closed each tick: about two seconds to settle. */
    private static final float EASE = 0.05f;
    /** A billboard's sprite covered about this much fully opaque area, in multiples of its size squared (haze sprites, fog097). */
    private static final float SPRITE_COVER = 0.64f;
    /** Extinction per block around which piled-up haze stops adding up (see {@link #saturated}). */
    private static final float SATURATION = 0.15f;
    /** Haze further than this is left to the fog: the puffs' kinds keep their Near area too. */
    private static final float REACH = 48.0f;

    private static final BlendFunction OVER_BY_TRANSMITTANCE = new BlendFunction(BlendFactor.ONE, BlendFactor.SRC_ALPHA,
        BlendFactor.ZERO, BlendFactor.ONE);
    static final RenderPipeline MARCH = PostPipelines.fullScreen("mist_field", LiteLightsClient.id("post/mist_field"),
            PostPipelines.samplers("FieldDepth"), PostPipelines.HALF_FLOAT, 64)
        .build();
    static final RenderPipeline COMPOSITE = PostPipelines.fullScreen("mist_field_composite", LiteLightsClient.id("post/mist_field_composite"),
            PostPipelines.samplers("FieldMarch", "FieldDepth", "FieldLightmap"),
            new ColorTargetState(Optional.of(OVER_BY_TRANSMITTANCE), GpuFormat.RGBA8_UNORM, PostPipelines.WRITE_RGB), 64)
        .build();

    // This tick's offers, summed: extinction, extinction-weighted colour, layer and dust share.
    private float offeredExtinction;
    private float offeredRed;
    private float offeredGreen;
    private float offeredBlue;
    private double offeredBottom;
    private double offeredTop;
    private float offeredDust;
    private int offeredLight;

    // Eased toward the offers once a tick; frames take the value between the last two ticks.
    private final float[] now = new float[7];
    private final float[] previous = new float[7];
    private final float[] target = new float[7];
    private final ByteBuffer push = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
    private final PostTexture march = new PostTexture("LiteLights mist field", GpuFormat.RGBA16_FLOAT, 2);
    private int light;
    private boolean active;

    private HazeField() {
    }

    public static HazeField get() {
        return INSTANCE;
    }

    void register() {
        PipelineOwners.claim(RenderFeature.PARTICLES, MARCH, COMPOSITE);
        LitePostChain.register(PostSlot.MIST_FIELD, this::draw);
    }

    /** Whether the spawners should offer their haze to the field rather than spawn it. */
    public static boolean wanted(RenderSettings settings) {
        return settings.weather().mistStyle() == RenderSettings.MistStyle.FIELD;
    }

    /**
     * The extinction per block that puffs would add on average across their layer: how many there
     * are at once, over the layer's volume, times the opaque area each covers.
     */
    public static float extinction(float puffsPerTick, float lifetimeTicks, float alpha, float size, float floorArea, float layerHeight) {
        if (floorArea <= 0.0f || layerHeight <= 0.0f) {
            return 0.0f;
        }
        return puffsPerTick * lifetimeTicks / (floorArea * layerHeight) * alpha * SPRITE_COVER * size * size;
    }

    /**
     * The field's extinction for what the puffs would add. Light haze passes nearly as it is; thick
     * haze much less than its sum, as the puffs never filled the air evenly: they fade out near the
     * eye, half of each lies under the ground, and in a storm they pile up over one another where
     * one would already hide what is behind. Matched against the puffs and billboards in rain, a
     * blizzard and a haboob (fog097-shots).
     */
    static float saturated(float extinction) {
        return SATURATION * (float) Math.log1p(extinction / SATURATION);
    }

    /** The floor area of a ring around the camera, from {@code inner} to {@code outer} blocks out. */
    public static float ring(float inner, float outer) {
        return (float) Math.PI * (outer * outer - inner * inner);
    }

    void beginTick() {
        offeredExtinction = 0.0f;
        offeredRed = 0.0f;
        offeredGreen = 0.0f;
        offeredBlue = 0.0f;
        offeredBottom = 0.0;
        offeredTop = 0.0;
        offeredDust = 0.0f;
    }

    /**
     * One kind's haze this tick: its extinction per block, its colour (rgb, alpha ignored), the world
     * heights its layer spans, and the light there (packed as vanilla's light coordinates).
     */
    public void offer(ParticleKind.Haze kind, float extinction, int rgb, double bottom, double top, int lightCoords) {
        if (extinction <= 0.0f || top <= bottom) {
            return;
        }
        offeredExtinction += extinction;
        offeredRed += extinction * ((rgb >> 16) & 255) / 255.0f;
        offeredGreen += extinction * ((rgb >> 8) & 255) / 255.0f;
        offeredBlue += extinction * (rgb & 255) / 255.0f;
        offeredBottom += extinction * bottom;
        offeredTop += extinction * top;
        if (kind == ParticleKind.Haze.SAND) {
            offeredDust += extinction;
        }
        offeredLight = lightCoords;
    }

    /** After the spawners: eases toward this tick's offers. */
    void endTick(boolean fieldChosen) {
        System.arraycopy(now, 0, previous, 0, now.length);
        if (!fieldChosen) {
            beginTick();
        }
        Arrays.fill(target, 0.0f);
        if (offeredExtinction > 0.0f) {
            float weight = 1.0f / offeredExtinction;
            target[0] = saturated(offeredExtinction);
            target[1] = offeredRed * weight;
            target[2] = offeredGreen * weight;
            target[3] = offeredBlue * weight;
            // World heights: a float holds them to well under a block's width, and the camera's own
            // height comes off each frame, so the layer stays put while the camera moves.
            target[4] = (float) (offeredBottom * weight);
            target[5] = (float) (offeredTop * weight);
            target[6] = offeredDust * weight;
            light = offeredLight;
        }
        boolean empty = now[0] < 1.0e-4f;
        for (int i = 0; i < now.length; i++) {
            // Colour and layer jump to the first offer rather than easing in from nothing; extinction always eases.
            if (i == 0 || !empty && target[0] > 0.0f) {
                now[i] += (target[i] - now[i]) * EASE;
            } else if (target[0] > 0.0f) {
                now[i] = target[i];
            }
        }
        active = fieldChosen && (now[0] > 1.0e-4f || offeredExtinction > 0.0f);
    }

    /** Clears everything, as when leaving a world. */
    void clear() {
        beginTick();
        Arrays.fill(now, 0.0f);
        Arrays.fill(previous, 0.0f);
        active = false;
    }

    /** QA: the field's extinction per block now, 0 when it isn't drawn. */
    public float extinctionNow() {
        return active ? now[0] : 0.0f;
    }

    private float at(int index, float partialTick) {
        return previous[index] + (now[index] - previous[index]) * partialTick;
    }

    private void draw(PostContext context) {
        if (!active || !RenderFeatureStatus.mayRun(RenderFeature.PARTICLES) || !wanted(context.settings())) {
            return;
        }
        FrameState frame = context.frame();
        if ((frame.featureFlags & FrameFlags.FEATURE_CAMERA_UNDERWATER) != 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float extinction = at(0, partialTick);
        if (extinction < 1.0e-4f) {
            return;
        }
        var lightmap = minecraft.gameRenderer.lightmap();
        if (lightmap == null) {
            return;
        }
        try {
            push.putFloat(0, at(1, partialTick)).putFloat(4, at(2, partialTick)).putFloat(8, at(3, partialTick)).putFloat(12, extinction);
            float cameraY = frame.cameraBlockY + frame.cameraFraction.y();
            push.putFloat(16, at(4, partialTick) - cameraY).putFloat(20, at(5, partialTick) - cameraY).putFloat(24, at(6, partialTick)).putFloat(28, REACH);
            // Vanilla's light coordinates: block light and sky light, each 0 to 240.
            push.putFloat(32, light & 0xFFFF).putFloat(36, light >>> 16 & 0xFFFF).putFloat(40, 0.0f).putFloat(44, 0.0f);
            push.putFloat(48, 0.0f).putFloat(52, 0.0f).putFloat(56, 0.0f).putFloat(60, 0.0f);
            GpuTextureView marched = march.ensure(context.width(), context.height());
            try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights mist field", marched, Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(MARCH));
                context.bindUniforms(pass);
                pass.setUniform("FieldDepth", context.sceneDepth(), PostContext.nearestSampler());
                pass.pushConstants(push);
                pass.draw(3, 1, 0, 0);
            }
            try (RenderPass pass = context.openFullScreen(() -> "LiteLights mist field composite", COMPOSITE, context.sceneColor())) {
                pass.setUniform("FieldMarch", marched, PostContext.nearestSampler());
                pass.setUniform("FieldDepth", context.sceneDepth(), PostContext.nearestSampler());
                pass.setUniform("FieldLightmap", lightmap, PostContext.linearSampler());
                pass.pushConstants(push);
                pass.draw(3, 1, 0, 0);
            }
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.PARTICLES, error);
        }
    }
}
