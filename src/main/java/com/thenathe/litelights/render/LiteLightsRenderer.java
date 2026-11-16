package com.thenathe.litelights.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.DimensionOptions;
import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.compat.SodiumSupport;
import com.thenathe.litelights.render.frame.CelestialLight;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.frame.FrameUniforms;
import com.thenathe.litelights.render.frame.WeatherPreview;
import com.thenathe.litelights.render.frame.WorldProjection;
import com.thenathe.litelights.render.material.MaterialAtlas;
import com.thenathe.litelights.render.pipeline.UniformBinder;
import com.thenathe.litelights.render.shadow.MobShadowMap;
import com.thenathe.litelights.render.shadow.ShadowCasterStore;
import com.thenathe.litelights.render.shadow.ShadowDepth;
import com.thenathe.litelights.render.shadow.ShadowRedrawRules;
import com.thenathe.litelights.render.shadow.SunShadowMap;
import com.thenathe.litelights.render.shadow.TerrainCasterRenderer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Runs LiteLights' rendering each frame: works out the frame's light and settings, keeps the shadow
 * maps current, uploads the LiteFrame block and binds LiteLights' uniforms on every pass.
 *
 * <p>Called from a handful of hooks (see the mixin.render package): device start-up, just before
 * the level is drawn, after the frame's entity draws are prepared, after the level, and from
 * {@code RenderSystem.bindDefaultUniforms}. All calls happen on the render thread.
 */
public final class LiteLightsRenderer {
    private static final LiteLightsRenderer INSTANCE = new LiteLightsRenderer();
    /** Casters are kept a little past the square's half-diagonal (radius times the square root of 2), so its corners are covered. */
    private static final double CASTER_RANGE_FACTOR = 1.42;
    private static final boolean DEBUG_SHADOWS = "shadows".equals(System.getProperty("litelights.debug"));
    /** Pretends the device can't make larger shadow maps, to try the smaller-tier fallback. */
    private static final int SHADOW_MAP_SIZE_LIMIT = Integer.getInteger("litelights.debug.shadowMapLimit", Integer.MAX_VALUE);
    /**
     * Holds the shaders' clock (waves, foam, caustics, glint) at this many seconds, for pictures that
     * must match pixel for pixel between builds; below 0, the clock runs.
     */
    private static final float FROZEN_SHADER_SECONDS = Float.parseFloat(System.getProperty("litelights.debug.shaderSeconds", "-1"));

    private final FrameState frame = new FrameState();
    private final CelestialLight celestial = new CelestialLight();
    private final WorldProjection projection = new WorldProjection();
    private final MaterialAtlas materials = new MaterialAtlas();
    private final TerrainCasterRenderer terrainCasters = new TerrainCasterRenderer();
    private final Matrix4f viewRotation = new Matrix4f();
    private final Matrix4f previousViewProjection = new Matrix4f();
    private final Matrix4f scratchViewProjection = new Matrix4f();
    private final Vector3d previousCamera = new Vector3d();
    private final Vector3d camera = new Vector3d();
    private boolean hasPreviousFrame;
    private final long startNanos = System.nanoTime();

    private @Nullable FrameUniforms uniforms;
    private @Nullable UniformBinder binder;
    private @Nullable ShadowCasterStore casters;
    private @Nullable SunShadowMap sunMap;
    private @Nullable MobShadowMap mobMap;
    /** The detail the sun map was made for (the chosen one, even when a smaller tier stands in). */
    private RenderSettings.@Nullable ShadowDetail sunMapDetail;
    private @Nullable String detailFallbackReason;
    private boolean detailFallbackShown;
    private boolean retryWantedDetail;
    private @Nullable ClientLevel level;
    private RenderSettings settings = RenderSettings.DEFAULTS;
    private boolean shadowsActive;
    private boolean rowsMarkedReady;
    private long lastRedrawNanos;
    private boolean mobShadowsActive;
    // Diagnostics (QA harness and settings footer).
    private int redrawCount;
    private int nearChangeRedrawCount;
    private ShadowRedrawRules.Reason lastRedrawReason = ShadowRedrawRules.Reason.NONE;

    private LiteLightsRenderer() {
    }

    public static LiteLightsRenderer get() {
        return INSTANCE;
    }

