package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * What each block state is to far terrain, worked out once per state from the game's own data:
 * <ul>
 *   <li><b>Kind</b>, from its shape: a block whose shape fills at least half a block's height and
 *   three quarters of its footprint, and that can be stood on, is a solid voxel (full blocks,
 *   slabs, stairs, chests); leaves and lava are solid too; water is water; plants, torches,
 *   fences, panes and the like are air. Coarse levels go by the kind alone.</li>
 *   <li><b>Shape</b> at full detail ({@link FarShape}): a whole voxel; the boxes of its shape
 *   (slabs, stairs, fences, panes, torches); crossed cards for a model drawn as crossed planes
 *   (plants); or nothing.</li>
 *   <li><b>Cover</b>: a thin layer over a whole block's footprint (snow layers, carpets, moss
 *   carpets) is stored in place of the block under it, so a snowy field reads white from afar.</li>
 *   <li><b>Faces</b>: for each side, the sprite of the model's largest quad on that side, and its
 *   tint index; for cards, the largest crossed quad's; the game's own block colours give the tint
 *   for the column's biome.</li>
 * </ul>
 * Safe on worker threads: models and shapes are read only, colours through {@link BiomeTint}.
 */
public final class BlockLooks implements FaceLooks {
    private static final Direction[] FACES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int UNTINTED = -1;
    private static final int WATER_TINT = -2;
    private static final Identifier WATER_SPRITE = Identifier.withDefaultNamespace("block/water_still");
    private static final Identifier LAVA_SPRITE = Identifier.withDefaultNamespace("block/lava_still");
    /** A quad whose normal is this close to an axis lies on the block's axes; a plant's crossed planes don't. */
    private static final float AXIS_ALIGNED = 0.98f;

    private record Look(int kind, boolean cover, FarShape shape, int[] appearances, int[] tintIndices) {
    }

    private final BlockPalette palette;
    private final FarAppearances appearances;
    private final AtomicReferenceArray<Look> looks = new AtomicReferenceArray<>(1 << 20);
    private final Map<Long, Integer> tints = new ConcurrentHashMap<>();
    private final BlockColors colors = Minecraft.getInstance().getBlockColors();

    public BlockLooks(BlockPalette palette, FarAppearances appearances) {
        this.palette = palette;
        this.appearances = appearances;
    }

    /** Forgets every look, after the models and atlas were made again; each is worked out again when next asked. */
    public void reset() {
        for (int i = 0; i < looks.length(); i++) {
            if (looks.get(i) != null) {
                looks.set(i, null);
            }
        }
        tints.clear();
    }

    @Override
    public int kind(int state) {
        return state == 0 ? AIR : look(state).kind;
    }

    /** A thin layer the store folds onto the block under it ({@link BlockLooks}). */
    public boolean cover(int state) {
        return state != 0 && look(state).cover;
    }

    @Override
    public int lightEmission(int state) {
        return state == 0 ? 0 : palette.stateOf(state).getLightEmission();
    }

    @Override
    public int lightColour(int state) {
        return lightColourOf(palette.stateOf(state));
    }

