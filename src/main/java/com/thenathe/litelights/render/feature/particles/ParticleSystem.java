package com.thenathe.litelights.render.feature.particles;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.config.LiteLightsOptions;
import com.thenathe.litelights.config.PreviewEvent;
import com.thenathe.litelights.config.PreviewWeather;
import com.thenathe.litelights.mixin.particles.BiomeManagerAccessor;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.weather.SkyEvents;
import com.thenathe.litelights.render.feature.weather.WeatherCompat;
import com.thenathe.litelights.render.feature.weather.WeatherParticles;
import com.thenathe.litelights.render.feature.weather.Wind;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.FrameState;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * LiteLights' particles and weather: owns the particle state, runs the spawners every client tick,
 * steps the simulation on its worker, and hands each frame's quads to the submit API. Also drives
 * the night sky events.
 *
 * <p>Per tick, on the client thread: wait for last tick's step, make its result the published
 * state, look at the surroundings, queue new particles within the cap, start the next step. Per
 * frame, on the same thread: build quads from the published state. The worker only reads the
 * published state, the spawn queue and the column grid, and only writes the other state, so
 * nothing is shared while it runs.
 */
public final class ParticleSystem {
    // Diagnostics slots: totals since the last reset, and where a tick's time goes, for the QA probe.
    // Declared before INSTANCE so they exist when it is constructed.
    private static final int TICK_NANOS = 0;
    private static final int TICKS = 1;
    private static final int FRAME_NANOS = 2;
    private static final int FRAMES = 3;
    private static final int SKY_NANOS = 4;
    private static final int PARTICLES_DRAWN = 5;
    private static final int VERTEX_NANOS = 6;
    private static final String[] PART_NAMES = {"wait", "surroundings", "grid", "weather", "ambient", "fire", "dimension"};
    private static final int PART_WAIT = 0;
    private static final int PART_SURROUNDINGS = 1;
    private static final int PART_GRID = 2;
    private static final int PART_WEATHER = 3;
    private static final int PART_AMBIENT = 4;
    private static final int PART_FIRE = 5;
    private static final int PART_DIMENSION = 6;

    private static final ParticleSystem INSTANCE = new ParticleSystem();
    /** The far-off bang of a distant firework arrives this many ticks after its flash. */
    private static final int FIREWORK_SOUND_DELAY = 30;
    /**
     * Where the bang is played from, in blocks toward the burst and up, and how loud. Minecraft
     * plays a sound at its volume (at most 1) and fades it linearly to silence at 16 blocks times
     * the volume (at least 16).
     */
    private static final double FIREWORK_SOUND_DISTANCE = 8.0;
    private static final double FIREWORK_SOUND_HEIGHT = 3.0;
    private static final float FIREWORK_SOUND_VOLUME = 0.33f;
    /** Rows of features this system draws, marked ready on its first frame unless another mod handles them. */
    private static final String[] PARTICLE_ROWS = {
        "particles", "ambient_particles", "particles.snow_sand_sprites", "particles.enhanced_fire", "particles.ember_veins",
        "particles.burn_marks", "weather.precipitation", "weather.rain_mist", "weather.drifting_sand", "weather.sandstorms",
        "weather.storm_atmosphere", "end_motes", "weather.rain_drips", "weather.wall_streaks"
    };
    /** Submit orders for fire's decals: after vanilla's own (order 0), soot before veins. */
    private static final int BURN_MARK_ORDER = 1;
    private static final int EMBER_VEIN_ORDER = 2;
    /** The sky-event rows drawn here; meteor showers are the sky pass's (SkyFogFeature marks that row). */
    private static final String[] SKY_ROWS = {"weather.polar_lights", "weather.far_fireworks"};

