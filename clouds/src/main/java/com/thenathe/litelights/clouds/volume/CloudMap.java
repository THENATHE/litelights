package com.thenathe.litelights.clouds.volume;

import com.thenathe.litelights.clouds.field.CloudColumn;
import com.thenathe.litelights.clouds.field.CloudField;
import com.thenathe.litelights.clouds.field.CloudType;
import java.util.Arrays;

/**
 * The cloud field written out as maps of columns, for the volumetric clouds to march through: one
 * square of {@link #SIZE} by {@link #SIZE} columns of cloud space, each {@code texelBlocks} wide,
 * and for every column four bands of sky ({@link Band}), each one span of cloud: its bottom and
 * top over the floor, how solid it is and how stormy. The shader turns each span into a soft
 * volume and wears its edges away with 3D noise, so the shapes bulge and overhang; the map only
 * says where the cloud is and how far up it reaches.
 *
 * <p>The bytes are the texture as it is uploaded: an RGBA8 atlas of the four bands, two by two
 * ({@link #ATLAS_SIZE} square), band {@code b} at tile ({@code b & 1}, {@code b >> 1}). A texel's
 * red is the span's bottom and green its top, both over {@link #HEIGHT_RANGE} blocks above the
 * floor; blue its density, alpha its storminess. In the low band alpha also says whether the
 * cloud is heaped (cumulus, congestus, the thunderstorm's tower, the rain deck), which the shader
 * builds of lumps, or stratus, which keeps the billows: 0 for stratus, and for a heaped cloud from
 * {@code HEAPED} (128, calm) up to 255 (fully stormy). A column with no cloud in a band keeps the band's
 * usual base for both heights, so filtering toward it brings the cloud down to its base rather
 * than to the floor, and in the low band reads as heaped and calm, so a heaped cloud stays heaped
 * out to its rim.
 *
 * <p>Built once, then only read: share it.
 */
public final class CloudMap {
    /** Columns across one band's square. */
    public static final int SIZE = 512;
    /** Texels across the whole atlas. */
    public static final int ATLAS_SIZE = 2 * SIZE;
    /** The heights the bytes cover, in blocks over the floor: the top of the sky's fourth level and a little more. */
    public static final float HEIGHT_RANGE = 1_600.0f;
    /** A span fades in over this many blocks above its bottom (a flat base, textured by the shader's noise)... */
    static final double BASE_SOFTNESS = 12.0;
    /** ...and out over this share of its height at the top, so no top is a lid. */
    static final double TOP_SOFTNESS = 0.2;
    /** A span is its full height from this density up, and shrinks to its middle below it. */
    static final double FADING_SPAN = 0.35;
    /** The low band's alpha from which a cloud is heaped (the shader's volume_march.fsh decodes it the same way). */
    private static final int HEAPED = 128;
    /** How softly overlapping low clouds' tops and bases join, as a share of the newer one's thickness. */
    private static final float SEAM_SOFTNESS = 0.25f;

    /**
     * The four bands of sky a column's cloud is sorted into, so that clouds at different heights
     * over one column stay apart: a stratocumulus layer over a cumulus, an anvil over its storm.
     * Clouds in one band over one column are joined into one span (a cumulus inside a rain deck).
     */
    public enum Band {
        /** The low and heaped clouds and the storms: cumulus, congestus, the thunderstorm's tower, stratus and nimbostratus. */
        LOW(24.0f),
        /** Stratocumulus, in the second level. */
        SECOND(385.0f),
        /** The alto clouds, in the third level. */
        MIDDLE(780.0f),
        /** The cirro clouds and the thunderstorms' anvils, at the top of the sky. */
        HIGH(1_280.0f);

        private final float usualBase;

        Band(float usualBase) {
            this.usualBase = usualBase;
        }

        /** Where an empty column of this band keeps its heights, in blocks over the floor. */
        public float usualBase() {
            return usualBase;
        }

        /** The band a span of cloud belongs to. */
        public static Band of(CloudType type, boolean anvil) {
            if (anvil) {
                return HIGH;
            }
            return switch (type) {
                case STRATOCUMULUS -> SECOND;
                case ALTOCUMULUS, ALTOSTRATUS -> MIDDLE;
                case CIRRUS, CIRROCUMULUS, CIRROSTRATUS -> HIGH;
                default -> LOW;
            };
        }
    }

    private static final int BANDS = Band.values().length;

    private final byte[] texels;
    private final int originX;
    private final int originZ;
    private final int texelBlocks;

