package com.thenathe.litelights.render.feature.weather;

/**
 * Where a night's distant fireworks are in the world and how high their bursts go: a town a
 * kilometre and a half to three kilometres from where the player was when the show began, in the
 * night's direction, its bursts between a sensible height over the land there and a ceiling just
 * under the clouds' bases, so a show is never hidden by the clouds over it and never bursts inside
 * the land. Plain arithmetic over the schedule's hashes and what the land and clouds report, so it
 * can be tested without the game ({@code FireworksShowTest}).
 *
 * <p>Every player on a world sees the same bursts at the same moments ({@link SkyEventSchedule}),
 * but the schedule holds no place in the world that everyone shares, so each client places the show
 * from its own player. The player's position is first rounded to the middle of a square of
 * {@link #ANCHOR_GRID} blocks: players standing together when the show starts see it in exactly
 * the same place, while players farther apart see it each about the same distance and direction
 * from themselves, in places as far apart as they are.
 */
public final class FireworksShow {
    /** How far from the player a real night's show is placed, in blocks (the schedule picks within this). */
    public static final float NEAREST = 1_500.0f;
    public static final float FARTHEST = 3_000.0f;
    /** A previewed show is placed this far in front of where the player looked. */
    public static final float PREVIEW_DISTANCE = 1_500.0f;
    /** The player's position is rounded to the middle of a square this wide before the show is placed from it. */
    static final double ANCHOR_GRID = 256.0;
    /** A show farther than this from the camera is placed again between volleys (the player went far). */
    public static final double PLACED_AGAIN_BEYOND = 4_500.0;
    /** Bursts go up this far either side of the show's place, across the line of sight and along it. */
    static final float SPREAD_ACROSS = 70.0f;
    static final float SPREAD_ALONG = 45.0f;
    /** The land under the show is asked for this far round its place, which covers its spread and its widest bursts. */
    static final int FOOTPRINT = 128;
    /**
     * A burst's radius, in blocks (60 to 90 across: 40 to 70 read as specks a dozen pixels wide at
     * two kilometres); one squeezed under low clouds may shrink to the least.
     */
    static final float SMALLEST_RADIUS = 30.0f;
    static final float LARGEST_RADIUS = 45.0f;
    static final float LEAST_RADIUS = 14.0f;
    /**
     * How far a spark falls by the end of a burst, as a share of its radius per tick squared: the
     * sky sparks' droop until 1.1.6 against their mean size, so a burst keeps its shape.
     */
    public static final float DROOP = 0.00034f;
    /**
     * The lowest a burst's sparks reach under its middle, in radii: its full spread and half its
     * droop at the end (by then most sparks have twinkled out and the rest are faint).
     */
    static final float REACH_BELOW = 1.0f + 0.5f * DROOP * SkyEventSchedule.FIREWORK_TICKS * SkyEventSchedule.FIREWORK_TICKS;
    /** No spark comes nearer the land than this. */
    static final float CLEARANCE = 12.0f;
    /** Where the room allows, a burst's middle is this far over the land, as a town's would be. */
    static final float LOWEST_OVER_LAND = 60.0f;
    static final float HIGHEST_OVER_LAND = 150.0f;
    /** No spark comes nearer the clouds' bases than this. */
    static final float UNDER_CLOUDS = 4.0f;
    /** The room under the clouds a place needs for its largest bursts to fit as they are, and the least it may have. */
    static final float ROOM_WANTED = CLEARANCE + (REACH_BELOW + 1.0f) * LARGEST_RADIUS;
    static final float ROOM_NEEDED = CLEARANCE + (REACH_BELOW + 1.0f) * LEAST_RADIUS;
    /**
     * Where the show may go, tried in turn: the night's own place first, then a little nearer or
     * farther, then turned aside, until one has the room under the clouds (turns in radians,
     * distances as shares of the night's).
     */
    private static final float[][] CANDIDATES = {
        {0.0f, 1.0f}, {0.0f, 0.8f}, {0.0f, 1.25f}, {0.2f, 1.0f}, {-0.2f, 1.0f}, {0.2f, 0.8f}, {-0.2f, 0.8f},
        {0.4f, 1.0f}, {-0.4f, 1.0f}, {0.6f, 1.0f}, {-0.6f, 1.0f}
    };

    private FireworksShow() {
    }

    /** What the land is like round a place: the highest ground known there, or {@link #UNKNOWN}. */
    public interface Ground {
        int UNKNOWN = Integer.MIN_VALUE;

        /** The highest land (or water surface) within {@code radius} blocks of a block column, or {@link #UNKNOWN}. */
        int highestAround(int x, int z, int radius);
    }

