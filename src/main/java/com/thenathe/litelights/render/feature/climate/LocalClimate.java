package com.thenathe.litelights.render.feature.climate;

import com.thenathe.litelights.LiteLightsClient;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * The one place LiteLights' weather asks what the climate is somewhere: what falls (rain, snow or
 * nothing), whether it is cold enough to snow, and whether it is dry and hot enough for sandstorms.
 * No feature asks a biome's climate methods itself, because a biome only knows its all-year
 * climate, while a seasons mod changes the climate through the level's lookups, by season and region.
 *
 * <p>The source is picked once, as the client starts:
 * <ul>
 *   <li><b>Almanac</b> installed on the client: its API ({@link AlmanacClimate}), so the season's
 *   climate kinds and Almanac's season-changed event are known too.</li>
 *   <li><b>Another seasons mod</b> (Serene Seasons, Fabric Seasons, Ecliptic Seasons): the client
 *   level's precipitation lookup, which such mods hook so vanilla's own rain follows the season.</li>
 *   <li><b>Neither</b>: the biome's own climate, exactly as before. Also right with Almanac on the
 *   server only, whose seasonal biome copies already carry the season's climate.</li>
 * </ul>
 *
 * <p>{@link #changes()} counts climate changes (a new sub-season, a rain event reaching other
 * climates), so features that keep what they worked out per biome or per chunk can drop it.
 * Client thread only, except {@link SeasonalCopies}, which chunk scans may use anywhere.
 */
public final class LocalClimate {
    public static final String ALMANAC_ID = "almanac";
    /** Seasons mods known by their public pages, by Fabric mod id. */
    static final List<String> SEASONS_MOD_IDS = List.of("sereneseasons", "seasons", "eclipticseasons");

    enum Kind { ALMANAC, SEASONS_MOD, VANILLA }

    private static ClimateSource source = new VanillaClimate();
    private static final ClimateWatch WATCH = new ClimateWatch();
    private static int changes;
    private static boolean inWorld;

    private LocalClimate() {
    }

    /** Called once from the client entrypoint: picks the source and starts watching for changes. */
    public static void register() {
        Kind kind = choose(FabricLoader.getInstance()::isModLoaded);
        source = create(kind);
        LiteLightsClient.LOG.info("Weather climate from: {}", source.name());
        ClientTickEvents.END_CLIENT_TICK.register(LocalClimate::tick);
    }

    static Kind choose(Predicate<String> loaded) {
        if (loaded.test(ALMANAC_ID)) {
            return Kind.ALMANAC;
        }
        for (String id : SEASONS_MOD_IDS) {
            if (loaded.test(id)) {
                return Kind.SEASONS_MOD;
            }
        }
        return Kind.VANILLA;
    }

    private static ClimateSource create(Kind kind) {
        return switch (kind) {
            case ALMANAC -> almanac();
            case SEASONS_MOD -> new SeasonsModClimate();
            case VANILLA -> new VanillaClimate();
        };
    }

    /**
     * Almanac's API. fabric.mod.json refuses an Almanac older than 0.1.2, so it is always there; if
     * it ever weren't, the client level's precipitation lookup, which Almanac hooks, is asked as for
     * any seasons mod.
     */
    private static ClimateSource almanac() {
        try {
            return AlmanacClimate.create();
        } catch (LinkageError olderAlmanac) {
            LiteLightsClient.LOG.warn("Almanac's API isn't there ({}); asking the game's lookups instead", olderAlmanac.toString());
            return new SeasonsModClimate();
        }
    }

    /** The source's name, for the log and QA summaries. */
    public static String sourceName() {
        return source.name();
    }

    /** What falls at a position whenever it rains (the biome's {@code getPrecipitationAt}, seasons included). */
    public static Biome.Precipitation fallAt(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return source.fallAt(level, biome, pos);
    }

    /** As {@link #fallAt(ClientLevel, Holder, BlockPos)}, for the biome the level has at the position. */
    public static Biome.Precipitation fallAt(ClientLevel level, BlockPos pos) {
        return source.fallAt(level, level.getBiome(pos), pos);
    }

    /** Whether it is cold enough for snow at a position (the biome's {@code coldEnoughToSnow}, seasons included). */
    public static boolean coldEnoughToSnow(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return source.coldEnoughToSnow(level, biome, pos);
    }

    /**
     * Whether the season leaves a place dry and hot: sandy land has sandstorms and desert heat only
     * then. Always so without a seasons mod, where the land alone decides.
     */
    public static boolean dryHeat(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return source.dryHeat(level, biome, pos);
    }

    /**
     * The biome whose climate holds at a position now, for what is worked out from a biome's
     * temperature (the aurora's coldness, the kind of place): with Almanac its sub-season's copy.
     */
    public static Holder<Biome> seasonalBiome(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return source.seasonalBiome(level, biome, pos);
    }

    /**
     * The world's rain level, 0 to 1, for a sandstorm's strength: Almanac gives each player only their
     * share of the rain, which says nothing about a desert's storm while it rains somewhere else.
     */
    public static float worldRain(ClientLevel level) {
        return source.worldRain(level);
    }

    /** The world's thunder level, 0 to 1, for a haboob. */
    public static float worldThunder(ClientLevel level) {
        return source.worldThunder(level);
    }

    /** The id to match a biome by: a seasonal copy's original's, any other biome's own. */
    public static @Nullable ResourceKey<Biome> idOf(Holder<Biome> biome) {
        return SeasonalCopies.originalKey(biome);
    }

    /** Goes up by one whenever the climate changed somewhere; caches built under another count are stale. */
    public static int changes() {
        return changes;
    }

    /** From a source that is told of a change (Almanac's event). */
    static void noteChange() {
        changes++;
    }

    private static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            if (inWorld) {
                inWorld = false;
                WATCH.forget();
                source.forget();
            }
            return;
        }
        inWorld = true;
        source.tick(level);
        if (WATCH.changed(level, source, client.player.getBlockX(), client.player.getBlockZ())) {
            noteChange();
        }
    }
}
