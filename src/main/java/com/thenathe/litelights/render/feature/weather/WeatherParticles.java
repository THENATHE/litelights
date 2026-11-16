package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.config.PreviewWeather;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.feature.particles.BiomeClimate;
import com.thenathe.litelights.render.feature.particles.ColumnGrid;
import com.thenathe.litelights.render.feature.particles.ParticleBudget;
import com.thenathe.litelights.render.feature.particles.ParticleCategory;
import com.thenathe.litelights.render.feature.particles.ParticleKind;
import com.thenathe.litelights.render.feature.particles.SpawnQueue;
import com.thenathe.litelights.render.feature.particles.Surroundings;
import com.thenathe.litelights.render.frame.WeatherPreview;
import net.minecraft.util.RandomSource;

/**
 * Rain, snow, storms and blizzards, mist, sand wind, sandstorms and the storm atmosphere's wind
 * grit and dark rain, as particles. Spawns into the whole circle around the camera, whatever way it
 * faces, so a quick turn always finds the air as full as the view it left; a little upwind so the
 * wind carries particles through the view rather than out of it. The weather density multiplies
 * single particles strongly and mist and dust clouds only lightly. The Weather distance widens the
 * circle (and the height rain and snow fall from) by its reach; with Fill the rates grow with the
 * area so the weather stays as thick as at Near. Mist and dust clouds keep their Near area and
 * count: they are the air close by, which the storm fog carries into the distance. Each storm
 * type's Cloud particles setting can leave its mist or dust clouds out (rain, thunderstorms, snow
 * and blizzards have mist; sandstorms and haboobs dust clouds); its single particles stay.
 *
 * <p>Weather visuals: Vanilla leaves rain and snow to vanilla. Enhanced keeps vanilla's rain and
 * snow sheets and adds a lighter layer of LiteLights drops and flakes (wind-driven, with splashes
 * and ripples), thicker in thunder. Custom replaces vanilla's sheets with LiteLights' own rain and
 * snow; with the particle amount at None, vanilla's sheets stay so the rain never disappears.
 */
public final class WeatherParticles {
    /** Spawn area radius around the camera at Near, in blocks. */
    private static final float RADIUS = 22.0f;
    /** How many ticks of wind to shift the spawn area upwind. */
    private static final float UPWIND_TICKS = 20.0f;
    // Spawn rates for the whole circle, as multiples of the old rates, which went mostly into the
    // view (rain 80 %, snow 55 %, sandstorm grains 70 % and dust clouds 60 % into a cone a third of
    // the circle wide). Rain and clouds follow the cone's share of the circle. Snow and sandstorm
    // grains were held well under their rates by the old cap, so theirs were measured until the
    // view at Light showed as many as before (weather-shots QA; DECISIONS, fire). Grains also live
    // their whole life now, crossing the area at the storm's speed, which is why theirs is under 1.
    private static final float RAIN_COVERAGE = 2.7f;
    private static final float SNOW_COVERAGE = 0.9f;
    private static final float GRAIN_COVERAGE = 0.4f;
    private static final float CLOUD_COVERAGE = 2.2f;
    /** Share of the weather budget rain and snow leave free for mist and sand. */
    private static final float RESERVE_SHARE = 0.18f;

    private static final int RAIN_COLOR = 0x98C4D2EC;
    /** Rain over pale gardens falls dark (storm atmosphere). */
    private static final int DARK_RAIN_COLOR = 0xB83E3A46;
    private static final int SNOW_COLOR = 0xF0FFFFFF;
    // Rain mist is many wide, faint puffs that overlap into one low haze rather than a few
    // stronger ones, which read as separate blobs.
    private static final int RAIN_MIST_COLOR = 0x22D4DCE4;
    private static final int SNOW_MIST_COLOR = 0x2EF4F8FF;
    private static final int DESERT_SAND = 0xFFDAC690;
    private static final int RED_SAND = 0xFFC46C36;
    private static final int SAVANNA_DUST = 0xFFC2AC7A;
    private static final int GRIT = 0xC09A8C7C;

