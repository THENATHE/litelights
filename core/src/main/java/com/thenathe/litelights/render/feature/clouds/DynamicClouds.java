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
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.clouds.field.CloudDrift;
import com.thenathe.litelights.clouds.field.CloudWeather;
import com.thenathe.litelights.clouds.field.IntegratedWind;
import com.thenathe.litelights.clouds.field.RegionalStorm;
import com.thenathe.litelights.clouds.field.StormPresence;
import com.thenathe.litelights.clouds.field.VanillaWeatherSky;
import com.thenathe.litelights.clouds.field.WeatherForecast;
import com.thenathe.litelights.clouds.mesh.CloudCells;
import com.thenathe.litelights.clouds.mesh.CloudRings;
import com.thenathe.litelights.clouds.mesh.FaceFormat;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.mixin.particles.BiomeManagerAccessor;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.climate.LocalClimate;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.frame.WeatherPreview;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Dynamic clouds (the Clouds row's fourth choice): big voxel clouds of real height from the
 * cloud field (clouds.field), meshed on worker threads ({@link CloudBuilder}) and drawn as one
 * face list expanded in the vertex shader ({@code shaders/clouds/dynamic.*}).
 *
 * <ul>
 *   <li><b>Where it draws:</b> the opaque cubes go into the main target after the solid world and
 *   before translucent terrain (mixin.water.LevelRendererWaterMixin's split), with both transparency
 *   modes, so they never pass through Improved Transparency's stages, which cost five times as much
 *   per face; water, glass and particles in front blend over them as over land.</li>
 *   <li><b>Generations:</b> every second or so a new build of every chunk in reach is made, and the
 *   change from the last one is drawn as growth over {@link #TRANSITION_TICKS}: cubes rise, sink,
 *   widen and shrink instead of switching (clouds.mesh.CloudMesher). The wind is an offset in the
 *   uniform, so drifting needs no build at all.</li>
 *   <li><b>The weather's pace:</b> {@link CloudWeather} follows the level's rain and thunder at a
 *   pace a sky can, and gives the rain to show under it (render.frame.ShownWeather). In single player
 *   the integrated server's countdowns let the clouds close before the rain.</li>
 *   <li><b>With Almanac's regional weather</b> the clouds are its storms where they are
 *   ({@link AlmanacStorms}, clouds.field.RegionalSky) on its weather tick and its wind, with
 *   LiteLights' fair-weather cloud between them; the weather shown is Almanac's at the camera, under
 *   cloud that is really there.</li>
 *   <li><b>Inside a cloud</b> the frame's storm fog thickens into the cloud's own white-out, so flying
 *   through a deck is a fog, not a view through the back of every face.</li>
 * </ul>
 *
 * <p>While another choice is on nothing here runs: no worker, no buffer, and every hook returns
 * after one read. Its resources are let go a few seconds after the choice changes (an A/B timing
 * run flips it every few frames).
 */
public final class DynamicClouds {
    private static final DynamicClouds INSTANCE = new DynamicClouds();

    /** Cube size near the camera, in blocks (the clouds writeup's 8; cheap to change only in multiples of 8). */
    public static final int CUBE_BLOCKS = 8;
    /** Ticks each generation's change takes to grow in: one second. */
    static final double TRANSITION_TICKS = 20.0;
    /** A sky that changed nothing in a build is built again only after this many transitions. */
    static final int QUIET_TRANSITIONS = 3;
    /** The clouds fade out over this share of the reach, to its end. */
    static final float FADE_SHARE = 0.15f;
    /**
     * A camera this far from where the shown generation was built (as a share of the near ring, or
     * of the reach if that is shorter) jumps to a new one at once.
     */
    static final double JUMP_SHARE = 0.4;
    /** How long resources outlive the choice being switched off, in nanoseconds. */
    private static final long RELEASE_NANOS = 10_000_000_000L;
    /** The densest fog inside a cloud, per block (storm fog's units): about 25 blocks of sight. */
    private static final float INSIDE_FOG_DENSITY = 0.12f;
    /** With vanilla's fog, how far one sees deep inside a cloud, in blocks (where its fog ends). */
    private static final float INSIDE_SIGHT = 32.0f;
    /** The tallest a cloud can be above the floor, in blocks, for the far plane. */
    private static final float TALLEST_CLOUD = CloudCells.HEIGHT * FaceFormat.CELL_BLOCKS;
    /** How far round the camera the cloud over it is looked at for the rain under it, in 8-block columns. */
    private static final int COVER_AROUND = 4;
    /** How often Almanac's storms near the camera are asked for, in client ticks. */
    private static final int STORMS_EVERY = 20;
    /** How far past the reach Almanac's storms are asked for, in blocks, so one drifting into view is known first. */
    private static final double STORMS_BEYOND_REACH = 2_048.0;
    /** How far ahead of the weather tick a weather mod's wind is summed before the first build, in ticks. */
    private static final double WIND_AHEAD = 48_000.0;
    /** How far round the camera the clouds' tops are looked at for the weather that falls at its height, in 8-block columns. */
    private static final int TOPS_AROUND = 6;
    /** The weather fades out from this far under the clouds' tops round the camera to this far over them, in blocks. */
    private static final float TOPS_FADE_UNDER = 24.0f;
    private static final float TOPS_FADE_OVER = 8.0f;
    /** How fast the weather at the camera's height follows the tops, a share a tick. */
    private static final float TOPS_EASE = 0.06f;
    /** The clouds' colour on a clear night, against 1.0 by day; brighter than vanilla's night clouds, as moonlit cloud is. */
    private static final float NIGHT_RED = 0.12f;
    private static final float NIGHT_GREEN = 0.135f;
    private static final float NIGHT_BLUE = 0.19f;

    static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
        .withUniform("DynamicCloudInfo", UniformType.UNIFORM_BUFFER)
        .withUniform("DynamicCloudFaces", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
        .build();
    static final RenderPipeline PIPELINE = RenderPipeline.builder()
        .withLocation(LiteLightsClient.id("pipeline/dynamic_clouds"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(LAYOUT)
        .withVertexShader(LiteLightsClient.id("clouds/dynamic"))
        .withFragmentShader(LiteLightsClient.id("clouds/dynamic"))
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .build();
    private static final int INFO_SIZE = new Std140SizeCalculator().putVec4().putVec4().putVec4().get();

    private final CloudWeather pace = new CloudWeather();
    /** Almanac's storms to draw, each faded in or out where it appeared or went at once. */
    private final StormPresence storms = new StormPresence();
    private int stormsDue;
    // For QA: how many storms the last ask listed, and the longest ask since QA last looked.
    private int stormsAsked;
    private long stormsNanos;
    private @Nullable ClientLevel pacedLevel;
    private volatile boolean pacing;
    /** How much of the weather falls at the camera's height: 1 under the clouds' tops, 0 over them. */
    private float underTops = 1.0f;

    private @Nullable CloudBuilder builder;
    private @Nullable CloudFaceBuffer faces;
    private @Nullable CloudShadows shadows;
    private int shownReach;
    private @Nullable MappableRingBuffer info;
    private @Nullable CompletableFuture<Generation> building;
    private boolean buildingJump;
    private @Nullable Generation ready;
    private boolean readyJump;
    private @Nullable Generation shown;
    private @Nullable Generation beforeShown;
    private double swapTick;
    private double lastTick;
    /**
     * The field's tick this frame: the game time, or with Almanac's regional weather its weather
     * tick (which stands still while the weather does). Builds, the wind's drift and the shadows go
     * by it; the changes growing in go by the game time.
     */
    private double skyTick;
    private long seed;
    private float floor = Float.NaN;
    /** Whether the shown clouds are Almanac's storms (its regional weather) rather than LiteLights' own sky. */
    private boolean regional;
    private @Nullable CloudDrift wind;
    /** Almanac's wind being summed up to now on a worker; the first regional build waits for it. */
    private @Nullable CompletableFuture<Void> windSummed;
    private @Nullable ClientLevel builtLevel;
    private boolean drawThisFrame;
    private int drawFaces;
    private long lastChosenNanos;
    private int quietBuilds;
    private long builds;
    // The render thread's own cost, for QA: prepare's time per frame (uploads included).
    private long prepareNanos;
    private long mostPrepareNanos;
    private int prepareFrames;

    // This frame's values for the in-cloud fog and the shader.
    private final Vec3Mutable camera = new Vec3Mutable();
    private float colourRed = 1.0f;
    private float colourGreen = 1.0f;
    private float colourBlue = 1.0f;
    private float moonlight = 1.0f;
    private float progress = 1.0f;
    private float inside;

    private DynamicClouds() {
    }

    public static DynamicClouds get() {
        return INSTANCE;
    }

    /** From LiteLightsClient, after sky-fog, so the in-cloud fog joins the storm fog sky-fog has just worked out. */
    public static void register() {
        PipelineOwners.claim(RenderFeature.DYNAMIC_CLOUDS, PIPELINE, CloudShadows.PIPELINE);
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onClientTick);
        LiteRenderEvents.FRAME_UPDATE.register(INSTANCE::onFrameUpdate);
    }

    /** Whether the Clouds row has the Dynamic clouds chosen (whether or not they can draw here). */
    public static boolean chosen(RenderSettings settings) {
        return settings.sky().clouds() == RenderSettings.CloudStyle.DYNAMIC;
    }

    /** Whether they may draw this frame: chosen, the sky allowed in this dimension, not failed. */
    public boolean mayDraw() {
        return chosen(RenderSettingsAccess.current()) && DimensionGate.allows(Aspect.SKY)
            && RenderFeatureStatus.mayRun(RenderFeature.DYNAMIC_CLOUDS);
    }

    // ---- The weather's pace -------------------------------------------------------------------

    /** Whether the shown weather follows the clouds' pace in this level (render.frame.ShownWeather). */
    public boolean pacesWeather(ClientLevel level) {
        return pacing && level == pacedLevel;
    }

    /** The rain to show at the camera: none over the clouds' tops, where nothing falls. */
    public float shownRain(float partialTicks) {
        return pace.shownRain(partialTicks) * underTops;
    }

    public float shownThunder(float partialTicks) {
        return pace.shownThunder(partialTicks) * underTops;
    }

    /** How much of the weather falls at the camera's height: 1 under the clouds' tops, 0 a little over them. */
    public float underCloudTops() {
        return underTops;
    }

    /** A real lightning bolt was first seen: thunderheads still building catch up faster (CloudWeather.boltStruck). */
    public void boltStruck() {
        if (pacing) {
            pace.boltStruck();
        }
    }

    /** The world's rain (a seasons mod's, before each player's share) at the same pace. */
    public float shownWorldRain(float partialTicks) {
        return pace.shownWorldRain(partialTicks);
    }

    public float shownWorldThunder(float partialTicks) {
        return pace.shownWorldThunder(partialTicks);
    }

    private void onClientTick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        RenderSettings settings = RenderSettingsAccess.current();
        boolean chosen = chosen(settings);
        if (chosen) {
            lastChosenNanos = System.nanoTime();
        }
        if (level == null || !chosen) {
            pacing = false;
            underTops = 1.0f;
            if (level != pacedLevel || System.nanoTime() - lastChosenNanos > RELEASE_NANOS) {
                pace.reset();
                pacedLevel = null;
            }
            if (level == null || System.nanoTime() - lastChosenNanos > RELEASE_NANOS) {
                release();
            }
            return;
        }
        if (level != pacedLevel) {
            pace.reset();
            storms.clear();
            stormsDue = 0;
            pacedLevel = level;
        }
        pacing = level.canHaveWeather() && DimensionGate.allows(Aspect.SKY) && RenderFeatureStatus.mayRun(RenderFeature.DYNAMIC_CLOUDS);
        Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        if (regionalWeather(level)) {
            followStorms(level, camera);
        } else {
            followOwnSky(minecraft, level);
        }
        underTops += Math.clamp(underTopsTarget(camera) - underTops, -TOPS_EASE, TOPS_EASE);
    }

    /** Whether Almanac's regional weather decides where it rains in the level: its storms are drawn, on its weather tick. */
    public static boolean regionalWeather(ClientLevel level) {
        return LocalClimate.fromAlmanac() && AlmanacStorms.regional(level);
    }

    /**
     * Under Almanac's regional weather: its storms near the camera are asked for every second, and
     * the weather shown is Almanac's at the camera, held back only where the cloud over the camera
     * hasn't closed in yet (a storm placed by an operator, which comes in at the deck's pace).
     */
    private void followStorms(ClientLevel level, Vec3 camera) {
        if (--stormsDue <= 0) {
            refreshStorms(level, camera.x, camera.z);
        }
        storms.tick();
        pace.tickUnder(level.getRainLevel(1.0f), level.getThunderLevel(1.0f), LocalClimate.realWorldRain(level),
            LocalClimate.realWorldThunder(level), regional && shown != null && builtLevel == level ? cloudCoverOverhead() : 1.0f);
    }

    /** Asks Almanac for the storms near the camera now. */
    private void refreshStorms(ClientLevel level, double cameraX, double cameraZ) {
        long started = System.nanoTime();
        stormsDue = STORMS_EVERY;
        int reach = RenderSettingsAccess.current().sky().cloudReach();
        double tick = AlmanacStorms.weatherTick(level, 0.0f);
        List<RegionalStorm> near = AlmanacStorms.near(level, cameraX, cameraZ, reach + STORMS_BEYOND_REACH);
        storms.update(near, tick, cameraX, cameraZ, reach);
        stormsAsked = near.size();
        stormsNanos = Math.max(stormsNanos, System.nanoTime() - started);
    }

    /** LiteLights' own sky: the clouds follow the level's weather at their own pace, with the forecast in single player. */
    private void followOwnSky(Minecraft minecraft, ClientLevel level) {
        float rainDue = 0.0f;
        float thunderDue = 0.0f;
        ServerLevel server = serverLevelOf(minecraft, level);
        if (server != null) {
            WeatherData weather = server.getWeatherData();
            int clear = weather.getClearWeatherTime();
            int untilRain = WeatherForecast.ticksUntilRain(weather.isRaining(), weather.getRainTime(), clear);
            int untilThunder = WeatherForecast.ticksUntilThunder(weather.isRaining(), weather.getRainTime(), weather.isThundering(),
                weather.getThunderTime(), clear);
            rainDue = untilRain == WeatherForecast.UNKNOWN ? 0.0f : CloudWeather.dueness(untilRain, CloudWeather.RAIN_LEAD_TICKS);
            thunderDue = untilThunder == WeatherForecast.UNKNOWN ? 0.0f : CloudWeather.dueness(untilThunder, CloudWeather.THUNDER_LEAD_TICKS);
        }
        pace.tick(level.getRainLevel(1.0f), level.getThunderLevel(1.0f), LocalClimate.realWorldRain(level),
            LocalClimate.realWorldThunder(level), rainDue, thunderDue);
    }

    /**
     * How much of the sky straight over the camera holds cloud in the shown clouds, 0..1 (the
     * columns within about 32 blocks, blended across the change growing in); 0 with nothing built.
     */
    public float cloudCoverOverhead() {
        Generation after = shown;
        if (after == null || wind == null) {
            return 0.0f;
        }
        Generation before = beforeShown != null ? beforeShown : after;
        int cubeX = (int) Math.floor((camera.x - wind.driftX(skyTick)) / CUBE_BLOCKS);
        int cubeZ = (int) Math.floor((camera.z - wind.driftZ(skyTick)) / CUBE_BLOCKS);
        float was = before.coverAround(cubeX, cubeZ, COVER_AROUND);
        float now = after.coverAround(cubeX, cubeZ, COVER_AROUND);
        return was + (now - was) * progress;
    }

    /**
     * Rain and snow fall from the clouds, not from the sky over them: 1 under the top of the cloud
     * the rain falls from round the camera (the lowest run of cloud in each column: the deck, or a
     * tower out of it, not an anvil over them), easing to 0 just over it; 1 where no cloud is near
     * (a gap in the deck lets the rain through).
     */
    private float underTopsTarget(Vec3 position) {
        Generation generation = shown;
        if (generation == null || wind == null) {
            return 1.0f;
        }
        int cubeX = (int) Math.floor((position.x - wind.driftX(skyTick)) / CUBE_BLOCKS);
        int cubeZ = (int) Math.floor((position.z - wind.driftZ(skyTick)) / CUBE_BLOCKS);
        float top = generation.rainCloudTopAround(cubeX, cubeZ, TOPS_AROUND);
        if (top < 0.0f) {
            return 1.0f;
        }
        float over = (float) (position.y - generation.floor()) - top;
        return 1.0f - LiteMath.smoothstep(-TOPS_FADE_UNDER, TOPS_FADE_OVER, over);
    }

    /**
     * The game's own server's copy of this level, where its weather countdowns can be read: only
     * in single player (or a world opened to LAN), and only while the weather runs by itself.
     *
     * <p>None where a seasons mod shares each rain event out by climate (Almanac): the countdowns say
     * when the world's rain comes, not whether it reaches the player's climate, and a deck closed
     * over a place the rain then passes by is a dark lid over a sunlit day. There the clouds follow
     * the player's own share, as on a server.
     */
    private static @Nullable ServerLevel serverLevelOf(Minecraft minecraft, ClientLevel level) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || LocalClimate.sharesRainOut()) {
            return null;
        }
        ServerLevel serverLevel = server.getLevel(level.dimension());
        if (serverLevel == null || !serverLevel.canHaveWeather() || !serverLevel.getGameRules().get(GameRules.ADVANCE_WEATHER)) {
            return null;
        }
        return serverLevel;
    }

    // ---- Each frame ---------------------------------------------------------------------------

    /**
     * Before the frame's translucent pass (from LiteClouds, in vanilla's cloud prepare): takes in a
     * finished build, uploads a slice, swaps when the last change has grown in, starts the next
     * build and writes this frame's uniform.
     *
     * @param color   vanilla's cloud colour (only its alpha is used: the dimension's clouds shown at all)
     * @param bottomY the clouds' height in this dimension: the floor
     */
    public void prepare(int color, float bottomY, Vec3 cameraPosition, long gameTime, float partialTicks) {
        drawThisFrame = false;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long started = System.nanoTime();
        try {
            double tick = gameTime + (double) partialTicks;
            if (tick < lastTick - 1.0) {
                // The game time went back (a new world): nothing built so far applies.
                forget();
            }
            lastTick = tick;
            long levelSeed = ((BiomeManagerAccessor) level.getBiomeManager()).litelights$biomeZoomSeed();
            boolean regionalNow = regionalWeather(level);
            if (level != builtLevel || levelSeed != seed || bottomY != floor || regionalNow != regional) {
                // Almanac's storms ride its own wind on its own clock: a sky of another kind is built afresh.
                forget();
                builtLevel = level;
                seed = levelSeed;
                floor = bottomY;
                regional = regionalNow;
                storms.startAfresh();
                wind = regional ? almanacWind(level) : VanillaWeatherSky.windOf(levelSeed);
            }
            // After any forgetting above, which lets the face buffers go.
            ensureResources();
            skyTick = regional ? AlmanacStorms.weatherTick(level, partialTicks) : tick;
            if (windSummed != null && !windSummed.isDone()) {
                // Almanac's wind is still being summed up to now: no clouds for that moment after joining.
                RenderFeatureStatus.succeeded(RenderFeature.DYNAMIC_CLOUDS);
                return;
            }
            CloudFaceBuffer buffers = faces;
            int reach = RenderSettingsAccess.current().sky().cloudReach();
            shownReach = reach;

            if (building != null && building.isDone()) {
                Generation built = building.join();
                building = null;
                ready = built;
                readyJump = buildingJump;
                buffers.begin(built.faces(), built.faceCount());
            }
            progress = shown == null ? 1.0f : LiteMath.clamp01((float) ((tick - swapTick) / TRANSITION_TICKS));
            if (ready != null) {
                boolean now = shown == null || readyJump;
                if (buffers.uploadSome(now) && (now || progress >= 1.0f)) {
                    buffers.swap();
                    beforeShown = readyJump ? ready : shown;
                    shown = ready;
                    ready = null;
                    swapTick = tick;
                    progress = 0.0f;
                }
            }
            camera.set(cameraPosition.x, cameraPosition.y, cameraPosition.z);
            boolean jumped = shown != null && cameraMovedFar(shown, cameraPosition, skyTick, reach);
            if (building == null && (ready == null || jumped) && (shown == null || jumped || dueForBuild(tick))) {
                if (jumped && ready != null) {
                    buffers.dropPending();
                    ready = null;
                }
                if (regional && (shown == null || jumped)) {
                    // A new view: the storms standing in it now, drawn whole.
                    storms.startAfresh();
                    refreshStorms(level, cameraPosition.x, cameraPosition.z);
                }
                startBuild(skyTick, cameraPosition, reach, shown == null || jumped);
            }

            clearColour(color);
            drawFaces = buffers.drawnFaces();
            if (shown != null && drawFaces > 0) {
                writeInfo(cameraPosition, skyTick, reach);
                RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(6 * drawFaces);
                drawThisFrame = true;
            }
            RenderFeatureStatus.succeeded(RenderFeature.DYNAMIC_CLOUDS);
        } catch (RuntimeException error) {
            forget();
            RenderFeatureStatus.failed(RenderFeature.DYNAMIC_CLOUDS, error);
        }
        long spent = System.nanoTime() - started;
        prepareNanos += spent;
        mostPrepareNanos = Math.max(mostPrepareNanos, spent);
        prepareFrames++;
    }

    /**
     * The clouds' colour for the time of day alone. Vanilla's cloud colour also greys the whole
     * sky's clouds in rain and nearly blacks them out in thunder, which is right for a flat layer
     * seen from below but leaves a storm's sunlit top black from above; here each face darkens by
     * its own storminess and depth instead (dynamic.vsh). The daylight is the frame's (it follows
     * the settings screen's time preview); the night's colour is vanilla's clear night's.
     */
    private void clearColour(int vanilla) {
        float daylight = LiteMath.smoothstep(0.0f, 1.0f, LiteLightsRenderer.get().frame().sunColor.w);
        colourRed = LiteMath.lerp(NIGHT_RED, 1.0f, daylight);
        colourGreen = LiteMath.lerp(NIGHT_GREEN, 1.0f, daylight);
        colourBlue = LiteMath.lerp(NIGHT_BLUE, 1.0f, daylight);
        moonlight = 1.0f;
        if (ARGB.alpha(vanilla) == 0) {
            colourRed = colourGreen = colourBlue = 0.0f;
            moonlight = 0.0f;
        }
    }

    /**
     * Almanac's wind for a level, summed on a worker up to a while past now before the first build
     * (a long-lived world has a long way to sum).
     */
    private CloudDrift almanacWind(ClientLevel level) {
        // The workers sum it.
        ensureResources();
        IntegratedWind almanac = AlmanacStorms.wind(level);
        double until = AlmanacStorms.weatherTick(level, 0.0f) + WIND_AHEAD;
        windSummed = builder.run(() -> almanac.prepare(until));
        return almanac;
    }

    /** The next build is due once the shown change has grown in (later, if the sky is standing still). */
    private boolean dueForBuild(double tick) {
        double wait = TRANSITION_TICKS * (quietBuilds > 0 ? QUIET_TRANSITIONS : 1);
        // The next build starts while the shown change still grows, so it is ready as that ends.
        return tick - swapTick >= wait - TRANSITION_TICKS * 0.5;
    }

    private void startBuild(double tick, Vec3 cameraPosition, int reach, boolean jump) {
        Generation previous = jump ? null : shown;
        if (shown != null && !jump) {
            quietBuilds = shown.movingFaces() == 0 ? quietBuilds + 1 : 0;
        }
        // A previewed weather shows its clouds at once; the world's weather comes at the clouds' pace.
        float rain = WeatherPreview.rain(pace.rain());
        float thunder = WeatherPreview.thunder(pace.thunder());
        // Almanac's storms, unless the settings screen previews a weather of LiteLights' own over them.
        List<RegionalStorm> drawnStorms = regional && !WeatherPreview.active() ? storms.storms() : null;
        building = builder.build(new CloudBuilder.Request(seed, floor, tick, rain, thunder, cameraPosition.x, cameraPosition.z, reach, previous,
            builds++, drawnStorms, wind));
        buildingJump = jump;
    }

    private boolean cameraMovedFar(Generation generation, Vec3 cameraPosition, double tick, int reach) {
        double dx = cameraPosition.x - wind.driftX(tick) - generation.cameraCloudX();
        double dz = cameraPosition.z - wind.driftZ(tick) - generation.cameraCloudZ();
        double limit = Math.min(reach, CloudRings.NEAR_RING) * JUMP_SHARE;
        return dx * dx + dz * dz > limit * limit;
    }

    private void writeInfo(Vec3 cameraPosition, double tick, int reach) {
        Generation generation = shown;
        double originX = (double) generation.originX() * CUBE_BLOCKS + wind.driftX(tick) - cameraPosition.x;
        double originZ = (double) generation.originZ() * CUBE_BLOCKS + wind.driftZ(tick) - cameraPosition.z;
        float originY = (float) (generation.floor() - cameraPosition.y);
        // A fresh slot every prepare: the level can be drawn twice in a frame (LiteClouds' note).
        info.rotate();
        try (GpuBufferSlice.MappedView view = info.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                .putVec4((float) originX, originY, (float) originZ, FaceFormat.CELL_BLOCKS)
                .putVec4(colourRed, colourGreen, colourBlue, moonlight)
                .putVec4(progress, reach * (1.0f - FADE_SHARE), reach, inside);
        }
    }

    /** Whether a pass before translucent terrain is wanted this frame (mixin.water.LevelRendererWaterMixin). */
    public boolean wantsPassBeforeTranslucents() {
        return drawThisFrame;
    }

    /** Draws the opaque cubes into the main target, after the solid world and before translucents. */
    public void drawBeforeTranslucents(RenderTarget mainTarget) {
        if (!drawThisFrame) {
            return;
        }
        drawThisFrame = false;
        GpuBuffer drawn = faces != null ? faces.drawn() : null;
        if (drawn == null || info == null || mainTarget.getDepthTextureView() == null) {
            return;
        }
        if (RenderSettingsAccess.current().sky().cloudShadows() && shown != null && wind != null) {
            try {
                if (shadows == null) {
                    shadows = new CloudShadows();
                }
                Generation before = beforeShown != null ? beforeShown : shown;
                shadows.draw(mainTarget, before, shown, progress, camera.x, camera.y, camera.z, skyTick, wind, shownReach);
            } catch (RuntimeException error) {
                RenderFeatureStatus.failed(RenderFeature.DYNAMIC_CLOUDS, error);
                return;
            }
        }
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights dynamic clouds", mainTarget.getColorTextureView(), Optional.empty(),
                mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsRenderer.get().bindWorldUniforms(pass);
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.setUniform("DynamicTransforms", transforms);
            pass.setIndexBuffer(indices.getBuffer(), indices.type());
            pass.setVertexBuffer(0, null);
            pass.setUniform("DynamicCloudInfo", info.currentBuffer());
            pass.setUniform("DynamicCloudFaces", drawn);
            pass.drawIndexed(6 * drawFaces, 1, 0, 0, 0);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.DYNAMIC_CLOUDS, error);
        }
    }

    /**
     * The level's far plane, pushed out so the farthest cloud in reach is in front of it (the
     * camera works it out from the render distance and vanilla's cloud range, which may be shorter).
     */
    public float farPlane(float vanilla) {
        if (shown == null || !chosen(RenderSettingsAccess.current())) {
            return vanilla;
        }
        float reach = RenderSettingsAccess.current().sky().cloudReach();
        // From under the clouds or high over them, the farthest cube is at the reach's edge, at the
        // far end of the clouds' heights from the camera.
        float vertical = Math.max(Math.abs(floor - (float) camera.y), Math.abs(floor + TALLEST_CLOUD - (float) camera.y));
        return Math.max(vanilla, (float) Math.sqrt(reach * reach + vertical * vertical) + 64.0f);
    }

    // ---- Inside a cloud -----------------------------------------------------------------------

    /**
     * After sky-fog's frame update: inside a cloud (or within a few blocks of its face), the storm
     * fog thickens to the cloud's colour, so the cloud closes in around the camera before its faces
     * are behind it.
     */
    private void onFrameUpdate(FrameState frame, RenderSettings settings) {
        Generation generation = shown;
        inside = 0.0f;
        if (generation == null || !chosen(settings) || wind == null || builtLevel != Minecraft.getInstance().level) {
            return;
        }
        inside = insideAmount(generation, beforeShown != null ? beforeShown : generation, progress);
        if (inside <= 0.001f) {
            inside = 0.0f;
            return;
        }
        float density = INSIDE_FOG_DENSITY * inside;
        float existing = frame.stormFog.w;
        float weight = density / (existing + density);
        float shade = insideShade();
        frame.stormFog.set(LiteMath.lerp(frame.stormFog.x, colourRed * shade, weight), LiteMath.lerp(frame.stormFog.y, colourGreen * shade, weight),
            LiteMath.lerp(frame.stormFog.z, colourBlue * shade, weight), Math.max(existing, density));
        frame.skyInfo.w = Math.max(frame.skyInfo.w, inside);
        frame.featureFlags |= FrameFlags.FEATURE_STORM_FOG;
    }

    /**
     * Vanilla's fog (mixin.skyfog.CloudInsideFogMixin), for when LiteLights' own fog doesn't replace
     * it (its Fog look set to vanilla's): inside a cloud it closes in over the same few dozen blocks
     * as the storm fog does with LiteLights' fog, toward the cloud's own colour. The clouds' faces
     * take the same white-out in their shader.
     */
    public void adjustVanillaFog(FogData fog) {
        if (inside <= 0.0f || (LiteLightsRenderer.get().frame().featureFlags & FrameFlags.FEATURE_LITE_FOG) != 0) {
            return;
        }
        float sight = LiteMath.lerp(fog.environmentalEnd, INSIDE_SIGHT, inside);
        fog.environmentalStart = Math.min(fog.environmentalStart, LiteMath.lerp(fog.environmentalStart, 0.0f, inside));
        fog.environmentalEnd = Math.min(fog.environmentalEnd, sight);
        fog.renderDistanceStart = Math.min(fog.renderDistanceStart, LiteMath.lerp(fog.renderDistanceStart, 0.0f, inside));
        fog.renderDistanceEnd = Math.min(fog.renderDistanceEnd, sight);
        fog.skyEnd = Math.min(fog.skyEnd, sight);
        fog.cloudEnd = Math.min(fog.cloudEnd, sight);
        float shade = insideShade();
        fog.color.set(LiteMath.lerp(fog.color.x, colourRed * shade, inside), LiteMath.lerp(fog.color.y, colourGreen * shade, inside),
            LiteMath.lerp(fog.color.z, colourBlue * shade, inside), fog.color.w);
    }

    /** The cloud's own colour seen from inside, as its sides are lit, darker for the rain and storms over it. */
    private float insideShade() {
        return 0.82f * (1.0f - 0.3f * pace.shownRain(1.0f) - 0.2f * pace.shownThunder(1.0f));
    }

    /**
     * How far inside cloud the camera is, 0..1: the cells' solidity blended between the cell
     * middles around it (and between the change's two builds by its progress), 0.5 on a face.
     */
    private float insideAmount(Generation after, Generation before, float change) {
        double cloudX = camera.x - wind.driftX(skyTick);
        double cloudZ = camera.z - wind.driftZ(skyTick);
        double cellX = cloudX / CUBE_BLOCKS - 0.5;
        double cellZ = cloudZ / CUBE_BLOCKS - 0.5;
        double cellY = (camera.y - after.floor()) / FaceFormat.CELL_BLOCKS - 0.5;
        if (cellY < -2.0 || cellY > CloudCells.HEIGHT + 1.0) {
            return 0.0f;
        }
        int x0 = (int) Math.floor(cellX);
        int y0 = (int) Math.floor(cellY);
        int z0 = (int) Math.floor(cellZ);
        double fx = cellX - x0;
        double fy = cellY - y0;
        double fz = cellZ - z0;
        double solid = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            int x = x0 + (corner & 1);
            int y = y0 + (corner >> 1 & 1);
            int z = z0 + (corner >> 2 & 1);
            double weight = ((corner & 1) != 0 ? fx : 1.0 - fx) * ((corner >> 1 & 1) != 0 ? fy : 1.0 - fy) * ((corner >> 2 & 1) != 0 ? fz : 1.0 - fz);
            if (weight <= 0.0) {
                continue;
            }
            double now = after.solid(x, z, y) ? 1.0 : 0.0;
            double was = before.solid(x, z, y) ? 1.0 : 0.0;
            solid += weight * (was + (now - was) * change);
        }
        return LiteMath.smoothstep(0.2f, 0.65f, (float) solid);
    }

    // ---- Lifecycle ----------------------------------------------------------------------------

    private void ensureResources() {
        if (builder == null) {
            builder = new CloudBuilder();
        }
        if (faces == null) {
            faces = new CloudFaceBuffer();
        }
        if (info == null) {
            info = new MappableRingBuffer(() -> "LiteLights dynamic clouds info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, INFO_SIZE);
        }
    }

    /** Drops every generation (a new level, floor or seed); the next prepare builds afresh. */
    private void forget() {
        if (building != null) {
            building.cancel(false);
            building = null;
        }
        ready = null;
        shown = null;
        beforeShown = null;
        quietBuilds = 0;
        drawThisFrame = false;
        if (faces != null) {
            faces.close();
            faces = null;
        }
        if (shadows != null) {
            shadows.forget();
        }
    }

    /** Lets go of the workers and the GPU memory, a while after the choice was switched off or the world left. */
    private void release() {
        if (builder == null && faces == null && info == null) {
            return;
        }
        forget();
        if (builder != null) {
            builder.close();
            builder = null;
        }
        if (info != null) {
            info.close();
            info = null;
        }
        if (shadows != null) {
            shadows.close();
            shadows = null;
        }
        builtLevel = null;
        floor = Float.NaN;
        windSummed = null;
    }

    // ---- For QA -------------------------------------------------------------------------------

    /** The shown generation's faces, or 0. */
    public int shownFaces() {
        return shown != null ? shown.faceCount() : 0;
    }

    /** The shown generation, for QA's readings. */
    public @Nullable Generation shownGeneration() {
        return shown;
    }

    /**
     * The render thread's time in prepare since the last call, per frame: {mean, most} in
     * milliseconds. Starts counting again.
     */
    public double[] takePrepareMillis() {
        double[] millis = {prepareFrames == 0 ? 0.0 : prepareNanos / 1.0e6 / prepareFrames, mostPrepareNanos / 1.0e6};
        prepareNanos = 0L;
        mostPrepareNanos = 0L;
        prepareFrames = 0;
        return millis;
    }

    /** The wind the shown clouds ride (LiteLights' own, or Almanac's), or null before the first build. */
    public @Nullable CloudDrift drift() {
        return wind;
    }

    /** The field's tick this frame: the game time, or Almanac's weather tick under its regional weather. */
    public double skyTick() {
        return skyTick;
    }

    /** For QA: {storms the last ask listed, the longest ask in milliseconds since the last call}; starts counting again. */
    public double[] takeStormAsks() {
        double[] asks = {stormsAsked, stormsNanos / 1.0e6};
        stormsNanos = 0L;
        return asks;
    }

    /** Whether the shown clouds are Almanac's storms. */
    public boolean drawsRegionalWeather() {
        return regional;
    }

    /** The clouds' own rain and thunder levels (how far the deck and towers have built). */
    public float deckLevel() {
        return pace.rain();
    }

    public float towerLevel() {
        return pace.thunder();
    }

    /** A camera position kept without allocating. */
    private static final class Vec3Mutable {
        double x;
        double y;
        double z;

        void set(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
