package com.thenathe.litelights.render.feature.particles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a kind of particle looks like and how it moves. Behaviour is plain data read by
 * {@link Simulation} (motion, ground contact) and {@link ParticleRenderer} (sprite, size, fade);
 * there is no scripting.
 *
 * <p>Units: blocks and ticks. Gravity is added to the downward speed every tick, drag multiplies
 * the speed every tick, wind coupling is how much of the gap to the wind's speed closes per tick.
 */
public final class ParticleKind {
    private static final List<ParticleKind> ALL = new ArrayList<>();

    public enum Shape {
        /** Faces the camera. */
        BILLBOARD,
        /** Stretched along its motion and turned to face the camera around that line (rain). */
        STREAK,
        /** Lies flat on the ground, facing up (ripples). */
        FLAT
    }

    public enum Ground {
        /** Ignores the ground (ambient particles, which also live under roofs). */
        NONE,
        /** Disappears on reaching the ground or a roof. */
        DIE,
        /** Disappears with a splash, and a ripple on water. */
        SPLASH,
        /** Comes to rest on the ground and melts away soon after. */
        SETTLE
    }

    public enum Frames {
        /** One frame per particle, picked by its seed. */
        BY_SEED,
        /** Frames play once over the particle's life. */
        BY_AGE,
        /** Frame 1 while the particle's flash is bright, frame 0 otherwise (fireflies). */
        BLINK
    }

    public enum Layer { TRANSLUCENT, GLOW, SOFT }

    /**
     * The haze kinds, the large soft puffs that fill the air (DECISIONS fog097). Each is drawn as a
     * 3D puff, as part of the Mist field or as a classic billboard, as Mist style says; the shaders
     * know each by its place in this list after NONE (particle/puff.vsh).
     */
    public enum Haze { NONE, MIST, SAND, ASH, END }

