package com.thenathe.litelights.api;

import java.util.function.Consumer;

/**
 * How a module declares settings rows of its own (docs/SETTINGS-API.md). Each row becomes one of
 * core's options: saved in {@code litelights.json} under its id, shown on its page in the LiteLights
 * screen and in Sodium's, with its cost badge, tag and feature state, exactly like core's own.
 * Declare rows while the module starts, so their saved values are claimed before the first save
 * and Sodium's pages see them.
 *
 * <p>Text comes from translation keys as for core's options ({@code litelights.option.<id>},
 * {@code .description}, enum choices {@code .<choice in lower case>}, sections
 * {@code litelights.section.<section>}); a module ships them in a language file of its own namespace.
 */
public interface ModuleSettings {
    /** The far terrain page's id. */
    String FAR_TERRAIN_PAGE = "far_terrain";

    /** Starts a row. Ids are the keys in the file: never rename one without a migration. */
    SettingRow row(String id);

    /**
     * Adds rows that aren't settings (notes, buttons) to a page each time the screen builds it,
     * so they can show live values.
     */
    void contribute(String page, Consumer<PageRows> contributor);

    /** What a row costs while it is on, shown as a badge (core's Cost). */
    enum RowCost { FREE, LOW, MEDIUM, HIGH }

    /** How a number reads on screen (core's ValueFormat). */
    enum NumberFormat { PLAIN, PERCENT, BLOCKS }

    /** A row being declared. */
    interface SettingRow {
        SettingRow at(String page, String section);

        SettingRow cost(RowCost cost);

        /** The settings feature id whose state (Ready, Unavailable, Failed) the row shows. */
        SettingRow feature(String featureId);

        /** Greyed out while this switch is off. */
        SettingRow needs(ToggleSetting parent);

        /** A small tag beside the cost badge, such as {@code litelights.tag.experimental}. */
        SettingRow tag(String translationKey);

        SettingRow format(NumberFormat format);

        ToggleSetting toggle(boolean defaultValue);

        NumberSetting number(int defaultValue, int min, int max, int step);

        <E extends Enum<E>> ChoiceSetting<E> choice(Class<E> type, E defaultValue);
    }

    /** A setting's value. Safe to read on any thread; set clamps and saves on the next save. */
    interface Setting<T> {
        String id();

        T get();

        void set(T value);

        T defaultValue();

        /** Runs after the value really changed, on the thread that changed it. */
        void onChange(Runnable listener);
    }

    interface ToggleSetting extends Setting<Boolean> {
        boolean on();
    }

    interface NumberSetting extends Setting<Integer> {
        int value();
    }

    interface ChoiceSetting<E extends Enum<E>> extends Setting<E> {
    }

    /** Rows that aren't settings, added to a page as it is built. */
    interface PageRows {
        /** Continues (or starts) a section of the page; rows added next go there. */
        PageRows section(String section);

        PageRows note(net.minecraft.network.chat.Component text);

        PageRows action(net.minecraft.network.chat.Component label, net.minecraft.network.chat.Component description,
                        net.minecraft.network.chat.Component button, Runnable action);

        /** An action that asks for a second click first. */
        PageRows confirmedAction(net.minecraft.network.chat.Component label, net.minecraft.network.chat.Component description,
                                 net.minecraft.network.chat.Component button, Runnable action);

        /**
         * A row that shows a short status where its control would be, asked for again each time the
         * page is drawn, so it follows what changes while the page is open.
         */
        PageRows status(net.minecraft.network.chat.Component label, net.minecraft.network.chat.Component description,
                        java.util.function.Supplier<net.minecraft.network.chat.Component> status);

        /**
         * A status row as above with a line under it that says more ({@code detail}, null for none,
         * wrapped to the page's width), and whether the status is good news ({@code good}: a tick,
         * in the page's good colour; else dimmed). All three are asked for each time the page is drawn.
         */
        PageRows status(net.minecraft.network.chat.Component label, net.minecraft.network.chat.Component description,
                        java.util.function.Supplier<net.minecraft.network.chat.Component> status,
                        java.util.function.Supplier<net.minecraft.network.chat.@org.jspecify.annotations.Nullable Component> detail,
                        java.util.function.BooleanSupplier good);
    }
}
