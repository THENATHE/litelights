package com.thenathe.litelights.render;

/**
 * Every setting the renderer reads, as one immutable snapshot. The defaults are the ones in
 * docs/PLAN.md section 5.
 *
 * <p>The renderer never reads the config system directly: the settings stream publishes a new
 * snapshot through {@link RenderSettingsAccess#publish} whenever the user changes something, and
 * render code calls {@link RenderSettingsAccess#current()} once per frame. Percent sliders are
 * stored as fractions (100% is {@code 1.0f}).
 */
public record RenderSettings(
    General general,
    PixelLock pixelLock,
    Sky sky,
    Fog fog,
    Lighting lighting,
    ColorGrading colorGrading,
    Shadows shadows,
    Water water,
    Vegetation vegetation,
    Particles particles,
    Weather weather,
    Outlines outlines,
    Dimensions dimensions,
    long previewDayTime
) {
    /** No time-of-day preview: draw with the world's own time. */
    public static final long NO_PREVIEW = -1L;

    public static final RenderSettings DEFAULTS = new RenderSettings(
        General.DEFAULTS,
        PixelLock.DEFAULTS,
        Sky.DEFAULTS,
        Fog.DEFAULTS,
        Lighting.DEFAULTS,
        ColorGrading.DEFAULTS,
        Shadows.DEFAULTS,
        Water.DEFAULTS,
        Vegetation.DEFAULTS,
        Particles.DEFAULTS,
        Weather.DEFAULTS,
        Outlines.DEFAULTS,
        Dimensions.DEFAULTS,
        NO_PREVIEW
    );

    public boolean hasPreview() {
        return previewDayTime != NO_PREVIEW;
    }

    public enum AntiAliasing { OFF, FXAA, TAA }

    public record General(AntiAliasing antiAliasing, boolean moddedBiomeFogColors) {
        public static final General DEFAULTS = new General(AntiAliasing.OFF, true);
    }

    /**
     * Pixel lock snaps surface effects to the 16-texels-per-block grid of the surface they land on.
     * An effect is locked when {@code enabled} and its own switch are both on.
     */
    public record PixelLock(
        boolean enabled,
        boolean shadows,
        boolean caustics,
        boolean waterSurface,
        boolean glint,
        boolean sunbeams
    ) {
        public static final PixelLock DEFAULTS = new PixelLock(true, true, true, true, true, true);

        public boolean locksShadows() {
            return enabled && shadows;
        }

        public boolean locksCaustics() {
            return enabled && caustics;
        }

        public boolean locksWaterSurface() {
            return enabled && waterSurface;
        }

        public boolean locksGlint() {
            return enabled && glint;
        }

        public boolean locksSunbeams() {
            return enabled && sunbeams;
        }
    }

    public enum SkyPalette { VANILLA_COLOURS, COMPUTED }

    public enum CloudStyle { OFF, FLAT, FANCY }

    public record Sky(SkyPalette palette, boolean fallingStars, CloudStyle clouds, int cloudLayers) {
        public static final Sky DEFAULTS = new Sky(SkyPalette.VANILLA_COLOURS, true, CloudStyle.FANCY, 1);
    }

    public enum FogLook { VANILLA, HAZE, OFF }

    /**
     * farTerrain: LiteLights' air (and light) on Voxy's and Distant Horizons' far terrain.
     * biomeHazeTint: how much of each biome's haze colour shows (0 the plain temperate haze, 1 the
     * biome's own). sunGlow: the warm glow toward a low sun and the haze's twilight warming (1 as built).
     */
    public record Fog(FogLook look, float density, boolean mistBanks, boolean farTerrain, float biomeHazeTint, float sunGlow) {
        public static final Fog DEFAULTS = new Fog(FogLook.HAZE, 1.0f, true, true);

        public Fog(FogLook look, float density, boolean mistBanks, boolean farTerrain) {
            this(look, density, mistBanks, farTerrain, 1.0f, 1.0f);
        }
    }

    public record Lighting(
        boolean halo,
        float haloStrength,
        boolean glowHeat,
        boolean sunbeams,
        float sunbeamStrength,
        boolean underwaterSunbeams,
        float underwaterSunbeamStrength,
        SunbeamQuality sunbeamQuality,
        boolean glintWater,
        WaterSparkle waterSparkle,
        boolean glintIceSnow,
        boolean glintMetalGlass,
        boolean glintSandStone,
        boolean submergedGlint,
        float glintSoftness,
        boolean nightColorTone
    ) {
        public static final Lighting DEFAULTS = new Lighting(
            true, 1.0f, true, true, 2.0f, true, 1.5f, SunbeamQuality.MEDIUM, true, WaterSparkle.GENTLE, true, true, false, true,
            0.15f, true);

        /** Whether anything glints above water. */
        public boolean landGlint() {
            return glintWater || glintIceSnow || glintMetalGlass || glintSandStone;
        }
    }

    public enum WaterSparkle { STILL, GENTLE, LIVELY }

    /**
     * How finely sunbeams are drawn: the divisor of the screen size their texture uses, and how many
     * times the usual number of slices they take.
     */
    public enum SunbeamQuality {
        LOW(4, 1),
        MEDIUM(2, 1),
        HIGH(1, 1),
        ULTRA(1, 2);

        private final int divisor;
        private final int sliceFactor;

        SunbeamQuality(int divisor, int sliceFactor) {
            this.divisor = divisor;
            this.sliceFactor = sliceFactor;
        }

        public int divisor() {
            return divisor;
        }

        public int sliceFactor() {
            return sliceFactor;
        }
    }

    public enum GradeSchedule { OFF, ALL_DAY, BY_TIME_OF_DAY }

    /**
     * One colour grade, in the settings' units: exposure in stops, hues in degrees, channel strengths
     * around 1, everything else a fraction around 0. Neutral values leave the image unchanged.
     */
    public record Grade(
        float exposure,
        float contrast,
        float shadows,
        float midtones,
        float highlights,
        float saturation,
        float vibrance,
        float hueShift,
        float temperature,
        float tint,
        float redStrength,
        float greenStrength,
        float blueStrength,
        float redHue,
        float greenHue,
        float blueHue
    ) {
        public static final Grade NEUTRAL = new Grade(
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f);
    }

    /** The curves the tonemap offers; VANILLA_FRIENDLY only rolls off what the grade pushes past white. */
    public enum ToneCurve { VANILLA_FRIENDLY, ACES, AGX, HABLE }

    public record ColorGrading(GradeSchedule schedule, Grade allDay, Grade dawn, Grade midday, Grade dusk, Grade night,
                               ToneCurve toneCurve) {
        public static final ColorGrading DEFAULTS = new ColorGrading(
            GradeSchedule.OFF, Grade.NEUTRAL, Grade.NEUTRAL, Grade.NEUTRAL, Grade.NEUTRAL, Grade.NEUTRAL);

        public ColorGrading(GradeSchedule schedule, Grade allDay, Grade dawn, Grade midday, Grade dusk, Grade night) {
            this(schedule, allDay, dawn, midday, dusk, night, ToneCurve.VANILLA_FRIENDLY);
        }
    }

    public enum LightModel { SHADOWS_ONLY, RELIT }

    /**
     * Sun shadow map sizes. Sharpest is the default; each step up has about twice the texels of the
     * one below, and Ultra four times Sharpest's. The sun map is two textures of 4-byte depth (the
     * one shaders read and the one a redraw goes into); the mob map is one, at 2048 from Sharpest up.
     */
    public enum ShadowDetail {
        FASTEST(1024),
        FAST(2048),
        SHARP(3072),
        SHARPEST(4096),
        ULTRA(8192);

        private static final long BYTES_PER_TEXEL = 4L;

        private final int mapSize;

        ShadowDetail(int mapSize) {
            this.mapSize = mapSize;
        }

        public int mapSize() {
            return mapSize;
        }

        public int mobMapSize() {
            return compareTo(SHARPEST) >= 0 ? 2048 : 1024;
        }

        /** Video memory for the two sun map textures and the mob map, in bytes. */
        public long videoMemoryBytes() {
            long sun = 2L * mapSize * mapSize * BYTES_PER_TEXEL;
            long mob = (long) mobMapSize() * mobMapSize() * BYTES_PER_TEXEL;
            return sun + mob;
        }

        /** The next smaller tier, or null below Fastest. */
        public ShadowDetail lower() {
            return ordinal() == 0 ? null : values()[ordinal() - 1];
        }

        /**
         * The detail to use when the device can't make textures larger than {@code largestMapSize}
         * texels a side: the wanted tier, or the largest smaller one that fits (Fastest at least).
         */
        public ShadowDetail fitting(int largestMapSize) {
            ShadowDetail detail = this;
            while (detail.mapSize > largestMapSize && detail.lower() != null) {
                detail = detail.lower();
            }
            return detail;
        }
    }

    /** How far a surface's shadow lookup moves off the surface, in shadow map texels. */
    public enum ShadowOffset {
        CONTACT(0.5f),
        DETAILED(1.0f),
        BALANCED(1.5f),
        CLEAN(2.0f);

        private final float texels;

        ShadowOffset(float texels) {
            this.texels = texels;
        }

        public float texels() {
            return texels;
        }
    }

    public record Shadows(
        boolean enabled,
        LightModel lightModel,
        float darkness,
        float sunBrightness,
        float sunlightColour,
        int reach,
        ShadowDetail detail,
        boolean entityShadows,
        boolean glide,
        boolean inWater,
        FarShadows farShadows,
        ShadowOffset offset
    ) {
        public static final Shadows DEFAULTS = new Shadows(
            true, LightModel.RELIT, 1.0f, 0.0f, 1.0f, 256, ShadowDetail.SHARPEST, true, true, true, FarShadows.SCREEN_SPACE,
            ShadowOffset.BALANCED);
    }

    /** Shadows past the cached sun map: none, from the depth buffer, or also from the land's heightfield. */
    public enum FarShadows { OFF, SCREEN_SPACE, ADVANCED }

    public enum WaterPalette { VANILLA_COLOURS, ENHANCED, NATURAL }

    public enum JumpSplash { OFF, BASIC, ADVANCED }

    /** Foam on flowing water stays put, or travels downstream with the current. */
    public enum FoamMotion { STATIONARY, DRIFTING }

    public enum MirrorDetail { COARSE, BALANCED, FINE }

    public record Water(
        WaterPalette palette,
        boolean mirrors,
        boolean iceMirrors,
        MirrorDetail mirrorDetail,
        float mirrorStrength,
        boolean biomeAndDepthColor,
        boolean edgeFoam,
        FoamMotion foamMotion,
        boolean lavaCrust,
        boolean floatingLeaves,
        boolean caustics,
        boolean underwaterDepthBlur,
        boolean largeWaterTextures,
        int largeWaterScale,
        int largeLavaScale,
        JumpSplash jumpSplash,
        boolean farTerrain,
        boolean farCaustics,
        boolean farDepthColour,
        boolean farEdgeFoam,
        boolean oceanWaves,
        boolean wakes,
        float wakeStrength,
        boolean wakeFoam
    ) {
        public static final Water DEFAULTS = new Water(
            WaterPalette.VANILLA_COLOURS, true, true, MirrorDetail.BALANCED, 1.0f, true, true, FoamMotion.STATIONARY, true, true, true, false,
            true, 4, 4, JumpSplash.ADVANCED, true, false, false, false, false, true, 1.0f, true);
    }

    public record Vegetation(boolean swayingGrass, boolean swayingPlants, boolean swayingLeaves) {
        public static final Vegetation DEFAULTS = new Vegetation(false, false, false);
    }

    public enum ParticleDensity { FULL, SPARSE, OFF }

    public enum SnowSandSprites { VANILLA, DETAILED }

    public record Particles(
        ParticleDensity density,
        SnowSandSprites snowSandSprites,
        boolean fireflySwarms,
        boolean dustSpecks,
        boolean pollen,
        boolean spores,
        boolean enhancedFire,
        boolean emberVeins,
        boolean enhancedWater,
        SpawnDistance distance,
        DistanceFill distanceFill
    ) {
        public static final Particles DEFAULTS = new Particles(
            ParticleDensity.FULL, SnowSandSprites.VANILLA, true, true, true, true, true, true, true,
            SpawnDistance.NEAR, DistanceFill.SPREAD);
    }

    /**
     * How far around the camera particles spawn and are drawn, as a multiple of the classic (Near)
     * distance. The weather and the ambient particles each have their own.
     */
    public enum SpawnDistance {
        NEAR(1.0f),
        MEDIUM(1.4f),
        FAR(1.8f),
        VERY_FAR(2.4f);

        public final float radius;

        SpawnDistance(float radius) {
            this.radius = radius;
        }
    }

    /**
     * Beyond Near: Spread keeps the number of particles and spreads them over the larger area;
     * Fill keeps Near's density, so the count (and the cap) grows with the area.
     */
    public enum DistanceFill { SPREAD, FILL }

    public enum PrecipitationLook { VANILLA, VANILLA_PLUS, LITELIGHTS }

    /**
     * How thick LiteLights' own weather is. Single particles (drops, flakes, grains, grit) are
     * multiplied by {@code particles}; what makes the air thick (snow and sand fog, mist and dust
     * clouds) only by {@code thickness}, so Dense is a heavy storm rather than a white-out.
     */
    public enum WeatherDensity {
        LIGHT(1.0f, 1.0f),
        MEDIUM(2.0f, 1.08f),
        HEAVY(3.0f, 1.15f),
        DENSE(4.5f, 1.22f);

        public final float particles;
        public final float thickness;

        WeatherDensity(float particles, float thickness) {
            this.particles = particles;
            this.thickness = thickness;
        }
    }

    public record Weather(
        PrecipitationLook precipitation,
        WeatherDensity density,
        boolean rainMist,
        boolean driftingSand,
        boolean sandstorms,
        boolean stormAtmosphere,
        boolean polarLights,
        boolean meteorNights,
        boolean farFireworks,
        SpawnDistance distance,
        DistanceFill distanceFill,
        boolean rainDrips,
        boolean wallStreaks,
        Glass glass,
        Storms storms
    ) {
        public static final Weather DEFAULTS = new Weather(PrecipitationLook.LITELIGHTS, WeatherDensity.MEDIUM, true, true, true, true, true, true,
            true, SpawnDistance.NEAR, DistanceFill.SPREAD, true, true, Glass.DEFAULTS, Storms.DEFAULTS);
    }

    /**
     * How thick a storm's fog gets, as a multiple of the fog it had before these settings: Normal
     * is exactly 1, so the default draws what it always did.
     */
    public enum StormFog {
        LIGHT(0.5f),
        NORMAL(1.0f),
        DENSE(2.0f),
        VERY_DENSE(3.5f);

        public final float thickness;

        StormFog(float thickness) {
            this.thickness = thickness;
        }
    }

    /** One storm type's look: its fog, and whether it brings its large cloud particles. */
    public record Storm(StormFog fog, boolean clouds) {
        public static final Storm DEFAULT = new Storm(StormFog.NORMAL, true);

        float cloudShare() {
            return clouds ? 1.0f : 0.0f;
        }
    }

    /**
     * Each storm type's look. The storm model knows rain, snow and sand, and thunder over each of
     * them; a storm partway between (thunder rolling in) blends the two types' values by the
     * thunder level. When both types agree the value is exact, so the defaults change nothing.
     */
    public record Storms(Storm rain, Storm thunderstorm, Storm snow, Storm blizzard, Storm sandstorm, Storm haboob) {
        public static final Storms DEFAULTS = new Storms(Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT, Storm.DEFAULT,
            Storm.DEFAULT);

        public float rainFog(float thunder) {
            return LiteMath.lerp(rain.fog.thickness, thunderstorm.fog.thickness, thunder);
        }

        public float snowFog(float thunder) {
            return LiteMath.lerp(snow.fog.thickness, blizzard.fog.thickness, thunder);
        }

        public float sandFog(float thunder) {
            return LiteMath.lerp(sandstorm.fog.thickness, haboob.fog.thickness, thunder);
        }

        /** The share of rain mist puffs kept: 1 with cloud particles on, 0 off. */
        public float rainClouds(float thunder) {
            return LiteMath.lerp(rain.cloudShare(), thunderstorm.cloudShare(), thunder);
        }

        public float snowClouds(float thunder) {
            return LiteMath.lerp(snow.cloudShare(), blizzard.cloudShare(), thunder);
        }

        public float sandClouds(float thunder) {
            return LiteMath.lerp(sandstorm.cloudShare(), haboob.cloudShare(), thunder);
        }
    }

    /** The pixel grid weather on glass snaps to, in texels per block. */
    public enum GlassDetail {
        TEXELS_16(16),
        TEXELS_32(32);

        public final int texelsPerBlock;

        GlassDetail(int texelsPerBlock) {
            this.texelsPerBlock = texelsPerBlock;
        }
    }

    /** Weather on glass: frost and snow, and dust after sandstorms, each with its own switch. */
    public record Glass(boolean enabled, GlassDetail detail, boolean frost, boolean dust) {
        public static final Glass DEFAULTS = new Glass(true, GlassDetail.TEXELS_16, true, true);
    }

    public enum GlowColour { ENCHANT, BLUE, CYAN, GREEN, GOLD, RED, WHITE }

    /**
     * diffusion: 0 a crisp line, 0.5 the original soft glow, 1 twice as soft. wornArmour: also enchanted armour that is worn.
     * hideShimmer: no vanilla glint on what gets an outline. trimColours: trimmed armour glows in its trim's colour.
     */
    public record Outlines(boolean enchantmentOutlines, GlowColour colour, float intensity, int size, float saturation,
                           float diffusion, boolean wornArmour, boolean hideShimmer, boolean trimColours) {
        public static final Outlines DEFAULTS = new Outlines(true, GlowColour.ENCHANT, 1.0f, 10, 0.8f, 0.5f, true, false, true);
    }

    /**
     * The End's switches. Per-dimension switches for modded dimensions live with the config system
     * and are applied by {@code render.frame.DimensionGate}.
     */
    public record Dimensions(
        boolean endHaze,
        boolean endSkyGlow,
        boolean endColorTint,
        boolean endMotes
    ) {
        public static final Dimensions DEFAULTS = new Dimensions(true, true, true, true);
    }
}
