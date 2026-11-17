package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.mixin.particles.BiomeManagerAccessor;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.feature.skyfog.StormAtmosphere;
import com.thenathe.litelights.render.feature.weather.Wind;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.WeatherPreview;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Everything the spawners need to know about the camera's surroundings this tick, worked out once
 * on the client thread: where the camera is, the dimension and climate, time of day, weather and
 * wind, and the settings in force.
 */
public final class Surroundings {
    public ClientLevel level;
    public RenderSettings settings;
    public final RandomSource random = RandomSource.create();
    public final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public double cameraX;
    public double cameraY;
    public double cameraZ;
    /** Horizontal direction the camera faces, as a unit vector (x, z). */
    public float forwardX;
    public float forwardZ;
    public int dimensionKind;
    /** Whether the dimension has the overworld's sky (rain, stars, the sun). */
    public boolean overworldSky;
    /** Modded dimensions' "effects" and "weather and events" switches; always on for vanilla ones. */
    public boolean effectsAllowed;
    public boolean weatherAllowed;
    public BiomeClimate climate = BiomeClimate.TEMPERATE;
    /** 1 at noon, 0 at night, from LiteLights' own sun (it follows the settings' time preview too). */
    public float daylight;
    public float rain;
    public float thunder;
    /** Sky-fog's storms at the camera, so the particles always agree with the storm fog. */
    public final StormAtmosphere storm = StormAtmosphere.current();
    public boolean cameraInWater;
    /** The camera has no sky light: in a cave or a closed room. */
    public boolean underground;
    public int skyLightAtCamera;
    public long seed;
    public long gameTime;
    public long clockTime;
    public final float[] wind = new float[2];
    /** How far the haze's pattern has drifted with the wind (HazeDrift), for where puffs gather (HazeClumps). */
    public double hazeDriftX;
    public double hazeDriftZ;
    /**
     * The Weather and Particle distances: spawn areas are multiplied by the reach, and spawn rates
     * by the fill (1 with Spread, the area's growth with Fill). See {@link ParticleReach}.
     */
    public float weatherReach = 1.0f;
    public float weatherFill = 1.0f;
    public float ambientReach = 1.0f;
    public float ambientFill = 1.0f;

    void update(ClientLevel currentLevel, Camera camera, RenderSettings currentSettings) {
        level = currentLevel;
        settings = currentSettings;
        cameraX = camera.position().x;
        cameraY = camera.position().y;
        cameraZ = camera.position().z;
        cursor.set(cameraX, cameraY, cameraZ);
        float yaw = (float) Math.toRadians(camera.yRot());
        forwardX = -(float) Math.sin(yaw);
        forwardZ = (float) Math.cos(yaw);
        overworldSky = currentLevel.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD;
        dimensionKind = currentLevel.dimension() == Level.OVERWORLD ? FrameFlags.DIMENSION_OVERWORLD
            : currentLevel.dimension() == Level.NETHER ? FrameFlags.DIMENSION_NETHER
            : currentLevel.dimension() == Level.END ? FrameFlags.DIMENSION_END
            : FrameFlags.DIMENSION_MODDED;
        effectsAllowed = DimensionGate.allows(Aspect.EFFECTS);
        weatherAllowed = DimensionGate.allows(Aspect.WEATHER);
        climate = BiomeClimate.of(currentLevel.getBiome(cursor));
        daylight = LiteLightsRenderer.get().frame().sunColor.w;
        // The settings screen's weather preview, if any, in place of the world's weather.
        rain = WeatherPreview.rain(currentLevel.getRainLevel(1.0f));
        thunder = WeatherPreview.thunder(currentLevel.getThunderLevel(1.0f));
        cameraInWater = currentLevel.getFluidState(cursor).is(FluidTags.WATER);
        skyLightAtCamera = currentLevel.getBrightness(LightLayer.SKY, cursor);
        underground = skyLightAtCamera == 0;
        seed = ((BiomeManagerAccessor) currentLevel.getBiomeManager()).litelights$biomeZoomSeed();
        gameTime = currentLevel.getGameTime();
        clockTime = currentLevel.getOverworldClockTime();
        // Rain brings some wind of its own; sandstorms and blizzards blow hardest.
        float rainWind = rain * (0.55f + 0.45f * thunder) * 0.45f;
        float windStorm = Math.max(rainWind, Math.max(storm.sandstorm(), storm.blizzard()));
        Wind.sample(seed, clockTime, gameTime, windStorm, wind);
        RenderSettings.Weather weatherSettings = currentSettings.weather();
        RenderSettings.Particles particleSettings = currentSettings.particles();
        weatherReach = weatherSettings.distance().radius;
        weatherFill = ParticleReach.countScale(weatherSettings.distance(), weatherSettings.distanceFill());
        ambientReach = particleSettings.distance().radius;
        ambientFill = ParticleReach.countScale(particleSettings.distance(), particleSettings.distanceFill());
    }

    /** Vanilla's packed light at a position (block and sky), for tinting a particle spawned there. */
    public int lightAt(double x, double y, double z) {
        return LightCoordsUtil.getLightCoords(level, cursor.set(x, y, z));
    }

    public static Camera camera() {
        return Minecraft.getInstance().gameRenderer.mainCamera();
    }
}
