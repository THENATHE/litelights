package com.thenathe.litelights.api;

import java.nio.file.Path;

/**
 * Which world the player is in, for data a module keeps per world (docs/ARCHITECTURE.md, "Modules").
 *
 * @param key         a file-safe name for the world: the save's folder name in single player, the
 *                    server's address with a hash of the world's seed hash in multiplayer, so one
 *                    address with a new world gets a new key. The seed itself is never in it
 * @param dataFolder  the folder a module's data for this world goes under (in single player inside
 *                    the world save, so it travels with backups and goes with the world; in
 *                    multiplayer under the game folder), not created until a module writes there
 * @param singlePlayer the world runs on this game's own integrated server
 * @param dimension   the dimension the player is in, as its id ("minecraft:overworld")
 */
public record WorldIdentity(String key, Path dataFolder, boolean singlePlayer, String dimension) {
}
