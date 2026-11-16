package com.thenathe.litelights.overlays.generation;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * The shapes an overlay's edge can take. Each one is drawn per texel at the source texture's own
 * resolution, so the edge stays pixel-crisp whether the pack is 8x, 16x or 32x.
 */
public enum EdgeStyle {
    /** Wavy, ragged edge with rounded corners, like turf grown over the neighbour. */
    ORGANIC,
    /** Stair-stepped teeth with square corners, the classic hand-drawn pixel-art fringe. */
    JAGGED,
    /** A straight band with square corners. */
    CLEAN,
    /** A thin band with round clumps scattered just beyond it. */
    CLUMPS,
    /** A solid core that breaks up into an ordered dither as it fades out. */
    DITHERED,
    /**
     * For masonry: the overlay is cut out of the source along its own mortar lines, so whole
     * bricks reach into the neighbour, some courses further than others (see {@link BrickShape}).
     * Added in 0.1.7, last, so the saved ids and the order of the others stay as they were.
     */
    BRICKS;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("litelights.overlays.edge_style." + id());
    }

    /** Reads a saved style id; unknown or missing ids give {@code fallback} so an old file still loads. */
    public static EdgeStyle byId(String id, EdgeStyle fallback) {
        if (id != null) {
            for (EdgeStyle style : values()) {
                if (style.id().equals(id)) {
                    return style;
                }
            }
        }
        return fallback;
    }
}
