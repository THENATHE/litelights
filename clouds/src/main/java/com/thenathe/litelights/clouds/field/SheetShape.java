package com.thenathe.litelights.clouds.field;

/**
 * A layer formation (stratus, nimbostratus, stratocumulus, altocumulus, cirrus): one sheet whose
 * holes close as the amount rises, thickest where it is most solid, with rounded heads on its top
 * and a ragged hanging base that rises and falls in long waves.
 */
final class SheetShape extends FormationShape {
    /** How far the layer's base hangs below its usual height under its thickest parts, as a share of the thickness. */
    private static final double HANGING = 0.03;

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
        // A deck thins as it fades with the weather, so it doesn't stand at full height over its holes.
        this.thickness = (formation.top() - formation.base()) * (0.35 + 0.65 * Math.sqrt(grown));
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
        double edge = FieldMath.smoothstep(0.0, 0.25, density) * (0.15 + 0.85 * shoulder);
        double top = base + depth * (0.3 + 0.7 * solid) * edge;
        // Rounded heads over the top, growing with the layer.
        top += depth * sheet.billows() * (layer.billows(cloudX, cloudZ, detail) - 0.4) * solid * edge;
        // A ragged fringe below, the thickest parts hanging a little lower than the thin ones, and
        // the whole base rising and falling in long waves (which stand still as the layer grows, so
        // a cell under a rising wave never fills and empties again).
        double bottom = base - (ragged * layer.ragged(cloudX, cloudZ, detail) + HANGING * depth) * solid
            - sheet.baseWaves() * layer.baseWaves(cloudX, cloudZ);
        bottom = Math.min(bottom, top - 1.0);
        out.addSpan((float) bottom, (float) top, (float) density, (float) (storm * solid), type);
        addPrecipitation(worldX, worldZ, density, out);
    }
}
