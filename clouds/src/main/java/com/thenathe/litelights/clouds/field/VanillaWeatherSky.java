package com.thenathe.litelights.clouds.field;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * LiteLights' own sky, for servers without a weather mod: formations from a seed, the game time
 * and vanilla's rain and thunder alone, so nothing local is invented. The same seed, tick and
 * weather always give the same formations, and two players at different places agree on every
 * formation both can see.
 *
 * <p>Formations are born on lattices of space and time: each cell of a lattice, in each slot of
 * time, gets a few formations from a hash of the seed, the cell and the slot, each with its own
 * birth tick, place, size, life and path. Three lattices make the weather:
 * <ul>
 *   <li><b>fair weather</b>, always there: cumulus fields, congestus, stratocumulus, altocumulus,
 *       cirrus and now and then a stratus layer, in a wide spread of sizes, with clear sky between;</li>
 *   <li><b>rain cores</b>, wide areas of heavier nimbostratus that show only as it rains;</li>
 *   <li><b>thunderstorms</b>, cumulonimbus towers that show only as it thunders.</li>
 * </ul>
 * Vanilla's rain falls everywhere at once, so as it rains a nimbostratus deck also closes over the
 * whole sky and the low fair-weather types fade under it; the lattices only make it uneven.
 */
public final class VanillaWeatherSky {
    /** The wind at cloud height: a steady part from the seed plus a part turning once in five days. */
    private static final double STEADY_WIND = 0.012;
    private static final double TURNING_WIND = 0.02;
    private static final double WIND_TURN_TICKS = 120_000.0;

    private static final Lattice FAIR = new Lattice(1, 4_096.0, 24_000.0, 1.0, 0.008, List.of(
        new Recipe(CloudType.CUMULUS, 0.5).radius(600, 4_500).stretch(2.0f).life(24_000, 72_000)
            .coverage(0.3f, 0.75f).sizeScale(0.6f, 2.0f).strength(0.0f, 0.12f),
        new Recipe(CloudType.CONGESTUS, 0.16).radius(500, 2_500).stretch(1.8f).life(12_000, 36_000).grow(0.25f, 0.3f)
            .coverage(0.3f, 0.7f).sizeScale(0.7f, 1.5f).strength(0.1f, 0.3f),
        new Recipe(CloudType.STRATOCUMULUS, 0.08).radius(1_000, 4_500).stretch(2.2f).life(24_000, 96_000)
            .coverage(0.5f, 0.85f).sizeScale(0.7f, 1.6f).strength(0.05f, 0.2f),
        new Recipe(CloudType.ALTOCUMULUS, 0.12).radius(1_000, 5_000).stretch(2.5f).life(18_000, 60_000)
            .coverage(0.4f, 0.85f).sizeScale(0.7f, 1.6f).strength(0.0f, 0.05f),
        new Recipe(CloudType.CIRRUS, 0.1).radius(1_500, 6_000).stretch(3.5f).life(24_000, 96_000)
            .coverage(0.2f, 0.6f).sizeScale(0.7f, 1.5f),
        new Recipe(CloudType.STRATUS, 0.04).radius(1_500, 5_000).stretch(2.0f).life(24_000, 72_000)
            .coverage(0.75f, 1.0f).sizeScale(0.8f, 1.4f).strength(0.1f, 0.25f)
    ));
    private static final Lattice RAIN_CORES = new Lattice(2, 8_192.0, 48_000.0, 1.2, 0.015, List.of(
        new Recipe(CloudType.NIMBOSTRATUS, 1.0).radius(2_000, 7_000).stretch(3.0f).life(24_000, 72_000).grow(0.15f, 0.15f)
            .coverage(0.9f, 1.0f).sizeScale(1.0f, 2.0f).strength(0.45f, 0.8f).rain(0.7f, 1.0f, 0.85f).baseShift(-10.0f)
    ));
    private static final Lattice THUNDERSTORMS = new Lattice(3, 8_192.0, 24_000.0, 1.3, 0.015, List.of(
        new Recipe(CloudType.CUMULONIMBUS, 1.0).radius(600, 1_800).stretch(1.6f).life(12_000, 36_000).grow(0.2f, 0.3f)
            .coverage(0.45f, 0.75f).sizeScale(0.8f, 1.3f).strength(0.7f, 1.0f).rain(0.8f, 1.0f, 0.6f)
    ));

    private VanillaWeatherSky() {
    }

