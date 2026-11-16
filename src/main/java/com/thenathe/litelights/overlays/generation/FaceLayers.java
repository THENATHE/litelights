package com.thenathe.litelights.overlays.generation;

import java.util.List;

/**
 * The textures a block draws on its top face and on its sides, bottom layer first. A grass block
 * has one tinted layer on top and two on each side: the dirt-and-grass side, then the tinted
 * grass fringe drawn over it.
 */
public record FaceLayers(List<Layer> top, List<Layer> side) {

    /**
     * @param texture   texture id as models write it, such as {@code minecraft:block/grass_block_top}
     * @param tintIndex the model's tint index, or -1 for an untinted face
     */
    public record Layer(String texture, int tintIndex) {
        public boolean tinted() {
            return tintIndex >= 0;
        }
    }
}
