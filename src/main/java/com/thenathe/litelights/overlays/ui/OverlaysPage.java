package com.thenathe.litelights.overlays.ui;

import com.mojang.blaze3d.Blaze3D;
import com.thenathe.litelights.config.CustomRow;
import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.config.PageContent;
import com.thenathe.litelights.config.Pages;
import com.thenathe.litelights.config.RowState;
import com.thenathe.litelights.overlays.OverlayRenderer;
import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.overlays.config.BlockEntry;
import com.thenathe.litelights.overlays.config.DefaultHierarchy;
import com.thenathe.litelights.overlays.config.HardnessGroup;
import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.continuity.VarietyMethod;
import com.thenathe.litelights.overlays.export.PackExporter;
import com.thenathe.litelights.overlays.generation.BuildCost;
import com.thenathe.litelights.overlays.generation.EdgeShape;
import com.thenathe.litelights.overlays.generation.EdgeStyle;
import com.thenathe.litelights.overlays.generation.EdgeVariety;
import com.thenathe.litelights.overlays.generation.GenerationReport;
import com.thenathe.litelights.overlays.pack.OverlayBuilds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The Overlays page of the LiteLights settings: every Dynamic Overlays setting, the hierarchy
 * editor, per-block styles, export and help. The rows are bound to {@link OverlayConfig}, which is
 * saved in litelights.json under {@code modules.overlays}.
 *
 * <p>Overlays are textures, so a change only shows after a resource reload. {@link OverlaysSettingsSession}
 * rebuilds them once when the player leaves the settings, if anything that affects them changed;
 * "Rebuild now" does it on the spot.
 */
public final class OverlaysPage {
    /** The feature the page's master switch reports: ready, off with a reason, or a failed build. */
    public static final Feature FEATURE = Feature.of("overlays");

    /** Block row choices: off, then follow the fallback, then each style, then receive only. */
    private static final int CHOICE_OFF = 0;
    private static final int CHOICE_DEFAULT = 1;
    private static final int FIRST_STYLE_CHOICE = 2;
    private static final int CHOICE_RECEIVE_ONLY = FIRST_STYLE_CHOICE + EdgeStyle.values().length;

    private OverlaysPage() {
    }

    public static void register() {
        OverlayRenderer.Choice choice = OverlaysModule.choice();
        if (choice.active()) {
            FEATURE.markReady();
        } else {
            FEATURE.markUnavailable("litelights.overlays.off." + choice.reason().name().toLowerCase(Locale.ROOT));
        }
        Pages.contribute(Pages.OVERLAYS, OverlaysPage::build);
        OverlaysSettingsSession.register();
    }

    private static void build(PageContent page) {
        OverlayConfig config = OverlayConfig.get();
        OverlayConfig defaults = new OverlayConfig();
        BooleanSupplier usable = OverlaysModule::active;
        addMainSection(page, config, defaults, usable);
        addVarietySection(page, config, defaults, usable);
        addSameGroupSection(page, config, defaults, usable);
        addSizeSection(page, config, defaults, usable);
        addBuildSection(page);
        addBlocksSection(page, config, usable);
        addExportSection(page);
        addHelpSection(page);
    }

    private static void addMainSection(PageContent page, OverlayConfig config, OverlayConfig defaults, BooleanSupplier usable) {
        page.section("overlays_main", text("section.overlays"), false)
            .note(statusLine())
            .row(CustomRow.toggle(setting("enabled"), description("enabled"), defaults.enabled,
                    () -> flag(config.enabled), value -> config.enabled = value != 0)
                .state(OverlaysPage::masterRowState)
                .enabledWhen(usable))
            .row(CustomRow.choice(setting("fallback_style"), description("fallback_style"),
                    EdgeShape.STYLES.indexOf(EdgeStyle.ORGANIC), names(EdgeShape.STYLES),
                    () -> EdgeShape.STYLES.indexOf(config.fallbackStyle()),
                    value -> config.fallbackStyle = EdgeShape.STYLES.get(value).id())
                .enabledWhen(usable))
            .row(CustomRow.toggle(setting("force_fallback"), description("force_fallback"), defaults.forceFallbackStyle,
                    () -> flag(config.forceFallbackStyle), value -> config.forceFallbackStyle = value != 0)
                .enabledWhen(usable))
            .row(CustomRow.slider(setting("edge_reach"), description("edge_reach"), defaults.edgeReach,
                    OverlayConfig.MIN_REACH, OverlayConfig.MAX_REACH, 1,
                    value -> Component.translatable("litelights.overlays.reach", value),
                    config::clampedReach, value -> config.edgeReach = value)
                .enabledWhen(usable))
            .row(CustomRow.slider(setting("edge_roughness"), description("edge_roughness"), defaults.edgeRoughness, 0, 100, 5,
                    value -> Component.literal(value + "%"), () -> config.edgeRoughness, value -> config.edgeRoughness = value)
                .enabledWhen(usable));
    }

