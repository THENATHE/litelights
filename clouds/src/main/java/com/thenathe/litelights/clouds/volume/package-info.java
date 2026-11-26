/**
 * What the volumetric clouds are drawn from: the cloud field written out as maps of columns
 * ({@link com.thenathe.litelights.clouds.volume.CloudMap}), and the tiling 3D noise that wears
 * their shapes into billows ({@link com.thenathe.litelights.clouds.volume.CloudNoiseVolume}).
 * Plain Java with no game classes, like the field; :core uploads them and marches through them.
 * See docs/CLOUDS.md.
 */
@NullMarked
package com.thenathe.litelights.clouds.volume;

import org.jspecify.annotations.NullMarked;
