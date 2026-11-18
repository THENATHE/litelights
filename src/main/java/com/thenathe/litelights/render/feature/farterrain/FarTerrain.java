package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import com.thenathe.litelights.render.post.PostTimings;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * LiteLights on far terrain: Voxy's and Distant Horizons' level of detail terrain, and LiteLights'
 * own terrain past the cached shadow map (docs/COMPAT-LOD.md has the whole picture).
 *
 * <ul>
 *   <li><b>Voxy</b> has no API. It draws its far terrain into the main target, colour and depth, in
 *   the level's own projection, during Sodium's cutout pass. LiteLights reads it from there just
 *   before translucent terrain (the same point water copies the scene), telling Voxy's pixels from
 *   its own by the alpha its own surfaces write while Voxy draws ({@code near_mark.glsl}). Two small
 *   changes to vanilla's state make that exact: the level's far plane moves out past Voxy's own,
 *   so its depth isn't clamped, and vanilla's distance fog, which Voxy would otherwise lay over its
 *   terrain under LiteLights' haze, is pushed out as Voxy's own "fade" fog mode does. Nothing in
 *   Voxy is touched.</li>
 *   <li><b>Distant Horizons</b> has an API: its fog is cancelled through its fog event, and
 *   LiteLights shades its colour texture, with its depth texture and projection, just before
 *   Distant Horizons copies it into the main target ({@link DistantHorizonsHook}).</li>
 *   <li><b>Far shadows</b> ({@link FarShadows}) cover both, and LiteLights' own land past the
 *   shadow distance.</li>
 *   <li><b>Seasonal snow</b> ({@link FarSnow}), with Almanac on the client: the far mods' captured
 *   snow brought in line with the season.</li>
 * </ul>
 * With no far-terrain mod drawing and far shadows off (or nothing past the shadow distance),
 * nothing here runs and nothing is allocated.
 */
public final class FarTerrain {
    private static final FarTerrain INSTANCE = new FarTerrain();

    /**
     * The far plane while Voxy draws, in blocks: past Voxy's own (48000), so Voxy's terrain keeps
     * its true depth when Voxy writes it into the level's depth buffer. The depth buffer is 32-bit
     * float with reversed depth, so the near range keeps its precision.
     */
    private static final float VOXY_FAR_PLANE = 49152.0f;
    /** Voxy pushes vanilla's render-distance fog to 1e9 while it draws; anything past this means it did this frame. */
    private static final float FOG_PUSHED_OUT = 1.0e8f;
    /** The top of a full water block above sea level's block: sea level is the first block above the water. */
    private static final float SEA_SURFACE_BELOW_SEA_LEVEL = 1.0f - 8.0f / 9.0f;
    /** Blocks around the player's body the screen-space far-shadow trace also leaves out (arms, a held item). */
    private static final float PLAYER_MARGIN = 1.0f;

    private static final boolean VOXY_LOADED = FabricLoader.getInstance().isModLoaded("voxy");
    private static final boolean DISTANT_HORIZONS_LOADED = FabricLoader.getInstance().isModLoaded("distanthorizons");

    private final FarTerrainPass pass = new FarTerrainPass();
    private final FarShadows shadows = new FarShadows();
    private final FarValues values = new FarValues();
    private final FarWaterCapture waterCapture = new FarWaterCapture();
    private final FarSnow snow = FarSnow.create();
    private @Nullable DistantHorizonsHook distantHorizons;
    private @Nullable String distantHorizonsProblem;

    private RenderSettings settings = RenderSettings.DEFAULTS;
    private boolean voxyDrawing;
    private boolean voxyTreated;
    /** Passes drawn since start-up, for QA: a switched-off feature must draw none. */
    private long mainPasses;
    private long distantHorizonsPasses;
    /** Of those, the ones drawn with the Experimental far water extras' shader variant. */
    private long waterDetailPasses;
    /** And the ones drawn with far snow's variant. */
    private long snowPasses;

    private FarTerrain() {
    }

    public static FarTerrain get() {
        return INSTANCE;
    }

