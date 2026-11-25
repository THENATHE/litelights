package com.thenathe.litelights.clouds.field;

import org.jspecify.annotations.Nullable;

/**
 * A heaped formation (cumulus, congestus, cumulonimbus): single clouds of many sizes, each with
 * its own place, size, height and life, riding the wind through the formation's area.
 *
 * <p>Sizes come in classes 2.5 times apart. Each class has its own lattice in cloud space, with
 * cells twice as wide as its largest cloud and its ragged outline together, so a cloud never
 * reaches past half a cell from its own and a column only has to look at the four cells nearest it. Each cell holds two overlapping
 * sequences of clouds, half a life apart: as one cloud fades another is growing, and every cloud
 * starts and ends at nothing, so the sky never changes in a step. A cloud shows only where the
 * formation's area, at its centre, is strong enough for it (a threshold of its own), so as an area
 * grows or passes over, clouds grow in and fade out whole instead of being cut.
 *
 * <p>Thunderstorms also have one core tower tied to the formation itself, which travels with the
 * storm rather than the wind, and an anvil spreading under the formation's top.
 */
final class PuffShape extends FormationShape {
    private static final double CLASS_RATIO = 2.5;
    /** How far the outline is pushed about, as a share of the class's largest radius. */
    private static final double WARP = 0.25;
    /** A cell is this many of its class's smallest radii across: twice the largest radius with its outline. */
    private static final double CELLS_PER_RADIUS = 2.0 * CLASS_RATIO * (1.0 + WARP);
    /** The share of a single cloud's life spent growing, and again shrinking. */
    private static final double CLOUD_GROW_SHARE = 0.3;
    /** How gradually a cloud appears as the area at its centre passes its threshold (chosen by eye from the field's pictures). */
    private static final double THRESHOLD_SOFTNESS = 0.16;
    /** How far one cloud's base may sit from another's, in blocks. */
    private static final double BASE_JITTER = 3.0;
    /** The size of the noise that frays the base, in blocks. */
    private static final double RAGGED_SIZE = 40.0;
    /** Where a cloud's base starts to curve up toward its rim, as a share of its radius. */
    private static final double RIM_START = 0.72;
    /**
     * In a storm (CloudFormation.Edge.STORM) a single cloud reaches no farther out than this, in
     * the storm's radii, so its towers end at the storm's edge at any size rather than hang past it.
     */
    private static final double STORM_CLOUDS_END = 1.05;

    private final CloudType.Puffs puffs;
    private final SizeClass[] classes;
    private final double ceiling;
    private final double heightScale;
    private final double lumpSize;
    private final double billowSize;
    private final double driftX;
    private final double driftZ;
    private final double tick;
    private final double reach;
    private final @Nullable Anvil anvil;
    private final @Nullable Tower coreTower;
    private final boolean stormEdge;

    // The column being added: its span so far, and its noise, read once and only if a cloud covers it.
    private double spanBottom;
    private double spanTop;
    private double spanDensity;
    private boolean noiseRead;
    private double lump;
    private double ragged;
    private double billows;

    PuffShape(CloudFormation formation, double tick, long skySeed, double driftX, double driftZ) {
        super(formation, tick, skySeed);
        CloudType.Puffs typePuffs = type.puffs();
        if (typePuffs == null) {
            throw new IllegalArgumentException(type + " is not a heaped type");
        }
        this.puffs = typePuffs;
        this.stormEdge = formation.edge() == CloudFormation.Edge.STORM;
        this.tick = tick;
        this.driftX = driftX;
        this.driftZ = driftZ;
        this.ceiling = formation.top() - formation.base();
        // Stronger storms build taller.
        this.heightScale = type.anvil() != null ? 0.55 + 0.45 * formation.strength() : 1.0;
        double smallest = puffs.smallestRadius() * formation.sizeScale();
        double largest = puffs.largestRadius() * formation.sizeScale();
        this.lumpSize = smallest * 0.9;
        this.billowSize = puffs.billowSize() * formation.sizeScale();
        int classCount = Math.max(1, (int) Math.ceil(StrictMath.log(largest / smallest) / StrictMath.log(CLASS_RATIO) - 1.0e-9));
        this.classes = new SizeClass[classCount];
        // The largest class shows a cloud in every cell it can; each smaller one in fewer, by the type's share.
        double classWeight = 1.0 / FieldMath.power(puffs.largerShare(), classCount - 1);
        for (int k = 0; k < classCount; k++) {
            double low = smallest * FieldMath.power(CLASS_RATIO, k);
            classes[k] = new SizeClass(k, low, Math.min(low * CLASS_RATIO, largest), classWeight);
            classWeight *= puffs.largerShare();
        }
        CloudType.Anvil typeAnvil = type.anvil();
        this.anvil = typeAnvil != null ? new Anvil(typeAnvil) : null;
        this.coreTower = typeAnvil != null ? coreTower(largest) : null;
        double farthest = footprint.reach() + largest * (1.0 + WARP);
        this.reach = anvil != null ? Math.max(farthest, anvil.footprint.reach() + anvil.offset) : farthest;
    }