    private static void addVarietySection(PageContent page, OverlayConfig config, OverlayConfig defaults, BooleanSupplier usable) {
        page.section("overlays_variety", text("section.variety"), false)
            .row(CustomRow.choice(setting("edge_variety"), description("edge_variety"), EdgeVariety.NORMAL.ordinal(),
                    varietyNames(), () -> config.edgeVariety().ordinal(),
                    value -> config.edgeVariety = EdgeVariety.values()[value].id())
                .enabledWhen(() -> usable.getAsBoolean() && !config.plainOverlays))
            .row(CustomRow.toggle(setting("spill_down_steps"), description("spill_down_steps"), defaults.spillDownSteps,
                    () -> flag(config.spillDownSteps), value -> config.spillDownSteps = value != 0)
                .enabledWhen(usable))
            .row(CustomRow.toggle(setting("plain_overlays"), description("plain_overlays"), defaults.plainOverlays,
                    () -> flag(config.plainOverlays), value -> config.plainOverlays = value != 0)
                .enabledWhen(usable))
            .note(drawingLine());
    }

    private static void addSameGroupSection(PageContent page, OverlayConfig config, OverlayConfig defaults, BooleanSupplier usable) {
        page.section("overlays_same_group", text("section.same_group"), false)
            .row(CustomRow.toggle(setting("blend_within_group"), description("blend_within_group"), defaults.blendWithinGroup,
                    () -> flag(config.blendWithinGroup), value -> config.blendWithinGroup = value != 0)
                .enabledWhen(usable));
    }

    private static void addSizeSection(PageContent page, OverlayConfig config, OverlayConfig defaults, BooleanSupplier usable) {
        page.section("overlays_large_textures", text("section.large_textures"), false)
            .row(CustomRow.choice(setting("largest_overlay_size"), description("largest_overlay_size"),
                    sizeChoice(defaults.largestOverlaySize), sizeNames(), () -> sizeChoice(config.largestOverlaySize),
                    value -> config.largestOverlaySize = OverlayConfig.OVERLAY_SIZE_CHOICES[value])
                .enabledWhen(usable));
    }

    private static void addBuildSection(PageContent page) {
        page.section("overlays_build", text("section.build"), false)
            .note(buildLine());
        if (OverlaysModule.active()) {
            page.action(setting("rebuild"), description("rebuild"), text("action.rebuild"), OverlaysSettingsSession::rebuildNow)
                .subScreen(setting("edit_hierarchy"), description("edit_hierarchy"), text("action.edit_hierarchy"),
                    HierarchyEditorScreen::new);
        }
    }

    private static void addBlocksSection(PageContent page, OverlayConfig config, BooleanSupplier usable) {
        page.section("overlays_blocks", text("page.blocks"), true)
            .note(text("note.blocks"));
        Map<String, BlockEntry> defaultEntries = defaultEntries();
        for (HardnessGroup group : config.groups) {
            page.note(Component.literal(group.name).withStyle(ChatFormatting.BOLD));
            for (BlockEntry entry : group.blocks) {
                page.row(blockRow(config, entry, defaultEntries.get(entry.id)).enabledWhen(usable));
            }
        }
    }

