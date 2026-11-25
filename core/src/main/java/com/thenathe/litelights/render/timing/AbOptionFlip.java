package com.thenathe.litelights.render.timing;

import com.thenathe.litelights.config.BoolOption;
import com.thenathe.litelights.config.EnumOption;
import com.thenathe.litelights.config.FloatOption;
import com.thenathe.litelights.config.IntOption;
import com.thenathe.litelights.config.Option;
import java.util.Locale;

/**
 * The option an A/B timing run flips: its two values, and the value it had before the run, which
 * goes back when the run stops. Values are written as in {@code litelights.json} (true, false, a
 * choice's lower-case name, a number); a switch with no values given flips between its current
 * value (A) and the other one (B).
 */
final class AbOptionFlip {
    private final Option<?> option;
    private final Object valueA;
    private final Object valueB;
    private final Object original;

    private AbOptionFlip(Option<?> option, Object valueA, Object valueB) {
        this.option = option;
        this.valueA = valueA;
        this.valueB = valueB;
        this.original = option.get();
    }

    /**
     * @param values "A,B", or empty for a switch
     * @throws IllegalArgumentException when the values can't be read as the option's type
     */
    static AbOptionFlip of(Option<?> option, String values) {
        String trimmed = values == null ? "" : values.trim();
        if (trimmed.isEmpty()) {
            if (option instanceof BoolOption bool) {
                return new AbOptionFlip(option, bool.get(), !bool.get());
            }
            throw new IllegalArgumentException(option.id() + " isn't a switch: give its two values as litelights.ab.values=A,B");
        }
        String[] parts = trimmed.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("litelights.ab.values needs two values, A and B: " + values);
        }
        return new AbOptionFlip(option, parse(option, parts[0].trim()), parse(option, parts[1].trim()));
    }

    static Object parse(Option<?> option, String text) {
        try {
            if (option instanceof BoolOption) {
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("not true or false");
                }
                return Boolean.parseBoolean(text);
            }
            if (option instanceof EnumOption<?> choice) {
                for (Enum<?> each : choice.choices()) {
                    if (EnumOption.storedName(each).equals(text.toLowerCase(Locale.ROOT))) {
                        return each;
                    }
                }
                throw new IllegalArgumentException("not one of " + choice.choices());
            }
            if (option instanceof IntOption) {
                return Integer.parseInt(text);
            }
            if (option instanceof FloatOption) {
                return Float.parseFloat(text);
            }
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(option.id() + ": not a number: " + text, notANumber);
        } catch (IllegalArgumentException unreadable) {
            throw new IllegalArgumentException(option.id() + ": " + unreadable.getMessage() + ": " + text, unreadable);
        }
        throw new IllegalArgumentException(option.id() + " has a type the A/B timing can't set");
    }

    String id() {
        return option.id();
    }

    Object value(AbSchedule.Side side) {
        return side == AbSchedule.Side.A ? valueA : valueB;
    }

    void apply(AbSchedule.Side side) {
        set(value(side));
    }

    void restore() {
        set(original);
    }

    @SuppressWarnings("unchecked")
    private void set(Object value) {
        ((Option<Object>) option).set(value);
    }
}
