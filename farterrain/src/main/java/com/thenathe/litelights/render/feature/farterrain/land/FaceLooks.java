package com.thenathe.litelights.render.feature.farterrain.land;

/** How a block's faces look, by palette id: what the mesher needs beyond {@link BlockKinds}. */
public interface FaceLooks extends BlockKinds {
    /**
     * The appearance (a sprite of the block atlas, FarAppearances) of one face, 1 to 65535, or of
     * a plant's cards ({@link FarQuad#CARD}).
     */
    int appearance(int state, int face);

    /** The face's tint in this biome, 0xRRGGBB; 0xFFFFFF for none. */
    int tint(int state, int face, int biome);

    /** How the block is drawn at full detail (level 0). */
    FarShape shape(int state);

    /** The light the block gives off, 0 to 15. */
    int lightEmission(int state);

    /** The colour of that light far away, 0xRRGGBB. */
    int lightColour(int state);
}