    /** Registers the events LiteLights' renderer listens to. Called from the client entrypoint. */
    public void registerEvents() {
        ClientChunkEvents.CHUNK_LOAD.register((chunkLevel, chunk) -> {
            if (casters != null) {
                casters.chunkLoaded(chunkLevel, chunk.getPos().x(), chunk.getPos().z());
            }
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((chunkLevel, chunk) -> {
            if (casters != null) {
                casters.chunkUnloaded(chunkLevel, chunk.getPos().x(), chunk.getPos().z());
            }
        });
        DimensionGate.register();
        SodiumSupport.reportStartupState();
    }

    /** Once the GPU device exists, before anything is drawn. */
    public void createDeviceResources() {
        uniforms = new FrameUniforms();
        binder = new UniformBinder(uniforms.world(), uniforms.neutral(), uniforms.hand());
        casters = new ShadowCasterStore();
        LiteLightsClient.LOG.info("Renderer ready ({})", RenderSystem.getBackendDescription());
    }

    public WorldProjection projection() {
        return projection;
    }

    public FrameState frame() {
        return frame;
    }

    /** From GameRenderer.renderItemInHand: the hand's pass is next (true) or done (false). */
    public void setDrawingHand(boolean drawingHand) {
        if (binder != null) {
            binder.setDrawingHand(drawingHand);
        }
    }

    /** From RenderSystem.bindDefaultUniforms: every world and GUI pass gets LiteLights' uniforms. */
    public void bindUniforms(RenderPass pass) {
        if (binder != null) {
            binder.bindDefaults(pass);
        }
    }

    public void onBlockAtlasUploaded(TextureAtlas atlas, int width, int height, Map<Identifier, TextureAtlasSprite> regions) {
        materials.onAtlasUploaded(width, height, regions);
        if (casters != null) {
            casters.invalidateAll();
        }
    }

    // --- Level change notifications (from ClientLevelMixin) ---

    public void onBlockChanged(BlockPos pos) {
        if (casters != null) {
            casters.markBlockDirty(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public void onSectionsDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (casters == null) {
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    casters.markSectionDirty(x, y, z);
                }
            }
        }
    }

    /**
     * The player left the world (back to the title screen, or disconnected): drops the level and
     * frees the caster meshes and shadow maps, which hold a hundred MB or more of GPU memory.
     */
    public void onWorldLeft() {
        level = null;
        if (casters != null) {
            casters.setLevel(null);
        }
        closeShadowMaps();
        if (mobMap != null) {
            mobMap.close();
            mobMap = null;
        }
        hasPreviousFrame = false;
    }

    private void closeShadowMaps() {
        if (binder != null) {
            binder.setShadowMaps(null, null, null);
        }
        if (sunMap != null) {
            sunMap.close();
            sunMap = null;
        }
    }

    /**
     * Just before the level is drawn: the camera, projection and light for this frame are known and
     * no render pass is open.
     */
    public void beforeLevel(GameRenderer gameRenderer) {
        if (uniforms == null || binder == null || casters == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        RenderSettingsFeed.refresh();
        settings = RenderSettingsAccess.current();
        LevelRenderState levelState = gameRenderer.gameRenderState().levelRenderState;
        CameraRenderState cameraState = levelState.cameraRenderState;
        ClientLevel currentLevel = minecraft.level;
        if (currentLevel != level) {
            level = currentLevel;
            casters.setLevel(currentLevel);
            materials.requestRebuild();
            // The old level's shadows and last frame's camera mean nothing in the new one.
            closeShadowMaps();
            hasPreviousFrame = false;
        }
        camera.set(cameraState.pos.x, cameraState.pos.y, cameraState.pos.z);
        viewRotation.set(cameraState.viewRotationMatrix);

        materials.update(currentLevel != null);
        binder.setMaterials(materials.view());

        float partialTicks = levelState.worldPartialTicks;
        // The settings screen's weather preview stands in for the world's weather in everything
        // LiteLights draws; the world's own weather is never touched.
        WeatherPreview.update();
        float rain = WeatherPreview.rain(currentLevel == null ? 0.0f : currentLevel.getRainLevel(partialTicks));
        float thunder = WeatherPreview.thunder(currentLevel == null ? 0.0f : currentLevel.getThunderLevel(partialTicks));
        float sunAngle = levelState.skyRenderState.sunAngle;
        float moonAngle = levelState.skyRenderState.moonAngle;
        if (settings.hasPreview()) {
            // The settings screen's time-of-day preview: light and shadows as at that time, while
            // the world's own time runs on untouched.
            sunAngle = CelestialLight.sunAngleForDayTime(settings.previewDayTime());
            moonAngle = sunAngle + (float) Math.PI;
        }
        celestial.update(sunAngle, moonAngle, rain, thunder, settings.shadows().sunlightColour());

        boolean hasSun = currentLevel != null && currentLevel.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD;
        boolean cameraUnderwater = cameraState.fogType == FogType.WATER;
        shadowsActive = hasSun && settings.shadows().enabled() && (!cameraUnderwater || settings.shadows().inWater())
            && RenderFeatureStatus.mayRun(RenderFeature.SHADOWS) && DimensionGate.allows(DimensionOptions.Aspect.SHADOWS);
        mobShadowsActive = shadowsActive && settings.shadows().entityShadows() && RenderFeatureStatus.mayRun(RenderFeature.MOB_SHADOWS);

        updateSunShadows(minecraft);
        updateMobShadowPlacement();
        fillFrame(gameRenderer, currentLevel, rain, thunder, cameraUnderwater);
        LiteRenderEvents.FRAME_UPDATE.invoker().onFrameUpdate(frame, settings);
        uniforms.update(frame);
        binder.setShadowMaps(
            shadowsActive && sunMap != null ? sunMap.frontView() : null,
            shadowsActive && sunMap != null ? sunMap.previousView() : null,
            mobShadowsActive && mobMap != null ? mobMap.view() : null);
    }

    private void updateSunShadows(Minecraft minecraft) {
        if (!shadowsActive) {
            casters.clearChanges();
            return;
        }
        RenderSettings.ShadowDetail detail = settings.shadows().detail();
        int distance = settings.shadows().reach();
        if (sunMap == null || detail != sunMapDetail || sunMap.shadowDistance() != distance || retryWantedDetail) {
            retryWantedDetail = false;
            if (sunMap != null) {
                sunMap.close();
                sunMap = null;
            }
            try {
                sunMap = createSunMap(detail, distance);
                sunMapDetail = detail;
            } catch (RuntimeException error) {
                RenderFeatureStatus.failed(RenderFeature.SHADOWS, error);
                shadowsActive = false;
                return;
            }
        }
        casters.update(camera.x, camera.z, sunMap.coverageRadius() * CASTER_RANGE_FACTOR);
        try {
            GpuTextureView blockAtlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            if (!sunMap.isDrawing() && !sunMap.isFading()) {
                startRedrawIfDue(distance);
            }
            if (sunMap.isDrawing()) {
                boolean clear = sunMap.isFirstRedrawFrame();
                LongArrayList batch = sunMap.takeThisFramesSections();
                terrainCasters.draw(sunMap, batch, casters, blockAtlas, clear);
                if (!sunMap.isDrawing()) {
                    sunMap.finishRedraw(settings.shadows().glide());
                    if (!rowsMarkedReady) {
                        rowsMarkedReady = true;
                        RenderFeatureStatus.markReady("shadows", "mob_shadows", "underwater_shadows", "pixel_lock");
                        showDetailFallback();
                    }
                }
            }
            RenderFeatureStatus.succeeded(RenderFeature.SHADOWS);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.SHADOWS, error);
            shadowsActive = false;
        }
    }

    /**
     * Makes the sun map at the chosen detail, or at the largest smaller one the device can hold: a
     * size the device can't make at all is skipped straight away, and if allocating the textures
     * fails the next tier down is tried. The Shadows row says when a smaller tier is in use, with a
     * Retry that tries the chosen one again. Throws only when even Fastest can't be made.
     */
    private SunShadowMap createSunMap(RenderSettings.ShadowDetail wanted, int distance) {
        int largestSize = Math.min(RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.D32_FLOAT),
            SHADOW_MAP_SIZE_LIMIT);
        RenderSettings.ShadowDetail detail = wanted.fitting(largestSize);
        detailFallbackReason = detail == wanted ? null : "litelights.shadows.detail_too_large";
        while (true) {
            try {
                SunShadowMap map = new SunShadowMap(detail.mapSize(), distance);
                showDetailFallback();
                return map;
            } catch (RuntimeException error) {
                RenderSettings.ShadowDetail lower = detail.lower();
                if (lower == null) {
                    throw error;
                }
                LiteLightsClient.LOG.warn("Couldn't make a {} sun shadow map, trying {}: {}", detail, lower, error.toString());
                detail = lower;
                detailFallbackReason = "litelights.shadows.detail_no_memory";
            }
        }
    }

    /** Shows on the Shadows row that a smaller detail than chosen is in use, or clears that note. */
    private void showDetailFallback() {
        if (detailFallbackReason != null) {
            Feature.of("shadows").markFailed(detailFallbackReason, () -> retryWantedDetail = true);
            detailFallbackShown = true;
        } else if (detailFallbackShown) {
            detailFallbackShown = false;
            RenderFeatureStatus.markReady("shadows");
        }
    }

    /** Starts a redraw of the sun map if the light, the camera or the casters in range call for one. */
    private void startRedrawIfDue(int distance) {
        Vector3d anchor = sunMap.frontAnchor();
        double nearestChange = sunMap.hasMap()
            ? casters.nearestChangeWithin(anchor.x, anchor.z, sunMap.coverageRadius(), camera.x, camera.z)
            : Double.POSITIVE_INFINITY;
        boolean castersChanged = ShadowRedrawRules.casterChangeIsDue(nearestChange, System.nanoTime() - lastRedrawNanos);
        ShadowRedrawRules.Reason reason = ShadowRedrawRules.decide(sunMap.drawnLightDirection(), celestial.shadowLightDirection(),
            anchor.x, anchor.z, camera.x, camera.z, distance, castersChanged);
        if (reason != ShadowRedrawRules.Reason.NONE) {
            if (reason == ShadowRedrawRules.Reason.CASTERS_CHANGED && nearestChange <= ShadowRedrawRules.NEAR_CHANGE_BLOCKS) {
                nearChangeRedrawCount++;
            }
            casters.clearChanges();
            lastRedrawNanos = System.nanoTime();
            redrawCount++;
            lastRedrawReason = reason;
            sunMap.beginRedraw(celestial.shadowLightDirection(), camera, casters, isZeroToOneDepth());
        }
    }

    private void updateMobShadowPlacement() {
        if (!mobShadowsActive) {
            return;
        }
        int size = settings.shadows().detail().mobMapSize();
        if (mobMap == null || mobMap.size() != size) {
            if (mobMap != null) {
                mobMap.close();
            }
            mobMap = new MobShadowMap(size);
        }
        // Mob shadows use the same light direction as the cached map, so both maps agree.
        var light = sunMap != null && sunMap.hasMap() ? sunMap.frontSpace().lightDirection() : celestial.shadowLightDirection();
        mobMap.place(light, camera, isZeroToOneDepth());
    }

    /** After the frame's entity draws are prepared, inside LevelRenderer.render, with no pass open. */
    public void onFeaturesPrepared(FeatureRenderDispatcher.PreparedFrame preparedFrame) {
        if (!mobShadowsActive || mobMap == null || binder == null || uniforms == null) {
            return;
        }
        try {
            mobMap.draw(preparedFrame, viewRotation, binder);
            RenderFeatureStatus.succeeded(RenderFeature.MOB_SHADOWS);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.MOB_SHADOWS, error);
        }
    }

