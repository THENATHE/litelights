package com.thenathe.litelights.overlays.continuity;

import com.thenathe.litelights.overlays.generation.VarietyLayout;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * One variety properties file, as the processor needs it. Only files the overlays module writes use the
 * variety method, so the parser expects exactly what {@code OverlayGenerator} writes and throws
 * on anything else.
 *
 * @param receivers   blocks that take this overlay ({@code matchBlocks})
 * @param source      the spilling block in the state it spills from ({@code connectBlocks})
 * @param faces       faces the ordinary pieces are drawn on
 * @param size        the tile size of the pieces, in texels
 * @param pieces      the piece numbers that have a sprite
 * @param tintIndex   the source's tint index, or -1 for none
 * @param tintState   the state whose tint colours the pieces, or {@code null}
 * @param betterGrassBlocks receivers whose sides LambdaBetterGrass may redraw
 * @param betterGrassOwnSides those receivers' own side textures: a side face still showing one is a
 *                            face LambdaBetterGrass left alone
 * @param betterGrassOwnSnowySides the same for their snowy states
 * @param betterGrassRole   what this set does beside LambdaBetterGrass's sides when its source is one
 *                          of LambdaBetterGrass's blocks, or {@code null} when it isn't
 * @param betterGrassSoil   receivers whose own side is the soil under the source's side (dirt under
 *                          grass): beside a full side they take the source's fringe, not its top
 * @param betterGrassRules  LambdaBetterGrass's rules, for sets with a role
 * @param firstLayerTints   for receivers whose top or sides are drawn in more than one layer, the
 *                          tint index of the bottom layer's quad ({@link #ANY_TINT} for a face of
 *                          one layer), top first: pieces are drawn on that quad only, once per face
 */