    private static volatile boolean replacingVanilla;

    private WeatherParticles() {
    }

    /** Read on the render thread while the frame is extracted: whether vanilla's rain and snow sheets are left out. */
    public static boolean replacesVanillaWeather() {
        return replacingVanilla;
    }

    /** Set by the particle system every tick, from the settings and whether particles are running. */
    public static void setReplacingVanilla(boolean value) {
        replacingVanilla = value;
    }

    /** Whether LiteLights draws rain and snow itself with these settings (and vanilla's sheets go). */
    public static boolean wantsToReplaceVanilla(RenderSettings settings) {
        // A previewed weather is always LiteLights' own: vanilla's sheets can only show the real weather.
        return (settings.weather().precipitation() == RenderSettings.PrecipitationLook.LITELIGHTS || WeatherPreview.previewing())
            && settings.particles().density() != RenderSettings.ParticleDensity.OFF
            && !WeatherCompat.precipitationHandledElsewhere();
    }

    public static void spawn(Surroundings surroundings, SpawnQueue queue, ColumnGrid grid) {
        if (!surroundings.overworldSky || !surroundings.weatherAllowed || surroundings.cameraInWater) {
            return;
        }
        RenderSettings.Weather weather = surroundings.settings.weather();
        // Single particles grow with Fill; mist and dust clouds (the air close by) never do.
        float airScale = queue.rateScale();
        float scale = airScale * surroundings.weatherFill;
        RenderSettings.WeatherDensity density = weather.density();
        // Rain and snow (and their mist) follow the density only when LiteLights draws them in
        // place of vanilla's (Enhanced, or a weather the settings screen previews); blowing sand is
        // LiteLights' own in every mode.
        boolean ownRain = ownPrecipitation(weather) && !WeatherCompat.precipitationHandledElsewhere();
        float rainParticles = ownRain ? density.particles : 1.0f;
        float rainThickness = ownRain ? density.thickness : 1.0f;
        precipitation(surroundings, queue, grid, weather, scale * rainParticles);
        RainDetails.spawn(surroundings, queue, grid, scale);
        if (weather.rainMist()) {
            mist(surroundings, queue, grid, airScale * rainThickness);
        }
        if (weather.sandstorms() && !WeatherCompat.sandstormsHandledElsewhere()) {
            sandstorm(surroundings, queue, grid, scale * density.particles, airScale * density.thickness);
        }
        if (!WeatherCompat.windHandledElsewhere()) {
            if (weather.driftingSand()) {
                driftingSand(surroundings, queue, grid, scale * density.particles, airScale * density.thickness);
            }
            windGrit(surroundings, queue, grid, scale * density.particles);
        }
    }