    public void afterLevel(GameRenderer gameRenderer) {
        if (uniforms == null) {
            return;
        }
        LiteRenderEvents.AFTER_LEVEL.invoker().afterLevel(gameRenderer.mainRenderTarget(), frame);
    }

    private void fillFrame(GameRenderer gameRenderer, @Nullable ClientLevel currentLevel, float rain, float thunder,
                           boolean cameraUnderwater) {
        fillMatrices();
        fillLightAndTime(currentLevel, rain, thunder);
        fillShadowInfo();
        var target = gameRenderer.mainRenderTarget();
        frame.screen.set(target.width, target.height, 1.0f / target.width, 1.0f / target.height);

        frame.featureFlags = featureFlags(cameraUnderwater);
        frame.pixelLockFlags = pixelLockFlags(settings.pixelLock());
        frame.materialGridShift = materials.gridShift();
    }

    private void fillMatrices() {
        // View-projection for camera-relative positions, and last frame's rebased onto this camera.
        Matrix4f viewProjection = scratchViewProjection.set(projection.unjittered()).mul(viewRotation);
        if (hasPreviousFrame) {
            frame.viewProjectionPrevious.set(previousViewProjection).translate(
                (float) (camera.x - previousCamera.x), (float) (camera.y - previousCamera.y), (float) (camera.z - previousCamera.z));
        } else {
            frame.viewProjectionPrevious.set(viewProjection);
        }
        frame.viewProjection.set(viewProjection);
        frame.inverseViewProjection.set(viewProjection).invert();
        previousViewProjection.set(viewProjection);
        previousCamera.set(camera);
        hasPreviousFrame = true;
    }

