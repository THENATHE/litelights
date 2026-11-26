package com.thenathe.litelights.config;

import com.google.gson.JsonElement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * One setting: an id, where it sits in the screen, its default and its current value.
 *
 * <p>Values are safe to read from any thread. Setting a value runs the option's listeners and the
 * registry's listeners on the thread that set it (the render thread when it comes from the
 * settings screen); listeners only run when the value really changed.
 *
 * @param <T> the value type: Boolean, an enum, Integer or Float
 */
public abstract sealed class Option<T> permits BoolOption, EnumOption, IntOption, FloatOption {
    private final String id;
    private final String page;
    private final String section;
    private final T defaultValue;
    private final Cost cost;
    private final Feature feature;
    private final String labelKey;
    private final String descriptionKey;
    private final String creditKey;
    private final String tagKey;
    private final String lockedTagKey;
    private final BoolOption needs;
    private final Condition enabledWhen;
    private final BooleanSupplier shownWhen;
    private final List<ValueListener<T>> listeners = new CopyOnWriteArrayList<>();
    private volatile T value;
    private volatile OptionRegistry registry;
    private volatile Set<Option<?>> dependencies;

    /** Told the old and new value after a change. */
    @FunctionalInterface
    public interface ValueListener<T> {
        void changed(T oldValue, T newValue);
    }

    /**
     * When a row matters, worked out from other options' values. It reads them through
     * {@code values}, never directly, so a screen holding changes back (Sodium's, until Apply) can
     * ask it about those.
     */
    @FunctionalInterface
    public interface Condition {
        boolean holds(OptionValues values);
    }

    protected Option(Builder<T, ?, ?> builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.page = Objects.requireNonNull(builder.page, "page of " + builder.id);
        this.section = Objects.requireNonNull(builder.section, "section of " + builder.id);
        this.cost = builder.cost;
        this.feature = Feature.of(builder.featureId != null ? builder.featureId : builder.id);
        this.labelKey = builder.labelKey != null ? builder.labelKey : "litelights.option." + builder.id;
        this.descriptionKey = builder.descriptionKey != null ? builder.descriptionKey : labelKey + ".description";
        this.creditKey = builder.creditKey;
        this.tagKey = builder.tagKey;
        this.lockedTagKey = builder.lockedTagKey;
        this.needs = builder.needs;
        this.enabledWhen = builder.enabledWhen;
        this.shownWhen = builder.shownWhen;
        this.defaultValue = builder.defaultValue;
        this.value = builder.defaultValue;
    }

    /** Called once the subclass has checked the default, so the default is always a legal value. */
    protected final void checkDefault() {
        if (!Objects.equals(normalize(defaultValue), defaultValue)) {
            throw new IllegalArgumentException(id + ": default " + defaultValue + " is outside the option's range or step");
        }
    }

    public String id() {
        return id;
    }

    /** The id of the settings page this option is on. */
    public String page() {
        return page;
    }

    /**
     * The section within the page; the caption's translation key is {@code litelights.section.<section>}.
     * A section with a slash is a sub-section ({@link Pages#parentSection}).
     */
    public String section() {
        return section;
    }

    public T defaultValue() {
        return defaultValue;
    }

    public Cost cost() {
        return cost;
    }

    public Feature feature() {
        return feature;
    }

    public String labelKey() {
        return labelKey;
    }

    public String descriptionKey() {
        return descriptionKey;
    }

    /** The translation key of the row's credit, shown by the heart beside its name, or null for none. */
    public String creditKey() {
        return creditKey;
    }

    /** The translation key of a small tag shown on the row, such as "Experimental", or null for none. */
    public String tagKey() {
        return tagKey;
    }

    /** The tag shown instead while the row is greyed out, saying why (such as "Dynamic clouds only"), or null. */
    public String lockedTagKey() {
        return lockedTagKey;
    }

    /** A switch that must be on for this option to matter (bloom intensity needs bloom), or null. */
    public BoolOption needs() {
        return needs;
    }

    /**
     * Whether the option matters right now: its {@link #needs()} switch is on and any other
     * condition holds. The screen greys the row out otherwise; the value is kept either way.
     */
    public boolean active() {
        return active(OptionValues.LIVE);
    }

    /** {@link #active()} with the other options' values read from {@code values}. */
    public boolean active(OptionValues values) {
        return (needs == null || values.on(needs) && needs.active(values)) && conditionHolds(values);
    }

    /** The {@code enabledWhen} condition alone, without the {@link #needs()} switch. */
    public boolean conditionHolds() {
        return conditionHolds(OptionValues.LIVE);
    }

    public boolean conditionHolds(OptionValues values) {
        return enabledWhen == null || enabledWhen.holds(values);
    }

