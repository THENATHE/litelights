package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.api.DimensionAspect;
import com.thenathe.litelights.api.FarTerrainSettings;
import com.thenathe.litelights.api.FarTerrainSettings.FarShadowMode;
import com.thenathe.litelights.api.FeatureStates;
import com.thenathe.litelights.api.FrameAccess;
import com.thenathe.litelights.api.FrameBits;
import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.LiteLightsMod;
import com.thenathe.litelights.api.ModuleEvents;
import com.thenathe.litelights.api.ModuleFeature;
import com.thenathe.litelights.api.RenderPasses;
import com.thenathe.litelights.render.feature.farterrain.land.FarLand;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

/**
 * Far terrain: LiteLights' own far land past the render distance ({@link FarLand}) and far shadows
 * on LiteLights' land past the cached shadow map (docs/FAR-TERRAIN.md).
 *
 * <p>Voxy and Distant Horizons support was dropped in 1.0.8; the code is in git history up to
 * 1.0.7. While either is installed this module steps aside completely: far shadows would land on
 * their terrain as well as LiteLights' own, so nothing here runs, nothing touches their pixels, and
 * the Far shadows row says why.
 *
 * <p>With far shadows off, or no land past the shadow distance, nothing runs and nothing is allocated.
 */
public final class FarTerrain implements ModuleEvents.BeforeTranslucents {
    private static final FarTerrain INSTANCE = new FarTerrain();

    /** The top of a full water block above sea level's block: sea level is the first block above the water. */
    private static final float SEA_SURFACE_BELOW_SEA_LEVEL = 1.0f - 8.0f / 9.0f;
    /** Blocks around the player's body the screen-space far-shadow trace also leaves out (arms, a held item). */
    private static final float PLAYER_MARGIN = 1.0f;

    /** The far terrain mods whose support was dropped in 1.0.8, by mod id. */
    private static final String[] DROPPED_MODS = {"voxy", "distanthorizons"};

    private final FeatureStates features = LiteLightsApi.features();
    private final FarTerrainPass pass = new FarTerrainPass();
    private final FarShadows shadows = new FarShadows();
    private final FarValues values = new FarValues();

    private FarTerrainSettings settings = LiteLightsApi.settings().farTerrainDefaults();
    /** Passes drawn since start-up, for QA: a switched-off feature must draw none. */
    private long passes;

    private FarTerrain() {
    }

    public static FarTerrain get() {
        return INSTANCE;
    }