    private void fillLightAndTime(@Nullable ClientLevel currentLevel, float rain, float thunder) {
        frame.sunDirection.set(celestial.sunDirection(), celestial.sunDirection().y);
        frame.moonDirection.set(celestial.moonDirection(), celestial.moonDirection().y);
        frame.shadowLightDirection.set(sunMap != null && sunMap.hasMap() ? sunMap.frontSpace().lightDirection() : celestial.shadowLightDirection(),
            celestial.moonCastsShadows() ? 1.0f : 0.0f);
        frame.sunColor.set(celestial.sunColor(), celestial.daylight());
        frame.ambientColor.set(celestial.ambientColor(), celestial.nightTone());

        BlockPos cameraBlock = BlockPos.containing(camera.x, camera.y, camera.z);
        frame.cameraBlockX = cameraBlock.getX();
        frame.cameraBlockY = cameraBlock.getY();
        frame.cameraBlockZ = cameraBlock.getZ();
        frame.cameraFraction.set((float) (camera.x - cameraBlock.getX()), (float) (camera.y - cameraBlock.getY()), (float) (camera.z - cameraBlock.getZ()));
        frame.dimensionKind = dimensionKind(currentLevel);

        float sunAngleTurns = (float) (Math.atan2(-celestial.sunDirection().x, celestial.sunDirection().y) / (Math.PI * 2.0));
        float seconds = FROZEN_SHADER_SECONDS >= 0.0f ? FROZEN_SHADER_SECONDS
            : (float) (((System.nanoTime() - startNanos) / 1.0e9) % 3600.0);
        frame.time.set(sunAngleTurns - (float) Math.floor(sunAngleTurns), seconds, 0.0f, 0.0f);
        frame.weather.set(rain, thunder, 0.0f, 0.0f);
    }

