package com.thenathe.litelights.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * A whole number between {@code min} and {@code max}, moving in steps of {@code step} from
 * {@code min}. Shown as a slider, or as buttons when it has three values or fewer.
 */
public final class IntOption extends Option<Integer> {
    private final int min;
    private final int max;
    private final int step;
    private final ValueFormat format;

    private IntOption(Builder builder) {
        super(builder);
        if (builder.min > builder.max || builder.step < 1) {
            throw new IllegalArgumentException(builder.toString());
        }
        this.min = builder.min;
        this.max = builder.max;
        this.step = builder.step;
        this.format = builder.format;
        checkDefault();
    }

    public static Builder builder(String id, int defaultValue, int min, int max, int step) {
        return new Builder(id, defaultValue, min, max, step);
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

    public ValueFormat format() {
        return format;
    }

    /** The value as a primitive, for hot paths. */
    public int value() {
        return get();
    }

    /** How many values the option can take. */
    public int valueCount() {
        return (max - min) / step + 1;
    }

    @Override
    protected Integer normalize(Integer candidate) {
        int clamped = Math.clamp(candidate, min, max);
        int steps = Math.round((clamped - min) / (float) step);
        return Math.min(max, min + steps * step);
    }

    @Override
    protected Integer read(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            double number = element.getAsDouble();
            if (Double.isFinite(number)) {
                return (int) Math.round(Math.clamp(number, Integer.MIN_VALUE, Integer.MAX_VALUE));
            }
        }
        throw new IllegalArgumentException("not a number: " + element);
    }

    @Override
    protected JsonElement write(Integer stored) {
        return new JsonPrimitive(stored);
    }

    public static final class Builder extends Option.Builder<Integer, IntOption, Builder> {
        private final int min;
        private final int max;
        private final int step;
        private ValueFormat format = ValueFormat.PLAIN;

        private Builder(String id, int defaultValue, int min, int max, int step) {
            super(id, defaultValue);
            this.min = min;
            this.max = max;
            this.step = step;
        }

        public Builder format(ValueFormat format) {
            this.format = format;
            return this;
        }

        @Override
        public IntOption build() {
            return new IntOption(this);
        }

        @Override
        public String toString() {
            return "IntOption.Builder[min=" + min + ", max=" + max + ", step=" + step + "]";
        }
    }
}