record VarietySettings(Set<Block> receivers, Predicate<BlockState> source, Set<Direction> faces, VarietyLayout layout,
                       int size, boolean blend, boolean steps, String folder, Set<Integer> pieces,
                       int tintIndex, BlockState tintState, ChunkSectionLayer layer, Set<Block> betterGrassBlocks,
                       Set<Identifier> betterGrassOwnSides, Set<Identifier> betterGrassOwnSnowySides,
                       BetterGrassRole betterGrassRole, Set<Block> betterGrassSoil, BetterGrassRules betterGrassRules,
                       Map<Block, int[]> firstLayerTints) {

    static final int ANY_TINT = Integer.MIN_VALUE;

    /**
     * The part a set of one of LambdaBetterGrass's blocks plays, written by the generator as
     * {@code variety.betterGrassRole}.
     */
    enum BetterGrassRole {
        /** The source's top layer, which a full side shows: drawn on tops, and on sides beside a full side. */
        TOP,
        /** The bottom layer of the source's own side (dirt under grass): only beside a side left alone. */
        SIDE,
        /** A layer of the source's own side that is also drawn on soil beside a full side (grass's fringe). */
        FRINGE,
        /** A source whose top and sides are the same texture. */
        BOTH;

        /**
         * Whether a source's side, which LambdaBetterGrass treated as {@code theirs}, spills this set
         * onto a receiver's side it treated as {@code own}, beside it in the same wall: a full side
         * spills the top (or, onto soil it left alone, the fringe), a side it left alone spills the
         * own side onto sides it left alone, and a blended side spills nothing.
         *
         * @param soil the receiver is the soil under the source's side (dirt under grass)
         */
        boolean spillsBeside(BetterGrassRules.Side theirs, BetterGrassRules.Side own, boolean soil) {
            boolean ownAlone = own == BetterGrassRules.Side.VANILLA;
            return switch (this) {
                case TOP -> theirs == BetterGrassRules.Side.FULL && own != BetterGrassRules.Side.BLEND && !(soil && ownAlone);
                case SIDE -> theirs == BetterGrassRules.Side.VANILLA && ownAlone;
                case FRINGE -> ownAlone && (theirs == BetterGrassRules.Side.VANILLA || (theirs == BetterGrassRules.Side.FULL && soil));
                case BOTH -> theirs == BetterGrassRules.Side.FULL
                    ? own != BetterGrassRules.Side.BLEND
                    : theirs == BetterGrassRules.Side.VANILLA && ownAlone;
            };
        }
    }

    /** @param rules LambdaBetterGrass's rules, asked for only when the file gives its set a role */
    static VarietySettings parse(Properties properties, Supplier<BetterGrassRules> rules) {
        String[] layout = required(properties, "variety.layout").split("\\s+");
        Set<Integer> pieces = new HashSet<>();
        for (String piece : required(properties, "variety.pieces").split("\\s+")) {
            pieces.add(Integer.parseInt(piece));
        }
        int tintIndex = Integer.parseInt(properties.getProperty("tintIndex", "-1").trim());
        String tintBlock = properties.getProperty("tintBlock");
        BlockState tintState = tintBlock == null ? null : block(tintBlock.trim()).defaultBlockState();
        ChunkSectionLayer layer = "translucent".equals(properties.getProperty("layer", "cutout").trim().toLowerCase(Locale.ROOT))
            ? ChunkSectionLayer.TRANSLUCENT
            : ChunkSectionLayer.CUTOUT;
        BetterGrassRole role = role(properties.getProperty("variety.betterGrassRole"));
        return new VarietySettings(receivers(required(properties, "matchBlocks")), source(required(properties, "connectBlocks")),
            faces(required(properties, "faces")), new VarietyLayout(Integer.parseInt(layout[0]), Integer.parseInt(layout[1])),
            Integer.parseInt(required(properties, "variety.size")),
            Boolean.parseBoolean(required(properties, "variety.blend")), Boolean.parseBoolean(required(properties, "variety.steps")),
            required(properties, "variety.folder"), Set.copyOf(pieces), tintIndex, tintState, layer,
            receivers(properties.getProperty("variety.betterGrassBlocks", "")),
            identifiers(properties.getProperty("variety.betterGrassOwnSides", "")),
            identifiers(properties.getProperty("variety.betterGrassOwnSnowySides", "")),
            role, receivers(properties.getProperty("variety.betterGrassSoil", "")), role == null ? null : rules.get(),
            firstLayerTints(properties.getProperty("variety.firstLayerTints", "")));
    }

    private static BetterGrassRole role(String text) {
        return text == null || text.isBlank() ? null : BetterGrassRole.valueOf(text.trim().toUpperCase(Locale.ROOT));
    }

    /** Reads {@code block=top,side} entries, each tint a number or {@code *} for any. */
    private static Map<Block, int[]> firstLayerTints(String list) {
        Map<Block, int[]> tints = new HashMap<>();
        for (String entry : list.trim().split("\\s+")) {
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.lastIndexOf('=');
            String[] values = entry.substring(separator + 1).split(",");
            if (separator < 0 || values.length != 2) {
                throw new IllegalArgumentException("unreadable first layer tints '" + entry + "'");
            }
            tints.put(block(entry.substring(0, separator)), new int[] {tint(values[0]), tint(values[1])});
        }
        return Map.copyOf(tints);
    }

    private static int tint(String value) {
        return value.equals("*") ? ANY_TINT : Integer.parseInt(value);
    }

    private static Set<Identifier> identifiers(String list) {
        Set<Identifier> ids = new HashSet<>();
        for (String id : list.trim().split("\\s+")) {
            if (!id.isEmpty()) {
                ids.add(Identifier.parse(id));
            }
        }
        return Set.copyOf(ids);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return value.trim();
    }

    private static Set<Block> receivers(String list) {
        Set<Block> blocks = new HashSet<>();
        for (String id : list.trim().split("\\s+")) {
            if (!id.isEmpty()) {
                blocks.add(block(id));
            }
        }
        return Set.copyOf(blocks);
    }

    /** Reads {@code namespace:block:property=value:...}, the form the generator pins a source's state with. */
    private static Predicate<BlockState> source(String text) {
        String[] parts = text.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("unreadable connectBlocks '" + text + "'");
        }
        Block block = block(parts[0] + ":" + parts[1]);
        Map<Property<?>, String> values = new HashMap<>();
        for (int i = 2; i < parts.length; i++) {
            String[] pair = parts[i].split("=", 2);
            Property<?> property = block.getStateDefinition().getProperty(pair[0]);
            if (property == null || pair.length < 2) {
                throw new IllegalArgumentException("unknown state '" + parts[i] + "' for " + block);
            }
            values.put(property, pair[1]);
        }
        return state -> {
            if (state.getBlock() != block) {
                return false;
            }
            for (Map.Entry<Property<?>, String> value : values.entrySet()) {
                if (!valueName(state, value.getKey()).equals(value.getValue())) {
                    return false;
                }
            }
            return true;
        };
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static Block block(String id) {
        Identifier identifier = Identifier.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(identifier)) {
            throw new IllegalArgumentException("unknown block '" + id + "'");
        }
        return BuiltInRegistries.BLOCK.getValue(identifier);
    }

    private static Set<Direction> faces(String list) {
        Set<Direction> faces = EnumSet.noneOf(Direction.class);
        for (String face : list.split("\\s+")) {
            switch (face.toLowerCase(Locale.ROOT)) {
                case "top" -> faces.add(Direction.UP);
                case "bottom" -> faces.add(Direction.DOWN);
                case "sides" -> faces.addAll(Direction.Plane.HORIZONTAL.stream().toList());
                default -> throw new IllegalArgumentException("unknown face '" + face + "'");
            }
        }
        return Collections.unmodifiableSet(faces);
    }
}