    /**
     * @param originX the map's corner, its least x and z, in texels of cloud space (so the corner
     *                in blocks is {@code originX * texelBlocks})
     */
    private CloudMap(byte[] texels, int originX, int originZ, int texelBlocks) {
        this.texels = texels;
        this.originX = originX;
        this.originZ = originZ;
        this.texelBlocks = texelBlocks;
    }

    /** A map with no cloud anywhere. */
    public static CloudMap empty(int originX, int originZ, int texelBlocks) {
        byte[] texels = new byte[ATLAS_SIZE * ATLAS_SIZE * 4];
        for (Band band : Band.values()) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    writeEmpty(texels, index(band.ordinal(), x, z), band);
                }
            }
        }
        return new CloudMap(texels, originX, originZ, texelBlocks);
    }

    /**
     * Starts a map around a place: its corner on the grid of its texels, so maps built a little
     * apart share their columns exactly.
     *
     * @param centreX the place to centre it on, in blocks of cloud space
     */
    public static Builder around(double centreX, double centreZ, int texelBlocks) {
        int originX = (int) Math.floor(centreX / texelBlocks) - SIZE / 2;
        int originZ = (int) Math.floor(centreZ / texelBlocks) - SIZE / 2;
        return new Builder(originX, originZ, texelBlocks);
    }

    public byte[] texels() {
        return texels;
    }

    /** The map's corner, in texels of cloud space. */
    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public int texelBlocks() {
        return texelBlocks;
    }

    /** How far across the map reaches, in blocks. */
    public int spanBlocks() {
        return SIZE * texelBlocks;
    }

    /** The map's middle in blocks of cloud space. */
    public double centreX() {
        return (originX + SIZE / 2.0) * texelBlocks;
    }

    public double centreZ() {
        return (originZ + SIZE / 2.0) * texelBlocks;
    }

    /** Whether a column of cloud space lies inside the map. */
    public boolean covers(double cloudX, double cloudZ) {
        double x = cloudX / texelBlocks - originX;
        double z = cloudZ / texelBlocks - originZ;
        return x >= 0.0 && z >= 0.0 && x < SIZE && z < SIZE;
    }

    /** A band's bottom over the floor at a texel, in blocks. */
    public float bottom(Band band, int x, int z) {
        return unsigned(texels[index(band.ordinal(), x, z)]) * HEIGHT_RANGE / 255.0f;
    }

    public float top(Band band, int x, int z) {
        return unsigned(texels[index(band.ordinal(), x, z) + 1]) * HEIGHT_RANGE / 255.0f;
    }

    public float density(Band band, int x, int z) {
        return unsigned(texels[index(band.ordinal(), x, z) + 2]) / 255.0f;
    }

    public float storminess(Band band, int x, int z) {
        int alpha = unsigned(texels[index(band.ordinal(), x, z) + 3]);
        return band == Band.LOW ? Math.max(alpha - HEAPED, 0) / (255.0f - HEAPED) : alpha / 255.0f;
    }

    /**
     * Whether the low band's cloud over a column is heaped rather than stratus (always false in the
     * other bands). A column with no low cloud reads as heaped, as the shader filters it.
     */
    public boolean heaped(Band band, int x, int z) {
        return band == Band.LOW && unsigned(texels[index(band.ordinal(), x, z) + 3]) >= HEAPED;
    }

    /**
     * How solid the cloud's envelope is at a point of cloud space, 0..1, from the nearest texels
     * blended as the GPU filters them, before the shader's noise wears it away: the densest band
     * there, faded in over the bottom few blocks of its span and out over the top fifth. An upper
     * bound on what the shader draws, for the fog inside a cloud.
     *
     * @param height the point's height over the floor, in blocks
     */
    public float envelopeAt(double cloudX, double height, double cloudZ) {
        double x = cloudX / texelBlocks - originX - 0.5;
        double z = cloudZ / texelBlocks - originZ - 0.5;
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        float densest = 0.0f;
        for (Band band : Band.values()) {
            double bottom = 0.0;
            double top = 0.0;
            double density = 0.0;
            for (int corner = 0; corner < 4; corner++) {
                int cx = Math.clamp(x0 + (corner & 1), 0, SIZE - 1);
                int cz = Math.clamp(z0 + (corner >> 1), 0, SIZE - 1);
                double weight = ((corner & 1) != 0 ? fx : 1.0 - fx) * ((corner >> 1) != 0 ? fz : 1.0 - fz);
                bottom += weight * bottom(band, cx, cz);
                top += weight * top(band, cx, cz);
                density += weight * density(band, cx, cz);
            }
            if (density <= 0.0 || top - bottom < 1.0) {
                continue;
            }
            densest = Math.max(densest, (float) (density * profile(height, bottom, top)));
        }
        return densest;
    }

    /**
     * The span's soft vertical shape, 0..1: in over {@link #BASE_SOFTNESS} blocks above its bottom
     * and out over the top {@link #TOP_SOFTNESS} of it. The shader's own (cloud_volume.glsl) is the
     * same before its noise.
     */
    static double profile(double height, double bottom, double top) {
        double thickness = Math.max(top - bottom, 1.0);
        double in = smoothstep(bottom, bottom + Math.min(BASE_SOFTNESS, thickness * 0.5), height);
        double out = 1.0 - smoothstep(top - thickness * TOP_SOFTNESS, top, height);
        return in * out;
    }

    /**
     * How much of a disc of columns around a point holds cloud at any height, 0..1: the share of
     * texels within {@code radiusBlocks} whose densest band is at least {@code solid}.
     */
    public float coverAround(double cloudX, double cloudZ, double radiusBlocks, float solid) {
        int centreX = (int) Math.floor(cloudX / texelBlocks) - originX;
        int centreZ = (int) Math.floor(cloudZ / texelBlocks) - originZ;
        int radius = Math.max(0, (int) Math.round(radiusBlocks / texelBlocks));
        int covered = 0;
        int columns = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                columns++;
                int x = centreX + dx;
                int z = centreZ + dz;
                if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
                    continue;
                }
                for (Band band : Band.values()) {
                    if (density(band, x, z) >= solid) {
                        covered++;
                        break;
                    }
                }
            }
        }
        return columns == 0 ? 0.0f : covered / (float) columns;
    }

    /**
     * The highest top of the cloud the rain falls from over a square of columns around a point, in
     * blocks over the floor, or -1 where none holds it: the low band's (a rain deck, or a storm's
     * tower rising out of it), not a layer or an anvil floating clear above.
     */
    public float rainCloudTopAround(double cloudX, double cloudZ, double radiusBlocks, float solid) {
        int centreX = (int) Math.floor(cloudX / texelBlocks) - originX;
        int centreZ = (int) Math.floor(cloudZ / texelBlocks) - originZ;
        int radius = Math.max(0, (int) Math.round(radiusBlocks / texelBlocks));
        float highest = -1.0f;
        for (int z = centreZ - radius; z <= centreZ + radius; z++) {
            for (int x = centreX - radius; x <= centreX + radius; x++) {
                if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
                    continue;
                }
                if (density(Band.LOW, x, z) >= solid) {
                    highest = Math.max(highest, top(Band.LOW, x, z));
                }
            }
        }
        return highest;
    }

    /** Whether two maps hold the same cloud in the same place. */
    public boolean sameAs(CloudMap other) {
        return other.originX == originX && other.originZ == originZ && other.texelBlocks == texelBlocks
            && Arrays.equals(other.texels, texels);
    }

    private static int index(int band, int x, int z) {
        int atlasX = (band & 1) * SIZE + x;
        int atlasZ = (band >> 1) * SIZE + z;
        return (atlasZ * ATLAS_SIZE + atlasX) * 4;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static byte height(double blocks) {
        return (byte) Math.clamp(Math.round(blocks / HEIGHT_RANGE * 255.0), 0, 255);
    }

    private static byte share(double value) {
        return (byte) Math.clamp(Math.round(value * 255.0), 0, 255);
    }

    private static void writeEmpty(byte[] texels, int index, Band band) {
        byte base = height(band.usualBase());
        texels[index] = base;
        texels[index + 1] = base;
        texels[index + 2] = 0;
        texels[index + 3] = band == Band.LOW ? heapedStorm(0.0) : 0;
    }

    /** The low band's alpha for a heaped cloud: {@link #HEAPED} when calm, up to 255 fully stormy. */
    private static byte heapedStorm(double storminess) {
        return (byte) (HEAPED + Math.clamp(Math.round(storminess * (255 - HEAPED)), 0, 255 - HEAPED));
    }

    /** The greater of two values, rounded off where they come within {@code softness} of each other. */
    private static float smoothMax(float a, float b, float softness) {
        float near = Math.max(softness - Math.abs(a - b), 0.0f) / softness;
        return Math.max(a, b) + near * near * softness * 0.25f;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Fills a map from the field, a strip of rows at a time, so the rows can be shared out among
     * worker threads: each strip asks the field for its own area, and the strips write to rows
     * apart, so they may run at once.
     */
    public static final class Builder {
        private final byte[] texels = new byte[ATLAS_SIZE * ATLAS_SIZE * 4];
        private final int originX;
        private final int originZ;
        private final int texelBlocks;

        private Builder(int originX, int originZ, int texelBlocks) {
            this.originX = originX;
            this.originZ = originZ;
            this.texelBlocks = texelBlocks;
        }

        public int originX() {
            return originX;
        }

        public int originZ() {
            return originZ;
        }

        /**
         * Samples rows {@code firstRow} (inclusive) to {@code endRow} (exclusive) from the field at
         * a tick, at the map's own column width as the detail.
         *
         * @return how many columns held cloud
         */
        public int sampleRows(CloudField field, double tick, int firstRow, int endRow) {
            double minX = (double) originX * texelBlocks;
            double minZ = (double) (originZ + firstRow) * texelBlocks;
            CloudField.Area area = field.area(tick, minX, minZ, minX + SIZE * texelBlocks, minZ + (endRow - firstRow) * texelBlocks);
            CloudColumn column = new CloudColumn();
            float floor = field.sky().baseHeight();
            int clouded = 0;
            float[] bottoms = new float[BANDS];
            float[] tops = new float[BANDS];
            float[] densities = new float[BANDS];
            float[] storms = new float[BANDS];
            boolean[] heaped = new boolean[BANDS];
            for (int z = firstRow; z < endRow; z++) {
                double cloudZ = (originZ + z + 0.5) * texelBlocks;
                for (int x = 0; x < SIZE; x++) {
                    double cloudX = (originX + x + 0.5) * texelBlocks;
                    if (area.formationCount() == 0) {
                        for (Band band : Band.values()) {
                            writeEmpty(texels, index(band.ordinal(), x, z), band);
                        }
                        continue;
                    }
                    area.column(cloudX, cloudZ, texelBlocks, column);
                    Arrays.fill(bottoms, Float.POSITIVE_INFINITY);
                    Arrays.fill(tops, Float.NEGATIVE_INFINITY);
                    Arrays.fill(densities, 0.0f);
                    Arrays.fill(storms, 0.0f);
                    Arrays.fill(heaped, false);
                    for (int span = 0; span < column.spanCount(); span++) {
                        int band = Band.of(column.type(span), column.anvil(span)).ordinal();
                        // A fading span shrinks toward its middle as its density falls, so a cloud
                        // ending at its rim inside a bigger one leaves the joined heights no step
                        // (a step there drew a straight line of shading down the bigger cloud).
                        float whole = (float) smoothstep(0.0, FADING_SPAN, column.density(span));
                        float middle = (column.bottom(span) + column.top(span)) * 0.5f - floor;
                        float half = (column.top(span) - column.bottom(span)) * 0.5f * whole;
                        if (band == Band.LOW.ordinal() && column.type(span) != CloudType.STRATUS) {
                            // Heaped and storm clouds that overlap join with rounded seams: a plain lesser-of and
                            // greater-of left a crease where one took over from the other, upright
                            // through the joined cloud's side, which read as a straight line down it.
                            float seam = Math.max(half * 2.0f * SEAM_SOFTNESS, 1.0f);
                            bottoms[band] = -smoothMax(-bottoms[band], -(middle - half), seam);
                            tops[band] = smoothMax(tops[band], middle + half, seam);
                            densities[band] = 1.0f - (1.0f - densities[band]) * (1.0f - column.density(span));
                        } else {
                            bottoms[band] = Math.min(bottoms[band], middle - half);
                            tops[band] = Math.max(tops[band], middle + half);
                            densities[band] = Math.max(densities[band], column.density(span));
                        }
                        storms[band] = Math.max(storms[band], column.storminess(span));
                        heaped[band] |= column.type(span) != CloudType.STRATUS;
                    }
                    boolean any = false;
                    for (Band band : Band.values()) {
                        int b = band.ordinal();
                        int index = index(b, x, z);
                        if (densities[b] <= 0.0f) {
                            writeEmpty(texels, index, band);
                            continue;
                        }
                        any = true;
                        texels[index] = height(bottoms[b]);
                        texels[index + 1] = height(Math.max(tops[b], bottoms[b] + 1.0f));
                        texels[index + 2] = share(densities[b]);
                        texels[index + 3] = band != Band.LOW ? share(storms[b]) : heaped[b] ? heapedStorm(storms[b]) : 0;
                    }
                    clouded += any ? 1 : 0;
                }
            }
            return clouded;
        }

        /** The finished map; the builder must not be sampled into afterwards. */
        public CloudMap build() {
            return new CloudMap(texels, originX, originZ, texelBlocks);
        }
    }
}