    // --- Weather ---
    public static final ParticleKind RAIN = builder("rain", ParticleCategory.WEATHER).sprites("rain_drop")
        .shape(Shape.STREAK).gravity(0.06f).drag(0.98f).wind(0.18f).ground(Ground.SPLASH).range(40).build();
    public static final ParticleKind RAIN_SPLASH = builder("rain_splash", ParticleCategory.WEATHER)
        .sprites("rain_splash_0", "rain_splash_1", "rain_splash_2", "rain_splash_3").frames(Frames.BY_AGE).range(28).fixedRange().build();
    public static final ParticleKind RAIN_RIPPLE = builder("rain_ripple", ParticleCategory.WEATHER)
        .sprites("ripple_0", "ripple_1", "ripple_2", "ripple_3").frames(Frames.BY_AGE).shape(Shape.FLAT).range(28).fixedRange().build();
    // Snow is soft so the air between covers distant flakes as it covers the land: in a blizzard's
    // bright fog, flakes under vanilla's particle shader stood out as dark violet dots.
    public static final ParticleKind SNOW = builder("snow", ParticleCategory.WEATHER)
        .sprites("snow_v_0", "snow_v_1", "snow_v_2", "snow_v_3").hdSprites("snow_hd_0", "snow_hd_1", "snow_hd_2", "snow_hd_3")
        .layer(Layer.SOFT).gravity(0.004f).drag(0.95f).wind(0.08f).wander(0.012f, 0.13f).ground(Ground.SETTLE).fade(6, 0.15f).range(36).build();
    public static final ParticleKind MIST = builder("mist", ParticleCategory.WEATHER).sprites("haze_0", "haze_1", "haze_2")
        .layer(Layer.SOFT).haze(Haze.MIST).nearFade(7.0f).drag(0.96f).wind(0.02f).wander(0.002f, 0.03f).fade(40, 0.35f).growth(1.3f).range(48).fixedRange().build();
    public static final ParticleKind SAND = builder("sand", ParticleCategory.WEATHER)
        .sprites("sand_v_0", "sand_v_1", "sand_v_2").hdSprites("sand_hd_0", "sand_hd_1", "sand_hd_2")
        .gravity(0.003f).drag(0.96f).wind(0.2f).wander(0.02f, 0.4f).ground(Ground.DIE).fade(4, 0.2f).range(32).build();
    public static final ParticleKind SAND_CLOUD = builder("sand_cloud", ParticleCategory.WEATHER)
        .sprites("haze_0", "haze_1", "haze_2").layer(Layer.SOFT).haze(Haze.SAND).nearFade(6.0f).drag(0.95f).wind(0.1f).wander(0.004f, 0.05f)
        .fade(16, 0.4f).growth(1.6f).range(40).fixedRange().build();
    // Sandstorm grains race along at well over the wind's speed, drawn as thin streaks along their
    // motion. They are soft particles so the storm's own sandy air covers them as it covers the
    // dust clouds; under vanilla's particle shader they stood out as grey dashes against the haze.
    public static final ParticleKind SANDSTORM_GRAIN = builder("sandstorm_grain", ParticleCategory.WEATHER).sprites("rain_drop")
        .shape(Shape.STREAK).layer(Layer.SOFT).nearFade(2.5f).gravity(0.002f).drag(0.97f).wind(0.2f).windBoost(2.4f).wander(0.02f, 0.4f)
        .ground(Ground.DIE).fade(3, 0.2f).range(32).build();
    public static final ParticleKind WIND_GRIT = builder("wind_grit", ParticleCategory.WEATHER).sprites("speck", "sand_v_1")
        .gravity(0.002f).drag(0.97f).wind(0.25f).wander(0.03f, 0.5f).ground(Ground.DIE).fade(5, 0.3f).range(28).build();
    // Rain details (drip and streak ideas by pigcart, Particle Rain; concepts only). A drip swells
    // under a wet eave, ledge or leaf for a moment, then falls and always splashes where it lands.
    public static final ParticleKind RAIN_DRIP = builder("rain_drip", ParticleCategory.WEATHER).sprites("drip")
        .hang(16).gravity(0.045f).drag(0.98f).ground(Ground.SPLASH).splash(16, 0.7f).fade(4, 0.05f).range(24).build();
    // Water running down a wall in the rain: a short streak drawn like a rain drop, sliding down the face.
    // Its lifetime ends it at the ground in front of the face (RainDetails), not a ground check: a
    // pane's face lies inside the pane's own column, whose top is the pane.
    public static final ParticleKind WALL_STREAK = builder("wall_streak", ParticleCategory.WEATHER).sprites("rain_drop")
        .shape(Shape.STREAK).streakLength(0.15f).fade(4, 0.4f).range(24).build();

    // --- Ambient ---
    public static final ParticleKind FIREFLY = builder("firefly", ParticleCategory.AMBIENT).sprites("firefly_0", "firefly_1").nearFade(2.5f)
        .frames(Frames.BLINK).layer(Layer.GLOW).emissive().drag(0.9f).wander(0.012f, 0.07f).twinkle().fade(20, 0.2f).range(40).build();
    // Small sprites fade out close to the camera: a speck drifting up to the lens would otherwise
    // fill the view as a big square or plus.
    public static final ParticleKind DUST_SPECK = builder("dust_speck", ParticleCategory.AMBIENT).sprites("speck", "mote")
        .nearFade(2.5f).drag(0.9f).wander(0.0015f, 0.05f).fade(30, 0.3f).range(18).build();
    public static final ParticleKind POLLEN = builder("pollen", ParticleCategory.AMBIENT).sprites("pollen_0", "pollen_1").nearFade(2.5f)
        .gravity(-0.0006f).drag(0.92f).wind(0.04f).wander(0.003f, 0.09f).fade(20, 0.3f).range(28).build();
    public static final ParticleKind SPORE = builder("spore", ParticleCategory.AMBIENT).sprites("spore_0", "spore_1", "spore_2").nearFade(2.5f)
        .gravity(0.0008f).drag(0.92f).wind(0.02f).wander(0.0025f, 0.06f).fade(24, 0.3f).range(28).build();
    public static final ParticleKind GLOW_SPORE = builder("glow_spore", ParticleCategory.AMBIENT).sprites("spore_0", "spore_2").nearFade(2.5f)
        .layer(Layer.GLOW).emissive().gravity(-0.0004f).drag(0.92f).wander(0.0025f, 0.06f).twinkle().fade(24, 0.3f).range(28).build();

