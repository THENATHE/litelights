package com.thenathe.litelights.clouds.field;

/**
 * The rain and thunder the clouds follow, and the rain and thunder shown under them. Vanilla turns
 * its rain on or off in five seconds (0.01 a tick), far too fast for a sky to cloud over, so the
 * two meet in the middle:
 * <ul>
 *   <li><b>the clouds</b> ({@link #rain}, {@link #thunder}) follow vanilla's levels at a pace a sky
 *   can: a deck closes in about 35 seconds and breaks up over two minutes once the rain stops;
 *   thunderheads take two minutes to build (faster, and towers rose five blocks a tick and anvils
 *   swept through the sky) and three to fall away;</li>
 *   <li><b>what is shown</b> ({@link #shownRain}, {@link #shownThunder}: LiteLights' rain, snow and
 *   mist, the sky's and the land's darkening, the rain's sound) waits for its clouds: the first
 *   drops fall once the deck is well on its way, under a sky already darkening, and full rain
 *   needs a closed deck. When the rain stops it fades over about fifteen seconds while the
 *   clouds linger. The server's own rain is never touched.</li>
 * </ul>
 * Where LiteLights can see the weather coming (single player, where the game's own server runs
 * beside it), a forecast lets the clouds start early, so the deck is closed by the time the rain
 * begins and the rain eases in at once.
 *
 * <p>Where a seasons mod gives each player only a share of the rain (Almanac), the world's own rain
 * and thunder go through the same pace beside the player's ({@link #shownWorldRain}), so weather
 * worked out from the world's levels (a sandstorm, snow gathering on leaves) comes and goes with
 * the clouds too. Without one the two are the same.
 *
 * <p>The levels jump straight to vanilla's on the first tick in a world, so a player joining in the
 * rain is under cloud, in the rain, at once. Call {@link #tick} once a game tick; read the levels
 * from any thread after.
 */
public final class CloudWeather {
    /** Per tick, the most the clouds' levels move toward vanilla's (and the forecast's). */
    static final float RAIN_IN = 1.0f / 700.0f;
    static final float RAIN_OUT = 1.0f / 2_400.0f;
    static final float THUNDER_IN = 1.0f / 2_400.0f;
    static final float THUNDER_OUT = 1.0f / 3_600.0f;
    /**
     * Per tick, how fast the thunderheads build once a real bolt has struck before them (on a server,
     * where no forecast says the storm is coming): about 45 seconds from nothing.
     */
    static final float THUNDER_HURRIED = 1.0f / 900.0f;
    /** Per tick, the most the shown levels move toward theirs. */
    static final float SHOWN_IN = 1.0f / 200.0f;
    static final float SHOWN_OUT = 1.0f / 300.0f;
    /** The deck's level at which rain starts to show, and at which it may show in full. */
    static final float RAIN_FROM = 0.4f;
    static final float RAIN_FULL = 0.9f;
    /** The same for thunder and the thunderheads. */
    static final float THUNDER_FROM = 0.2f;
    static final float THUNDER_FULL = 0.6f;
    /**
     * How long before the forecast rain the clouds start closing, in ticks: a little longer than
     * the deck takes, so it is closed when the rain begins.
     */
    public static final int RAIN_LEAD_TICKS = 800;
    /** The same for thunderheads. */
    public static final int THUNDER_LEAD_TICKS = 2_600;

    /** The player's own rain and thunder: the clouds overhead, and what is shown where they stand. */
    private final Channel here = new Channel();
    /**
     * The world's own rain and thunder, where a seasons mod gives each player only a share of it
     * (Almanac): the same pace, for weather that blows where nothing falls (a sandstorm) or gathers
     * from the snow falling elsewhere (snowy leaves). Without a seasons mod it is the player's.
     */
    private final Channel world = new Channel();
    private boolean started;

    /**
     * Moves the levels one tick toward vanilla's, with no forecast.
     *
     * @param vanillaRain    the level's rain, 0..1
     * @param vanillaThunder the level's thunder as vanilla shows it (already times the rain), 0..1
     */
    public void tick(float vanillaRain, float vanillaThunder) {
        tick(vanillaRain, vanillaThunder, 0.0f, 0.0f);
    }

    /**
     * Moves the levels one tick toward vanilla's and the forecast, with the world's levels the
     * player's own (no seasons mod sharing the rain out).
     *
     * @param rainDue    how close forecast rain is, 0..1 ({@link #dueness}): the clouds close toward it
     * @param thunderDue how close a forecast thunderstorm is, 0..1
     */
    public void tick(float vanillaRain, float vanillaThunder, float rainDue, float thunderDue) {
        tick(vanillaRain, vanillaThunder, vanillaRain, vanillaThunder, rainDue, thunderDue);
    }

    /**
     * Moves the levels one tick toward vanilla's, the world's and the forecast.
     *
     * @param vanillaRain    the player's rain (their share of the world's, with a seasons mod that shares it out)
     * @param vanillaThunder the player's thunder, already times their rain
     * @param worldRain      the world's own rain, before any share
     * @param worldThunder   the world's own thunder, already times the world's rain
     */
    public void tick(float vanillaRain, float vanillaThunder, float worldRain, float worldThunder, float rainDue, float thunderDue) {
        here.tick(vanillaRain, vanillaThunder, rainDue, thunderDue, started);
        world.tick(worldRain, worldThunder, rainDue, thunderDue, started);
        started = true;
    }

