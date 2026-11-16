package com.thenathe.litelights.gui.sodium;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.BoolOption;
import com.thenathe.litelights.config.EnumOption;
import com.thenathe.litelights.config.FloatOption;
import com.thenathe.litelights.config.IntOption;
import com.thenathe.litelights.config.LiteLightsConfig;
import com.thenathe.litelights.config.Option;
import com.thenathe.litelights.config.Pages;
import com.thenathe.litelights.config.RowState;
import com.thenathe.litelights.gui.SettingsScreen;
import com.thenathe.litelights.gui.Theme;
import com.thenathe.litelights.gui.ValueFormats;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.StatefulOptionBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * LiteLights' pages inside Sodium's Video Settings, through Sodium's public config API (no mixins).
 * Every switch, choice and slider is bound to the same option objects the LiteLights screen uses,
 * so a change in either screen is the same change: it applies live through the options' listeners
 * and is saved to litelights.json when Sodium applies it.
 *
 * <p>What Sodium can't show (cost badges, the time-of-day preview, Credits, module sub-screens)
 * stays in the LiteLights screen, which the first row opens. Credit lines and feature states go in
 * the tooltips. Sodium has no float option, so decimal sliders are whole steps here.
 */
public final class SodiumOptions implements ConfigEntryPoint {
    /**
     * One handler for every option: Sodium runs each distinct storage handler once on Apply, so a
     * new method reference per option would write litelights.json once per changed option. Made in
     * {@link #addPages}, not at class load, so a changed Sodium API is caught there like the rest.
     */
    private static StorageEventHandler save;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        try {
            addPages(builder);
        } catch (RuntimeException | LinkageError failure) {
            // Sodium turns any exception from a config entrypoint into a crash, and a Sodium that
            // changed its API throws linkage errors here. Its screen simply goes without our pages.
            LiteLightsClient.LOG.error("Pages couldn't be added to Sodium's Video Settings; use the LiteLights screen instead", failure);
        }
    }

    private static void addPages(ConfigBuilder builder) {
        LiteLightsConfig.init();
        save = LiteLightsConfig::saveIfChanged;
        ModOptionsBuilder mod = builder.registerOwnModOptions()
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(Theme.ACCENT & 0xFFFFFF))
                .setNonTintedIcon(LiteLightsClient.id("icon-128.png"));

        boolean first = true;
        for (Pages.PageInfo info : Pages.all()) {
            Map<String, OptionGroupBuilder> groups = new LinkedHashMap<>();
            if (first) {
                groups.put("", builder.createOptionGroup().addOption(openScreenButton(builder)));
                first = false;
            }
            for (Option<?> option : LiteLightsConfig.registry().onPage(info.id())) {
                OptionBuilder built = buildOption(builder, option);
                if (built == null) {
                    continue;
                }
                groups.computeIfAbsent(groupKey(option), key -> builder.createOptionGroup()
                        .setName(groupName(info.id(), option))).addOption(built);
            }
            if (groups.isEmpty()) {
                continue;
            }
            OptionPageBuilder page = builder.createOptionPage().setName(Component.translatable(info.labelKey()));
            groups.values().forEach(page::addOptionGroup);
            mod.addPage(page);
        }
        mod.addPage(builder.createExternalPage()
                .setName(Component.translatable("litelights.sodium.full_screen_page"))
                .setScreenConsumer(SodiumOptions::openLiteLights));
    }

    private static OptionBuilder openScreenButton(ConfigBuilder builder) {
        return builder.createExternalButtonOption(LiteLightsClient.id("open_settings"))
                .setName(Component.translatable("litelights.sodium.open"))
                .setTooltip(Component.translatable("litelights.sodium.open.tooltip"))
                .setScreenConsumer(SodiumOptions::openLiteLights);
    }

    private static void openLiteLights(Screen parent) {
        Minecraft.getInstance().gui.setScreen(SettingsScreen.create(parent));
    }

    /** Grade sliders of each time of day get a group of their own, since Sodium can't hide rows. */
    private static String groupKey(Option<?> option) {
        if (option.id().startsWith("colour_grade.") && option.section().equals("grade")) {
            return option.section() + "/" + option.id().split("\\.")[1];
        }
        return option.section();
    }

    private static Component groupName(String page, Option<?> option) {
        if (Pages.isSubsection(option.section())) {
            // Sodium has no sub-headings: a sub-section is a group named after both ("Storms: Rain").
            return Component.translatable("litelights.sodium.subsection",
                    Component.translatable(Pages.captionKey(Pages.parentSection(option.section()))),
                    Component.translatable(Pages.captionKey(option.section())));
        }
        String literal = Pages.sectionName(page, option.section());
        Component section = literal != null ? Component.literal(literal) : Component.translatable(Pages.captionKey(option.section()));
        String key = groupKey(option);
        if (key.contains("/")) {
            String time = key.substring(key.indexOf('/') + 1);
            String timeKey = time.equals("all_day") ? "litelights.option.colour_grade.schedule.all_day" : "litelights.grading.time." + time;
            return Component.translatable("litelights.sodium.grade_group", section, Component.translatable(timeKey));
        }
        return section;
    }

    private static OptionBuilder buildOption(ConfigBuilder builder, Option<?> option) {
        try {
            OptionBuilder built = switch (option) {
                case BoolOption toggle -> common(builder.createBooleanOption(LiteLightsClient.id(toggle.id())), toggle)
                        .setDefaultValue(toggle.defaultValue())
                        .setBinding(toggle::set, toggle::get)
                        .setTooltip(value -> tooltip(toggle));
                case EnumOption<?> choice -> enumOption(builder, choice);
                case IntOption whole -> common(builder.createIntegerOption(LiteLightsClient.id(whole.id())), whole)
                        .setRange(whole.min(), whole.max(), whole.step())
                        .setValueFormatter(value -> ValueFormats.format(whole.format(), value))
                        .setDefaultValue(whole.defaultValue())
                        .setBinding(whole::set, whole::get)
                        .setTooltip(value -> tooltip(whole));
                case FloatOption decimal -> common(builder.createIntegerOption(LiteLightsClient.id(decimal.id())), decimal)
                        .setRange(0, decimal.stepCount(), 1)
                        .setValueFormatter(step -> ValueFormats.format(decimal.format(), decimal.valueAtStep(step)))
                        .setDefaultValue(decimal.stepIndex(decimal.defaultValue()))
                        .setBinding(step -> decimal.set(decimal.valueAtStep(step)), () -> decimal.stepIndex(decimal.value()))
                        .setTooltip(value -> tooltip(decimal));
            };
            return built;
        } catch (RuntimeException | LinkageError failure) {
            // Sodium crashes the game on a bad registration; leaving one row out is far better.
            LiteLightsClient.LOG.warn("Couldn't add {} to Sodium's settings: {}", option.id(), failure.toString());
            return null;
        }
    }

    private static <E extends Enum<E>> OptionBuilder enumOption(ConfigBuilder builder, EnumOption<E> option) {
        return common(builder.createEnumOption(LiteLightsClient.id(option.id()), option.type()), option)
                .setElementNameProvider(choice -> choiceName(option, choice))
                .setAllowedValuesProvider(state -> allowedChoices(option))
                .setDefaultValue(option.defaultValue())
                .setBinding(option::set, option::get)
                .setTooltip(value -> tooltip(option));
    }

    private static <E extends Enum<E>> Component choiceName(EnumOption<E> option, E choice) {
        MutableComponent name = Component.translatable(option.choiceKey(choice));
        if (option.tagKey(choice) != null) {
            name.append(" (").append(Component.translatable(option.tagKey(choice))).append(")");
        }
        return name;
    }

    /** Choices that can't be picked (Coming soon) are left out; the current one always stays so Sodium can show it. */
    private static <E extends Enum<E>> Set<E> allowedChoices(EnumOption<E> option) {
        Set<E> allowed = EnumSet.noneOf(option.type());
        for (E choice : option.choices()) {
            if (option.choiceState(choice).editable() || choice == option.get()) {
                allowed.add(choice);
            }
        }
        return allowed;
    }

    /** Name, cost and the enabled rule shared by every kind of option. */
    private static <V, B extends StatefulOptionBuilder<V>> B common(B builder, Option<?> option) {
        MutableComponent name = Component.translatable(option.labelKey());
        if (option.tagKey() != null) {
            name.append(" (").append(Component.translatable(option.tagKey())).append(")");
        }
        builder.setName(name);
        builder.setStorageHandler(save);
        OptionImpact impact = impact(option);
        if (impact != null) {
            builder.setImpact(impact);
        }
        BoolOption needs = option.needs();
        if (needs != null) {
            // Read the master switch from Sodium's state, so turning it off greys the row before Apply.
            builder.setEnabledProvider(state -> enabled(option, state), LiteLightsClient.id(needs.id()));
        } else {
            builder.setEnabledProvider(state -> enabled(option, state));
        }
        return builder;
    }

    private static boolean enabled(Option<?> option, ConfigState state) {
        if (!option.feature().state().editable() || !option.conditionHolds()) {
            return false;
        }
        BoolOption needs = option.needs();
        return needs == null || state.readBooleanOption(LiteLightsClient.id(needs.id()));
    }

    private static OptionImpact impact(Option<?> option) {
        if (option instanceof EnumOption<?> choice && costsVary(choice)) {
            return OptionImpact.VARIES;
        }
        return switch (option.cost()) {
            case FREE -> null;
            case LOW -> OptionImpact.LOW;
            case MEDIUM -> OptionImpact.MEDIUM;
            case HIGH -> OptionImpact.HIGH;
        };
    }

    private static <E extends Enum<E>> boolean costsVary(EnumOption<E> option) {
        return option.choices().stream().map(option::costOf).distinct().count() > 1;
    }

    /**
     * The description, then the credit line and the feature's state, worked out each time it shows.
     * Rows under a sub-heading with hover text (Haboob) carry that text too, as Sodium's group
     * names have no tooltip of their own.
     */
    private static Component tooltip(Option<?> option) {
        MutableComponent text = Component.translatable(option.descriptionKey());
        String asideKey = Pages.captionKey(option.section()) + ".tooltip";
        if (Pages.isSubsection(option.section()) && Language.getInstance().has(asideKey)) {
            text.append("\n\n").append(Component.translatable(asideKey).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
        }
        if (option.creditKey() != null) {
            text.append("\n\n").append(Component.translatable(option.creditKey()).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
        }
        Component state = stateLine(option.feature().state());
        if (state != null) {
            text.append("\n\n").append(state);
        }
        return text;
    }

    private static Component stateLine(RowState state) {
        return switch (state) {
            case RowState.ComingSoon ignored -> Component.translatable("litelights.state.coming_soon").withStyle(ChatFormatting.GOLD);
            case RowState.HandledBy handled -> Component.translatable("litelights.state.handled_by", handled.modName())
                    .withStyle(ChatFormatting.GOLD);
            case RowState.Unavailable unavailable -> Component.translatableWithFallback(unavailable.reason(), unavailable.reason())
                    .withStyle(ChatFormatting.GOLD);
            case RowState.Failed failed -> Component.translatable("litelights.sodium.failed",
                    Component.translatableWithFallback(failed.reason(), failed.reason())).withStyle(ChatFormatting.RED);
            case RowState.Ready ignored -> null;
        };
    }

}
