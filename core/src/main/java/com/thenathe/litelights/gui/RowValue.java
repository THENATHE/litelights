package com.thenathe.litelights.gui;

import com.thenathe.litelights.config.BoolOption;
import com.thenathe.litelights.config.Cost;
import com.thenathe.litelights.config.CustomRow;
import com.thenathe.litelights.config.EnumOption;
import com.thenathe.litelights.config.FloatOption;
import com.thenathe.litelights.config.IntOption;
import com.thenathe.litelights.config.Option;
import com.thenathe.litelights.config.RowState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * One settings row seen as an int, whatever is behind it: a registered option or a module's
 * custom row. A switch is 0 or 1, a choice an index, a slider a step count or a whole number.
 * Widgets only talk to this, so every kind of row gets the same controls, reset and marks.
 */
public interface RowValue {
    /** {@code NOTICE} rows have no control, only a status where the control would be. */
    enum Kind { TOGGLE, CHOICE, SLIDER, NOTICE }

    Kind kind();

    Component label();

    Component description();

    /** The credit the heart beside the row's name shows, or null. */
    Component credit();

    /** A line under the row that says what its status stands for right now, or null (module notices). */
    default Component detail() {
        return null;
    }

    /** Whether a notice's status is good news: a tick, in the good colour. */
    default boolean noticeGood() {
        return state() instanceof RowState.Ready;
    }

    int value();

    void set(int value);

    int min();

    int max();

    /** The step between neighbouring int values (1 for everything but whole-number sliders). */
    int step();

    List<Component> choices();

    Component format(int value);

    boolean changed();

    void reset();

    /** False for view controls that have no default to go back to. */
    boolean tracked();

    Cost cost();

    /** A small tag shown beside the cost badge, such as "Experimental", or null for none. */
    default Component rowTag() {
        return null;
    }

    /** What the tag shows when the pointer is over it, or null to show nothing more than the tag. */
    default Component rowTagTooltip() {
        return null;
    }

    /**
     * Why a row is locked by another setting (such as "Dynamic clouds only"), or null. A locked row
     * is left off its page (PageModel.onPage); search shows it with this reason.
     */
    default Component lockedTag() {
        return null;
    }

    /** A tag's tooltip is the translation at {@code <tagKey>.tooltip}, where there is one. */
    static Component tagTooltip(String tagKey) {
        return tagKey != null && Language.getInstance().has(tagKey + ".tooltip")
            ? Component.translatable(tagKey + ".tooltip") : null;
    }

    RowState state();

    /** Whether this row shows its feature's reason and Retry button (the others only grey out). */
    boolean leadsFeature();

    /** Whether the row matters right now; greyed out otherwise. */
    boolean active();

    boolean shown();

    /** Whether a choice can be picked (a choice can be "Coming soon" on its own). */
    default boolean choiceEnabled(int index) {
        return true;
    }

    /** A small tag after a choice's name, such as "Experimental", or null. */
    default Component choiceTag(int index) {
        return null;
    }

    /** What the row stands for, so search and tests can find it. */
    String id();

    static RowValue of(Option<?> option) {
        return new OptionValue(option);
    }

    static RowValue of(CustomRow row) {
        return new CustomValue(row);
    }

    /** A registered option. */
    final class OptionValue implements RowValue {
        private final Option<?> option;

        OptionValue(Option<?> option) {
            this.option = option;
        }

        public Option<?> option() {
            return option;
        }

        @Override
        public String id() {
            return option.id();
        }

        @Override
        public Kind kind() {
            return switch (option) {
                case BoolOption ignored -> Kind.TOGGLE;
                case EnumOption<?> ignored -> Kind.CHOICE;
                case IntOption whole -> whole.valueCount() <= 3 ? Kind.CHOICE : Kind.SLIDER;
                case FloatOption ignored -> Kind.SLIDER;
            };
        }

        @Override
        public Component label() {
            return Component.translatable(option.labelKey());
        }

        @Override
        public Component description() {
            return Component.translatable(option.descriptionKey());
        }

        @Override
        public Component credit() {
            return option.creditKey() == null ? null : Component.translatable(option.creditKey());
        }

        @Override
        public int value() {
            return switch (option) {
                case BoolOption toggle -> toggle.on() ? 1 : 0;
                case EnumOption<?> choice -> choice.get().ordinal();
                case IntOption whole -> whole.valueCount() <= 3 ? (whole.value() - whole.min()) / whole.step() : whole.value();
                case FloatOption decimal -> decimal.stepIndex(decimal.value());
            };
        }

        @Override
        public void set(int value) {
            switch (option) {
                case BoolOption toggle -> toggle.set(value != 0);
                case EnumOption<?> choice -> setChoice(choice, value);
                case IntOption whole -> whole.set(whole.valueCount() <= 3 ? whole.min() + value * whole.step() : value);
                case FloatOption decimal -> decimal.set(decimal.valueAtStep(value));
            }
        }

        private static <E extends Enum<E>> void setChoice(EnumOption<E> option, int index) {
            option.set(option.choices().get(Math.clamp(index, 0, option.choices().size() - 1)));
        }

        @Override
        public int min() {
            return option instanceof IntOption whole && whole.valueCount() > 3 ? whole.min() : 0;
        }

        @Override
        public int max() {
            return switch (option) {
                case BoolOption ignored -> 1;
                case EnumOption<?> choice -> choice.choices().size() - 1;
                case IntOption whole -> whole.valueCount() <= 3 ? whole.valueCount() - 1 : whole.max();
                case FloatOption decimal -> decimal.stepCount();
            };
        }

