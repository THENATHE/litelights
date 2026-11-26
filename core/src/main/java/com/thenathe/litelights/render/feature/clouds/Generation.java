package com.thenathe.litelights.render.feature.clouds;

import com.thenathe.litelights.clouds.volume.CloudMap;

/**
 * One finished build of the clouds: the cloud field at one tick written out as column maps
 * (clouds.volume.CloudMap), a near one of narrow columns round the camera and a far one of wide
 * columns out to the reach (the far one rebuilt only every few builds, and reused between).
 * Immutable once built.
 *
 * @param tick          the tick the clouds were built for
 * @param floor         the lowest cloud base, in world y
 * @param near          the near map, in cloud space
 * @param far           the far map; the same object as the last build's while it wasn't due
 * @param farSampled    whether this build sampled the far map (rather than reusing it)
 * @param cameraCloudX  where the camera was in cloud space when it was built
 * @param cloudedColumns the near map's columns holding any cloud
 * @param buildMillis   the build's wall time on the workers, and the near and far maps' shares of it
 */
public record Generation(double tick, float floor, CloudMap near, CloudMap far, boolean farSampled, double cameraCloudX, double cameraCloudZ,
                         int cloudedColumns, double buildMillis, double nearMillis, double farMillis) {

    /** The map holding a column of cloud space, near first. */
    CloudMap mapAt(double cloudX, double cloudZ) {
        return near.covers(cloudX, cloudZ) ? near : far;
    }

    /** How solid the cloud's envelope is at a point of cloud space (CloudMap.envelopeAt), y in world blocks. */
    float envelopeAt(double cloudX, double y, double cloudZ) {
        return mapAt(cloudX, cloudZ).envelopeAt(cloudX, y - floor, cloudZ);
    }

    /** How much of a disc of sky around a column holds cloud, 0..1 (CloudMap.coverAround). */
    float coverAround(double cloudX, double cloudZ, double radiusBlocks) {
        return mapAt(cloudX, cloudZ).coverAround(cloudX, cloudZ, radiusBlocks, 0.3f);
    }

    /** The top of the cloud the rain falls from round a column, in blocks over the floor, or -1 (CloudMap.rainCloudTopAround). */
    float rainCloudTopAround(double cloudX, double cloudZ, double radiusBlocks) {
        return mapAt(cloudX, cloudZ).rainCloudTopAround(cloudX, cloudZ, radiusBlocks, 0.3f);
    }
}
