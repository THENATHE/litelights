package com.thenathe.litelights.render.feature.water;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.mixin.particles.BiomeManagerAccessor;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.particles.ParticleBudget;
import com.thenathe.litelights.render.feature.skyfog.StormAtmosphere;
import com.thenathe.litelights.render.feature.water.splash.SplashEffects;
import com.thenathe.litelights.render.feature.water.splash.SplashTracker;
import com.thenathe.litelights.render.feature.water.splash.StreamsReflowingSpray;
import com.thenathe.litelights.render.feature.water.splash.WaterParticleSprites;
import com.thenathe.litelights.render.feature.water.wake.WakeSources;
import com.thenathe.litelights.render.feature.weather.GlassWeather;
import com.thenathe.litelights.render.feature.weather.Wind;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import com.thenathe.litelights.render.post.LitePostChain;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostSlot;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The water stream's renderer: water colour and depth, reflections, foam, caustics, floating
 * leaves, lava crust, large fluid textures, the underwater blur, jump splashes, wakes and the
 * waterfall effects. Most of it happens in the terrain shaders (shaders/include/water/); this class feeds them
 * each frame and runs the few things that live outside them.
 *
 * <p>Called from LiteLights' render events, the client tick and the mixins in mixin.water, all on
 * the render thread.
 */
public final class WaterRenderer {
    private static final WaterRenderer INSTANCE = new WaterRenderer();
    private static final int ANCHOR_PERIOD = 2048;
    private static final int WATERFALL_CHUNK_RADIUS = 2;
    private static final double WATERFALL_RANGE = 40.0;

    private final boolean bigWaterLoaded = FabricLoader.getInstance().isModLoaded("bigwater");
    private final boolean wakesLoaded = FabricLoader.getInstance().isModLoaded("wakes");
    private final FluidColumnMap columns = new FluidColumnMap();
    private final GlassWindowMap glassWindows = new GlassWindowMap();
    private final LargeFluidTextures largeTextures = new LargeFluidTextures();
    private final SceneCopy sceneCopy = new SceneCopy();
    private final WaterSpriteLooks spriteLooks = new WaterSpriteLooks();
    private final TextureAtlasSprite[] alternateSprites = new TextureAtlasSprite[4];
    private @Nullable TextureAtlasSprite otherWaterStill;
    private @Nullable TextureAtlasSprite otherWaterFlow;
    private float @Nullable [] stripStillMean;
    private float @Nullable [] stripFlowMean;
    private boolean vanillaWaterSprites;
    private final FluidDataTexture fluidData = new FluidDataTexture();
    private final UnderwaterBlur blur = new UnderwaterBlur();
    private final SplashEffects effects = new SplashEffects();
    private final SplashTracker splashes = new SplashTracker(wakesLoaded);
    private final StreamsReflowingSpray streamsSpray = new StreamsReflowingSpray();
    private final OceanSwell swell = new OceanSwell();
    private final WakeMap wakes = new WakeMap();
    private final WakeSources.BowSpray bowSpray = this::spawnBowSpray;
    private @Nullable ClientLevel sprayLevel;
    private float sprayAmount;
    private boolean wakeShadingHeldOff;
    private final float[] windSample = new float[2];
    private float lastShaderSeconds = Float.NaN;
    private final BlockPos.MutableBlockPos tintCursor = new BlockPos.MutableBlockPos();

    private @Nullable WaterUniforms uniforms;
    private @Nullable WaterBindings bindings;
    private int activeFlags;
    private boolean wantsSceneCopy;
    private boolean blurThisFrame;
    private boolean spritesKnown;
    private boolean rowsMarked;
    private boolean largeTexturesMarked;
    private float waterfallAmount;
    private Vec3 waterfallCamera = Vec3.ZERO;
    private @Nullable ClientLevel waterfallLevel;
    private final FluidColumnMap.LandingConsumer waterfallSpawner = this::spawnWaterfall;

    private WaterRenderer() {
    }

    public static WaterRenderer get() {
        return INSTANCE;
    }