    @Override
    double reach() {
        return reach;
    }

    @Override
    void addTo(double cloudX, double cloudZ, double worldX, double worldZ, double detail, CloudColumn out) {
        spanBottom = Double.POSITIVE_INFINITY;
        spanTop = Double.NEGATIVE_INFINITY;
        spanDensity = 0.0;
        noiseRead = false;

        for (SizeClass sizeClass : classes) {
            // Clouds smaller than the column can't be drawn at this distance.
            if (sizeClass.largest < detail * 0.6) {
                continue;
            }
            sizeClass.prepare(cellOf(cloudX, sizeClass.offsetX, sizeClass.spacing), cellOf(cloudZ, sizeClass.offsetZ, sizeClass.spacing));
            if (!sizeClass.anyNear(cloudX, cloudZ)) {
                continue;
            }
            double warpedX = cloudX + sizeClass.warpX(cloudX, cloudZ, detail);
            double warpedZ = cloudZ + sizeClass.warpZ(cloudX, cloudZ, detail);
            for (int i = 0; i < sizeClass.count; i++) {
                double dx = warpedX - sizeClass.centreX[i];
                double dz = warpedZ - sizeClass.centreZ[i];
                double radius = sizeClass.radius[i];
                double reachSquared = (dx * dx + dz * dz) / (radius * radius);
                if (reachSquared < 1.0) {
                    addCloud(cloudX, cloudZ, detail, reachSquared, formation.base() + sizeClass.baseShift[i],
                        sizeClass.height[i], sizeClass.solidity[i]);
                }
            }
        }

        if (coreTower != null) {
            double dx = cloudX - coreTower.x;
            double dz = cloudZ - coreTower.z;
            double reachSquared = (dx * dx + dz * dz) / (coreTower.radius * coreTower.radius);
            if (reachSquared < 1.0) {
                addCloud(cloudX, cloudZ, detail, reachSquared, formation.base(), coreTower.height, Math.min(1.0, grown * 3.0));
            }
        }

        if (spanDensity > 0.0) {
            out.addSpan((float) spanBottom, (float) spanTop, (float) spanDensity,
                (float) (storm * FieldMath.smoothstep(0.0, 0.5, spanDensity)), type);
            addPrecipitation(worldX, worldZ, spanDensity, out);
        }
        if (anvil != null) {
            anvil.addTo(cloudX, cloudZ, worldX, worldZ, detail, out);
        }
    }

    /**
     * Adds one cloud covering the column to the formation's span: a dome from its base, its top
     * lumpy, its base sagging a little in the middle and frayed in places.
     *
     * @param reachSquared the column's squared distance from the cloud's middle, in radii
     * @param solidity     how solid the cloud is for its age, 0..1
     */
    private void addCloud(double cloudX, double cloudZ, double detail, double reachSquared, double cloudBase, double height, double solidity) {
        if (!noiseRead) {
            noiseRead = true;
            lump = CloudNoise.fractal(seed + 3, cloudX / lumpSize, cloudZ / lumpSize, 3, detail / lumpSize);
            ragged = Math.max(0.0, CloudNoise.fractal(seed + 4, cloudX / RAGGED_SIZE, cloudZ / RAGGED_SIZE, 2, detail / RAGGED_SIZE));
            billows = CloudNoise.billows(seed + 12, cloudX, cloudZ, billowSize, detail);
        }
        double shape = dome(reachSquared);
        // Rounded heads crowd the top, fading out toward the cloud's edge so its outline stays its own.
        double heads = height * puffs.billows() * (billows - 0.4) * FieldMath.smoothstep(0.0, 0.5, shape);
        spanTop = Math.max(spanTop, cloudBase + height * shape * (1.0 + puffs.lumpiness() * lump) + heads);
        // A flat base that curves up into the cloud's side near its rim, so the side is rounded at the
        // bottom as the dome rounds it at the top.
        double rim = FieldMath.smoothstep(RIM_START, 1.0, Math.sqrt(reachSquared));
        spanBottom = Math.min(spanBottom, cloudBase - puffs.bottomSag() * height * (1.0 - reachSquared)
            - type.heights().raggedBottom() * ragged * shape + puffs.rimLift() * height * rim * rim);
        spanDensity = Math.max(spanDensity, FieldMath.smoothstep(0.0, 0.3, shape) * solidity);
    }