    /**
     * Every other option {@link #active(OptionValues)} reads: the {@link #needs()} switch, what that
     * switch itself depends on, and what the condition reads. Found once, by trying the condition
     * on those options' values ({@link OptionValues#readBy}).
     */
    public Set<Option<?>> dependencies() {
        Set<Option<?>> found = dependencies;
        if (found == null) {
            Set<Option<?>> read = new LinkedHashSet<>(OptionValues.readBy(this::active));
            read.remove(this);
            found = Set.copyOf(read);
            dependencies = found;
        }
        return found;
    }

    /** Whether the row is shown at all right now (grading sliders only show for the edited time of day). */
    public boolean shown() {
        return shownWhen == null || shownWhen.getAsBoolean();
    }

    public T get() {
        return value;
    }

    /** Sets the value, after clamping and snapping it into range. */
    public void set(T newValue) {
        T normalized = normalize(Objects.requireNonNull(newValue, "value"));
        T old = value;
        if (Objects.equals(old, normalized)) {
            return;
        }
        value = normalized;
        for (ValueListener<T> listener : listeners) {
            listener.changed(old, normalized);
        }
        OptionRegistry owner = registry;
        if (owner != null) {
            owner.fireChanged(this);
        }
    }

    public void reset() {
        set(defaultValue);
    }

    public boolean isChanged() {
        return !Objects.equals(value, defaultValue);
    }

    public void addListener(ValueListener<T> listener) {
        listeners.add(listener);
    }

    public void removeListener(ValueListener<T> listener) {
        listeners.remove(listener);
    }

    /** Brings a value into the option's range and step; enums and booleans pass through. */
    protected abstract T normalize(T candidate);

    /** Reads a stored value. Throws IllegalArgumentException when it can't be read as this option's type. */
    protected abstract T read(JsonElement element);

    protected abstract JsonElement write(T stored);

    /** Reads a stored value into the option without running listeners; used while loading. */
    final void load(JsonElement element) {
        value = normalize(read(element));
    }

    final JsonElement writeCurrent() {
        return write(value);
    }

    final void attach(OptionRegistry owner) {
        if (registry != null && registry != owner) {
            throw new IllegalStateException(id + " is already registered");
        }
        registry = owner;
        feature.add(this);
    }

    @Override
    public String toString() {
        return id + "=" + value;
    }

    /**
     * Shared builder steps. Every option needs an id, a page and a section; the rest is optional.
     * Labels default to {@code litelights.option.<id>} and {@code litelights.option.<id>.description}.
     */
    @SuppressWarnings("unchecked")
    public abstract static class Builder<T, O extends Option<T>, B extends Builder<T, O, B>> {
        private final String id;
        private final T defaultValue;
        private String page;
        private String section;
        private Cost cost = Cost.FREE;
        private String featureId;
        private String labelKey;
        private String descriptionKey;
        private String creditKey;
        private String tagKey;
        private String lockedTagKey;
        private BoolOption needs;
        private Condition enabledWhen;
        private BooleanSupplier shownWhen;

        protected Builder(String id, T defaultValue) {
            this.id = id;
            this.defaultValue = defaultValue;
        }

        /** Page and section in one step: {@code at(Pages.SHADOWS, "quality")}. */
        public B at(String page, String section) {
            this.page = page;
            this.section = section;
            return (B) this;
        }

        public B cost(Cost cost) {
            this.cost = cost;
            return (B) this;
        }

        /** Groups the option with others under one feature state; defaults to the option's own id. */
        public B feature(String featureId) {
            this.featureId = featureId;
            return (B) this;
        }

        /** Uses other translation keys for the label and description, for modules with their own lang files. */
        public B text(String labelKey, String descriptionKey) {
            this.labelKey = labelKey;
            this.descriptionKey = descriptionKey;
            return (B) this;
        }

        /** Gives the row a credit heart, its tooltip read from {@code <label key>.credit}. */
        public B credit() {
            this.creditKey = (labelKey != null ? labelKey : "litelights.option." + id) + ".credit";
            return (B) this;
        }

        public B credit(String translationKey) {
            this.creditKey = translationKey;
            return (B) this;
        }

        /** Shows a small tag on the row, such as {@code litelights.tag.experimental}. */
        public B tag(String translationKey) {
            this.tagKey = translationKey;
            return (B) this;
        }

        /** While the row is greyed out, a small tag saying why, such as {@code litelights.tag.dynamic_clouds_only}. */
        public B lockedTag(String translationKey) {
            this.lockedTagKey = translationKey;
            return (B) this;
        }

        public B needs(BoolOption parent) {
            this.needs = parent;
            return (B) this;
        }

        /** Greys the row out while the condition is false, for conditions other than one switch. */
        public B enabledWhen(Condition condition) {
            this.enabledWhen = condition;
            return (B) this;
        }

        public B shownWhen(BooleanSupplier condition) {
            this.shownWhen = condition;
            return (B) this;
        }

        public abstract O build();

        /** Builds the option and registers it with the shared LiteLights config. */
        public O register() {
            return LiteLightsConfig.registry().register(build());
        }
    }
}
