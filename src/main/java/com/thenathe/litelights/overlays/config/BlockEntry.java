package com.thenathe.litelights.overlays.config;

import com.thenathe.litelights.overlays.generation.EdgeStyle;

/** One block in the hierarchy, with its own switch and edge style. */
public final class BlockEntry {
    /** Saved in place of a style id when the block follows the fallback style. */
    public static final String USE_DEFAULT = "default";
    /** Saved in place of a style id when the block takes overlays from softer blocks but never spills its own. */
    public static final String RECEIVE_ONLY = "receive_only";

    public String id;
    public boolean enabled = true;
    public String style = USE_DEFAULT;

    public BlockEntry() {
    }

    public BlockEntry(String id) {
        this.id = id;
    }

    public BlockEntry(String id, String style) {
        this.id = id;
        this.style = style;
    }

    public BlockEntry copy() {
        BlockEntry copy = new BlockEntry(id, style);
        copy.enabled = enabled;
        return copy;
    }

    public boolean receivesOnly() {
        return RECEIVE_ONLY.equals(style);
    }

    public boolean usesDefaultStyle() {
        return style == null || USE_DEFAULT.equals(style) || (!receivesOnly() && EdgeStyle.byId(style, null) == null);
    }

    /**
     * The style this block's overlays are drawn in. Receive-only blocks have none. An unknown id
     * (say, a style a later version added and then dropped) falls back without being rewritten,
     * so the saved choice survives.
     */
    public EdgeStyle effectiveStyle(OverlayConfig config) {
        if (receivesOnly()) {
            return null;
        }
        if (config.forceFallbackStyle || usesDefaultStyle()) {
            return config.fallbackStyle();
        }
        return EdgeStyle.byId(style, config.fallbackStyle());
    }
}