    /**
     * The sky at a tick around a place.
     *
     * @param seed       the sky's seed ({@link CloudOptions#skySeed})
     * @param baseHeight vanilla's cloud height in this dimension
     * @param rain       the clouds' rain level, 0..1 ({@link CloudWeather}, which follows vanilla's slowly)
     * @param thunder    the clouds' thunder level, 0..1
     * @param reach      formations that can't reach this far from ({@code centreX}, {@code centreZ}) are left out
     */
    public static CloudSky sky(long seed, float baseHeight, double tick, float rain, float thunder,
                               double centreX, double centreZ, double reach) {
        long skySeed = skySeed(seed);
        CloudWind wind = wind(skySeed);
        List<CloudFormation> formations = new ArrayList<>();
        float lowFairPresence = (float) (1.0 - FieldMath.smoothstep(0.15, 0.75, rain));
        float highFairPresence = (float) (1.0 - 0.6 * FieldMath.smoothstep(0.3, 1.0, rain));
        FAIR.collect(skySeed, wind, baseHeight, tick, centreX, centreZ, reach, formations,
            type -> type == CloudType.ALTOCUMULUS || type == CloudType.CIRRUS ? highFairPresence : lowFairPresence);
        RAIN_CORES.collect(skySeed, wind, baseHeight, tick, centreX, centreZ, reach, formations, type -> rain);
        THUNDERSTORMS.collect(skySeed, wind, baseHeight, tick, centreX, centreZ, reach, formations, type -> thunder);
        if (rain > 0.0f) {
            formations.add(deck(skySeed, baseHeight, rain, thunder));
        }
        return new CloudSky(skySeed, baseHeight, wind, formations);
    }

    /**
     * The fair-weather formations alone, for a sky whose storms come from a weather mod
     * ({@link RegionalSky}): the same lattice as {@link #sky}'s, at full presence, its areas left
     * where they form rather than carried by LiteLights' own wind (the weather mod's wind carries
     * their single clouds through them).
     *
     * @param skySeed the sky's seed as {@link #skySeed} makes it
     */
    static void fairWeather(long skySeed, float baseHeight, double tick, double centreX, double centreZ, double reach,
                            List<CloudFormation> into) {
        FAIR.collect(skySeed, CloudWind.CALM, baseHeight, tick, centreX, centreZ, reach, into, type -> 1.0f);
    }

    /**
     * The wind at cloud height that {@link #sky} gives for this seed, so a renderer can move the
     * clouds by it every frame without making a sky.
     */
    public static CloudWind windOf(long seed) {
        return wind(skySeed(seed));
    }

    static long skySeed(long seed) {
        return CloudNoise.hash(seed, 0x0C10D5L);
    }

    /** The wind at cloud height for a sky's seed. */
    static CloudWind wind(long skySeed) {
        double heading = CloudNoise.unit(CloudNoise.hash(skySeed, 0x3171DL)) * Math.PI * 2.0;
        double turnPhase = CloudNoise.unit(CloudNoise.hash(skySeed, 0x3171EL)) * Math.PI * 2.0;
        return new CloudWind(StrictMath.cos(heading) * STEADY_WIND, StrictMath.sin(heading) * STEADY_WIND,
            TURNING_WIND, WIND_TURN_TICKS, turnPhase);
    }

    /**
     * The farthest a formation's cloud can be from its centre, in blocks: its noisy edge, its
     * anvil, and single clouds that sit inside but hang over.
     */
    private static double farthestReach(CloudType type, double radius, float stretch, float sizeScale) {
        double area = radius * Math.sqrt(stretch) * Footprint.FARTHEST_EDGE;
        CloudType.Anvil anvil = type.anvil();
        if (anvil != null) {
            area = area * anvil.spread() + radius * 0.5;
        }
        CloudType.Puffs puffs = type.puffs();
        double clouds = puffs != null ? puffs.largestRadius() * sizeScale * 1.3 : 0.0;
        return area + clouds;
    }

    /** Vanilla's rain is everywhere at once, so its deck is too: holes close as the rain sets in. */
    private static CloudFormation deck(long skySeed, float baseHeight, float rain, float thunder) {
        CloudType.Heights heights = CloudType.NIMBOSTRATUS.heights();
        float angle = (float) (CloudNoise.unit(CloudNoise.hash(skySeed, 0xDEC4L)) * Math.PI * 2.0);
        return new CloudFormation(CloudNoise.hash(skySeed, 0xDEC5L), CloudType.NIMBOSTRATUS,
            0.0, 0.0, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, 0.0f, 0.0f,
            Double.POSITIVE_INFINITY, 1.0f, angle, 0.97f,
            baseHeight + heights.base(), baseHeight + heights.top(), 1.6f,
            0.35f + 0.45f * thunder, Precipitation.BY_BIOME, Math.min(1.0f, 0.6f + 0.4f * thunder), 1.0f, rain);
    }

    /** How much of a family the weather allows now, by type. */
    @FunctionalInterface
    private interface Presence {
        float of(CloudType type);
    }

