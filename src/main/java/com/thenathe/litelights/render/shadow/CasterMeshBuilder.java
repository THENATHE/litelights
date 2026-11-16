package com.thenathe.litelights.render.shadow;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Builds a section's depth-only shadow casters on a worker thread.
 *
 * <ul>
 *   <li>Full opaque cubes become merged rectangles: every exposed face of a slice is greedily
 *   merged, so flat ground costs a handful of quads.</li>
 *   <li>Every other block with a model (slabs, stairs, fences, plants, leaves) contributes its own
 *   model quads. Cutout quads keep their atlas coordinates so the shadow shader can alpha-test
 *   them; translucent quads (glass, ice, water) cast nothing.</li>
 *   <li>Block entities whose block model has no geometry (chests, banners, heads, shulker boxes:
 *   in 26.3 their block models carry only a particle texture) cast through their outline shape.</li>
 * </ul>
 *
 * Model quad lists are only read, never kept or compared by identity: FerriteCore shares them
 * between models.
 */
public final class CasterMeshBuilder {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final CasterMeshWriter writer = new CasterMeshWriter();
    private final RandomSource random = RandomSource.create();
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private final boolean[] solid = new boolean[16 * 16 * 16];
    private final boolean[] faceMask = new boolean[GreedyFaceMerger.SIZE * GreedyFaceMerger.SIZE];
    private final BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();

    public CasterMesh build(SectionSnapshot section, BlockStateModelSet models) {
        writer.reset();
        boolean anySolid = markSolidBlocks(section);
        if (anySolid) {
            for (Direction direction : DIRECTIONS) {
                emitMergedFaces(section, direction);
            }
        }
        emitModelBlocks(section, models);
        return writer.finish(section.sectionKey());
    }

