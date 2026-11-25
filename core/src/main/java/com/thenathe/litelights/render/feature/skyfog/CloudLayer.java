package com.thenathe.litelights.render.feature.skyfog;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import java.nio.ByteBuffer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * One layer of LiteLights clouds. It draws through vanilla's cloud pipelines and face format
 * (three signed bytes per face, expanded into quads in the vertex shader, which LiteLights
 * replaces with its own lit version), so Improved Transparency and every graphics API work as for
 * vanilla clouds.
 *
 * <p>The face list is rebuilt only when the camera moves into another cell, crosses the layer, or
 * the style changes, never per frame; between rebuilds only the small offset uniform is written.
 */
final class CloudLayer implements AutoCloseable {
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putVec4().putVec3().putVec3().get();
    private static final int FLAG_INSIDE_FACE = 16;
    private static final int FLAG_USE_TOP_COLOR = 32;
    /** Faces are stored as half the cell offset in a signed byte, so the radius can't pass this. */
    private static final int LARGEST_RADIUS_CELLS = 255;

    private static final Direction[] DIRECTIONS = Direction.values();

    private enum CameraSide { ABOVE, INSIDE, BELOW }

    private final byte[] cells;
    private final int size;
    private final float cellSize;
    private final float thickness;
    private final float blocksPerTick;
    private final float offsetZ;
    private final float opacity;
    private final MappableRingBuffer info;
    private @Nullable MappableRingBuffer faces;

    private boolean needsRebuild = true;
    private int builtCellX = Integer.MIN_VALUE;
    private int builtCellZ = Integer.MIN_VALUE;
    private CameraSide builtSide = CameraSide.INSIDE;
    private @Nullable CloudStatus builtStatus;
    private int quadCount;
    private float relativeBottom;

