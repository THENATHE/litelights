package com.thenathe.litelights.render.feature.farterrain.seed;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ReportedException;

/**
 * This game's world-generation data couldn't be read, with the namespaces of the entries that
 * failed (a mod's id or a data pack's namespace), so the player can be told which mod. The game
 * itself only says "Registry Loading"; the entries come from its crash report, whose lines read
 * {@code <registry>/<entry>: <message>}. Why each failed is logged in full by the game just before.
 */
public final class UnreadableData extends RuntimeException {
    private static final Pattern ENTRY = Pattern.compile("^\\s*[a-z0-9_.-]+:[a-z0-9_./-]+/([a-z0-9_.-]+):[a-z0-9_./-]+: .*$");

    private final SortedSet<String> namespaces;

    UnreadableData(SortedSet<String> namespaces, Throwable cause) {
        super("World-generation data couldn't be read: " + (namespaces.isEmpty() ? "entries the game didn't name" : String.join(", ", namespaces)),
            cause);
        this.namespaces = namespaces;
    }

    static UnreadableData from(ReportedException report) {
        return from(report.getReport().getDetails(), report);
    }

    /** Reads the crash report's details: one line per entry that failed. */
    static UnreadableData from(String details, Throwable cause) {
        SortedSet<String> namespaces = new TreeSet<>();
        for (String line : details.split("\\R")) {
            Matcher entry = ENTRY.matcher(line);
            if (entry.matches()) {
                namespaces.add(entry.group(1));
            }
        }
        return new UnreadableData(namespaces, cause);
    }

    /** The namespaces of the entries that couldn't be read, sorted; empty when the report named none. */
    public List<String> namespaces() {
        return List.copyOf(namespaces);
    }
}
