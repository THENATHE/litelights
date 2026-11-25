package com.thenathe.litelights.clouds.field;

import java.util.ArrayList;
import java.util.List;

/**
 * The cloud field: given a sky ({@link CloudSky}), a place and a time, how much cloud is there and
 * between which heights. A pure function of its inputs: the same sky, place and tick always give
 * the same column, on any thread and any machine.
 *
 * <p>Columns are asked for in <em>cloud space</em>, which moves with the wind: a cloud's single
 * clouds sit still there, so between two ticks a column of cloud space only changes as its clouds
 * grow, shrink and come and go, never by the wind's motion. World space is cloud space plus
 * {@link #driftX}, {@link #driftZ}.
 *
 * <p>No cloud reaches below the sky's base height ({@link CloudSky#baseHeight}), in any weather:
 * it is the floor every type's heights are laid out above.
 *
 * <p>The field itself is immutable and shared. For work, make an {@link Area} for one tick and
 * rectangle: it keeps only the formations that can reach it and remembers the last clouds it
 * looked at, so an area belongs to one thread at a time.
 */
public final class CloudField {
    private final CloudSky sky;

    public CloudField(CloudSky sky) {
        this.sky = sky;
    }

    public CloudSky sky() {
        return sky;
    }

    /** How far the wind has carried cloud space along x by a tick: world x is cloud x plus this. */
    public double driftX(double tick) {
        return sky.wind().driftX(tick);
    }

    /** The same along z. */
    public double driftZ(double tick) {
        return sky.wind().driftZ(tick);
    }

    /** Readies the formations that reach a rectangle of cloud space at a tick. */
    public Area area(double tick, double minCloudX, double minCloudZ, double maxCloudX, double maxCloudZ) {
        return new Area(tick, minCloudX, minCloudZ, maxCloudX, maxCloudZ);
    }

    /** One column in world space, for tools and tests (it readies its own one-column area). */
    public void sampleWorld(double tick, double worldX, double worldZ, double detail, CloudColumn out) {
        double cloudX = worldX - driftX(tick);
        double cloudZ = worldZ - driftZ(tick);
        area(tick, cloudX, cloudZ, cloudX, cloudZ).column(cloudX, cloudZ, detail, out);
    }

    /** The field over one rectangle of cloud space at one tick. Not for sharing between threads. */
    public final class Area {
        private final double tick;
        private final double driftX;
        private final double driftZ;
        private final FormationShape[] shapes;

        private Area(double tick, double minCloudX, double minCloudZ, double maxCloudX, double maxCloudZ) {
            this.tick = tick;
            this.driftX = CloudField.this.driftX(tick);
            this.driftZ = CloudField.this.driftZ(tick);
            List<FormationShape> reaching = new ArrayList<>();
            for (CloudFormation formation : sky.formations()) {
                if (!formation.aliveAt(tick) || formation.presence() <= 0.0f || formation.coverage() <= 0.0f) {
                    continue;
                }
                FormationShape shape = formation.type().puffs() != null
                    ? new PuffShape(formation, tick, sky.seed(), driftX, driftZ)
                    : new SheetShape(formation, tick, sky.seed());
                if (!shape.empty() && shape.reaches(minCloudX + driftX, minCloudZ + driftZ, maxCloudX + driftX, maxCloudZ + driftZ)) {
                    reaching.add(shape);
                }
            }
            this.shapes = reaching.toArray(FormationShape[]::new);
        }

        public double tick() {
            return tick;
        }

        /** How many formations can reach this area. */
        public int formationCount() {
            return shapes.length;
        }

        /**
         * Fills {@code out} with one column of cloud space.
         *
         * @param detail the column's width in blocks (the cube size it will be drawn with): clouds
         *               and lumps much smaller than this are left out
         */
        public void column(double cloudX, double cloudZ, double detail, CloudColumn out) {
            out.reset();
            double worldX = cloudX + driftX;
            double worldZ = cloudZ + driftZ;
            for (FormationShape shape : shapes) {
                shape.addTo(cloudX, cloudZ, worldX, worldZ, detail, out);
            }
            out.floorAt(sky.baseHeight());
        }
    }
}
