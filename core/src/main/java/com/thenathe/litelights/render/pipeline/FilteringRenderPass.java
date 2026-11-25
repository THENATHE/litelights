package com.thenathe.litelights.render.pipeline;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/**
 * A render pass that replays the game's own draws into another pass, swapping each pipeline for one
 * of LiteLights' and dropping the draws whose pipeline has no replacement. Subclasses decide the
 * replacement ({@link #replacementFor}); everything else passes straight through.
 *
 * <p>The target is never the screen (a shadow map, a half-resolution mask), so the screen's
 * scissor rectangles are ignored, and the real pass is closed by whoever opened it.
 */
public abstract class FilteringRenderPass implements RenderPass {
    private @Nullable RenderPass target;
    private boolean drawing;
    private boolean drewAnything;

    /** Starts filtering into a pass. */
    protected void begin(RenderPass pass) {
        target = pass;
        drawing = false;
        drewAnything = false;
    }

    /** Whether any draw went through since {@link #begin}. */
    public boolean drewAnything() {
        return drewAnything;
    }

    /** The pipeline to draw with instead of {@code pipeline}, or null to drop its draws. */
    protected abstract @Nullable CompiledRenderPipeline replacementFor(CompiledRenderPipeline pipeline);

    protected final RenderPass target() {
        if (target == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " used before begin()");
        }
        return target;
    }

    private boolean drawThrough() {
        if (drawing) {
            drewAnything = true;
        }
        return drawing;
    }

    @Override
    public final void setPipeline(CompiledRenderPipeline pipeline) {
        CompiledRenderPipeline replacement = replacementFor(pipeline);
        drawing = replacement != null;
        if (replacement != null) {
            target().setPipeline(replacement);
        }
    }

    @Override
    public void setUniform(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
        target().setUniform(name, textureView, sampler);
    }

    @Override
    public void setUniform(String name, GpuBuffer value) {
        target().setUniform(name, value);
    }

    @Override
    public void setUniform(String name, GpuBufferSlice value) {
        target().setUniform(name, value);
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        if (drawing) {
            target().pushConstants(value);
        }
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
    }

    @Override
    public void disableScissor() {
    }

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        target().setVertexBuffer(slot, vertexBuffer);
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
        target().setIndexBuffer(indexBuffer, indexType);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        if (drawThrough()) {
            target().drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        if (drawThrough()) {
            target().multiDrawIndexed(drawParameters, instanceCount, firstInstance, drawCount);
        }
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        if (drawThrough()) {
            target().multiDrawIndexed(firstIndexOffsets, indexCounts, vertexOffsets, drawCount);
        }
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        if (drawThrough()) {
            target().drawIndexedIndirect(commands, drawCount);
        }
    }

    @Override
    public <T> void drawMultipleIndexed(
        Collection<RenderPass.Draw<T>> draws,
        @Nullable GpuBuffer defaultIndexBuffer,
        @Nullable IndexType defaultIndexType,
        Collection<String> dynamicUniforms,
        T uniformArgument
    ) {
        if (drawThrough()) {
            target().drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (drawThrough()) {
            target().draw(vertexCount, instanceCount, firstVertex, firstInstance);
        }
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        if (drawThrough()) {
            target().multiDraw(drawParameters, instanceCount, firstInstance, drawCount);
        }
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        if (drawThrough()) {
            target().multiDraw(firstVertices, vertexCounts, drawCount);
        }
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        if (drawThrough()) {
            target().drawIndirect(commands, drawCount);
        }
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        target().pushDebugGroup(label);
    }

    @Override
    public void popDebugGroup() {
        target().popDebugGroup();
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        target().writeTimestamp(pool, index);
    }

    @Override
    public void close() {
    }
}