    /** Called once, as the module starts (FarTerrainModule). */
    static void register() {
        FeatureStates features = INSTANCE.features;
        String dropped = droppedModInstalled();
        if (dropped != null) {
            LiteLightsMod.LOG.info("{} is installed; LiteLights' far terrain steps aside (Voxy and Distant Horizons support was dropped in 1.0.8)", dropped);
            // The row says why it is off, not that support was dropped: that stays an internal note (user, 2026-11-25).
            features.markUnavailable("far_shadows", "Off while " + dropped + " is installed: it draws far terrain of its own, so LiteLights' "
                + "far terrain and these shadows stay off");
            return;
        }
        features.claimPipelines(ModuleFeature.FAR_SHADOWS, FarTerrainPass.MAIN, FarShadows.SCREEN, FarShadows.ADVANCED);
        FarLand.register();
        ModuleEvents.FRAME_UPDATE.register(INSTANCE::onFrameUpdate);
        ModuleEvents.BEFORE_TRANSLUCENTS.register(INSTANCE);
        ModuleEvents.BLOCK_CHANGED.register(INSTANCE::onBlockChanged);
        ModuleEvents.WORLD_LEFT.register(INSTANCE::onWorldLeft);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> INSTANCE.shadows.heightfield().chunkChanged(chunk.getPos().x(), chunk.getPos().z()));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> INSTANCE.shadows.heightfield().chunkChanged(chunk.getPos().x(), chunk.getPos().z()));
    }

    /** The name of an installed far terrain mod whose support was dropped, or null. */
    private static String droppedModInstalled() {
        for (String modId : DROPPED_MODS) {
            var container = FabricLoader.getInstance().getModContainer(modId);
            if (container.isPresent()) {
                return container.get().getMetadata().getName();
            }
        }
        return null;
    }

    // --- Per frame ---

    private void onFrameUpdate(FrameAccess frame, FarTerrainSettings frameSettings) {
        settings = frameSettings;
        FarLand.get().frameUpdate(frame);
        FarShadowMode mode = farShadowMode(frame);
        shadows.applyMode(mode);
        ClientLevel level = Minecraft.getInstance().level;
        if (mode == FarShadowMode.ADVANCED && level != null) {
            try {
                CameraRenderState camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
                BlockPos cameraBlock = camera.blockPos;
                shadows.heightfield().update(level, cameraBlock.getX() >> 4, cameraBlock.getZ() >> 4,
                    Minecraft.getInstance().options.getEffectiveRenderDistance());
            } catch (RuntimeException error) {
                features.failed(ModuleFeature.FAR_SHADOWS, error);
                shadows.applyMode(FarShadowMode.OFF);
            }
        }
    }

    private FarShadowMode farShadowMode(FrameAccess frame) {
        boolean shadowsOn = settings.shadows() && (frame.featureFlags() & FrameBits.SHADOWS) != 0
            && features.dimensionAllows(DimensionAspect.SHADOWS) && features.mayRun(ModuleFeature.FAR_SHADOWS);
        return shadowsOn ? settings.farShadows() : FarShadowMode.OFF;
    }

    /** Whether the main target needs the far shadow pass this frame (asked before translucent terrain). */
    @Override
    public boolean wantsPassBeforeTranslucents() {
        return FarLand.get().wantsPass() || farShadowsWanted();
    }

    private boolean farShadowsWanted() {
        return farShadowMode(LiteLightsApi.frame()) != FarShadowMode.OFF && ownLandPastShadows();
    }

    /**
     * LiteLights' own land reaches past the point where the shadow map fades out. Sections are kept
     * within a circle of the render distance, plus part of a section.
     */
    private boolean ownLandPastShadows() {
        if (FarLand.get().wantsPass()) {
            return true;
        }
        float landReach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f + 16.0f;
        return landReach > settings.shadowReach() * 0.9f;
    }

    /**
     * Just before translucent terrain, with no render pass open: the far land, then far shadows on
     * the main target (so they fall on the far land too).
     */
    @Override
    public void beforeTranslucents(RenderTarget mainTarget) {
        FarLand.get().draw(mainTarget);
        if (!farShadowsWanted()) {
            return;
        }
        GpuTexture colorTexture = mainTarget.getColorTexture();
        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (colorTexture == null || colorView == null || depthView == null) {
            return;
        }
        FarShadowMode mode = farShadowMode(LiteLightsApi.frame());
        ClientLevel level = Minecraft.getInstance().level;
        float shadowDistance = settings.shadowReach();
        values.shadowFadeStart = shadowDistance * 0.9f;
        values.shadowFadeEnd = shadowDistance;
        values.seaSurfaceHeight = level == null ? 0.0f
            : (float) (level.getSeaLevel() - SEA_SURFACE_BELOW_SEA_LEVEL - Minecraft.getInstance().gameRenderer.gameRenderState()
                .levelRenderState.cameraRenderState.pos.y);
        placePlayer();
        try {
            GpuTextureView farShadow = shadows.draw(depthView, values, mode);
            RenderPasses passes = LiteLightsApi.passes();
            passes.beginTiming(RenderPasses.TimedSection.FAR_TERRAIN);
            pass.draw(colorTexture, colorView, depthView, values, farShadow);
            passes.endTiming(RenderPasses.TimedSection.FAR_TERRAIN);
            this.passes++;
            features.succeeded(ModuleFeature.FAR_SHADOWS);
        } catch (RuntimeException error) {
            features.failed(ModuleFeature.FAR_SHADOWS, error);
        }
    }

    /**
     * Where the player the camera follows stands, for the screen-space far-shadow trace to leave
     * out: their body in third person, and what they hold, fill much of the screen and would
     * otherwise cast a shadow over the land in front of them. A sphere round the middle of the body,
     * a block wider than it all round, takes in the arms and a held sword or bow.
     */
    private void placePlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        Entity player = minecraft.getCameraEntity();
        if (player == null) {
            values.playerReach = 0.0f;
            return;
        }
        Vec3 camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
        float partialTick = minecraft.gameRenderer.gameRenderState().levelRenderState.worldPartialTicks;
        Vec3 feet = player.getPosition(partialTick);
        values.playerCentre.set((float) (feet.x - camera.x), (float) (feet.y + player.getBbHeight() * 0.5 - camera.y), (float) (feet.z - camera.z));
        values.playerReach = Math.max(player.getBbHeight(), player.getBbWidth()) * 0.5f + PLAYER_MARGIN;
    }

    /**
     * The level's far plane (mixin.farterrain.CameraFarPlaneMixin): vanilla's, moved out past the
     * far land while it is on.
     */
    public float farPlane(float vanillaFarPlane) {
        return FarLand.get().farPlane(vanillaFarPlane);
    }

    // --- Lifecycle ---

    private void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        shadows.heightfield().chunkChanged(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private void onWorldLeft() {
        shadows.close();
        pass.close();
    }

    /**
     * Vanilla's fog for the frame (mixin.farterrain.FogRendererFarLandMixin). While far land is
     * drawn and the camera is in the open air, the render-distance fog moves out to the far land's
     * end, so with vanilla's fog as with LiteLights' the near land doesn't fade out in front of it.
     */
    public void adjustVanillaFog(FogData fog, FogType cameraFluid) {
        float distance = FarLand.get().farDistance();
        if (distance <= 0.0f || cameraFluid != FogType.NONE) {
            return;
        }
        fog.renderDistanceEnd = Math.max(fog.renderDistanceEnd, distance);
        fog.renderDistanceStart = Math.max(fog.renderDistanceStart, distance * 0.75f);
    }

    /** For QA: what far terrain is doing. */
    public String describe() {
        String dropped = droppedModInstalled();
        return "steppedAsideFor=" + (dropped == null ? "none" : dropped) + ", passes=" + passes + ", " + shadows.describe()
            + ", farLand=" + FarLand.get().describe();
    }
}
