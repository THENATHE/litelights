package com.thenathe.litelights.render.feature.climate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Almanac's seasonal biome copies, as a client without Almanac receives them from an Almanac server:
 * {@code almanac:<sub_season>/<namespace>/<path>}, such as {@code almanac:mid_winter/minecraft/plains}.
 * A copy's climate and colours are already the season's and it carries its original's tags, so
 * everything that asks the biome's climate or tags is right as it is; only what LiteLights looks up
 * by a biome's id (the desert, the dappled forest, sky-fog's table of vanilla biomes) must look up
 * the original's id instead.
 *
 * <p>Only strings: the format is Almanac's stable contract (its {@code SeasonalCopyId}, from 0.1.1),
 * read here without Almanac installed. Thread-safe, as chunk scans on other threads classify biomes too.
 */
public final class SeasonalCopies {
    public static final String NAMESPACE = "almanac";
    private static final String[] SUB_SEASONS = {
        "early_spring", "mid_spring", "late_spring", "early_summer", "mid_summer", "late_summer",
        "early_fall", "mid_fall", "late_fall", "early_winter", "mid_winter", "late_winter"
    };

    /** Each copy's original, worked out once; ids that aren't copies map to themselves. */
    private static final Map<ResourceKey<Biome>, ResourceKey<Biome>> ORIGINALS = new ConcurrentHashMap<>();

    private SeasonalCopies() {
    }

    /** The id LiteLights should match a biome by: its original's for a seasonal copy, its own otherwise. */
    public static @Nullable ResourceKey<Biome> originalKey(Holder<Biome> biome) {
        ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
        if (key == null || !key.identifier().getNamespace().equals(NAMESPACE)) {
            return key;
        }
        return ORIGINALS.computeIfAbsent(key, SeasonalCopies::readOriginal);
    }

    /** Whether the biome is a seasonal copy, so its climate is the season's rather than its own. */
    public static boolean isCopy(Holder<Biome> biome) {
        ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
        return key != null && key.identifier().getNamespace().equals(NAMESPACE) && originalKey(biome) != key;
    }

    /** The copy's sub-season (0 early spring to 11 late winter), or -1 for any other biome. */
    public static int subSeasonOf(Holder<Biome> biome) {
        ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
        if (key == null || !isCopy(biome)) {
            return -1;
        }
        String path = key.identifier().getPath();
        return subSeasonIndex(path.substring(0, path.indexOf('/')));
    }

    private static ResourceKey<Biome> readOriginal(ResourceKey<Biome> key) {
        String original = originalId(key.identifier().toString());
        return original == null ? key : ResourceKey.create(Registries.BIOME, Identifier.parse(original));
    }

    /**
     * The original biome's id in a copy's id, or null if the id isn't a copy's (Almanac's own other
     * biomes, or anything malformed). Follows Almanac's {@code SeasonalCopyId.parse}.
     */
    static @Nullable String originalId(String id) {
        String prefix = NAMESPACE + ":";
        if (!id.startsWith(prefix)) {
            return null;
        }
        String[] parts = id.substring(prefix.length()).split("/", 3);
        if (parts.length < 3 || subSeasonIndex(parts[0]) < 0 || !isNamespace(parts[1]) || !isPath(parts[2])) {
            return null;
        }
        return parts[1] + ":" + parts[2];
    }

    static int subSeasonIndex(String name) {
        for (int index = 0; index < SUB_SEASONS.length; index++) {
            if (SUB_SEASONS[index].equals(name)) {
                return index;
            }
        }
        return -1;
    }

    // The game's own rules for the parts of an id.
    private static boolean isNamespace(String text) {
        return !text.isEmpty() && text.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
            || c == '_' || c == '-' || c == '.');
    }

    private static boolean isPath(String text) {
        return !text.isEmpty() && text.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
            || c == '_' || c == '-' || c == '.' || c == '/');
    }
}
