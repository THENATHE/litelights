package com.thenathe.litelights.config;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/**
 * A row bound to a module's own data rather than to a registered option, such as one row per
 * block in the overlays hierarchy. Its value is an int: a switch is 0 or 1, a choice an index, a
 * slider a number. The module saves the data itself. A notice has no value to set: it only shows
 * a status, such as whether a compatibility fix is active.
 */
public final class CustomRow {
    public enum Kind { TOGGLE, CHOICE, SLIDER, NOTICE }

    private final Kind kind;
    private final Component label;
    private final Component description;
    private final int defaultValue;
    private final int min;
    private final int max;
    private final int step;
    private final List<Component> choices;
    private final IntFunction<Component> format;
    private final IntSupplier getter;
    private final IntConsumer setter;
    private Cost cost = Cost.FREE;
    private Supplier<RowState> state = () -> RowState.READY;
    private BooleanSupplier enabledWhen = () -> true;
    private BooleanSupplier shownWhen = () -> true;
    private boolean tracked = true;
    private String tagKey;

    private CustomRow(Kind kind, Component label, Component description, int defaultValue, int min, int max, int step,
                      List<Component> choices, IntFunction<Component> format, IntSupplier getter, IntConsumer setter) {
        this.kind = kind;
        this.label = label;
        this.description = description;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.step = step;
        this.choices = choices;
        this.format = format;
        this.getter = getter;
        this.setter = setter;
    }

    public static CustomRow toggle(Component label, Component description, boolean defaultValue, IntSupplier getter, IntConsumer setter) {
        return new CustomRow(Kind.TOGGLE, label, description, defaultValue ? 1 : 0, 0, 1, 1, List.of(),
                value -> Component.translatable(value != 0 ? "options.on" : "options.off"), getter, setter);
    }

    public static CustomRow choice(Component label, Component description, int defaultValue, List<Component> choices,
                                   IntSupplier getter, IntConsumer setter) {
        return new CustomRow(Kind.CHOICE, label, description, defaultValue, 0, choices.size() - 1, 1, List.copyOf(choices),
                choices::get, getter, setter);
    }

    public static CustomRow slider(Component label, Component description, int defaultValue, int min, int max, int step,
                                   IntFunction<Component> format, IntSupplier getter, IntConsumer setter) {
        return new CustomRow(Kind.SLIDER, label, description, defaultValue, min, max, step, List.of(), format, getter, setter);
    }

    /**
     * A row without a control that shows {@code status} where the control would be, such as
     * "Active". Its {@link #state} gives the colour (ready shows as good news) and, when it isn't
     * ready, the reason under the row. It is never saved, reset or counted as changed.
     */
    public static CustomRow notice(Component label, Component description, Supplier<Component> status) {
        CustomRow row = new CustomRow(Kind.NOTICE, label, description, 0, 0, 0, 1, List.of(), value -> status.get(),
                () -> 0, value -> { });
        row.tracked = false;
        return row;
    }

    public CustomRow cost(Cost cost) {
        this.cost = cost;
        return this;
    }

    /** Where the row's state comes from, such as a module's feature: {@code row.state(feature::state)}. */
    public CustomRow state(Supplier<RowState> state) {
        this.state = state;
        return this;
    }

    /**
     * Shows a small tag on the row, as {@code Option.Builder.tag} does for options. A translation
     * at {@code <tagKey>.tooltip}, if there is one, shows when the pointer is over the tag.
     */
    public CustomRow tag(String tagKey) {
        this.tagKey = tagKey;
        return this;
    }

    /** Greys the row out while the condition is false. */
    public CustomRow enabledWhen(BooleanSupplier condition) {
        this.enabledWhen = condition;
        return this;
    }

    /** Hides the row while the condition is false. */
    public CustomRow shownWhen(BooleanSupplier condition) {
        this.shownWhen = condition;
        return this;
    }

    /**
     * Marks the row as a view control rather than a setting (such as which time of day the grade
     * sliders edit): it has no reset button and never counts as changed.
     */
    public CustomRow untracked() {
        this.tracked = false;
        return this;
    }

    public boolean active() {
        return enabledWhen.getAsBoolean();
    }

    public boolean shown() {
        return shownWhen.getAsBoolean();
    }

    public boolean tracked() {
        return tracked;
    }

    public Kind kind() {
        return kind;
    }

    public Component label() {
        return label;
    }

    public Component description() {
        return description;
    }

    public int defaultValue() {
        return defaultValue;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public int step() {
        return step;
    }

    public List<Component> choices() {
        return choices;
    }

    public Component format(int value) {
        return format.apply(value);
    }

    public Cost cost() {
        return cost;
    }

    /** The translation key of the row's tag, or null for none. */
    public String tagKey() {
        return tagKey;
    }

    public RowState state() {
        return state.get();
    }

    public int value() {
        return getter.getAsInt();
    }

    public void set(int value) {
        int clamped = Math.clamp(value, min, max);
        setter.accept(min + Math.round((clamped - min) / (float) step) * step);
    }

    public boolean isChanged() {
        return tracked && value() != defaultValue;
    }

    public void reset() {
        set(defaultValue);
    }
}
