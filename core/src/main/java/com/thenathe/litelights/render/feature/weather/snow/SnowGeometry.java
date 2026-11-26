package com.thenathe.litelights.render.feature.weather.snow;

import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The visual snow's geometry, added to a block's quads as its chunk mesh is built (VisualSnowModel):
 * fake snow at a block's base (the game's own snow layer model, so it is vanilla's snow to the
 * pixel), fake snow on the upward surfaces of its shape (boxes with the snow layer's texture, as
 * deep as the layers, their sides down to the shape below), and the snowy overlay: snow weeping a
 * little way down the faces under any snow's edge, ragged, in short drips (SnowDrips). Everything
 * is ordinary solid terrain, so it is lit, shadowed, fogged and shaded like the land around it, on
 * Sodium and on the vanilla renderer alike (both mesh through Fabric's renderer API).
 */
final class SnowGeometry {
    private static final float SIXTEENTH = 1.0f / 16.0f;
    /** How far the overlay's drips stand off the face they lie on, so they never fight it for depth. */
    private static final float DRIP_LIFT = 0.0025f;
    private static final int WHITE = 0xFFFFFFFF;

    private SnowGeometry() {
    }

    static void emit(SnowState snow, SnowHosts.Host host, QuadEmitter emitter, BlockAndTintGetter region, BlockPos pos,
                     BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        // Grass, flowers and the like are drawn shifted a little off their block by the game; the
        // renderer shifts everything this block emits alike, so the snow is shifted back onto the block.
        if (state.hasOffsetFunction()) {
            Vec3 offset = state.getOffset(pos);
            if (!offset.equals(Vec3.ZERO)) {
                float x = (float) -offset.x;
                float y = (float) -offset.y;
                float z = (float) -offset.z;
                emitter.pushTransform(quad -> {
                    for (int vertex = 0; vertex < 4; vertex++) {
                        quad.pos(vertex, quad.x(vertex) + x, quad.y(vertex) + y, quad.z(vertex) + z);
                    }
                    return true;
                });
                try {
                    emitUnshifted(snow, host, emitter, region, pos, state, random, cullTest);
                } finally {
                    emitter.popTransform();
                }
                return;
            }
        }
        emitUnshifted(snow, host, emitter, region, pos, state, random, cullTest);
    }

    private static void emitUnshifted(SnowState snow, SnowHosts.Host host, QuadEmitter emitter, BlockAndTintGetter region,
                                      BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        if (host.realSnow()) {
            if (snow.drips()) {
                dripsBelow(snow, emitter, region, pos, state.getValue(SnowLayerBlock.LAYERS));
            }
            return;
        }
        Holder<Biome> biome = region.getBiomeFabric(pos);
        if (biome == null) {
            return;
        }
        if (host.base()) {
            int real = RealSnow.layersAt(region, pos, state, snow.betterSnow());
            if (real > 0) {
                // LambdaBetterGrass draws this snow; the overlay still weeps from it.
                if (snow.drips() && real < SnowLayerRule.MOST_LAYERS) {
                    dripsBelow(snow, emitter, region, pos, real);
                }
            } else if (snow.blocks()) {
                int layers = FakeSnow.atBase(snow, region, biome, pos, state, true);
                if (layers > 0) {
                    snowLayer(emitter, region, pos, layers, random, cullTest);
                }
            }
        }
        if (snow.blocks() && !host.tops().isEmpty()) {
            int layers = FakeSnow.onTop(snow, region, biome, pos, true);
            if (layers > 0) {
                tops(snow, emitter, region, pos, host.tops(), layers, snow.drips() && SnowHosts.takesDrips(state));
            }
        }
    }

    /**
     * The game's own snow layer of that depth, emitted from its model: its model is wrapped too, so
     * the overlay weeps from it as from a real snow layer.
     */
    private static void snowLayer(QuadEmitter emitter, BlockAndTintGetter region, BlockPos pos, int layers,
                                  RandomSource random, Predicate<Direction> cullTest) {
        BlockState snowState = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(snowState);
        model.emitQuads(emitter, region, pos, snowState, random, cullTest);
    }

    /**
     * Snow on each upward surface of the shape, {@code layers} eighths of a block deep, with its sides,
     * and with {@code drips} the overlay down the shape's own faces.
     */
    private static void tops(SnowState snow, QuadEmitter emitter, BlockAndTintGetter region, BlockPos pos,
                             SnowSurfaces surfaces, int layers, boolean drips) {
        TextureAtlasSprite sprite = snow.sprite();
        int depth = layers * 2;
        int aboveLight = light(region, pos.above());
        for (SnowSurfaces.Top top : surfaces.tops()) {
            int base = top.top();
            int roof = base + depth;
            boolean outside = roof > SnowSurfaces.GRID;
            int topLight = outside ? aboveLight : -1;
            topQuad(emitter, sprite, top.minX(), top.minZ(), top.maxX(), top.maxZ(), roof, topLight);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                edge(snow, emitter, region, pos, surfaces, top, side, base, roof, topLight, drips);
            }
        }
    }

    /**
     * One side of a surface's snow: its rim, from the surface (or the shape beside, if higher) up to
     * the snow's top, run by run of equal neighbour height; and under it, with the overlay on, drips
     * down the shape's own face where the shape steps down beside the surface.
     */
    private static void edge(SnowState snow, QuadEmitter emitter, BlockAndTintGetter region, BlockPos pos,
                             SnowSurfaces surfaces, SnowSurfaces.Top top, Direction side, int base, int roof, int topLight,
                             boolean drips) {
        boolean alongX = side.getAxis() == Direction.Axis.Z;
        int from = alongX ? top.minX() : top.minZ();
        int to = alongX ? top.maxX() : top.maxZ();
        int plane = switch (side) {
            case NORTH -> top.minZ();
            case SOUTH -> top.maxZ();
            case WEST -> top.minX();
            default -> top.maxX();
        };
        boolean atBlockSide = plane == 0 || plane == SnowSurfaces.GRID;
        if (atBlockSide && region.getBlockState(pos.relative(side)).isSolidRender()) {
            return;
        }
        int outsideLight = atBlockSide ? light(region, pos.relative(side)) : -1;
        int start = from;
        while (start < to) {
            int beside = besideHeight(surfaces, side, plane, start);
            int end = start + 1;
            while (end < to && besideHeight(surfaces, side, plane, end) == beside) {
                end++;
            }
            if (beside < roof) {
                int light = roof > SnowSurfaces.GRID ? topLight : outsideLight;
                sideQuad(emitter, snow.sprite(), side, plane, start, end, Math.max(base, beside), roof, light, roof - base);
            }
            if (drips && beside < base) {
                SnowDrips.along(emitter, snow.sprite(), side, plane, start, end, base, beside, roof - base,
                    pos.getX(), pos.getY(), pos.getZ(), atBlockSide ? outsideLight : -1);
            }
            start = end;
        }
    }

    /** The shape's height just outside an edge, at one sixteenth along it. */
    private static int besideHeight(SnowSurfaces surfaces, Direction side, int plane, int along) {
        return switch (side) {
            case NORTH -> surfaces.heightAt(along, plane - 1);
            case SOUTH -> surfaces.heightAt(along, plane);
            case WEST -> surfaces.heightAt(plane - 1, along);
            default -> surfaces.heightAt(plane, along);
        };
    }

    /**
     * Drips under a snow layer of {@code layers} at {@code pos}: down the four sides of the block it
     * lies on, where that block's face shows to open air, unless that block keeps its own look for snow
     * ({@link SnowHosts#takesDrips}).
     */
    static void dripsBelow(SnowState snow, QuadEmitter emitter, BlockAndTintGetter region, BlockPos pos, int layers) {
        BlockPos below = pos.below();
        BlockState ground = region.getBlockState(below);
        if (!SnowHosts.takesDrips(ground)) {
            return;
        }
        BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!ground.isFaceSturdy(region, below, side)) {
                continue;
            }
            BlockState next = region.getBlockState(beside.setWithOffset(below, side));
            if (next.isSolidRender() || next.isFaceSturdy(region, beside, side.getOpposite())) {
                continue;
            }
            int plane = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? SnowSurfaces.GRID : 0;
            SnowDrips.along(emitter, snow.sprite(), side, plane, 0, SnowSurfaces.GRID, 0, -SnowSurfaces.GRID, layers * 2,
                pos.getX(), pos.getY(), pos.getZ(), light(region, beside));
        }
    }

    /** The light in a space, packed as a lightmap coordinate. */
    private static int light(BlockAndTintGetter region, BlockPos pos) {
        return LightCoordsUtil.pack(region.getBrightness(LightLayer.BLOCK, pos), region.getBrightness(LightLayer.SKY, pos));
    }

    /**
     * The top of a surface's snow, in sixteenths. {@code light} is the least light it takes (the light
     * of the space it is really in, for snow above the block's own space), or -1 to take the block's.
     */
    private static void topQuad(QuadEmitter emitter, TextureAtlasSprite sprite, int minX, int minZ, int maxX, int maxZ,
                                int y, int light) {
        float x0 = minX * SIXTEENTH;
        float x1 = maxX * SIXTEENTH;
        float z0 = minZ * SIXTEENTH;
        float z1 = maxZ * SIXTEENTH;
        float height = y * SIXTEENTH;
        emitter.pos(0, x0, height, z0).uv(0, u(sprite, x0), v(sprite, z0));
        emitter.pos(1, x0, height, z1).uv(1, u(sprite, x0), v(sprite, z1));
        emitter.pos(2, x1, height, z1).uv(2, u(sprite, x1), v(sprite, z1));
        emitter.pos(3, x1, height, z0).uv(3, u(sprite, x1), v(sprite, z0));
        finish(emitter, Direction.UP, light);
    }

    /**
     * A side face, in sixteenths: on the plane {@code plane} across the side's axis, from
     * {@code from} to {@code to} along it, from {@code bottom} up to {@code top}. The texture's lowest
     * {@code textureRows} rows, as a snow layer's side shows them.
     */
    static void sideQuad(QuadEmitter emitter, TextureAtlasSprite sprite, Direction side, int plane, int from, int to,
                         int bottom, int top, int light, int textureRows) {
        sideQuad(emitter, sprite, side, plane * SIXTEENTH, from * SIXTEENTH, to * SIXTEENTH, bottom * SIXTEENTH,
            top * SIXTEENTH, 1.0f - textureRows * SIXTEENTH, 1.0f, light, false);
    }

    /**
     * A side face in blocks, facing {@code side}, with the texture's rows from {@code vTop} (at the
     * top edge) to {@code vBottom}.
     */
    static void sideQuad(QuadEmitter emitter, TextureAtlasSprite sprite, Direction side, float plane, float from, float to,
                         float bottom, float top, float vTop, float vBottom, int light, boolean lifted) {
        float offset = lifted ? (side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? DRIP_LIFT : -DRIP_LIFT) : 0.0f;
        float at = plane + offset;
        float u0 = u(sprite, from);
        float u1 = u(sprite, to);
        float vt = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * vTop;
        float vb = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * vBottom;
        switch (side) {
            case NORTH -> {
                emitter.pos(0, to, top, at).uv(0, u1, vt);
                emitter.pos(1, to, bottom, at).uv(1, u1, vb);
                emitter.pos(2, from, bottom, at).uv(2, u0, vb);
                emitter.pos(3, from, top, at).uv(3, u0, vt);
            }
            case SOUTH -> {
                emitter.pos(0, from, top, at).uv(0, u0, vt);
                emitter.pos(1, from, bottom, at).uv(1, u0, vb);
                emitter.pos(2, to, bottom, at).uv(2, u1, vb);
                emitter.pos(3, to, top, at).uv(3, u1, vt);
            }
            case WEST -> {
                emitter.pos(0, at, top, from).uv(0, u0, vt);
                emitter.pos(1, at, bottom, from).uv(1, u0, vb);
                emitter.pos(2, at, bottom, to).uv(2, u1, vb);
                emitter.pos(3, at, top, to).uv(3, u1, vt);
            }
            default -> {
                emitter.pos(0, at, top, to).uv(0, u1, vt);
                emitter.pos(1, at, bottom, to).uv(1, u1, vb);
                emitter.pos(2, at, bottom, from).uv(2, u0, vb);
                emitter.pos(3, at, top, from).uv(3, u0, vt);
            }
        }
        finish(emitter, side, light);
    }

    /**
     * Ends a quad: white, untinted, solid. A quad given a light of its own lies outside the block's
     * space, where the block's ambient occlusion would darken it wrongly, so it takes none.
     */
    private static void finish(QuadEmitter emitter, Direction face, int light) {
        for (int vertex = 0; vertex < 4; vertex++) {
            emitter.color(vertex, WHITE);
            if (light >= 0) {
                emitter.lightmap(vertex, light);
            }
        }
        emitter.nominalFace(face);
        emitter.cullFace(null);
        emitter.tintIndex(-1);
        emitter.chunkLayer(ChunkSectionLayer.SOLID);
        emitter.ambientOcclusion(light >= 0 ? TriState.FALSE : TriState.DEFAULT);
        emitter.emit();
    }

    private static float u(TextureAtlasSprite sprite, float along) {
        return sprite.getU0() + (sprite.getU1() - sprite.getU0()) * Math.clamp(along, 0.0f, 1.0f);
    }

    private static float v(TextureAtlasSprite sprite, float along) {
        return sprite.getV0() + (sprite.getV1() - sprite.getV0()) * Math.clamp(along, 0.0f, 1.0f);
    }
}
