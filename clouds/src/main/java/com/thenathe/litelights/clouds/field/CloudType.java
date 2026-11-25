package com.thenathe.litelights.clouds.field;

import org.jspecify.annotations.Nullable;

/**
 * LiteLights' cloud types: what each kind of cloud looks like, wherever its formations come from.
 * Their names ({@link #id()}) are the fixed list a weather mod and LiteLights agree on, so they
 * must never change once released; the numbers are LiteLights' own and may.
 *
 * <p>Every height is in blocks from the sky's base height (vanilla's cloud height, 192 in the
 * overworld), so a dimension or data pack that moves vanilla's clouds moves these too. The base
 * height is also the floor: no cloud reaches below it (the user's rule, 2026-11-25), so the low
 * and rain types sit just above it, the rain deck lowest, and their ragged bases fray down to it.
 * The numbers come from real clouds at one scale, 150 blocks to the kilometre across and up to
 * 3 km high, and 100 blocks to the kilometre above that (so towers and cirrus stay within a
 * reachable sky); the lowest real rain base sits at the floor. See docs/CLOUDS-FIELD.md for each
 * type's real figures.
 */
public enum CloudType {
    /** Fair-weather cumulus: low, puffy, from small wisps to clouds 300 blocks across. */
    CUMULUS("cumulus", new Heights(28, 10, 210, 60, 6),
        new Puffs(15, 150, 1.35f, 0.6f, 1.4f, 3, 0.12f, 0.18f, 0.4f, 40, 0.3f, 4_000, 12_000), null, null, false, false),
    /** Towering cumulus: big, tall, bright heads; never rains in LiteLights' own skies. */
    CONGESTUS("congestus", new Heights(24, 10, 490, 90, 5),
        new Puffs(80, 360, 1.2f, 1.4f, 2.6f, 4, 0.1f, 0.2f, 0.3f, 70, 0.25f, 8_000, 20_000), null, null, false, false),
    /** The thunderstorm: towers that reach the top of the sky and spread into an anvil. */
    CUMULONIMBUS("cumulonimbus", new Heights(6, 4, 1_150, 120, 12),
        new Puffs(200, 750, 1.0f, 1.8f, 3.2f, 6, 0.05f, 0.1f, 0.14f, 130, 0.1f, 12_000, 30_000), null,
        new Anvil(1.8f, 120, new Sheet(500, 2.5f, 0.12f, 0.35f, 1.0f, 0.25f, 110, 10)), true, false),
    /** The layered rain cloud: a thick grey deck with the lowest base, uneven and ragged. */
    NIMBOSTRATUS("nimbostratus", new Heights(32, 6, 240, 40, 14),
        null, new Sheet(900, 1.3f, 0.12f, 0.35f, 1.0f, 0.2f, 130, 12), null, true, false),
    /** A low, even grey layer. */
    STRATUS("stratus", new Heights(4, 4, 44, 10, 6),
        null, new Sheet(1_400, 1.5f, 0.15f, 0.3f, 1.0f, 0.12f, 90, 3), null, false, false),
    /** A low lumpy layer of rolls and cells with gaps between. */
    STRATOCUMULUS("stratocumulus", new Heights(14, 8, 110, 25, 5),
        null, new Sheet(160, 1.4f, 0.06f, 0.5f, 1.0f, 0.35f, 55, 4), null, false, false),
    /** Small puffs in rows at middle height. */
    ALTOCUMULUS("altocumulus", new Heights(340, 40, 375, 15, 3),
        null, new Sheet(45, 2.0f, 0.05f, 0.5f, 0.9f, 0.3f, 28, 0), null, false, false),
    /** Thin wisps high up, drawn in the sky pass rather than as cubes. */
    CIRRUS("cirrus", new Heights(820, 80, 840, 10, 0),
        null, new Sheet(600, 6.0f, 0.15f, 0.4f, 0.35f, 0.0f, 100, 0), null, false, true);

    /**
     * Heights from the sky's base height, in blocks.
     *
     * @param base          where the cloud's base usually is
     * @param baseVariation how far a formation's base may be from that, either way
     * @param top           for puffs and towers the ceiling no cloud passes; for layers the top over
     *                      the usual base, so {@code top - base} is the usual thickness
     * @param topVariation  how far a formation's top may be from that, either way
     * @param raggedBottom  how far the base hangs down in ragged noise at most (never past the floor)
     */
    public record Heights(float base, float baseVariation, float top, float topVariation, float raggedBottom) {
    }

