package com.thenathe.litelights.render.feature.weather;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.PreviewEvent;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import com.thenathe.litelights.render.post.PostPipelines;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/**
 * The night sky's events: an aurora over cold biomes, meteor showers and distant fireworks. Drawn
 * into the main target just after vanilla's sky and before terrain, so terrain and clouds cover
 * them and they sit at the sky's distance.
 *
 * <p>Which nights bring what, and when each meteor and burst happens, comes from
 * {@link SkyEventSchedule}, and the aurora's curtains from {@link AuroraCurtains}; this class only
 * turns that into at most several hundred quads a frame. The vertex data goes into one reused
 * native buffer and one GPU buffer that grows only if needed.
 */
public final class SkyEvents {
    /** The sky's distance in blocks, inside vanilla's sky (the sun and moon sit at 100). */
    private static final float SKY_RADIUS = 95.0f;
    private static final int SPARKS_PER_BURST = 40;
    /** Each spark draws itself and a short trail of earlier positions. */
    private static final int TRAIL = 4;
    /** At most two meteors and three bursts show at once; a meteor is a streak and a head, a burst its sparks, a flash and a glow. */
    private static final int MAX_QUADS = AuroraCurtains.MAX_QUADS + 3 * 2 + 4 * (SPARKS_PER_BURST * TRAIL + 2);
    /** Firework glows are told apart from sparks in the shader by their texture coordinates, which start here. */
    private static final float GLOW_UV = 10.0f;
    private static final int VERTEX_SIZE = DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();

    /** The colour modulator of the sky-event draws. Never changed; the API only takes a mutable vector. */
    private static final Vector4f WHITE = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final int[] FIREWORK_COLORS = {0xFF4A4A, 0xFFC93C, 0x5CFF7A, 0x52B8FF, 0xD66BFF, 0xFFFFFF};
    /**
     * The AFTER_SKY phase the events draw in: after the default phase, where LiteLights' own sky
     * pass draws, because that pass covers the whole sky with the Computed sky colours and during
     * the settings screen's time preview, and would hide the events drawn under it.
     */
    public static final Identifier DRAW_PHASE = LiteLightsClient.id("sky_events");

