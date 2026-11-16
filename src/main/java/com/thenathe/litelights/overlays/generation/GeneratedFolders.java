package com.thenathe.litelights.overlays.generation;

import java.util.List;

/**
 * The folders, inside the pack's namespace, that one build's files live in. The live pack answers
 * only inside them, so lookups of the mod's other assets in the same namespace never wait for a build.
 *
 * @param roots folder paths ending in {@code /}, such as {@code optifine/ctm/}
 */
public record GeneratedFolders(List<String> roots) {
    public static final GeneratedFolders NONE = new GeneratedFolders(List.of());

    /** Continuity's properties files and the tiles they name. */
    public static final GeneratedFolders CONTINUITY = new GeneratedFolders(
        List.of(OverlayGenerator.CONTINUITY_PROPERTIES_FOLDER, OverlayGenerator.CONTINUITY_TEXTURE_FOLDER));

    /** Whether a file path (relative to the namespace) is inside one of the folders. */
    public boolean holds(String path) {
        for (String root : roots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /** Whether listing {@code directory} could reach a file in one of the folders. */
    public boolean overlaps(String directory) {
        String asFolder = directory.endsWith("/") ? directory : directory + "/";
        for (String root : roots) {
            if (root.startsWith(asFolder) || asFolder.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
