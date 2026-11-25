package com.thenathe.litelights.overlays.generation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.overlays.pack.PackStack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

/**
 * The blocks whose side faces LambdaBetterGrass redraws, read from its own resource format: every
 * {@code bettergrass/states/<block>.json} in the current packs whose {@code type} is {@code grass}.
 * These are the grass-like blocks (grass, mycelium, podzol, the nyliums, paths) whose sides it
 * turns into its connected, better-grass sides, snowy ones included. Reading the files the mod
 * itself reads, rather than its classes, keeps this working whatever its code looks like, and
 * follows packs that add or remove blocks from it.
 */
final class BetterGrassBlocks {
    private static final String FOLDER = "bettergrass/states";

    private BetterGrassBlocks() {
    }

    static Set<String> scan(PackStack packs) {
        Set<String> blocks = new TreeSet<>();
        packs.listAll(FOLDER, (pack, location) -> {
            String path = location.getPath();
            if (!path.endsWith(".json")) {
                return;
            }
            try {
                byte[] bytes = packs.readFrom(pack, location);
                if (bytes == null) {
                    return;
                }
                JsonObject state = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (state.has("type") && "grass".equals(state.get("type").getAsString())) {
                    String name = path.substring(FOLDER.length() + 1, path.length() - ".json".length());
                    blocks.add(location.getNamespace() + ":" + name);
                }
            } catch (IOException | RuntimeException ignored) {
                // A file LambdaBetterGrass can't read doesn't redraw anything either.
            }
        });
        return blocks;
    }
}
