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
 * The sky's events drawn as geometry: an aurora over cold biomes and distant fireworks at night,
 * and by day a rainbow after the rain. Drawn into the main target just after vanilla's sky and
 * before terrain, so terrain and clouds cover them and they sit at the sky's distance. (A meteor
 * shower's streaks are the sky shader's, beside the falling stars.)
 *
 * <p>Which nights bring what, and when each burst happens, comes from
 * {@link SkyEventSchedule}, the aurora's curtains from {@link AuroraCurtains} and the rainbows from
 * {@link RainbowSchedule}; this class only
 * turns that into at most several hundred quads a frame. The vertex data goes into one reused
 * native buffer and one GPU buffer that grows only if needed.
 */
public final class SkyEvents {
    /** The sky's distance in blocks, inside vanilla's sky (the sun and moon sit at 100). */
    private static final float SKY_RADIUS = 95.0f;
    private static final int SPARKS_PER_BURST = 40;
    /** Each spark draws itself and a short trail of earlier positions. */
    private static final int TRAIL = 4;
    /**
     * At most a volley of three bursts shows at once, and in the busier preview two bursts of the
     * volley before can still be fading; a burst is its sparks, a flash and a glow.
     */
    private static final int MAX_BURSTS = SkyEventSchedule.MAX_VOLLEY + 2;
    /** The rainbow is one band of quads around the point opposite the sun, from inside the bow to past the second bow. */
    private static final int RAINBOW_SEGMENTS = 72;
    private static final float RAINBOW_INNER = (float) Math.toRadians(24.0);
    private static final float RAINBOW_OUTER = (float) Math.toRadians(57.0);
    /** Segments wholly this far under the horizon are left out; the shader fades the bow out at the horizon itself. */
    private static final float RAINBOW_LOWEST = -0.05f;
    private static final float PREVIEW_RAINBOW = 0.9f;
    private static final int MAX_QUADS = AuroraCurtains.MAX_QUADS + MAX_BURSTS * (SPARKS_PER_BURST * TRAIL + 2) + RAINBOW_SEGMENTS;
    /** A previewed show's first burst goes off this many ticks after the preview is chosen. */
    private static final int PREVIEW_FIRST_BURST_TICKS = 10;
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
    private static final RenderPipeline RAINBOW_PIPELINE = base(PostPipelines.ADD_KEEP_ALPHA)
        .withLocation(LiteLightsClient.id("pipeline/sky_rainbow"))
        .withShaderDefine("RAINBOW")
        .build();

    private final ByteBuffer vertices = MemoryUtil.memAlloc(MAX_QUADS * 4 * VERTEX_SIZE);
    private final float[] event = new float[5];
    private final AuroraCurtains curtains = new AuroraCurtains();
    private final AuroraCurtains.Sink sink = this::vertex;
    private final RainbowSchedule.Watch rainbowWatch = new RainbowSchedule.Watch();
    private GpuBuffer gpuBuffer;
    /** Tonight's events; while the settings screen previews one, the preview's night instead. */
    private SkyEventSchedule.Night night;
    private SkyEventSchedule.Night scheduled;
    private int auroraQuads;
    private int sparkQuads;
    private int rainbowQuads;
    private long lastFireworkSound = Long.MIN_VALUE;
    /** The event the settings screen previewed last frame, to notice when fireworks are chosen. */
    private PreviewEvent.Event previewed;
    /**
     * Ticks the fireworks' schedule runs behind the game while their preview shows: set when the
     * preview is chosen, so its first burst goes off half a second later. 0 on a real night.
     */
    private long fireworksDelay;

    // Inputs for this frame, set by update() on the client thread before the level draws.
    private float auroraStrength;
    private boolean fireworksOn;
    private long seed;
    private long nightSeed;
    private double gameTime;
    private float visibility;
    private float rainbowStrength;
    /** How much of the camera's surroundings has rain, eased, so a rainbow fades on leaving them. */
    private float rainbowHere;
    private boolean rainbowPreviewed;

    /** What was drawn last frame, for the QA probe. */
    public int lastAuroraQuads() {
        return auroraQuads;
    }

    public int lastSparkQuads() {
        return sparkQuads;
    }

    public int lastRainbowQuads() {
        return rainbowQuads;
    }

    /** This frame's rainbow, 0 to 1 (for the QA probe). */
    public float rainbowStrength() {
        return rainbowStrength;
    }

