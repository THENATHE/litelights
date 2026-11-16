package com.thenathe.litelights.overlays.hierarchy;

import com.thenathe.litelights.overlays.config.BlockEntry;
import com.thenathe.litelights.overlays.config.DefaultHierarchy;
import com.thenathe.litelights.overlays.config.HardnessGroup;
import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.generation.EdgeStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who spills onto whom, worked out once per build from the hierarchy.
 *
 * <p>Every enabled block spills onto every enabled block ranked harder than it: anything in a
 * later group, and anything later in its own group. With "blend within a group" on, blocks that
 * share a group blend into each other instead, and only the cross-group rule stays one way.
 *
 * <p>Masonry in the Bricks style has rules of its own (DECISIONS, overlays17):
 * <ul>
 *   <li>two Bricks blocks of one group always blend into each other, whatever the blend setting
 *       and the order, so stone bricks and bricks merge both ways;</li>
 *   <li>a Bricks block spills onto every block of its group that receives only (cut, polished and
 *       smooth masonry), wherever it is listed, since those have no pattern of their own to keep;</li>
 *   <li>where brick masonry meets rock (a block from one of {@link DefaultHierarchy#ROCK_GROUPS}),
 *       the bricks merge into the rock rather than the rock spilling over the bricks, so a wall
 *       looks built into the stone around it. Soil, sand and plants still spill onto bricks.</li>
 * </ul>
 */
public record OverlayPlan(List<Source> sources) {

    /**
     * One block whose texture spills.
     *
     * @param spillOnto blocks that get this block's full overlay
     * @param blendWith same-group blocks that get this block's half of a blended seam
     */
    public record Source(String blockId, EdgeStyle style, Set<String> spillOnto, Set<String> blendWith) {
        public boolean isEmpty() {
            return spillOnto.isEmpty() && blendWith.isEmpty();
        }
    }

    public static OverlayPlan from(OverlayConfig config, EligibleBlocks eligible) {
        if (!config.enabled) {
            return new OverlayPlan(List.of());
        }
        return ofHierarchy(config, eligible);
    }

    /** Who would spill onto whom, whether or not the module is switched on (for the hierarchy editor). */
    public static OverlayPlan ofHierarchy(OverlayConfig config, EligibleBlocks eligible) {
        List<List<String>> ranked = enabledBlocksByGroup(config, eligible);
        Set<String> rock = new LinkedHashSet<>();
        for (int groupIndex = 0; groupIndex < ranked.size(); groupIndex++) {
            if (DefaultHierarchy.ROCK_GROUPS.contains(config.groups.get(groupIndex).name)) {
                rock.addAll(ranked.get(groupIndex));
            }
        }
        List<Source> sources = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < ranked.size(); groupIndex++) {
            List<String> group = ranked.get(groupIndex);
            for (int position = 0; position < group.size(); position++) {
                String blockId = group.get(position);
                EdgeStyle style = config.find(blockId).effectiveStyle(config);
                if (style == null || !eligible.canSpill(blockId)) {
                    continue;
                }
                boolean bricks = style == EdgeStyle.BRICKS;
                Set<String> spillOnto = new LinkedHashSet<>();
                Set<String> blendWith = new LinkedHashSet<>();
                for (int other = 0; other < group.size(); other++) {
                    if (other == position) {
                        continue;
                    }
                    String otherId = group.get(other);
                    if (bricks && isBricksSource(config, eligible, otherId)) {
                        blendWith.add(otherId);
                    } else if (bricks && !isSource(config, eligible, otherId)) {
                        spillOnto.add(otherId);
                    } else if (config.blendWithinGroup) {
                        blendWith.add(otherId);
                    } else if (other > position) {
                        spillOnto.add(otherId);
                    }
                }
                for (int harderGroup = groupIndex + 1; harderGroup < ranked.size(); harderGroup++) {
                    spillOnto.addAll(ranked.get(harderGroup));
                }
                if (bricks) {
                    spillOnto.addAll(rock);
                    spillOnto.remove(blockId);
                    spillOnto.removeAll(blendWith);
                } else if (rock.contains(blockId)) {
                    spillOnto.removeIf(receiver -> isBricksSource(config, eligible, receiver));
                }
                Source source = new Source(blockId, style, spillOnto, blendWith);
                if (!source.isEmpty()) {
                    sources.add(source);
                }
            }
        }
        return new OverlayPlan(List.copyOf(sources));
    }

    private static boolean isSource(OverlayConfig config, EligibleBlocks eligible, String blockId) {
        return config.find(blockId).effectiveStyle(config) != null && eligible.canSpill(blockId);
    }

    private static boolean isBricksSource(OverlayConfig config, EligibleBlocks eligible, String blockId) {
        return config.find(blockId).effectiveStyle(config) == EdgeStyle.BRICKS && eligible.canSpill(blockId);
    }

    /**
     * The enabled, loadable blocks of each group, in order. A block listed twice counts where it
     * first appears; blocks from mods that aren't installed are skipped but stay in the config.
     */
    private static List<List<String>> enabledBlocksByGroup(OverlayConfig config, EligibleBlocks eligible) {
        Set<String> seen = new LinkedHashSet<>();
        List<List<String>> ranked = new ArrayList<>();
        for (HardnessGroup group : config.groups) {
            List<String> blocks = new ArrayList<>();
            for (BlockEntry entry : group.blocks) {
                if (entry.enabled && eligible.isEligible(entry.id) && seen.add(entry.id)) {
                    blocks.add(entry.id);
                }
            }
            ranked.add(blocks);
        }
        return ranked;
    }
}
