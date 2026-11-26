package com.thenathe.litelights.gui;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.CustomRow;
import com.thenathe.litelights.config.DimensionOptions;
import com.thenathe.litelights.config.GradeTemplates;
import com.thenathe.litelights.config.LiteLightsConfig;
import com.thenathe.litelights.config.Option;
import com.thenathe.litelights.config.LiteLightsOptions.GradeTime;
import com.thenathe.litelights.config.LiteLightsOptions.GradeSchedule;
import com.thenathe.litelights.config.LiteLightsOptions;
import com.thenathe.litelights.config.PageContent;
import com.thenathe.litelights.config.Pages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/** The rows of the built-in pages that aren't options: recommendations, notes, compatibility notices, About. */
final class BuiltInPages {
    static final String LAMBDYNAMICLIGHTS = "lambdynlights";
    static final String LAMBDABETTERGRASS = "lambdabettergrass";
    static final String SODIUM = "sodium";
    static final String CONTINUITY = "continuity";
    static final String VISUALITY = "visuality";
    static final String SUBTLE_EFFECTS = "subtle_effects";

    /** The page that gathers every far terrain setting; its own id, between Water and Vegetation. */
    public static final String FAR_TERRAIN_PAGE = "far_terrain";

    private static boolean registered;

    private BuiltInPages() {
    }

    static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        Pages.placeSectionFirst(Pages.GENERAL, "getting_started");
        // Each storm type's sub-heading carries the weather preview's icon for it.
        PageModel.subheadingIcon(Pages.WEATHER, "storms/rain", Glyphs.RAIN);
        PageModel.subheadingIcon(Pages.WEATHER, "storms/thunderstorm", Glyphs.STORM);
        PageModel.subheadingIcon(Pages.WEATHER, "storms/snow", Glyphs.SNOW);
        PageModel.subheadingIcon(Pages.WEATHER, "storms/blizzard", Glyphs.BLIZZARD);
        PageModel.subheadingIcon(Pages.WEATHER, "storms/sandstorm", Glyphs.SANDSTORM);
        PageModel.subheadingIcon(Pages.WEATHER, "storms/haboob", Glyphs.SAND_THUNDER);
        Pages.contribute(Pages.GENERAL, BuiltInPages::gettingStarted);
        Pages.contribute(Pages.GENERAL, CompatibilityNotices::contribute);
        Pages.contribute(Pages.SKY, CloudRows::contribute);
        Pages.register(new Pages.PageInfo(FAR_TERRAIN_PAGE, "litelights.page.far_terrain", FAR_TERRAIN_PAGE, 750));
        Pages.contribute(FAR_TERRAIN_PAGE, BuiltInPages::farTerrain);
        Pages.register(new Pages.PageInfo(Pages.VISUAL_SNOW, "litelights.page.visual_snow", Pages.VISUAL_SNOW, 1050));
        Pages.contribute(Pages.VISUAL_SNOW, BuiltInPages::visualSnow);
        Pages.contribute(Pages.COLOUR_GRADING, page -> page.section("grading").row(gradeTemplatePicker()).row(gradeTimePicker()));
        Pages.contribute(Pages.PARTICLES, page -> {
            page.section("particles").note(Component.translatable("litelights.note.nether_ambience"));
            page.section("recommended")
                    .recommendation(VISUALITY, Component.translatable("litelights.recommend.visuality"))
                    .recommendation(SUBTLE_EFFECTS, Component.translatable("litelights.recommend.subtle_effects"));
        });
        Pages.contribute(Pages.LIGHTING, page -> page.section("recommended")
                .recommendation(LAMBDYNAMICLIGHTS, Component.translatable("litelights.recommend.lambdynamiclights")));
        Pages.contribute(Pages.VEGETATION, page -> page.section("recommended")
                .recommendation(LAMBDABETTERGRASS, Component.translatable("litelights.recommend.lambdabettergrass")));
        Pages.contribute(Pages.DIMENSIONS, page -> {
            if (DimensionOptions.known().isEmpty()) {
                page.section("modded").note(Component.translatable("litelights.note.no_modded_dimensions"));
            }
        });
        // Credits for a whole page: a heart beside the page's first caption.
        PageModel.pageCredit(Pages.OVERLAYS, "litelights.credit.overlays");
        PageModel.pageCredit(FAR_TERRAIN_PAGE, "litelights.credit.far_terrain");
        Pages.contribute(Pages.OVERLAYS, page -> {
            page.section("recommended")
                    .recommendation(LAMBDABETTERGRASS, Component.translatable("litelights.recommend.lambdabettergrass"));
        });
        Pages.contribute(Pages.ABOUT, page -> {
            String version = FabricLoader.getInstance().getModContainer(LiteLightsClient.MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("?");
            page.note(Component.translatable("litelights.about.summary"))
                    .note(Component.translatable("litelights.about.version", version))
                    .card(Component.translatable("litelights.about.credits").withStyle(style -> style.withBold(true)),
                            Component.translatable("litelights.about.credits.description"),
                            Component.translatable("litelights.about.credits.button"), null, SettingsScreen::showCreditsOnOpenScreen);
            for (ModStatus.Group group : ModStatus.Group.values()) {
                String key = group.name().toLowerCase(Locale.ROOT);
                page.section("about_" + key, Component.translatable("litelights.about.group." + key), false)
                        .note(Component.translatable("litelights.about.group." + key + ".description"));
                for (ModStatus.Mod mod : ModStatus.inGroup(group)) {
                    String link = ModStatus.linkFor(mod);
                    page.card(ModStatus.heading(mod), Component.translatable(mod.whatKey()),
                            link == null ? null : Component.literal(ModStatus.site(link)), link, null);
                }
            }
            page.section("about_reset", Component.translatable("litelights.section.reset"), false)
                    .confirmedAction(Component.translatable("litelights.about.reset_all"),
                            Component.translatable("litelights.about.reset_all.description"),
                            Component.translatable("litelights.about.reset_all.button"), BuiltInPages::resetEverything);
        });
    }

