package com.thenathe.litelights.clouds.field;

/**
 * One storm of a weather mod's regional weather (Almanac's {@code Storm}), as plain numbers: an
 * ellipse born somewhere, growing, drifting in a straight line, holding, shrinking and gone, all of
 * it fixed at its birth, so its place and size at any tick follow from these alone. Times are the
 * weather mod's weather ticks; positions are in blocks.
 *
 * @param id             the same on every side for as long as it lives
 * @param birthX         its centre at {@code birthTick}
 * @param birthZ         the same, z
 * @param birthTick      the tick it is born at, with no size
 * @param lifeTicks      how long it lives
 * @param velocityX      how far its centre moves a tick
 * @param velocityZ      the same, z
 * @param radius         the radius of a circle of its full-grown area
 * @param stretch        how much longer it is than wide, 1 or more
 * @param angle          its long axis, in radians from +x towards +z
 * @param strength       how heavy its rain or snow is, 0..1
 * @param thunder        whether it is a thunderstorm
 * @param cloudTop       the block height of its cloud's top: nothing falls above it
 * @param growShare      the share of its life it takes to grow, and again to shrink at the end
 * @param rainCoreShare  how far out, as a share of its size, rain or snow falls
 * @param presence       how much of it to draw, 0..1: 1 for a storm that came as storms do, less
 *                       while one that appeared whole (an operator's {@code /weather}) or was
 *                       cleared away fades in or out
 */
public record RegionalStorm(long id, double birthX, double birthZ, double birthTick, double lifeTicks, double velocityX,
                            double velocityZ, double radius, double stretch, double angle, float strength, boolean thunder,
                            int cloudTop, double growShare, double rainCoreShare, float presence) {
    public RegionalStorm {
        if (!(lifeTicks > 0.0) || !(radius > 0.0) || stretch < 1.0 || growShare < 0.0 || growShare > 0.5) {
            throw new IllegalArgumentException("not a storm: life " + lifeTicks + ", radius " + radius + ", stretch " + stretch
                + ", grow share " + growShare);
        }
    }

    /** The same storm drawn at another presence. */
    public RegionalStorm withPresence(float shown) {
        return new RegionalStorm(id, birthX, birthZ, birthTick, lifeTicks, velocityX, velocityZ, radius, stretch, angle, strength,
            thunder, cloudTop, growShare, rainCoreShare, shown);
    }

    public double endTick() {
        return birthTick + lifeTicks;
    }

    public boolean aliveAt(double tick) {
        return tick >= birthTick && tick < endTick();
    }

    public double centreX(double tick) {
        return birthX + velocityX * (tick - birthTick);
    }

    public double centreZ(double tick) {
        return birthZ + velocityZ * (tick - birthTick);
    }

    /** Its size as a share of full grown: from 0 at an even pace, 1 while it holds, back to 0. */
    public double size(double tick) {
        if (!aliveAt(tick)) {
            return 0.0;
        }
        double phase = (tick - birthTick) / lifeTicks;
        double growing = growShare > 0.0 ? Math.min(1.0, phase / growShare) : 1.0;
        double shrinking = growShare > 0.0 ? Math.min(1.0, (1.0 - phase) / growShare) : 1.0;
        return Math.min(growing, shrinking);
    }

    /** The long semi-axis at full size, in blocks. */
    public double majorAxis() {
        return radius * Math.sqrt(stretch);
    }

    /** How far from its centre, in blocks, its full-grown cloud can reach. */
    public double extent() {
        return majorAxis();
    }
}
