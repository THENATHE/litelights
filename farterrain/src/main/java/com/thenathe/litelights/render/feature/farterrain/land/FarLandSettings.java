package com.thenathe.litelights.render.feature.farterrain.land;

import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.ModuleSettings.ChoiceSetting;
import com.thenathe.litelights.api.ModuleSettings.RowCost;
import com.thenathe.litelights.api.ModuleSettings.ToggleSetting;
import com.thenathe.litelights.api.ModuleSettings;

/**
 * Far terrain's rows on the Far Terrain page (docs/SETTINGS-API.md), declared by the module
 * through the internal API. The switch is off by default and tagged Experimental: by LiteLights'
 * rule, defaults keep today's picture and cost, and today nothing is drawn past the render
 * distance. With it off nothing runs, nothing is stored and nothing is drawn.
 */
public final class FarLandSettings {
    public static final String FEATURE = "far_terrain";
    private static final String SECTION = "far_land";
    static final String SEED_SECTION = "far_seed_generation";
    private static final String EXPERIMENTAL = "litelights.tag.experimental";

    /** How far far terrain reaches. */
    public enum Distance {
        KM_1(1024), KM_2(2048), KM_4(4096), KM_8(8192), KM_16(16384);

        public final int blocks;

        Distance(int blocks) {
            this.blocks = blocks;
        }
    }

    /**
     * How large a voxel may look before its tile splits: a tile of level L is drawn from
     * {@code levelDistance << L} blocks out, so a voxel covers about the same few pixels everywhere.
     */
    public enum Detail {
        LOW(128), MEDIUM(256), HIGH(512), ULTRA(1024);

        public final int levelDistance;

        Detail(int levelDistance) {
            this.levelDistance = levelDistance;
        }
    }

    /** The most video memory the quads may take; Auto follows the distance. */
    public enum VideoMemory {
        AUTO(0), MB_256(256), MB_512(512), GB_1(1024), GB_2(2048), GB_4(4096);

        public final int megabytes;

        VideoMemory(int megabytes) {
            this.megabytes = megabytes;
        }
    }

    /**
     * Background workers that build far terrain's meshes and predict land from the seed, besides
     * the one that stores it. All but two leaves two of the processor's threads to the game.
     */
    public enum Threads {
        AUTO(0), ONE(1), TWO(2), THREE(3), FOUR(4), SIX(6), EIGHT(8), ALL_BUT_TWO(-1);

        public final int count;

        Threads(int count) {
            this.count = count;
        }
    }

    public final ToggleSetting enabled;
    /**
     * How far water is shaded: as the near water is (its colour and depth, the land under it seen
     * through, traced reflections, the swell's grain; needs water's scene copy), or plainly, a flat
     * colour by biome and depth with the sky's sheen at glancing angles, which costs a fraction and
     * needs no scene copy. Both ease from the near water's own look at the render distance's edge.
     */
    public enum WaterLook {
        FULL, PLAIN
    }

    public final ChoiceSetting<Distance> distance;
    public final ChoiceSetting<Detail> detail;
    public final ChoiceSetting<VideoMemory> videoMemory;
    public final ChoiceSetting<Threads> threads;
    public final ToggleSetting lights;
    public final ChoiceSetting<WaterLook> waterLook;
    /**
     * Snow on far terrain: far land shows Almanac's snow as it lies now, by place under its regional
     * weather. Its own row since far-snow-115 (it was under Visual snow, off by default, so far land
     * kept the snow it was seen with unless both were on); the saved name is the old row's, so a
     * value saved before carries over.
     */
    public final ToggleSetting farSnow;
    /** Seed generation: land not visited yet, predicted from the world's seed (off by default). */
    public final ToggleSetting seedGeneration;
    /**
     * On a world's first fill from the seed, far land stays hidden (the game's own fog at the render
     * distance) until its first set of levels is built, so the coarsest level never shows up close.
     */
    public final ToggleSetting waitForDetail;

