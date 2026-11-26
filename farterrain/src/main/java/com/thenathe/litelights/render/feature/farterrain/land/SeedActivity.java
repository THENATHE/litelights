package com.thenathe.litelights.render.feature.farterrain.land;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * What seed generation is doing, for the Far Terrain page's Doing now row: the short status where
 * a control would be, the line under the row that says more, and whether it is good news (a tick).
 */
record SeedActivity(Component status, @Nullable Component detail, boolean good) {
    static SeedActivity of(String state, @Nullable Component detail, boolean good) {
        return new SeedActivity(Component.translatable("litelights.option.far_terrain.seed_now." + state), detail, good);
    }
}