    private void fillShadowInfo() {
        RenderSettings.Shadows shadows = settings.shadows();
        frame.shadowParams.set(shadows.darkness(), ShadowDepth.forExtraDarkness(shadows.sunBrightness()), shadows.sunlightColour(), shadows.reach());
        if (sunMap != null) {
            sunMap.frontSpace().cameraRelative(camera.x, camera.y, camera.z, frame.sunShadowMatrix);
            sunMap.previousSpace().cameraRelative(camera.x, camera.y, camera.z, frame.sunShadowMatrixPrevious);
        }
        if (mobMap != null) {
            frame.mobShadowMatrix.set(mobMap.cameraRelativeMatrix());
        }
        frame.shadowMapInfo.set(
            sunMap == null ? 1 : sunMap.size(), sunMap == null ? 1 : (float) sunMap.blocksPerTexel(),
            mobMap == null ? 1 : mobMap.size(), mobMap == null ? 1 : (float) mobMap.blocksPerTexel());
        frame.shadowFade.set(sunMap == null ? 1.0f : sunMap.fadeWeight(), shadowsActive ? celestial.lightStrength() : 0.0f, 0.0f,
            shadows.offset().texels());
    }

    private int featureFlags(boolean cameraUnderwater) {
        RenderSettings.Shadows shadows = settings.shadows();
        int flags = 0;
        boolean sunMapReady = shadowsActive && sunMap != null && sunMap.hasMap();
        if (shadowsActive) {
            flags |= FrameFlags.FEATURE_SHADOWS;
            if (shadows.lightModel() == RenderSettings.LightModel.RELIT) {
                flags |= FrameFlags.FEATURE_ENHANCED_SHADOWS;
            }
        }
        if (sunMapReady) {
            flags |= FrameFlags.FEATURE_SUN_MAP;
            if (sunMap.isFading()) {
                flags |= FrameFlags.FEATURE_SUN_MAP_FADING;
            }
        }
        if (mobShadowsActive && mobMap != null) {
            flags |= FrameFlags.FEATURE_MOB_SHADOWS | FrameFlags.FEATURE_MOB_MAP;
        }
        if (cameraUnderwater) {
            flags |= FrameFlags.FEATURE_CAMERA_UNDERWATER;
        }
        if (settings.lighting().glowHeat()) {
            flags |= FrameFlags.FEATURE_ENHANCED_LIGHT;
        }
        if (materials.view() != null) {
            flags |= FrameFlags.FEATURE_MATERIALS;
        }
        if (DEBUG_SHADOWS) {
            flags |= FrameFlags.FEATURE_DEBUG_SHADOWS;
        }
        return flags;
    }

