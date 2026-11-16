package com.thenathe.litelights.render.shadow;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.mixin.render.PreparedFrameAccessor;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.pipeline.UniformBinder;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

/**
 * The small shadow map around the player for entities (mobs, the player's own body, item frames,
 * block entities), redrawn every frame. Shaders take the darker of this map and the cached sun map,
 * so the cached map never has to include anything that moves.
 *
 * <p>Entities are not drawn again from scratch: the frame's prepared entity draws are replayed
 * through a {@link ShadowCasterPass}, with the projection swapped for the light's. The entity
 * shaders multiply by the view rotation, so the projection given to them is the light's matrix
 * times the inverse view rotation, which cancels it.
 */
public final class MobShadowMap implements AutoCloseable {
    /** Blocks from the camera that entity shadows reach. */
    public static final double RADIUS = 24.0;
    private static final float DEPTH_RADIUS = 192.0f;

    private final int size;
    private final GpuTexture texture;
    private final GpuTextureView view;
    private final GpuBuffer projectionBuffer;
    private final Matrix4f inverseViewRotation = new Matrix4f();
    private final LightSpace space = new LightSpace();
    private final Matrix4f cameraRelative = new Matrix4f();
    private final Matrix4f entityProjection = new Matrix4f();

    public MobShadowMap(int size) {
        this.size = size;
        var device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;
        texture = device.createTexture("LiteLights mob shadow", usage, GpuFormat.D32_FLOAT, size, size, 1, 1);
        view = device.createTextureView(texture);
        projectionBuffer = device.createBuffer(() -> "LiteLights mob shadow projection",
            GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, RenderSystem.PROJECTION_MATRIX_UBO_SIZE);
    }

    public int size() {
        return size;
    }

    public double blocksPerTexel() {
        return RADIUS * 2.0 / size;
    }

    public GpuTextureView view() {
        return view;
    }

    /** The camera-relative-to-clip matrix of the last draw, for the LiteFrame block. */
    public Matrix4f cameraRelativeMatrix() {
        return cameraRelative;
    }

    /**
     * Places the map around the camera. Done before the frame's uniforms are written, so the
     * shaders and the replayed draws agree on the matrix.
     */
    public void place(Vector3f light, Vector3d camera, boolean zeroToOneDepth) {
        space.set(light, camera, RADIUS, size, DEPTH_RADIUS, zeroToOneDepth);
        space.cameraRelative(camera.x, camera.y, camera.z, cameraRelative);
    }

    /**
     * Replays the frame's solid and translucent entity draws into the map.
     *
     * <p>The translucent draws are replayed without an OIT stage even with Improved Transparency on:
     * each draw then sets its render type's own pipeline, which every render type has, instead of the
     * OIT stage's pipeline, which only render types built for Improved Transparency have. The map is
     * depth only, so nothing is lost: the caster variants drop the OIT defines either way. Asking for
     * a stage made a modded render type without OIT pipelines (Wakes' wakes) throw inside the pass.
     *
     * @param viewRotation the camera rotation the prepared draws were recorded with
     */
    public void draw(FeatureRenderDispatcher.PreparedFrame frame, Matrix4fc viewRotation, UniformBinder binder) {
        PreparedFrameAccessor accessor = (PreparedFrameAccessor) (Object) frame;
        FeatureFrameContext context = accessor.litelights$context();
        SubmitNodeStorage storage = accessor.litelights$submitNodeStorage();
        if (context == null || storage == null) {
            return;
        }
        entityProjection.set(cameraRelative).mul(inverseViewRotation.set(viewRotation).invert());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, RenderSystem.PROJECTION_MATRIX_UBO_SIZE).putMat4f(entityProjection).get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(projectionBuffer.slice(), data);
        }

        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "LiteLights mob shadow")
            .withDepthAttachment(view, OptionalDouble.of(1.0))
            .build();
        try (RenderPass pass = GuardedRenderPass.open(descriptor)) {
            RenderSystem.bindDefaultUniforms(pass);
            binder.bindForShadowCasters(pass);
            ShadowCasterPass casterPass = new ShadowCasterPass(pass);
            casterPass.lockUniform("Projection", projectionBuffer.slice());
            // The same phase object can sit in several fields (with Improved Transparency every
            // translucent field is the one OIT phase), so each is replayed once.
            Set<FeatureRenderPhase<?>> replayed = Collections.newSetFromMap(new IdentityHashMap<>());
            for (SubmitNodeCollection collection : storage.getSubmitsPerOrder().values()) {
                replay(accessor, context, collection.solid, casterPass, replayed);
                replay(accessor, context, collection.translucentModels, casterPass, replayed);
                replay(accessor, context, collection.translucentBlocksAndItems, casterPass, replayed);
            }
        }
    }

    private static void replay(PreparedFrameAccessor accessor, FeatureFrameContext context, FeatureRenderPhase<?> phase,
                               ShadowCasterPass pass, Set<FeatureRenderPhase<?>> replayed) {
        if (replayed.add(phase)) {
            accessor.litelights$executePhase(phase, context, null, pass);
        }
    }

    @Override
    public void close() {
        view.close();
        texture.close();
        projectionBuffer.close();
    }
}
