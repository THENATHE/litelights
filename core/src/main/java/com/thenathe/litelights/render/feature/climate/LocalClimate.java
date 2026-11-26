package com.thenathe.litelights.render.feature.climate;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.frame.ShownWeather;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * The one place LiteLights' weather asks what the climate is somewhere: what falls (rain, snow or
 * nothing), whether it is cold enough to snow, and whether it is dry and hot enough for sandstorms.
 * No feature asks a biome's climate methods itself, because a biome only knows its all-year
 * climate, while a seasons mod changes the climate through the level's lookups, by season and region.
 *
 * <p>The source is picked once, as the client starts:
 * <ul>
 *   <li><b>Almanac</b> 1.0.0 or later installed on the client: its API ({@link AlmanacClimate}), so
 *   the season's climate and Almanac's season-changed event are known too. An older Almanac shares
 *   nothing with LiteLights: it counts as not installed, and {@link AlmanacNotice} says so.</li>
 *   <li><b>Another seasons mod</b> (Serene Seasons, Fabric Seasons, Ecliptic Seasons): the client
 *   level's precipitation lookup, which such mods hook so vanilla's own rain follows the season.</li>
 *   <li><b>Neither</b>: the biome's own climate, exactly as before.</li>
 * </ul>
 *
 * <p>{@link #changes()} counts climate changes (a new sub-season, a rain event reaching other
 * climates), so features that keep what they worked out per biome or per chunk can drop it.
 * Client thread only.
 */
public final class LocalClimate {
    public static final String ALMANAC_ID = "almanac";
    /** Seasons mods known by their public pages, by Fabric mod id. */
    static final List<String> SEASONS_MOD_IDS = List.of("sereneseasons", "seasons", "eclipticseasons");

    enum Kind { ALMANAC, SEASONS_MOD, VANILLA }

    private static ClimateSource source = new VanillaClimate();
    /** Whether the climate comes from an Almanac new enough for LiteLights, through its API. */
    private static boolean fromAlmanac;
    private static final ClimateWatch WATCH = new ClimateWatch();
    private static int changes;
    private static boolean inWorld;

    private LocalClimate() {
    }

    /** Called once from the client entrypoint: picks the source and starts watching for changes. */
    public static void register() {
        Kind kind = choose(FabricLoader.getInstance()::isModLoaded, AlmanacVersion.shared());
        source = create(kind);
        LiteLightsClient.LOG.info("Weather climate from: {}", source.name());
        AlmanacNotice.logAtStart();
        ClientTickEvents.END_CLIENT_TICK.register(LocalClimate::tick);
    }

    /** {@code almanacShared}: the installed Almanac is new enough for LiteLights (an older one counts as absent). */
    static Kind choose(Predicate<String> loaded, boolean almanacShared) {
        if (loaded.test(ALMANAC_ID) && almanacShared) {
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
     * Almanac's API, from an Almanac new enough for it. Should a later Almanac have dropped what
     * LiteLights links against, the weather goes by each biome's own climate, as beside an older one.
     */
    private static ClimateSource almanac() {
        try {
            ClimateSource almanac = AlmanacClimate.create();
            fromAlmanac = true;
            return almanac;
        } catch (LinkageError changedApi) {
            LiteLightsClient.LOG.warn("Almanac's API isn't as LiteLights knows it ({}); the weather goes by each biome's own climate",
                changedApi.toString());
            return new VanillaClimate();
        }
    }

    /**
     * Whether LiteLights takes the climate from Almanac's API (an Almanac new enough for it is
     * installed), so Almanac's other calls, such as its regional weather, may be made.
     */
    public static boolean fromAlmanac() {
        return fromAlmanac;
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
     * The biome's temperature now, before cooling with height, for what is worked out from it (the
     * aurora's coldness, the kind of place): with Almanac the season's, else {@code getBaseTemperature}.
     */
    public static float temperature(ClientLevel level, Holder<Biome> biome, BlockPos pos) {
        return source.temperature(level, biome, pos);
    }

    /**
     * The world's rain level to show, 0 to 1, for a sandstorm's strength and the snow gathering on
     * leaves: Almanac gives each player only their share of the rain, which says nothing about a
     * desert's storm while it rains somewhere else. With the Dynamic clouds it comes in and goes at
     * their pace, as the rain shown does (render.frame.ShownWeather).
     */
    public static float worldRain(ClientLevel level) {
        return ShownWeather.worldRain(level, source.worldRain(level));
    }

    /** The world's thunder level to show, 0 to 1, for a haboob. */
    public static float worldThunder(ClientLevel level) {
        return ShownWeather.worldThunder(level, source.worldThunder(level));
    }

    /** The world's real rain level, as the server has it, for the clouds' pace to follow. */
    public static float realWorldRain(ClientLevel level) {
        return source.worldRain(level);
    }

    /** Whether each rain event reaches only some climates (Almanac), so the world's weather can't foretell a player's. */
    public static boolean sharesRainOut() {
        return source.sharesRainOut();
    }

    /** The world's real thunder level (already times its rain), for the clouds' pace to follow. */
    public static float realWorldThunder(ClientLevel level) {
        return source.worldThunder(level);
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
                RegionalRain.forget();
            }
            return;
        }
        inWorld = true;
        AlmanacNotice.inWorld(client);
        source.tick(level);
        RegionalRain.tick(client);
        if (WATCH.changed(level, source, client.player.getBlockX(), client.player.getBlockZ())) {
            noteChange();
        }
    }
}
