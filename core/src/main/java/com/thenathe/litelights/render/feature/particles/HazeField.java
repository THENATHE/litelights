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
import com.thenathe.litelights.config.LiteLightsOptions;
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
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The Mist field (Mist style: Mist field, fog097): instead of puffs, one low layer of drifting mist,
 * traced through right after the level at half resolution (post/mist_field.fsh, twelve fixed steps,
 * no loop over data), then laid over the picture at full resolution with an upsample that keeps the
 * edges of what stands in it (post/mist_field_composite.fsh), lit like the puffs, through the
 * lightmap.
 *
 * <p>The layer lies on the land (fog098): its bottom and top are heights over the ground at each
 * step of the trace, read from a small map of the land around the camera ({@link FieldGround}), so
 * it fills valleys, climbs hills, pools in hollows and rises in slow banks, and stays on the land
 * however high the camera flies. The Nether's ash haze fills the open air around the camera instead.
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
    /**
     * How fast the field's light follows the light around the camera, each tick: about three seconds
     * to settle, so walking past a torch or under a tree doesn't flicker the whole field (1.0.7).
     */
    private static final float LIGHT_EASE = 0.035f;
    /** The light is read at the camera and this far to each side of it. */
    private static final double LIGHT_SPREAD = 10.0;
    /** A further layer starts this share of the layer's height above the one below, so they overlap. */
    private static final float LAYER_STEP = 0.85f;
    /** Soft edges the shader gives the layer, in blocks below its bottom and as a share of its height above its top (mist_field.fsh). */
    private static final float BOTTOM_SOFTNESS = 1.5f;
    private static final float TOP_SOFTNESS = 0.45f;

    private static final int PUSH_BYTES = 80;
    private static final BlendFunction OVER_BY_TRANSMITTANCE = new BlendFunction(BlendFactor.ONE, BlendFactor.SRC_ALPHA,
        BlendFactor.ZERO, BlendFactor.ONE);
    static final RenderPipeline MARCH = PostPipelines.fullScreen("mist_field", LiteLightsClient.id("post/mist_field"),
            PostPipelines.samplers("FieldDepth", "FieldGround"), PostPipelines.HALF_FLOAT, PUSH_BYTES)
        .build();
    static final RenderPipeline COMPOSITE = PostPipelines.fullScreen("mist_field_composite", LiteLightsClient.id("post/mist_field_composite"),
            PostPipelines.samplers("FieldMarch", "FieldDepth", "FieldLightmap"),
            new ColorTargetState(Optional.of(OVER_BY_TRANSMITTANCE), GpuFormat.RGBA8_UNORM, PostPipelines.WRITE_RGB), PUSH_BYTES)
        .build();

    // This tick's offers, summed: extinction, extinction-weighted colour, layer, dust share and how
    // much of it lies on the land.
    private float offeredExtinction;
    private float offeredRed;
    private float offeredGreen;
    private float offeredBlue;
    private float offeredBottom;
    private float offeredTop;
    private float offeredDust;
    private float offeredOnLand;
    private int offeredLight;

    // Eased toward the offers once a tick; frames take the value between the last two ticks.
    // 0 extinction, 1-3 colour, 4-5 the layer's bottom and top over its base, 6 dust share, 7 on the land.
    private final float[] now = new float[8];
    private final float[] previous = new float[8];
    private final float[] target = new float[8];
    private final FieldGround ground = new FieldGround();
    private final ByteBuffer push = ByteBuffer.allocateDirect(PUSH_BYTES).order(ByteOrder.nativeOrder());
    private final PostTexture march = new PostTexture("LiteLights mist field", GpuFormat.RGBA16_FLOAT, 2);
    /** The light the field is lit with, block and sky (vanilla's light coordinates, 0 to 240), eased each tick. */
    private float blockLight;
    private float skyLight;
    private float previousBlockLight;
    private float previousSkyLight;
    private boolean lightKnown;
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
        offeredBottom = 0.0f;
        offeredTop = 0.0f;
        offeredDust = 0.0f;
        offeredOnLand = 0.0f;
    }

    /**
     * One kind's haze this tick: its extinction per block, its colour (rgb, alpha ignored), the
     * heights its layer spans, and the light there (packed as vanilla's light coordinates). The
     * heights are over the ground wherever the trace goes, or for the Nether's ash haze, which fills
     * the open air around the camera, over the camera.
     */
    public void offer(ParticleKind.Haze kind, float extinction, int rgb, float bottom, float top, int lightCoords) {
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
        if (kind != ParticleKind.Haze.ASH) {
            offeredOnLand += extinction;
        }
        offeredLight = lightCoords;
    }

    /**
     * After the spawners: eases toward this tick's offers and, while the field lies on the land,
     * brings the map of the land under it up to date.
     */
    void endTick(boolean fieldChosen, Surroundings s) {
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
            target[4] = offeredBottom * weight;
            target[5] = offeredTop * weight;
            target[6] = offeredDust * weight;
            target[7] = offeredOnLand * weight;
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
        previousBlockLight = blockLight;
        previousSkyLight = skyLight;
        if (!active) {
            lightKnown = false;
        } else if (offeredExtinction > 0.0f) {
            steadyLight(s);
        }
        if (active && now[7] > 0.0f && s.level != null) {
            // Under a roof the camera's column is the roof: the fill for cells nothing is known of
            // is just under the camera then, as the spawners' field ground was.
            int x = (int) Math.floor(s.cameraX);
            int z = (int) Math.floor(s.cameraZ);
            double cameraGround = s.level.hasChunk(x >> 4, z >> 4)
                ? Math.min(s.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), s.cameraY - 1.62)
                : s.cameraY - 1.62;
            ground.update(s.level, s.cameraX, s.cameraY, s.cameraZ, cameraGround, s.hazeDriftX, s.hazeDriftZ);
        }
    }

    /**
     * The field is one colour over a hundred blocks and more, so it can't be lit by the one spot the
     * camera stands on: a torch in the hand or a tree overhead would brighten or dim all of it at
     * once. Its sky light is the most found at the camera and four spots around it (the open sky's,
     * unless the camera is well indoors), its block light their mean, and both are eased.
     */
    private void steadyLight(Surroundings s) {
        float sky = offeredLight >>> 16 & 0xFFFF;
        float block = offeredLight & 0xFFFF;
        int samples = 1;
        if (s.level != null) {
            for (int side = 0; side < 4; side++) {
                double x = s.cameraX + (side == 0 ? LIGHT_SPREAD : side == 1 ? -LIGHT_SPREAD : 0.0);
                double z = s.cameraZ + (side == 2 ? LIGHT_SPREAD : side == 3 ? -LIGHT_SPREAD : 0.0);
                int blockX = (int) Math.floor(x);
                int blockZ = (int) Math.floor(z);
                if (!s.level.hasChunk(blockX >> 4, blockZ >> 4)) {
                    continue;
                }
                double y = s.level.dimensionType().hasSkyLight()
                    ? s.level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ) + 1.0
                    : s.cameraY;
                int packed = s.lightAt(x, y, z);
                sky = Math.max(sky, packed >>> 16 & 0xFFFF);
                block += packed & 0xFFFF;
                samples++;
            }
        }
        block /= samples;
        if (!lightKnown) {
            blockLight = block;
            skyLight = sky;
            previousBlockLight = block;
            previousSkyLight = sky;
            lightKnown = true;
            return;
        }
        blockLight += (block - blockLight) * LIGHT_EASE;
        skyLight += (sky - skyLight) * LIGHT_EASE;
    }

    /** How far over the ground the field reaches, in blocks: the Mist distance row. */
    private static float reach() {
        return LiteLightsOptions.MIST_DISTANCE.get();
    }

    /** How many layers of mist lie on one another, the first on the ground: the Mist layers row. */
    private static int layers() {
        return LiteLightsOptions.MIST_LAYERS.get();
    }

    /** Clears everything, as when leaving a world. */
    void clear() {
        lightKnown = false;
        beginTick();
        Arrays.fill(now, 0.0f);
        Arrays.fill(previous, 0.0f);
        ground.forget();
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
            float cameraY = frame.cameraBlockY + frame.cameraFraction.y();
            double cameraX = frame.cameraBlockX + frame.cameraFraction.x();
            double cameraZ = frame.cameraBlockZ + frame.cameraFraction.z();
            float onLand = at(7, partialTick);
            float bottom = at(4, partialTick);
            float top = at(5, partialTick);
            // The slab the layer can be anywhere in, camera-relative, so the trace only steps through
            // that: over the map's lowest and highest land, or around the camera for ash haze.
            float referenceY = ground.referenceY() - cameraY;
            float low = onLand * (referenceY + ground.lowestGround()) + bottom - BOTTOM_SOFTNESS;
            // highestTop counts banks and pooling in full, more than the shader raises the top by.
            int layers = layers();
            float reach = Math.min(reach(), FieldGround.BLOCKS * 0.5f);
            float tallest = (top - bottom + onLand * FieldGround.MOST_RAISED) * (1.0f + LAYER_STEP * (layers - 1));
            float high = onLand * (referenceY + ground.highestTop()) + bottom + tallest + Math.max(TOP_SOFTNESS * tallest, 0.5f);
            push.putFloat(0, at(1, partialTick)).putFloat(4, at(2, partialTick)).putFloat(8, at(3, partialTick)).putFloat(12, extinction);
            push.putFloat(16, bottom).putFloat(20, top).putFloat(24, at(6, partialTick)).putFloat(28, reach);
            // Vanilla's light coordinates: block light and sky light, each 0 to 240.
            push.putFloat(32, previousBlockLight + (blockLight - previousBlockLight) * partialTick)
                .putFloat(36, previousSkyLight + (skyLight - previousSkyLight) * partialTick).putFloat(40, onLand).putFloat(44, referenceY);
            push.putFloat(64, layers).putFloat(68, LAYER_STEP).putFloat(72, 0.0f).putFloat(76, 0.0f);
            push.putFloat(48, (float) (ground.originX() - cameraX)).putFloat(52, (float) (ground.originZ() - cameraZ))
                .putFloat(56, low).putFloat(60, high);
            GpuTextureView marched = march.ensure(context.width(), context.height());
            GpuTextureView groundView = ground.view();
            try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights mist field", marched, Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(MARCH));
                context.bindUniforms(pass);
                pass.setUniform("FieldDepth", context.sceneDepth(), PostContext.nearestSampler());
                pass.setUniform("FieldGround", groundView, PostContext.linearSampler());
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
