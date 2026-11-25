package com.thenathe.litelights.overlays.generation;

import java.util.List;

/**
 * What the last build made, for the log and the settings screen.
 *
 * @param atlasTexels texels the overlays add to the block atlas (before mipmaps)
 * @param drawing     how the overlays are drawn: "plain" for Continuity's own overlay method, or
 *                    the variety level used by the module's own method
 * @param notes       one line per decision the build took for the player, such as lowering variety
 *                    to fit a large pack into the atlas
 * @param skipped     one line per block or texture that was left out, with the reason
 */
public record GenerationReport(int sources, int tileSets, int tiles, long bytes, long millis, int reusedTileSets,
                               int coveredPairs, int existingRules, long atlasTexels, String drawing,
                               List<String> notes, List<String> skipped) {
    public static final String DRAWING_PLAIN = "plain";
    public static final GenerationReport NONE = new GenerationReport(0, 0, 0, 0, 0, 0, 0, 0, 0, "", List.of(), List.of());

    public String summary() {
        return String.format("%d overlay sets (%d tiles, %d KiB, %.1f M atlas texels, %s) from %d blocks in %d ms; "
                + "%d reused, %d pairs left to other packs, %d skipped",
            tileSets, tiles, bytes / 1024, atlasTexels / 1e6, drawing, sources, millis, reusedTileSets, coveredPairs,
            skipped.size());
    }
}