    public float auroraStrength() {
        return auroraStrength;
    }

    /** Compiles both pipelines now, so a shader problem shows on the first frame rather than on the first event night. */
    public void compilePipelines() {
        RenderSystem.getCompiledPipeline(AURORA_PIPELINE);
        RenderSystem.getCompiledPipeline(SPARKS_PIPELINE);
        RenderSystem.getCompiledPipeline(RAINBOW_PIPELINE);
    }

    /** A sky-event shader that fails to compile is reported on the aurora row, the sky events' lead. */
    public static void claimPipelines() {
        PipelineOwners.claim(RenderFeature.SKY_EVENTS, AURORA_PIPELINE, SPARKS_PIPELINE, RAINBOW_PIPELINE);
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
            rainbowQuads = 0;
        }
        PreviewEvent.Event preview = PreviewEvent.current();
        if (preview != null) {
            // The settings screen previews one event tonight, wherever the player is and in front
            // of them; the night's own events are left out meanwhile so the preview shows just the one.
            night = SkyEventSchedule.preview(index, preview, PreviewEvent.bearing(), PreviewEvent.elevation());
            if (preview == PreviewEvent.Event.FIREWORKS && previewed != PreviewEvent.Event.FIREWORKS) {
                fireworksDelay = SkyEventSchedule.previewDelay(worldSeed, night, (long) gameTicks, PREVIEW_FIRST_BURST_TICKS);
            }
            auroraStrength = show && settings.polarLights() ? night.auroraStrength() * visibility : 0.0f;
            fireworksOn = show && settings.farFireworks() && night.fireworks();
        } else {
            fireworksDelay = 0;
            auroraStrength = show && settings.polarLights() ? night.auroraStrength() * coldness * visibility : 0.0f;
            fireworksOn = show && settings.farFireworks() && night.fireworks() && SkyEventSchedule.fireworksHour(clockTime);
        }
        previewed = preview;
        this.visibility = visibility;
    }

    /**
     * Follows the rain for the rainbows, once a tick after {@link #update}. {@code rain} and
     * {@code thunder} are the world's levels (never a preview's); {@code rainsHere} whether the
     * camera's biome has rain, rather than snow or none.
     */
    public void updateRainbow(RenderSettings.Weather settings, boolean skyAllowed, long gameTick, long clockTime, float rain,
                              float thunder, boolean rainsHere) {
        if (!skyAllowed) {
            // The Nether, the End, underground: a rain clearing out of sight brings no rainbow.
            rainbowWatch.reset();
            rainbowStrength = 0.0f;
            rainbowPreviewed = false;
            return;
        }
        rainbowWatch.tick(seed, gameTick, clockTime, rain, thunder, rainsHere);
        rainbowHere += ((rainsHere ? 1.0f : 0.0f) - rainbowHere) * 0.05f;
        rainbowPreviewed = PreviewEvent.current() == PreviewEvent.Event.RAINBOW;
        if (!settings.rainbows()) {
            rainbowStrength = 0.0f;
        } else if (rainbowPreviewed) {
            rainbowStrength = PREVIEW_RAINBOW;
        } else if (PreviewEvent.active()) {
            // Like the other events, a real one is left out while another is previewed.
            rainbowStrength = 0.0f;
        } else {
            float clearing = (1.0f - rain) * (1.0f - rain);
            float sun = RainbowSchedule.sunLight(RainbowSchedule.sunElevation(clockTime));
            rainbowStrength = rainbowWatch.strength(gameTime) * clearing * rainbowHere * sun;
        }
    }

    /** QA only: a rainbow as if rain had cleared at this game tick, whatever the schedule says. */
    public void startRainbowForQa(long gameTick) {
        rainbowWatch.startForQa(gameTick);
    }

    /** Forgets the rain the rainbows follow, as on entering another world. */
    public void resetRainbows() {
        rainbowWatch.reset();
        rainbowHere = 0.0f;
        rainbowStrength = 0.0f;
    }

    /**
     * Whether a firework burst started at this game tick: the client thread plays its far-off
     * bang a second and a half later, as sound from a distant town would take that long to arrive.
     * Returns the burst's azimuth through {@code out[0]}.
     */
    public boolean burstStartedAt(long tick, float[] out) {
        if (!fireworksOn || tick == lastFireworkSound) {
            return false;
        }
        long showTick = tick - fireworksDelay;
        long beat = Math.floorDiv(showTick, SkyEventSchedule.beatTicks(night));
        int bursts = SkyEventSchedule.volleySize(seed, night, beat);
        for (int burst = 0; burst < bursts; burst++) {
            if (SkyEventSchedule.burstTick(seed, night, beat, burst) == showTick) {
                SkyEventSchedule.firework(seed, night, beat, burst, out);
                lastFireworkSound = tick;
                return true;
            }
        }
        return false;
    }

    /** From LiteRenderEvents.AFTER_SKY: builds and draws this frame's events, if any. */
    public void draw(RenderTarget target, FrameState frame) {
        auroraQuads = 0;
        sparkQuads = 0;
        rainbowQuads = 0;
        if (auroraStrength <= 0.0f && !fireworksOn && rainbowStrength <= 0.0f) {
            return;
        }
        vertices.clear();
        if (auroraStrength > 0.0f) {
            buildAurora(frame.time.y);
        }
        int auroraVertices = vertices.position() / VERTEX_SIZE;
        if (fireworksOn) {
            buildFireworks();
        }
        int sparkVertices = vertices.position() / VERTEX_SIZE;
        if (rainbowStrength > 0.0f) {
            buildRainbow(frame);
        }
        int totalVertices = vertices.position() / VERTEX_SIZE;
        auroraQuads = auroraVertices / 4;
        sparkQuads = (sparkVertices - auroraVertices) / 4;
        rainbowQuads = (totalVertices - sparkVertices) / 4;
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
            if (sparkVertices > auroraVertices) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(SPARKS_PIPELINE));
                pass.drawIndexed((sparkVertices - auroraVertices) / 4 * 6, 1, auroraVertices / 4 * 6, 0, 0);
            }
            if (totalVertices > sparkVertices) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RAINBOW_PIPELINE));
                pass.drawIndexed((totalVertices - sparkVertices) / 4 * 6, 1, sparkVertices / 4 * 6, 0, 0);
            }
        }
    }

    /** The night's curtains, shaped by its hash; previewed, they face the way the player looked. */
    private void buildAurora(float seconds) {
        long shape = SkyEventSchedule.mix(seed, night.index() ^ 0x6175726FL);
        curtains.build(shape, night.auroraStrength(), auroraStrength, night.auroraBearing(), seconds, sink);
    }

    private void buildFireworks() {
        double showTime = gameTime - fireworksDelay;
        int beatTicks = SkyEventSchedule.beatTicks(night);
        long newestBeat = Math.floorDiv((long) showTime, beatTicks);
        long oldestBeat = Math.floorDiv((long) showTime - SkyEventSchedule.FIREWORK_TICKS, beatTicks);
        for (long beat = oldestBeat; beat <= newestBeat; beat++) {
            int bursts = SkyEventSchedule.volleySize(seed, night, beat);
            for (int burst = 0; burst < bursts; burst++) {
                double age = showTime - SkyEventSchedule.burstTick(seed, night, beat, burst);
                if (age >= 0.0 && age <= SkyEventSchedule.FIREWORK_TICKS) {
                    SkyEventSchedule.firework(seed, night, beat, burst, event);
                    drawBurst(age);
                }
            }
        }
    }

    /** One burst {@code age} ticks after it went off, from the values in {@link #event}. */
    private void drawBurst(double age) {
        float life = (float) (age / SkyEventSchedule.FIREWORK_TICKS);
        int color = FIREWORK_COLORS[(int) event[3]];
        long pattern = (long) event[4];
        float fade = (1.0f - life * life) * visibility;
        float widthScale = 1.0f / Math.max(0.3f, (float) Math.cos(event[1]));
        if (age < 5.0) {
            // The burst's first flash.
            spark(event[0], event[1], 2.6f * (float) (1.0 - age / 5.0) + 0.7f, 0xFFF6E0, visibility);
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
                float droop = (float) (sampleAge * sampleAge) * 0.00003f;
                float azimuth = event[0] + (float) Math.cos(angle) * spread * widthScale;
                float elevation = event[1] + (float) Math.sin(angle) * spread - droop;
                spark(azimuth, elevation, trail == 0 ? 0.8f : 0.6f, color, fade * (trail == 0 ? 1.0f : 0.5f / trail));
            }
        }
    }

    /**
     * The rainbow: a band of quads around the point opposite the sun (previewed, low in front of
     * where the player looked), from inside the main bow to past the second, leaving out what lies
     * well under the horizon. The shader works out each pixel's angle from that point and draws the
     * bows' colours from it; every vertex carries the point's azimuth and elevation in its texture
     * coordinate and the rainbow's strength in its alpha.
     */
    private void buildRainbow(FrameState frame) {
        float centreX;
        float centreY;
        float centreZ;
        if (rainbowPreviewed) {
            // Its top a little above where the player looked, with most of the bow in view.
            float elevation = Math.clamp(PreviewEvent.elevation() - (float) Math.toRadians(25.0), (float) Math.toRadians(-20.0),
                (float) Math.toRadians(-5.0));
            float[] centre = direction(PreviewEvent.bearing(), elevation);
            centreX = centre[0];
            centreY = centre[1];
            centreZ = centre[2];
        } else {
            centreX = -frame.sunDirection.x;
            centreY = -frame.sunDirection.y;
            centreZ = -frame.sunDirection.z;
        }
        float horizontal = (float) Math.hypot(centreX, centreZ);
        if (horizontal < 0.05f) {
            // The sun straight overhead or underfoot: no bow shows.
            return;
        }
        // Across the band: along the horizon, and up the sky at the centre.
        float acrossX = -centreZ / horizontal;
        float acrossZ = centreX / horizontal;
        float upX = -centreY * centreX / horizontal;
        float upY = horizontal;
        float upZ = -centreY * centreZ / horizontal;
        float azimuth = (float) Math.atan2(centreX, -centreZ);
        float elevation = (float) Math.asin(Math.clamp(centreY, -1.0f, 1.0f));
        float innerCos = (float) Math.cos(RAINBOW_INNER);
        float innerSin = (float) Math.sin(RAINBOW_INNER);
        float outerCos = (float) Math.cos(RAINBOW_OUTER);
        float outerSin = (float) Math.sin(RAINBOW_OUTER);
        for (int segment = 0; segment < RAINBOW_SEGMENTS; segment++) {
            double from = segment * Math.PI * 2.0 / RAINBOW_SEGMENTS;
            double to = (segment + 1) * Math.PI * 2.0 / RAINBOW_SEGMENTS;
            float fromCos = (float) Math.cos(from);
            float fromSin = (float) Math.sin(from);
            float toCos = (float) Math.cos(to);
            float toSin = (float) Math.sin(to);
            float highest = Math.max(
                Math.max(centreY * innerCos + upY * innerSin * fromSin, centreY * innerCos + upY * innerSin * toSin),
                Math.max(centreY * outerCos + upY * outerSin * fromSin, centreY * outerCos + upY * outerSin * toSin));
            if (highest < RAINBOW_LOWEST) {
                continue;
            }
            rainbowVertex(centreX, centreY, centreZ, acrossX, acrossZ, upX, upY, upZ, innerCos, innerSin, fromCos, fromSin, azimuth, elevation);
            rainbowVertex(centreX, centreY, centreZ, acrossX, acrossZ, upX, upY, upZ, innerCos, innerSin, toCos, toSin, azimuth, elevation);
            rainbowVertex(centreX, centreY, centreZ, acrossX, acrossZ, upX, upY, upZ, outerCos, outerSin, toCos, toSin, azimuth, elevation);
            rainbowVertex(centreX, centreY, centreZ, acrossX, acrossZ, upX, upY, upZ, outerCos, outerSin, fromCos, fromSin, azimuth, elevation);
        }
    }

    /** A rainbow vertex at an angle from the centre (its cosine and sine) and a turn around it. */
    private void rainbowVertex(float centreX, float centreY, float centreZ, float acrossX, float acrossZ, float upX, float upY, float upZ,
                               float radiusCos, float radiusSin, float turnCos, float turnSin, float azimuth, float elevation) {
        float x = centreX * radiusCos + (acrossX * turnCos + upX * turnSin) * radiusSin;
        float y = centreY * radiusCos + upY * turnSin * radiusSin;
        float z = centreZ * radiusCos + (acrossZ * turnCos + upZ * turnSin) * radiusSin;
        vertex(x * SKY_RADIUS, y * SKY_RADIUS, z * SKY_RADIUS, azimuth, elevation, 0xFFFFFF, rainbowStrength);
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

    private final float[] direction = new float[3];

    /** Unit direction for an azimuth (0 north, toward -z, turning east toward +x) and an elevation, in a scratch array. */
    private float[] direction(float azimuth, float elevation) {
        float[] out = direction;
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