    /**
     * @param cells cell flags from {@link CloudShapes}, {@code size} by {@code size}
     * @param cellSize width of a cell in blocks
     * @param thickness height of a cell in blocks (Fancy)
     * @param blocksPerTick how fast the wind carries the layer along X
     * @param offsetZ a fixed shift along Z, so layers don't line up
     * @param opacity multiplies the cloud colour's alpha
     */
    CloudLayer(String name, byte[] cells, int size, float cellSize, float thickness, float blocksPerTick, float offsetZ, float opacity) {
        this.cells = cells;
        this.size = size;
        this.cellSize = cellSize;
        this.thickness = thickness;
        this.blocksPerTick = blocksPerTick;
        this.offsetZ = offsetZ;
        this.opacity = opacity;
        this.info = new MappableRingBuffer(() -> "LiteLights clouds (" + name + ") info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
    }

    /** Height of the layer's middle above the camera (negative when the camera is above it). */
    float middleAboveCamera() {
        return relativeBottom + thickness * 0.5f;
    }

    /** Before the frame's translucent pass: rebuild the face list if needed and write the offset. */
    void prepare(int color, CloudStatus status, float bottomY, int rangeChunks, Vec3 camera, long gameTime, float partialTicks) {
        int radiusCells = Math.min(Mth.ceil(rangeChunks * 16 / cellSize), LARGEST_RADIUS_CELLS);
        int facesSize = largestFaceBytes(radiusCells);
        if (faces == null || faces.size() != facesSize) {
            if (faces != null) {
                faces.close();
            }
            faces = new MappableRingBuffer(() -> "LiteLights cloud faces",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE, facesSize);
            needsRebuild = true;
        }

        relativeBottom = (float) (bottomY - camera.y);
        CameraSide side = relativeBottom + thickness < 0.0f ? CameraSide.ABOVE : (relativeBottom > 0.0f ? CameraSide.BELOW : CameraSide.INSIDE);

        // The wind moves the layer along X; the map repeats every size cells.
        // Cell sizes and speeds are chosen so a whole number of ticks carries the wind once across
        // the map, so the wrap below is seamless.
        double mapBlocks = size * (double) cellSize;
        long ticksPerMap = Math.round(mapBlocks / blocksPerTick);
        double drift = (gameTime % ticksPerMap + partialTicks) * blocksPerTick;
        double cloudX = camera.x + drift;
        double cloudZ = camera.z + offsetZ;
        cloudX -= Math.floor(cloudX / mapBlocks) * mapBlocks;
        cloudZ -= Math.floor(cloudZ / mapBlocks) * mapBlocks;
        int cellX = Mth.floor(cloudX / cellSize);
        int cellZ = Mth.floor(cloudZ / cellSize);
        float withinX = (float) (cloudX - cellX * cellSize);
        float withinZ = (float) (cloudZ - cellZ * cellSize);

        if (needsRebuild || cellX != builtCellX || cellZ != builtCellZ || side != builtSide || status != builtStatus) {
            needsRebuild = false;
            builtCellX = cellX;
            builtCellZ = cellZ;
            builtSide = side;
            builtStatus = status;
            faces.rotate();
            try (GpuBufferSlice.MappedView view = faces.currentBuffer().map(false, true)) {
                ByteBuffer data = view.data();
                buildFaces(data, side, cellX, cellZ, status == CloudStatus.FANCY, radiusCells);
                quadCount = data.position() / 3;
            }
        }

        if (quadCount > 0) {
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(6 * quadCount);
            // A fresh slot for every prepare, not every frame: the level can be drawn twice in one
            // frame (a screenshot, mods that render the world again), and the mapping is
            // unsynchronized. Rewriting the slot the previous draw still reads let its three
            // Improved Transparency stages see different wind offsets, so faces across the wind
            // failed the later stages' depth test and turned near black.
            info.rotate();
            try (GpuBufferSlice.MappedView view = info.currentBuffer().map(false, true)) {
                Std140Builder.intoBuffer(view.data())
                    .putVec4(ARGB.redFloat(color), ARGB.greenFloat(color), ARGB.blueFloat(color), ARGB.alphaFloat(color) * opacity)
                    .putVec3(-withinX, relativeBottom, -withinZ)
                    .putVec3(cellSize, status == CloudStatus.FANCY ? thickness : 0.0f, cellSize);
            }
        }
    }

    void render(CloudStatus status, RenderPass pass) {
        if (quadCount == 0 || faces == null) {
            return;
        }
        pass.pushDebugGroup(() -> "LiteLights clouds");
        RenderSystem.bindDefaultUniforms(pass);
        draw(pass, status == CloudStatus.FANCY ? RenderPipelines.CLOUDS : RenderPipelines.FLAT_CLOUDS);
        pass.popDebugGroup();
    }

    void renderOit(CloudStatus status, OitStage stage, GpuTextureView mainDepth, OitRenderPassProvider.Parameters parameters) {
        if (quadCount == 0 || faces == null) {
            return;
        }
        RenderPipeline pipeline = (status == CloudStatus.FANCY ? RenderPipelines.OIT_CLOUDS : RenderPipelines.OIT_FLAT_CLOUDS).getPipeline(stage);
        try (RenderPass pass = GuardedRenderPass.own(OitRenderPassProvider.createRenderPass(stage, () -> "LiteLights clouds", parameters))) {
            if (stage == OitStage.DEPTH_BOUNDS) {
                // As vanilla does: the cloud pass starts from the scene's depth.
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH_DURING_DEPTH_BOUNDS));
                pass.setUniform("InSampler", mainDepth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            draw(pass, pipeline);
        }
    }

    private void draw(RenderPass pass, RenderPipeline pipeline) {
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        pass.setUniform("DynamicTransforms", transforms);
        pass.setIndexBuffer(indices.getBuffer(), indices.type());
        pass.setVertexBuffer(0, null);
        pass.setUniform("CloudInfo", info.currentBuffer());
        pass.setUniform("CloudFaces", faces.currentBuffer());
        pass.drawIndexed(6 * quadCount, 1, 0, 0, 0);
    }

    @Override
    public void close() {
        info.close();
        if (faces != null) {
            faces.close();
        }
    }

    private static int largestFaceBytes(int radiusCells) {
        // At most half the cells in the square can hold cloud with four open sides, plus the
        // interior faces of the nine cells around the camera.
        int cellsInSquare = (radiusCells + 1) * 2 * (radiusCells + 1) * 2;
        int faces = cellsInSquare / 2 * 4 + 54;
        return faces * 3;
    }

    /**
     * Writes the visible faces, nearest cells first (rings of growing distance), so with depth
     * writes on the near faces hide the far ones and the layer doesn't show its insides.
     */
    private void buildFaces(ByteBuffer data, CameraSide side, int centreX, int centreZ, boolean fancy, int radiusCells) {
        int radiusSquared = radiusCells * radiusCells;
        for (int ring = 0; ring <= 2 * radiusCells; ring++) {
            for (int x = -ring; x <= ring; x++) {
                int z = ring - Math.abs(x);
                if (z > radiusCells || x * x + z * z > radiusSquared) {
                    continue;
                }
                if (z != 0) {
                    addCell(data, side, centreX, centreZ, x, -z, fancy);
                }
                addCell(data, side, centreX, centreZ, x, z, fancy);
            }
        }
    }

    private void addCell(ByteBuffer data, CameraSide side, int centreX, int centreZ, int x, int z, boolean fancy) {
        int flags = cells[Math.floorMod(centreX + x, size) + Math.floorMod(centreZ + z, size) * size];
        if ((flags & CloudShapes.FILLED) == 0) {
            return;
        }
        if (!fancy) {
            putFace(data, x, z, Direction.DOWN, FLAG_USE_TOP_COLOR);
            return;
        }
        if (side != CameraSide.BELOW) {
            putFace(data, x, z, Direction.UP, 0);
        }
        if (side != CameraSide.ABOVE) {
            putFace(data, x, z, Direction.DOWN, 0);
        }
        // Side faces only where the neighbour is empty and the face looks toward the camera.
        if ((flags & CloudShapes.NORTH_OPEN) != 0 && z > 0) {
            putFace(data, x, z, Direction.NORTH, 0);
        }
        if ((flags & CloudShapes.SOUTH_OPEN) != 0 && z < 0) {
            putFace(data, x, z, Direction.SOUTH, 0);
        }
        if ((flags & CloudShapes.WEST_OPEN) != 0 && x > 0) {
            putFace(data, x, z, Direction.WEST, 0);
        }
        if ((flags & CloudShapes.EAST_OPEN) != 0 && x < 0) {
            putFace(data, x, z, Direction.EAST, 0);
        }
        if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
            // Inside a cloud: draw its faces from the inside too.
            for (Direction direction : DIRECTIONS) {
                putFace(data, x, z, direction, FLAG_INSIDE_FACE);
            }
        }
    }

    private static void putFace(ByteBuffer data, int x, int z, Direction direction, int flags) {
        int packed = direction.get3DDataValue() | flags | (x & 1) << 7 | (z & 1) << 6;
        data.put((byte) (x >> 1)).put((byte) (z >> 1)).put((byte) packed);
    }
}
