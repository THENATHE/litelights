package com.thenathe.litelights.config;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The values a row's condition reads its options through: the live ones, as the LiteLights screen
 * and the renderer see them, or the ones waiting in another screen. Sodium's Video Settings hold a
 * change until Apply, so its pages ask the conditions about their own unapplied values, and a row
 * greys the moment the switch it depends on is turned off there, as it does on LiteLights' screen.
 */
public abstract class OptionValues {
    /** The options' current values. */
    public static final OptionValues LIVE = new OptionValues() {
        @Override
        public <T> T get(Option<T> option) {
            return option.get();
        }
    };

    public abstract <T> T get(Option<T> option);

    public boolean on(BoolOption option) {
        return get(option);
    }

    /**
     * Every option a test can read. The test is run with each value of every option it reads (both
     * sides of a switch, every choice of a list, the default and both ends of a number), and again
     * for each new option a run reads, until none does. So an option read only for some values of
     * another ("particles on and distance past Near") is found too.
     */
    static Set<Option<?>> readBy(Predicate<OptionValues> test) {
        Set<Option<?>> read = new LinkedHashSet<>();
        Set<Map<Option<?>, Object>> tried = new HashSet<>();
        Deque<Map<Option<?>, Object>> toTry = new ArrayDeque<>();
        toTry.add(Map.of());
        while (!toTry.isEmpty()) {
            Map<Option<?>, Object> fixed = toTry.poll();
            if (!tried.add(fixed)) {
                continue;
            }
            Recording run = new Recording(fixed);
            test.test(run);
            for (Option<?> option : run.read) {
                read.add(option);
                if (fixed.containsKey(option)) {
                    continue;
                }
                for (Object value : samples(option)) {
                    Map<Option<?>, Object> next = new HashMap<>(fixed);
                    next.put(option, value);
                    toTry.add(next);
                }
            }
        }
        return read;
    }

    private static List<?> samples(Option<?> option) {
        return switch (option) {
            case BoolOption ignored -> List.of(true, false);
            case EnumOption<?> choice -> choice.choices();
            case IntOption whole -> List.of(whole.min(), whole.defaultValue(), whole.max());
            case FloatOption decimal -> List.of(decimal.min(), decimal.defaultValue(), decimal.max());
        };
    }

    /** Answers with the fixed values (the default for the rest) and notes every option asked about. */
    private static final class Recording extends OptionValues {
        private final Map<Option<?>, Object> fixed;
        private final Set<Option<?>> read = new LinkedHashSet<>();

        Recording(Map<Option<?>, Object> fixed) {
            this.fixed = fixed;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Option<T> option) {
            read.add(option);
            return fixed.containsKey(option) ? (T) fixed.get(option) : option.defaultValue();
        }
    }
}