    /**
     * One tick under clouds that are really where the weather is (a weather mod's regional storms,
     * drawn where they are, LiteLights' Dynamic clouds fed from Almanac): the clouds need no pace of
     * their own, since they come and go with their storms, and the weather shown is the weather
     * mod's at the camera, held back only where the cloud over the camera isn't there yet (a storm
     * still closing in). A storm's cloud reaches past its rain, so the cloud comes first and the rain
     * then falls when and where the weather mod says, with no forecast.
     *
     * @param rain         the rain or snow at the camera, as the weather mod gives it (already eased)
     * @param thunder      the thunder there, already times the rain
     * @param worldRain    the storm over the camera whatever falls from it (a sandstorm's strength)
     * @param worldThunder the same for thunder
     * @param cover        how much of the sky straight over the camera holds cloud now, 0..1
     */
    public void tickUnder(float rain, float thunder, float worldRain, float worldThunder, float cover) {
        here.under(rain, thunder, cover);
        world.under(worldRain, worldThunder, cover);
        started = true;
    }

    /**
     * A real lightning bolt struck: the thunderheads, if they are still building, build faster from
     * now until they have caught up with the thunder, so the bolts don't fall from a sky with no
     * storm in it for long. The bolts themselves are the server's and can't wait.
     */
    public void boltStruck() {
        here.hurried = true;
        world.hurried = true;
    }

    /** Forgets the levels, for a new world or dimension: the next tick takes vanilla's at once. */
    public void reset() {
        started = false;
        here.reset();
        world.reset();
    }

    /** Whether a tick has given it vanilla's levels since it was made or reset. */
    public boolean started() {
        return started;
    }

    /** The clouds' rain level: how far the rain deck has closed, 0..1. */
    public float rain() {
        return here.deck;
    }

    /** The clouds' thunder level: how far the thunderheads have built, 0..1. */
    public float thunder() {
        return here.towers;
    }

    /** The rain to show, between the last tick and this one, 0..1. */
    public float shownRain(float partialTick) {
        return here.shownRain(partialTick);
    }

    /** The thunder to show (already times the rain, as vanilla's is), between the last tick and this one. */
    public float shownThunder(float partialTick) {
        return here.shownThunder(partialTick);
    }

    /**
     * The world's rain at the clouds' pace, between the last tick and this one: it rises and falls
     * as the rain shown would under a sky following the world's rain.
     */
    public float shownWorldRain(float partialTick) {
        return world.shownRain(partialTick);
    }

    /** The world's thunder at the clouds' pace (already times the world's rain). */
    public float shownWorldThunder(float partialTick) {
        return world.shownThunder(partialTick);
    }

    /**
     * How close forecast weather is, 0..1: 0 while it is further off than {@code leadTicks}, rising
     * to 1 as it starts.
     *
     * @param ticksUntil ticks until it starts, as the server's weather counts them down
     */
    public static float dueness(int ticksUntil, int leadTicks) {
        if (ticksUntil <= 0) {
            return 1.0f;
        }
        return Math.clamp(1.0f - ticksUntil / (float) leadTicks, 0.0f, 1.0f);
    }

    /** One tick's move of {@code current} toward {@code target}, at most {@code up} rising or {@code down} falling. */
    static float step(float current, float target, float up, float down) {
        if (target > current) {
            return Math.min(target, current + up);
        }
        return Math.max(target, current - down);
    }

    private static float ramp(float level, float from, float full) {
        return (float) FieldMath.smoothstep(from, full, level);
    }

    /**
     * One set of levels at the clouds' pace: a deck and towers following the rain and thunder they
     * are given (and the forecast), and the rain and thunder shown under them.
     */
    private static final class Channel {
        private volatile float deck;
        private volatile float towers;
        private volatile float shownRain;
        private volatile float shownThunder;
        private volatile float previousShownRain;
        private volatile float previousShownThunder;
        /** A bolt struck before the towers stood: they build at the hurried pace until they have caught up. */
        private volatile boolean hurried;

        void tick(float rain, float thunder, float rainDue, float thunderDue, boolean started) {
            float rainTarget = Math.max(rain, rainDue);
            float thunderTarget = Math.max(thunder, thunderDue);
            if (!started) {
                deck = rainTarget;
                towers = thunderTarget;
                shownRain = rain;
                shownThunder = Math.min(thunder, rain);
                previousShownRain = shownRain;
                previousShownThunder = shownThunder;
                return;
            }
            deck = step(deck, rainTarget, RAIN_IN, RAIN_OUT);
            towers = step(towers, thunderTarget, hurried ? THUNDER_HURRIED : THUNDER_IN, THUNDER_OUT);
            hurried &= towers < thunderTarget;
            previousShownRain = shownRain;
            previousShownThunder = shownThunder;
            shownRain = step(shownRain, rain * ramp(deck, RAIN_FROM, RAIN_FULL), SHOWN_IN, SHOWN_OUT);
            float thunderShows = thunder * ramp(towers, THUNDER_FROM, THUNDER_FULL);
            shownThunder = Math.min(shownRain, step(shownThunder, thunderShows, SHOWN_IN, SHOWN_OUT));
        }

        /** Under clouds that are really there: the levels as given, held back where the cloud overhead isn't there yet. */
        void under(float rain, float thunder, float cover) {
            deck = cover;
            towers = cover;
            hurried = false;
            previousShownRain = shownRain;
            previousShownThunder = shownThunder;
            float held = ramp(cover, RAIN_FROM, RAIN_FULL);
            shownRain = rain * held;
            shownThunder = Math.min(shownRain, thunder * held);
        }

        void reset() {
            hurried = false;
            deck = 0.0f;
            towers = 0.0f;
            shownRain = 0.0f;
            shownThunder = 0.0f;
            previousShownRain = 0.0f;
            previousShownThunder = 0.0f;
        }

        float shownRain(float partialTick) {
            return previousShownRain + (shownRain - previousShownRain) * partialTick;
        }

        float shownThunder(float partialTick) {
            return previousShownThunder + (shownThunder - previousShownThunder) * partialTick;
        }
    }
}
