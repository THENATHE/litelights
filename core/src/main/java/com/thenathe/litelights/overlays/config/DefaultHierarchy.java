package com.thenathe.litelights.overlays.config;

import com.thenathe.litelights.overlays.generation.EdgeStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The hierarchy a fresh install starts with, softest group first. The reasoning behind the order
 * is written up in docs/HIERARCHY.md; in short, whatever grows or drifts spills onto whatever
 * stays put. Worked stone is hardest: its brick and tile blocks merge into each other and into
 * the plain cut stone beside them with whole bricks, and nothing of it spills onto softer ground.
 */
public final class DefaultHierarchy {
    /**
     * The groups whose blocks are rock. Where rock spills onto brick masonry, the overlay follows
     * the masonry's own bricks (see {@code OverlayPlan}). Matched by name, so a group renamed in the
     * editor stops counting as rock.
     */
    public static final Set<String> ROCK_GROUPS = Set.of("Soft rock", "Stone", "Deep rock", "Nether and End");

    /**
     * Masonry with a brick or tile pattern: these spill in the Bricks style. Up to 0.1.6 every
     * masonry block received only; {@link OverlayConfig} moves the ones a player never changed.
     */
    public static final List<String> BRICK_MASONRY = List.of("minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
        "minecraft:cracked_stone_bricks", "minecraft:bricks", "minecraft:mud_bricks", "minecraft:deepslate_bricks",
        "minecraft:cracked_deepslate_bricks", "minecraft:deepslate_tiles", "minecraft:cracked_deepslate_tiles",
        "minecraft:tuff_bricks", "minecraft:polished_blackstone_bricks", "minecraft:cracked_polished_blackstone_bricks",
        "minecraft:cinnabar_bricks", "minecraft:sulfur_bricks", "minecraft:end_stone_bricks", "minecraft:nether_bricks",
        "minecraft:cracked_nether_bricks", "minecraft:red_nether_bricks", "minecraft:quartz_bricks",
        "minecraft:resin_bricks");

    private static final String[] TERRACOTTA_COLOURS = {
        "white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow",
        "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"
    };

    private DefaultHierarchy() {
    }

    public static List<HardnessGroup> groups() {
        List<HardnessGroup> groups = new ArrayList<>();
        groups.add(group("Ground cover", "snow_block", "moss_block", "pale_moss_block", "grass_block",
            "mycelium", "podzol", "crimson_nylium", "warped_nylium"));
        groups.add(group("Sand", "sand", "red_sand", "soul_soil"));
        groups.add(group("Mud and clay", "mud", "clay"));
        groups.add(group("Gravel", "gravel"));
        groups.add(group("Soil", "dirt", "coarse_dirt", "rooted_dirt", "packed_mud"));

        List<String> softRock = new ArrayList<>(List.of("sandstone", "red_sandstone", "terracotta"));
        for (String colour : TERRACOTTA_COLOURS) {
            softRock.add(colour + "_terracotta");
        }
        softRock.add("calcite");
        softRock.add("dripstone_block");
        groups.add(group("Soft rock", softRock.toArray(String[]::new)));

        groups.add(group("Stone", "stone", "andesite", "diorite", "granite", "tuff", "cobblestone",
            "mossy_cobblestone", "cinnabar", "sulfur"));
        groups.add(group("Deep rock", "deepslate", "cobbled_deepslate", "smooth_basalt", "blackstone"));
        groups.add(group("Nether and End", "netherrack", "end_stone"));

        // 0.1.7 added the cracked bricks and tiles, the nether, quartz and resin bricks at the end, so
        // the order of the blocks offered before stays as it was.
        HardnessGroup masonry = group("Masonry", "smooth_stone", "smooth_sandstone", "smooth_red_sandstone",
            "cut_sandstone", "cut_red_sandstone", "polished_andesite", "polished_diorite", "polished_granite",
            "polished_tuff", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "bricks", "mud_bricks",
            "polished_deepslate", "deepslate_bricks", "deepslate_tiles", "tuff_bricks", "polished_blackstone",
            "polished_blackstone_bricks", "polished_cinnabar", "cinnabar_bricks", "polished_sulfur", "sulfur_bricks",
            "end_stone_bricks", "cracked_deepslate_bricks", "cracked_deepslate_tiles", "cracked_polished_blackstone_bricks",
            "nether_bricks", "cracked_nether_bricks", "red_nether_bricks", "quartz_bricks", "resin_bricks");
        // Bricks and tiles merge into their neighbours with whole bricks. Plain cut and polished
        // stone has no pattern to cut along, so it receives only and keeps its crisp edges.
        masonry.blocks.forEach(entry -> entry.style = BRICK_MASONRY.contains(entry.id)
            ? EdgeStyle.BRICKS.id()
            : BlockEntry.RECEIVE_ONLY);
        groups.add(masonry);
        return groups;
    }

    public static List<String> blockIds() {
        List<String> ids = new ArrayList<>();
        for (HardnessGroup group : groups()) {
            group.blocks.forEach(entry -> ids.add(entry.id));
        }
        return ids;
    }

    private static HardnessGroup group(String name, String... paths) {
        List<BlockEntry> entries = new ArrayList<>();
        for (String path : paths) {
            entries.add(new BlockEntry("minecraft:" + path));
        }
        return new HardnessGroup(name, entries);
    }
}
