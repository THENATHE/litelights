package com.thenathe.litelights.render.feature.farterrain.land;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names to small ids and back, kept in a text file of one name per line (the id is the line). Far
 * terrain stores block states and biomes by name this way, so a mod update or removal changes how
 * a block looks but never breaks the store: a name the game no longer knows reads as a stand-in.
 * Id 0 is fixed at construction ("minecraft:air" for blocks). Ids are never reused or moved.
 * Thread-safe.
 */
public final class NameTable {
    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private int savedCount;

    public NameTable(String first) {
        add(first);
        savedCount = 0;
    }

    public synchronized int idOf(String name) {
        Integer id = ids.get(name);
        return id != null ? id : add(name);
    }

    private int add(String name) {
        int id = names.size();
        names.add(name);
        ids.put(name, id);
        return id;
    }

    /** The name of an id, or null for an id this table never handed out. */
    public synchronized String nameOf(int id) {
        return id >= 0 && id < names.size() ? names.get(id) : null;
    }

    public synchronized int size() {
        return names.size();
    }

    public synchronized boolean changedSinceSave() {
        return savedCount != names.size();
    }

    /**
     * Reads the file, if there is one, into an empty table (only id 0 handed out). A first line
     * that isn't this table's id 0 means the file belongs to something else, and nothing is read.
     */
    public synchronized void load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(names.get(0))) {
            throw new IOException("Not a far terrain name table: " + file);
        }
        for (int i = 1; i < lines.size(); i++) {
            add(lines.get(i));
        }
        savedCount = names.size();
    }

    /** Writes every name through a temporary file, so a crash mid-write leaves the old file whole. */
    public synchronized void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, names, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        savedCount = names.size();
    }
}
