package com.thenathe.litelights.render.feature.particles;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.thenathe.litelights.LiteLightsClient;
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
import com.thenathe.litelights.render.feature.climate.LocalClimate;
import com.thenathe.litelights.render.feature.weather.AuroraColdness;
import com.thenathe.litelights.render.feature.weather.SkyEvents;
import com.thenathe.litelights.render.feature.weather.WeatherCompat;
import com.thenathe.litelights.render.feature.weather.WeatherParticles;
import com.thenathe.litelights.render.feature.weather.Wind;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.frame.ShownWeather;
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
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * LiteLights' particles and weather: owns the particle state, runs the spawners every client tick,
 * steps the simulation on its worker, and hands each frame's quads to the submit API. Also drives
 * the night sky events.
 *
 * <p>Per tick, on the client thread: if last tick's step has finished, make its result the
 * published state, look at the surroundings, queue new particles within the cap and start the next
 * step; if it hasn't (a catch-up tick after a hitch, or a CPU too slow for the settings), skip the
 * particles for this tick rather than wait, and hold them where they are. The worker only reads the
 * published state, the spawn queue and the column grid, and only writes the other state, with the
 * list of its particles a frame may draw ({@link CullList}), so nothing is shared while it runs.
 *
 * <p>Per frame: as soon as the frame's camera is known (LiteLights' frame update, before the level
 * draws), the render thread hands the quad building to {@link QuadHelpers} in slices; when the
 * level reaches the particles it builds what is left, collects the slices in order and submits
 * them. The published state can't change in between: ticks run outside frames, and every tick,
 * clear and resize finishes the frame's slices first.
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
    private static final int LATE_TICKS = 7;
    private static final int LIST_FRAMES = 8;
    private static final int WALKED = 9;
    private static final int AUDITED_FRAMES = 10;
    private static final int AUDIT_MISSING = 11;
    private static final int AUDIT_EXTRA = 12;
    private static final int AUDITED_LIST_FRAMES = 13;
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
    private static final String[] SKY_ROWS = {"weather.polar_lights", "weather.far_fireworks", "weather.rainbows"};
    /** The frame update's phase that starts the quad slices, after every stream has filled in the frame (the storm fog). */
    private static final Identifier QUAD_PHASE = LiteLightsClient.id("particle_quads");
    /**
     * The cull list's margins (CullList): it serves frames one to two ticks after the camera it is
     * picked for, so it allows three ticks of the last tick's movement and turning, plus a little for
     * starting to move or turn. A frame beyond them walks every particle.
     */
    private static final float MOVE_MARGIN = 2.0f;
    private static final float TURN_MARGIN = (float) Math.toRadians(12.0);
    private static final float LEAD_TICKS = 3.0f;
    /** A move this long in a tick is a teleport: no reason to widen the next list for it. */
    private static final double TELEPORT = 16.0;
    /** The storm fog thins between ticks; the list keeps soft particles this much further out than it hides them. */
    private static final float FOG_LEAD = 1.25f;

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
    private final QuadHelpers quads = new QuadHelpers(renderer::buildSlice, QuadHelpers.helpersFor(Runtime.getRuntime().availableProcessors()));
    /** What the next step's cull list is picked for (written on the client thread before each step). */
    private final CullList.Basis cullBasis = new CullList.Basis();
    private boolean hasLastView;
    private double lastViewX;
    private double lastViewY;
    private double lastViewZ;
    private float lastForwardX;
    private float lastForwardY;
    private float lastForwardZ;
    /** Whether this frame's slices have been started (the frame update ran) and not yet collected. */
    private boolean framePrepared;
    /**
     * The last step hadn't finished when this tick came, so the published state is a tick old:
     * frames hold its particles where they are instead of running through the tick again.
     */
    private boolean stepLate;
    /** QA only: compare every frame's particles against a walk over all of them. */
    private boolean auditing;
    /** QA only: no particle ticks, and frames hold the particles at their newest position. */
    private boolean heldForQa;
    private ParticleBatch auditScratch;
    private final SkyEvents sky = new SkyEvents();
    private final AuroraColdness auroraColdness = new AuroraColdness();
    private final HazeDrift hazeDrift = new HazeDrift();
    private final SimulationWorker worker = new SimulationWorker(this::stepOnWorker);
    /** Worker-thread time spent stepping and picking the cull list, summed (written by the worker only; read for the QA probe). */
    private volatile long workerNanos;
    private volatile long cullNanos;
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
    private final long[] totals = new long[14];

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
        LiteRenderEvents.FRAME_UPDATE.addPhaseOrdering(Event.DEFAULT_PHASE, QUAD_PHASE);
        LiteRenderEvents.FRAME_UPDATE.register(QUAD_PHASE, this::startQuads);
        LiteRenderEvents.AFTER_SKY.addPhaseOrdering(Event.DEFAULT_PHASE, SkyEvents.DRAW_PHASE);
        LiteRenderEvents.AFTER_SKY.register(SkyEvents.DRAW_PHASE, this::afterSky);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            quads.stop();
            worker.stop();
        });
        markHandledRows();
        FallingLeaves.register();
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
        // A frame whose level never reached the particles still has its slices out.
        quads.finish();
        if (heldForQa) {
            return;
        }
        boolean stepDone = publishLastStep(started);

        RenderSettings settings = RenderSettingsAccess.current();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            if (lastLevel != null) {
                onWorldLeft();
            }
            WeatherParticles.setReplacingVanilla(false);
            FallingLeaves.stop();
            return;
        }
        if (level != lastLevel) {
            clear();
            stepDone = true;
            auroraColdness.reset();
            sky.resetRainbows();
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
            FallingLeaves.stop();
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
        FallingLeaves.update(surroundings, amount);
        hazeDrift.tick(surroundings.wind[0], surroundings.wind[1]);
        surroundings.hazeDriftX = hazeDrift.x();
        surroundings.hazeDriftZ = hazeDrift.z();
        stepLate = !stepDone;
        if (stepLate) {
            // The worker still reads the queue and the grid: this tick spawns nothing and steps
            // nothing, and the particles lose a tick.
            totals[LATE_TICKS]++;
            countTick(started);
            return;
        }
        fitToReach(settings);
        queue.begin(amount, capScales, published.categoryCounts, published.count);
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
        countTick(started);
    }

    private void countTick(long started) {
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

    /**
     * Makes the worker's finished step the published state, without waiting: returns false while
     * the step still runs.
     */
    private boolean publishLastStep(long started) {
        boolean finished = worker.poll();
        mark(PART_WAIT, started);
        if (!finished) {
            return false;
        }
        if (stepPending) {
            ParticleState stepped = next;
            next = published;
            published = stepped;
            stepPending = false;
        }
        Throwable failure = worker.takeFailure();
        if (failure != null) {
            RenderFeatureStatus.failed(RenderFeature.PARTICLES, failure);
            clear();
        }
        return true;
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
        long stepped = System.nanoTime();
        next.drawable.pick(next, cullBasis);
        long picked = System.nanoTime();
        workerNanos += picked - started;
        cullNanos += picked - stepped;
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
        updateCullBasis();
        worker.launch();
        stepPending = true;
    }

    /**
     * What the step's cull list is picked for: the last frame's camera and view, with margins grown
     * from how far that camera moved and turned since the last tick's.
     */
    private void updateCullBasis() {
        if (!renderer.writeView(cullBasis)) {
            cullBasis.known = false;
            hasLastView = false;
            return;
        }
        double moved = 0.0;
        float turned = 0.0f;
        if (hasLastView) {
            double movedX = cullBasis.cameraX - lastViewX;
            double movedY = cullBasis.cameraY - lastViewY;
            double movedZ = cullBasis.cameraZ - lastViewZ;
            moved = Math.sqrt(movedX * movedX + movedY * movedY + movedZ * movedZ);
            if (moved > TELEPORT) {
                moved = 0.0;
            }
            float cos = cullBasis.forwardX * lastForwardX + cullBasis.forwardY * lastForwardY + cullBasis.forwardZ * lastForwardZ;
            turned = (float) Math.acos(Math.clamp(cos, -1.0f, 1.0f));
        }
        hasLastView = true;
        lastViewX = cullBasis.cameraX;
        lastViewY = cullBasis.cameraY;
        lastViewZ = cullBasis.cameraZ;
        lastForwardX = cullBasis.forwardX;
        lastForwardY = cullBasis.forwardY;
        lastForwardZ = cullBasis.forwardZ;
        cullBasis.moveMargin = MOVE_MARGIN + LEAD_TICKS * (float) moved;
        cullBasis.coneAngle += TURN_MARGIN + LEAD_TICKS * turned;
        cullBasis.softLimit *= FOG_LEAD;
        System.arraycopy(rangeScales, 0, cullBasis.rangeScales, 0, rangeScales.length);
        cullBasis.known = true;
    }

    private static boolean anythingOn(RenderSettings settings) {
        RenderSettings.Weather weather = settings.weather();
        return settings.particles().density() != RenderSettings.ParticleDensity.OFF
            || weather.polarLights() || weather.farFireworks() || weather.rainbows();
    }

    private void updateSky(ClientLevel level, RenderSettings settings) {
        float daylight = surroundings.daylight;
        float darkness = Math.clamp((0.4f - daylight) / 0.3f, 0.0f, 1.0f);
        // A previewed event shows even while it really rains, unless a weather is previewed with it.
        float rain = PreviewEvent.active() && !PreviewWeather.active() ? 0.0f : surroundings.rain;
        float visibility = darkness * (1.0f - rain);
        int cameraY = (int) Math.floor(surroundings.cameraY);
        float coldness = auroraColdness.ease(AuroraColdness.around((int) Math.floor(surroundings.cameraX), (int) Math.floor(surroundings.cameraZ),
            (x, z) -> auroraColdness.of(level, surroundings.cursor.set(x, cameraY, z))));
        boolean skyAllowed = surroundings.overworldSky && surroundings.weatherAllowed && !surroundings.underground
            && RenderFeatureStatus.mayRun(RenderFeature.SKY_EVENTS);
        sky.update(settings.weather(), skyAllowed, surroundings.seed, surroundings.clockTime, surroundings.gameTime, coldness, visibility);
        // The rainbows follow the world's rain, not a previewed one, where the camera is.
        BlockPos camera = surroundings.cursor.set(surroundings.cameraX, surroundings.cameraY, surroundings.cameraZ);
        boolean rainsHere = LocalClimate.fallAt(level, camera) == Biome.Precipitation.RAIN;
        sky.updateRainbow(settings.weather(), skyAllowed, surroundings.gameTime, surroundings.clockTime, ShownWeather.rain(level, 1.0f),
            ShownWeather.thunder(level, 1.0f), rainsHere);
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
            quads.finish();
            sprites.forget();
        }
    }

    /**
     * The player left the world: drops every particle, the level they were spawned in and the
     * aurora's biome lookups (which hold the old world's biome registry), and ends the quad helpers
     * until the next world draws particles.
     */
    public void onWorldLeft() {
        clear();
        quads.stop();
        auroraColdness.reset();
        lastLevel = null;
        surroundings.level = null;
        hasLastView = false;
        framePrepared = false;
    }

    private void clear() {
        quads.finish();
        worker.await();
        stepPending = false;
        published.clear();
        next.clear();
        grid.forget();
        fire.clear();
        HazeField.get().clear();
    }

    /**
     * The frame update's last phase: the frame's camera, projection and storm fog are known, so the
     * quad slices start now and the helpers build them while the level draws.
     */
    private void startQuads(FrameState frame, RenderSettings settings) {
        long started = System.nanoTime();
        framePrepared = false;
        try {
            quads.finish();
            if (!RenderFeatureStatus.mayRun(RenderFeature.PARTICLES) || !sprites.refresh()) {
                return;
            }
            LevelRenderState levelState = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState;
            prepareFrame(levelState.cameraRenderState, levelState.worldPartialTicks, frame, settings);
            quads.start(renderer.sliceCount());
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.PARTICLES, error);
        }
        totals[FRAME_NANOS] += System.nanoTime() - started;
    }

    private void prepareFrame(CameraRenderState camera, float worldPartialTicks, FrameState frame, RenderSettings settings) {
        boolean hdStyle = settings.particles().snowSandSprites() == RenderSettings.SnowSandSprites.DETAILED;
        // A late step leaves last tick's particles published: hold them at their newest position.
        float partialTick = stepLate || heldForQa ? 1.0f : worldPartialTicks;
        renderer.prepare(published, camera.pos.x, camera.pos.y, camera.pos.z, camera.orientation, frame.viewProjection, partialTick,
            hdStyle, rangeScales, stormFogOverParticles(frame), settings.weather().mistStyle());
        framePrepared = true;
    }

    private void collect(LevelRenderContext context) {
        if (published.count == 0 && !fire.hasDecals() || !RenderFeatureStatus.mayRun(RenderFeature.PARTICLES)) {
            quads.finish();
            framePrepared = false;
            return;
        }
        long started = System.nanoTime();
        // Vanilla built last frame's vertices after this method returned; count that time too.
        totals[VERTEX_NANOS] += renderer.takeBuildNanos();
        try {
            quads.finish();
            if (!framePrepared) {
                // No frame update this frame (LiteLights' renderer isn't ready yet): build it all now.
                if (!sprites.refresh()) {
                    return;
                }
                prepareFrame(context.levelState().cameraRenderState, context.levelState().worldPartialTicks,
                    LiteLightsRenderer.get().frame(), RenderSettingsAccess.current());
                for (int slice = 0; slice < renderer.sliceCount(); slice++) {
                    renderer.buildSlice(slice);
                }
            }
            framePrepared = false;
            renderer.finishSlices();
            if (auditing) {
                audit();
            }
            CameraRenderState camera = context.levelState().cameraRenderState;
            if (fire.hasDecals()) {
                // Wraps once an hour, so the pulse keeps a float's precision on old worlds.
                float seconds = (surroundings.gameTime % 72000L + context.levelState().worldPartialTicks) / 20.0f;
                fire.drawDecals(renderer, sprites, camera.pos.x, camera.pos.y, camera.pos.z, seconds);
            }
            SubmitNodeCollector collector = context.submitNodeCollector();
            boolean drew = false;
            // In slice order: vanilla appends each batch's quads to one draw per layer, so the
            // frame's quads keep the order of one walk over the particles.
            for (int slice = 0; slice < renderer.sliceCount(); slice++) {
                ParticleBatch batch = renderer.slice(slice);
                if (!batch.isEmpty()) {
                    collector.submitQuadParticleGroup(batch);
                    drew = true;
                }
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
        totals[WALKED] += renderer.walkCount();
        if (renderer.walksList()) {
            totals[LIST_FRAMES]++;
        }
    }

    /** QA only: this frame's particles against a walk over every particle (see {@link #auditCullListForQa}). */
    private void audit() {
        if (auditScratch == null) {
            auditScratch = new ParticleBatch();
        }
        int[] result = renderer.compareWithFullWalk(auditScratch);
        if (result[0] < 0) {
            return;
        }
        totals[AUDITED_FRAMES]++;
        totals[AUDIT_MISSING] += result[0];
        totals[AUDIT_EXTRA] += result[1];
        if (renderer.walksList()) {
            totals[AUDITED_LIST_FRAMES]++;
        }
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
        cullNanos = 0L;
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

    /** Average worker-thread milliseconds per tick picking the cull list (included in {@link #averageWorkerMillis}). */
    public double averageCullMillis() {
        return totals[TICKS] == 0 ? 0.0 : cullNanos / 1.0e6 / totals[TICKS];
    }

    /** Ticks since the last reset whose particle work was skipped because the worker's step hadn't finished. */
    public long lateTicks() {
        return totals[LATE_TICKS];
    }

    /** The share of frames since the last reset that walked the worker's cull list rather than every particle. */
    public double listFrameShare() {
        return totals[FRAMES] == 0 ? 0.0 : totals[LIST_FRAMES] / (double) totals[FRAMES];
    }

    /** Particles walked per frame since the last reset (the cull list's, or every live one). */
    public double averageWalked() {
        return totals[FRAMES] == 0 ? 0.0 : totals[WALKED] / (double) totals[FRAMES];
    }

    /** Quad helper threads running now. */
    public int quadHelperThreads() {
        return quads.threadCount();
    }

    /**
     * QA only: from now on compare every frame's particles against a walk over all of them (slow:
     * not for timing runs), or stop.
     */
    public void auditCullListForQa(boolean on) {
        quads.finish();
        auditing = on;
        renderer.recordDrawn(on);
    }

    /** QA only: stop the particles where they are (no ticks), so one moment can be drawn several ways. */
    public void holdForQa(boolean hold) {
        heldForQa = hold;
    }

    /** QA only: walk every particle every frame, as before the cull list. */
    public void useCullListForQa(boolean use) {
        renderer.cullListOff = !use;
    }

    /** The audit since the last reset: frames compared, of them walking the list, and particles missing and extra. */
    public Map<String, Object> cullAudit() {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("frames", totals[AUDITED_FRAMES]);
        audit.put("listFrames", totals[AUDITED_LIST_FRAMES]);
        audit.put("missing", totals[AUDIT_MISSING]);
        audit.put("extra", totals[AUDIT_EXTRA]);
        return audit;
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

    /**
     * The layer for plain particles of LiteLights' that live outside this system (the soft rain splash): vanilla's
     * look without depth writes, so they never cut holes in the mist or the clouds behind them (ParticleLayers).
     */
    public static net.minecraft.client.particle.SingleQuadParticle.Layer blendedLayer() {
        return ParticleLayers.BLENDED;
    }
}
