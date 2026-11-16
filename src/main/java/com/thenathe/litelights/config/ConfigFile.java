package com.thenathe.litelights.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.thenathe.litelights.LiteLightsClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Reads and writes litelights.json:
 *
 * <pre>
 * {
 *   "schema": 1,
 *   "options": { "shadows.reach": 256, "sky.palette": "vanilla_colours", ... },
 *   "unreadable": { ... values kept because they couldn't be read ... },
 *   "modules": { "overlays": { ... a module's own data ... } }
 * }
 * </pre>
 *
 * Top-level keys it doesn't know are written back unchanged. A file that isn't valid JSON is
 * renamed aside (never deleted) and LiteLights starts from the defaults. Before a migration, the
 * original file is copied aside as {@code litelights.json.schema<N>.bak}.
 */
public final class ConfigFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path path;
    private final OptionRegistry registry;
    private final ConfigMigrations migrations;
    private JsonObject root = new JsonObject();

    public ConfigFile(Path path, OptionRegistry registry, ConfigMigrations migrations) {
        this.path = path;
        this.registry = registry;
        this.migrations = migrations;
    }

    public Path path() {
        return path;
    }

    public synchronized void load() {
        root = new JsonObject();
        if (!Files.isRegularFile(path)) {
            registry.acceptStored(null, null);
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("the file isn't a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | JsonParseException | IllegalStateException broken) {
            setAside(broken);
            registry.acceptStored(null, null);
            return;
        }

        int schema = readSchema(root);
        if (schema < migrations.currentSchema()) {
            backUpBeforeMigrating(schema);
            schema = migrations.migrate(root, schema);
            LiteLightsClient.LOG.info("Upgraded {} to settings schema {}", path.getFileName(), schema);
        }
        root.addProperty("schema", Math.max(schema, migrations.currentSchema()));
        registry.acceptStored(objectOrNull(root, "options"), objectOrNull(root, "unreadable"));
    }

    private static int readSchema(JsonObject root) {
        JsonElement schema = root.get("schema");
        if (schema != null && schema.isJsonPrimitive() && schema.getAsJsonPrimitive().isNumber()) {
            return schema.getAsInt();
        }
        return 1;
    }

    private void backUpBeforeMigrating(int schema) {
        Path backup = path.resolveSibling(path.getFileName() + ".schema" + schema + ".bak");
        try {
            if (!Files.exists(backup)) {
                Files.copy(path, backup);
            }
        } catch (IOException failure) {
            LiteLightsClient.LOG.warn("Couldn't back up {} before upgrading it: {}", path, failure.toString());
        }
    }

    private void setAside(Exception reason) {
        Path aside = path.resolveSibling(path.getFileName() + ".broken-" + LocalDateTime.now().format(STAMP));
        LiteLightsClient.LOG.error("Couldn't read {} ({}). Moved it to {} and started from the defaults.", path, reason.toString(), aside.getFileName());
        try {
            Files.move(path, aside);
        } catch (IOException failure) {
            LiteLightsClient.LOG.error("Couldn't move {} aside: {}", path, failure.toString());
        }
    }

    private static JsonObject objectOrNull(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /**
     * A named object under "modules" that a module reads and writes itself. It is created empty
     * on first use and saved with the rest of the file.
     */
    public synchronized JsonObject module(String id) {
        JsonObject modules = objectOrNull(root, "modules");
        if (modules == null) {
            modules = new JsonObject();
            root.add("modules", modules);
        }
        JsonObject module = objectOrNull(modules, id);
        if (module == null) {
            module = new JsonObject();
            modules.add(id, module);
        }
        return module;
    }

    /** Whether the file has data for this module, for one-time imports from older mods. */
    public synchronized boolean hasModule(String id) {
        JsonObject modules = objectOrNull(root, "modules");
        JsonObject module = modules == null ? null : objectOrNull(modules, id);
        return module != null && !module.isEmpty();
    }

    /** Writes the file, through a temporary file so a crash mid-write never leaves half a file. */
    public synchronized void save() {
        JsonObject out = root.deepCopy();
        out.addProperty("schema", Math.max(readSchema(root), migrations.currentSchema()));
        out.add("options", registry.storedOptions());
        JsonObject unreadable = registry.storedUnreadable();
        if (unreadable.isEmpty()) {
            out.remove("unreadable");
        } else {
            out.add("unreadable", unreadable);
        }
        JsonObject ordered = new JsonObject();
        for (String key : new String[] {"schema", "options", "unreadable", "modules"}) {
            if (out.has(key)) {
                ordered.add(key, out.remove(key));
            }
        }
        out.entrySet().forEach(entry -> ordered.add(entry.getKey(), entry.getValue()));
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(ordered) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            registry.markSaved();
        } catch (IOException failure) {
            LiteLightsClient.LOG.error("Couldn't save {}: {}", path, failure.toString());
        }
    }
}
