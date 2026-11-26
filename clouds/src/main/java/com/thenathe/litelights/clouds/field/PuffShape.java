package com.thenathe.litelights.clouds.field;

import org.jspecify.annotations.Nullable;

/**
 * A heaped formation (cumulus, congestus, cumulonimbus): single clouds of many sizes, each with
 * its own place, size, height and life, riding the wind through the formation's area.
 *
 * <p>Sizes come in classes 2.5 times apart. Each class has its own lattice in cloud space, with
 * cells wider than its largest cloud and its ragged outline together, so a cloud never reaches past
 * the next cell and a column only has to look at the nine cells round it; fair-weather clouds sit
 * on a closer lattice than a storm's, so there are more of them. Each cell holds two overlapping
 * sequences of clouds, half a life apart: as one cloud fades another is growing, and every cloud
 * starts and ends at nothing, so the sky never changes in a step. A cloud shows only where the
 * formation's area, at its centre, is strong enough for it (a threshold of its own), so as an area
 * grows or passes over, clouds grow in and fade out whole instead of being cut.
 *
 * <p>A single cloud is not one round heap: it is a main mass, off its middle and stretched one way,
 * with a chain of lower masses trailing off the other way, bending as it goes, some of them sitting
 * higher as shelves, each of its own size (the owner, after 1.1.4: "the clouds are too rounded
 * still, they need to be more "abstract shapes""). The masses join with rounded seams.
 *
 * <p>A thunderstorm is one mass instead (the user, 2026-11-25: "the tower is only slightly less
 * circumfrence than the bulbous cloud at the bottom"): no single clouds, only its core tower,
 * nearly as wide as the formation, tied to the formation itself so it travels with the storm
 * rather than the wind, and an anvil spreading under the formation's top.
 */
