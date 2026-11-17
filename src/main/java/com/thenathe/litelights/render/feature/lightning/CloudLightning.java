package com.thenathe.litelights.render.feature.lightning;

import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.mixin.particles.BiomeManagerAccessor;
import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameState;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Lightning inside the clouds (storm099): each frame, the flashes {@link LightningSchedule} has
 * going on around the camera, and the glow of vanilla's real bolts, become the frame's
 * {@code LiteLightning} values (see {@code include/skyfog/lightning.glsl}). The two brightest
 * flashes light the clouds and the sky around them; all of them together light the air and the
 * land a little, from their direction.
 *
 * <p>Vanilla's Hide Lightning Flashes option is honoured as vanilla honours it for its own sky
 * flash: nothing brightens the fog, the sky or the land, and only a dim glow is left in the clouds.
 *
 * <p>Everything runs on the render thread; nothing is allocated per frame.
 */
public final class CloudLightning {
    private static final CloudLightning INSTANCE = new CloudLightning();

    /** Where the clouds are when nothing has said yet (vanilla's overworld cloud height). */
    private static final float DEFAULT_CLOUD_BASE = 192.33f;
    private static final int CELLS = (2 * LightningSchedule.CELL_REACH + 1) * (2 * LightningSchedule.CELL_REACH + 1);
    private static final int MAX_BOLTS = 4;
    private static final int MAX_SOURCES = CELLS + MAX_BOLTS;
    /** A real bolt's glow in the clouds above it, and how fast it dies away once the bolt goes dark (seconds). */
    private static final float BOLT_GLOW_RADIUS = 70.0f;
    private static final float BOLT_AFTERGLOW_SECONDS = 0.1f;
    /** A bolt not seen for this long has gone. */
    private static final double BOLT_FORGET_SECONDS = 1.0;
    /** Flashes past these distances (blocks) count for half: for choosing which to show, and for the air. */
    private static final float SHOWN_REACH = 500.0f;
    private static final float AIR_REACH = 260.0f;
    /** With Hide Lightning Flashes on, the clouds keep this much of the glow. */
    private static final float HIDDEN_CLOUD_GLOW = 0.3f;

    private final LightningSchedule.Flash scheduled = new LightningSchedule.Flash();

    // This frame's sources: the scheduled flashes going on, then the bolts.
    private final float[] sourceX = new float[MAX_SOURCES];
    private final float[] sourceY = new float[MAX_SOURCES];
    private final float[] sourceZ = new float[MAX_SOURCES];
    private final float[] sourceRadius = new float[MAX_SOURCES];
    private final float[] sourceBrightness = new float[MAX_SOURCES];
    private int sources;

    // Vanilla's bolts, as their renderer last saw them.
    private final int[] boltId = new int[MAX_BOLTS];
    private final double[] boltX = new double[MAX_BOLTS];
    private final double[] boltZ = new double[MAX_BOLTS];
    private final double[] boltLitAt = new double[MAX_BOLTS];
    private final double[] boltSeenAt = new double[MAX_BOLTS];
    private final boolean[] boltKnown = new boolean[MAX_BOLTS];

    private float cloudBase = Float.NaN;
    private @Nullable ClientLevel level;
    private boolean readyMarked;

    // For QA: this frame's brightest flash in the clouds and how much the air was lit.
    private float shownCloudFlash;
    private float shownAirLight;
    private int scheduledNow;

    private CloudLightning() {
    }

    public static CloudLightning get() {
        return INSTANCE;
    }

    /** Hooks into LiteLights' frame updates; called by the sky and fog feature as it registers. */
    public static void register() {
        LiteRenderEvents.FRAME_UPDATE.register(INSTANCE::onFrameUpdate);
    }

    /** Where this frame's clouds start, from the cloud hook (also while vanilla draws them). */
    public void cloudsAt(float bottomY) {
        cloudBase = bottomY;
    }

    /** The height of the cloud base, or NaN before any clouds were placed. */
    public float cloudBaseY() {
        return cloudBase;
    }