    private static void precipitation(Surroundings s, SpawnQueue queue, ColumnGrid grid, RenderSettings.Weather weather, float scale) {
        boolean custom = ownPrecipitation(weather);
        if (!custom && weather.precipitation() == RenderSettings.PrecipitationLook.VANILLA || WeatherCompat.precipitationHandledElsewhere()
            || s.rain < 0.01f) {
            return;
        }
        // Enhanced adds a lighter layer on top of vanilla's sheets, filling out in thunder.
        float share = custom ? 1.0f : 0.25f + 0.35f * s.thunder;
        float rainRate = 56.0f * RAIN_COVERAGE * s.rain * (1.0f + 0.6f * s.thunder) * share * scale;
        float snowRate = 15.0f * SNOW_COVERAGE * s.rain * (1.0f + 3.0f * s.storm.blizzard()) * share * scale;
        RandomSource random = s.random;
        int attempts = ParticleBudget.countFromRate(Math.max(rainRate, snowRate), random.nextFloat());
        float snowChance = snowRate / Math.max(rainRate, 0.001f);
        int reserve = (int) (queue.categoryCap(ParticleCategory.WEATHER) * RESERVE_SHARE);
        float radius = RADIUS * s.weatherReach;
        // Further out, the view reaches higher: drops and flakes start higher up to fill it.
        float rainHeight = 15.0f * s.weatherReach;
        float snowHeight = 14.0f * s.weatherReach;
        for (int i = 0; i < attempts && queue.roomInCap(ParticleCategory.WEATHER) > reserve; i++) {
            float angle = random.nextFloat() * (float) (Math.PI * 2.0);
            // Not right at the camera, where a drop would fill the view.
            float distance = 1.5f + (float) Math.sqrt(random.nextFloat()) * (radius - 1.5f);
            double x = s.cameraX - s.wind[0] * UPWIND_TICKS + Math.cos(angle) * distance;
            double z = s.cameraZ - s.wind[1] * UPWIND_TICKS + Math.sin(angle) * distance;
            byte fall = previewedFall(grid.precipitationAt(x, z));
            if (fall == ColumnGrid.PRECIPITATION_NONE) {
                continue;
            }
            double ground = grid.groundHeight(x, z);
            boolean snow = fall == ColumnGrid.PRECIPITATION_SNOW;
            double y = s.cameraY + (snow ? 1.0 + random.nextFloat() * snowHeight : 5.0 + random.nextFloat() * rainHeight);
            if (ground == Double.NEGATIVE_INFINITY || ground > y) {
                continue; // under a roof, or underground
            }
            // Snow lingers far longer than rain, so it needs fewer new flakes: most attempts over snow are skipped.
            if (snow && random.nextFloat() > snowChance) {
                continue;
            }
            int light = grid.lightAt(x, z);
            if (snow) {
                float fallSpeed = -(0.05f + random.nextFloat() * 0.05f) * (1.0f + 0.8f * s.storm.blizzard());
                queue.add(ParticleKind.SNOW, x, y, z, s.wind[0], fallSpeed, s.wind[1], 0.07f + random.nextFloat() * 0.05f,
                    150 + random.nextInt(80), SNOW_COLOR, light, random.nextInt());
            } else {
                boolean dark = s.storm.atmosphere() && grid.climateAt(x, z) == BiomeClimate.PALE_GARDEN;
                float fallSpeed = -(0.7f + random.nextFloat() * 0.3f) * (1.0f + 0.2f * s.thunder);
                queue.add(ParticleKind.RAIN, x, y, z, s.wind[0] * 1.5f, fallSpeed, s.wind[1] * 1.5f, 0.026f,
                    60, dark ? DARK_RAIN_COLOR : RAIN_COLOR, light, random.nextInt());
            }
        }
    }

    private static void mist(Surroundings s, SpawnQueue queue, ColumnGrid grid, float scale) {
        if (s.rain < 0.2f) {
            return;
        }
        RenderSettings.Storms storms = s.settings.weather().storms();
        float rainKept = storms.rainClouds(s.thunder);
        float snowKept = storms.snowClouds(s.thunder);
        if (rainKept <= 0.0f && snowKept <= 0.0f) {
            return;
        }
        // A blizzard closes in: much more mist, and nearer.
        float blizzard = s.storm.blizzard();
        float rate = 1.6f * s.rain * (1.0f + 3.0f * blizzard) * scale;
        RandomSource random = s.random;
        int count = ParticleBudget.countFromRate(rate, random.nextFloat());
        float nearest = 7.0f - 3.0f * blizzard;
        float farthest = nearest + 24.0f - 6.0f * blizzard;
        for (int i = 0; i < count && queue.room(ParticleCategory.WEATHER) > 0; i++) {
            float angle = random.nextFloat() * (float) (Math.PI * 2.0);
            float distance = nearest + random.nextFloat() * (farthest - nearest);
            double x = s.cameraX + Math.cos(angle) * distance;
            double z = s.cameraZ + Math.sin(angle) * distance;
            byte fall = previewedFall(grid.precipitationAt(x, z));
            double ground = grid.groundHeight(x, z);
            if (fall == ColumnGrid.PRECIPITATION_NONE || ground == Double.NEGATIVE_INFINITY
                || Math.abs(ground - s.cameraY) > 24.0) {
                continue;
            }
            boolean snowMist = fall == ColumnGrid.PRECIPITATION_SNOW;
            if (!keeps(snowMist ? snowKept : rainKept, random)) {
                continue;
            }
            double y = ground + 0.6 + random.nextFloat() * (1.4f + 5.0f * blizzard);
            int color = snowMist ? SNOW_MIST_COLOR : RAIN_MIST_COLOR;
            // A blizzard's mist is thicker: it is what closes the view in.
            color = ((int) ((color >>> 24) * (1.0f + 1.2f * blizzard)) << 24) | (color & 0x00FFFFFF);
            queue.add(ParticleKind.MIST, x, y, z, s.wind[0], 0.0f, s.wind[1], 5.0f + random.nextFloat() * 3.0f,
                180 + random.nextInt(80), color, grid.lightAt(x, z), random.nextInt());
        }
    }

