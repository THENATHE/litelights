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
 *
 * <p>The sky is four levels deep ({@link #levelTop}): the low clouds in the first, stratocumulus
 * in the second, the alto clouds in the third and the cirro clouds in the fourth, while
 * nimbostratus reaches from the first through the third and a thunderstorm through all four. The
 * levels say how far up each type reaches, not slabs it fills (the user, 2026-11-25: "clouds that
 * extend a somewhat fixed distance so that they mimic real cloud shapes"); within its reach a
 * cloud's own shape decides its height.
 */
public enum CloudType {
    /** Fair-weather cumulus: low, puffy and about as tall as they are wide, from heaps 140 blocks across to clouds of 800. */
    CUMULUS("cumulus", new Heights(40, 15, 345, 15, 10),
        new Puffs(70, 390, 1.35f, 1.0f, 1.9f, 3, 0.08f, 0.15f, 0.3f, 60, 0.25f, 6_000, 16_000), null, null, false, 1, 1),
    /** Towering cumulus: big and tall with bright heads, from the low level into the second; never rains in LiteLights' own skies. */
    CONGESTUS("congestus", new Heights(36, 12, 690, 40, 8),
        new Puffs(200, 650, 1.2f, 1.5f, 2.6f, 4, 0.08f, 0.15f, 0.25f, 110, 0.2f, 10_000, 24_000), null, null, false, 1, 2),
    /**
     * The thunderstorm: one great tower from the floor to the top of the sky, nearly as wide as the
     * storm, spreading into an anvil at the top. Its puffs are the core tower's sizes; it has no
     * smaller clouds of its own.
     */
    CUMULONIMBUS("cumulonimbus", new Heights(24, 6, 1_480, 20, 16),
        new Puffs(300, 1_600, 1.0f, 1.8f, 3.2f, 6, 0.04f, 0.08f, 0.12f, 220, 0.12f, 12_000, 30_000), null,
        new Anvil(1.5f, 200, new Sheet(900, 1.4f, 0.14f, 0.3f, 1.0f, 0.2f, 220, 14)), true, 1, 4),
    /** The layered rain cloud: a thick grey mass with the lowest base, from the floor up through the third level. */
    NIMBOSTRATUS("nimbostratus", new Heights(46, 8, 900, 120, 20),
        null, new Sheet(1_200, 1.3f, 0.12f, 0.3f, 1.0f, 0.15f, 260, 14), null, true, 1, 3),
    /** A low, even grey layer. */
    STRATUS("stratus", new Heights(8, 6, 110, 20, 6),
        null, new Sheet(1_400, 1.5f, 0.15f, 0.3f, 1.0f, 0.12f, 120, 4), null, false, 1, 1),
    /** A lumpy layer of rolls and cells with gaps between, in the second level. */
    STRATOCUMULUS("stratocumulus", new Heights(385, 25, 535, 30, 6),
        null, new Sheet(260, 1.4f, 0.06f, 0.4f, 1.0f, 0.3f, 90, 8), null, false, 2, 2),
    /** Small puffs in rows in the third level. */
    ALTOCUMULUS("altocumulus", new Heights(780, 40, 890, 20, 4),
        null, new Sheet(200, 1.6f, 0.08f, 0.5f, 0.95f, 0.35f, 110, 6), null, false, 3, 3),
    /** Thin wisps at the top of the sky. */
    CIRRUS("cirrus", new Heights(1_300, 60, 1_370, 15, 0),
        null, new Sheet(600, 6.0f, 0.15f, 0.4f, 0.35f, 0.0f, 100, 0), null, false, 4, 4),
    /** A pale, even sheet in the third level, the sun showing through it where it is thin. */
    ALTOSTRATUS("altostratus", new Heights(760, 40, 990, 30, 8),
        null, new Sheet(1_800, 2.5f, 0.2f, 0.3f, 0.85f, 0.08f, 200, 10), null, false, 3, 3),
    /** Rows of tiny puffs at the top of the sky. */
    CIRROCUMULUS("cirrocumulus", new Heights(1_250, 50, 1_300, 10, 0),
        null, new Sheet(90, 1.6f, 0.06f, 0.5f, 0.5f, 0.25f, 50, 0), null, false, 4, 4),
    /** A faint veil at the top of the sky. */
    CIRROSTRATUS("cirrostratus", new Heights(1_240, 50, 1_360, 20, 0),
        null, new Sheet(2_400, 3.0f, 0.3f, 0.25f, 0.22f, 0.0f, 200, 0), null, false, 4, 4);

    /** How many levels the sky has. */
    public static final int LEVELS = 4;
    /** The top of each level, in blocks over the sky's base height: about 350, 700, 1,100 and 1,500. */
    private static final float[] LEVEL_TOPS = {350.0f, 700.0f, 1_100.0f, 1_500.0f};

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
    private final int lowestLevel;
    private final int highestLevel;

    CloudType(String id, Heights heights, @Nullable Puffs puffs, @Nullable Sheet sheet, @Nullable Anvil anvil,
              boolean precipitates, int lowestLevel, int highestLevel) {
        this.id = id;
        this.heights = heights;
        this.puffs = puffs;
        this.sheet = sheet;
        this.anvil = anvil;
        this.precipitates = precipitates;
        this.lowestLevel = lowestLevel;
        this.highestLevel = highestLevel;
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

    /** The lowest level of the sky this type reaches, 1 (the low clouds) to {@link #LEVELS}. */
    public int lowestLevel() {
        return lowestLevel;
    }

    /** The highest level of the sky this type reaches. */
    public int highestLevel() {
        return highestLevel;
    }

    /** The bottom of a level (1 to {@link #LEVELS}), in blocks over the sky's base height. */
    public static float levelBottom(int level) {
        return level <= 1 ? 0.0f : LEVEL_TOPS[level - 2];
    }

    /** The top of a level (1 to {@link #LEVELS}), in blocks over the sky's base height. */
    public static float levelTop(int level) {
        return LEVEL_TOPS[Math.clamp(level, 1, LEVELS) - 1];
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
