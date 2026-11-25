package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.overlays.pack.PackStack;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/**
 * Overlays the player's own packs already draw, so the generator can leave those pairs alone.
 *
 * <p>Every {@code optifine/ctm/**.properties} file whose method starts with {@code overlay} is read.
 * Its receivers come from {@code matchBlocks}, {@code matchTiles} or, failing both, a
 * {@code block_<id>.properties} file name (the same fallback Continuity uses). Its sources come
 * from {@code connectBlocks} and {@code connectTiles}; with neither, it reacts to any neighbour, so
 * it counts as covering every source. A pair counts as covered when both sides match.
 */
public final class ExistingOverlays {
    private final List<Rule> rules;

    /** No pack has overlays of its own. */
    static final ExistingOverlays NONE = new ExistingOverlays(List.of());

    private ExistingOverlays(List<Rule> rules) {
        this.rules = rules;
    }

    private record Rule(Set<String> matchBlocks, Set<String> matchTiles, Set<String> connectBlocks,
                        Set<String> connectTiles, String origin) {
    }

    public static ExistingOverlays scan(PackStack packs) {
        List<Rule> rules = new ArrayList<>();
        packs.listAll("optifine/ctm", (pack, location) -> {
            if (!location.getPath().endsWith(".properties")) {
                return;
            }
            try {
                byte[] bytes = packs.readFrom(pack, location);
                if (bytes == null) {
                    return;
                }
                Properties properties = new Properties();
                properties.load(new ByteArrayInputStream(bytes));
                Rule rule = toRule(properties, location, pack.packId());
                if (rule != null) {
                    rules.add(rule);
                }
            } catch (IOException | RuntimeException ignored) {
                // A file Continuity can't read wouldn't draw anything either.
            }
        });
        return new ExistingOverlays(List.copyOf(rules));
    }

    public int ruleCount() {
        return rules.size();
    }

    /**
     * Whether some pack already draws an overlay from {@code source} onto {@code receiver}.
     *
     * @param textures the texture ids each block shows, for rules written with tiles
     */
    public boolean covers(String receiver, String source, Function<String, Set<String>> textures) {
        for (Rule rule : rules) {
            boolean receives = rule.matchBlocks.contains(receiver) || intersects(rule.matchTiles, textures.apply(receiver));
            if (!receives) {
                continue;
            }
            boolean anySource = rule.connectBlocks.isEmpty() && rule.connectTiles.isEmpty();
            if (anySource || rule.connectBlocks.contains(source) || intersects(rule.connectTiles, textures.apply(source))) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersects(Set<String> rule, Set<String> textures) {
        if (rule.isEmpty() || textures == null) {
            return false;
        }
        for (String texture : textures) {
            if (rule.contains(texture)) {
                return true;
            }
        }
        return false;
    }

    private static Rule toRule(Properties properties, Identifier location, String packId) {
        String method = properties.getProperty("method", "ctm").trim().toLowerCase(Locale.ROOT);
        if (!method.startsWith("overlay")) {
            return null;
        }
        Set<String> matchBlocks = blocks(properties.getProperty("matchBlocks"));
        Set<String> matchTiles = tiles(properties.getProperty("matchTiles"));
        if (matchBlocks.isEmpty() && matchTiles.isEmpty()) {
            String fileName = location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
            if (fileName.startsWith("block_")) {
                matchBlocks = blocks(fileName.substring(6, fileName.length() - ".properties".length()));
            }
        }
        return new Rule(matchBlocks, matchTiles, blocks(properties.getProperty("connectBlocks")),
            tiles(properties.getProperty("connectTiles")), packId + ":" + location);
    }

    /** Block ids from a list like {@code sand minecraft:grass_block:snowy=false}, states dropped. */
    private static Set<String> blocks(String value) {
        Set<String> ids = new HashSet<>();
        if (value == null) {
            return ids;
        }
        for (String token : value.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            String[] parts = token.split(":");
            boolean hasNamespace = parts.length > 1 && !parts[1].contains("=");
            String id = hasNamespace ? parts[0] + ":" + parts[1] : "minecraft:" + parts[0];
            ids.add(id.toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    /** Texture ids from a tile list like {@code sand block/stone minecraft:block/dirt}. */
    private static Set<String> tiles(String value) {
        Set<String> ids = new HashSet<>();
        if (value == null) {
            return ids;
        }
        for (String token : value.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            String namespace = "minecraft";
            String path = token;
            int colon = token.indexOf(':');
            if (colon >= 0) {
                namespace = token.substring(0, colon);
                path = token.substring(colon + 1);
            }
            if (path.endsWith(".png")) {
                path = path.substring(0, path.length() - 4);
            }
            if (path.startsWith("textures/")) {
                path = path.substring("textures/".length());
            }
            if (!path.contains("/")) {
                path = "block/" + path;
            }
            ids.add(namespace + ":" + path);
        }
        return ids;
    }
}
