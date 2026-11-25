package com.thenathe.litelights.clouds.field;

/**
 * One formation made ready for one tick: its area placed and grown for its age, and what the
 * weather and its life allow folded into a few numbers. Each structure (layers, heaped clouds)
 * adds its own spans to a column.
 */
abstract class FormationShape {
    final CloudFormation formation;
    final CloudType type;
    final Footprint footprint;
    final long seed;
    /** The share of the area to cover now: coverage, life and weather together. */
    final double amount;
    /** How grown it is now, life and weather together, 0..1. */
    final double grown;
    final float storm;
    private final double rainRate;

    FormationShape(CloudFormation formation, double tick, long skySeed) {
        this.formation = formation;
        this.type = formation.type();
        this.seed = CloudNoise.hash(skySeed, formation.id());
        double life = formation.lifeAt(tick);
        boolean stormEdge = formation.edge() == CloudFormation.Edge.STORM;
        this.grown = life * formation.presence();
        // A storm is as covered over its rain core whatever its size, where it rains: only its area grows.
        this.amount = formation.coverage() * (stormEdge ? (life > 0.0 ? formation.presence() : 0.0) : grown);
        this.storm = formation.strength() * formation.presence();
        this.rainRate = formation.precipitationRate() * grown;
        // A formation grows out from its middle as well as filling in; a storm's area is the storm's size.
        double scale = stormEdge ? life : 0.5 + 0.5 * life;
        this.footprint = new Footprint(formation, formation.centreXAt(tick), formation.centreZAt(tick), scale, seed);
    }

    /** Nothing of it shows: no cloud to add. */
    boolean empty() {
        return amount <= 0.0;
    }

    /** No cloud reaches farther than this from the area's centre, in blocks. */
    abstract double reach();

    /** Whether any of its cloud can be inside a rectangle of world space. */
    boolean reaches(double minX, double minZ, double maxX, double maxZ) {
        if (footprint.everywhere()) {
            return true;
        }
        double reach = reach();
        double nearestX = Math.clamp(footprint.centreX(), minX, maxX);
        double nearestZ = Math.clamp(footprint.centreZ(), minZ, maxZ);
        double dx = nearestX - footprint.centreX();
        double dz = nearestZ - footprint.centreZ();
        return dx * dx + dz * dz <= reach * reach;
    }

    /**
     * Adds its spans for a column.
     *
     * @param cloudX the column in cloud space (where single clouds sit)
     * @param worldX the same column in world space (where the area is)
     * @param detail the column's width in blocks: detail finer than this is left out
     */
    abstract void addTo(double cloudX, double cloudZ, double worldX, double worldZ, double detail, CloudColumn out);

    /** Rain or snow under cloud of this density, inside the formation's rain core. */
    void addPrecipitation(double worldX, double worldZ, double density, CloudColumn out) {
        if (formation.precipitation() == Precipitation.NONE || rainRate <= 0.0) {
            return;
        }
        double core = formation.rainCoreShare();
        double inCore = 1.0 - FieldMath.smoothstep(core * 0.75, core, footprint.distance(worldX, worldZ));
        double rate = rainRate * inCore * FieldMath.smoothstep(0.25, 0.6, density);
        out.addPrecipitation(formation.precipitation(), (float) rate);
    }
}