    private static void addExportSection(PageContent page) {
        page.section("overlays_export", text("section.export"), true)
            .note(text("note.export"));
        if (OverlaysModule.active()) {
            page.action(setting("export_pack"), description("export_pack"), text("action.export"), OverlaysPage::export);
        }
        page.action(setting("open_folder"), description("open_folder"), text("action.open_folder"),
            () -> Blaze3D.openPath(Minecraft.getInstance().getResourcePackDirectory()));
    }

    private static void addHelpSection(PageContent page) {
        page.section("overlays_help", text("section.help"), true);
        for (String topic : List.of("how_it_works", "variety_help", "steps_help", "grass_and_sand", "other_mods",
                "when_rebuilt", "texture_sizes")) {
            page.note(text("caption." + topic).copy().withStyle(ChatFormatting.BOLD))
                .note(text("note." + topic));
        }
        GenerationReport report = OverlayBuilds.lastReport();
        if (!report.notes().isEmpty()) {
            page.note(text("caption.build_notes").copy().withStyle(ChatFormatting.BOLD));
            report.notes().forEach(note -> page.note(Component.literal(note)));
        }
        if (!report.skipped().isEmpty()) {
            page.note(Component.translatable("litelights.overlays.caption.skipped", report.skipped().size())
                .withStyle(ChatFormatting.BOLD));
            report.skipped().forEach(reason -> page.note(Component.literal(reason)));
        }
    }

    /**
     * The master switch shows a failed build with its Retry button. When the module is off, the
     * full-width note above it already says why, so the row is only greyed out.
     */
    private static RowState masterRowState() {
        RowState state = FEATURE.state();
        return state instanceof RowState.Unavailable ? RowState.READY : state;
    }

    /** That Continuity draws the overlays, or why the module is off. */
    private static Component statusLine() {
        OverlayRenderer.Choice choice = OverlaysModule.choice();
        return switch (choice.renderer()) {
            case CONTINUITY -> text("status.drawn_by_continuity");
            case NONE -> text("off." + choice.reason().name().toLowerCase(Locale.ROOT));
        };
    }

    private static Component buildLine() {
        if (!OverlaysModule.active()) {
            return Component.empty();
        }
        if (OverlayBuilds.isBuilding()) {
            return text("status.building");
        }
        GenerationReport report = OverlayBuilds.lastReport();
        if (report == GenerationReport.NONE) {
            return text("status.none");
        }
        BuildCost cost = BuildCost.last();
        Component built = Component.translatable("litelights.overlays.status.built", report.tileSets(), report.sources(),
            report.millis(), drawingName(report), report.skipped().size());
        if (cost.allocatedBytes() < 0) {
            return built;
        }
        return built.copy().append(" ").append(Component.translatable("litelights.overlays.status.cost", cost.millis(),
            cost.allocatedBytes() / (1024 * 1024)));
    }

    private static Component drawingName(GenerationReport report) {
        EdgeVariety variety = EdgeVariety.byId(report.drawing(), null);
        return variety == null
            ? text("drawing.plain")
            : Component.translatable("litelights.overlays.drawing.variety", variety.displayName());
    }

    /** Says what draws the overlays now, and why it's the plain method if that wasn't the player's choice. */
    private static Component drawingLine() {
        if (!OverlaysModule.active()) {
            return Component.empty();
        }
        if (OverlayConfig.get().plainOverlays) {
            return text("drawing.chosen_plain");
        }
        if (!VarietyMethod.isAvailable() && !VarietyMethod.problem().isEmpty()) {
            return Component.translatable("litelights.overlays.drawing.fallback", VarietyMethod.problem());
        }
        return text("drawing.in_use");
    }

