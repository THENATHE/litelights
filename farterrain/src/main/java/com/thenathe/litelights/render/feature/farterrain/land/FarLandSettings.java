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

    /** Background workers that build far terrain's meshes, besides the one that stores it. */
    public enum Threads {
        AUTO(0), ONE(1), TWO(2), THREE(3), FOUR(4);

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
    /** Seed generation: land not visited yet, predicted from the world's seed (off by default). */
    public final ToggleSetting seedGeneration;

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
        // Its own section under the switch, so its status and buttons sit with it. Off by default:
        // it keeps processor threads busy while land fills in.
        seedGeneration = rows.row("far_terrain.seed_generation").at(ModuleSettings.FAR_TERRAIN_PAGE, SEED_SECTION).cost(RowCost.HIGH)
            .feature(FEATURE).needs(enabled).tag(EXPERIMENTAL).toggle(false);
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

    /** Mesh workers: Auto is a quarter of the cores, one to four. */
    int meshThreads() {
        int count = threads.get().count;
        if (count == 0) {
            count = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 4);
        }
        return count;
    }
}
