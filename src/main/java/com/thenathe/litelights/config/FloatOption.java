package com.thenathe.litelights.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * A number between {@code min} and {@code max} in steps of {@code step}. Percentages are stored as
 * fractions (100% is 1.0) so the renderer can use them directly.
 */
public final class FloatOption extends Option<Float> {
    private final float min;
    private final float max;
    private final float step;
    private final ValueFormat format;

    private FloatOption(Builder builder) {
        super(builder);
        if (!(builder.min <= builder.max) || !(builder.step > 0)) {
            throw new IllegalArgumentException(builder.toString());
        }
        this.min = builder.min;
        this.max = builder.max;
        this.step = builder.step;
        this.format = builder.format;
        checkDefault();
    }

    public static Builder builder(String id, float defaultValue, float min, float max, float step) {
        return new Builder(id, defaultValue, min, max, step);
    }

    public float min() {
        return min;
    }

    public float max() {
        return max;
    }

    public float step() {
        return step;
    }

    public ValueFormat format() {
        return format;
    }

    /** The value as a primitive, for hot paths. */
    public float value() {
        return get();
    }

    public int stepCount() {
        return Math.round((max - min) / step);
    }

    /** The value at a whole number of steps from {@code min}, rounded so 0.1 steps don't drift to 0.30000001. */
    public float valueAtStep(int index) {
        float raw = min + Math.clamp(index, 0, stepCount()) * step;
        return Math.min(max, Math.round(raw * 10000f) / 10000f);
    }

    public int stepIndex(float candidate) {
        return Math.round((Math.clamp(candidate, min, max) - min) / step);
    }

    @Override
    protected Float normalize(Float candidate) {
        if (candidate.isNaN()) {
            return defaultValue();
        }
        return valueAtStep(stepIndex(candidate));
    }

    @Override
    protected Float read(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            float number = element.getAsFloat();
            if (!Float.isNaN(number)) {
                return number;
            }
        }
        throw new IllegalArgumentException("not a number: " + element);
    }

    @Override
    protected JsonElement write(Float stored) {
        return new JsonPrimitive(stored);
    }

    public static final class Builder extends Option.Builder<Float, FloatOption, Builder> {
        private final float min;
        private final float max;
        private final float step;
        private ValueFormat format = ValueFormat.PERCENT;

        private Builder(String id, float defaultValue, float min, float max, float step) {
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
        public FloatOption build() {
            return new FloatOption(this);
        }

        @Override
        public String toString() {
            return "FloatOption.Builder[min=" + min + ", max=" + max + ", step=" + step + "]";
        }
    }
}
