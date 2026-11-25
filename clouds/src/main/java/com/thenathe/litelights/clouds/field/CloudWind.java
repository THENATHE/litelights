package com.thenathe.litelights.clouds.field;

/**
 * The wind at cloud height, which carries every single cloud along. It is a steady part plus a
 * part that turns full circle once a period, so how far the wind has carried the clouds by any
 * tick has a closed form: every player works out the same drift from the game time alone, with
 * nothing to accumulate, and a later stage can draw the drift as an offset instead of rebuilding.
 *
 * @param steadyX     the steady part along x, in blocks per tick
 * @param steadyZ     the steady part along z, in blocks per tick
 * @param turningSpeed the turning part's speed, in blocks per tick
 * @param turnTicks   how long the turning part takes to go full circle, in ticks (above 0)
 * @param turnPhase   the turning part's heading at tick 0, in radians (x is its cosine)
 */
public record CloudWind(double steadyX, double steadyZ, double turningSpeed, double turnTicks, double turnPhase) implements CloudDrift {
    public static final CloudWind CALM = new CloudWind(0.0, 0.0, 0.0, 1.0, 0.0);

    public CloudWind {
        if (!(turnTicks > 0.0)) {
            throw new IllegalArgumentException("turnTicks must be above 0: " + turnTicks);
        }
    }

    /** How far the wind has carried the clouds along x since tick 0, in blocks. */
    @Override
    public double driftX(double tick) {
        double turnRate = Math.PI * 2.0 / turnTicks;
        double turned = turnPhase + Math.PI * 2.0 * fullTurnsFraction(tick);
        return steadyX * tick + turningSpeed / turnRate * (StrictMath.sin(turned) - StrictMath.sin(turnPhase));
    }

    /** How far the wind has carried the clouds along z since tick 0, in blocks. */
    @Override
    public double driftZ(double tick) {
        double turnRate = Math.PI * 2.0 / turnTicks;
        double turned = turnPhase + Math.PI * 2.0 * fullTurnsFraction(tick);
        return steadyZ * tick - turningSpeed / turnRate * (StrictMath.cos(turned) - StrictMath.cos(turnPhase));
    }

    /** The wind's velocity along x at a tick, in blocks per tick. */
    public double velocityX(double tick) {
        return steadyX + turningSpeed * StrictMath.cos(turnPhase + Math.PI * 2.0 * fullTurnsFraction(tick));
    }

    /** The wind's velocity along z at a tick, in blocks per tick. */
    public double velocityZ(double tick) {
        return steadyZ + turningSpeed * StrictMath.sin(turnPhase + Math.PI * 2.0 * fullTurnsFraction(tick));
    }

    /**
     * Where in its turn the turning part is, 0..1. The whole turns are dropped first: the sine of a
     * large angle loses precision, and a game a year old is past 600 million ticks.
     */
    private double fullTurnsFraction(double tick) {
        double turns = tick / turnTicks;
        return turns - Math.floor(turns);
    }
}
