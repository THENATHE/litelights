package com.thenathe.litelights.render.shadow;

import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * A copy of one chunk section's blocks and its six face neighbours, taken on the client thread so
 * a worker thread can build the section's shadow casters without touching the live level.
 * Missing neighbours (unloaded chunks, outside the world) read as air.
 */
public final class SectionSnapshot {
    private final long sectionKey;
    private final PalettedContainer<BlockState> states;
    private final @Nullable PalettedContainer<BlockState>[] neighbours;

    public SectionSnapshot(long sectionKey, PalettedContainer<BlockState> states, @Nullable PalettedContainer<BlockState>[] neighbours) {
        this.sectionKey = sectionKey;
        this.states = states;
        this.neighbours = neighbours;
    }

    public long sectionKey() {
        return sectionKey;
    }

    public int originX() {
        return SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey));
    }

    public int originY() {
        return SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
    }

    public int originZ() {
        return SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey));
    }

    /** The block at section-local coordinates 0..15. */
    public BlockState get(int x, int y, int z) {
        return states.get(x, y, z);
    }

    /**
     * The block next to (x, y, z) in the given direction, which may lie in a neighbouring section.
     * Only face neighbours are available, which is all face culling needs.
     */
    public BlockState neighbour(int x, int y, int z, Direction direction) {
        int nx = x + direction.getStepX();
        int ny = y + direction.getStepY();
        int nz = z + direction.getStepZ();
        if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16) {
            return states.get(nx, ny, nz);
        }
        PalettedContainer<BlockState> neighbour = neighbours[direction.ordinal()];
        if (neighbour == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return neighbour.get(nx & 15, ny & 15, nz & 15);
    }
}
