package com.thenathe.litelights.overlays.generation;

/**
 * Numbers the pieces of one variety tile set, so the generator and the drawing method agree on
 * which sprite is which. Each piece is saved as {@code <index>.png} in the set's folder.
 *
 * <ul>
 *   <li><b>Bands</b>, one per edge, per class of the corner it starts at, per class of the corner it
 *       ends at, per wave.</li>
 *   <li><b>Inner corners</b>, one per corner and class: the fill where two bands meet on a face.</li>
 *   <li><b>Steps</b>, one per edge: the band a block one step up spills onto the top of the block
 *       below it. They use the source's side texture, read upwards from its bottom row, because
 *       that's the face the eye follows down the step. Steps keep the plain corner depth, which
 *       keeps them cheap; they only meet other steps along a step's line.</li>
 * </ul>
 *
 * <p>Until 0.1.7 there were outer corners too, for a block whose only softer neighbour touched it
 * at a corner. They were dropped, and the steps moved down into their numbers; nothing keeps the
 * old numbering, because the variety files only ever live in the in-memory pack of the same build.
 *
 * @param classes how many depth classes a block corner can have
 * @param waves   how many waves an edge can have
 */
public record VarietyLayout(int classes, int waves) {
    public VarietyLayout {
        if (classes < 1 || waves < 1) {
            throw new IllegalArgumentException("A variety layout needs at least one class and one wave");
        }
    }

    public int bandCount() {
        return 4 * classes * classes * waves;
    }

    public int band(int direction, int startClass, int endClass, int wave) {
        return ((direction * classes + startClass) * classes + endClass) * waves + wave;
    }

    public int innerCorner(int corner, int cornerClass) {
        return bandCount() + corner * classes + cornerClass;
    }

    public int step(int direction) {
        return bandCount() + 4 * classes + direction;
    }

    public int pieceCount() {
        return bandCount() + 4 * classes + 4;
    }

    /** The edges a piece hangs from: one for a band or step, the two that meet at its corner for an inner corner. */
    public int[] anchors(int piece) {
        if (piece < bandCount()) {
            return new int[] {piece / (classes * classes * waves)};
        }
        int afterBands = piece - bandCount();
        if (afterBands < 4 * classes) {
            int corner = afterBands / classes;
            return new int[] {corner, (corner + 1) % 4};
        }
        return new int[] {afterBands - 4 * classes};
    }

    public boolean isStep(int piece) {
        return piece >= bandCount() + 4 * classes;
    }
}