final class PuffShape extends FormationShape {
    private static final double CLASS_RATIO = 2.5;
    /**
     * How far heaped clouds depart from round heaps, 0 to 1: how many clouds take a named form instead
     * of a lopsided heap (towers, anvils, boots, arches, chains, crowns, leaning stacks, rings, torn
     * clusters; about a sixth at 0.3, seven in ten at 0.6, nearly all at 0.9) and how far their
     * heights and gaps are pulled apart. The calibration pictures were taken at 0.3, 0.6 and 0.9
     * ({@code -Dlitelights.clouds.shapes}); the game uses the default.
     */
    static final double SHAPES = Double.parseDouble(System.getProperty("litelights.clouds.shapes", "0.6"));
    /**
     * How far the outline is pushed about, as a share of the class's largest radius, over the
     * cloud's whole width and half of it: more the stronger {@link #SHAPES} is, so outlines lean
     * and bend (0.25 until 1.1.4).
     */
    private static final double WARP = 0.25 + 0.3 * SHAPES;
    // (EXTENT + WARP) times 2.5 must stay within FAIR_SPACING times CELLS_PER_RADIUS: 4.8 against 4.875 at full strength.
    /** How far a cloud's masses may reach from its middle, as a share of its size (its radius as a round heap). */
    private static final double EXTENT = 1.4;
    /**
     * A cell is this many of its class's smallest radii across, as in 1.1.4 (twice its largest cloud
     * with the outline pushed a quarter of it), so the number of clouds doesn't follow the warp. A
     * cloud with its masses and warp must stay within one cell of its own, which the nine-cell
     * lookup covers: (EXTENT + WARP) times the largest radius, at most 4.8 smallest radii at full
     * strength, against FAIR_SPACING of this, 4.9.
     */
    private static final double CELLS_PER_RADIUS = 2.0 * CLASS_RATIO * 1.25;
    /**
     * Fair-weather clouds' cells are this share of that across (about 1.6 times as many clouds; the
     * owner: "the clouds that are not apart of weather systems need to be more numerous"). A cloud
     * then reaches past half its cell but not past the next, which the nine-cell lookup covers.
     */
    private static final double FAIR_SPACING = 0.78;
    /**
     * The layouts are drawn in shares of the cloud's size; each mass is this much bigger again, so a
     * cloud of a few masses with gaps covers about as much sky as 1.1.4's round heap of its size.
     */
    private static final double MASS_SCALE = 1.6;
    /** A tower's squared reach is raised to this before the dome: 2/3 turns the heaps' dome of power 3 into a paraboloid. */
    private static final double TOWER_ROUNDING = 2.0 / 3.0;
    /** No mass stands more than this many times as tall as it is wide. */
    private static final double SLENDEREST = 2.0;
    /** A mass's top stays under this share of the formation's ceiling, leaving room for its lumps and heads. */
    private static final double TALLEST = 0.8;
    /** At most this many masses make one cloud. */
    private static final int MOST_MASSES = 6;
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
    /** How softly puffs' tops join, as a share of the newer puff's height. */
    private static final double SEAM_SOFTNESS = 0.25;
    /**
     * How softly their bases join, in blocks: a cloud's masses share one base, and a rounded seam
     * dips below the lower of two equal values by a quarter of its width each time, so a wide one
     * sank the base of a cloud of several masses toward the floor.
     */
    private static final double BASE_SEAM = 6.0;
    /**
     * In a storm (CloudFormation.Edge.STORM) a single cloud reaches no farther out than this, in
     * the storm's radii, so its towers end at the storm's edge at any size rather than hang past it.
     */
    private static final double STORM_CLOUDS_END = 1.05;
    /** A thunderstorm's core tower is this share of its formation's radius across, and of its short axis while young. */
    private static final double CORE_TOWER_SHARE = 0.62;
    /** The core tower stands this many radii off the storm's middle along its long axis, where the deck builds highest (SheetShape). */
    private static final double CORE_TOWER_SHIFT = 0.3;
    /** Lesser turrets on the core tower's shoulders: how many, how far out (in its radii), how wide and how tall against it. */
    private static final int SHOULDER_TURRETS = 4;
    private static final double SHOULDER_FROM = 0.75;
    private static final double SHOULDER_SIZE = 0.55;

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
    private final Tower[] coreTowers;
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
        // A thunderstorm is its core tower alone, with no smaller clouds standing about it.
        int classCount = type.anvil() != null ? 0
            : Math.max(1, (int) Math.ceil(StrictMath.log(largest / smallest) / StrictMath.log(CLASS_RATIO) - 1.0e-9));
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
        this.coreTowers = typeAnvil != null ? coreTowers(largest) : new Tower[0];
        double farthest = footprint.reach() + largest * (EXTENT + WARP);
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
                double extent = sizeClass.radius[i] * EXTENT;
                if (dx * dx + dz * dz >= extent * extent) {
                    continue;
                }
                for (int m = 0; m < sizeClass.masses[i]; m++) {
                    int mass = i * MOST_MASSES + m;
                    double scale = sizeClass.radius[i];
                    double u = dx / scale - sizeClass.massX[mass];
                    double v = dz / scale - sizeClass.massZ[mass];
                    double along = u * sizeClass.massCos[mass] + v * sizeClass.massSin[mass];
                    double across = v * sizeClass.massCos[mass] - u * sizeClass.massSin[mass];
                    double longRadius = sizeClass.massLong[mass];
                    double shortRadius = sizeClass.massShort[mass];
                    double reachSquared = along * along / (longRadius * longRadius) + across * across / (shortRadius * shortRadius);
                    if (reachSquared < 1.0) {
                        if (sizeClass.massHeight[mass] > 1.05 || sizeClass.massLift[mass] > 0.3) {
                            // A tower's or a lifted shelf's sides slope like a paraboloid's rather than the
                            // heap's steeper dome, so its flank isn't a sheer wall on which the map's columns
                            // show as streaks, nor a shelf a flat-topped box.
                            reachSquared = StrictMath.pow(reachSquared, TOWER_ROUNDING);
                        }
                        double lift = sizeClass.massLift[mass] * sizeClass.height[i];
                        // A tower may stand taller than its cloud's own height, but its lumps and heads
                        // must still end within the type's levels; and no mass stands more than
                        // SLENDEREST times as tall as it is wide, so nothing is a thin upright sliver.
                        double massHeight = Math.min(sizeClass.height[i] * sizeClass.massHeight[mass],
                            Math.min(Math.max(TALLEST * ceiling - lift, 1.0), SLENDEREST * 2.0 * shortRadius * scale));
                        addCloud(cloudX, cloudZ, detail, reachSquared,
                            formation.base() + sizeClass.baseShift[i] + lift, massHeight,
                            sizeClass.solidity[i]);
                    }
                }
            }
        }

        for (Tower tower : coreTowers) {
            double dx = cloudX - tower.x;
            double dz = cloudZ - tower.z;
            double reachSquared = (dx * dx + dz * dz) / (tower.radius * tower.radius);
            if (reachSquared < 1.0) {
                addCloud(cloudX, cloudZ, detail, reachSquared, formation.base(), tower.height, Math.min(1.0, grown * 3.0));
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
        // Puffs join with rounded seams: a plain greater-of left a crease where one puff's dome took
        // over from the next, upright through the cloud's whole side, which read as a straight line.
        double seam = Math.max(height * SEAM_SOFTNESS, 1.0);
        spanTop = FieldMath.smoothMax(spanTop, cloudBase + height * shape * (1.0 + puffs.lumpiness() * lump) + heads, seam);
        // A flat base that curves up into the cloud's side near its rim, so the side is rounded at the
        // bottom as the dome rounds it at the top.
        double rim = FieldMath.smoothstep(RIM_START, 1.0, Math.sqrt(reachSquared));
        spanBottom = FieldMath.smoothMin(spanBottom, cloudBase - puffs.bottomSag() * height * (1.0 - reachSquared)
            - type.heights().raggedBottom() * ragged * shape + puffs.rimLift() * height * rim * rim, BASE_SEAM);
        // Densities join as overlapping veils do, which is as smooth and still nothing where neither puff reaches.
        double density = FieldMath.smoothstep(0.0, 0.3, shape) * solidity;
        spanDensity = 1.0 - (1.0 - spanDensity) * (1.0 - density);
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
        // The cell holding the column; it and its eight neighbours hold every cloud that can reach it.
        return (long) Math.floor((position - offset) / spacing);
    }

    /** A cloud with no lattice cell: x and z in cloud space, radius and height in blocks. */
    private record Tower(double x, double z, double radius, double height) {
    }

    /**
     * The storm's own tower, tied to the formation's centre (which moves with the storm, not the
     * wind), off its middle where the deck builds highest, as tall as its strength and age allow,
     * with lesser turrets of different heights grown onto its shoulders so it rises out of the mass
     * rather than standing on it.
     */
    private Tower[] coreTowers(double largestRadius) {
        double radius = Math.clamp(formation.radius() * CORE_TOWER_SHARE, puffs.smallestRadius() * formation.sizeScale(), largestRadius)
            * (0.4 + 0.6 * grown);
        if (stormEdge) {
            // A young storm is small: its tower stands inside it.
            radius = Math.min(radius, footprint.shortAxis() * CORE_TOWER_SHARE);
        }
        double shift = formation.everywhere() ? 0.0 : formation.radius() * Math.sqrt(formation.stretch()) * CORE_TOWER_SHIFT * formation.lifeAt(tick);
        double x = footprint.centreX() - driftX + StrictMath.cos(formation.angle()) * shift;
        double z = footprint.centreZ() - driftZ + StrictMath.sin(formation.angle()) * shift;
        double height = ceiling * heightScale * grown;
        Tower[] towers = new Tower[1 + SHOULDER_TURRETS];
        towers[0] = new Tower(x, z, radius, height);
        long turretState = CloudNoise.hash(seed, 0x7055L);
        double angle = formation.angle();
        for (int j = 1; j <= SHOULDER_TURRETS; j++) {
            turretState = CloudNoise.next(turretState);
            double jitter = CloudNoise.unit(turretState);
            angle += 2.0 * Math.PI / SHOULDER_TURRETS * (0.7 + 0.6 * jitter);
            double from = radius * SHOULDER_FROM * (0.8 + 0.4 * jitter);
            towers[j] = new Tower(x + StrictMath.cos(angle) * from, z + StrictMath.sin(angle) * from,
                radius * SHOULDER_SIZE * (0.8 + 0.4 * jitter), height * (0.3 + 0.35 * jitter));
        }
        return towers;
    }

    /** One size class of single clouds, with the clouds of the nine cells last asked for kept. */
    private final class SizeClass {
        private static final int MOST_NEAR = 18;

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
        // Each cloud's masses, MOST_MASSES to a cloud, in shares of its size: where each sits from the
        // cloud's middle, its long and short radii and which way the long one points, how tall it is
        // against the cloud, and how far its base stands above the cloud's.
        final int[] masses = new int[MOST_NEAR];
        private long shapeState;
        private int massFirst;
        private int massCount;
        final double[] massX = new double[MOST_NEAR * MOST_MASSES];
        final double[] massZ = new double[MOST_NEAR * MOST_MASSES];
        final double[] massLong = new double[MOST_NEAR * MOST_MASSES];
        final double[] massShort = new double[MOST_NEAR * MOST_MASSES];
        final double[] massCos = new double[MOST_NEAR * MOST_MASSES];
        final double[] massSin = new double[MOST_NEAR * MOST_MASSES];
        final double[] massHeight = new double[MOST_NEAR * MOST_MASSES];
        final double[] massLift = new double[MOST_NEAR * MOST_MASSES];

        SizeClass(int index, double smallest, double largest, double weight) {
            this.smallest = smallest;
            this.largest = largest;
            this.spacing = smallest * CELLS_PER_RADIUS * (stormEdge ? 1.0 : FAIR_SPACING);
            this.weight = weight;
            this.classSeed = CloudNoise.hash(seed, 100 + index);
            // Each class's lattice starts somewhere else, so no grid lines are shared.
            this.offsetX = CloudNoise.unit(CloudNoise.hash(classSeed, 1)) * spacing;
            this.offsetZ = CloudNoise.unit(CloudNoise.hash(classSeed, 2)) * spacing;
            this.warpSize = largest * 1.2;
            this.warpAmount = largest * WARP;
        }

        /** Finds the clouds of the 3 by 3 cells round ({@code cellX}, {@code cellZ}), unless they are the last ones. */
        void prepare(long cellX, long cellZ) {
            if (cellX == cachedX && cellZ == cachedZ) {
                return;
            }
            cachedX = cellX;
            cachedZ = cellZ;
            count = 0;
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
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
                double near = radius[i] * EXTENT + warpAmount;
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
            layOutMasses(i, CloudNoise.next(baseHash));
        }

        /**
         * The cloud's masses, its features, before any fluff: the soft lumpy surface the shader
         * gives every heaped cloud goes on whatever these are, so the layout is drawn to survive it:
         * a few big masses rather than many small ones, real gaps where sky should show through,
         * and heights far apart. Some clouds are lopsided heaps of two or three distinct masses; the
         * rest, more of them and more extreme the stronger {@link #SHAPES} is, take a form with a
         * name: a tower on a skirt, an anvil, a boot, an arch, a chain, a crown, a leaning stack, a
         * ring, a torn cluster. Everything stays within EXTENT of the middle.
         */
        private void layOutMasses(int cloud, long shapeHash) {
            shapeState = shapeHash;
            massFirst = cloud * MOST_MASSES;
            massCount = 0;
            double axis = 2.0 * Math.PI * random();
            // How far odd forms go: heights and gaps pulled further apart the stronger the dial.
            double extreme = 0.5 + SHAPES + 2.0 * Math.max(0.0, SHAPES - 0.6);
            double oddShare = Math.clamp((SHAPES - 0.2) * 1.75, 0.0, 0.97);
            if (random() >= oddShare) {
                heap(axis, extreme);
            } else {
                double pick = random();
                if (pick < 0.12) {
                    tower(axis, extreme);
                } else if (pick < 0.24) {
                    anvil(axis, extreme);
                } else if (pick < 0.35) {
                    boot(axis, extreme);
                } else if (pick < 0.46) {
                    arch(axis, extreme);
                } else if (pick < 0.58) {
                    chain(axis, extreme);
                } else if (pick < 0.69) {
                    crown(axis, extreme);
                } else if (pick < 0.80) {
                    stack(axis, extreme);
                } else if (pick < 0.88) {
                    ring(axis, extreme);
                } else {
                    torn(extreme);
                }
            }
            // Past 0.6 the dial makes some clouds strange: a second feature on top of the first.
            if (random() < 2.0 * (SHAPES - 0.6)) {
                oddity(axis + Math.PI * (0.5 + random()), extreme);
            }
            masses[cloud] = massCount;
        }

        /** Something strange added to a cloud: a mass floating above one side, a high turret, or a long low streamer. */
        private void oddity(double side, double extreme) {
            double cos = StrictMath.cos(side);
            double sin = StrictMath.sin(side);
            double pick = random();
            if (pick < 0.35) {
                mass(cos * 0.6, sin * 0.6, 0.38, 0.3, side, 0.35, 0.7 + 0.3 * extreme);
            } else if (pick < 0.7) {
                mass(cos * 0.55, sin * 0.55, 0.28, 0.26, side, 0.8 + 0.5 * extreme, 0.0);
            } else {
                mass(cos * 0.7, sin * 0.7, 0.7, 0.14, side, 0.25, 0.05);
            }
        }

        /** A lopsided heap: a main mass off the middle with one or two distinct masses beside it, lower and of other sizes. */
        private void heap(double side, double extreme) {
            double cos = StrictMath.cos(side);
            double sin = StrictMath.sin(side);
            double main = 0.62 + 0.15 * random();
            mass(cos * 0.25, sin * 0.25, main * (1.0 + 0.3 * random()), main, side + random(), 1.0, 0.0);
            int others = 1 + (int) Math.floor(2.0 * random());
            double angle = side + Math.PI + (random() - 0.5);
            for (int j = 0; j < others; j++) {
                double radius = main * (0.35 + 0.3 * random());
                double from = 0.25 + main * 0.6 + radius * 0.7;
                mass(StrictMath.cos(angle) * from, StrictMath.sin(angle) * from, radius * 1.2, radius, angle, 0.75 / extreme * (0.6 + 0.4 * random()),
                    0.0);
                angle += (random() < 0.5 ? -1.0 : 1.0) * (0.8 + 0.6 * random());
            }
        }

        /** A tall tower standing off the middle of a low, wide skirt. */
        private void tower(double side, double extreme) {
            mass(0.0, 0.0, 1.15, 0.8, side + Math.PI / 2.0, 0.35 / extreme, 0.0);
            mass(StrictMath.cos(side) * 0.35, StrictMath.sin(side) * 0.35, 0.5, 0.45, side, 1.0 + 0.6 * extreme, 0.0);
        }

        /** A tower with a wide, thin shelf spread from its top, overhanging one way. */
        private void anvil(double side, double extreme) {
            mass(0.0, 0.0, 0.5, 0.45, side, 0.9 + 0.3 * extreme, 0.0);
            double out = 0.3 + 0.1 * extreme;
            mass(StrictMath.cos(side) * out, StrictMath.sin(side) * out, 0.95, 0.6, side, 0.45, 0.7 + 0.3 * extreme);
        }

        /** A tall heel at one end and a long, low toe reaching away from it. */
        private void boot(double side, double extreme) {
            double cos = StrictMath.cos(side);
            double sin = StrictMath.sin(side);
            mass(-cos * 0.6, -sin * 0.6, 0.55, 0.45, side, 1.0 + 0.4 * extreme, 0.0);
            mass(cos * 0.2, sin * 0.2, 1.05, 0.42, side, 0.32 / extreme, 0.0);
        }

        /** Two masses with sky between them and a thin bridge across, lifted so the sky shows under it. */
        private void arch(double side, double extreme) {
            double cos = StrictMath.cos(side);
            double sin = StrictMath.sin(side);
            double apart = 0.65 + 0.15 * extreme;
            mass(-cos * apart, -sin * apart, 0.45, 0.4, side, 1.0, 0.0);
            mass(cos * apart, sin * apart, 0.4, 0.36, side, 0.75, 0.0);
            mass(0.0, 0.0, apart, 0.22, side, 0.3, 0.45 + 0.15 * extreme);
        }

        /** Three or four separate masses in a bending row, sky between each, heights up and down. */
        private void chain(double side, double extreme) {
            int count = 3 + (int) Math.floor(2.0 * random());
            double heading = side;
            double radius = 0.34;
            double x = -StrictMath.cos(side) * 0.85;
            double z = -StrictMath.sin(side) * 0.85;
            for (int j = 0; j < count; j++) {
                double size = radius * (0.75 + 0.5 * random());
                mass(x, z, size * 1.1, size, heading, j % 2 == 0 ? 0.9 + 0.2 * extreme * random() : 0.45 / extreme, 0.0);
                heading += (random() - 0.5) * 0.9;
                double step = size * 2.0 + 0.08 * extreme;
                x += StrictMath.cos(heading) * step;
                z += StrictMath.sin(heading) * step;
            }
        }

        /** A low base with three turrets of very different heights standing up out of it. */
        private void crown(double side, double extreme) {
            mass(0.0, 0.0, 1.0, 0.75, side, 0.4 / extreme, 0.0);
            for (int j = 0; j < 3; j++) {
                double angle = side + j * 2.0 * Math.PI / 3.0 + (random() - 0.5) * 0.6;
                double tall = j == 0 ? 1.0 + 0.6 * extreme : (j == 1 ? 0.7 + 0.3 * extreme : 0.55);
                mass(StrictMath.cos(angle) * 0.5, StrictMath.sin(angle) * 0.5, 0.3, 0.27, angle, tall, 0.0);
            }
        }

        /** Three masses each sitting higher and further over than the last: a cloud leaning one way. */
        private void stack(double side, double extreme) {
            double cos = StrictMath.cos(side);
            double sin = StrictMath.sin(side);
            mass(-cos * 0.45, -sin * 0.45, 0.6, 0.5, side, 0.55, 0.0);
            mass(cos * 0.05, sin * 0.05, 0.5, 0.42, side, 0.55, 0.35 + 0.1 * extreme);
            mass(cos * (0.45 + 0.1 * extreme), sin * (0.45 + 0.1 * extreme), 0.42, 0.36, side, 0.5, 0.75 + 0.25 * extreme);
        }

        /** Four or five masses round an empty middle, one left out: a cloud with a hole, notched open on one side. */
        private void ring(double side, double extreme) {
            int count = 5;
            for (int j = 1; j < count; j++) {
                double angle = side + 2.0 * Math.PI * j / count;
                mass(StrictMath.cos(angle) * 0.75, StrictMath.sin(angle) * 0.75, 0.42, 0.3, angle + Math.PI / 2.0,
                    j == 2 ? 1.0 + 0.3 * extreme : 0.55 + 0.25 * random(), 0.0);
            }
        }

        /** A torn cluster: masses of very different sizes, apart, at different heights. */
        private void torn(double extreme) {
            int count = 4 + (int) Math.floor(2.0 * random());
            for (int j = 0; j < count; j++) {
                double angle = 2.0 * Math.PI * random();
                double from = 0.9 * Math.sqrt(random());
                double radius = j == 0 ? 0.45 : 0.15 + 0.2 * random();
                mass(StrictMath.cos(angle) * from, StrictMath.sin(angle) * from, radius * 1.3, radius, angle, j == 0 ? 1.0 : 0.35 + 0.5 * random(),
                    random() < 0.3 ? 0.15 * extreme : 0.0);
            }
        }

        private double random() {
            shapeState = CloudNoise.next(shapeState);
            return CloudNoise.unit(shapeState);
        }

        /**
         * Adds a mass, shrunk to stay within EXTENT of the middle; false (and nothing added) once the
         * cloud has all it can hold or the mass would be too small to show.
         */
        private boolean mass(double x, double z, double longRadius, double shortRadius, double axis, double tallShare, double lift) {
            if (massCount >= MOST_MASSES) {
                return false;
            }
            double from = Math.sqrt(x * x + z * z);
            double room = EXTENT - from;
            if (room < 0.1) {
                return false;
            }
            double scale = Math.min(MASS_SCALE, room / longRadius);
            setMass(massFirst + massCount, x, z, longRadius * scale, shortRadius * scale, axis, tallShare, lift);
            massCount++;
            return true;
        }

        private void setMass(int mass, double x, double z, double longRadius, double shortRadius, double axis, double tallShare, double lift) {
            massX[mass] = x;
            massZ[mass] = z;
            massLong[mass] = Math.max(longRadius, 0.05);
            massShort[mass] = Math.max(Math.min(shortRadius, longRadius), 0.05);
            massCos[mass] = StrictMath.cos(axis);
            massSin[mass] = StrictMath.sin(axis);
            massHeight[mass] = tallShare;
            massLift[mass] = lift;
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
            out.addSpan((float) (top - depth * FieldMath.smoothstep(0.0, 0.25, density)), (float) top, (float) density, (float) (storm * solid), type,
                true);
        }
    }
}
