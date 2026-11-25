package com.thenathe.litelights.overlays.generation;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * How many variants of each edge piece are made, so long straight borders don't repeat once per
 * block. More variety costs more atlas space; see {@link VarietyLayout} for what each piece is.
 */
public enum EdgeVariety {
    /** One shape per edge, like a plain overlay pack, but still able to spill down steps. */
    OFF(1, 1),
    /** Two corner depths: the band's depth changes from block to block. */
    LOW(2, 1),
    /** Two corner depths and two waves per edge. */
    NORMAL(2, 2),
    /** Three corner depths and two waves per edge. */
    HIGH(3, 2);

    private final int classes;
    private final int waves;

    EdgeVariety(int classes, int waves) {
        this.classes = classes;
        this.waves = waves;
    }

    public VarietyLayout layout() {
        return new VarietyLayout(classes, waves);
    }

    /** One step less variety, for when a build has to fit the atlas; {@link #OFF} stays off. */
    public EdgeVariety lower() {
        return this == OFF ? OFF : values()[ordinal() - 1];
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("litelights.overlays.edge_variety." + id());
    }

    /** Reads a saved id; unknown or missing ids give {@code fallback} so an old or newer file still loads. */
    public static EdgeVariety byId(String id, EdgeVariety fallback) {
        if (id != null) {
            for (EdgeVariety variety : values()) {
                if (variety.id().equals(id)) {
                    return variety;
                }
            }
        }
        return fallback;
    }
}