    private ParticleState published = new ParticleState(ParticleBudget.totalCap(RenderSettings.ParticleDensity.FULL));
    private ParticleState next = new ParticleState(ParticleBudget.totalCap(RenderSettings.ParticleDensity.FULL));
    /** Each category's share of the cap and its range, multiplied (by ordinal); see ParticleReach. */
    private final float[] capScales = ParticleBudget.unscaled();
    private final float[] rangeScales = ParticleBudget.unscaled();
    private final SpawnQueue queue = new SpawnQueue();
    private final ColumnGrid grid = new ColumnGrid();
    private final Surroundings surroundings = new Surroundings();
    private final FireEffects fire = new FireEffects();
    private final Simulation.Inputs inputs = new Simulation.Inputs();
    private final ParticleSprites sprites = new ParticleSprites();
    private final ParticleRenderer renderer = new ParticleRenderer(sprites);
    private final SkyEvents sky = new SkyEvents();
    private final HazeDrift hazeDrift = new HazeDrift();
    private final SimulationWorker worker = new SimulationWorker(this::stepOnWorker);
    /** Worker-thread time spent stepping, summed (written by the worker only; read for the QA probe). */
    private volatile long workerNanos;
    private final float[] burst = new float[5];

    private boolean stepPending;
    private ClientLevel lastLevel;
    private boolean rowsMarked;
    private boolean skyRowsMarked;

    private final long[] partNanos = new long[PART_NAMES.length];

    private long mark(int part, long since) {
        long now = System.nanoTime();
        partNanos[part] += now - since;
        return now;
    }

    // Timings for the QA probe: client-thread nanoseconds spent per tick and per frame, and totals
    // since the last reset.
    private long lastTickNanos;
    private long lastFrameNanos;
    private final long[] totals = new long[7];

    private ParticleSystem() {
    }

    public static ParticleSystem get() {
        return INSTANCE;
    }

