package com.thenathe.litelights.clouds.field;

/**
 * A formation: a large area of one cloud type with a place, a size, a life and a path. This is the
 * plain data either source fills: LiteLights' own sky for vanilla servers
 * ({@link VanillaWeatherSky}), or a weather mod that decides where the clouds are. Its place at any
 * tick is worked out from its birth, so a list stays right for as long as it lives.
 *
 * <p>The area is an ellipse in world space that travels in a straight line. The single clouds
 * inside it ride the wind ({@link CloudWind}) and drift through the area, so a formation is where
 * the air favours that type, and its clouds grow on one side and fade on the other.
 *
 * @param id                stable for the formation's whole life; seeds its single clouds and lumps
 * @param type              its cloud type
 * @param centreX           the area's centre at {@code birthTick}, x in world blocks
 * @param centreZ           the same, z
 * @param velocityX         how fast the area moves along x, in blocks per tick
 * @param velocityZ         the same, z
 * @param birthTick         the game tick it starts growing
 * @param lifeTicks         how long it lives, in ticks; infinite for one that lasts as long as the weather
 * @param growShare         the share of its life spent growing (0 for full size at once)
 * @param shrinkShare       the share of its life spent shrinking at the end
 * @param radius            the area's radius at full size, in blocks; infinite for a formation everywhere
 * @param stretch           the long axis over the short one, 1 or more; the area stays that of the radius
 * @param angle             the long axis's heading, in radians (x is its cosine)
 * @param coverage          how much of the area holds cloud at full size, 0..1
 * @param base              the usual cloud base, in world y
 * @param top               the ceiling (heaped types) or the usual top (layers), in world y
 * @param sizeScale         multiplies its type's single-cloud sizes, so formations of one type differ
 * @param strength          how stormy, 0..1: darker undersides, taller towers, heavier rain
 * @param precipitation     what falls from it
 * @param precipitationRate how hard at the heaviest, 0..1
 * @param rainCoreShare     the share of the radius that precipitates; the ring outside is cloud only
 * @param presence          how much of it the weather allows now, 0..1 (vanilla's rain fades its
 *                          decks in and out through this); 1 when the source has no such idea
 * @param edge              how its area grows and where it ends: LiteLights' own soft formations, or
 *                          a weather mod's storm, drawn where the storm is
 */
public record CloudFormation(
    long id,
    CloudType type,
    double centreX,
    double centreZ,
    double velocityX,
    double velocityZ,
    double birthTick,
    double lifeTicks,
    float growShare,
    float shrinkShare,
    double radius,
    float stretch,
    float angle,
    float coverage,
    float base,
    float top,
    float sizeScale,
    float strength,
    Precipitation precipitation,
    float precipitationRate,
    float rainCoreShare,
    float presence,
    Edge edge
) {
    /** How a formation's area grows and where it ends. */
    public enum Edge {
        /**
         * LiteLights' own: its life rises and falls smoothly, its area grows out from half its size,
         * is full strength inside about half its radius and frays out by its edge.
         */
        SOFT,
        /**
         * A weather mod's storm (Almanac's): its area grows from nothing and shrinks back at an even
         * pace over its grow and shrink shares, as the storm does, is whole over its rain core and
         * ends at the storm's edge, so the cloud is where the storm is at any tick.
         */
        STORM
    }

    /** A formation of LiteLights' own ({@link Edge#SOFT}). */
    public CloudFormation(long id, CloudType type, double centreX, double centreZ, double velocityX, double velocityZ,
                          double birthTick, double lifeTicks, float growShare, float shrinkShare, double radius, float stretch,
                          float angle, float coverage, float base, float top, float sizeScale, float strength,
                          Precipitation precipitation, float precipitationRate, float rainCoreShare, float presence) {
        this(id, type, centreX, centreZ, velocityX, velocityZ, birthTick, lifeTicks, growShare, shrinkShare, radius, stretch, angle,
            coverage, base, top, sizeScale, strength, precipitation, precipitationRate, rainCoreShare, presence, Edge.SOFT);
    }

    public CloudFormation {
        require(edge != null, "edge must be given");
        require(lifeTicks > 0.0, "lifeTicks must be above 0");
        require(radius > 0.0, "radius must be above 0");
        require(stretch >= 1.0f, "stretch must be 1 or more");
        require(top >= base, "top must not be under base");
        require(sizeScale > 0.0f, "sizeScale must be above 0");
        require(growShare >= 0.0f && shrinkShare >= 0.0f && growShare + shrinkShare <= 1.0f,
            "growShare and shrinkShare must be shares of the life that fit in it");
        require(inUnitRange(coverage) && inUnitRange(strength) && inUnitRange(precipitationRate)
            && inUnitRange(rainCoreShare) && inUnitRange(presence),
            "coverage, strength, precipitationRate, rainCoreShare and presence must be 0..1");
    }

    /** Whether it covers the whole sky (a deck over vanilla's world-wide rain). */
    public boolean everywhere() {
        return radius == Double.POSITIVE_INFINITY;
    }

    /** The area's centre at a tick, x in world blocks. */
    public double centreXAt(double tick) {
        return centreX + velocityX * (tick - birthTick);
    }

    /** The area's centre at a tick, z in world blocks. */
    public double centreZAt(double tick) {
        return centreZ + velocityZ * (tick - birthTick);
    }

    /** How far through its life it is: 0 at birth, 1 at its end. */
    public double phaseAt(double tick) {
        return (tick - birthTick) / lifeTicks;
    }

    /** Whether it exists at a tick. */
    public boolean aliveAt(double tick) {
        double phase = phaseAt(tick);
        return phase >= 0.0 && phase <= 1.0;
    }

    /**
     * How grown it is at a tick, 0..1: rising from 0 at birth, 1 while it holds, and back to 0 at
     * its end, with no step anywhere. Smoothly for LiteLights' own formations; at an even pace for a
     * storm, whose size this is exactly.
     */
    public float lifeAt(double tick) {
        double phase = phaseAt(tick);
        if (phase < 0.0 || phase > 1.0) {
            return 0.0f;
        }
        if (edge == Edge.STORM) {
            double growing = growShare > 0.0f ? Math.min(1.0, phase / growShare) : 1.0;
            double shrinking = shrinkShare > 0.0f ? Math.min(1.0, (1.0 - phase) / shrinkShare) : 1.0;
            return (float) Math.min(growing, shrinking);
        }
        double grown = growShare > 0.0f ? smoothstep(phase / growShare) : 1.0;
        double left = shrinkShare > 0.0f ? smoothstep((1.0 - phase) / shrinkShare) : 1.0;
        return (float) (grown * left);
    }

    private static double smoothstep(double t) {
        double x = Math.clamp(t, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    private static boolean inUnitRange(float value) {
        return value >= 0.0f && value <= 1.0f;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
