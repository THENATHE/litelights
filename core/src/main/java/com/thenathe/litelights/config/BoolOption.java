package com.thenathe.litelights.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** An on/off switch. */
public final class BoolOption extends Option<Boolean> {
    private BoolOption(Builder builder) {
        super(builder);
        checkDefault();
    }

    public static Builder builder(String id, boolean defaultValue) {
        return new Builder(id, defaultValue);
    }

    /** The value as a primitive, for hot paths. */
    public boolean on() {
        return get();
    }

    @Override
    protected Boolean normalize(Boolean candidate) {
        return candidate;
    }

    @Override
    protected Boolean read(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        throw new IllegalArgumentException("not true or false: " + element);
    }

    @Override
    protected JsonElement write(Boolean stored) {
        return new JsonPrimitive(stored);
    }

    public static final class Builder extends Option.Builder<Boolean, BoolOption, Builder> {
        private Builder(String id, boolean defaultValue) {
            super(id, defaultValue);
        }

        @Override
        public BoolOption build() {
            return new BoolOption(this);
        }
    }
}
