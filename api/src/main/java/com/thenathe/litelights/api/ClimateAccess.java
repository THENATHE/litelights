package com.thenathe.litelights.api;

/** What LiteLights knows about the climate mods installed beside it. */
public interface ClimateAccess {
    /**
     * Whether the installed Almanac is this version ({@link AlmanacVersions}) or later; false
     * without Almanac. Calls a later Almanac added are only made where this says they exist.
     */
    boolean almanacAtLeast(String version);
}
