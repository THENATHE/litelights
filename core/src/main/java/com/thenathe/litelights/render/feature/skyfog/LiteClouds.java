package com.thenathe.litelights.render.feature.skyfog;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.clouds.DynamicClouds;
import com.thenathe.litelights.render.frame.CelestialLight;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.ShownWeather;
import com.thenathe.litelights.render.frame.WeatherPreview;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * LiteLights' clouds: one or two {@link CloudLayer}s in place of vanilla's cloud renderer, or the
 * Dynamic clouds ({@link DynamicClouds}). The LiteLights Clouds setting (Off, Flat, Fancy,
 * Dynamic) decides the cloud style while it is in charge; vanilla's cloud distance still applies
 * to the layers. The Dynamic clouds draw before translucent terrain instead of in vanilla's cloud
 * turn, so they skip the calls below; if they can't run, the Fancy layers stand in. Where the sky switch of a modded dimension is off, or
 * after a failure, vanilla's clouds and setting come back.
 *
 * <p>Hooked in from {@code mixin.skyfog.CloudHooksMixin}: the options extraction asks
 * {@link #statusFor} once per frame, and the level renderer's cloud calls are routed here.
 */
public final class LiteClouds {
    private static final LiteClouds INSTANCE = new LiteClouds();
    private static final int MAP_SIZE = 256;
    /** The second layer floats this many blocks above the first. */
    private static final float UPPER_LAYER_RISE = 30.0f;

    private @Nullable CloudLayer lower;
    private @Nullable CloudLayer upper;
    private boolean active;
    private boolean twoLayers;
    private boolean readyMarked;
    /** This frame's clouds are prepared and not drawn yet, and with what style. */
    private @Nullable CloudStatus pendingStatus;
    private boolean drawnEarly;
    /** This frame's clouds are the Dynamic ones, which draw before translucent terrain. */
    private boolean dynamic;
    /** The top of the layer the rain falls from, in world y, while this frame's clouds are Fancy or Flat layers; NaN otherwise. */
    private float rainLayerTop = Float.NaN;

    private LiteClouds() {
    }

    public static LiteClouds get() {
        return INSTANCE;
    }

    /**
     * The top of the layer the rain falls from (the lower one; the second layer is alto cloud), in
     * world y: the Cloud height setting's height, plus the cells' height with Fancy clouds. NaN
     * unless this frame's clouds are LiteLights' Fancy or Flat layers (climate.RegionalRain).
     */
    public float rainLayerTop() {
        return rainLayerTop;
    }

    /** Whether LiteLights draws this frame's clouds (decided during options extraction). */
    public boolean activeThisFrame() {
        return active;
    }

    /**
     * The cloud style to use this frame, given vanilla's setting. While LiteLights is in charge its
     * own setting wins: Off, Flat (vanilla's Fast) or Fancy.
     */
    public CloudStatus statusFor(CloudStatus vanilla) {
        RenderSettings settings = RenderSettingsAccess.current();
        active = DimensionGate.allows(Aspect.SKY) && RenderFeatureStatus.mayRun(RenderFeature.CLOUDS);
        dynamic = false;
        rainLayerTop = Float.NaN;
        if (!active) {
            return vanilla;
        }
        twoLayers = settings.sky().cloudLayers() >= 2;
        return switch (settings.sky().clouds()) {
            case OFF -> CloudStatus.OFF;
            case FLAT -> CloudStatus.FAST;
            case FANCY -> CloudStatus.FANCY;
            case DYNAMIC -> {
                // Vanilla's cloud calls still come (Fancy), which is where the Dynamic clouds prepare.
                dynamic = DynamicClouds.get().mayDraw();
                yield CloudStatus.FANCY;
            }
        };
    }

    public void prepare(int color, CloudStatus status, float bottomY, int rangeChunks, Vec3 camera, long gameTime, float partialTicks) {
        if (dynamic) {
            pendingStatus = null;
            DynamicClouds.get().prepare(color, bottomY, camera, gameTime, partialTicks);
            if (!readyMarked) {
                readyMarked = true;
                RenderFeatureStatus.markReady("clouds");
            }
            return;
        }
        try {
            if (lower == null) {
                lower = new CloudLayer("lower", CloudShapes.cumulus(MAP_SIZE), MAP_SIZE, 10.0f, 4.5f, 0.025f, 3.96f, 1.0f);
                upper = new CloudLayer("upper", CloudShapes.altocumulus(MAP_SIZE), MAP_SIZE, 10.0f, 2.5f, 0.04f, 517.0f, 0.82f);
            }
            color = previewColour(color);
            lower.prepare(color, status, bottomY, rangeChunks, camera, gameTime, partialTicks);
            if (status != CloudStatus.OFF) {
                rainLayerTop = bottomY + (status == CloudStatus.FANCY ? lower.thickness() : 0.0f);
            }
            if (twoLayers) {
                upper.prepare(color, status, bottomY + UPPER_LAYER_RISE, rangeChunks, camera, gameTime, partialTicks);
            }
            if (!readyMarked) {
                readyMarked = true;
                RenderFeatureStatus.markReady("clouds");
            }
            pendingStatus = status;
            RenderFeatureStatus.succeeded(RenderFeature.CLOUDS);
        } catch (RuntimeException error) {
            pendingStatus = null;
            RenderFeatureStatus.failed(RenderFeature.CLOUDS, error);
        }
    }

    /**
     * Draws this frame's clouds ahead of vanilla's turn, right after translucent terrain and before
     * the particles (mixin.skyfog.CloudHooksMixin). Vanilla draws clouds last, after particles that
     * have already written their depth: smoke in front of a cloud then cut its whole quad out of the
     * cloud, see-through texels and all, and the sky showed round every puff. Drawn first, the
     * clouds are simply behind the smoke, which blends over them.
     */
    public void renderBeforeParticles(RenderPass pass) {
        CloudStatus status = pendingStatus;
        if (status != null && active) {
            pendingStatus = null;
            render(status, pass);
            drawnEarly = true;
        }
    }

    /** True once if {@link #renderBeforeParticles} drew this frame's clouds: vanilla's turn is then skipped. */
    public boolean takeDrawnEarly() {
        boolean drawn = drawnEarly;
        drawnEarly = false;
        pendingStatus = null;
        return drawn;
    }

    /**
     * The clouds' colour while the settings screen previews a time or a weather: as at the
     * previewed time (the classic overworld cloud curve, from white) and greyed for the previewed
     * weather. Without a preview, vanilla's own colour as it is.
     */
    private static int previewColour(int vanilla) {
        RenderSettings settings = RenderSettingsAccess.current();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !settings.hasPreview() && !WeatherPreview.active()) {
            return vanilla;
        }
        // The rain and thunder vanilla's colours already carry: the player's share of them with
        // Almanac, the clouds' pace with the Dynamic clouds.
        float shownRain = ShownWeather.rain(level, 1.0f);
        float shownThunder = ShownWeather.thunder(level, 1.0f);
        float rain = WeatherPreview.rain(shownRain);
        float thunder = WeatherPreview.thunder(shownThunder);
        float red = ARGB.redFloat(vanilla);
        float green = ARGB.greenFloat(vanilla);
        float blue = ARGB.blueFloat(vanilla);
        if (settings.hasPreview()) {
            float facing = (float) Math.cos(CelestialLight.sunAngleForDayTime(settings.previewDayTime()));
            float brightness = LiteMath.clamp01(facing * 2.0f + 0.5f);
            red = green = blue = 1.0f;
            float grey = SkyFogMath.luminance(red, green, blue) * 0.6f;
            red = LiteMath.lerp(red, grey, rain * 0.95f);
            green = LiteMath.lerp(green, grey, rain * 0.95f);
            blue = LiteMath.lerp(blue, grey, rain * 0.95f);
            red *= brightness * 0.9f + 0.1f;
            green *= brightness * 0.9f + 0.1f;
            blue *= brightness * 0.85f + 0.15f;
        } else {
            // Vanilla's colour already carries the weather shown here; add only what the preview brings beyond it.
            float extraRain = Math.max(0.0f, rain - shownRain);
            float grey = SkyFogMath.luminance(red, green, blue) * 0.6f;
            red = LiteMath.lerp(red, grey, extraRain * 0.95f);
            green = LiteMath.lerp(green, grey, extraRain * 0.95f);
            blue = LiteMath.lerp(blue, grey, extraRain * 0.95f);
            thunder = Math.max(0.0f, thunder - shownThunder);
        }
        float storm = SkyFogMath.luminance(red, green, blue) * 0.2f;
        red = LiteMath.lerp(red, storm, thunder * 0.95f);
        green = LiteMath.lerp(green, storm, thunder * 0.95f);
        blue = LiteMath.lerp(blue, storm, thunder * 0.95f);
        return ARGB.colorFromFloat(ARGB.alphaFloat(vanilla), red, green, blue);
    }

    /**
     * Draws into the game's translucent pass. A failure switches the clouds off until their retry
     * (vanilla's clouds stand in meanwhile), and the pass is handed back as it came, so the frame
     * goes on.
     */
    public void render(CloudStatus status, RenderPass pass) {
        if (dynamic) {
            return;
        }
        try (RenderPass guarded = GuardedRenderPass.borrow(pass)) {
            for (CloudLayer layer : farthestFirst()) {
                if (layer != null) {
                    layer.render(status, guarded);
                }
            }
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.CLOUDS, error);
        }
    }

    public void renderOit(CloudStatus status, OitStage stage, GpuTextureView mainDepth, OitRenderPassProvider.Parameters parameters) {
        if (dynamic) {
            return;
        }
        try {
            for (CloudLayer layer : farthestFirst()) {
                if (layer != null) {
                    layer.renderOit(status, stage, mainDepth, parameters);
                }
            }
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.CLOUDS, error);
        }
    }

    private final CloudLayer[] order = new CloudLayer[2];

    /** Classic transparency blends in draw order, so the layer farther from the camera goes first. */
    private CloudLayer[] farthestFirst() {
        order[0] = lower;
        order[1] = twoLayers ? upper : null;
        if (twoLayers && lower != null && upper != null
            && Math.abs(upper.middleAboveCamera()) > Math.abs(lower.middleAboveCamera())) {
            order[0] = upper;
            order[1] = lower;
        }
        return order;
    }
}
