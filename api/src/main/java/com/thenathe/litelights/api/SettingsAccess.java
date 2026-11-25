package com.thenathe.litelights.api;

/** The settings a module reads, as immutable snapshots; read once a frame. */
public interface SettingsAccess {
    /** Far terrain's settings now. */
    FarTerrainSettings farTerrain();

    /** Far terrain's settings at their defaults, until the first frame update brings the real ones. */
    FarTerrainSettings farTerrainDefaults();

    /** The visual snow's rows that reach far terrain, now. */
    VisualSnowSettings visualSnow();
}
