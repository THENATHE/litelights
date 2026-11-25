package com.thenathe.litelights.render.feature.farterrain.seed;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The seeds a player has given LiteLights for servers, and what they chose at the notice, one
 * record per server address in {@code <game>/litelights/server-seeds.json}. Seeds are kept as
 * decimal text, so every signed 64-bit value survives any JSON tool. The file is replaced whole
 * through a temporary file, with only its owner allowed to read it where the system has file
 * permissions. A file that can't be read is kept as it is and nothing is written over it; seeds
 * for that session are kept in memory only. Nothing here ever writes a seed to the log.
 */
public final class ServerSeeds {
    /** Where a seed came from. */
    public enum Source { TYPED, SEED_COMMAND, HELPER }

    /** What the player chose at the notice for this server. */
    public enum Choice { ASK, ALWAYS }

    public record Entry(@Nullable Long seed, Source source, Choice choice) {
        public Entry {
            Objects.requireNonNull(source);
            Objects.requireNonNull(choice);
        }

        @Override
        public String toString() {
            return "Entry[" + (seed == null ? "no seed" : "a seed") + ", " + source + ", " + choice + "]";
        }
    }

    private static final int VERSION = 1;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, Entry> servers = new TreeMap<>();
    private boolean writable = true;
    private @Nullable String problem;

    public ServerSeeds(Path file) {
        this.file = file;
        read();
    }

    /** The file's problem, if it couldn't be read (its seeds are then kept for this session only). */
    public synchronized @Nullable String problem() {
        return problem;
    }

    public synchronized @Nullable Entry get(String address) {
        return servers.get(normalize(address));
    }

    public synchronized void putSeed(String address, long seed, Source source) {
        String key = normalize(address);
        Entry before = servers.get(key);
        servers.put(key, new Entry(seed, source, before == null ? Choice.ASK : before.choice()));
        write();
    }

    public synchronized void setChoice(String address, Choice choice) {
        String key = normalize(address);
        Entry before = servers.get(key);
        servers.put(key, new Entry(before == null ? null : before.seed(), before == null ? Source.TYPED : before.source(), choice));
        write();
    }

    /** Forgets this server's seed and choice. */
    public synchronized void forget(String address) {
        if (servers.remove(normalize(address)) != null) {
            write();
        }
    }

    /**
     * A server address as the key: host in lower case without a final dot, the port always
     * written (25565 when none is given). No lookups: two names for one server stay two records.
     */
    public static String normalize(String address) {
        String trimmed = address.trim().toLowerCase(Locale.ROOT);
        String host = trimmed;
        String port = "25565";
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close > 0) {
                host = trimmed.substring(0, close + 1);
                if (trimmed.length() > close + 2 && trimmed.charAt(close + 1) == ':') {
                    port = trimmed.substring(close + 2);
                }
            }
        } else if (trimmed.indexOf(':') == trimmed.lastIndexOf(':') && trimmed.indexOf(':') > 0) {
            host = trimmed.substring(0, trimmed.indexOf(':'));
            port = trimmed.substring(trimmed.indexOf(':') + 1);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host + ":" + port;
    }

    /** A seed as a player types it: a signed 64-bit number. Anything else (text seeds) is not taken. */
    public static OptionalLong parse(String text) {
        String value = text.trim();
        if (!value.matches("[+-]?[0-9]{1,20}")) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException outOfRange) {
            return OptionalLong.empty();
        }
    }

    private void read() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != VERSION) {
                throw new JsonParseException("unknown version " + root.get("version"));
            }
            for (Map.Entry<String, JsonElement> server : root.getAsJsonObject("servers").entrySet()) {
                JsonObject entry = server.getValue().getAsJsonObject();
                Long seed = entry.has("seed") && !entry.get("seed").isJsonNull() ? Long.parseLong(entry.get("seed").getAsString()) : null;
                servers.put(normalize(server.getKey()), new Entry(seed, Source.valueOf(entry.get("source").getAsString()),
                    Choice.valueOf(entry.get("choice").getAsString())));
            }
        } catch (IOException | RuntimeException unreadable) {
            // Kept as it is: the player's seeds may be in it. Nothing is written over it.
            writable = false;
            servers.clear();
            problem = "LiteLights couldn't read " + file.getFileName() + " (" + unreadable.getClass().getSimpleName()
                + "); it was left as it is, and seeds given now are kept until the game closes.";
        }
    }

    private void write() {
        if (!writable) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject entries = new JsonObject();
        Map<String, Entry> sorted = new LinkedHashMap<>(servers);
        for (Map.Entry<String, Entry> server : sorted.entrySet()) {
            JsonObject entry = new JsonObject();
            Long seed = server.getValue().seed();
            if (seed != null) {
                entry.addProperty("seed", Long.toString(seed));
            }
            entry.addProperty("source", server.getValue().source().name());
            entry.addProperty("choice", server.getValue().choice().name());
            entries.add(server.getKey(), entry);
        }
        root.add("servers", entries);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            ownerOnly(temporary);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            problem = null;
        } catch (IOException failure) {
            problem = "LiteLights couldn't save " + file.getFileName() + " (" + failure.getClass().getSimpleName() + ").";
        }
    }

    private static void ownerOnly(Path path) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException noPermissions) {
            // A file system without POSIX permissions (Windows): the user's own folder is all there is.
        }
    }
}