    // --- Fire ---
    public static final ParticleKind EMBER = builder("ember", ParticleCategory.FIRE).sprites("ember_0", "ember_1", "ember_2")
        .layer(Layer.GLOW).emissive().gravity(-0.004f).drag(0.94f).wind(0.03f).wander(0.012f, 0.35f).twinkle().fade(2, 0.35f).range(40).build();
    // Smoke lives about six seconds: it slows as it rises, leans with the wind and spreads wide, so
    // the long life thins into the air instead of stacking into a solid column.
    public static final ParticleKind SMOKE = builder("smoke", ParticleCategory.FIRE).sprites("smoke_0", "smoke_1", "smoke_2", "smoke_3")
        .gravity(-0.0026f).drag(0.93f).wind(0.05f).wander(0.0045f, 0.05f).fade(8, 0.6f).growth(3.4f).fadeTo(0x6E6A66).range(48).build();

    // --- Nether ---
    public static final ParticleKind ASH_HAZE = builder("ash_haze", ParticleCategory.NETHER).sprites("haze_0", "haze_1", "haze_2")
        .layer(Layer.SOFT).haze(Haze.ASH).nearFade(5.0f).drag(0.96f).wind(0.02f).wander(0.002f, 0.03f).fade(40, 0.4f).growth(1.4f).range(48).fixedRange().build();
    public static final ParticleKind ASH = builder("ash", ParticleCategory.NETHER).sprites("ash_0", "ash_1")
        .gravity(0.0015f).drag(0.94f).wind(0.04f).wander(0.006f, 0.15f).fade(10, 0.3f).range(28).build();
    public static final ParticleKind NETHER_EMBER = builder("nether_ember", ParticleCategory.NETHER).sprites("ember_1", "ember_2")
        .layer(Layer.GLOW).emissive().gravity(-0.0015f).drag(0.94f).wind(0.04f).wander(0.008f, 0.2f).twinkle().fade(6, 0.4f).range(36).build();
    public static final ParticleKind CRIMSON_DRIP = builder("crimson_drip", ParticleCategory.NETHER).sprites("drip")
        .layer(Layer.GLOW).emissive().gravity(0.006f).drag(0.96f).fade(4, 0.15f).range(32).build();
    public static final ParticleKind CRIMSON_MOTE = builder("crimson_mote", ParticleCategory.NETHER).sprites("mote", "spore_2").nearFade(2.5f)
        .layer(Layer.GLOW).emissive().gravity(-0.0005f).drag(0.92f).wander(0.003f, 0.07f).twinkle().fade(20, 0.3f).range(32).build();
    public static final ParticleKind SOUL_WISP = builder("soul_wisp", ParticleCategory.NETHER).sprites("wisp_0", "wisp_1", "wisp_2", "wisp_3")
        .frames(Frames.BY_AGE).layer(Layer.GLOW).emissive().gravity(-0.003f).drag(0.93f).wander(0.006f, 0.1f).fade(12, 0.4f).range(40).build();
    public static final ParticleKind WARPED_SPORE = builder("warped_spore", ParticleCategory.NETHER).sprites("spore_0", "spore_1", "spore_2").nearFade(2.5f)
        .layer(Layer.GLOW).emissive().gravity(0.0006f).drag(0.92f).wander(0.003f, 0.06f).twinkle().fade(20, 0.3f).range(32).build();