    /**
     * A placed show.
     *
     * @param x       the middle of the town, world x
     * @param z       the middle of the town, world z
     * @param azimuth the direction the show was placed in from where it was placed from, radians from
     *                north (toward -z) turning east (toward +x): the bursts' spread lies across and along it
     * @param ground  the highest land under the show, or the guess where nothing is known of it
     * @param ceiling the highest a spark may reach: just under the clouds' bases, infinite with no clouds
     */
    public record Place(double x, double z, float azimuth, int ground, float ceiling) {
        /** The same place with the land and the clouds as they are now. */
        public Place with(int newGround, float newCeiling) {
            return new Place(x, z, azimuth, newGround, newCeiling);
        }

        /** The room between the land and the ceiling, in blocks. */
        public float room() {
            return ceiling - ground;
        }
    }

    /**
     * Places tonight's show from the camera at {@code fromX}, {@code fromZ}: in the night's
     * direction at its distance, or the nearest other place with room for the bursts under the
     * clouds. Null when no place near there has room (a mountain range under low clouds).
     *
     * @param guessedGround the land's height to take where nothing is known of it ({@link #guessedGround})
     * @param cloudBase     the clouds' bases (their floor), or infinite with no clouds
     * @param preview       a previewed show: placed from the camera itself, at {@link #PREVIEW_DISTANCE}
     */
    public static Place place(SkyEventSchedule.Night night, double fromX, double fromZ, Ground ground, int guessedGround, float cloudBase,
                              boolean preview) {
        double originX = preview ? fromX : anchor(fromX);
        double originZ = preview ? fromZ : anchor(fromZ);
        float ceiling = cloudBase - UNDER_CLOUDS;
        Place roomiest = null;
        for (float[] candidate : CANDIDATES) {
            float azimuth = night.fireworksAzimuth() + candidate[0];
            float distance = night.fireworksDistance() * candidate[1];
            double x = originX + Math.sin(azimuth) * distance;
            double z = originZ - Math.cos(azimuth) * distance;
            Place place = new Place(x, z, night.fireworksAzimuth(), groundAt(x, z, ground, guessedGround), ceiling);
            if (place.room() >= ROOM_WANTED) {
                return place;
            }
            if (roomiest == null || place.room() > roomiest.room()) {
                roomiest = place;
            }
        }
        return roomiest.room() >= ROOM_NEEDED ? roomiest : null;
    }

    /** A coordinate rounded to the middle of its square of {@link #ANCHOR_GRID} blocks. */
    static double anchor(double coordinate) {
        return (Math.floor(coordinate / ANCHOR_GRID) + 0.5) * ANCHOR_GRID;
    }

    /** The highest land known under a show at this place, or the guess. */
    public static int groundAt(double x, double z, Ground ground, int guessedGround) {
        int known = ground.highestAround((int) Math.floor(x), (int) Math.floor(z), FOOTPRINT);
        return known == Ground.UNKNOWN ? guessedGround : known;
    }

    /**
     * The land's height to take where nothing is known of it: the player's own ground if that is
     * above the sea (a show seen from a plateau is likely over one), else sea level, but never so
     * high that the largest bursts no longer fit under the clouds.
     */
    public static int guessedGround(int seaLevel, int playerGround, float cloudBase) {
        float highest = cloudBase - UNDER_CLOUDS - ROOM_WANTED;
        return (int) Math.max(seaLevel, Math.min(Math.max(seaLevel, playerGround), highest));
    }

    /**
     * Where one burst of the show is: fills {@code out} with its middle (world x, y, z) and its
     * radius, from the schedule's burst ({@link SkyEventSchedule#firework}). False when there is no
     * room for it under the clouds.
     */
    public static boolean burst(Place place, float[] firework, double[] out) {
        float radius = firework[2];
        float lowest = place.ground + Math.max(LOWEST_OVER_LAND, CLEARANCE + REACH_BELOW * radius);
        float highest = Math.min(place.ground + HIGHEST_OVER_LAND, place.ceiling - radius);
        if (highest < lowest) {
            // Low clouds: as low as the land allows, and smaller if it must be.
            radius = Math.min(radius, (place.room() - CLEARANCE) / (REACH_BELOW + 1.0f));
            if (radius < LEAST_RADIUS) {
                return false;
            }
            lowest = place.ground + CLEARANCE + REACH_BELOW * radius;
            highest = Math.max(lowest, place.ceiling - radius);
        }
        double acrossX = Math.cos(place.azimuth);
        double acrossZ = Math.sin(place.azimuth);
        double alongX = Math.sin(place.azimuth);
        double alongZ = -Math.cos(place.azimuth);
        out[0] = place.x + acrossX * firework[0] * SPREAD_ACROSS + alongX * firework[5] * SPREAD_ALONG;
        out[1] = lowest + firework[1] * (highest - lowest);
        out[2] = place.z + acrossZ * firework[0] * SPREAD_ACROSS + alongZ * firework[5] * SPREAD_ALONG;
        out[3] = radius;
        return true;
    }
}
