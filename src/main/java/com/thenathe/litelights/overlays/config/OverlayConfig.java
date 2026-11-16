package com.thenathe.litelights.overlays.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.overlays.generation.EdgeStyle;
import com.thenathe.litelights.overlays.generation.EdgeVariety;
import com.thenathe.litelights.overlays.generation.SourceTexture;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Everything the player can set for the overlays module, saved in {@code config/litelights.json}
 * under {@code modules.overlays} (see {@link OverlayConfigStore}, which also imports the
 * standalone Dynamic Overlays mod's settings on first run).
 *
 * <p>Upgrades must never lose data, so loading is forgiving: missing fields take their defaults,
 * and unknown style ids and unknown block ids are kept as written.
 */
public final class OverlayConfig {
    public static final int CURRENT_VERSION = 5;
    /** The first file version in which brick masonry spills in the Bricks style by default. */
    static final int BRICKS_VERSION = 5;
    public static final int MIN_REACH = 1;
    public static final int MAX_REACH = 8;
    /** Choices for {@link #largestOverlaySize}. */
    public static final int[] OVERLAY_SIZE_CHOICES = {64, 128, 256, 512};
    /**
     * The largest overlay size there is. Overlays are only ever made at the size of the textures in
     * use, so a high limit costs nothing until a pack that large is active.
     */
    public static final int MAX_OVERLAY_SIZE = 512;

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static OverlayConfig current;
    private static OverlayConfigStore.ModuleData storage;

    public int configVersion = CURRENT_VERSION;

    public boolean enabled = true;
    public String fallbackStyle = EdgeStyle.ORGANIC.id();
    public boolean forceFallbackStyle = false;
    /** How far an overlay reaches onto the harder block, in sixteenths of a block. */
    public int edgeReach = 4;
    /** How much the edge wanders around that reach, 0 to 100. */
    public int edgeRoughness = 60;
    public boolean blendWithinGroup = false;
    /** How many variants of each edge piece to make, by {@link EdgeVariety} id. */
    public String edgeVariety = EdgeVariety.NORMAL.id();
    /** Whether a block one step up spills onto the top of a lower block beside it. */
    public boolean spillDownSteps = true;
    /** Draw with Continuity's plain overlay method, as exported packs do, instead of the overlays module's own. */
    public boolean plainOverlays = false;
    /**
     * Textures larger than this many texels across are drawn at this size. Dynamic Overlays 0.2.0 offered 0 for "no
     * limit"; that, and anything above {@link #MAX_OVERLAY_SIZE}, now means the maximum. The saved
     * number is left as it is.
     */
    public int largestOverlaySize = MAX_OVERLAY_SIZE;

    public List<HardnessGroup> groups = DefaultHierarchy.groups();
    /**
     * Every default block this file has already been offered. A later version that adds new
     * defaults adds only the ones listed nowhere here, so a block the player removed stays removed.
     */
    public Set<String> offeredDefaults = new LinkedHashSet<>(DefaultHierarchy.blockIds());

    public static synchronized OverlayConfig get() {
        if (current == null) {
            storage = new LiteLightsModuleData();
            current = OverlayConfigStore.load(storage, FabricLoader.getInstance().getConfigDir()).config();
        }
        return current;
    }

    /** Writes the settings into litelights.json and saves it. */
    public static synchronized void save() {
        OverlayConfigStore.save(get(), storage);
    }

    /**
     * The style blocks set to "Default" use. Bricks needs a brick texture, so it is never the
     * fallback; a file that names it (only possible by hand) falls back to Organic, unchanged on disk.
     */
    public EdgeStyle fallbackStyle() {
        EdgeStyle style = EdgeStyle.byId(fallbackStyle, EdgeStyle.ORGANIC);
        return style == EdgeStyle.BRICKS ? EdgeStyle.ORGANIC : style;
    }

    public EdgeVariety edgeVariety() {
        return EdgeVariety.byId(edgeVariety, EdgeVariety.NORMAL);
    }

    /** The size cap in texels. */
    public int overlaySizeLimit() {
        if (largestOverlaySize <= 0 || largestOverlaySize > MAX_OVERLAY_SIZE) {
            return MAX_OVERLAY_SIZE;
        }
        return Math.max(SourceTexture.SMALLEST_SIZE, largestOverlaySize);
    }

    public int clampedReach() {
        return Math.clamp(edgeReach, MIN_REACH, MAX_REACH);
    }

    public BlockEntry find(String blockId) {
        for (HardnessGroup group : groups) {
            for (BlockEntry entry : group.blocks) {
                if (blockId.equals(entry.id)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /** A deep copy, so a build on a worker thread never sees edits made while it runs. */
    public OverlayConfig snapshot() {
        return GSON.fromJson(GSON.toJson(this), OverlayConfig.class);
    }

    public List<HardnessGroup> copyOfGroups() {
        List<HardnessGroup> copy = new ArrayList<>();
        groups.forEach(group -> copy.add(group.copy()));
        return copy;
    }

    /**
     * A fingerprint of everything that changes the generated overlays. The settings screen compares
     * it before and after editing to decide whether a resource reload is needed.
     */
    public String generationFingerprint() {
        StringBuilder text = new StringBuilder();
        text.append(enabled).append('|').append(fallbackStyle()).append('|').append(forceFallbackStyle)
            .append('|').append(clampedReach()).append('|').append(edgeRoughness).append('|').append(blendWithinGroup)
            .append('|').append(edgeVariety()).append('|').append(spillDownSteps).append('|').append(overlaySizeLimit())
            .append('|').append(plainOverlays);
        for (HardnessGroup group : groups) {
            text.append("|[");
            for (BlockEntry entry : group.blocks) {
                text.append(entry.id).append(entry.enabled ? '+' : '-').append(entry.style).append(',');
            }
            text.append(']');
        }
        return text.toString();
    }

    /** Fills in what an older file lacks and adds default blocks it hasn't been offered yet. */
    OverlayConfig upgraded() {
        OverlayConfig defaults = new OverlayConfig();
        fallbackStyle = Objects.requireNonNullElse(fallbackStyle, defaults.fallbackStyle);
        // Added in version 2. A file from Dynamic Overlays 0.1.0 doesn't have them, so they take their defaults
        // through the constructor; an explicit null gets the default too.
        edgeVariety = Objects.requireNonNullElse(edgeVariety, defaults.edgeVariety);
        // Dynamic Overlays 0.2.0 (file version 2) saved its default size limit, 128x, like any other
        // value, so a chosen 128 can't be told from the default. Every 128 in a version 2 file moves
        // to the new default; any other value stays. Files from 0.2.1 on keep a 128 as a choice.
        if (configVersion == 2 && largestOverlaySize == 128) {
            largestOverlaySize = MAX_OVERLAY_SIZE;
            OverlaysModule.LOG.info("Largest overlay size moved from Dynamic Overlays 0.2.0's default 128x to the new default {}x",
                MAX_OVERLAY_SIZE);
        }
        if (groups == null) {
            groups = defaults.groups;
        }
        groups.removeIf(Objects::isNull);
        for (HardnessGroup group : groups) {
            if (group.name == null) {
                group.name = "";
            }
            if (group.blocks == null) {
                group.blocks = new ArrayList<>();
            }
            group.blocks.removeIf(entry -> entry == null || entry.id == null);
            group.blocks.forEach(entry -> entry.style = Objects.requireNonNullElse(entry.style, BlockEntry.USE_DEFAULT));
        }
        if (offeredDefaults == null) {
            // With no record, assume every current default was offered: re-adding blocks the
            // player may have removed would be worse than missing a new one.
            offeredDefaults = new LinkedHashSet<>(DefaultHierarchy.blockIds());
        }
        if (configVersion < BRICKS_VERSION) {
            moveUntouchedMasonryToBricks();
        }
        offerNewDefaults(defaults);
        configVersion = CURRENT_VERSION;
        return this;
    }

    /**
     * Up to 0.1.6 every masonry block received only. A brick or tile block that came from the
     * defaults (it is in {@link #offeredDefaults}) and still has that old default now spills in
     * the Bricks style, like a fresh install. Any other style is the player's choice and stays,
     * and so does a block the player added themselves. Runs once, for files older than version 5.
     */
    private void moveUntouchedMasonryToBricks() {
        int moved = 0;
        for (HardnessGroup group : groups) {
            for (BlockEntry entry : group.blocks) {
                if (DefaultHierarchy.BRICK_MASONRY.contains(entry.id) && offeredDefaults.contains(entry.id) && entry.receivesOnly()) {
                    entry.style = EdgeStyle.BRICKS.id();
                    moved++;
                }
            }
        }
        if (moved > 0) {
            OverlaysModule.LOG.info("{} masonry blocks still on the old default (receive only) now spill as Bricks", moved);
        }
    }

    private void offerNewDefaults(OverlayConfig defaults) {
        for (int groupIndex = 0; groupIndex < defaults.groups.size(); groupIndex++) {
            HardnessGroup defaultGroup = defaults.groups.get(groupIndex);
            for (BlockEntry entry : defaultGroup.blocks) {
                if (offeredDefaults.contains(entry.id)) {
                    continue;
                }
                offeredDefaults.add(entry.id);
                if (find(entry.id) == null) {
                    groupNamed(defaultGroup.name, groupIndex).blocks.add(entry.copy());
                }
            }
        }
    }

    private HardnessGroup groupNamed(String name, int fallbackIndex) {
        for (HardnessGroup group : groups) {
            if (name.equals(group.name)) {
                return group;
            }
        }
        HardnessGroup created = new HardnessGroup(name, List.of());
        groups.add(Math.min(fallbackIndex, groups.size()), created);
        return created;
    }
}
