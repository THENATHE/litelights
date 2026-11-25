package com.thenathe.litelights.clouds.field;

/**
 * A formation's area at one tick, in world space: an ellipse whose edge is pushed in and out by
 * noise that travels with it, fading from 1 inside to 0 at the edge.
 */
final class Footprint {
    /** How far the noise moves the edge, as a share of the distance from the centre. */
    private static final double EDGE_NOISE = 0.25;
    /** Inside this share of the (noisy) radius the area is at full strength (chosen by eye from the field's pictures). */
    private static final double FULL_INSIDE = 0.5;
    /** The noise never brings the edge farther out than this many radii. */
    static final double FARTHEST_EDGE = 1.0 / (1.0 - EDGE_NOISE);

    /*
     * A storm's area (CloudFormation.Edge.STORM) is whole over its rain core and fades to nothing
     * a little past the storm's own edge, so its cloud is solid where rain falls and its ragged
     * outline sits on the storm's edge. Its edge noise is small: the storm decides where it is.
     */
    private static final double STORM_EDGE_NOISE = 0.06;
    private static final double STORM_FADES_OUT_BY = 1.12;

    private final boolean everywhere;
    private final double centreX;
    private final double centreZ;
    private final double cos;
    private final double sin;
    private final double longAxis;
    private final double shortAxis;
    private final double noiseSize;
    private final long seed;
    /** Full strength inside this distance, nothing past {@code fadedBy}, in radii; the noise moves the edge by {@code edgeNoise}. */
    private final double fullInside;
    private final double fadedBy;
    private final double edgeNoise;
    private final double farthest;

    /**
     * @param scale grows or shrinks the ellipse about its centre (its life, or an anvil's spread)
     */
    Footprint(CloudFormation formation, double centreX, double centreZ, double scale, long seed) {
        this.everywhere = formation.everywhere();
        this.centreX = centreX;
        this.centreZ = centreZ;
        this.cos = StrictMath.cos(formation.angle());
        this.sin = StrictMath.sin(formation.angle());
        double stretchRoot = Math.sqrt(formation.stretch());
        double radius = everywhere ? 1.0 : Math.max(1.0, formation.radius() * scale);
        this.longAxis = radius * stretchRoot;
        this.shortAxis = radius / stretchRoot;
        this.noiseSize = radius * 0.45;
        this.seed = seed;
        boolean storm = formation.edge() == CloudFormation.Edge.STORM;
        this.fullInside = storm ? Math.min(formation.rainCoreShare(), STORM_FADES_OUT_BY - 0.05) : FULL_INSIDE;
        this.fadedBy = storm ? STORM_FADES_OUT_BY : 1.0;
        this.edgeNoise = storm ? STORM_EDGE_NOISE : EDGE_NOISE;
        this.farthest = fadedBy / (1.0 - edgeNoise);
    }

    boolean everywhere() {
        return everywhere;
    }

    double centreX() {
        return centreX;
    }

    double centreZ() {
        return centreZ;
    }

    /** The ellipse's short semi-axis, in blocks: how far its edge is from the centre at the nearest. */
    double shortAxis() {
        return everywhere ? Double.POSITIVE_INFINITY : shortAxis;
    }

    /** No point farther than this from the centre is inside, in blocks. */
    double reach() {
        return everywhere ? Double.POSITIVE_INFINITY : longAxis * farthest;
    }

    /** The area's strength at a world position, 0..1. */
    double value(double worldX, double worldZ) {
        if (everywhere) {
            return 1.0;
        }
        double dx = worldX - centreX;
        double dz = worldZ - centreZ;
        double along = dx * cos + dz * sin;
        double across = dz * cos - dx * sin;
        double distance = ellipseDistance(along, across);
        if (distance >= farthest) {
            return 0.0;
        }
        // The noise is read relative to the centre, so the ragged outline moves with the area.
        double noise = CloudNoise.fractal(seed, along / noiseSize, across / noiseSize, 2, 0.0);
        double edged = distance * (1.0 + edgeNoise * noise);
        return 1.0 - FieldMath.smoothstep(fullInside, fadedBy, edged);
    }

    /** The distance from the centre in radii of the plain ellipse: 1 on its edge. */
    double distance(double worldX, double worldZ) {
        if (everywhere) {
            return 0.0;
        }
        double dx = worldX - centreX;
        double dz = worldZ - centreZ;
        return ellipseDistance(dx * cos + dz * sin, dz * cos - dx * sin);
    }

    private double ellipseDistance(double along, double across) {
        double a = along / longAxis;
        double b = across / shortAxis;
        return Math.sqrt(a * a + b * b);
    }
}
