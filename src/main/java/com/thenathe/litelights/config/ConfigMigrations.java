package com.thenathe.litelights.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.function.Consumer;

/**
 * The steps that bring an older litelights.json up to the current schema. Each step takes the
 * whole file at {@code fromSchema} and leaves it at {@code fromSchema + 1}. Steps must only move
 * or rename values, never drop them: anything a step doesn't understand stays where it is.
 *
 * <p>Schema 1 is the first release's layout. A file without a schema number is read as schema 1.
 * Schema 2 (0.1.5) stores the jump splash as a choice.
 */
public record ConfigMigrations(int currentSchema, List<Step> steps) {
    public static final int CURRENT_SCHEMA = 2;

    public record Step(int fromSchema, String what, Consumer<JsonObject> apply) {
    }

    /** The real chain. Add a step here, and raise {@link #CURRENT_SCHEMA}, whenever the layout changes. */
    public static ConfigMigrations standard() {
        return new ConfigMigrations(CURRENT_SCHEMA, List.of(
                new Step(1, "jump splash: on/off becomes Off, Basic or Advanced", ConfigMigrations::jumpSplashChoices)));
    }

    /**
     * 0.1.5: the jump splash switch became a choice. Off stays Off; on, which was the default, takes
     * the new default, Advanced.
     */
    static void jumpSplashChoices(JsonObject root) {
        JsonElement options = root.get("options");
        if (options == null || !options.isJsonObject()) {
            return;
        }
        JsonObject values = options.getAsJsonObject();
        JsonElement splash = values.get("water.jump_splash");
        if (splash != null && splash.isJsonPrimitive() && splash.getAsJsonPrimitive().isBoolean()) {
            values.addProperty("water.jump_splash", splash.getAsBoolean() ? "advanced" : "off");
        }
    }

    /** Runs every step from {@code schema} up; returns the schema the file ends at. */
    public int migrate(JsonObject root, int schema) {
        int at = schema;
        while (at < currentSchema) {
            int from = at;
            Step step = steps.stream().filter(candidate -> candidate.fromSchema() == from).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No migration from schema " + from));
            step.apply().accept(root);
            at++;
        }
        return at;
    }
}
