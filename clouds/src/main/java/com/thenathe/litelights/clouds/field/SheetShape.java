package com.thenathe.litelights.clouds.field;

/**
 * A layer formation (stratus, nimbostratus, stratocumulus, altocumulus, cirrus): one sheet whose
 * holes close as the amount rises, thickest where it is most solid, with rounded heads on its top
 * and a ragged hanging base that rises and falls in long waves.
 */
final class SheetShape extends FormationShape {
    /**
     * How far the layer's base hangs below its usual height under its thickest parts, as a share of
     * the thickness, and at most in blocks (a rain deck a kilometre thick would otherwise lie on the floor).
     */
    private static final double HANGING = 0.03;
    private static final double MOST_HANGING = 10.0;
    /**
     * A storm's deck (CloudFormation.Edge.STORM) is one irregular mass, not a disc with things on
     * it (the owner, 1.1.8: "still kinda circle with stuff on top"): a low shelf of this share of
     * its thickness at its edge, sloping up to a range of merged towers whose highest part stands
     * {@link #STORM_PEAK_SHIFT} radii off its middle along its long axis; the towers are noise
     * {@link #STORM_TOWER_SIZE} short radii across that travels with the storm.
     */
    private static final double STORM_SHELF = 0.12;
    private static final double STORM_PEAK_SHIFT = 0.3;
    private static final double STORM_SLOPE_REACH = 1.45;
    private static final double STORM_TOWER_SIZE = 0.35;
    /** Vanilla's deck over the whole sky: its top rises and falls in hills and towers this wide (blocks), down to this share of its thickness. */
    private static final double DECK_HILL_SIZE = 900.0;
    private static final double DECK_LOWEST = 0.4;

    private final CloudType.Sheet sheet;
    private final SheetLayer layer;
    private final double thickness;
    private final double ragged;

    SheetShape(CloudFormation formation, double tick, long skySeed) {
        super(formation, tick, skySeed);
        CloudType.Sheet sheet = type.sheet();
        if (sheet == null) {
            throw new IllegalArgumentException(type + " is not a layer type");
        }
        this.sheet = sheet;
        this.layer = new SheetLayer(sheet, seed, formation.angle(), formation.sizeScale());
        // A deck thins as it fades with the weather, so it doesn't stand at full height over its holes;
        // it thickens a little more gently from nothing than a square root, so a deck a kilometre
        // thick doesn't shoot up in its first seconds (CloudFieldSmoothnessTest).
        this.thickness = (formation.top() - formation.base()) * (0.35 + 0.65 * StrictMath.pow(grown, 0.75));
        this.ragged = type.heights().raggedBottom();
    }

    @Override
    double reach() {
        return footprint.reach();
    }

    @Override
    void addTo(double cloudX, double cloudZ, double worldX, double worldZ, double detail, CloudColumn out) {
        double area = footprint.value(worldX, worldZ);
        if (area <= 0.0) {
            return;
        }
        double share = layer.share(cloudX, cloudZ, detail);
        double density = layer.density(share, amount * area);
        if (density <= 0.0) {
            return;
        }
        // A rounded shoulder at the edge: a quarter circle's height over the band inside it.
        double inside = layer.inside(share, amount * area);
        double shoulder = Math.sqrt(inside * (2.0 - inside));
        // Thin where it is thin: the layer comes down to nothing at a hole's edge, with no step.
        double solid = FieldMath.smoothstep(0.0, 1.0, density);
        double base = formation.base();
        double depth = thickness * layer.thickness(cloudX, cloudZ);
        if (formation.edge() == CloudFormation.Edge.STORM) {
            double fromPeak = footprint.fromPeak(worldX, worldZ, STORM_PEAK_SHIFT);
            double slope = 1.0 - FieldMath.smoothstep(0.0, STORM_SLOPE_REACH, fromPeak);
            double towers = 0.5 + 0.5 * footprint.noiseInArea(worldX, worldZ, STORM_TOWER_SIZE, 3, seed + 20);
            depth *= STORM_SHELF + (1.0 - STORM_SHELF) * slope * (0.35 + 0.65 * towers);
        } else if (formation.everywhere()) {
            double hills = 0.5 + 0.5 * CloudNoise.fractal(seed + 20, cloudX / DECK_HILL_SIZE, cloudZ / DECK_HILL_SIZE, 3, detail / DECK_HILL_SIZE);
            depth *= DECK_LOWEST + (1.0 - DECK_LOWEST) * hills;
        }
        double edge = FieldMath.smoothstep(0.0, 0.25, density) * (0.15 + 0.85 * shoulder);
        double top = base + depth * (0.3 + 0.7 * solid) * edge;
        // Rounded heads over the top, growing with the layer.
        top += depth * sheet.billows() * (layer.billows(cloudX, cloudZ, detail) - 0.4) * solid * edge;
        // A ragged fringe below, the thickest parts hanging a little lower than the thin ones, and
        // the whole base rising and falling in long waves (which stand still as the layer grows, so
        // a cell under a rising wave never fills and empties again).
        double bottom = base - (ragged * layer.ragged(cloudX, cloudZ, detail) + Math.min(HANGING * depth, MOST_HANGING)) * solid
            - sheet.baseWaves() * layer.baseWaves(cloudX, cloudZ);
        bottom = Math.min(bottom, top - 1.0);
        out.addSpan((float) bottom, (float) top, (float) density, (float) (storm * solid), type);
        addPrecipitation(worldX, worldZ, density, out);
    }
}