    /**
     * One row per block: off, the fallback style, a style of its own, or receive only. Turning a
     * block off keeps its style, so turning it back on with "Default" or a style is a choice.
     */
    private static CustomRow blockRow(OverlayConfig config, BlockEntry entry, BlockEntry defaultEntry) {
        List<Component> choices = new ArrayList<>();
        choices.add(text("block.off"));
        choices.add(Component.translatable("litelights.overlays.block.default", config.fallbackStyle().displayName()));
        choices.addAll(styleNames());
        choices.add(text("block.receive_only"));
        int defaultChoice = defaultEntry == null ? CHOICE_DEFAULT : choiceFor(defaultEntry);
        return CustomRow.choice(blockName(entry.id), Component.translatable("litelights.overlays.block.description", entry.id),
            defaultChoice, choices, () -> choiceFor(entry), value -> apply(entry, value));
    }

    private static int choiceFor(BlockEntry entry) {
        if (!entry.enabled) {
            return CHOICE_OFF;
        }
        if (entry.receivesOnly()) {
            return CHOICE_RECEIVE_ONLY;
        }
        if (entry.usesDefaultStyle()) {
            return CHOICE_DEFAULT;
        }
        return FIRST_STYLE_CHOICE + EdgeStyle.byId(entry.style, EdgeStyle.ORGANIC).ordinal();
    }

    private static void apply(BlockEntry entry, int choice) {
        if (choice == CHOICE_OFF) {
            entry.enabled = false;
            return;
        }
        entry.enabled = true;
        if (choice == CHOICE_DEFAULT) {
            entry.style = BlockEntry.USE_DEFAULT;
        } else if (choice == CHOICE_RECEIVE_ONLY) {
            entry.style = BlockEntry.RECEIVE_ONLY;
        } else {
            entry.style = EdgeStyle.values()[choice - FIRST_STYLE_CHOICE].id();
        }
    }

    private static Map<String, BlockEntry> defaultEntries() {
        Map<String, BlockEntry> entries = new HashMap<>();
        for (HardnessGroup group : DefaultHierarchy.groups()) {
            group.blocks.forEach(entry -> entries.put(entry.id, entry));
        }
        return entries;
    }

    /** The size choice for a saved limit; a value that isn't a choice shows as the nearest one. */
    private static int sizeChoice(int limit) {
        int[] choices = OverlayConfig.OVERLAY_SIZE_CHOICES;
        if (limit <= 0 || limit > OverlayConfig.MAX_OVERLAY_SIZE) {
            return choices.length - 1;
        }
        int nearest = 0;
        for (int i = 0; i < choices.length; i++) {
            if (Math.abs(choices[i] - limit) < Math.abs(choices[nearest] - limit)) {
                nearest = i;
            }
        }
        return nearest;
    }

    private static List<Component> styleNames() {
        return names(List.of(EdgeStyle.values()));
    }

    /** Bricks needs a brick texture, so the fallback, which any block may use, offers only the drawn styles. */
    private static List<Component> names(List<EdgeStyle> styles) {
        List<Component> names = new ArrayList<>();
        for (EdgeStyle style : styles) {
            names.add(style.displayName());
        }
        return names;
    }

    private static List<Component> varietyNames() {
        List<Component> names = new ArrayList<>();
        for (EdgeVariety variety : EdgeVariety.values()) {
            names.add(variety.displayName());
        }
        return names;
    }

    private static List<Component> sizeNames() {
        List<Component> names = new ArrayList<>();
        for (int size : OverlayConfig.OVERLAY_SIZE_CHOICES) {
            names.add(Component.translatable("litelights.overlays.size.texels", size));
        }
        return names;
    }

    static Component blockName(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
            return BuiltInRegistries.BLOCK.getValue(id).getName();
        }
        return Component.literal(blockId);
    }

    private static void export() {
        Minecraft minecraft = Minecraft.getInstance();
        SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            text("toast.exporting"), null);
        PackExporter.exportToResourcePacks().whenComplete((path, failure) -> minecraft.execute(() -> {
            Component title = failure == null ? text("toast.exported") : text("toast.export_failed");
            Component detail = failure == null ? Component.literal(path.getFileName().toString()) : null;
            SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, detail);
        }));
    }

    private static Component setting(String id) {
        return text("setting." + id);
    }

    private static Component description(String id) {
        return text("setting." + id + ".description");
    }

    static Component text(String key) {
        return Component.translatable("litelights.overlays." + key);
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }
}