    // --- The End ---
    public static final ParticleKind END_MOTE = builder("end_mote", ParticleCategory.END).sprites("mote", "spore_2").nearFade(2.5f)
        .layer(Layer.GLOW).emissive().gravity(-0.0012f).drag(0.93f).wander(0.003f, 0.05f).twinkle().fade(30, 0.35f).range(40).build();
    public static final ParticleKind END_SPECK = builder("end_speck", ParticleCategory.END).sprites("speck").nearFade(2.5f)
        .layer(Layer.GLOW).emissive().drag(0.9f).wander(0.002f, 0.08f).twinkle().fade(20, 0.3f).range(24).build();
    public static final ParticleKind END_MIST = builder("end_mist", ParticleCategory.END).sprites("haze_0", "haze_1", "haze_2")
        .layer(Layer.SOFT).haze(Haze.END).nearFade(5.0f).drag(0.96f).wander(0.0015f, 0.025f).fade(50, 0.4f).growth(1.3f).range(48).fixedRange().build();

    public final int id;
    public final String name;
    public final ParticleCategory category;
    public final List<String> sprites;
    /** Sprites for the HD snow and sand style; the same as {@link #sprites} for other kinds. */
    public final List<String> hdSprites;
    public final Shape shape;
    public final Frames frames;
    public final Layer layer;
    public final Haze haze;
    public final Ground ground;
    public final boolean emissive;
    public final boolean twinkle;
    public final float gravity;
    public final float drag;
    public final float windCoupling;
    /** The particle settles to the wind's speed times this (sandstorm grains outrun the wind). */
    public final float windBoost;
    public final float wanderStrength;
    public final float wanderRate;
    /** Ticks to fade in, and the share of the life at the end spent fading out. */
    public final int fadeInTicks;
    public final float fadeOutShare;
    /** Size at the end of the life as a multiple of the starting size. */
    public final float growth;
    /** Beyond this distance from the camera the particle isn't drawn, and is removed a little further out. */
    public final float range;
    /**
     * Whether the kind keeps its Near area, count and range at every Weather or Particle distance:
     * kinds tied to where something happens (a drop's splash), and the haze kinds (mist, dust
     * clouds, ash haze, End mist), which are the air close by. The fog carries the air into the
     * distance; more puffs further out only stacked up into a white-out.
     */
    public final boolean fixedRange;
    /** Closer than this to the camera the particle fades away (half gone at three quarters of it), or 0. */
    public final float nearFade;
    /** RGB the particle's colour moves to over its life (smoke cooling from fire-lit to grey), or -1. */
    public final int fadeToColor;
    /** Ticks the particle hangs where it formed, growing to its full size, before it starts to move (drips). */
    public final int hangTicks;
    /** Out of 16: how many of a splashing kind leave a splash where they land. */
    public final int splashChance;
    /** Size of the splash or ripple a landing leaves, as a multiple of a rain drop's. */
    public final float splashScale;
    /** A streak's half-length in blocks, whatever its speed; 0 to stretch it with the motion (rain). */
    public final float streakLength;

    private ParticleKind(Builder builder) {
        this.id = ALL.size();
        this.name = builder.name;
        this.category = builder.category;
        this.sprites = List.copyOf(builder.sprites);
        this.hdSprites = builder.hdSprites == null ? this.sprites : List.copyOf(builder.hdSprites);
        this.shape = builder.shape;
        this.frames = builder.frames;
        this.layer = builder.layer;
        this.haze = builder.haze;
        this.ground = builder.ground;
        this.emissive = builder.emissive;
        this.twinkle = builder.twinkle;
        this.gravity = builder.gravity;
        this.drag = builder.drag;
        this.windCoupling = builder.windCoupling;
        this.windBoost = builder.windBoost;
        this.wanderStrength = builder.wanderStrength;
        this.wanderRate = builder.wanderRate;
        this.fadeInTicks = builder.fadeInTicks;
        this.fadeOutShare = builder.fadeOutShare;
        this.growth = builder.growth;
        this.range = builder.range;
        this.fixedRange = builder.fixedRange;
        this.fadeToColor = builder.fadeToColor;
        this.nearFade = builder.nearFade;
        this.hangTicks = builder.hangTicks;
        this.splashChance = builder.splashChance;
        this.splashScale = builder.splashScale;
        this.streakLength = builder.streakLength;
        ALL.add(this);
    }

