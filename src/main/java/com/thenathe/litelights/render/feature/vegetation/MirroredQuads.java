package com.thenathe.litelights.render.feature.vegetation;

import com.thenathe.litelights.render.compat.SodiumSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Faces that map their texture mirrored left to right, such as the back faces of a crop's planes.
 *
 * <p>Vanilla's terrain shader tells a vertex's corner of its sprite from the vertex's place in the
 * quad (see {@code lite_vegetation_corner} in hooks/vegetation.glsl). On a mirrored face that gives
 * the wrong side, and the sway hook then reads the material of the sprite next to it in the atlas.
 * So plant models with mirrored faces are wrapped (Fabric's model loading and renderer APIs) to
 * write those quads' vertex colour alpha as 254 instead of 255, which the shader reads back to swap
 * the side. Cut-out plants never use that alpha (a texel is kept from 0.1 up), so nothing else
 * changes. Not done with Sodium installed: Sodium stores the exact corner with every vertex.
 */
public final class MirroredQuads {
    /** The vertex colour alpha a mirrored quad is drawn with. */
    public static final int MARK_ALPHA = 254;

    private MirroredQuads() {
    }

    /** Wraps every plant model with mirrored faces as models are baked, unless Sodium builds the chunks. */
    public static void register() {
        ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake().register((model, bake) ->
            !SodiumSupport.isLoaded() && hasMirroredUprightQuads(model) ? new MarkingModel(model) : model));
    }

    /**
     * True when the quad's top edge runs right to left in the texture. The baker starts every
     * unrotated face at its top-left corner and ends at the top-right one, so the first and last
     * vertices hold the top edge; on a mirrored face the first one has the larger u.
     */
    static boolean isMirrored(float firstU, float lastU) {
        return firstU > lastU;
    }

    /** The colour with its alpha lowered to the mark, if it was fully opaque. */
    static int marked(int argb) {
        return (argb >>> 24) == 0xFF ? (argb & 0x00FFFFFF) | (MARK_ALPHA << 24) : argb;
    }

    private static boolean hasMirroredUprightQuads(BlockStateModel model) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        for (BlockStateModelPart part : parts) {
            if (anyMirroredUpright(part.getQuads(null))) {
                return true;
            }
            for (Direction direction : Direction.values()) {
                if (anyMirroredUpright(part.getQuads(direction))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyMirroredUpright(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            if (VegetationMaterials.isUpright(quad) && isMirrored(UVPair.unpackU(quad.packedUV(0)), UVPair.unpackU(quad.packedUV(3)))) {
                return true;
            }
        }
        return false;
    }

    /** Marks a quad on its way into the chunk mesh if its texture is mirrored. Never drops one. */
    private static boolean markIfMirrored(MutableQuadView quad) {
        if (isMirrored(quad.u(0), quad.u(3))) {
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.color(vertex, marked(quad.color(vertex)));
            }
        }
        return true;
    }

    /** A plant model that marks its mirrored quads as it emits them. */
    private static final class MarkingModel extends WrapperBlockStateModel {
        MarkingModel(BlockStateModel wrapped) {
            super(wrapped);
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                              Predicate<Direction> cullTest) {
            emitter.pushTransform(MirroredQuads::markIfMirrored);
            try {
                super.emitQuads(emitter, level, pos, state, random, cullTest);
            } finally {
                emitter.popTransform();
            }
        }
    }
}