    /** A few lines for new players at the top of General: what LiteLights is, mods that go well with it, tips. */
    private static void gettingStarted(PageContent page) {
        page.section("getting_started")
                .note(Component.translatable("litelights.getting_started.summary"))
                .recommendation(SODIUM, Component.translatable("litelights.getting_started.sodium"))
                .recommendation(LAMBDYNAMICLIGHTS, Component.translatable("litelights.getting_started.lambdynamiclights"))
                .recommendation(LAMBDABETTERGRASS, Component.translatable("litelights.getting_started.lambdabettergrass"))
                .recommendation(CONTINUITY, Component.translatable("litelights.getting_started.continuity"))
                .recommendation(VISUALITY, Component.translatable("litelights.getting_started.visuality"))
                .recommendation(SUBTLE_EFFECTS, Component.translatable("litelights.getting_started.subtle_effects"))
                .note(Component.translatable("litelights.getting_started.tip.gpu"))
                .note(Component.translatable("litelights.getting_started.tip.preview"))
                .subScreen(Component.translatable("litelights.setup.again"),
                        Component.translatable("litelights.setup.again.description"),
                        Component.translatable("litelights.setup.again.button"), FirstSetupScreen::new);
    }

    /**
     * Fills the grade sliders from a template in one click: the four times of day when grading
     * goes by time of day, the whole-day set otherwise. It shows the template the sliders still
     * match, or "Your own" once any slider differs; nothing is locked or remembered.
     */
    private static CustomRow gradeTemplatePicker() {
        List<Component> names = new ArrayList<>();
        names.add(Component.translatable("litelights.grading.template.own"));
        for (GradeTemplates.Template template : GradeTemplates.ALL) {
            names.add(Component.translatable(template.translationKey()));
        }
        return CustomRow.choice(Component.translatable("litelights.grading.template"),
                        Component.translatable("litelights.grading.template.description"), 0, names,
                        () -> {
                            GradeTemplates.Template matching = GradeTemplates.matching(LiteLightsOptions.GRADE_SCHEDULE.get());
                            return matching == null ? 0 : GradeTemplates.ALL.indexOf(matching) + 1;
                        },
                        index -> {
                            if (index > 0) {
                                GradeTemplates.apply(GradeTemplates.ALL.get(index - 1), LiteLightsOptions.GRADE_SCHEDULE.get());
                            }
                        })
                .untracked()
                .state(() -> LiteLightsOptions.GRADE_SCHEDULE.feature().state())
                .enabledWhen(() -> LiteLightsOptions.GRADE_SCHEDULE.get() != GradeSchedule.OFF);
    }

    /** Which time of day the grade sliders edit; only shown when grading is set per time of day. */
    private static CustomRow gradeTimePicker() {
        List<GradeTime> times = List.of(GradeTime.DAWN, GradeTime.MIDDAY, GradeTime.DUSK, GradeTime.NIGHT);
        List<Component> names = new ArrayList<>();
        for (GradeTime time : times) {
            names.add(Component.translatable("litelights.grading.time." + time.name().toLowerCase(Locale.ROOT)));
        }
        return CustomRow.choice(Component.translatable("litelights.grading.editing"),
                        Component.translatable("litelights.grading.editing.description"), 0, names,
                        () -> times.indexOf(LiteLightsOptions.editedGradeTime()),
                        index -> LiteLightsOptions.editGradeTime(times.get(index)))
                .untracked()
                .state(() -> LiteLightsOptions.GRADE_SCHEDULE.feature().state())
                .shownWhen(() -> LiteLightsOptions.GRADE_SCHEDULE.get() == GradeSchedule.BY_TIME_OF_DAY);
    }

    /**
     * The far terrain settings that are left. The Far shadows and Reach rows are the Shadows page's
     * own, so a change on either page shows on the other. That Voxy and Distant Horizons support was
     * dropped in 1.0.8 is an internal note (DECISIONS, 1.0.8 and seedgen-server-112), not said on the
     * page (user, 2026-11-25); with either installed, the Far shadows row says why it is off.
     */
    private static void farTerrain(PageContent page) {
        page.section("from_shadows", Component.translatable("litelights.page.shadows"), false)
                .option(LiteLightsOptions.FAR_SHADOWS)
                .option(LiteLightsOptions.SHADOW_REACH);
    }

    /**
     * Snow on far terrain on the Visual snow page too: the same option as far terrain's own row (one
     * setting, two rows), in a section of its own, since the Visual snow switch doesn't grey it out.
     */
    private static void visualSnow(PageContent page) {
        Option<?> farSnow = LiteLightsConfig.registry().get("weather.visual_snow.far");
        if (farSnow != null) {
            page.section("far_snow")
                    .note(Component.translatable("litelights.note.far_snow"))
                    .option(farSnow);
        }
    }

    private static void resetEverything() {
        LiteLightsConfig.registry().resetAll();
        LiteLightsConfig.save();
    }

    static boolean installed(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }
}