    private static void driftingSand(Surroundings s, SpawnQueue queue, ColumnGrid grid, float scale, float thicknessScale) {
        RandomSource random = s.random;
        int count = ParticleBudget.countFromRate(14.0f * scale, random.nextFloat());
        for (int i = 0; i < count && queue.room(ParticleCategory.WEATHER) > 0; i++) {
            double[] spot = groundSpot(s, grid, 3.0f, RADIUS * s.weatherReach);
            if (spot == null) {
                continue;
            }
            BiomeClimate climate = grid.climateAt(spot[0], spot[2]);
            if (!climate.isSandy()) {
                continue;
            }
            float height = random.nextFloat();
            double y = spot[1] + 0.05 + height * height * 2.4;
            queue.add(ParticleKind.SAND, spot[0], y, spot[2], s.wind[0] * 2.0f, 0.0f, s.wind[1] * 2.0f, 0.11f,
                50 + random.nextInt(40), grainColor(climate), grid.lightAt(spot[0], spot[2]), random.nextInt());
        }
        // Low, thin drifts of dust skim the ground.
        int drifts = ParticleBudget.countFromRate(0.8f * thicknessScale, random.nextFloat());
        for (int i = 0; i < drifts && queue.room(ParticleCategory.WEATHER) > 0; i++) {
            double[] spot = groundSpot(s, grid, 6.0f, RADIUS);
            if (spot != null && grid.climateAt(spot[0], spot[2]).isSandy()) {
                BiomeClimate climate = grid.climateAt(spot[0], spot[2]);
                double y = spot[1] + 0.2 + random.nextFloat() * 0.8;
                int color = (blownSandColor(climate) & 0x00FFFFFF) | 0x80000000;
                queue.add(ParticleKind.SAND_CLOUD, spot[0], y, spot[2], s.wind[0] * 2.0f, 0.0f, s.wind[1] * 2.0f,
                    0.8f + random.nextFloat() * 0.6f, 60 + random.nextInt(40), color, grid.lightAt(spot[0], spot[2]), random.nextInt());
            }
        }
    }

