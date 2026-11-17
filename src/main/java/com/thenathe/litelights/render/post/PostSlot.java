package com.thenathe.litelights.render.post;

/**
 * Where a pass runs in the post chain. Slots run in the order listed. The world slots run as soon
 * as the level is finished, before the hand is drawn, and see the level's own depth; the final
 * slots run after the hand and the screen overlays, just before the HUD.
 *
 * <p>Light-post owns {@link #BLOOM}, {@link #LIGHT_RAYS}, {@link #ANTI_ALIASING} and
 * {@link #TONEMAP}; water owns {@link #UNDERWATER}; particles-weather owns {@link #MIST_FIELD}. Water's reflections are traced in its own
 * surface shader, not in the chain. A new slot goes where its pass must run; see
 * docs/ARCHITECTURE.md.
 */
public enum PostSlot {
    /** Passes that only run while the camera is under water: the depth blur (water stream). */
    UNDERWATER(Stage.WORLD),
    /** Particles-weather: the Mist field (Mist style: Mist field), before bloom so it covers what glows behind it. */
    MIST_FIELD(Stage.WORLD),
    /** Light-post: reads bright and emissive pixels into the quarter-resolution bloom chain. */
    BLOOM(Stage.WORLD),
    /** Light-post: sunbeam slices, added to the level. */
    LIGHT_RAYS(Stage.WORLD),
    /** Light-post: FXAA or TAA. The last world pass, so it smooths everything above. */
    ANTI_ALIASING(Stage.WORLD),
    /** Light-post: bloom composite, tonemap and colour grade, over the level and the hand. */
    TONEMAP(Stage.FINAL);

    public enum Stage { WORLD, FINAL }

    private final Stage stage;

    PostSlot(Stage stage) {
        this.stage = stage;
    }

    public Stage stage() {
        return stage;
    }
}
