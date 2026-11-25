package com.thenathe.litelights.overlays.generation;

import java.util.Map;
import java.util.TreeMap;

/**
 * The files of one build, by path inside the pack ({@code assets/<namespace>/...}). Sorted, so
 * listing and exporting give the same order every time.
 */
public record GeneratedFiles(Map<String, byte[]> files, GenerationReport report) {
    public static final GeneratedFiles EMPTY = new GeneratedFiles(Map.of(), GenerationReport.NONE);

    public GeneratedFiles {
        files = new TreeMap<>(files);
    }

    public byte[] get(String path) {
        return files.get(path);
    }
}