    /** Called once from the client entrypoint. */
    public static void register() {
        PipelineOwners.claim(RenderFeature.FAR_TERRAIN, FarTerrainPass.MAIN, FarTerrainPass.DISTANT_HORIZONS);
        PipelineOwners.claim(RenderFeature.FAR_WATER_DETAILS, FarTerrainPass.MAIN_WATER_DETAILS, FarTerrainPass.DISTANT_HORIZONS_WATER_DETAILS);
        PipelineOwners.claim(RenderFeature.FAR_SHADOWS, FarShadows.SCREEN, FarShadows.SCREEN_DH, FarShadows.ADVANCED, FarShadows.ADVANCED_DH);
        PipelineOwners.claim(RenderFeature.FAR_SNOW, FarTerrainPass.MAIN_SNOW, FarTerrainPass.DISTANT_HORIZONS_SNOW,
            FarTerrainPass.MAIN_WATER_DETAILS_SNOW, FarTerrainPass.DISTANT_HORIZONS_WATER_DETAILS_SNOW);
        LiteRenderEvents.FRAME_UPDATE.register(INSTANCE::onFrameUpdate);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> INSTANCE.shadows.heightfield().chunkChanged(chunk.getPos().x(), chunk.getPos().z()));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> INSTANCE.shadows.heightfield().chunkChanged(chunk.getPos().x(), chunk.getPos().z()));
        if (DISTANT_HORIZONS_LOADED) {
            INSTANCE.hookDistantHorizons();
        }
        INSTANCE.reportRow();
    }

    private void hookDistantHorizons() {
        try {
            distantHorizons = DistantHorizonsHook.register(this);
        } catch (LinkageError | RuntimeException error) {
            // A Distant Horizons whose API changed: LiteLights leaves its terrain alone and says why.
            distantHorizonsProblem = "Distant Horizons' API has changed (" + error.getClass().getSimpleName() + "); its far terrain is left as it draws it";
            LiteLightsClient.LOG.warn("Couldn't hook into Distant Horizons' API", error);
        }
    }

    private void reportRow() {
        Feature row = Feature.of("far_terrain");
        if (distantHorizonsProblem != null) {
            row.markUnavailable(distantHorizonsProblem);
        } else if (!VOXY_LOADED && !DISTANT_HORIZONS_LOADED) {
            row.markUnavailable("Needs Voxy or Distant Horizons");
            Feature.of("far_water_details").markUnavailable("Needs Voxy or Distant Horizons");
            Feature.of("far_snow").markUnavailable("Needs Voxy or Distant Horizons");
        }
        if (snow.problem() != null) {
            Feature.of("far_snow").markUnavailable(snow.problem());
        }
    }

    // --- Per frame ---

    private void onFrameUpdate(FrameState frame, RenderSettings frameSettings) {
        settings = frameSettings;
        CameraRenderState camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        voxyDrawing = VOXY_LOADED && camera.fogData.renderDistanceEnd >= FOG_PUSHED_OUT;
        voxyTreated = voxyDrawing && frameSettings.fog().farTerrain() && RenderFeatureStatus.mayRun(RenderFeature.FAR_TERRAIN);
        boolean distantHorizonsTreated = distantHorizons != null && distantHorizons.drawing() && frameSettings.fog().farTerrain()
            && RenderFeatureStatus.mayRun(RenderFeature.FAR_TERRAIN);
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        waterCapture.prepare((voxyTreated || distantHorizonsTreated) && wantsFarWater(frame), main.width, main.height);
        // LiteFeatureValuesA.x: where LiteLights' own land ends while Voxy's carries on, so LiteLights'
        // water can hand over to Voxy's look there (water/surface.glsl), whether or not LiteLights
        // shades Voxy's terrain; 0 otherwise. LiteFeatureValuesA.y: the same border for the far pass,
        // where far depth colour starts from that handed-over look and far edge foam never draws.
        float landEnd = voxyDrawing ? Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f : 0.0f;
        frame.featureValuesA.x = landEnd;
        frame.featureValuesA.y = landEnd;
        if (voxyTreated) {
            frame.featureFlags |= FrameFlags.FEATURE_MARK_NEAR;
        }
        RenderSettings.FarShadows mode = farShadowMode(frame);
        shadows.applyMode(mode);
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.dimension() != Level.OVERWORLD) {
            // Far snow is the overworld's (Almanac's map): elsewhere it keeps nothing of the overworld.
            snow.leaveLevel();
        }
        if (mode == RenderSettings.FarShadows.ADVANCED && level != null) {
            try {
                BlockPos cameraBlock = camera.blockPos;
                shadows.heightfield().update(level, cameraBlock.getX() >> 4, cameraBlock.getZ() >> 4,
                    Minecraft.getInstance().options.getEffectiveRenderDistance());
            } catch (RuntimeException error) {
                RenderFeatureStatus.failed(RenderFeature.FAR_SHADOWS, error);
                shadows.applyMode(RenderSettings.FarShadows.OFF);
            }
        }
    }

    /** Whether any far water look is on and allowed here, so the far mod's water must be found. */
    private boolean wantsFarWater(FrameState frame) {
        boolean skyless = (frame.featureFlags & FrameFlags.FEATURE_SKYLESS) != 0;
        if (skyless || Minecraft.getInstance().level == null) {
            return false;
        }
        RenderSettings.Water water = settings.water();
        boolean looks = water.farTerrain() && DimensionGate.allows(Aspect.REFLECTIONS) && RenderFeatureStatus.mayRun(RenderFeature.FAR_WATER);
        return looks || farWaterDetail(water.farCaustics()) || farWaterDetail(water.farDepthColour()) || farWaterDetail(water.farEdgeFoam());
    }

    /** One Experimental far water extra: its switch, the dimension's switch, and the shared feature. */
    private boolean farWaterDetail(boolean on) {
        return on && DimensionGate.allows(Aspect.REFLECTIONS) && RenderFeatureStatus.mayRun(RenderFeature.FAR_WATER_DETAILS);
    }

    // --- The far mods' translucent passes (FarWaterCapture) ---

    /** From mixin.voxy, just before Voxy draws its translucent terrain. */
    public void voxyBeforeTranslucents() {
        waterCapture.voxyBeforeTranslucents();
    }

    /** From mixin.voxy, just after Voxy draws its translucent terrain. */
    public void voxyAfterTranslucents() {
        waterCapture.voxyAfterTranslucents();
    }

    /** From DistantHorizonsHook, before Distant Horizons' translucent pass, with its depth texture. */
    void distantHorizonsBeforeTranslucents(GpuTexture depth) {
        waterCapture.distantHorizonsBeforeTranslucents(depth);
    }

    void distantHorizonsCombinedPass() {
        waterCapture.distantHorizonsCombinedPass();
    }

    private RenderSettings.FarShadows farShadowMode(FrameState frame) {
        boolean shadowsOn = settings.shadows().enabled() && (frame.featureFlags & FrameFlags.FEATURE_SHADOWS) != 0
            && DimensionGate.allows(Aspect.SHADOWS) && RenderFeatureStatus.mayRun(RenderFeature.FAR_SHADOWS);
        return shadowsOn ? settings.shadows().farShadows() : RenderSettings.FarShadows.OFF;
    }

    /** True while another mod's far terrain is drawn and LiteLights shades it: the land no longer ends at the render distance. */
    public boolean farTerrainDrawn() {
        return voxyTreated || distantHorizons != null && distantHorizons.drawing() && settings.fog().farTerrain();
    }

    /** Whether the main target needs the far-terrain pass this frame (asked before translucent terrain). */
    public boolean wantsPassBeforeTranslucents() {
        FrameState frame = LiteLightsRenderer.get().frame();
        return voxyTreated || farShadowMode(frame) != RenderSettings.FarShadows.OFF && ownLandPastShadows();
    }

    /**
     * LiteLights' own land reaches past the point where the shadow map fades out. Sections are kept
     * within a circle of the render distance, plus part of a section.
     */
    private boolean ownLandPastShadows() {
        float landReach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f + 16.0f;
        return landReach > settings.shadows().reach() * 0.9f;
    }

    /** Just before translucent terrain, with no render pass open: shades the main target. */
    public void beforeTranslucents(RenderTarget mainTarget) {
        if (!wantsPassBeforeTranslucents()) {
            return;
        }
        GpuTexture colorTexture = mainTarget.getColorTexture();
        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (colorTexture == null || colorView == null || depthView == null) {
            return;
        }
        values.inverseProjection.identity();
        values.reversedDepth = true;
        values.zeroToOneDepth = true;
        shade(colorTexture, colorView, depthView, voxyTreated, false);
    }

    /**
     * Shades Distant Horizons' colour texture, from its depth texture and projection, before it is
     * copied into the main target. Called by {@link DistantHorizonsHook}.
     */
    void shadeDistantHorizons(GpuTexture colorTexture, GpuTextureView colorView, GpuTextureView depthView, Matrix4fc inverseProjection,
                              boolean reversedDepth, boolean zeroToOneDepth) {
        if (!settings.fog().farTerrain() || !RenderFeatureStatus.mayRun(RenderFeature.FAR_TERRAIN)) {
            return;
        }
        values.inverseProjection.set(inverseProjection);
        values.reversedDepth = reversedDepth;
        values.zeroToOneDepth = zeroToOneDepth;
        shade(colorTexture, colorView, depthView, true, true);
    }

    /** True when LiteLights' haze stands in for Distant Horizons' fog this frame. */
    boolean replacesDistantHorizonsFog() {
        FrameState frame = LiteLightsRenderer.get().frame();
        return settings.fog().farTerrain() && RenderFeatureStatus.mayRun(RenderFeature.FAR_TERRAIN)
            && (frame.featureFlags & FrameFlags.FEATURE_LITE_FOG) != 0;
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

    private void shade(GpuTexture colorTexture, GpuTextureView colorView, GpuTextureView depthView, boolean farTerrain, boolean distantHorizons) {
        FrameState frame = LiteLightsRenderer.get().frame();
        RenderSettings.FarShadows mode = farShadowMode(frame);
        ClientLevel level = Minecraft.getInstance().level;
        boolean skyless = (frame.featureFlags & FrameFlags.FEATURE_SKYLESS) != 0;
        values.farTerrainPresent = farTerrain;
        values.relight = farTerrain && settings.shadows().enabled() && DimensionGate.allows(Aspect.SHADOWS);
        values.fog = farTerrain && DimensionGate.allows(Aspect.EFFECTS);
        values.farWater = farTerrain && !skyless && level != null && settings.water().farTerrain()
            && DimensionGate.allows(Aspect.REFLECTIONS) && RenderFeatureStatus.mayRun(RenderFeature.FAR_WATER);
        values.farShadows = mode != RenderSettings.FarShadows.OFF;
        boolean waterAllowed = farTerrain && !skyless && level != null;
        values.farCaustics = waterAllowed && farWaterDetail(settings.water().farCaustics());
        values.farDepthColour = waterAllowed && farWaterDetail(settings.water().farDepthColour());
        values.farEdgeFoam = waterAllowed && farWaterDetail(settings.water().farEdgeFoam());
        GpuTextureView waterA = null;
        GpuTextureView waterB = null;
        if (farTerrain && distantHorizons && waterCapture.distantHorizonsCaptured()) {
            waterA = waterCapture.distantHorizonsFloorView();
        } else if (farTerrain && !distantHorizons && waterCapture.voxyCaptured()) {
            waterA = waterCapture.voxyBeforeView();
            waterB = waterCapture.voxyAfterView();
        }
        values.waterCaptured = waterA != null;
        float shadowDistance = settings.shadows().reach();
        values.shadowFadeStart = shadowDistance * 0.9f;
        values.shadowFadeEnd = shadowDistance;
        values.seaSurfaceHeight = level == null ? 0.0f
            : (float) (level.getSeaLevel() - SEA_SURFACE_BELOW_SEA_LEVEL - Minecraft.getInstance().gameRenderer.gameRenderState()
                .levelRenderState.cameraRenderState.pos.y);
        GpuTextureView farShadow = null;
        if (values.farShadows) {
            placePlayer();
            try {
                farShadow = shadows.draw(depthView, values, mode, distantHorizons);
                RenderFeatureStatus.succeeded(RenderFeature.FAR_SHADOWS);
            } catch (RuntimeException error) {
                RenderFeatureStatus.failed(RenderFeature.FAR_SHADOWS, error);
                values.farShadows = false;
            }
        }
        if (!farTerrain && !values.farShadows) {
            return;
        }
        FarSnow snowThisFrame = farTerrain && !skyless && level != null && level.dimension() == Level.OVERWORLD && prepareSnow(level) ? snow : null;
        try {
            PostTimings.begin(PostTimings.FAR_TERRAIN);
            pass.draw(colorTexture, colorView, depthView, values, farShadow, waterA, waterB, distantHorizons, snowThisFrame);
            PostTimings.end(PostTimings.FAR_TERRAIN);
            if (distantHorizons) {
                distantHorizonsPasses++;
            } else {
                mainPasses++;
            }
            if (farTerrain) {
                RenderFeatureStatus.succeeded(RenderFeature.FAR_TERRAIN);
            }
            if (values.anyWaterDetails()) {
                waterDetailPasses++;
                RenderFeatureStatus.succeeded(RenderFeature.FAR_WATER_DETAILS);
            }
            if (snowThisFrame != null) {
                snowPasses++;
                RenderFeatureStatus.succeeded(RenderFeature.FAR_SNOW);
            }
        } catch (RuntimeException error) {
            RenderFeature failing = snowThisFrame != null ? RenderFeature.FAR_SNOW : values.anyWaterDetails() ? RenderFeature.FAR_WATER_DETAILS
                : farTerrain ? RenderFeature.FAR_TERRAIN : RenderFeature.FAR_SHADOWS;
            RenderFeatureStatus.failed(failing, error);
        }
    }

    /**
     * Brings far snow up to date for this frame, if it is on and allowed: Almanac on the client,
     * the switch on, the overworld's far terrain. Returns whether its variant should draw.
     */
    private boolean prepareSnow(ClientLevel level) {
        if (!snow.available() || !settings.fog().farSnow() || !RenderFeatureStatus.mayRun(RenderFeature.FAR_SNOW)) {
            return false;
        }
        try {
            var levelState = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState;
            Vec3 camera = levelState.cameraRenderState.pos;
            return snow.prepare(level, camera.x, camera.z, level.getGameTime(), levelState.worldPartialTicks);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.FAR_SNOW, error);
            return false;
        }
    }

    // --- Vanilla state while Voxy draws (from mixin.farterrain) ---

    /** The level's far plane: pushed past Voxy's while LiteLights shades Voxy's terrain. */
    public float farPlane(float vanillaFarPlane) {
        if (!VOXY_LOADED) {
            return vanillaFarPlane;
        }
        RenderSettings current = RenderSettingsAccess.current();
        return current.fog().farTerrain() && RenderFeatureStatus.mayRun(RenderFeature.FAR_TERRAIN)
            ? Math.max(vanillaFarPlane, VOXY_FAR_PLANE) : vanillaFarPlane;
    }

    /**
     * Voxy lays vanilla's environmental fog over its terrain unless its fog mode is "fade". While
     * LiteLights' haze covers that terrain instead, the fog is pushed out the way that mode does,
     * so the two never stack. Close fog (blindness, darkness, being in a fluid) is left alone, and
     * so is everything when Voxy isn't drawing.
     */
    public void adjustVanillaFog(FogData fog, FogType medium) {
        if (!VOXY_LOADED || fog.renderDistanceEnd < FOG_PUSHED_OUT || medium != FogType.NONE || fog.environmentalEnd < 10.0f) {
            return;
        }
        RenderSettings current = RenderSettingsAccess.current();
        if (current.fog().farTerrain() && current.fog().look() != RenderSettings.FogLook.VANILLA
            && RenderFeatureStatus.mayRun(RenderFeature.FAR_TERRAIN)) {
            fog.environmentalStart = FOG_PUSHED_OUT;
            fog.environmentalEnd = FOG_PUSHED_OUT;
        }
    }

    // --- Lifecycle ---

    public void onBlockChanged(BlockPos pos) {
        shadows.heightfield().chunkChanged(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public void onWorldLeft() {
        shadows.close();
        pass.close();
        waterCapture.close();
        snow.close();
        voxyDrawing = false;
        voxyTreated = false;
    }

    /** For QA: far snow's change of season, 1 when none is being shown. */
    public float farSnowProgress() {
        return snow.progress();
    }

    /** For QA: how many changes of season far snow has shown since the world was joined. */
    public int farSnowChanges() {
        return snow.changes();
    }

    /** For QA: how many changes far snow picked up part way on joining. */
    public int farSnowResumes() {
        return snow.resumes();
    }

    /** For QA: the game tick the change being shown (or the last one) began at. */
    public long farSnowChangeStart() {
        return snow.changeStart();
    }

    /** For QA: far snow lines known in the inner ring. */
    public int farSnowKnown() {
        return snow.knownInner();
    }

    /** For QA: what the far-terrain support found. */
    public String describe() {
        return "voxyLoaded=" + VOXY_LOADED + ", voxyDrawing=" + voxyDrawing + ", voxyTreated=" + voxyTreated
            + ", mainPasses=" + mainPasses + ", distantHorizonsPasses=" + distantHorizonsPasses + ", waterDetailPasses=" + waterDetailPasses
            + ", snowPasses=" + snowPasses + ", farSnow={" + snow.describe() + "}"
            + ", " + shadows.describe()
            + ", " + waterCapture.describe()
            + ", distantHorizons=" + (distantHorizons == null ? Objects.toString(distantHorizonsProblem, "absent") : distantHorizons.describe());
    }
}