    /** Connects water to LiteLights' render events and Fabric's client events. Called once from the client entrypoint. */
    public void register() {
        PipelineOwners.claim(RenderFeature.WATER, SceneCopy.PIPELINE);
        PipelineOwners.claim(RenderFeature.UNDERWATER_BLUR, UnderwaterBlur.PIPELINE);
        LiteRenderEvents.FRAME_UPDATE.register(this::onFrameUpdate);
        LiteRenderEvents.AFTER_LEVEL.register((target, frame) -> afterLevel());
        // Reflections are traced in the water shader itself; only the depth blur is a post pass.
        LitePostChain.register(PostSlot.UNDERWATER, this::drawUnderwaterBlur);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> onChunkChanged(chunk.getPos().x(), chunk.getPos().z()));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> onChunkChanged(chunk.getPos().x(), chunk.getPos().z()));
        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
        markCompatibility();
    }

    private void markCompatibility() {
        if (bigWaterLoaded) {
            Feature.of("large_water_textures").markHandledBy("Big Water");
        }
        // Wakes (Goby56's, or the Wakes Reforged port: the same mod id) draws its own: ours step aside.
        if (wakesLoaded) {
            Feature.of("water.wakes").markHandledBy("Wakes");
        }
    }

    // --- Each frame ---

    /** From LiteRenderEvents.FRAME_UPDATE: no pass open, uploads allowed. */
    void onFrameUpdate(FrameState frame, RenderSettings settings) {
        try {
            // Only a frame that really ran clears an earlier failure, and one that copies the scene
            // only once the copy is taken: clearing it while the retry delay runs made a failing
            // scene copy fail again, and log again, on every frame.
            if (update(frame, settings) && !wantsSceneCopy) {
                RenderFeatureStatus.succeeded(RenderFeature.WATER);
            }
        } catch (RuntimeException error) {
            activeFlags = 0;
            wantsSceneCopy = false;
            blurThisFrame = false;
            if (uniforms != null) {
                uniforms.flags.x = 0;
                uniforms.upload();
            }
            RenderFeatureStatus.failed(RenderFeature.WATER, error);
        }
    }

    /** Once the GPU device exists, before anything is drawn (from RenderSystem.initRenderer). */
    public void createDeviceResources() {
        uniforms = new WaterUniforms();
        bindings = new WaterBindings(uniforms.buffer());
        uniforms.upload();
    }

    /** Returns whether water ran this frame (false with no level or while a retry delay runs). */
    private boolean update(FrameState frame, RenderSettings settings) {
        if (uniforms == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        sceneCopy.beginFrame();
        bindings.setScene(null);
        if (level == null || !RenderFeatureStatus.mayRun(RenderFeature.WATER)) {
            activeFlags = 0;
            wantsSceneCopy = false;
            blurThisFrame = false;
            uniforms.flags.x = 0;
            uniforms.upload();
            return false;
        }
        if (!spritesKnown) {
            readSprites(minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS));
        }
        readFluidSprites(minecraft);
        markRowsReady();

        RenderSettings.Water water = settings.water();
        int flags = waterFlags(settings);
        double cameraX = frame.cameraBlockX + frame.cameraFraction.x;
        double cameraY = frame.cameraBlockY + frame.cameraFraction.y;
        double cameraZ = frame.cameraBlockZ + frame.cameraFraction.z;

        if (water.largeWaterTextures() && !bigWaterLoaded && RenderFeatureStatus.mayRun(RenderFeature.LARGE_WATER_TEXTURES)) {
            try {
                LargeFluidTextures.Prepared prepared = largeTextures.update();
                if (prepared != null) {
                    fluidData.setStrip(prepared.layout(), prepared.mipPixels());
                    stripStillMean = prepared.waterStillMean();
                    stripFlowMean = prepared.waterFlowMean();
                    columns.requestFullUpload();
                    glassWindows.requestUpload();
                }
                if (fluidData.strip() != null) {
                    flags |= WaterFlags.LARGE_WATER | WaterFlags.LARGE_LAVA;
                    if (!largeTexturesMarked) {
                        largeTexturesMarked = true;
                        RenderFeatureStatus.markReady("large_water_textures");
                    }
                }
                RenderFeatureStatus.succeeded(RenderFeature.LARGE_WATER_TEXTURES);
            } catch (RuntimeException error) {
                largeTextures.requestReload();
                RenderFeatureStatus.failed(RenderFeature.LARGE_WATER_TEXTURES, error);
            }
        }

        if (vanillaWaterSprites) {
            flags |= WaterFlags.VANILLA_WATER_SPRITES;
        }
        // Weather on glass reads the rain tops kept under the column map and the glass windows under
        // those, so it keeps the map running too.
        boolean glassWeather = GlassWeather.get().wantsRainTops();
        if (flags != 0 || glassWeather) {
            if (fluidData.ensure()) {
                columns.requestFullUpload();
                glassWindows.requestUpload();
            }
            columns.setRecordFlow((flags & WaterFlags.DRIFTING_FOAM) != 0);
            columns.setRecordWaves((flags & WaterFlags.OCEAN_WAVES) != 0);
            columns.setShoreOnlyAgainstBlocks(water.edgeFoamBlocksOnly());
            columns.update(level, cameraX, cameraZ, fluidData);
            if (columns.hasOrigin()) {
                flags |= WaterFlags.COLUMN_MAP;
            }
            // The waves read the shore field, which takes a moment to work out after they're turned on.
            if (!columns.hasShoreField()) {
                flags &= ~WaterFlags.OCEAN_WAVES;
            }
        }
        // The wakes are read only while the texture holds some.
        wakes.frame(fluidData);
        if (!wakes.drawing(fluidData) || wakeShadingHeldOff) {
            flags &= ~WaterFlags.WAKES;
        }
        if (glassWeather) {
            glassWindows.update(level, cameraX, cameraY, cameraZ, fluidData);
        } else {
            glassWindows.forget();
        }
        boolean cameraUnderwater = (frame.featureFlags & FrameFlags.FEATURE_CAMERA_UNDERWATER) != 0;
        wantsSceneCopy = (flags & WaterFlags.NEEDS_SCENE_COPY) != 0 && columns.hasReflectiveSurface();
        blurThisFrame = water.underwaterDepthBlur() && cameraUnderwater;
        activeFlags = flags;

        fillUniforms(frame, settings, flags, cameraX, cameraY, cameraZ);
        uniforms.upload();
        bindings.setTextures(fluidData.view(), minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView());
        return true;
    }

    private int waterFlags(RenderSettings settings) {
        RenderSettings.Water water = settings.water();
        boolean reflectionsHere = DimensionGate.allows(Aspect.REFLECTIONS);
        int flags = 0;
        if (water.palette() == RenderSettings.WaterPalette.NATURAL) {
            flags |= WaterFlags.REALISTIC_COLOURS;
        } else if (water.palette() == RenderSettings.WaterPalette.ENHANCED) {
            flags |= WaterFlags.ENHANCED_COLOURS;
        }
        if (water.biomeAndDepthColor()) {
            flags |= WaterFlags.BIOME_DEPTH_COLOUR;
        }
        if (water.mirrors() && reflectionsHere) {
            flags |= WaterFlags.REFLECTIONS;
        }
        if (water.iceMirrors() && reflectionsHere) {
            flags |= WaterFlags.ICE_REFLECTIONS;
        }
        if (water.edgeFoam()) {
            flags |= WaterFlags.SHORE_FOAM;
        }
        if (water.lavaCrust()) {
            flags |= WaterFlags.LAVA_CRUST;
        }
        if (water.floatingLeaves()) {
            flags |= WaterFlags.FLOATING_LEAVES;
        }
        if (water.caustics()) {
            flags |= WaterFlags.CAUSTICS;
        }
        if (settings.particles().enhancedWater()) {
            flags |= WaterFlags.FALLING_WATER;
        }
        if (water.foamMotion() == RenderSettings.FoamMotion.DRIFTING && (flags & WaterFlags.DRIFTING_FOAM_MOVES) != 0) {
            flags |= WaterFlags.DRIFTING_FOAM;
        }
        if (water.oceanWaves()) {
            flags |= WaterFlags.OCEAN_WAVES;
        }
        if (wakesWanted(settings)) {
            flags |= WaterFlags.WAKES;
        }
        return flags;
    }

    /** Wakes are on, not handled by Wakes (the mod), allowed in this dimension and not waiting for a retry. */
    private boolean wakesWanted(RenderSettings settings) {
        return settings.water().wakes() && !wakesLoaded && DimensionGate.allows(Aspect.EFFECTS) && RenderFeatureStatus.mayRun(RenderFeature.WAKES);
    }

    private void fillUniforms(FrameState frame, RenderSettings settings, int flags, double cameraX, double cameraY, double cameraZ) {
        RenderSettings.Water water = settings.water();
        LevelRenderState levelState = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState;
        boolean hasSky = levelState.skyRenderState.skybox == DimensionType.Skybox.OVERWORLD;
        var sky = levelState.skyRenderState.skyColor;
        if (sky != null) {
            uniforms.skyColor.set(sky.x(), sky.y(), sky.z(), hasSky ? 1.0f : 0.0f);
        }
        Vector4f fog = levelState.cameraRenderState.fogData.color;
        uniforms.horizonColor.set(fog.x, fog.y, fog.z, 1.0f);

        int waterTile = water.largeWaterScale();
        int lavaTile = water.largeLavaScale();
        FluidStripLayout layout = fluidData.strip();
        int waterFlowTile = waterTile * 2;
        int lavaFlowTile = lavaTile * 2;
        if (layout != null) {
            uniforms.stripLayout.set(fluidData.u(layout.stillSize()), fluidData.u(layout.flowSize()),
                fluidData.v(layout.stillSize()), fluidData.v(layout.flowSize()));
            uniforms.stripColumns.set(fluidData.stripU(layout.waterStillColumn()), fluidData.stripU(layout.waterFlowColumn()),
                fluidData.stripU(layout.lavaStillColumn()), fluidData.stripU(layout.lavaFlowColumn()));
            double ticks = levelState.gameTime + levelState.worldPartialTicks;
            uniforms.waterFrames.set(layout.waterStill().frameAt(ticks), layout.waterFlow().frameAt(ticks),
                layout.waterStill().frames(), layout.waterFlow().frames());
            uniforms.lavaFrames.set(layout.lavaStill().frameAt(ticks), layout.lavaFlow().frameAt(ticks),
                layout.lavaStill().frames(), layout.lavaFlow().frames());
            waterFlowTile = layout.flowTileBlocks(waterTile);
            lavaFlowTile = layout.flowTileBlocks(lavaTile);
        }
        uniforms.params.set(water.mirrorStrength(), water.mirrorDetail().reflectionSteps(), waterTile, lavaTile);

        // Device depth = A + B / view depth, from the level's projection (see lite_water_view_depth).
        Matrix4fc projection = LiteLightsRenderer.get().projection().unjittered();
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        float depthA = zeroToOne ? -projection.m22() : 0.5f - 0.5f * projection.m22();
        float depthB = zeroToOne ? projection.m32() : 0.5f * projection.m32();
        uniforms.depth.set(depthA, depthB, 0.0f, 0.0f);

        int blockX = frame.cameraBlockX;
        int blockY = frame.cameraBlockY;
        int blockZ = frame.cameraBlockZ;
        uniforms.flags.set(flags, columns.originBlockX(), columns.originBlockZ(), columns.minY());
        uniforms.waterTileOrigin.set(Math.floorMod(blockX, waterTile), Math.floorMod(blockZ, waterTile),
            Math.floorMod(blockX, waterFlowTile), Math.floorMod(blockZ, waterFlowTile));
        uniforms.lavaTileOrigin.set(Math.floorMod(blockX, lavaTile), Math.floorMod(blockZ, lavaTile),
            Math.floorMod(blockX, lavaFlowTile), Math.floorMod(blockZ, lavaFlowTile));
        uniforms.anchor.set(Math.floorMod(blockX, ANCHOR_PERIOD), Math.floorMod(blockZ, ANCHOR_PERIOD),
            Math.floorMod(blockY, waterFlowTile), Math.floorMod(blockY, lavaFlowTile));
        if ((flags & WaterFlags.OCEAN_WAVES) != 0) {
            fillWaves(frame, water);
        }
        uniforms.wakeWindow.set(wakes.shownOriginCellX() - blockX * 4, wakes.shownOriginCellZ() - blockZ * 4, 0, 0);
        uniforms.wakeParams.set(water.wakeFoam() ? 1.0f : 0.0f, levelState.worldPartialTicks, 0.0f, 0.0f);
    }

    /**
     * The ocean waves' uniforms: the sea follows LiteLights' wind, which rain and storms make
     * stronger as they do for the particles (Surroundings), and its clock runs on the shaders' own
     * seconds, so a held shader clock holds the sea too. The player's Shore run-up and Wave foam
     * ride in the spare lanes (both 1 at the defaults, which the shaders treat as no change), and the
     * biome's water tint for the swash in the last, packed 0xRRGGBB (exact in a float).
     */
    private void fillWaves(FrameState frame, RenderSettings.Water water) {
        ClientLevel level = Minecraft.getInstance().level;
        long seed = ((BiomeManagerAccessor) level.getBiomeManager()).litelights$biomeZoomSeed();
        long clockTime = level.getOverworldClockTime();
        long gameTime = level.getGameTime();
        float rain = frame.weather.x;
        float thunder = frame.weather.y;
        StormAtmosphere storm = StormAtmosphere.current();
        float rainWind = rain * (0.55f + 0.45f * thunder) * 0.45f;
        float windStorm = Math.max(rainWind, Math.max(storm.sandstorm(), storm.blizzard()));
        Wind.sample(seed, clockTime, gameTime, 0.0f, windSample);
        double calmSpeed = Math.hypot(windSample[0], windSample[1]);
        Wind.sample(seed, clockTime, gameTime, windStorm, windSample);
        double windSpeed = Math.hypot(windSample[0], windSample[1]);
        double heading = Wind.heading(seed, clockTime, gameTime);

        float seconds = frame.time.y;
        double elapsed = Float.isNaN(lastShaderSeconds) ? 0.0 : seconds - lastShaderSeconds;
        if (elapsed < 0.0) {
            // The shaders' clock wraps at an hour.
            elapsed += 3600.0;
        }
        lastShaderSeconds = seconds;
        swell.update(heading, calmSpeed, windSpeed, Math.floorMod(clockTime, 24000L), elapsed);

        for (int train = 0; train < OceanSwell.TRAINS; train++) {
            uniforms.waveTrains[train].set(swell.trainValue(train, 0), swell.trainValue(train, 1),
                swell.trainValue(train, 2), swell.trainValue(train, 3));
        }
        uniforms.waveInfo.set(swell.seaState(), swell.shorePhase(), OceanSwell.shoreWavenumber(), water.shoreRunUp());
        uniforms.waveWind.set(swell.windX(), swell.windZ(), water.waveFoam(), seaTint(level, frame));
    }

    /** Rings searched around the camera's column for the nearest water, in blocks. */
    private static final int[] TINT_RINGS = {0, 2, 4, 8, 12, 16};

    /**
     * The swash's sheet takes the sea's colour: the biome's water tint at the water nearest the
     * camera (its surface, as biomes change with height), which is the beach in view whenever the
     * swash is close enough to see. With no water found, where the camera is.
     */
    private int seaTint(ClientLevel level, FrameState frame) {
        tintCursor.set(frame.cameraBlockX, frame.cameraBlockY, frame.cameraBlockZ);
        search:
        for (int ring : TINT_RINGS) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    // The first ring is the camera's own column; the others its eight neighbours that far out.
                    boolean centre = dx == 0 && dz == 0;
                    if (centre != (ring == 0)) {
                        continue;
                    }
                    int x = frame.cameraBlockX + dx * ring;
                    int z = frame.cameraBlockZ + dz * ring;
                    double surface = columns.openWaterSurfaceY(x, z);
                    if (!Double.isNaN(surface)) {
                        tintCursor.set(x, (int) Math.floor(surface - 0.05), z);
                        break search;
                    }
                }
            }
        }
        // The biome's own colour, not the blended tint cache, which can hold a chunk's old biomes
        // after they change (one value a frame, so no blending is needed).
        return level.getBiome(tintCursor).value().getWaterColor() & 0xFFFFFF;
    }

    private void markRowsReady() {
        if (rowsMarked || RenderFeatureStatus.problem(RenderFeature.TERRAIN_SHADING) != null) {
            return;
        }
        rowsMarked = true;
        RenderFeatureStatus.markReady("water.palette", "water.biome_depth_colour", "reflections", "water.edge_foam",
            "water.caustics", "water.floating_leaves", "water.lava_crust", "water.underwater_blur", "water.jump_splash",
            "particles.enhanced_water", "water.wakes");
        markCompatibility();
    }

    // --- Inside the level ---

    /** Whether the frame wants the opaque scene copied before translucent terrain. */
    public boolean wantsSceneCopy() {
        return wantsSceneCopy && bindings != null;
    }

    /** After the opaque world, before translucent terrain, with no render pass open. */
    public void copyScene(RenderTarget mainTarget) {
        try {
            sceneCopy.take(mainTarget);
            bindings.setScene(sceneCopy.view());
            RenderFeatureStatus.succeeded(RenderFeature.WATER);
        } catch (RuntimeException error) {
            wantsSceneCopy = false;
            RenderFeatureStatus.failed(RenderFeature.WATER, error);
        }
    }

    /**
     * For bloom after the level: the opaque scene's colour and alpha (its emissive mask) from before
     * translucent terrain, or null when no copy was taken this frame (no water or ice nearby, or
     * reflections and depth colour off).
     */
    public @Nullable GpuTextureView opaqueSceneColor() {
        return sceneCopy.colorThisFrame();
    }

    public @Nullable GpuTextureView opaqueSceneAlpha() {
        return sceneCopy.opaqueAlphaThisFrame();
    }

    /** From RenderSystem.bindDefaultUniforms: every pass gets the water uniforms. */
    public void bind(RenderPass pass) {
        if (bindings != null) {
            bindings.bind(pass);
        }
    }

    /** From LiteRenderEvents.AFTER_LEVEL: later passes (the hand, the HUD) must not see the scene copy. */
    void afterLevel() {
        sceneCopy.invalidate();
        if (bindings != null) {
            bindings.setScene(null);
        }
    }

    /** The post chain's UNDERWATER slot: the depth blur, only under water. */
    void drawUnderwaterBlur(PostContext context) {
        if (!blurThisFrame || !RenderFeatureStatus.mayRun(RenderFeature.UNDERWATER_BLUR)) {
            return;
        }
        try {
            blur.draw(context);
            RenderFeatureStatus.succeeded(RenderFeature.UNDERWATER_BLUR);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.UNDERWATER_BLUR, error);
        }
    }

    // --- World and resource changes ---

    public void onAtlasUploaded(TextureAtlas atlas, Map<Identifier, TextureAtlasSprite> regions) {
        if (atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) {
            spriteLooks.forget();
            readSprites(atlas);
            readAlternateFluidSprites(regions);
            largeTextures.requestReload();
        } else if (atlas.location().equals(TextureAtlas.LOCATION_PARTICLES)) {
            WaterParticleSprites.forget();
        }
    }

    public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        columns.markBlock(pos.getX(), pos.getZ());
        if (WindowGlass.isGlass(oldState) || WindowGlass.isGlass(newState)) {
            glassWindows.markChunk(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    void onChunkChanged(int chunkX, int chunkZ) {
        columns.markChunk(chunkX, chunkZ);
        glassWindows.markChunk(chunkX, chunkZ);
    }

    private void readSprites(TextureAtlas atlas) {
        if (uniforms == null) {
            return;
        }
        setSprite(uniforms.iceSprites[0], atlas.getSprite(Identifier.withDefaultNamespace("block/ice")));
        setSprite(uniforms.iceSprites[1], atlas.getSprite(Identifier.withDefaultNamespace("block/packed_ice")));
        setSprite(uniforms.iceSprites[2], atlas.getSprite(Identifier.withDefaultNamespace("block/blue_ice")));
        // Weather on glass draws snow and sand in their block textures.
        setSprite(uniforms.glassSprites[0], atlas.getSprite(Identifier.withDefaultNamespace("block/snow")));
        setSprite(uniforms.glassSprites[1], atlas.getSprite(Identifier.withDefaultNamespace("block/sand")));
        setSprite(uniforms.glassSprites[2], atlas.getSprite(Identifier.withDefaultNamespace("block/red_sand")));
        spritesKnown = true;
    }

    /**
     * A mod that draws fluids with textures of its own (Big Water) puts them in the block atlas under
     * its own namespace with the usual names. Water effects should still find that water, so the
     * first such sprite of each kind is recognised as well.
     */
    private void readAlternateFluidSprites(Map<Identifier, TextureAtlasSprite> regions) {
        if (uniforms == null) {
            return;
        }
        String[] names = {"block/water_still", "block/water_flow", "block/lava_still", "block/lava_flow"};
        for (int i = 0; i < names.length; i++) {
            TextureAtlasSprite found = null;
            for (Map.Entry<Identifier, TextureAtlasSprite> entry : regions.entrySet()) {
                Identifier id = entry.getKey();
                if (!id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) && id.getPath().equals(names[i])) {
                    found = entry.getValue();
                    break;
                }
            }
            alternateSprites[i] = found;
            setSprite(uniforms.alternateFluidSprites[i], found);
        }
    }

    /**
     * The sprites the fluid renderer really uses, from the fluid models (a resource pack or another
     * mod may point them elsewhere). Read every frame: the models are swapped during a resource
     * reload, after the atlas upload, and reading them costs a few field reads.
     */
    private void readFluidSprites(Minecraft minecraft) {
        FluidStateModelSet fluids = minecraft.getModelManager().getFluidStateModelSet();
        if (fluids == null) {
            return;
        }
        FluidModel water = fluids.get(Fluids.WATER.defaultFluidState());
        FluidModel lava = fluids.get(Fluids.LAVA.defaultFluidState());
        TextureAtlasSprite still = water.stillMaterial().sprite();
        TextureAtlasSprite flow = water.flowingMaterial().sprite();
        TextureAtlasSprite overlay = water.overlayMaterial() != null ? water.overlayMaterial().sprite() : null;
        setSprite(uniforms.waterStillSprite, still);
        setSprite(uniforms.waterFlowSprite, flow);
        setSprite(uniforms.waterOverlaySprite, overlay);
        setSprite(uniforms.lavaStillSprite, lava.stillMaterial().sprite());
        setSprite(uniforms.lavaFlowSprite, lava.flowingMaterial().sprite());
        if (alternateSprites[0] == null && alternateSprites[1] == null) {
            readOtherWaterFluids(fluids, still, flow);
            setSprite(uniforms.alternateFluidSprites[0], otherWaterStill);
            setSprite(uniforms.alternateFluidSprites[1], otherWaterFlow);
        } else {
            otherWaterStill = alternateSprites[0];
            otherWaterFlow = alternateSprites[1];
        }

        // For the Enhanced colours: each sprite's average colour, and whether the water is vanilla's.
        vanillaWaterSprites = spriteLooks.vanillaWater(still, flow);
        setMean(uniforms.spriteMeans[0], spriteLooks.mean(still));
        setMean(uniforms.spriteMeans[1], spriteLooks.mean(flow));
        setMean(uniforms.spriteMeans[2], spriteLooks.mean(overlay));
        setMean(uniforms.spriteMeans[3], spriteLooks.mean(otherWaterStill));
        setMean(uniforms.spriteMeans[4], spriteLooks.mean(otherWaterFlow));
        setMean(uniforms.spriteMeans[5], stripStillMean != null ? stripStillMean : SpriteMean.FALLBACK);
        setMean(uniforms.spriteMeans[6], stripFlowMean != null ? stripFlowMean : SpriteMean.FALLBACK);
    }

    /**
     * Any other fluid in #water that a mod draws with sprites of its own is water too: its sprites
     * take the other-namespace slots when no same-named sprite claimed them.
     */
    private void readOtherWaterFluids(FluidStateModelSet fluids, TextureAtlasSprite still, TextureAtlasSprite flow) {
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            FluidState state = fluid.defaultFluidState();
            if (fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER || state.isEmpty() || !state.is(FluidTags.WATER)) {
                continue;
            }
            FluidModel model = fluids.get(state);
            TextureAtlasSprite otherStill = model.stillMaterial().sprite();
            TextureAtlasSprite otherFlow = model.flowingMaterial().sprite();
            if (otherStill != still || otherFlow != flow) {
                otherWaterStill = otherStill;
                otherWaterFlow = otherFlow;
                return;
            }
        }
        otherWaterStill = null;
        otherWaterFlow = null;
    }

    private static void setMean(Vector4f target, float[] mean) {
        target.set(mean[0], mean[1], mean[2], 1.0f);
    }

    private static void setSprite(Vector4f rect, @Nullable TextureAtlasSprite sprite) {
        if (sprite == null) {
            rect.set(2.0f, 2.0f, 2.0f, 2.0f);
        } else {
            rect.set(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
        }
    }

    /** The player left the world: forget its columns and the entities being watched for splashes. */
    public void onWorldLeft() {
        columns.reset();
        glassWindows.forget();
        swell.reset();
        lastShaderSeconds = Float.NaN;
        splashes.clear();
        wakes.reset();
        waterfallLevel = null;
    }

    // --- Client tick: wakes, splashes and waterfalls ---

    void clientTick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            splashes.clear();
            wakes.stop();
            return;
        }
        RenderSettings settings = RenderSettingsAccess.current();
        float amount = ParticleBudget.spawnScale(settings.particles().density());
        boolean effectsHere = DimensionGate.allows(Aspect.EFFECTS);
        Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        tickWakes(minecraft, level, settings, camera, amount);
        if (amount <= 0.0f || !effectsHere) {
            return;
        }
        try {
            RenderSettings.JumpSplash splashStyle = settings.water().jumpSplash();
            if (splashStyle != RenderSettings.JumpSplash.OFF && RenderFeatureStatus.mayRun(RenderFeature.WATER_SPLASHES)) {
                splashes.tick(level, camera, amount, splashStyle == RenderSettings.JumpSplash.ADVANCED, effects);
            }
            // Streams Reflowing throws its own, far heavier spray at every waterfall: ours steps aside.
            boolean waterfallParticles = settings.particles().enhancedWater() && !streamsSpray.spraysWaterfalls();
            if (waterfallParticles && (activeFlags & WaterFlags.COLUMN_MAP) != 0) {
                waterfallLevel = level;
                waterfallCamera = camera;
                waterfallAmount = amount;
                columns.forEachLanding(WATERFALL_CHUNK_RADIUS, waterfallSpawner);
                waterfallLevel = null;
            }
            RenderFeatureStatus.succeeded(RenderFeature.WATER_SPLASHES);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.WATER_SPLASHES, error);
        }
    }

    /**
     * The wakes run in every tick, whatever the particle amount (they are drawn by the water shader);
     * only their bow spray follows it. While the game is paused or its ticks are frozen they hold
     * still.
     */
    private void tickWakes(Minecraft minecraft, ClientLevel level, RenderSettings settings, Vec3 camera, float amount) {
        if (!wakesWanted(settings) || !columns.hasOrigin() || fluidData.view() == null) {
            wakes.stop();
            return;
        }
        try {
            boolean stepping = !minecraft.isPaused() && level.tickRateManager().runsNormally();
            sprayLevel = level;
            sprayAmount = amount;
            float cameraYaw = minecraft.gameRenderer.mainCamera().yRot();
            wakes.tick(level, camera, cameraYaw, settings.water().wakeStrength(), columns, fluidData, stepping, amount > 0.0f ? bowSpray : null);
            RenderFeatureStatus.succeeded(RenderFeature.WAKES);
        } catch (RuntimeException error) {
            wakes.stop();
            RenderFeatureStatus.failed(RenderFeature.WAKES, error);
        } finally {
            sprayLevel = null;
        }
    }

    private void spawnBowSpray(double x, double surfaceY, double z, double headingX, double headingZ, double speed) {
        if (sprayLevel != null) {
            effects.bowSpray(sprayLevel, x, surfaceY, z, headingX, headingZ, speed, sprayAmount);
        }
    }

    private void spawnWaterfall(int x, int y, int z, int drop) {
        double dx = x + 0.5 - waterfallCamera.x;
        double dy = y + 0.5 - waterfallCamera.y;
        double dz = z + 0.5 - waterfallCamera.z;
        if (dx * dx + dy * dy + dz * dz < WATERFALL_RANGE * WATERFALL_RANGE) {
            effects.waterfallTick(waterfallLevel, x, y, z, drop, waterfallAmount);
        }
    }

    // --- Diagnostics for the QA harness ---

    public int activeFlags() {
        return activeFlags;
    }

    public boolean columnMapComplete() {
        return columns.isComplete();
    }

    /** Glass blocks' window entries in the table now, for weather on glass. */
    public int glassWindowEntries() {
        return glassWindows.entries();
    }

    /** How long the last glass window build took on the render thread, in milliseconds. */
    public double glassWindowBuildMillis() {
        return glassWindows.lastBuildMillis();
    }

    public boolean largeTexturesReady() {
        return fluidData.strip() != null;
    }

    public int splashCount() {
        return splashes.splashCount();
    }

    /** Whether LiteLights' waterfall particles are stepping aside for Streams Reflowing's spray. */
    public boolean waterfallSprayHandedOver() {
        return streamsSpray.installed() && streamsSpray.spraysWaterfalls();
    }

    public boolean sceneCopyWanted() {
        return wantsSceneCopy;
    }

    /** Ocean waves: the sea state now (0 calm to 1 storm), and whether the shore field is in use. */
    public float oceanSeaState() {
        return swell.seaState();
    }

    /** The water tint the swash's sheet takes now, 0xRRGGBB (QA). */
    public int oceanShoreTint() {
        return uniforms == null ? 0 : (int) uniforms.waveWind.w;
    }

    public boolean oceanWavesDrawing() {
        return (activeFlags & WaterFlags.OCEAN_WAVES) != 0;
    }

    public double shoreFieldMillis() {
        return columns.shoreFieldMillis();
    }

    /**
     * QA only: the shaders leave the wakes out while the field runs on as usual, so the same frozen
     * wake can be pictured and timed with and without them.
     */
    public void holdWakeShadingOffForQa(boolean heldOff) {
        wakeShadingHeldOff = heldOff;
    }

    /** Wakes: whether the shaders draw them this frame, and what the last tick cost (QA). */
    public boolean wakesDrawing() {
        return (activeFlags & WaterFlags.WAKES) != 0;
    }

    public Map<String, Object> wakeReadings() {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("drawing", wakesDrawing());
        readings.put("stepMs", wakes.lastStepMillis());
        readings.put("tickMs", wakes.lastTickMillis());
        readings.put("uploadMs", wakes.lastUploadMillis());
        readings.put("uploadTiles", wakes.lastUploadTiles());
        readings.put("workCells", wakes.lastWorkCells());
        readings.put("sources", wakes.lastSources());
        readings.put("totalSources", wakes.totalSources());
        readings.put("boatTicks", wakes.sources().boatTicks());
        readings.put("swimmerTicks", wakes.sources().swimmerTicks());
        readings.put("entries", wakes.sources().entries());
        return readings;
    }

    /** The column map's raw column and shader texel at a world column, for the edge foam QA. */
    public int[] fluidColumnForQa(int x, int z) {
        return columns.columnForQa(x, z);
    }

    /** The wind's heading the swell follows now, in radians (x its cosine, z its sine). */
    public double oceanWindHeading() {
        return Math.atan2(swell.windZ(), swell.windX());
    }

}