    /**
     * Single clouds, for the heaped types.
     *
     * @param smallestRadius the smallest cloud's radius, in blocks
     * @param largestRadius  the largest cloud's radius, in blocks
     * @param largerShare    how much more sky each size class covers than the next smaller one
     *                       (1: the same; clouds of the smaller class are still more numerous)
     * @param leastHeight    the lowest cloud's height as a share of its radius
     * @param mostHeight     the tallest cloud's height as a share of its radius
     * @param domePower      the dome's shape: height is {@code 1 - u^domePower} at {@code u} of the radius
     *                       (2 a rounded hill, 6 a column with a rounded top)
     * @param bottomSag      how far the middle of the base sags below its edge, as a share of the height
     * @param lumpiness      how much the top rises and falls in noise, as a share of the height
     * @param billows        how high the rounded heads on its top rise, as a share of the height
     *                       (they rise up to 0.6 of it and their creases sink 0.4)
     * @param billowSize     how wide its heads are, in blocks (times the formation's size scale)
     * @param rimLift        how far the base curves up at the rim, as a share of the height (round sides)
     * @param shortestLife   the shortest single cloud's life, in ticks
     * @param longestLife    the longest single cloud's life, in ticks
     */
    public record Puffs(float smallestRadius, float largestRadius, float largerShare, float leastHeight, float mostHeight,
                        int domePower, float bottomSag, float lumpiness, float billows, float billowSize, float rimLift,
                        float shortestLife, float longestLife) {
    }

    /**
     * A layer, for the sheet types and the anvil.
     *
     * @param featureSize       the size of its lumps and holes, in blocks
     * @param streak            how much longer the lumps are along the formation's long axis
     * @param softness          how soft the edges of holes are (a share of the noise's range)
     * @param thicknessVariation how much the thickness varies, as a share of it
     * @param opacity           how solid it gets at most (thin cirrus never is)
     * @param billows           how high the rounded heads on its top rise, as a share of its thickness
     * @param billowSize        how wide its heads are, in blocks
     * @param baseWaves         how far its base rises and falls in long, slow waves, in blocks either way
     */
    public record Sheet(float featureSize, float streak, float softness, float thicknessVariation, float opacity,
                        float billows, float billowSize, float baseWaves) {
    }

    /**
     * The top of a thunderstorm.
     *
     * @param spread    the anvil's radius as a multiple of the formation's
     * @param thickness the anvil's thickness in blocks, under the formation's top
     * @param sheet     the anvil's lumps and holes
     */
    public record Anvil(float spread, float thickness, Sheet sheet) {
    }

    private final String id;
    private final Heights heights;
    private final @Nullable Puffs puffs;
    private final @Nullable Sheet sheet;
    private final @Nullable Anvil anvil;
    private final boolean precipitates;
    private final boolean skyLayer;

    CloudType(String id, Heights heights, @Nullable Puffs puffs, @Nullable Sheet sheet, @Nullable Anvil anvil,
              boolean precipitates, boolean skyLayer) {
        this.id = id;
        this.heights = heights;
        this.puffs = puffs;
        this.sheet = sheet;
        this.anvil = anvil;
        this.precipitates = precipitates;
        this.skyLayer = skyLayer;
    }

    /** The type's name in the list both mods share. Never changes. */
    public String id() {
        return id;
    }

    public Heights heights() {
        return heights;
    }

    /** Single clouds, for the heaped types (cumulus, congestus, cumulonimbus); null for layers. */
    public @Nullable Puffs puffs() {
        return puffs;
    }

    /** The layer, for the sheet types; null for the heaped types. */
    public @Nullable Sheet sheet() {
        return sheet;
    }

    /** The anvil, for cumulonimbus only. */
    public @Nullable Anvil anvil() {
        return anvil;
    }

    /** Whether rain or snow can fall from this type. */
    public boolean precipitates() {
        return precipitates;
    }

    /** Drawn in the sky pass as a thin layer, not as cubes (cirrus). */
    public boolean skyLayer() {
        return skyLayer;
    }

    /** The type with this {@link #id()}, or null for a name this version doesn't know. */
    public static @Nullable CloudType byId(String id) {
        for (CloudType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}