    /**
     * A vanilla bolt as its renderer sees it this frame. {@code lit} is whether it is in one of its
     * bright moments (vanilla's own sky flash comes with those).
     */
    public void boltSeen(int id, double x, double z, boolean lit) {
        double now = seconds();
        int slot = -1;
        int oldest = 0;
        for (int index = 0; index < MAX_BOLTS; index++) {
            if (boltKnown[index] && boltId[index] == id) {
                slot = index;
                break;
            }
            if (!boltKnown[index]) {
                slot = slot < 0 ? index : slot;
            } else if (boltSeenAt[index] < boltSeenAt[oldest]) {
                oldest = index;
            }
        }
        if (slot < 0) {
            slot = oldest;
        }
        if (!boltKnown[slot] || boltId[slot] != id) {
            boltKnown[slot] = true;
            boltId[slot] = id;
            boltLitAt[slot] = lit ? now : Double.NEGATIVE_INFINITY;
        }
        boltX[slot] = x;
        boltZ[slot] = z;
        boltSeenAt[slot] = now;
        if (lit) {
            boltLitAt[slot] = now;
        }
    }

    private void onFrameUpdate(FrameState frame, RenderSettings settings) {
        for (Vector4f value : frame.lightning) {
            value.zero();
        }
        shownCloudFlash = 0.0f;
        shownAirLight = 0.0f;
        scheduledNow = 0;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel currentLevel = minecraft.level;
        if (currentLevel != level) {
            level = currentLevel;
            Arrays.fill(boltKnown, false);
            cloudBase = Float.NaN;
        }
        if (currentLevel == null || !settings.weather().storms().lightning().inClouds() || !hasStormySky(currentLevel)
            || !RenderFeatureStatus.mayRun(RenderFeature.LIGHTNING)) {
            return;
        }
        try {
            fill(frame, minecraft, currentLevel);
            RenderFeatureStatus.succeeded(RenderFeature.LIGHTNING);
            if (!readyMarked) {
                readyMarked = true;
                RenderFeatureStatus.markReady("lightning");
            }
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.LIGHTNING, error);
            for (Vector4f value : frame.lightning) {
                value.zero();
            }
        }
    }

    /** Lightning lights the overworld's kind of sky only, where the dimension settings let weather and sky through. */
    private static boolean hasStormySky(ClientLevel level) {
        DimensionType dimension = level.dimensionType();
        return dimension.skybox() == DimensionType.Skybox.OVERWORLD && dimension.hasSkyLight()
            && DimensionGate.allows(Aspect.WEATHER) && DimensionGate.allows(Aspect.SKY);
    }

    private void fill(FrameState frame, Minecraft minecraft, ClientLevel currentLevel) {
        Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        double tick = currentLevel.getGameTime() + minecraft.gameRenderer.gameRenderState().levelRenderState.worldPartialTicks;
        float base = Float.isNaN(cloudBase) ? DEFAULT_CLOUD_BASE : cloudBase;
        float height = (float) (base - camera.y);
        sources = 0;
        gatherScheduled(currentLevel, camera, tick, height, LightningSchedule.storminess(frame.weather.y));
        gatherBolts(camera, height);
        if (sources == 0) {
            return;
        }

        // The two flashes that show most, nearer ones first, light the clouds and the sky.
        int first = -1;
        int second = -1;
        float firstScore = 0.0f;
        float secondScore = 0.0f;
        float air = 0.0f;
        float towardX = 0.0f;
        float towardY = 0.0f;
        float towardZ = 0.0f;
        for (int source = 0; source < sources; source++) {
            float distance = (float) Math.sqrt(sourceX[source] * sourceX[source] + sourceY[source] * sourceY[source]
                + sourceZ[source] * sourceZ[source]);
            float score = sourceBrightness[source] * falloff(distance, SHOWN_REACH);
            if (score > firstScore) {
                second = first;
                secondScore = firstScore;
                first = source;
                firstScore = score;
            } else if (score > secondScore) {
                second = source;
                secondScore = score;
            }
            float lighting = sourceBrightness[source] * falloff(distance, AIR_REACH);
            air += lighting;
            float weight = lighting / Math.max(distance, 1.0f);
            towardX += sourceX[source] * weight;
            towardY += sourceY[source] * weight;
            towardZ += sourceZ[source] * weight;
        }
        if (first < 0) {
            return;
        }

        boolean hidden = minecraft.options.hideLightningFlash().get();
        float night = 1.0f - frame.sunColor.w;
        float cloudGlow = (hidden ? HIDDEN_CLOUD_GLOW : 1.0f) * LiteMath.lerp(0.55f, 1.0f, night);
        writeSource(frame.lightning[1], first, cloudGlow);
        frame.lightning[3].x = sourceRadius[first];
        if (second >= 0) {
            writeSource(frame.lightning[2], second, cloudGlow);
            frame.lightning[3].y = sourceRadius[second];
        }
        shownCloudFlash = sourceBrightness[first] * cloudGlow;
        if (hidden) {
            return;
        }
        air = Math.min(air, 1.0f);
        float length = (float) Math.sqrt(towardX * towardX + towardY * towardY + towardZ * towardZ);
        if (length > 1.0e-4f) {
            frame.lightning[0].set(towardX / length, towardY / length, towardZ / length, air * LiteMath.lerp(0.12f, 0.45f, night));
        }
        frame.lightning[3].z = air * LiteMath.lerp(0.25f, 1.1f, night);
        frame.lightning[3].w = LiteMath.lerp(0.15f, 0.45f, night);
        shownAirLight = frame.lightning[0].w;
    }

    private void writeSource(Vector4f target, int source, float glow) {
        target.set(sourceX[source], sourceY[source], sourceZ[source], sourceBrightness[source] * glow);
    }

    /** The flashes the schedule has going on in the cells around the camera. */
    private void gatherScheduled(ClientLevel currentLevel, Vec3 camera, double tick, float height, float storminess) {
        if (storminess <= 0.0f) {
            return;
        }
        long seed = ((BiomeManagerAccessor) currentLevel.getBiomeManager()).litelights$biomeZoomSeed();
        int cameraCellX = LightningSchedule.cellOf(camera.x);
        int cameraCellZ = LightningSchedule.cellOf(camera.z);
        long beat = LightningSchedule.beatOf((long) Math.floor(tick));
        for (int cellX = cameraCellX - LightningSchedule.CELL_REACH; cellX <= cameraCellX + LightningSchedule.CELL_REACH; cellX++) {
            for (int cellZ = cameraCellZ - LightningSchedule.CELL_REACH; cellZ <= cameraCellZ + LightningSchedule.CELL_REACH; cellZ++) {
                // A flash runs into the next beat at most, so the one before is asked too.
                for (long asked = beat - 1; asked <= beat; asked++) {
                    if (!LightningSchedule.flash(seed, cellX, cellZ, asked, storminess, scheduled)) {
                        continue;
                    }
                    float brightness = LightningSchedule.brightness(scheduled, tick);
                    if (brightness <= 0.0f || sources >= CELLS) {
                        continue;
                    }
                    sourceX[sources] = (float) (LightningSchedule.centreX(scheduled, tick) - camera.x);
                    sourceY[sources] = height;
                    sourceZ[sources] = (float) (LightningSchedule.centreZ(scheduled, tick) - camera.z);
                    sourceRadius[sources] = LightningSchedule.radius(scheduled, tick);
                    sourceBrightness[sources] = brightness;
                    sources++;
                    scheduledNow++;
                }
            }
        }
    }

    /** Vanilla's bolts light the clouds above them, with a short afterglow once they go dark. */
    private void gatherBolts(Vec3 camera, float height) {
        double now = seconds();
        for (int bolt = 0; bolt < MAX_BOLTS; bolt++) {
            if (!boltKnown[bolt]) {
                continue;
            }
            if (now - boltSeenAt[bolt] > BOLT_FORGET_SECONDS) {
                boltKnown[bolt] = false;
                continue;
            }
            float brightness = (float) Math.exp(-(now - boltLitAt[bolt]) / BOLT_AFTERGLOW_SECONDS);
            if (brightness < 0.01f) {
                continue;
            }
            sourceX[sources] = (float) (boltX[bolt] - camera.x);
            sourceY[sources] = height;
            sourceZ[sources] = (float) (boltZ[bolt] - camera.z);
            sourceRadius[sources] = BOLT_GLOW_RADIUS;
            sourceBrightness[sources] = brightness;
            sources++;
        }
    }

    /** 1 close by, a half at {@code reach}, falling off with the square of the distance beyond. */
    static float falloff(float distance, float reach) {
        float scaled = distance / reach;
        return 1.0f / (1.0f + scaled * scaled);
    }

    private static double seconds() {
        return System.nanoTime() * 1.0e-9;
    }

    /** QA: how bright the brightest flash in the clouds was this frame (after Hide Lightning Flashes). */
    public float shownCloudFlash() {
        return shownCloudFlash;
    }

    /** QA: how much this frame's flashes lit the air (0 with Hide Lightning Flashes on). */
    public float shownAirLight() {
        return shownAirLight;
    }

    /** QA: how many of vanilla's bolts are being followed. */
    public int boltsFollowed() {
        int followed = 0;
        for (boolean known : boltKnown) {
            followed += known ? 1 : 0;
        }
        return followed;
    }

    /** QA: how many of the schedule's flashes were going on this frame. */
    public int scheduledNow() {
        return scheduledNow;
    }
}