    /**
     * How one kind of formation is made: the ranges its formations are drawn from. Unset ranges
     * are the defaults below (a fair-weather formation: no storm, no rain).
     */
    private static final class Recipe {
        final CloudType type;
        /** How often this recipe is picked among its lattice's. */
        final double weight;
        double smallestRadius = 1_000.0;
        double largestRadius = 1_000.0;
        float mostStretch = 1.0f;
        double shortestLife = 24_000.0;
        double longestLife = 24_000.0;
        float growShare = 0.2f;
        float shrinkShare = 0.25f;
        float leastCoverage = 0.5f;
        float mostCoverage = 0.5f;
        float smallestScale = 1.0f;
        float largestScale = 1.0f;
        float leastStrength;
        float mostStrength;
        float leastRain;
        float mostRain;
        float rainCoreShare;
        /** Moves the type's usual base, in blocks (rain cores hang lower). */
        float baseShift;

        Recipe(CloudType type, double weight) {
            this.type = type;
            this.weight = weight;
        }

        /** The area's radius, drawn log-uniform: as likely a few hundred blocks as a few thousand. */
        Recipe radius(double smallest, double largest) {
            smallestRadius = smallest;
            largestRadius = largest;
            return this;
        }

        /** The most the area is stretched; most formations are close to round. */
        Recipe stretch(float most) {
            mostStretch = most;
            return this;
        }

        Recipe life(double shortest, double longest) {
            shortestLife = shortest;
            longestLife = longest;
            return this;
        }

        /** The shares of life spent growing and shrinking. */
        Recipe grow(float grow, float shrink) {
            growShare = grow;
            shrinkShare = shrink;
            return this;
        }

        Recipe coverage(float least, float most) {
            leastCoverage = least;
            mostCoverage = most;
            return this;
        }

        /** The size of its single clouds or lumps against the type's, drawn log-uniform. */
        Recipe sizeScale(float smallest, float largest) {
            smallestScale = smallest;
            largestScale = largest;
            return this;
        }

        Recipe strength(float least, float most) {
            leastStrength = least;
            mostStrength = most;
            return this;
        }

        /** How hard it rains, and over what share of the radius. */
        Recipe rain(float least, float most, float coreShare) {
            leastRain = least;
            mostRain = most;
            rainCoreShare = coreShare;
            return this;
        }

        Recipe baseShift(float blocks) {
            baseShift = blocks;
            return this;
        }
    }

    /**
     * A lattice of cells {@code cellSize} blocks square and slots {@code slotTicks} long, in a frame
     * that moves with the steady wind (so a formation that drifts with it stays near its cell).
     *
     * @param meanCount how many formations are born in a cell in a slot, on average
     * @param ownDrift  the fastest a formation moves against the steady wind, in blocks per tick
     */
    private record Lattice(int salt, double cellSize, double slotTicks, double meanCount, double ownDrift, List<Recipe> recipes) {
        void collect(long skySeed, CloudWind wind, float baseHeight, double tick, double centreX, double centreZ,
                     double reach, List<CloudFormation> into, Presence presence) {
            double longestLife = 0.0;
            double farthest = 0.0;
            double totalWeight = 0.0;
            boolean anyPresent = false;
            for (Recipe recipe : recipes) {
                longestLife = Math.max(longestLife, recipe.longestLife);
                farthest = Math.max(farthest, farthestReach(recipe.type, recipe.largestRadius, recipe.mostStretch, recipe.largestScale));
                totalWeight += recipe.weight;
                anyPresent |= presence.of(recipe.type) > 0.0f;
            }
            if (!anyPresent) {
                return;
            }
            long latticeSeed = CloudNoise.hash(skySeed, salt);
            // Where the camera is in the lattice's frame, and how far a formation born anywhere could reach it from.
            double frameX = centreX - wind.steadyX() * tick;
            double frameZ = centreZ - wind.steadyZ() * tick;
            double range = reach + farthest + ownDrift * longestLife;
            long firstX = (long) Math.floor((frameX - range) / cellSize);
            long lastX = (long) Math.floor((frameX + range) / cellSize);
            long firstZ = (long) Math.floor((frameZ - range) / cellSize);
            long lastZ = (long) Math.floor((frameZ + range) / cellSize);
            long firstSlot = (long) Math.floor((tick - longestLife) / slotTicks);
            long lastSlot = (long) Math.floor(tick / slotTicks);
            for (long slot = firstSlot; slot <= lastSlot; slot++) {
                for (long cellZ = firstZ; cellZ <= lastZ; cellZ++) {
                    for (long cellX = firstX; cellX <= lastX; cellX++) {
                        long cellHash = CloudNoise.hash(latticeSeed, cellX, cellZ, slot);
                        int births = poisson(meanCount, CloudNoise.unit(cellHash));
                        for (int index = 0; index < births; index++) {
                            long formationHash = CloudNoise.hash(cellHash, index);
                            CloudFormation formation = make(formationHash, cellX, cellZ, slot, wind, baseHeight, totalWeight, presence);
                            if (formation != null && formation.aliveAt(tick) && near(formation, tick, centreX, centreZ, reach)) {
                                into.add(formation);
                            }
                        }
                    }
                }
            }
        }