    /** Declares the rows through these settings: the module's own, or core's in its tests. */
    public FarLandSettings(ModuleSettings rows) {
        enabled = rows.row("far_terrain.enabled").at(ModuleSettings.FAR_TERRAIN_PAGE, SECTION).cost(RowCost.MEDIUM)
            .feature(FEATURE).tag(EXPERIMENTAL).toggle(false);
        distance = rows.row("far_terrain.distance").at(ModuleSettings.FAR_TERRAIN_PAGE, SECTION).cost(RowCost.MEDIUM)
            .feature(FEATURE).needs(enabled).choice(Distance.class, Distance.KM_4);
        detail = rows.row("far_terrain.detail").at(ModuleSettings.FAR_TERRAIN_PAGE, SECTION).cost(RowCost.MEDIUM)
            .feature(FEATURE).needs(enabled).choice(Detail.class, Detail.MEDIUM);
        videoMemory = rows.row("far_terrain.video_memory").at(ModuleSettings.FAR_TERRAIN_PAGE, SECTION)
            .feature(FEATURE).needs(enabled).choice(VideoMemory.class, VideoMemory.AUTO);
        threads = rows.row("far_terrain.threads").at(ModuleSettings.FAR_TERRAIN_PAGE, SECTION)
            .feature(FEATURE).needs(enabled).choice(Threads.class, Threads.AUTO);
        lights = rows.row("far_terrain.lights").at(ModuleSettings.FAR_TERRAIN_PAGE, SECTION).cost(RowCost.LOW)
            .feature(FEATURE).needs(enabled).toggle(true);
        waterLook = rows.row("far_terrain.water_look").at(ModuleSettings.FAR_TERRAIN_PAGE, SECTION).cost(RowCost.MEDIUM)
            .feature(FEATURE).needs(enabled).choice(WaterLook.class, WaterLook.FULL);
        farSnow = rows.row("weather.visual_snow.far").at(ModuleSettings.FAR_TERRAIN_PAGE, SECTION).cost(RowCost.LOW)
            .feature(FEATURE).needs(enabled).toggle(true);
        // Its own section under the switch, so its status and buttons sit with it. Off by default:
        // it keeps processor threads busy while land fills in.
        seedGeneration = rows.row("far_terrain.seed_generation").at(ModuleSettings.FAR_TERRAIN_PAGE, SEED_SECTION).cost(RowCost.HIGH)
            .feature(FEATURE).needs(enabled).tag(EXPERIMENTAL).toggle(false);
        // On by default: it only changes the first minute or two of a world's first fill, and it
        // keeps the coarse stand-ins out of sight then (user, 2026-11-25).
        waitForDetail = rows.row("far_terrain.seed_wait_for_detail").at(ModuleSettings.FAR_TERRAIN_PAGE, SEED_SECTION)
            .feature(FEATURE).needs(seedGeneration).toggle(true);
    }

    static FarLandSettings declare() {
        return new FarLandSettings(LiteLightsApi.rows());
    }

    /** The video memory the quads may take, in bytes: Auto is 64 MiB per kilometre, at least 256 MiB. */
    long videoMemoryBytes() {
        int megabytes = videoMemory.get().megabytes;
        if (megabytes == 0) {
            megabytes = Math.max(256, distance.get().blocks / 1024 * 64);
        }
        return (long) megabytes << 20;
    }

    /** Mesh and prediction workers for this machine. */
    int meshThreads() {
        return meshThreads(threads.get(), Runtime.getRuntime().availableProcessors());
    }

    /**
     * Auto is half the processor's threads less two, one to eight (a quarter, one to four, up to
     * 1.1.4): six of sixteen, two of eight, one of four. A count is never more than the processor
     * has (DECISIONS, seedgen-speed-115).
     */
    static int meshThreads(Threads choice, int processors) {
        return switch (choice) {
            case AUTO -> Math.clamp(processors / 2 - 2, 1, 8);
            case ALL_BUT_TWO -> Math.max(1, processors - 2);
            default -> Math.clamp(choice.count, 1, Math.max(1, processors));
        };
    }
}
