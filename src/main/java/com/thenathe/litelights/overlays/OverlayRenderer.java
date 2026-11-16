package com.thenathe.litelights.overlays;

/** Whether Continuity draws the overlays this session, or why the module is off. */
public enum OverlayRenderer {
    CONTINUITY,
    /** The module is off for the session; {@link Choice#reason()} says why. */
    NONE;

    public static final String CONTINUITY_MOD_ID = "continuity";
    public static final String STANDALONE_MOD_ID = "dynamic_overlays";

    /** Why the module is off, as a translation key under {@code litelights.overlays.off.}. */
    public enum OffReason {
        NONE,
        NO_CONTINUITY,
        STANDALONE_INSTALLED
    }

    public record Choice(OverlayRenderer renderer, OffReason reason) {
        public boolean active() {
            return renderer != NONE;
        }
    }

    /**
     * Picks from which mods are installed. The standalone Dynamic Overlays mod would build the same
     * overlays a second time, through the same hooks, so the module stands aside whenever it's present.
     */
    public static Choice choose(boolean continuity, boolean standaloneDynamicOverlays) {
        if (standaloneDynamicOverlays) {
            return new Choice(NONE, OffReason.STANDALONE_INSTALLED);
        }
        if (continuity) {
            return new Choice(CONTINUITY, OffReason.NONE);
        }
        return new Choice(NONE, OffReason.NO_CONTINUITY);
    }
}
