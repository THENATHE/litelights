package com.thenathe.litelights.clouds.field;

/**
 * How far the wind at cloud height has carried the clouds by a tick: world space is cloud space
 * plus this. Every player works it out from the tick alone, so they all see the same sky, and the
 * renderer draws it as an offset instead of rebuilding.
 *
 * <p>LiteLights' own sky has a wind with a closed form ({@link CloudWind}); a weather mod's wind is
 * followed by summing it up ({@link IntegratedWind}).
 */
public interface CloudDrift {
    /** How far the clouds have been carried along x by a tick, in blocks. */
    double driftX(double tick);

    /** The same along z. */
    double driftZ(double tick);
}
