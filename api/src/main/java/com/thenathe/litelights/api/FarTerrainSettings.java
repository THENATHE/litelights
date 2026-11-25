package com.thenathe.litelights.api;

/**
 * Far terrain's share of LiteLights' settings snapshot (docs/ARCHITECTURE.md, "Reading settings").
 * The rows themselves are still declared in core.
 *
 * @param shadows     Shadows on
 * @param farShadows  the Far shadows mode
 * @param shadowReach the shadow distance in blocks
 */
public record FarTerrainSettings(boolean shadows, FarShadowMode farShadows, int shadowReach) {
    public enum FarShadowMode { OFF, SCREEN_SPACE, ADVANCED }
}