    /**
     * The aurora's blend. Its shader already multiplies its colour by its brightness, so it is added
     * as is; sparks add light scaled by their alpha ({@link PostPipelines#ADD_KEEP_ALPHA}). Both
     * leave the main target's alpha (light-post's bloom mask) as it was.
     */
    private static final BlendFunction ADD = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE);

    private static RenderPipeline.Builder base(BlendFunction blend) {
        return RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader(LiteLightsClient.id("sky/events"))
            .withFragmentShader(LiteLightsClient.id("sky/events"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(blend));
    }

    private static final RenderPipeline AURORA_PIPELINE = base(ADD)
        .withLocation(LiteLightsClient.id("pipeline/sky_aurora"))
        .withShaderDefine("AURORA")
        .build();
    private static final RenderPipeline SPARKS_PIPELINE = base(PostPipelines.ADD_KEEP_ALPHA)
        .withLocation(LiteLightsClient.id("pipeline/sky_sparks"))
        .build();

    private final ByteBuffer vertices = MemoryUtil.memAlloc(MAX_QUADS * 4 * VERTEX_SIZE);
    private final float[] event = new float[6];
    private final AuroraCurtains curtains = new AuroraCurtains();
    private final AuroraCurtains.Sink sink = this::vertex;
    private GpuBuffer gpuBuffer;
    /** Tonight's events; while the settings screen previews one, the preview's night instead. */
    private SkyEventSchedule.Night night;
    private SkyEventSchedule.Night scheduled;
    private int auroraQuads;
    private int sparkQuads;
    private long lastFireworkSound = Long.MIN_VALUE;

    // Inputs for this frame, set by update() on the client thread before the level draws.
    private float auroraStrength;
    private boolean meteorsOn;
    private boolean fireworksOn;
    private long seed;
    private long nightSeed;
    private double gameTime;
    private float visibility;

    /** What was drawn last frame, for the QA probe. */
    public int lastAuroraQuads() {
        return auroraQuads;
    }

    public int lastSparkQuads() {
        return sparkQuads;
    }

    public float auroraStrength() {
        return auroraStrength;
    }

    /** Compiles both pipelines now, so a shader problem shows on the first frame rather than on the first event night. */
    public void compilePipelines() {
        RenderSystem.getCompiledPipeline(AURORA_PIPELINE);
        RenderSystem.getCompiledPipeline(SPARKS_PIPELINE);
    }

    /** A sky-event shader that fails to compile is reported on the aurora row, the sky events' lead. */
    public static void claimPipelines() {
        PipelineOwners.claim(RenderFeature.SKY_EVENTS, AURORA_PIPELINE, SPARKS_PIPELINE);
    }

    /**
     * Decides this frame's events. {@code coldness} is 1 in snowy biomes and 0 in warm ones;
     * {@code visibility} is how dark and clear the sky is (0 by day or under heavy rain).
     */
    public void update(RenderSettings.Weather settings, boolean skyAllowed, long worldSeed, long clockTime, double gameTicks,
                       float coldness, float visibility) {
        seed = worldSeed;
        gameTime = gameTicks;
        long index = SkyEventSchedule.nightIndex(clockTime);
        if (scheduled == null || scheduled.index() != index || nightSeed != worldSeed) {
            scheduled = SkyEventSchedule.night(worldSeed, index);
            nightSeed = worldSeed;
        }
        night = scheduled;
        boolean show = skyAllowed && visibility > 0.01f;
        if (!skyAllowed) {
            // No sky pass runs here (the Nether, the End), so nothing will reset these.
            auroraQuads = 0;
            sparkQuads = 0;
        }
        PreviewEvent.Event preview = PreviewEvent.current();
        if (preview != null) {
            // The settings screen previews one event tonight, wherever the player is and in front
            // of them; the night's own events are left out meanwhile so the preview shows just the one.
            night = SkyEventSchedule.preview(index, preview, PreviewEvent.bearing(), PreviewEvent.elevation());
            auroraStrength = show && settings.polarLights() ? night.auroraStrength() * visibility : 0.0f;
            meteorsOn = show && settings.meteorNights() && night.meteorShower();
            fireworksOn = show && settings.farFireworks() && night.fireworks();
        } else {
            auroraStrength = show && settings.polarLights() ? night.auroraStrength() * coldness * visibility : 0.0f;
            meteorsOn = show && settings.meteorNights() && night.meteorShower();
            fireworksOn = show && settings.farFireworks() && night.fireworks() && SkyEventSchedule.fireworksHour(clockTime);
        }
        this.visibility = visibility;
    }

    /**
     * Whether a firework burst started at this game tick: the client thread plays its far-off
     * bang a second and a half later, as sound from a distant town would take that long to arrive.
     * Returns the burst's azimuth through {@code out[0]}.
     */
    public boolean burstStartedAt(long tick, float[] out) {
        if (!fireworksOn || tick % SkyEventSchedule.FIREWORK_SLOT_TICKS != 0 || tick == lastFireworkSound) {
            return false;
        }
        if (!SkyEventSchedule.firework(seed, night, tick / SkyEventSchedule.FIREWORK_SLOT_TICKS, out)) {
            return false;
        }
        lastFireworkSound = tick;
        return true;
    }

    /** From LiteRenderEvents.AFTER_SKY: builds and draws this frame's events, if any. */
    public void draw(RenderTarget target, FrameState frame) {
        auroraQuads = 0;
        sparkQuads = 0;
        if (auroraStrength <= 0.0f && !meteorsOn && !fireworksOn) {
            return;
        }
        vertices.clear();
        if (auroraStrength > 0.0f) {
            buildAurora(frame.time.y);
        }
        int auroraVertices = vertices.position() / VERTEX_SIZE;
        if (meteorsOn) {
            buildMeteors();
        }
        if (fireworksOn) {
            buildFireworks();
        }
        int totalVertices = vertices.position() / VERTEX_SIZE;
        auroraQuads = auroraVertices / 4;
        sparkQuads = (totalVertices - auroraVertices) / 4;
        if (totalVertices == 0) {
            return;
        }
        vertices.flip();
        var device = RenderSystem.getDevice();
        if (gpuBuffer == null || gpuBuffer.size() < vertices.capacity()) {
            if (gpuBuffer != null) {
                gpuBuffer.close();
            }
            gpuBuffer = device.createBuffer(() -> "LiteLights sky events", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                vertices.capacity());
        }
        var encoder = device.createCommandEncoder();
        encoder.writeToBuffer(gpuBuffer.slice(0L, vertices.remaining()), vertices);

        var quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = quadIndices.getBuffer(totalVertices / 4 * 6);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
            .writeTransform(RenderSystem.getModelViewMatrixCopy(), WHITE);
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights sky events", target.getColorTextureView(),
            Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, gpuBuffer.slice());
            pass.setIndexBuffer(indexBuffer, quadIndices.type());
            if (auroraVertices > 0) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(AURORA_PIPELINE));
                pass.drawIndexed(auroraVertices / 4 * 6, 1, 0, 0, 0);
            }
            if (totalVertices > auroraVertices) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(SPARKS_PIPELINE));
                pass.drawIndexed((totalVertices - auroraVertices) / 4 * 6, 1, auroraVertices / 4 * 6, 0, 0);
            }
        }
    }

    /** The night's curtains, shaped by its hash; previewed, they face the way the player looked. */
    private void buildAurora(float seconds) {
        long shape = SkyEventSchedule.mix(seed, night.index() ^ 0x6175726FL);
        curtains.build(shape, night.auroraStrength(), auroraStrength, night.auroraBearing(), seconds, sink);
    }

    private void buildMeteors() {
        long newestSlot = Math.floorDiv((long) gameTime, SkyEventSchedule.METEOR_SLOT_TICKS);
        long oldestSlot = Math.floorDiv((long) gameTime - SkyEventSchedule.METEOR_TICKS, SkyEventSchedule.METEOR_SLOT_TICKS);
        for (long slot = oldestSlot; slot <= newestSlot; slot++) {
            if (!SkyEventSchedule.meteor(seed, night, slot, event)) {
                continue;
            }
            double age = gameTime - slot * SkyEventSchedule.METEOR_SLOT_TICKS;
            if (age < 0.0 || age > SkyEventSchedule.METEOR_TICKS) {
                continue;
            }
            float progress = (float) (age / SkyEventSchedule.METEOR_TICKS);
            boolean fireball = event[5] > 0.5f;
            float heading = event[3];
            float travel = event[2] * progress;
            float elevationScale = 1.0f / Math.max(0.3f, (float) Math.cos(event[1]));
            float headAzimuth = event[0] + (float) Math.cos(heading) * travel * elevationScale;
            float headElevation = event[1] + (float) Math.sin(heading) * travel;
            // The trail grows quickly to most of the way back to where the meteor started.
            float tail = event[2] * 0.75f * Math.min(1.0f, progress * 2.5f);
            float tailAzimuth = headAzimuth - (float) Math.cos(heading) * tail * elevationScale;
            float tailElevation = headElevation - (float) Math.sin(heading) * tail;
            float brightness = Math.min(1.0f, event[4] * (float) Math.sqrt(Math.sin(Math.PI * progress)) * (fireball ? 1.3f : 1.0f)) * visibility;
            streak(tailAzimuth, tailElevation, headAzimuth, headElevation, fireball ? 1.3f : 0.85f, 0xFFF4DC, brightness);
            // A bright head leads the trail; a fireball's burns green.
            spark(headAzimuth, headElevation, fireball ? 2.0f : 1.3f, fireball ? 0xC8FFD2 : 0xFFFBEE, brightness);
        }
    }

    private void buildFireworks() {
        long newestSlot = Math.floorDiv((long) gameTime, SkyEventSchedule.FIREWORK_SLOT_TICKS);
        long oldestSlot = Math.floorDiv((long) gameTime - SkyEventSchedule.FIREWORK_TICKS, SkyEventSchedule.FIREWORK_SLOT_TICKS);
        for (long slot = oldestSlot; slot <= newestSlot; slot++) {
            if (!SkyEventSchedule.firework(seed, night, slot, event)) {
                continue;
            }
            double age = gameTime - slot * SkyEventSchedule.FIREWORK_SLOT_TICKS;
            if (age < 0.0 || age > SkyEventSchedule.FIREWORK_TICKS) {
                continue;
            }
            float life = (float) (age / SkyEventSchedule.FIREWORK_TICKS);
            int color = FIREWORK_COLORS[(int) event[3]];
            long pattern = (long) event[4];
            float fade = (1.0f - life * life) * visibility;
            float widthScale = 1.0f / Math.max(0.3f, (float) Math.cos(event[1]));
            if (age < 5.0) {
                // The burst's first flash.
                spark(event[0], event[1], 3.4f * (float) (1.0 - age / 5.0) + 0.8f, 0xFFF6E0, visibility);
            }
            // A soft glow in the burst's colour, the smoke and haze around it lit up, so the burst
            // reads from far away.
            glow(event[0], event[1], event[2] * SKY_RADIUS * 1.9f, color, 0.3f * fade * (1.0f - life));
            for (int spark = 0; spark < SPARKS_PER_BURST; spark++) {
                long sparkHash = SkyEventSchedule.mix(pattern, spark);
                // Late in the burst, sparks twinkle out one by one.
                if (life > 0.6f && SkyEventSchedule.unit(SkyEventSchedule.mix(sparkHash, (long) age)) < life - 0.45f) {
                    continue;
                }
                float angle = (float) ((spark + SkyEventSchedule.unit(sparkHash) * 0.8) / SPARKS_PER_BURST * Math.PI * 2.0);
                float reachShare = 0.35f + 0.65f * (float) SkyEventSchedule.unit(SkyEventSchedule.mix(sparkHash, 13));
                for (int trail = 0; trail < TRAIL; trail++) {
                    double sampleAge = Math.max(0.0, age - trail * 2.5);
                    float spread = event[2] * reachShare * (1.0f - (float) Math.exp(-sampleAge / 5.0));
                    float droop = (float) (sampleAge * sampleAge) * 0.00005f;
                    float azimuth = event[0] + (float) Math.cos(angle) * spread * widthScale;
                    float elevation = event[1] + (float) Math.sin(angle) * spread - droop;
                    spark(azimuth, elevation, trail == 0 ? 0.95f : 0.7f, color, fade * (trail == 0 ? 1.0f : 0.5f / trail));
                }
            }
        }
    }

    /** A square point of light facing the camera, {@code size} blocks across at the sky's distance. */
    private void spark(float azimuth, float elevation, float size, int rgb, float alpha) {
        facingQuad(azimuth, elevation, size, rgb, alpha, 0.0f);
    }

    /** A soft round glow facing the camera, {@code size} blocks across at the sky's distance. */
    private void glow(float azimuth, float elevation, float size, int rgb, float alpha) {
        facingQuad(azimuth, elevation, size, rgb, alpha, GLOW_UV);
    }

    private void facingQuad(float azimuth, float elevation, float size, int rgb, float alpha, float uvOffset) {
        float[] centre = direction(azimuth, elevation);
        // Two directions across the sky at that point: along the horizon and up the sky.
        float rightX = (float) Math.cos(azimuth);
        float rightZ = (float) Math.sin(azimuth);
        float upX = -(float) (Math.sin(elevation) * Math.sin(azimuth));
        float upY = (float) Math.cos(elevation);
        float upZ = (float) (Math.sin(elevation) * Math.cos(azimuth));
        float half = size * 0.5f;
        float cx = centre[0] * SKY_RADIUS;
        float cy = centre[1] * SKY_RADIUS;
        float cz = centre[2] * SKY_RADIUS;
        float low = uvOffset - 1.0f;
        float high = uvOffset + 1.0f;
        vertex(cx - (rightX - upX) * half, cy + upY * half, cz - (rightZ - upZ) * half, low, high, rgb, alpha);
        vertex(cx + (rightX + upX) * half, cy + upY * half, cz + (rightZ + upZ) * half, high, high, rgb, alpha);
        vertex(cx + (rightX - upX) * half, cy - upY * half, cz + (rightZ - upZ) * half, high, low, rgb, alpha);
        vertex(cx - (rightX + upX) * half, cy - upY * half, cz - (rightZ + upZ) * half, low, low, rgb, alpha);
    }

    /** A meteor: a thin quad from tail (dark) to head (bright). */
    private void streak(float tailAzimuth, float tailElevation, float headAzimuth, float headElevation, float width, int rgb, float alpha) {
        float[] tail = direction(tailAzimuth, tailElevation);
        float tx = tail[0];
        float ty = tail[1];
        float tz = tail[2];
        float[] head = direction(headAzimuth, headElevation);
        // Across the streak: perpendicular to both its line and the view toward it.
        float lineX = head[0] - tx;
        float lineY = head[1] - ty;
        float lineZ = head[2] - tz;
        float acrossX = lineY * head[2] - lineZ * head[1];
        float acrossY = lineZ * head[0] - lineX * head[2];
        float acrossZ = lineX * head[1] - lineY * head[0];
        float length = (float) Math.sqrt(acrossX * acrossX + acrossY * acrossY + acrossZ * acrossZ);
        if (length < 1.0e-6f) {
            return;
        }
        float scale = width * 0.5f / length;
        acrossX *= scale;
        acrossY *= scale;
        acrossZ *= scale;
        float r = SKY_RADIUS;
        vertex(tx * r - acrossX, ty * r - acrossY, tz * r - acrossZ, 0.0f, -1.0f, rgb, 0.0f);
        vertex(tx * r + acrossX, ty * r + acrossY, tz * r + acrossZ, 0.0f, 1.0f, rgb, 0.0f);
        vertex(head[0] * r + acrossX, head[1] * r + acrossY, head[2] * r + acrossZ, 0.0f, 1.0f, rgb, alpha);
        vertex(head[0] * r - acrossX, head[1] * r - acrossY, head[2] * r - acrossZ, 0.0f, -1.0f, rgb, alpha);
    }

    private final float[] directionA = new float[3];
    private final float[] directionB = new float[3];
    private boolean useB;

    /**
     * Unit direction for an azimuth (0 north, toward -z, turning east toward +x) and an elevation.
     * Alternates between two scratch arrays so a caller can hold two directions at once.
     */
    private float[] direction(float azimuth, float elevation) {
        float[] out = useB ? directionB : directionA;
        useB = !useB;
        float horizontal = (float) Math.cos(elevation);
        out[0] = (float) Math.sin(azimuth) * horizontal;
        out[1] = (float) Math.sin(elevation);
        out[2] = -(float) Math.cos(azimuth) * horizontal;
        return out;
    }

    private void vertex(float x, float y, float z, float u, float v, int rgb, float alpha) {
        if (vertices.remaining() < VERTEX_SIZE) {
            return;
        }
        vertices.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        vertices.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) (Math.clamp(alpha, 0.0f, 1.0f) * 255.0f));
    }
}