    /**
     * Sand and dust clouds as thick as sky-fog's sandstorm at the camera, so the two always agree.
     * Grains race past at over twice the wind's speed and fill the air from the ground to well
     * above the camera, so a storm surrounds the player rather than skimming the dunes.
     */
    private static void sandstorm(Surroundings s, SpawnQueue queue, ColumnGrid grid, float scale, float thicknessScale) {
        float strength = s.storm.sandstorm();
        if (strength < 0.03f) {
            return;
        }
        boolean stormAtmosphere = s.storm.atmosphere();
        RandomSource random = s.random;
        // Clouds of dust roll through, nearer and thicker the stronger the storm. They go first:
        // grains would fill the budget on their own. Cloud particles Off leaves them out.
        float cloudsKept = s.settings.weather().storms().sandClouds(s.thunder);
        int clouds = cloudsKept <= 0.0f ? 0
            : ParticleBudget.countFromRate(4.0f * CLOUD_COVERAGE * strength * thicknessScale * cloudsKept, random.nextFloat());
        for (int i = 0; i < clouds && queue.room(ParticleCategory.WEATHER) > 0; i++) {
            double[] spot = groundSpot(s, grid, 2.0f, RADIUS + 6.0f);
            if (spot == null) {
                continue;
            }
            BiomeClimate climate = grid.climateAt(spot[0], spot[2]);
            if (!sandstormLand(climate, stormAtmosphere)) {
                continue;
            }
            double y = spot[1] + 0.6 + random.nextFloat() * 7.0;
            int color = (sandColor(climate) & 0x00FFFFFF) | 0x90000000;
            queue.add(ParticleKind.SAND_CLOUD, spot[0], y, spot[2], s.wind[0], 0.0f, s.wind[1], 2.4f + random.nextFloat() * 2.0f,
                100 + random.nextInt(60), color, grid.lightAt(spot[0], spot[2]), random.nextInt());
        }
        int reserve = (int) (queue.categoryCap(ParticleCategory.WEATHER) * RESERVE_SHARE);
        int grains = ParticleBudget.countFromRate(90.0f * GRAIN_COVERAGE * strength * scale, random.nextFloat());
        float boost = ParticleKind.SANDSTORM_GRAIN.windBoost;
        float radius = RADIUS * s.weatherReach;
        for (int i = 0; i < grains && queue.roomInCap(ParticleCategory.WEATHER) > reserve; i++) {
            // Further upwind than the rest: the grains cross the spawn area in about two seconds.
            double[] spot = spotAt(s, grid, random.nextFloat() * (float) (Math.PI * 2.0),
                1.0f + (float) Math.sqrt(random.nextFloat()) * (radius - 1.0f), UPWIND_TICKS * boost * 0.6f);
            if (spot == null) {
                continue;
            }
            BiomeClimate climate = grid.climateAt(spot[0], spot[2]);
            if (!sandstormLand(climate, stormAtmosphere)) {
                continue;
            }
            // From the ground to twelve blocks over the camera (more further out), thinning a little with height.
            double top = Math.max(spot[1] + 9.0, s.cameraY + 12.0 * s.weatherReach);
            double y = spot[1] + 0.1 + Math.pow(random.nextFloat(), 1.15) * (top - spot[1]);
            queue.add(ParticleKind.SANDSTORM_GRAIN, spot[0], y, spot[2], s.wind[0] * boost, -0.01f, s.wind[1] * boost, 0.035f,
                40 + random.nextInt(40), blownSandColor(climate), grid.lightAt(spot[0], spot[2]), random.nextInt());
        }
    }

    /**
     * Where a sandstorm's grains and clouds may land: deserts, and with Storm atmosphere every sandy
     * biome. The same split as sky-fog's sandstorm, which sets how thick it is.
     */
    private static boolean sandstormLand(BiomeClimate climate, boolean stormAtmosphere) {
        return WeatherPreview.fall() == PreviewWeather.Fall.SAND
            || climate == BiomeClimate.DESERT
            || stormAtmosphere && (climate == BiomeClimate.BADLANDS || climate == BiomeClimate.SAVANNA);
    }

    /**
     * The storm atmosphere's soft wind: dust and grit over hills, mountains, deserts and badlands,
     * in any weather, as strong as sky-fog's wind dust at the camera.
     */
    private static void windGrit(Surroundings s, SpawnQueue queue, ColumnGrid grid, float scale) {
        float dust = s.storm.windDust();
        if (dust < 0.03f) {
            return;
        }
        RandomSource random = s.random;
        int count = ParticleBudget.countFromRate(5.0f * dust * (1.0f + s.rain * (0.55f + 0.45f * s.thunder)) * scale, random.nextFloat());
        for (int i = 0; i < count && queue.room(ParticleCategory.WEATHER) > 0; i++) {
            double[] spot = groundSpot(s, grid, 3.0f, RADIUS * s.weatherReach);
            if (spot == null) {
                continue;
            }
            BiomeClimate climate = grid.climateAt(spot[0], spot[2]);
            if (climate != BiomeClimate.WINDY && !climate.isSandy()) {
                continue;
            }
            double y = spot[1] + 0.2 + random.nextFloat() * 4.0;
            int color = climate.isSandy() ? (sandColor(climate) & 0x00FFFFFF) | 0xC0000000 : GRIT;
            queue.add(ParticleKind.WIND_GRIT, spot[0], y, spot[2], s.wind[0] * 3.0f, 0.01f, s.wind[1] * 3.0f, 0.07f,
                40 + random.nextInt(30), color, grid.lightAt(spot[0], spot[2]), random.nextInt());
        }
    }