    public static List<ParticleKind> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static ParticleKind byId(int id) {
        return ALL.get(id);
    }

    @Override
    public String toString() {
        return name;
    }

    private static Builder builder(String name, ParticleCategory category) {
        return new Builder(name, category);
    }

    private static final class Builder {
        private final String name;
        private final ParticleCategory category;
        private List<String> sprites = List.of();
        private List<String> hdSprites;
        private Shape shape = Shape.BILLBOARD;
        private Frames frames = Frames.BY_SEED;
        private Layer layer = Layer.TRANSLUCENT;
        private Haze haze = Haze.NONE;
        private Ground ground = Ground.NONE;
        private boolean emissive;
        private boolean twinkle;
        private float gravity;
        private float drag = 1.0f;
        private float windCoupling;
        private float windBoost = 1.0f;
        private float wanderStrength;
        private float wanderRate;
        private int fadeInTicks = 2;
        private float fadeOutShare = 0.2f;
        private float growth = 1.0f;
        private float range = 32.0f;
        private boolean fixedRange;
        private int fadeToColor = -1;
        private float nearFade;
        private int hangTicks;
        private int splashChance = 6;
        private float splashScale = 1.0f;
        private float streakLength;

        private Builder(String name, ParticleCategory category) {
            this.name = name;
            this.category = category;
        }

        Builder sprites(String... names) {
            this.sprites = List.of(names);
            return this;
        }

        Builder hdSprites(String... names) {
            this.hdSprites = List.of(names);
            return this;
        }

        Builder shape(Shape value) {
            this.shape = value;
            return this;
        }

        Builder frames(Frames value) {
            this.frames = value;
            return this;
        }

        Builder layer(Layer value) {
            this.layer = value;
            return this;
        }

        Builder haze(Haze value) {
            this.haze = value;
            return this;
        }

        Builder ground(Ground value) {
            this.ground = value;
            return this;
        }

        Builder emissive() {
            this.emissive = true;
            return this;
        }

        Builder twinkle() {
            this.twinkle = true;
            return this;
        }

        Builder gravity(float value) {
            this.gravity = value;
            return this;
        }

        Builder drag(float value) {
            this.drag = value;
            return this;
        }

        Builder wind(float coupling) {
            this.windCoupling = coupling;
            return this;
        }

        Builder windBoost(float multiple) {
            this.windBoost = multiple;
            return this;
        }

        Builder wander(float strength, float rate) {
            this.wanderStrength = strength;
            this.wanderRate = rate;
            return this;
        }

        Builder fade(int inTicks, float outShare) {
            this.fadeInTicks = inTicks;
            this.fadeOutShare = outShare;
            return this;
        }

        Builder growth(float value) {
            this.growth = value;
            return this;
        }

        Builder nearFade(float blocks) {
            this.nearFade = blocks;
            return this;
        }

        Builder fadeTo(int rgb) {
            this.fadeToColor = rgb;
            return this;
        }

        Builder range(float blocks) {
            this.range = blocks;
            return this;
        }

        Builder hang(int ticks) {
            this.hangTicks = ticks;
            return this;
        }

        Builder splash(int inSixteen, float scale) {
            this.splashChance = inSixteen;
            this.splashScale = scale;
            return this;
        }

        Builder streakLength(float halfLength) {
            this.streakLength = halfLength;
            return this;
        }

        Builder fixedRange() {
            this.fixedRange = true;
            return this;
        }

        ParticleKind build() {
            return new ParticleKind(this);
        }
    }
}