    /**
     * A light's colour far away: soul fire's blue, fire and lava's orange, else the block's map
     * colour warmed halfway toward torch light (lanterns, glowstone, sea lanterns); torch light
     * itself for blocks with no map colour (torches).
     */
    static int lightColourOf(BlockState state) {
        String name = state.getBlock().getDescriptionId();
        if (name.contains("soul")) {
            return 0x7AD8FF;
        }
        if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || name.contains("magma") || name.contains("campfire")) {
            return 0xFF8A33;
        }
        int map = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        if (map == 0) {
            return FarMesher.WARM_LIGHT;
        }
        int warm = FarMesher.WARM_LIGHT;
        int red = (((map >> 16) & 255) + ((warm >> 16) & 255)) / 2;
        int green = (((map >> 8) & 255) + ((warm >> 8) & 255)) / 2;
        int blue = ((map & 255) + (warm & 255)) / 2;
        return red << 16 | green << 8 | blue;
    }

    @Override
    public FarShape shape(int state) {
        return state == 0 ? FarShape.EMPTY : look(state).shape;
    }

    @Override
    public int appearance(int state, int face) {
        return look(state).appearances[face];
    }

    @Override
    public int tint(int state, int face, int biome) {
        Look look = look(state);
        int index = look.tintIndices[face];
        if (index == UNTINTED) {
            return 0xFFFFFF;
        }
        long key = state | (long) biome << 20 | (long) face << 36;
        Integer known = tints.get(key);
        if (known != null) {
            return known;
        }
        int tint = index == WATER_TINT ? palette.biomeOf(biome).getWaterColor() : blockTint(palette.stateOf(state), index, biome);
        tint &= 0xFFFFFF;
        tints.put(key, tint);
        return tint;
    }

    private int blockTint(BlockState state, int index, int biome) {
        try {
            BlockTintSource source = colors.getTintSource(state, index);
            return source == null ? 0xFFFFFF : source.colorInWorld(state, new BiomeTint(palette.biomeOf(biome)), BlockPos.ZERO);
        } catch (RuntimeException unusual) {
            return 0xFFFFFF;
        }
    }

    private Look look(int state) {
        Look look = looks.get(state);
        if (look == null) {
            look = make(palette.stateOf(state));
            looks.compareAndSet(state, null, look);
        }
        return look;
    }

    private Look make(BlockState state) {
        int[] faceAppearances = new int[7];
        int[] tintIndices = new int[7];
        int kind = kindOf(state);
        boolean cover = isCover(state);
        if (cover) {
            kind = SOLID;
        }
        FluidState fluid = state.getFluidState();
        if (kind == WATER || (!solidShape(state) && fluid.is(FluidTags.LAVA))) {
            boolean water = kind == WATER;
            int appearance = appearances.idOf(water ? WATER_SPRITE : LAVA_SPRITE, water ? FarAppearances.FLAG_WATER : 0);
            Arrays.fill(faceAppearances, appearance);
            Arrays.fill(tintIndices, water ? WATER_TINT : UNTINTED);
            return new Look(kind, false, water ? FarShape.EMPTY : FarShape.WHOLE, faceAppearances, tintIndices);
        }
        List<BakedQuad> quads = modelQuads(state);
        FarShape shape = cover ? FarShape.WHOLE : shapeOf(state, kind, quads);
        if (kind == AIR && shape.type() == FarShape.NONE) {
            return new Look(AIR, false, FarShape.EMPTY, faceAppearances, tintIndices);
        }
        fillFaces(state, quads, faceAppearances, tintIndices);
        return new Look(kind, cover, shape, faceAppearances, tintIndices);
    }

    /** Every quad of the block's model (seed 42, as for the faces). */
    private static List<BakedQuad> modelQuads(BlockState state) {
        BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        List<BlockStateModelPart> parts = new ArrayList<>();
        models.get(state).collectParts(RandomSource.create(42L), parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (Direction direction : FACES) {
                quads.addAll(part.getQuads(direction));
            }
            quads.addAll(part.getQuads(null));
        }
        return quads;
    }

    /** The block's shape at full detail, for a block that isn't water or lava. */
    private static FarShape shapeOf(BlockState state, int kind, List<BakedQuad> quads) {
        if (state.is(BlockTags.LEAVES)) {
            return FarShape.WHOLE;
        }
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return kind == SOLID ? FarShape.WHOLE : FarShape.EMPTY;
        }
        if (crossedPlanes(quads)) {
            return kind == SOLID ? FarShape.WHOLE : FarShape.CROSS;
        }
        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty() || Block.isShapeFullBlock(shape)) {
            return kind == SOLID ? FarShape.WHOLE : FarShape.EMPTY;
        }
        List<AABB> parts = shape.toAabbs();
        int[] boxes = new int[parts.size() * 6];
        int count = 0;
        for (AABB part : parts) {
            int x0 = sixteenths(part.minX);
            int y0 = sixteenths(part.minY);
            int z0 = sixteenths(part.minZ);
            int x1 = sixteenths(part.maxX);
            int y1 = sixteenths(part.maxY);
            int z1 = sixteenths(part.maxZ);
            if (x1 > x0 && y1 > y0 && z1 > z0) {
                boxes[count++] = x0;
                boxes[count++] = y0;
                boxes[count++] = z0;
                boxes[count++] = x1;
                boxes[count++] = y1;
                boxes[count++] = z1;
            }
        }
        FarShape result = FarShape.ofBoxes(Arrays.copyOf(boxes, count));
        // A solid block always draws something, as it stands for its ground at coarser levels.
        return kind == SOLID && result.type() == FarShape.NONE ? FarShape.WHOLE : result;
    }

    private static int sixteenths(double coordinate) {
        return Math.clamp(Math.round(coordinate * 16.0), 0, 16);
    }

    /** Whether most of the model's area is in quads off the block's axes, as a plant's crossed planes are. */
    static boolean crossedPlanes(List<BakedQuad> quads) {
        float crossed = 0.0f;
        float straight = 0.0f;
        for (BakedQuad quad : quads) {
            Vector3f normal = normal(quad);
            float area = normal.length();
            if (area < 1.0e-6f) {
                continue;
            }
            normal.div(area);
            float axis = Math.max(Math.abs(normal.x), Math.max(Math.abs(normal.y), Math.abs(normal.z)));
            if (axis < AXIS_ALIGNED) {
                crossed += area;
            } else {
                straight += area;
            }
        }
        return crossed > straight;
    }

    /** The quad's normal, as long as its area. */
    private static Vector3f normal(BakedQuad quad) {
        Vector3fc a = quad.position(0);
        Vector3f ab = new Vector3f(quad.position(1)).sub(a);
        Vector3f ad = new Vector3f(quad.position(3)).sub(a);
        return ab.cross(ad);
    }

    static int kindOf(BlockState state) {
        if (state.isAir()) {
            return AIR;
        }
        FluidState fluid = state.getFluidState();
        boolean solid = solidShape(state);
        if (!solid && !fluid.isEmpty()) {
            if (fluid.is(FluidTags.WATER)) {
                return WATER;
            }
            return fluid.is(FluidTags.LAVA) ? SOLID : AIR;
        }
        if (state.is(BlockTags.LEAVES)) {
            return SOLID;
        }
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return AIR;
        }
        return solid ? SOLID : AIR;
    }

    private static boolean solidShape(BlockState state) {
        if (state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
            return false;
        }
        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return false;
        }
        AABB bounds = shape.bounds();
        return bounds.getYsize() >= 0.5 && bounds.getXsize() >= 0.75 && bounds.getZsize() >= 0.75;
    }

    private static boolean isCover(BlockState state) {
        if (state.getRenderShape() == RenderShape.INVISIBLE || !state.getFluidState().isEmpty()) {
            return false;
        }
        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return false;
        }
        AABB bounds = shape.bounds();
        boolean thin = bounds.getYsize() <= 0.25 && bounds.getXsize() >= 0.9 && bounds.getZsize() >= 0.9;
        boolean standable = !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty() || state.is(Blocks.SNOW);
        return thin && standable;
    }

    /**
     * Each face's sprite: the model's largest quad on that side, or its particle sprite; and the
     * cards' ({@link FarQuad#CARD}): its largest quad off the block's axes.
     */
    private void fillFaces(BlockState state, List<BakedQuad> quads, int[] faceAppearances, int[] tintIndices) {
        TextureAtlasSprite particle = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
        for (int face = 0; face <= FarQuad.CARD; face++) {
            BakedQuad best = null;
            float bestArea = -1.0f;
            for (BakedQuad quad : quads) {
                if (face == FarQuad.CARD ? !offAxis(quad) : quad.direction() != FACES[face]) {
                    continue;
                }
                float area = area(quad);
                if (area > bestArea + 1.0e-4f) {
                    best = quad;
                    bestArea = area;
                }
            }
            if (best == null) {
                faceAppearances[face] = appearances.idOf(particle.contents().name(), FarAppearances.FLAG_CUTOUT);
                tintIndices[face] = UNTINTED;
                continue;
            }
            BakedQuad.MaterialInfo material = best.materialInfo();
            int flags = material.layer() == ChunkSectionLayer.SOLID ? 0 : FarAppearances.FLAG_CUTOUT;
            faceAppearances[face] = appearances.idOf(material.sprite().contents().name(), flags);
            tintIndices[face] = material.isTinted() ? material.tintIndex() : UNTINTED;
        }
    }

    private static boolean offAxis(BakedQuad quad) {
        Vector3f normal = normal(quad);
        float length = normal.length();
        if (length < 1.0e-6f) {
            return false;
        }
        normal.div(length);
        return Math.max(Math.abs(normal.x), Math.max(Math.abs(normal.y), Math.abs(normal.z))) < AXIS_ALIGNED;
    }

    private static float area(BakedQuad quad) {
        return normal(quad).length();
    }
}