    private boolean markSolidBlocks(SectionSnapshot section) {
        boolean any = false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    boolean isSolid = isFullOpaqueCube(section.get(x, y, z));
                    solid[index(x, y, z)] = isSolid;
                    any |= isSolid;
                }
            }
        }
        return any;
    }

    private static boolean isFullOpaqueCube(BlockState state) {
        return !state.isAir() && state.isSolidRender() && state.getRenderShape() == RenderShape.MODEL;
    }

    /**
     * For each slice along the direction, marks the cells whose solid block has an exposed face on
     * that side, then merges them.
     */
    private void emitMergedFaces(SectionSnapshot section, Direction direction) {
        Direction.Axis axis = direction.getAxis();
        boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        for (int slice = 0; slice < 16; slice++) {
            boolean anyFace = false;
            for (int v = 0; v < 16; v++) {
                for (int u = 0; u < 16; u++) {
                    // In-plane axes in X, Y, Z order: X faces use (y, z), Y faces (x, z), Z faces (x, y).
                    int x = switch (axis) {
                        case X -> slice;
                        case Y, Z -> u;
                    };
                    int y = switch (axis) {
                        case X -> u;
                        case Y -> slice;
                        case Z -> v;
                    };
                    int z = switch (axis) {
                        case X, Y -> v;
                        case Z -> slice;
                    };
                    boolean exposed = solid[index(x, y, z)] && !isNeighbourSolid(section, x, y, z, direction);
                    faceMask[u + v * 16] = exposed;
                    anyFace |= exposed;
                }
            }
            if (!anyFace) {
                continue;
            }
            float plane = positive ? slice + 1 : slice;
            GreedyFaceMerger.merge(faceMask, rectangle -> emitRectangle(axis, plane, rectangle));
        }
    }

    private boolean isNeighbourSolid(SectionSnapshot section, int x, int y, int z, Direction direction) {
        int nx = x + direction.getStepX();
        int ny = y + direction.getStepY();
        int nz = z + direction.getStepZ();
        if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16) {
            return solid[index(nx, ny, nz)];
        }
        return isFullOpaqueCube(section.neighbour(x, y, z, direction));
    }

    /** Writes one merged rectangle, with u and v mapped to world axes as in emitMergedFaces. */
    private void emitRectangle(Direction.Axis axis, float plane, int rectangle) {
        float u0 = GreedyFaceMerger.u(rectangle);
        float v0 = GreedyFaceMerger.v(rectangle);
        float u1 = u0 + GreedyFaceMerger.width(rectangle);
        float v1 = v0 + GreedyFaceMerger.height(rectangle);
        switch (axis) {
            case X -> {
                writer.vertex(plane, u0, v0, 0, 0, 0);
                writer.vertex(plane, u1, v0, 0, 0, 0);
                writer.vertex(plane, u1, v1, 0, 0, 0);
                writer.vertex(plane, u0, v1, 0, 0, 0);
            }
            case Y -> {
                writer.vertex(u0, plane, v0, 0, 0, 0);
                writer.vertex(u1, plane, v0, 0, 0, 0);
                writer.vertex(u1, plane, v1, 0, 0, 0);
                writer.vertex(u0, plane, v1, 0, 0, 0);
            }
            case Z -> {
                writer.vertex(u0, v0, plane, 0, 0, 0);
                writer.vertex(u1, v0, plane, 0, 0, 0);
                writer.vertex(u1, v1, plane, 0, 0, 0);
                writer.vertex(u0, v1, plane, 0, 0, 0);
            }
        }
        writer.endQuad();
    }

    private void emitModelBlocks(SectionSnapshot section, BlockStateModelSet models) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (solid[index(x, y, z)]) {
                        continue;
                    }
                    BlockState state = section.get(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    boolean modelHasGeometry = state.getRenderShape() == RenderShape.MODEL
                        && emitModel(section, models, state, x, y, z);
                    if (!modelHasGeometry && castsThroughShape(state)) {
                        emitShapeBoxes(state, x, y, z);
                    }
                }
            }
        }
    }

    /** Emits a block's model quads; returns whether the model has any geometry at all, culled or not. */
    private boolean emitModel(SectionSnapshot section, BlockStateModelSet models, BlockState state, int x, int y, int z) {
        worldPos.set(section.originX() + x, section.originY() + y, section.originZ() + z);
        random.setSeed(state.getSeed(worldPos));
        parts.clear();
        models.get(state).collectParts(random, parts);
        // Grass, flowers, ferns, bamboo and the like are drawn shifted by a random amount per
        // position. The game's own offset function is used, as in its chunk meshing, so the shadow
        // always sits under the plant as drawn, whatever a mod makes that function return.
        Vec3 offset = state.getOffset(worldPos);
        float originX = x + (float) offset.x;
        float originY = y + (float) offset.y;
        float originZ = z + (float) offset.z;
        boolean hasGeometry = false;
        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                List<BakedQuad> quads = part.getQuads(direction);
                hasGeometry |= !quads.isEmpty();
                if (!Block.shouldRenderFace(state, section.neighbour(x, y, z, direction), direction)) {
                    continue;
                }
                emitQuads(quads, originX, originY, originZ);
            }
            List<BakedQuad> unculled = part.getQuads(null);
            hasGeometry |= !unculled.isEmpty();
            emitQuads(unculled, originX, originY, originZ);
        }
        return hasGeometry;
    }

    /** Portals have outline shapes too, but they are light, not matter. */
    private static boolean castsThroughShape(BlockState state) {
        return state.hasBlockEntity() && !state.is(BlockTags.PORTALS);
    }

    /**
     * Block entities drawn without block model geometry (chests, banners, heads, shulker boxes) cast
     * through their outline shape. That covers vanilla's own renderers, which the cached map can't
     * replay, and mods such as Better Block Entities, which draw these blocks into chunk meshes in
     * their own way. Only the stateless shape is used, so no level access is needed.
     */
    private void emitShapeBoxes(BlockState state, int x, int y, int z) {
        for (AABB box : state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
            float x0 = (float) (x + box.minX);
            float y0 = (float) (y + box.minY);
            float z0 = (float) (z + box.minZ);
            float x1 = (float) (x + box.maxX);
            float y1 = (float) (y + box.maxY);
            float z1 = (float) (z + box.maxZ);
            boxFace(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            boxFace(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
            boxFace(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
            boxFace(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            boxFace(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            boxFace(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        }
    }

    private void boxFace(float ax, float ay, float az, float bx, float by, float bz,
                         float cx, float cy, float cz, float dx, float dy, float dz) {
        writer.vertex(ax, ay, az, 0, 0, 0);
        writer.vertex(bx, by, bz, 0, 0, 0);
        writer.vertex(cx, cy, cz, 0, 0, 0);
        writer.vertex(dx, dy, dz, 0, 0, 0);
        writer.endQuad();
    }

    private void emitQuads(List<BakedQuad> quads, float x, float y, float z) {
        for (int i = 0, count = quads.size(); i < count; i++) {
            BakedQuad quad = quads.get(i);
            ChunkSectionLayer layer = quad.materialInfo().layer();
            if (layer.translucent()) {
                continue;
            }
            int flags = layer == ChunkSectionLayer.CUTOUT ? CasterMesh.FLAG_ALPHA_TEST : 0;
            for (int vertex = 0; vertex < 4; vertex++) {
                Vector3fc position = quad.position(vertex);
                long uv = quad.packedUV(vertex);
                writer.vertex(x + position.x(), y + position.y(), z + position.z(), UVPair.unpackU(uv), UVPair.unpackV(uv), flags);
            }
            writer.endQuad();
        }
    }

    private static int index(int x, int y, int z) {
        return x + (z << 4) + (y << 8);
    }
}