    /** A dome's height at a squared share of its radius: 1 in the middle, 0 at the edge, sloped (not sheer) there. */
    private double dome(double reachSquared) {
        int power = puffs.domePower();
        double edge = (power & 1) == 0
            ? FieldMath.power(reachSquared, power / 2)
            : Math.sqrt(reachSquared) * FieldMath.power(reachSquared, power / 2);
        return 1.0 - edge;
    }

    private static long cellOf(double position, double offset, double spacing) {
        // The two cells around the column's nearer half: a cloud reaches at most half a cell from its own.
        return (long) Math.floor((position - offset) / spacing - 0.5);
    }

    /** A cloud with no lattice cell: x and z in cloud space, radius and height in blocks. */
    private record Tower(double x, double z, double radius, double height) {
    }

    /**
     * The storm's own tower, tied to the formation's centre (which moves with the storm, not the
     * wind): about a third of its radius across, as tall as its strength and age allow.
     */
    private Tower coreTower(double largestRadius) {
        double radius = Math.clamp(formation.radius() * 0.35, puffs.smallestRadius() * formation.sizeScale(), largestRadius)
            * (0.4 + 0.6 * grown);
        if (stormEdge) {
            // A young storm is small: its tower stands inside it.
            radius = Math.min(radius, footprint.shortAxis() * 0.6);
        }
        return new Tower(footprint.centreX() - driftX, footprint.centreZ() - driftZ, radius, ceiling * heightScale * grown);
    }

    /** One size class of single clouds, with the clouds of the four cells last asked for kept. */
    private final class SizeClass {
        private static final int MOST_NEAR = 8;

        final double smallest;
        final double largest;
        final double spacing;
        final double weight;
        final long classSeed;
        final double offsetX;
        final double offsetZ;
        private final double warpSize;
        private final double warpAmount;

        private long cachedX = Long.MIN_VALUE;
        private long cachedZ = Long.MIN_VALUE;
        int count;
        final double[] centreX = new double[MOST_NEAR];
        final double[] centreZ = new double[MOST_NEAR];
        final double[] radius = new double[MOST_NEAR];
        final double[] height = new double[MOST_NEAR];
        final double[] baseShift = new double[MOST_NEAR];
        final double[] solidity = new double[MOST_NEAR];

        SizeClass(int index, double smallest, double largest, double weight) {
            this.smallest = smallest;
            this.largest = largest;
            this.spacing = smallest * CELLS_PER_RADIUS;
            this.weight = weight;
            this.classSeed = CloudNoise.hash(seed, 100 + index);
            // Each class's lattice starts somewhere else, so no grid lines are shared.
            this.offsetX = CloudNoise.unit(CloudNoise.hash(classSeed, 1)) * spacing;
            this.offsetZ = CloudNoise.unit(CloudNoise.hash(classSeed, 2)) * spacing;
            this.warpSize = largest * 1.2;
            this.warpAmount = largest * WARP;
        }