    /**
     * Single grains skimming the ground: a shade darker than the sand they blow over, so they show
     * against it; the thin drifts above them are paler, catching the light.
     */
    private static int grainColor(BiomeClimate climate) {
        return climate == BiomeClimate.BADLANDS ? 0xFF8E4422 : 0xFFAE8E58;
    }

    /** Thin drifts of lifted sand catch the light: a shade paler than the ground. */
    private static int blownSandColor(BiomeClimate climate) {
        return climate == BiomeClimate.BADLANDS ? 0xFFE89A62 : 0xFFF2E4BC;
    }

    private static int sandColor(BiomeClimate climate) {
        return switch (climate) {
            case BADLANDS -> RED_SAND;
            case SAVANNA -> SAVANNA_DUST;
            default -> DESERT_SAND;
        };
    }

    /**
     * Whether one mist puff is kept, given the share of them the storm keeps. Draws a random
     * number only for a share between none and all (thunder rolling in between two settings), so
     * with every storm's clouds on the spawns are exactly what they were before the setting.
     */
    static boolean keeps(float share, RandomSource random) {
        if (share >= 1.0f) {
            return true;
        }
        return share > 0.0f && random.nextFloat() < share;
    }

    /** LiteLights draws the rain and snow itself: the Enhanced look, or any weather the settings screen previews. */
    private static boolean ownPrecipitation(RenderSettings.Weather weather) {
        return weather.precipitation() == RenderSettings.PrecipitationLook.LITELIGHTS || WeatherPreview.previewing();
    }

    /**
     * What falls on a column: the land's own rain or snow, or while the settings screen previews a
     * weather, that weather's everywhere (a sandstorm is dry). Columns that aren't known yet stay
     * unknown; their missing ground height skips them anyway.
     */
    static byte previewedFall(byte landFall) {
        PreviewWeather.Fall fall = WeatherPreview.previewing() ? WeatherPreview.fall() : null;
        if (fall == null) {
            return landFall;
        }
        return switch (fall) {
            case RAIN -> ColumnGrid.PRECIPITATION_RAIN;
            case SNOW -> ColumnGrid.PRECIPITATION_SNOW;
            case SAND -> ColumnGrid.PRECIPITATION_NONE;
        };
    }

    private static final double[] SPOT = new double[3];

    /**
     * A random open spot on the ground around the camera, upwind, or null where the ground isn't
     * known or is far above or below the camera. Reuses one array: read it before asking again.
     */
    private static double[] groundSpot(Surroundings s, ColumnGrid grid, float minDistance, float maxDistance) {
        RandomSource random = s.random;
        float angle = random.nextFloat() * (float) (Math.PI * 2.0);
        return spotAt(s, grid, angle, minDistance + (float) Math.sqrt(random.nextFloat()) * (maxDistance - minDistance), UPWIND_TICKS);
    }

    private static double[] spotAt(Surroundings s, ColumnGrid grid, float angle, float distance, float upwindTicks) {
        double x = s.cameraX - s.wind[0] * upwindTicks + Math.cos(angle) * distance;
        double z = s.cameraZ - s.wind[1] * upwindTicks + Math.sin(angle) * distance;
        double ground = grid.groundHeight(x, z);
        if (ground == Double.NEGATIVE_INFINITY || Math.abs(ground - s.cameraY) > 20.0 * s.weatherReach) {
            return null;
        }
        SPOT[0] = x;
        SPOT[1] = ground;
        SPOT[2] = z;
        return SPOT;
    }
}
