package com.thenathe.litelights.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A choice between the constants of an enum. It is stored as the constant's name in lower case,
 * and each choice's label is {@code <label key>.<name in lower case>}.
 */
public final class EnumOption<E extends Enum<E>> extends Option<E> {
    private final Class<E> type;
    private final List<E> choices;
    private final Map<E, Cost> choiceCosts;
    private final Map<E, String> choiceTags;
    private final Map<E, Feature> choiceFeatures;

    private EnumOption(Builder<E> builder) {
        super(builder);
        this.type = builder.type;
        this.choices = List.of(builder.type.getEnumConstants());
        this.choiceCosts = Map.copyOf(builder.choiceCosts);
        this.choiceTags = Map.copyOf(builder.choiceTags);
        this.choiceFeatures = Map.copyOf(builder.choiceFeatures);
        checkDefault();
    }

    public static <E extends Enum<E>> Builder<E> builder(String id, Class<E> type, E defaultValue) {
        return new Builder<>(id, type, defaultValue);
    }

    public List<E> choices() {
        return choices;
    }

    public Class<E> type() {
        return type;
    }

    public String choiceKey(E choice) {
        return labelKey() + "." + storedName(choice);
    }

    /** The cost of the row while this choice is picked; the option's own cost unless set per choice. */
    public Cost costOf(E choice) {
        return choiceCosts.getOrDefault(choice, cost());
    }

    /** The cost badge for the current choice. */
    public Cost currentCost() {
        return costOf(get());
    }

    /** A small tag shown next to the choice, such as "Experimental", as a translation key; or null. */
    public String tagKey(E choice) {
        return choiceTags.get(choice);
    }

    /**
     * Whether the choice can be picked. A choice with a feature of its own (TAA in the
     * anti-aliasing option) stays greyed out until that feature is ready, even when the rest of
     * the option works.
     */
    public RowState choiceState(E choice) {
        Feature feature = choiceFeatures.get(choice);
        return feature == null ? RowState.READY : feature.state();
    }

    public Feature choiceFeature(E choice) {
        return choiceFeatures.get(choice);
    }

    public static String storedName(Enum<?> choice) {
        return choice.name().toLowerCase(Locale.ROOT);
    }

    @Override
    protected E normalize(E candidate) {
        return candidate;
    }

    @Override
    protected E read(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String name = element.getAsString();
            for (E choice : choices) {
                if (storedName(choice).equals(name)) {
                    return choice;
                }
            }
        }
        throw new IllegalArgumentException("not one of " + choices + ": " + element);
    }

    @Override
    protected JsonElement write(E stored) {
        return new JsonPrimitive(storedName(stored));
    }

    public static final class Builder<E extends Enum<E>> extends Option.Builder<E, EnumOption<E>, Builder<E>> {
        private final Class<E> type;
        private final Map<E, Cost> choiceCosts;
        private final Map<E, String> choiceTags;
        private final Map<E, Feature> choiceFeatures;

        private Builder(String id, Class<E> type, E defaultValue) {
            super(id, defaultValue);
            this.type = type;
            this.choiceCosts = new EnumMap<>(type);
            this.choiceTags = new EnumMap<>(type);
            this.choiceFeatures = new EnumMap<>(type);
        }

        public Builder<E> choiceCost(E choice, Cost cost) {
            choiceCosts.put(choice, cost);
            return this;
        }

        public Builder<E> choiceTag(E choice, String tagKey) {
            choiceTags.put(choice, tagKey);
            return this;
        }

        /** Gives one choice a feature state of its own (TAA's own failures show on its choice). */
        public Builder<E> choiceFeature(E choice, String featureId) {
            choiceFeatures.put(choice, Feature.of(featureId));
            return this;
        }

        @Override
        public EnumOption<E> build() {
            return new EnumOption<>(this);
        }
    }
}
