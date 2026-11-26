/**
 * The cloud field: where clouds are, how big and how tall, and how they grow, shrink and move.
 * It draws nothing. The volumetric clouds' column maps (clouds.volume) and their shadows read it, and either
 * LiteLights' own sky ({@link com.thenathe.litelights.clouds.field.VanillaWeatherSky}) or a
 * weather mod fills its input ({@link com.thenathe.litelights.clouds.field.CloudSky}).
 *
 * <p>Kept free of the game's classes and of the rest of LiteLights (only plain Java and the
 * nullness annotations), so it can become a module of its own when the build is split, and its
 * tests need no game. See docs/CLOUDS-FIELD.md.
 */
package com.thenathe.litelights.clouds.field;