    /** Called once from the client entrypoint. */
    public void register() {
        SkyEvents.claimPipelines();
        ParticleLayers.claimPipelines();
        HazeField.get().register();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LevelRenderEvents.COLLECT_SUBMITS.register(this::collect);
        LiteRenderEvents.FRAME_UPDATE.register(this::frameUpdate);
        LiteRenderEvents.AFTER_SKY.addPhaseOrdering(Event.DEFAULT_PHASE, SkyEvents.DRAW_PHASE);
        LiteRenderEvents.AFTER_SKY.register(SkyEvents.DRAW_PHASE, this::afterSky);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> worker.stop());
        markHandledRows();
    }

    /** Rows whose effect another loaded mod draws instead: they say so, and stay that way. */
    private static void markHandledRows() {
        if (WeatherCompat.precipitationHandledElsewhere()) {
            Feature.of("weather.precipitation").markHandledBy(WeatherCompat.PARTICLE_RAIN);
            Feature.of("particles.snow_sand_sprites").markHandledBy(WeatherCompat.PARTICLE_RAIN);
        }
        if (WeatherCompat.sandstormsHandledElsewhere()) {
            Feature.of("weather.sandstorms").markHandledBy(WeatherCompat.sandstormHandler());
        }
        if (WeatherCompat.windHandledElsewhere()) {
            Feature.of("weather.drifting_sand").markHandledBy(WeatherCompat.IMMERSIVE_STORMS);
        }
        if (WeatherCompat.stormAtmosphereHandledElsewhere()) {
            Feature.of("weather.storm_atmosphere").markHandledBy(WeatherCompat.IMMERSIVE_STORMS);
        }
        if (WeatherCompat.wallStreaksHandledElsewhere()) {
            Feature.of("weather.wall_streaks").markHandledBy(WeatherCompat.PARTICLE_RAIN);
        }
        if (WeatherCompat.densityHandledElsewhere()) {
            Feature.of("weather.density").markHandledBy(WeatherCompat.PARTICLE_RAIN_AND_IMMERSIVE_STORMS);
        }
        // A sandstorm's and a haboob's fog is all Storm atmosphere's, and their dust clouds are
        // sandstorm particles. The other storms' rows stay: their haze and mist are LiteLights' own.
        for (LiteLightsOptions.StormType type : new LiteLightsOptions.StormType[] {
                LiteLightsOptions.StormType.SANDSTORM, LiteLightsOptions.StormType.HABOOB}) {
            LiteLightsOptions.StormRows rows = LiteLightsOptions.storm(type);
            if (WeatherCompat.stormAtmosphereHandledElsewhere()) {
                Feature.of(rows.fog().id()).markHandledBy(WeatherCompat.IMMERSIVE_STORMS);
            }
            if (WeatherCompat.sandstormsHandledElsewhere()) {
                Feature.of(rows.clouds().id()).markHandledBy(WeatherCompat.sandstormHandler());
            }
        }
    }

    private static boolean handledElsewhere(String row) {
        return switch (row) {
            case "weather.precipitation", "particles.snow_sand_sprites" -> WeatherCompat.precipitationHandledElsewhere();
            case "weather.sandstorms" -> WeatherCompat.sandstormsHandledElsewhere();
            case "weather.drifting_sand" -> WeatherCompat.windHandledElsewhere();
            case "weather.storm_atmosphere" -> WeatherCompat.stormAtmosphereHandledElsewhere();
            case "weather.wall_streaks" -> WeatherCompat.wallStreaksHandledElsewhere();
            default -> false;
        };
    }

    private void tick(Minecraft minecraft) {
        long started = System.nanoTime();
        publishLastStep(started);

        RenderSettings settings = RenderSettingsAccess.current();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            if (lastLevel != null) {
                onWorldLeft();
            }
            WeatherParticles.setReplacingVanilla(false);
            return;
        }
        if (level != lastLevel) {
            clear();
            lastLevel = level;
        }
        if (minecraft.isPaused()) {
            return;
        }
        if (!anythingOn(settings)) {
            // Everything this stream draws is switched off: no spawning, no stepping, no sky work.
            if (published.count > 0 || fire.hasDecals()) {
                clear();
            }
            WeatherParticles.setReplacingVanilla(false);
            HazeField.get().clear();
            sky.update(settings.weather(), false, 0L, 0L, 0L, 0.0f, 0.0f);
            return;
        }

        long part = System.nanoTime();
        surroundings.update(level, Surroundings.camera(), settings);
        boolean running = RenderFeatureStatus.mayRun(RenderFeature.PARTICLES);
        WeatherParticles.setReplacingVanilla(running && surroundings.weatherAllowed && WeatherParticles.wantsToReplaceVanilla(settings));
        updateSky(level, settings);
        part = mark(PART_SURROUNDINGS, part);

        RenderSettings.ParticleDensity amount = running ? settings.particles().density() : RenderSettings.ParticleDensity.OFF;
        fitToReach(settings);
        queue.begin(amount, capScales, published.categoryCounts, published.count);
        hazeDrift.tick(surroundings.wind[0], surroundings.wind[1]);
        surroundings.hazeDriftX = hazeDrift.x();
        surroundings.hazeDriftZ = hazeDrift.z();
        HazeField.get().beginTick();
        if (amount != RenderSettings.ParticleDensity.OFF) {
            spawn(level, part);
        } else {
            fire.clear();
        }
        HazeField.get().endTick(amount != RenderSettings.ParticleDensity.OFF && HazeField.wanted(settings), surroundings);
        if (published.count > 0 || queue.count > 0) {
            startStep(amount);
        }
        lastTickNanos = System.nanoTime() - started;
        totals[TICK_NANOS] += lastTickNanos;
        totals[TICKS]++;
    }

    /**
     * Works out the cap and range scales for the Weather and Particle distances, and resizes the
     * particle states, the spawn queue and the column grid when they change. Only between steps;
     * arrays are allocated only when a setting changes what they must hold.
     */
    private void fitToReach(RenderSettings settings) {
        ParticleReach.capScales(settings.weather(), settings.particles(), capScales);
        ParticleReach.rangeScales(settings.weather(), settings.particles(), rangeScales);
        // Sized for Full whatever the amount, so changing the amount alone never reallocates.
        int wanted = ParticleBudget.totalCap(RenderSettings.ParticleDensity.FULL, capScales);
        if (wanted > published.capacity || wanted < published.capacity / 2) {
            published = published.resized(wanted);
            next = new ParticleState(wanted);
        }
        float fill = Math.max(surroundings.weatherFill, surroundings.ambientFill);
        queue.ensureCapacity((int) Math.ceil(SpawnQueue.BASE_CAPACITY * fill));
        grid.resize(ParticleReach.gridHalf(settings.weather().distance()));
    }

    /** Waits for the worker's step and makes its result the published state. */
    private void publishLastStep(long started) {
        Throwable failure = worker.await();
        mark(PART_WAIT, started);
        if (stepPending) {
            ParticleState stepped = next;
            next = published;
            published = stepped;
            stepPending = false;
        }
        if (failure != null) {
            RenderFeatureStatus.failed(RenderFeature.PARTICLES, failure);
            clear();
        }
    }

    /** Queues this tick's new particles from every spawner, timing each for the QA probe. */
    private void spawn(ClientLevel level, long part) {
        try {
            if (surroundings.overworldSky) {
                grid.update(level, surroundings.cameraX, surroundings.cameraZ);
            }
            part = mark(PART_GRID, part);
            WeatherParticles.spawn(surroundings, queue, grid);
            part = mark(PART_WEATHER, part);
            AmbientParticles.spawn(surroundings, queue);
            part = mark(PART_AMBIENT, part);
            fire.spawn(surroundings, queue);
            part = mark(PART_FIRE, part);
            DimensionAmbience.spawn(surroundings, queue);
            mark(PART_DIMENSION, part);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.PARTICLES, error);
            queue.begin(RenderSettings.ParticleDensity.OFF, published.categoryCounts, published.count);
        }
    }

    private void stepOnWorker() {
        long started = System.nanoTime();
        Simulation.step(published, next, queue, grid, inputs);
        workerNanos += System.nanoTime() - started;
    }

    /** Hands the worker this tick's camera and wind and starts the next simulation step. */
    private void startStep(RenderSettings.ParticleDensity amount) {
        inputs.cameraX = surroundings.cameraX;
        inputs.cameraY = surroundings.cameraY;
        inputs.cameraZ = surroundings.cameraZ;
        inputs.windX = surroundings.wind[0];
        inputs.windZ = surroundings.wind[1];
        inputs.cap = ParticleBudget.totalCap(amount, capScales);
        System.arraycopy(rangeScales, 0, inputs.rangeScales, 0, rangeScales.length);
        worker.launch();
        stepPending = true;
    }

    private static boolean anythingOn(RenderSettings settings) {
        RenderSettings.Weather weather = settings.weather();
        return settings.particles().density() != RenderSettings.ParticleDensity.OFF
            || weather.polarLights() || weather.farFireworks();
    }

    private void updateSky(ClientLevel level, RenderSettings settings) {
        float daylight = surroundings.daylight;
        float darkness = Math.clamp((0.4f - daylight) / 0.3f, 0.0f, 1.0f);
        // A previewed event shows even while it really rains, unless a weather is previewed with it.
        float rain = PreviewEvent.active() && !PreviewWeather.active() ? 0.0f : surroundings.rain;
        float visibility = darkness * (1.0f - rain);
        float temperature = level.getBiome(surroundings.cursor.set(surroundings.cameraX, surroundings.cameraY, surroundings.cameraZ))
            .value().getBaseTemperature();
        float coldness = Math.clamp((0.35f - temperature) / 0.2f, 0.0f, 1.0f);
        boolean skyAllowed = surroundings.overworldSky && surroundings.weatherAllowed && !surroundings.underground
            && RenderFeatureStatus.mayRun(RenderFeature.SKY_EVENTS);
        sky.update(settings.weather(), skyAllowed, surroundings.seed, surroundings.clockTime, surroundings.gameTime, coldness, visibility);
        if (sky.burstStartedAt(surroundings.gameTime - FIREWORK_SOUND_DELAY, burst)) {
            // Vanilla's far blast, which already sounds distant, from the burst's direction. It is
            // placed near the camera on purpose: placed 60 blocks out at volume 2.5 (as before
            // 0.1.7) it was never heard. 8.5 blocks away, in a 16-block fade, at volume 0.33, it is
            // heard at 0.33 x (1 - 8.5 / 16) = 0.15 of full loudness: a third of 0.9.7's 0.47
            // (16 and 6 blocks away and up, in the 32-block fade of volume 2, played at 1).
            double x = surroundings.cameraX + Math.sin(burst[0]) * FIREWORK_SOUND_DISTANCE;
            double z = surroundings.cameraZ - Math.cos(burst[0]) * FIREWORK_SOUND_DISTANCE;
            level.playLocalSound(x, surroundings.cameraY + FIREWORK_SOUND_HEIGHT, z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST_FAR, SoundSource.AMBIENT,
                FIREWORK_SOUND_VOLUME, 0.85f + surroundings.random.nextFloat() * 0.2f, false);
        }
    }

    /** An atlas was stitched again; the particle atlas holds this system's sprites. */
    public void onAtlasUploaded(TextureAtlas atlas) {
        if (atlas.location().equals(TextureAtlas.LOCATION_PARTICLES)) {
            sprites.forget();
        }
    }

    /** The player left the world: drops every particle and the level they were spawned in. */
    public void onWorldLeft() {
        clear();
        lastLevel = null;
        surroundings.level = null;
    }

    private void clear() {
        worker.await();
        stepPending = false;
        published.clear();
        next.clear();
        grid.forget();
        fire.clear();
        HazeField.get().clear();
    }

    private void collect(LevelRenderContext context) {
        if (published.count == 0 && !fire.hasDecals() || !RenderFeatureStatus.mayRun(RenderFeature.PARTICLES)) {
            return;
        }
        long started = System.nanoTime();
        // Vanilla built last frame's vertices after this method returned; count that time too.
        totals[VERTEX_NANOS] += renderer.batch().takeBuildNanos() + renderer.burnMarkBatch().takeBuildNanos()
            + renderer.emberVeinBatch().takeBuildNanos();
        try {
            if (!sprites.refresh()) {
                return;
            }
            CameraRenderState camera = context.levelState().cameraRenderState;
            RenderSettings settings = RenderSettingsAccess.current();
            boolean hdStyle = settings.particles().snowSandSprites() == RenderSettings.SnowSandSprites.DETAILED;
            FrameState frame = LiteLightsRenderer.get().frame();
            renderer.build(published, camera.pos.x, camera.pos.y, camera.pos.z, camera.orientation,
                frame.viewProjection, context.levelState().worldPartialTicks, hdStyle, rangeScales, stormFogOverParticles(frame),
                settings.weather().mistStyle());
            if (fire.hasDecals()) {
                // Wraps once an hour, so the pulse keeps a float's precision on old worlds.
                float seconds = (surroundings.gameTime % 72000L + context.levelState().worldPartialTicks) / 20.0f;
                fire.drawDecals(renderer, sprites, camera.pos.x, camera.pos.y, camera.pos.z, seconds);
            }
            SubmitNodeCollector collector = context.submitNodeCollector();
            boolean drew = !renderer.batch().isEmpty();
            if (drew) {
                collector.submitQuadParticleGroup(renderer.batch());
            }
            // The decals go after everything else in the solid phase, in their own orders, so the
            // soot is laid on the face first and the veins glow over it.
            if (!renderer.burnMarkBatch().isEmpty()) {
                collector.order(BURN_MARK_ORDER).submitQuadParticleGroup(renderer.burnMarkBatch());
                drew = true;
            }
            if (!renderer.emberVeinBatch().isEmpty()) {
                collector.order(EMBER_VEIN_ORDER).submitQuadParticleGroup(renderer.emberVeinBatch());
                drew = true;
            }
            if (drew) {
                if (!rowsMarked) {
                    rowsMarked = true;
                    for (String row : PARTICLE_ROWS) {
                        if (!handledElsewhere(row)) {
                            RenderFeatureStatus.markReady(row);
                        }
                    }
                }
            }
            RenderFeatureStatus.succeeded(RenderFeature.PARTICLES);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.PARTICLES, error);
        }
        lastFrameNanos = System.nanoTime() - started;
        totals[FRAME_NANOS] += lastFrameNanos;
        totals[FRAMES]++;
        totals[PARTICLES_DRAWN] += renderer.drawnLastFrame();
    }

    /**
     * The storm fog's density per block as the soft particles get it this frame, or 0 where they
     * don't (LiteLights' fog or haze off, no storm fog, or the dimension's weather switched off).
     */
    private static float stormFogOverParticles(FrameState frame) {
        int needed = FrameFlags.FEATURE_LITE_FOG | FrameFlags.FEATURE_HAZE | FrameFlags.FEATURE_STORM_FOG;
        return (frame.featureFlags & needed) == needed ? frame.stormFog.w : 0.0f;
    }

    private void afterSky(RenderTarget target, FrameState frame) {
        if (!RenderFeatureStatus.mayRun(RenderFeature.SKY_EVENTS)) {
            return;
        }
        long started = System.nanoTime();
        try {
            sky.draw(target, frame);
            if (!skyRowsMarked && lastLevel != null && surroundings.overworldSky) {
                skyRowsMarked = true;
                sky.compilePipelines();
                RenderFeatureStatus.markReady(SKY_ROWS);
            }
            RenderFeatureStatus.succeeded(RenderFeature.SKY_EVENTS);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.SKY_EVENTS, error);
        }
        totals[SKY_NANOS] += System.nanoTime() - started;
    }

    /**
     * This stream's values for shaders: featureValuesD.y, the wind's heading in radians, which the
     * sway follows, worked out even with every particle off; featureValuesB.xyz, the haze's drift.
     */
    private void frameUpdate(FrameState frame, RenderSettings settings) {
        HazeDepth.get().beginFrame();
        // The haze's noise drift (featureValuesB.xyz), between the last two ticks: still while paused.
        hazeDrift.write(frame.featureValuesB, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            long seed = ((BiomeManagerAccessor) level.getBiomeManager()).litelights$biomeZoomSeed();
            frame.featureValuesD.y = (float) Wind.heading(seed, level.getOverworldClockTime(), level.getGameTime());
        }
    }

    // --- Diagnostics for the QA probe ---

    public int liveParticles() {
        return published.count;
    }

    public int liveInCategory(ParticleCategory category) {
        return published.categoryCounts[category.ordinal()];
    }

    /** How many live particles are of one kind (QA only: walks the whole state). */
    public int liveOfKind(ParticleKind kind) {
        int count = 0;
        ParticleState state = published;
        for (int i = 0; i < state.count; i++) {
            if (state.kind[i] == kind.id) {
                count++;
            }
        }
        return count;
    }

    /** Where the live particles of one kind are, as x, y, z triples (QA only: walks the whole state). */
    public double[] positionsOfKind(ParticleKind kind) {
        ParticleState state = published;
        int count = 0;
        for (int i = 0; i < state.count; i++) {
            if (state.kind[i] == kind.id) {
                count++;
            }
        }
        double[] positions = new double[count * 3];
        int at = 0;
        for (int i = 0; i < state.count; i++) {
            if (state.kind[i] == kind.id) {
                positions[at++] = state.x[i];
                positions[at++] = state.y[i];
                positions[at++] = state.z[i];
            }
        }
        return positions;
    }

    public int drawnLastFrame() {
        return renderer.drawnLastFrame();
    }

    public long lastTickNanos() {
        return lastTickNanos;
    }

    public long lastFrameNanos() {
        return lastFrameNanos;
    }

    public void resetTotals() {
        workerNanos = 0L;
        Arrays.fill(totals, 0L);
        Arrays.fill(partNanos, 0L);
    }

    /** Average milliseconds per tick for each part of the tick since the last reset. */
    public Map<String, Double> tickParts() {
        Map<String, Double> parts = new LinkedHashMap<>();
        for (int i = 0; i < PART_NAMES.length; i++) {
            parts.put(PART_NAMES[i], totals[TICKS] == 0 ? 0.0 : partNanos[i] / 1.0e6 / totals[TICKS]);
        }
        return parts;
    }

    /** Average worker-thread milliseconds per tick for the simulation step since the last reset. */
    public double averageWorkerMillis() {
        return totals[TICKS] == 0 ? 0.0 : workerNanos / 1.0e6 / totals[TICKS];
    }

    /** Average client-thread milliseconds per tick for spawning and grid upkeep since the last reset. */
    public double averageTickMillis() {
        return totals[TICKS] == 0 ? 0.0 : totals[TICK_NANOS] / 1.0e6 / totals[TICKS];
    }

    /** Average milliseconds per frame building particle quads since the last reset (frames with particles). */
    public double averageFrameMillis() {
        return totals[FRAMES] == 0 ? 0.0 : totals[FRAME_NANOS] / 1.0e6 / totals[FRAMES];
    }

    /** Average milliseconds per frame vanilla's particle renderer spends building LiteLights' vertices. */
    public double averageVertexMillis() {
        return totals[FRAMES] == 0 ? 0.0 : totals[VERTEX_NANOS] / 1.0e6 / totals[FRAMES];
    }

    /** Total milliseconds drawing sky events since the last reset. */
    public double totalSkyMillis() {
        return totals[SKY_NANOS] / 1.0e6;
    }

    public long framesWithParticles() {
        return totals[FRAMES];
    }

    public double averageDrawn() {
        return totals[FRAMES] == 0 ? 0.0 : totals[PARTICLES_DRAWN] / (double) totals[FRAMES];
    }

    public long ticksCounted() {
        return totals[TICKS];
    }

    public int fireEmitters() {
        return fire.emitterCount();
    }

    public int knownFires() {
        return fire.knownFires();
    }

    public int burnMarks() {
        return fire.marks().count();
    }

    /** QA only: where each burn mark lies. */
    public java.util.List<String> burnMarkPlaces() {
        return fire.marks().describe();
    }

    public int emberVeins() {
        return fire.veins().count();
    }

    public int lastVeinReached() {
        return fire.veins().lastReached();
    }

    public long lastVeinSearchNanos() {
        return fire.veins().lastSearchNanos();
    }

    /** QA only: ember veins whose block has burnt away or been broken (none, once a tick has passed). */
    public int strandedEmberVeins() {
        return fire.strandedVeins();
    }

    /** QA only: runs the ember vein search now; returns its time in nanoseconds. */
    public long forceVeinSearch() {
        return fire.forceVeinSearch();
    }

    /** A block changed in the client's level (client thread): fire's marks and veins follow it. */
    public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        fire.onBlockChanged(pos, oldState, newState);
    }

    /**
     * QA only: draw soft puffs untrimmed and don't skip those the storm fog hides, as before 0.1.5,
     * so a run can time both ways in the same client.
     */
    public void drawSoftParticlesWholeForQa(boolean whole) {
        sprites.trimOff = whole;
        sprites.forget();
        renderer.fogCullOff = whole;
    }

    public SkyEvents sky() {
        return sky;
    }

    public Surroundings surroundings() {
        return surroundings;
    }
}
