package com.thenathe.litelights.render.feature.farterrain.land;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The land far terrain draws: what the player has visited, and where nothing visited exists,
 * what seed generation predicted. The two are kept apart on disk and only meet here, column by
 * column: a column the player has seen is always the visited one, whatever was predicted for it,
 * so predictions never stand in place of land seen; a column never seen takes the prediction.
 * A tile's revision is the sum of its two sides' revisions, so either changing makes its mesh out
 * of date and it is meshed again: a prediction is replaced as soon as the chunks under it arrive.
 *
 * <p>Thread-safe: the selection asks on the client thread, the mesh workers on theirs.
 */
final class LandView {
    /** Predicted tiles, already in the visited store's block and biome ids. */
    interface Predictions {
        boolean present(long key);

        @Nullable TileMeta meta(long key);

        @Nullable TileVoxels read(long key) throws IOException;
    }

    private record Merged(long visitedRevision, long predictedRevision, TileMeta meta) {
    }

    private final FarStore visited;
    private final BlockKinds kinds;
    private volatile @Nullable Predictions predictions;
    private final Map<Long, Merged> merged = new ConcurrentHashMap<>();

    LandView(FarStore visited, BlockKinds kinds) {
        this.visited = visited;
        this.kinds = kinds;
    }

    /** Starts or stops showing predictions (null stops). */
    void predictions(@Nullable Predictions predictions) {
        this.predictions = predictions;
        merged.clear();
    }

    boolean showsPredictions() {
        return predictions != null;
    }

    boolean present(long key) {
        Predictions predicted = predictions;
        return visited.present(key) || (predicted != null && predicted.present(key));
    }

    /** A predicted tile covers its whole ground; a visited one only its children's. */
    boolean coversWhole(long key) {
        Predictions predicted = predictions;
        return predicted != null && predicted.meta(key) != null;
    }

    @Nullable TileMeta meta(long key) {
        TileMeta seen = visited.meta(key);
        Predictions predicted = predictions;
        TileMeta guessed = predicted == null ? null : predicted.meta(key);
        if (guessed == null) {
            return seen;
        }
        if (seen == null) {
            return guessed;
        }
        Merged known = merged.get(key);
        if (known != null && known.visitedRevision == seen.revision() && known.predictedRevision == guessed.revision()) {
            return known.meta;
        }
        TileMeta both = merge(seen, guessed);
        merged.put(key, new Merged(seen.revision(), guessed.revision(), both));
        return both;
    }

    /** The two sides' metas as one: rows spanning both, each edge column from the side that knows it. */
    static TileMeta merge(TileMeta seen, TileMeta guessed) {
        int lowest;
        int highest;
        if (seen.empty()) {
            lowest = guessed.lowestRow();
            highest = guessed.highestRow();
        } else if (guessed.empty()) {
            lowest = seen.lowestRow();
            highest = seen.highestRow();
        } else {
            lowest = Math.min(seen.lowestRow(), guessed.lowestRow());
            highest = Math.max(seen.highestRow(), guessed.highestRow());
        }
        int[][] tops = new int[4][TileVoxels.SIZE];
        for (int edge = 0; edge < 4; edge++) {
            for (int i = 0; i < TileVoxels.SIZE; i++) {
                int top = seen.edges().top(edge, i);
                tops[edge][i] = top == TileVoxels.UNKNOWN_TOP ? guessed.edges().top(edge, i) : top;
            }
        }
        return new TileMeta(lowest, highest, new EdgeTops(tops), seen.revision() + guessed.revision());
    }

    /** The tile as drawn: the prediction with every column the player has seen put over it. */
    @Nullable TileVoxels read(long key) throws IOException {
        TileVoxels seen = visited.meta(key) == null ? null : visited.read(key);
        Predictions predicted = predictions;
        TileVoxels guessed = predicted == null || predicted.meta(key) == null ? null : predicted.read(key);
        if (guessed == null) {
            return seen;
        }
        if (seen == null) {
            return guessed;
        }
        if (seen.level != guessed.level || seen.minY != guessed.minY || seen.height != guessed.height) {
            return seen;
        }
        return overlay(seen, guessed);
    }

    /** Every column the player has seen, put over the prediction (which is changed and returned). */
    static TileVoxels overlay(TileVoxels seen, TileVoxels guessed) {
        for (int z = 0; z < TileVoxels.SIZE; z++) {
            for (int x = 0; x < TileVoxels.SIZE; x++) {
                if (!seen.known(x, z)) {
                    continue;
                }
                for (int y = 0; y < seen.height; y++) {
                    int index = TileVoxels.index(x, y, z);
                    guessed.states[index] = seen.states[index];
                    guessed.light[index] = seen.light[index];
                }
                guessed.biomes[TileVoxels.column(x, z)] = seen.biomes[TileVoxels.column(x, z)];
            }
        }
        guessed.hasInsets = seen.hasInsets && guessed.hasInsets;
        return guessed;
    }

    /** The visited store's kinds, for the tests. */
    BlockKinds kinds() {
        return kinds;
    }
}