        @Override
        public int step() {
            return option instanceof IntOption whole && whole.valueCount() > 3 ? whole.step() : 1;
        }

        @Override
        public List<Component> choices() {
            List<Component> names = new ArrayList<>();
            if (option instanceof EnumOption<?> choice) {
                addChoiceNames(choice, names);
            } else if (option instanceof IntOption whole && whole.valueCount() <= 3) {
                for (int i = 0; i < whole.valueCount(); i++) {
                    names.add(ValueFormats.format(whole.format(), whole.min() + i * whole.step()));
                }
            }
            return names;
        }

        private static <E extends Enum<E>> void addChoiceNames(EnumOption<E> option, List<Component> names) {
            for (E choice : option.choices()) {
                names.add(Component.translatable(option.choiceKey(choice)));
            }
        }

        @Override
        public Component format(int value) {
            return switch (option) {
                case BoolOption ignored -> Component.translatable(value != 0 ? "options.on" : "options.off");
                case EnumOption<?> ignored -> choices().get(Math.clamp(value, 0, max()));
                case IntOption whole -> ValueFormats.format(whole.format(),
                        whole.valueCount() <= 3 ? whole.min() + value * whole.step() : value);
                case FloatOption decimal -> ValueFormats.format(decimal.format(), decimal.valueAtStep(value));
            };
        }

        @Override
        public boolean changed() {
            return option.isChanged();
        }

        @Override
        public void reset() {
            option.reset();
        }

        @Override
        public boolean tracked() {
            return true;
        }

        @Override
        public Cost cost() {
            return option instanceof EnumOption<?> choice ? choice.currentCost() : option.cost();
        }

        @Override
        public Component rowTag() {
            return option.tagKey() == null ? null : Component.translatable(option.tagKey());
        }

        @Override
        public Component rowTagTooltip() {
            return RowValue.tagTooltip(option.tagKey());
        }

        @Override
        public Component lockedTag() {
            return !active() && option.lockedTagKey() != null ? Component.translatable(option.lockedTagKey()) : null;
        }

        @Override
        public RowState state() {
            return option.feature().state();
        }

        @Override
        public boolean leadsFeature() {
            return option.feature().isLead(option);
        }

        @Override
        public boolean active() {
            return option.active();
        }

        @Override
        public boolean shown() {
            return option.shown();
        }

        @Override
        public boolean choiceEnabled(int index) {
            return !(option instanceof EnumOption<?> choice) || choiceReady(choice, index);
        }

        private static <E extends Enum<E>> boolean choiceReady(EnumOption<E> option, int index) {
            return option.choiceState(option.choices().get(index)).editable();
        }

        @Override
        public Component choiceTag(int index) {
            if (option instanceof EnumOption<?> choice) {
                return tagFor(choice, index);
            }
            return null;
        }

        private static <E extends Enum<E>> Component tagFor(EnumOption<E> option, int index) {
            E choice = option.choices().get(index);
            if (!option.choiceState(choice).editable()) {
                return Component.translatable("litelights.state.coming_soon");
            }
            String key = option.tagKey(choice);
            return key == null ? null : Component.translatable(key);
        }
    }

    /** A module's custom row. */
    final class CustomValue implements RowValue {
        private final CustomRow row;

        CustomValue(CustomRow row) {
            this.row = row;
        }

        @Override
        public String id() {
            return "custom:" + row.label().getString();
        }

        @Override
        public Kind kind() {
            return switch (row.kind()) {
                case TOGGLE -> Kind.TOGGLE;
                case CHOICE -> Kind.CHOICE;
                case SLIDER -> Kind.SLIDER;
                case NOTICE -> Kind.NOTICE;
            };
        }

        @Override
        public Component label() {
            return row.label();
        }

        @Override
        public Component description() {
            return row.description();
        }

        @Override
        public Component credit() {
            return null;
        }

        @Override
        public Component detail() {
            return row.detail();
        }

        @Override
        public boolean noticeGood() {
            return row.state() instanceof RowState.Ready && row.good();
        }

        @Override
        public int value() {
            return row.value();
        }

        @Override
        public void set(int value) {
            row.set(value);
        }

        @Override
        public int min() {
            return row.min();
        }

        @Override
        public int max() {
            return row.max();
        }

        @Override
        public int step() {
            return row.step();
        }

        @Override
        public List<Component> choices() {
            return row.choices();
        }

        @Override
        public Component format(int value) {
            return row.format(value);
        }

        @Override
        public boolean changed() {
            return row.isChanged();
        }

        @Override
        public void reset() {
            row.reset();
        }

        @Override
        public boolean tracked() {
            return row.tracked();
        }

        @Override
        public Cost cost() {
            return row.cost();
        }

        @Override
        public Component rowTag() {
            return row.tagKey() == null ? null : Component.translatable(row.tagKey());
        }

        @Override
        public Component rowTagTooltip() {
            return RowValue.tagTooltip(row.tagKey());
        }

        @Override
        public Component lockedTag() {
            return !active() && row.lockedTagKey() != null ? Component.translatable(row.lockedTagKey()) : null;
        }

        @Override
        public RowState state() {
            return row.state();
        }

        @Override
        public boolean leadsFeature() {
            return true;
        }

        @Override
        public boolean active() {
            return row.active();
        }

        @Override
        public boolean shown() {
            return row.shown();
        }
    }
}