    private static int pixelLockFlags(RenderSettings.PixelLock lock) {
        int flags = 0;
        flags |= lock.locksShadows() ? FrameFlags.LOCK_SHADOWS : 0;
        flags |= lock.locksCaustics() ? FrameFlags.LOCK_CAUSTICS : 0;
        flags |= lock.locksWaterSurface() ? FrameFlags.LOCK_WATER_SURFACE : 0;
        flags |= lock.locksGlint() ? FrameFlags.LOCK_GLINT : 0;
        flags |= lock.locksSunbeams() ? FrameFlags.LOCK_LIGHT_RAYS : 0;
        return flags;
    }

    private static int dimensionKind(@Nullable ClientLevel level) {
        if (level == null || level.dimension() == Level.OVERWORLD) {
            return FrameFlags.DIMENSION_OVERWORLD;
        }
        if (level.dimension() == Level.NETHER) {
            return FrameFlags.DIMENSION_NETHER;
        }
        if (level.dimension() == Level.END) {
            return FrameFlags.DIMENSION_END;
        }
        return FrameFlags.DIMENSION_MODDED;
    }

    private static boolean isZeroToOneDepth() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
    }

    // --- Diagnostics, read by the QA harness and the settings screen's footer ---

    /** How many times the cached sun map has been redrawn since start-up. */
    public int sunMapRedrawCount() {
        return redrawCount;
    }

    /** Redraws caused by caster changes near the camera (the player building, or a false positive). */
    public int nearChangeRedrawCount() {
        return nearChangeRedrawCount;
    }

    /** Section rebuilds that were dropped because their caster geometry was unchanged. */
    public long unchangedCasterRebuilds() {
        return casters == null ? 0 : casters.unchangedRebuilds();
    }

    public ShadowRedrawRules.Reason lastRedrawReason() {
        return lastRedrawReason;
    }

    public int casterSectionCount() {
        return casters == null ? 0 : casters.builtCount();
    }

    public ShadowCasterStore.@Nullable Memory casterMemory() {
        return casters == null ? null : casters.memory();
    }

    public int casterSectionsPending() {
        return casters == null || !shadowsActive ? 0 : casters.pendingInRange();
    }

    public boolean shadowsActive() {
        return shadowsActive;
    }

    /**
     * Whether this frame draws entities into the mob map (Shadows and Creature shadows on, with a
     * sun or moon to cast them). Vanilla's round entity shadows are hidden while it does.
     */
    public boolean drawsCreatureShadows() {
        return mobShadowsActive;
    }

    /** How many of the shadow maps (two sun map textures count as one) hold GPU memory now. */
    public int shadowMapsAllocated() {
        return (sunMap == null ? 0 : 1) + (mobMap == null ? 0 : 1);
    }

    /**
     * Binds LiteLights' uniforms with the world frame on a pass opened after the level (post
     * passes), where {@code bindDefaultUniforms} would give the neutral frame.
     */
    public void bindWorldUniforms(RenderPass pass) {
        if (binder != null && uniforms != null) {
            binder.bind(pass, uniforms.world());
        }
    }
}
