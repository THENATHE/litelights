package com.thenathe.litelights.render.feature.weather;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.PreviewEvent;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.feature.clouds.DynamicClouds;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import com.thenathe.litelights.render.post.PostPipelines;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The sky's events drawn as geometry: an aurora over cold biomes and distant fireworks at night,
 * and by day a rainbow after the rain. Each is drawn at the depth where it really is (sky-events-116).
 * The aurora is beyond everything, so it goes into the main target just after vanilla's sky and
 * before terrain, and the land and clouds cover it. The fireworks are a town's show at a place in
 * the world ({@link FireworksShow}), under the clouds' bases, and the rainbow is the light of the
 * raindrops all along the line of sight: both are drawn after the solid world, far terrain and the
 * Dynamic clouds and before translucent terrain ({@link #drawBeforeTranslucents}), reading the
 * scene's depth, so land in front hides a burst, cloud in front dims it by what it lets through
 * and cloud behind it doesn't touch it, and the rainbow shows over the far scene and fades out over
 * what is near. (A meteor shower's streaks are the sky shader's, beside the falling stars.)
 *
 * <p>Which nights bring what, and when each burst happens, comes from
 * {@link SkyEventSchedule}, the aurora's curtains from {@link AuroraCurtains} and the rainbows from
 * {@link RainbowSchedule}; this class only
 * turns that into at most several hundred quads a frame. The vertex data goes into one reused
 * native buffer, and into a GPU buffer for each of the two draws that grows only if needed.
 */
public final class SkyEvents {
    /** The sky's distance in blocks, inside vanilla's sky (the sun and moon sit at 100), for the aurora and the rainbow. */
    private static final float SKY_RADIUS = 95.0f;
    private static final int SPARKS_PER_BURST = 40;
    /**
     * A burst's pieces as shares of its radius, as the sky sparks were against their bursts until
     * 1.1.6: a spark and its trail, the flash as it opens and as it settles, and the glow round it.
     */
    private static final float SPARK_SHARE = 0.095f;
    private static final float TRAIL_SHARE = 0.071f;
    private static final float FLASH_SHARE = 0.31f;
    private static final float FLASH_REST_SHARE = 0.083f;
    private static final float GLOW_SHARE = 1.9f;
    /** Sparks are never drawn smaller than this many pixels across, so a distant one still reads instead of flickering in and out. */
    private static final float SMALLEST_SPARK_PIXELS = 2.0f;
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
    /** Between volleys, a placed show takes in the land and clouds under it again this often, in ticks. */
    private static final int SHOW_RECHECK_TICKS = 40;
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

    /** Push constants of the fireworks drawn behind the Dynamic clouds: their march's pixel size. */
    private static final int COVER_PUSH_BYTES = 16;

    private static final RenderPipeline AURORA_PIPELINE = base(ADD)
        .withLocation(LiteLightsClient.id("pipeline/sky_aurora"))
        .withShaderDefine("AURORA")
        .build();
    /**
     * The fireworks' sparks and glows, at their place in the world, with no depth target: the
     * shader reads the scene's depth itself, so it can tell a cloud's front (which dims a burst)
     * from the land (which hides it).
     */
    private static final RenderPipeline SPARKS_PIPELINE = base(PostPipelines.ADD_KEEP_ALPHA)
        .withLocation(LiteLightsClient.id("pipeline/sky_sparks"))
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(PostPipelines.samplers("SceneDepth"))
        .withShaderDefine("WORLD")
        .build();
    /** The same with the Dynamic clouds' march, for a camera over their floor: the cloud in front dims them. */
    private static final RenderPipeline COVERED_SPARKS_PIPELINE = base(PostPipelines.ADD_KEEP_ALPHA)
        .withLocation(LiteLightsClient.id("pipeline/sky_sparks_covered"))
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(PostPipelines.samplers("SceneDepth", "CloudMarch"))
        .withPushConstantSize(COVER_PUSH_BYTES)
        .withShaderDefine("WORLD")
        .withShaderDefine("CLOUD_COVER")
        .build();
    /**
     * The sparks' depth, after their light: Flat and Fancy clouds, water, glass and far water drawn
     * later sort against a burst as against anything else at its distance (the glows write none).
     */
    private static final RenderPipeline SPARK_DEPTH_PIPELINE = base(PostPipelines.ADD_KEEP_ALPHA)
        .withLocation(LiteLightsClient.id("pipeline/sky_spark_depth"))
        .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
        .withShaderDefine("WORLD")
        .withShaderDefine("DEPTH_ONLY")
        .build();
    /** The rainbow, after the clouds, reading the scene's depth so it fades out over what is near. */
    private static final RenderPipeline RAINBOW_PIPELINE = base(PostPipelines.ADD_KEEP_ALPHA)
        .withLocation(LiteLightsClient.id("pipeline/sky_rainbow"))
        .withBindGroupLayout(PostPipelines.samplers("SceneDepth"))
        .withShaderDefine("RAINBOW")
        .build();

    private final ByteBuffer vertices = MemoryUtil.memAlloc(MAX_QUADS * 4 * VERTEX_SIZE);
    private final float[] event = new float[6];
    private final double[] burstPlace = new double[4];
    private final AuroraCurtains curtains = new AuroraCurtains();
    private final AuroraCurtains.Sink sink = this::vertex;
    private final RainbowSchedule.Watch rainbowWatch = new RainbowSchedule.Watch();
    /** The sky pass's vertices (the aurora) and the later draw's (fireworks, rainbow): two, so neither overwrites what the other's draw still reads. */
    private GpuBuffer skyBuffer;
    private GpuBuffer lateBuffer;
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
    /** Tonight's show, placed in the world, or null before it is (or where no place has room). */
    private FireworksShow.@Nullable Place show;
    /** The night the show was placed for, and whether as a preview; a new one places it again. */
    private long showNight = Long.MIN_VALUE;
    private boolean showPreviewed;
    /** The preview's delay the show was placed for: choosing the preview again places it again, in front of where the player looks now. */
    private long shownDelay;
    private long showChecked;
    /** The camera as the client tick last saw it, for the bangs' direction. */
    private double cameraX;
    private double cameraZ;

    // Drawing one frame's fireworks: the camera and how wide a pixel is there.
    private double drawCameraX;
    private double drawCameraY;
    private double drawCameraZ;
    private float pixelAngle;

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

    /** Where tonight's show is placed in the world, or null (for the QA probe and the far plane). */
    public FireworksShow.@Nullable Place show() {
        return fireworksOn ? show : null;
    }

    /** Compiles the pipelines now, so a shader problem shows on the first frame rather than on the first event night. */
    public void compilePipelines() {
        RenderSystem.getCompiledPipeline(AURORA_PIPELINE);
        RenderSystem.getCompiledPipeline(SPARKS_PIPELINE);
        RenderSystem.getCompiledPipeline(COVERED_SPARKS_PIPELINE);
        RenderSystem.getCompiledPipeline(SPARK_DEPTH_PIPELINE);
        RenderSystem.getCompiledPipeline(RAINBOW_PIPELINE);
    }

    /** A sky-event shader that fails to compile is reported on the aurora row, the sky events' lead. */
    public static void claimPipelines() {
        PipelineOwners.claim(RenderFeature.SKY_EVENTS, AURORA_PIPELINE, SPARKS_PIPELINE, COVERED_SPARKS_PIPELINE, SPARK_DEPTH_PIPELINE,
            RAINBOW_PIPELINE);
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
            night = SkyEventSchedule.preview(index, preview, PreviewEvent.bearing());
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
     * Places tonight's show in the world, after {@link #update}: when the night's show begins (or
     * a preview is chosen), from the camera at {@code x}, {@code z}; again only if the camera has
     * gone far from it. Between volleys it takes in the land and clouds under it again, so a show
     * placed before far terrain knew the land there never bursts inside it once it does.
     *
     * @param ground        what is known of the land round a place
     * @param guessedGround the land's height to take where nothing is known ({@link FireworksShow#guessedGround})
     * @param cloudBase     the clouds' bases, or infinite with the clouds off
     */
    public void followShow(double x, double z, FireworksShow.Ground ground, int guessedGround, float cloudBase) {
        cameraX = x;
        cameraZ = z;
        if (!fireworksOn) {
            return;
        }
        boolean previewing = PreviewEvent.current() == PreviewEvent.Event.FIREWORKS;
        long tick = (long) gameTime;
        boolean between = !burstsAlive(gameTime - fireworksDelay);
        boolean newShow = showNight != night.index() || showPreviewed != previewing || (previewing && fireworksDelay != shownDelay);
        boolean wentFar = show != null && Math.hypot(show.x() - x, show.z() - z) > FireworksShow.PLACED_AGAIN_BEYOND;
        if (newShow || (wentFar && between) || (show == null && tick - showChecked >= SHOW_RECHECK_TICKS)) {
            show = FireworksShow.place(night, x, z, ground, guessedGround, cloudBase, previewing);
            showNight = night.index();
            showPreviewed = previewing;
            shownDelay = fireworksDelay;
            showChecked = tick;
        } else if (between && tick - showChecked >= SHOW_RECHECK_TICKS) {
            showChecked = tick;
            show = show.with(FireworksShow.groundAt(show.x(), show.z(), ground, guessedGround), cloudBase - FireworksShow.UNDER_CLOUDS);
        }
    }

    /** Whether any burst of the show is in the air at this moment of the show's own time. */
    private boolean burstsAlive(double showTime) {
        int beatTicks = SkyEventSchedule.beatTicks(night);
        long newestBeat = Math.floorDiv((long) showTime, beatTicks);
        long oldestBeat = Math.floorDiv((long) showTime - SkyEventSchedule.FIREWORK_TICKS, beatTicks);
        for (long beat = oldestBeat; beat <= newestBeat; beat++) {
            for (int burst = 0; burst < SkyEventSchedule.volleySize(seed, night, beat); burst++) {
                double age = showTime - SkyEventSchedule.burstTick(seed, night, beat, burst);
                if (age >= 0.0 && age <= SkyEventSchedule.FIREWORK_TICKS) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The level's far plane, pushed out past tonight's show while it is on, so its bursts are in
     * front of it (four times the render distance can be well short of two kilometres).
     */
    public float farPlane(float vanilla) {
        FireworksShow.Place place = show();
        if (place == null) {
            return vanilla;
        }
        double horizontal = Math.hypot(place.x() - cameraX, place.z() - cameraZ) + FireworksShow.SPREAD_ACROSS + FireworksShow.LARGEST_RADIUS;
        return (float) Math.max(vanilla, Math.hypot(horizontal, 512.0) + 64.0);
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
     * Returns the town's azimuth from the camera through {@code out[0]}.
     */
    public boolean burstStartedAt(long tick, float[] out) {
        if (!fireworksOn || show == null || tick == lastFireworkSound) {
            return false;
        }
        long showTick = tick - fireworksDelay;
        long beat = Math.floorDiv(showTick, SkyEventSchedule.beatTicks(night));
        int bursts = SkyEventSchedule.volleySize(seed, night, beat);
        for (int burst = 0; burst < bursts; burst++) {
            if (SkyEventSchedule.burstTick(seed, night, beat, burst) == showTick) {
                out[0] = (float) Math.atan2(show.x() - cameraX, -(show.z() - cameraZ));
                lastFireworkSound = tick;
                return true;
            }
        }
        return false;
    }

    /** From LiteRenderEvents.AFTER_SKY: builds and draws this frame's aurora, if any. */
    public void draw(RenderTarget target, FrameState frame) {
        auroraQuads = 0;
        sparkQuads = 0;
        rainbowQuads = 0;
        if (auroraStrength <= 0.0f) {
            return;
        }
        vertices.clear();
        buildAurora(frame.time.y);
        int auroraVertices = vertices.position() / VERTEX_SIZE;
        auroraQuads = auroraVertices / 4;
        if (auroraVertices == 0) {
            return;
        }
        skyBuffer = upload(skyBuffer, "LiteLights sky events");
        var quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = quadIndices.getBuffer(auroraVertices / 4 * 6);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
            .writeTransform(RenderSystem.getModelViewMatrixCopy(), WHITE);
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights sky events", target.getColorTextureView(),
            Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, skyBuffer.slice());
            pass.setIndexBuffer(indexBuffer, quadIndices.type());
            pass.setPipeline(RenderSystem.getCompiledPipeline(AURORA_PIPELINE));
            pass.drawIndexed(auroraVertices / 4 * 6, 1, 0, 0, 0);
        }
    }

    /** Whether there are fireworks or a rainbow to draw before translucent terrain this frame. */
    public boolean wantsPassBeforeTranslucents() {
        return (fireworksOn && show != null) || rainbowStrength > 0.0f;
    }

    /**
     * Before translucent terrain, after the solid world, far terrain and the Dynamic clouds: this
     * frame's fireworks at their place in the world, and the rainbow, both over the scene by its
     * depth, then the sparks' depth. {@code cover} is what this frame's Dynamic clouds left
     * ({@link DynamicClouds#coverThisFrame}), or null.
     */
    public void drawBeforeTranslucents(RenderTarget target, FrameState frame, DynamicClouds.@Nullable Cover cover) {
        GpuTextureView depth = target.getDepthTextureView();
        if (!wantsPassBeforeTranslucents() || depth == null) {
            return;
        }
        drawCameraX = frame.cameraBlockX + (double) frame.cameraFraction.x;
        drawCameraY = frame.cameraBlockY + (double) frame.cameraFraction.y;
        drawCameraZ = frame.cameraBlockZ + (double) frame.cameraFraction.z;
        pixelAngle = pixelAngle(frame.viewProjection, target.height);
        vertices.clear();
        if (fireworksOn && show != null) {
            buildFireworks();
        }
        int sparkVertices = vertices.position() / VERTEX_SIZE;
        if (rainbowStrength > 0.0f) {
            buildRainbow(frame);
        }
        int totalVertices = vertices.position() / VERTEX_SIZE;
        sparkQuads = sparkVertices / 4;
        rainbowQuads = (totalVertices - sparkVertices) / 4;
        if (totalVertices == 0) {
            return;
        }
        lateBuffer = upload(lateBuffer, "LiteLights fireworks and rainbow");
        var quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = quadIndices.getBuffer(totalVertices / 4 * 6);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
            .writeTransform(RenderSystem.getModelViewMatrixCopy(), WHITE);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        // The clouds lie in front of a burst only from over their floor: the bursts are all under it.
        boolean covered = cover != null && drawCameraY > cover.floorY();
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights fireworks and rainbow", target.getColorTextureView(), Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsRenderer.get().bindWorldUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, lateBuffer.slice());
            pass.setIndexBuffer(indexBuffer, quadIndices.type());
            if (sparkVertices > 0) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(covered ? COVERED_SPARKS_PIPELINE : SPARKS_PIPELINE));
                pass.setUniform("SceneDepth", depth, nearest);
                if (covered) {
                    pass.setUniform("CloudMarch", cover.resolved(), nearest);
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        ByteBuffer push = stack.malloc(COVER_PUSH_BYTES);
                        push.putFloat(0, cover.divisor()).putFloat(4, 0.0f).putFloat(8, 0.0f).putFloat(12, 0.0f);
                        pass.pushConstants(push);
                    }
                }
                pass.drawIndexed(sparkVertices / 4 * 6, 1, 0, 0, 0);
            }
            if (totalVertices > sparkVertices) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RAINBOW_PIPELINE));
                pass.setUniform("SceneDepth", depth, nearest);
                pass.drawIndexed((totalVertices - sparkVertices) / 4 * 6, 1, sparkVertices / 4 * 6, 0, 0);
            }
        }
        if (sparkVertices > 0) {
            try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights firework depth", target.getColorTextureView(), Optional.empty(),
                depth, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, lateBuffer.slice());
                pass.setIndexBuffer(indexBuffer, quadIndices.type());
                pass.setPipeline(RenderSystem.getCompiledPipeline(SPARK_DEPTH_PIPELINE));
                pass.drawIndexed(sparkVertices / 4 * 6, 1, 0, 0, 0);
            }
        }
    }

    /** The built vertices put up into {@code buffer}, made or grown first if needed. */
    private GpuBuffer upload(GpuBuffer buffer, String label) {
        vertices.flip();
        var device = RenderSystem.getDevice();
        if (buffer == null || buffer.size() < vertices.capacity()) {
            if (buffer != null) {
                buffer.close();
            }
            buffer = device.createBuffer(() -> label, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertices.capacity());
        }
        device.createCommandEncoder().writeToBuffer(buffer.slice(0L, vertices.remaining()), vertices);
        return buffer;
    }

    /**
     * How wide a pixel is at the middle of the screen, in radians: the projection's vertical scale
     * is the length of the view-projection's second row (the view only turns), and the screen's
     * height spans two of it.
     */
    static float pixelAngle(Matrix4f viewProjection, int screenHeight) {
        float scale = (float) Math.sqrt(viewProjection.m01() * viewProjection.m01() + viewProjection.m11() * viewProjection.m11()
            + viewProjection.m21() * viewProjection.m21());
        return 2.0f / (Math.max(scale, 1.0e-3f) * Math.max(screenHeight, 1));
    }

    /** The night's curtains, shaped by its hash; previewed, they face the way the player looked. */
    private void buildAurora(float seconds) {
        long shape = SkyEventSchedule.mix(seed, night.index() ^ 0x6175726FL);
        curtains.build(shape, night.auroraStrength(), auroraStrength, night.auroraBearing(), seconds, sink);
    }

    /** Tonight's bursts in the air this frame, around the show's place. */
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
                    if (FireworksShow.burst(show, event, burstPlace)) {
                        drawBurst(age);
                    }
                }
            }
        }
    }

    /**
     * One burst {@code age} ticks after it went off, from the values in {@link #event} at the place
     * in {@link #burstPlace}: its sparks, flash and glow on quads facing the camera, positions
     * camera-relative. As the sky bursts until 1.1.6, only sized in blocks: the sparks spread out
     * and droop, trailing earlier places, and twinkle out late in the burst.
     */
    private void drawBurst(double age) {
        float life = (float) (age / SkyEventSchedule.FIREWORK_TICKS);
        int color = FIREWORK_COLORS[(int) event[3]];
        long pattern = (long) event[4];
        float fade = (1.0f - life * life) * visibility;
        float radius = (float) burstPlace[3];
        float centreX = (float) (burstPlace[0] - drawCameraX);
        float centreY = (float) (burstPlace[1] - drawCameraY);
        float centreZ = (float) (burstPlace[2] - drawCameraZ);
        float distance = (float) Math.sqrt(centreX * centreX + centreY * centreY + centreZ * centreZ);
        if (distance < 1.0f) {
            return;
        }
        // Across the view at the burst: along the horizon, and up as near to straight up as faces the camera.
        float horizontal = Math.max((float) Math.hypot(centreX, centreZ), 1.0e-3f);
        float rightX = -centreZ / horizontal;
        float rightZ = centreX / horizontal;
        float upX = -centreY * centreX / (horizontal * distance);
        float upY = horizontal / distance;
        float upZ = -centreY * centreZ / (horizontal * distance);
        float smallest = SMALLEST_SPARK_PIXELS * pixelAngle * distance;
        if (age < 5.0) {
            // The burst's first flash.
            float flash = radius * (FLASH_SHARE * (float) (1.0 - age / 5.0) + FLASH_REST_SHARE);
            facingQuad(centreX, centreY, centreZ, rightX, rightZ, upX, upY, upZ, Math.max(flash, smallest), 0xFFF6E0, visibility, 0.0f);
        }
        // A soft glow in the burst's colour, the smoke and haze around it lit up, so the burst
        // reads from far away.
        facingQuad(centreX, centreY, centreZ, rightX, rightZ, upX, upY, upZ, radius * GLOW_SHARE, color, 0.3f * fade * (1.0f - life), GLOW_UV);
        float sparkSize = Math.max(radius * SPARK_SHARE, smallest);
        float trailSize = Math.max(radius * TRAIL_SHARE, smallest);
        for (int spark = 0; spark < SPARKS_PER_BURST; spark++) {
            long sparkHash = SkyEventSchedule.mix(pattern, spark);
            // Late in the burst, sparks twinkle out one by one.
            if (life > 0.6f && SkyEventSchedule.unit(SkyEventSchedule.mix(sparkHash, (long) age)) < life - 0.45f) {
                continue;
            }
            float angle = (float) ((spark + SkyEventSchedule.unit(sparkHash) * 0.8) / SPARKS_PER_BURST * Math.PI * 2.0);
            float reachShare = 0.35f + 0.65f * (float) SkyEventSchedule.unit(SkyEventSchedule.mix(sparkHash, 13));
            float across = (float) Math.cos(angle);
            float upward = (float) Math.sin(angle);
            for (int trail = 0; trail < TRAIL; trail++) {
                double sampleAge = Math.max(0.0, age - trail * 2.5);
                float spread = radius * reachShare * (1.0f - (float) Math.exp(-sampleAge / 5.0));
                float droop = radius * FireworksShow.DROOP * (float) (sampleAge * sampleAge);
                float x = centreX + (rightX * across + upX * upward) * spread;
                float y = centreY + upY * upward * spread - droop;
                float z = centreZ + (rightZ * across + upZ * upward) * spread;
                facingQuad(x, y, z, rightX, rightZ, upX, upY, upZ, trail == 0 ? sparkSize : trailSize, color,
                    fade * (trail == 0 ? 1.0f : 0.5f / trail), 0.0f);
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

    /**
     * A square facing the camera, {@code size} blocks across, round a camera-relative point, given
     * the two directions across the view there. Its texture coordinates run -1 to 1 across it, from
     * {@code uvOffset} (sparks at 0, glows at {@link #GLOW_UV}).
     */
    private void facingQuad(float x, float y, float z, float rightX, float rightZ, float upX, float upY, float upZ, float size, int rgb,
                            float alpha, float uvOffset) {
        float half = size * 0.5f;
        float low = uvOffset - 1.0f;
        float high = uvOffset + 1.0f;
        vertex(x - (rightX - upX) * half, y + upY * half, z - (rightZ - upZ) * half, low, high, rgb, alpha);
        vertex(x + (rightX + upX) * half, y + upY * half, z + (rightZ + upZ) * half, high, high, rgb, alpha);
        vertex(x + (rightX - upX) * half, y - upY * half, z + (rightZ - upZ) * half, high, low, rgb, alpha);
        vertex(x - (rightX + upX) * half, y - upY * half, z - (rightZ + upZ) * half, low, low, rgb, alpha);
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
