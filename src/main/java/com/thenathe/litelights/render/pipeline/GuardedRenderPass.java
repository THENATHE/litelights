package com.thenathe.litelights.render.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/**
 * A render pass that is left clean however the drawing into it ends, so a LiteLights draw that
 * throws costs only its own feature (which {@code RenderFeatureStatus} switches off and retries),
 * never the frame.
 *
 * <p>The game's render pass can't recover by itself: closing it with a debug group still open marks
 * it closed, throws, and never tells the command encoder the pass has ended, so the next pass the
 * game opens fails with "Close the existing render pass before creating a new one!" and the game
 * crashes. A replayed vanilla draw that throws (a modded render type without Improved
 * Transparency pipelines, in 0.1.6) leaves exactly that behind: the feature phase pushes a debug
 * group, then the draw throws before popping it. This wrapper counts the groups pushed through it
 * and pops whatever is still open before closing, so with try-with-resources the real pass is
 * always ended properly before the failure reaches the feature's catch.
 *
 * <p>Passes LiteLights opens are owned ({@link #open}, {@link #own}) and closed here; a pass that
 * belongs to the game and that LiteLights only draws into is borrowed ({@link #borrow}), and its
 * close only pops what was pushed through the wrapper.
 */
public final class GuardedRenderPass implements RenderPass {
    private final RenderPass pass;
    private final boolean owned;
    private int openDebugGroups;
    private boolean closed;

    GuardedRenderPass(RenderPass pass, boolean owned) {
        this.pass = pass;
        this.owned = owned;
    }

    public static GuardedRenderPass open(RenderPassDescriptor descriptor) {
        return own(RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor));
    }

    public static GuardedRenderPass open(Supplier<String> label, GpuTextureView colorTexture, Optional<Vector4fc> clearColor) {
        return own(RenderSystem.getDevice().createCommandEncoder().createRenderPass(label, colorTexture, clearColor));
    }

    public static GuardedRenderPass open(Supplier<String> label, GpuTextureView colorTexture, Optional<Vector4fc> clearColor,
                                         @Nullable GpuTextureView depthTexture, OptionalDouble clearDepth) {
        return own(RenderSystem.getDevice().createCommandEncoder().createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth));
    }

    /** Takes charge of a pass LiteLights opened some other way (vanilla's OIT pass provider). */
    public static GuardedRenderPass own(RenderPass pass) {
        return new GuardedRenderPass(pass, true);
    }

    /** Draws into the game's own pass; closing pops only the debug groups pushed through this. */
    public static GuardedRenderPass borrow(RenderPass pass) {
        return new GuardedRenderPass(pass, false);
    }

    /** Debug groups pushed through this pass and not yet popped. */
    public int openDebugGroups() {
        return openDebugGroups;
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        pass.pushDebugGroup(label);
        openDebugGroups++;
    }

    @Override
    public void popDebugGroup() {
        pass.popDebugGroup();
        openDebugGroups--;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            while (openDebugGroups > 0) {
                openDebugGroups--;
                pass.popDebugGroup();
            }
        } finally {
            if (owned) {
                pass.close();
            }
        }
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        pass.writeTimestamp(pool, index);
    }

    @Override
    public void setPipeline(CompiledRenderPipeline pipeline) {
        pass.setPipeline(pipeline);
    }

    @Override
    public void setUniform(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
        pass.setUniform(name, textureView, sampler);
    }

    @Override
    public void setUniform(String name, GpuBuffer value) {
        pass.setUniform(name, value);
    }

    @Override
    public void setUniform(String name, GpuBufferSlice value) {
        pass.setUniform(name, value);
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        pass.pushConstants(value);
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        pass.enableScissor(x, y, width, height);
    }

    @Override
    public void disableScissor() {
        pass.disableScissor();
    }

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        pass.setVertexBuffer(slot, vertexBuffer);
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
        pass.setIndexBuffer(indexBuffer, indexType);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        pass.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        pass.multiDrawIndexed(drawParameters, instanceCount, firstInstance, drawCount);
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        pass.multiDrawIndexed(firstIndexOffsets, indexCounts, vertexOffsets, drawCount);
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        pass.drawIndexedIndirect(commands, drawCount);
    }

    @Override
    public <T> void drawMultipleIndexed(
        Collection<RenderPass.Draw<T>> draws,
        @Nullable GpuBuffer defaultIndexBuffer,
        @Nullable IndexType defaultIndexType,
        Collection<String> dynamicUniforms,
        T uniformArgument
    ) {
        pass.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        pass.multiDraw(drawParameters, instanceCount, firstInstance, drawCount);
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        pass.multiDraw(firstVertices, vertexCounts, drawCount);
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        pass.drawIndirect(commands, drawCount);
    }
}