        /** Finds the clouds of the 2 by 2 cells from ({@code cellX}, {@code cellZ}), unless they are the last ones. */
        void prepare(long cellX, long cellZ) {
            if (cellX == cachedX && cellZ == cachedZ) {
                return;
            }
            cachedX = cellX;
            cachedZ = cellZ;
            count = 0;
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    for (int sequence = 0; sequence < 2; sequence++) {
                        addCloud(cellX + x, cellZ + z, sequence);
                    }
                }
            }
        }

        boolean anyNear(double cloudX, double cloudZ) {
            for (int i = 0; i < count; i++) {
                double dx = cloudX - centreX[i];
                double dz = cloudZ - centreZ[i];
                double near = radius[i] + warpAmount;
                if (dx * dx + dz * dz < near * near) {
                    return true;
                }
            }
            return false;
        }

        // Three layers of noise push the outline about, the finest a fifth of the cloud's size, so a
        // cloud seen from above is ragged rather than round.
        double warpX(double cloudX, double cloudZ, double detail) {
            return warpAmount * CloudNoise.fractal(classSeed + 5, cloudX / warpSize, cloudZ / warpSize, 3, detail / warpSize);
        }

        double warpZ(double cloudX, double cloudZ, double detail) {
            return warpAmount * CloudNoise.fractal(classSeed + 6, cloudX / warpSize, cloudZ / warpSize, 3, detail / warpSize);
        }

        /** The cloud of one cell's sequence at this tick, if it has one and it shows. */
        private void addCloud(long cellX, long cellZ, int sequence) {
            long cellHash = CloudNoise.hash(classSeed, cellX, cellZ);
            // Each cell keeps one life length, so its clouds follow each other with no gap or overlap.
            double life = FieldMath.lerp(puffs.shortestLife(), puffs.longestLife(), CloudNoise.unit(cellHash));
            double position = tick / life + CloudNoise.unit(CloudNoise.next(cellHash)) + sequence * 0.5;
            double slot = Math.floor(position);
            double phase = position - slot;
            long cloudHash = CloudNoise.hash(cellHash, sequence, (long) slot);
            if (CloudNoise.unit(cloudHash) >= weight) {
                return;
            }
            long jitterXHash = CloudNoise.next(cloudHash);
            long jitterZHash = CloudNoise.next(jitterXHash);
            long sizeHash = CloudNoise.next(jitterZHash);
            long heightHash = CloudNoise.next(sizeHash);
            long thresholdHash = CloudNoise.next(heightHash);
            long baseHash = CloudNoise.next(thresholdHash);
            double x = offsetX + (cellX + CloudNoise.unit(jitterXHash)) * spacing;
            double z = offsetZ + (cellZ + CloudNoise.unit(jitterZHash)) * spacing;

            double area = footprint.value(x + driftX, z + driftZ);
            // The threshold's soft band starts at 0 at the lowest, so where the area is nothing no cloud shows.
            double threshold = CloudNoise.unit(thresholdHash) * (1.0 - THRESHOLD_SOFTNESS);
            double shows = FieldMath.smoothstep(threshold, threshold + THRESHOLD_SOFTNESS, amount * area);
            double lifeShape = FieldMath.smoothstep(0.0, CLOUD_GROW_SHARE, phase)
                * FieldMath.smoothstep(0.0, CLOUD_GROW_SHARE, 1.0 - phase);
            double grow = lifeShape * shows;
            if (grow < 0.01) {
                return;
            }
            // Log-uniform within the class: as many clouds near its small end as its large end.
            double fullRadius = Math.min(smallest * StrictMath.pow(CLASS_RATIO, CloudNoise.unit(sizeHash)), largest);
            if (stormEdge) {
                fullRadius = Math.min(fullRadius, Math.max(smallest * 0.5,
                    (STORM_CLOUDS_END - footprint.distance(x + driftX, z + driftZ)) * footprint.shortAxis()));
            }
            double heightShare = FieldMath.lerp(puffs.leastHeight(), puffs.mostHeight(), CloudNoise.unit(heightHash));
            // Lower and flatter toward the area's edge, where the air favours the type less.
            double fullHeight = Math.min(fullRadius * heightShare * (0.6 + 0.4 * area) * heightScale, ceiling);

            int i = count++;
            centreX[i] = x;
            centreZ[i] = z;
            radius[i] = fullRadius * (0.3 + 0.7 * grow);
            height[i] = fullHeight * grow;
            // A new cloud starts at the formation's base and settles to its own as it grows.
            baseShift[i] = (CloudNoise.unit(baseHash) * 2.0 - 1.0) * BASE_JITTER * grow;
            solidity[i] = Math.min(1.0, grow * 3.0);
        }
    }

    /** A thunderstorm's anvil: a layer under the formation's top, spread wider and pushed along its long axis. */
    private final class Anvil {
        final Footprint footprint;
        final double offset;
        private final SheetLayer layer;
        private final double thickness;
        private final double amount;

        Anvil(CloudType.Anvil anvil) {
            double scale = (0.5 + 0.5 * formation.lifeAt(tick)) * anvil.spread();
            this.offset = formation.everywhere() ? 0.0 : formation.radius() * 0.5;
            double centreX = PuffShape.this.footprint.centreX() + StrictMath.cos(formation.angle()) * offset;
            double centreZ = PuffShape.this.footprint.centreZ() + StrictMath.sin(formation.angle()) * offset;
            this.footprint = new Footprint(formation, centreX, centreZ, scale, seed + 7);
            this.layer = new SheetLayer(anvil.sheet(), seed + 11, formation.angle(), formation.sizeScale());
            this.thickness = anvil.thickness();
            // The anvil stays at the storm's ceiling and spreads there as the tower grows into it.
            this.amount = 0.85 * heightScale * FieldMath.smoothstep(0.4, 1.0, grown);
        }

        void addTo(double cloudX, double cloudZ, double worldX, double worldZ, double detail, CloudColumn out) {
            double area = footprint.value(worldX, worldZ);
            if (area <= 0.0 || amount <= 0.0) {
                return;
            }
            double density = layer.density(cloudX, cloudZ, amount * area, detail);
            if (density <= 0.0) {
                return;
            }
            // Thickest and highest over the storm, thinning and sinking a little toward its edge.
            double solid = FieldMath.smoothstep(0.0, 1.0, density);
            double top = formation.base() + ceiling * heightScale - thickness * 0.3 * (1.0 - area);
            double depth = thickness * layer.thickness(cloudX, cloudZ) * (0.3 + 0.7 * solid) * (0.35 + 0.65 * area);
            out.addSpan((float) (top - depth * FieldMath.smoothstep(0.0, 0.25, density)), (float) top, (float) density, (float) (storm * solid), type);
        }
    }
}