        private @Nullable CloudFormation make(long hash, long cellX, long cellZ, long slot, CloudWind wind, float baseHeight,
                                    double totalWeight, Presence presence) {
            Draws draws = new Draws(hash);
            Recipe recipe = pick(draws.next() * totalWeight);
            float shown = presence.of(recipe.type);
            if (shown <= 0.0f) {
                return null;
            }
            double birth = (slot + draws.next()) * slotTicks;
            double life = FieldMath.lerp(recipe.shortestLife, recipe.longestLife, draws.next());
            double frameX = (cellX + draws.next()) * cellSize;
            double frameZ = (cellZ + draws.next()) * cellSize;
            double driftHeading = draws.next() * Math.PI * 2.0;
            double driftSpeed = draws.next() * ownDrift;
            double velocityX = wind.steadyX() + StrictMath.cos(driftHeading) * driftSpeed;
            double velocityZ = wind.steadyZ() + StrictMath.sin(driftHeading) * driftSpeed;
            // Log-uniform: a formation is as likely to be a few hundred blocks wide as a few thousand.
            double radius = recipe.smallestRadius * StrictMath.pow(recipe.largestRadius / recipe.smallestRadius, draws.next());
            float stretch = (float) FieldMath.lerp(1.0, recipe.mostStretch, draws.next() * draws.next());
            float angle = (float) (draws.next() * Math.PI * 2.0);
            float coverage = (float) FieldMath.lerp(recipe.leastCoverage, recipe.mostCoverage, draws.next());
            float sizeScale = (float) (recipe.smallestScale * StrictMath.pow(recipe.largestScale / recipe.smallestScale, draws.next()));
            float strength = (float) FieldMath.lerp(recipe.leastStrength, recipe.mostStrength, draws.next());
            float rainRate = (float) FieldMath.lerp(recipe.leastRain, recipe.mostRain, draws.next());
            CloudType.Heights heights = recipe.type.heights();
            float base = (float) (baseHeight + heights.base() + recipe.baseShift + (draws.next() * 2.0 - 1.0) * heights.baseVariation());
            // Heaped types have a ceiling of their own; a layer's top keeps its usual thickness over its base.
            float topDraw = (float) ((draws.next() * 2.0 - 1.0) * heights.topVariation());
            float top = recipe.type.puffs() != null
                ? baseHeight + heights.top() + topDraw
                : base + heights.top() - heights.base() + topDraw;
            Precipitation falls = recipe.type.precipitates() && rainRate > 0.0f ? Precipitation.BY_BIOME : Precipitation.NONE;
            return new CloudFormation(hash, recipe.type,
                frameX + wind.steadyX() * birth, frameZ + wind.steadyZ() * birth, velocityX, velocityZ,
                birth, life, recipe.growShare, recipe.shrinkShare, radius, stretch, angle, coverage,
                base, Math.max(top, base + 8.0f), sizeScale, strength, falls, rainRate, recipe.rainCoreShare, shown);
        }

        private Recipe pick(double draw) {
            double total = 0.0;
            for (Recipe recipe : recipes) {
                total += recipe.weight;
                if (draw < total) {
                    return recipe;
                }
            }
            return recipes.getLast();
        }

        private static boolean near(CloudFormation formation, double tick, double centreX, double centreZ, double reach) {
            double dx = formation.centreXAt(tick) - centreX;
            double dz = formation.centreZAt(tick) - centreZ;
            double limit = reach + farthestReach(formation.type(), formation.radius(), formation.stretch(), formation.sizeScale());
            return dx * dx + dz * dz <= limit * limit;
        }

        /** How many events a Poisson process with this mean gives, for a uniform draw. */
        private static int poisson(double mean, double draw) {
            double chance = StrictMath.exp(-mean);
            double below = chance;
            int count = 0;
            while (draw > below && count < 16) {
                count++;
                chance *= mean / count;
                below += chance;
            }
            return count;
        }
    }

    /** A sequence of independent uniform draws from one hash. */
    private static final class Draws {
        private long state;

        Draws(long seed) {
            this.state = seed;
        }

        double next() {
            state = CloudNoise.next(state);
            return CloudNoise.unit(state);
        }
    }
}
