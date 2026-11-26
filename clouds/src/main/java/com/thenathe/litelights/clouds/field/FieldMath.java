package com.thenathe.litelights.clouds.field;

/** Small maths for the cloud field, kept here so the package needs nothing else of LiteLights'. */
final class FieldMath {
    private FieldMath() {
    }

    /** GLSL's smoothstep: 0 below {@code edge0}, 1 above {@code edge1}, an S-curve between. */
    static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * The greater of two values, rounded off where they come within {@code softness} of each
     * other, so a shape made of two meeting where they cross has no crease along the meeting.
     */
    static double smoothMax(double a, double b, double softness) {
        double near = Math.max(softness - Math.abs(a - b), 0.0) / softness;
        return Math.max(a, b) + near * near * softness * 0.25;
    }

    /** The lesser of two values, rounded off like {@link #smoothMax}. */
    static double smoothMin(double a, double b, double softness) {
        return -smoothMax(-a, -b, softness);
    }

    static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    /** {@code value} to a whole power without {@link Math#pow}, whose bits may differ between machines. */
    static double power(double value, int exponent) {
        double result = 1.0;
        for (int i = 0; i < exponent; i++) {
            result *= value;
        }
        return result;
    }
}
