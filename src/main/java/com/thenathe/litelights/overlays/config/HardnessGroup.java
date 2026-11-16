package com.thenathe.litelights.overlays.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A rank in the hardness hierarchy. Groups are listed softest first, and so are the blocks inside
 * each group: overlays flow from earlier blocks onto later ones.
 */
public final class HardnessGroup {
    public String name;
    public List<BlockEntry> blocks = new ArrayList<>();

    public HardnessGroup() {
    }

    public HardnessGroup(String name, List<BlockEntry> blocks) {
        this.name = name;
        this.blocks = new ArrayList<>(blocks);
    }

    public HardnessGroup copy() {
        HardnessGroup copy = new HardnessGroup();
        copy.name = name;
        for (BlockEntry entry : blocks) {
            copy.blocks.add(entry.copy());
        }
        return copy;
    }
}
