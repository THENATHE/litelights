package com.thenathe.litelights.overlays.continuity;

import com.thenathe.litelights.overlays.generation.EdgeShape;
import com.thenathe.litelights.overlays.generation.VarietyHash;
import com.thenathe.litelights.overlays.generation.VarietyLayout;
import com.thenathe.litelights.overlays.pack.BetterGrass;
import java.util.Set;
import java.util.function.Function;
import me.pepperbell.continuity.api.client.QuadProcessor;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Draws one variety set onto the faces of its receiving blocks.
 *
 * <p>Which neighbours count follows Continuity's own overlay method, so a variety set covers
 * exactly what the plain set would: a neighbour spills onto a face when its own face in that plane
 * is visible, it has a full collision box, and it is the source block. An inner corner is drawn
 * where two spilling edges meet. A neighbour that touches the face only at a corner draws nothing
 * (0.1.7 dropped those diagonal-only corners, and the plain set's corner tiles are empty to match).
 *
 * <p>What changes is the choice of piece. Every block corner has a depth class and every edge a
 * wave, both hashed from the world position (see {@link VarietyHash}), so neighbouring blocks agree
 * where they meet while a long straight border stops repeating once per block. Like Continuity's
 * own overlays, every piece is drawn over the whole face: a smaller quad wouldn't share the face's
 * corners, and the tiny depth difference makes it flicker through the face below.
 *
 * <p>Steps: on the top face of a receiver, a source block standing one step up on a neighbour
 * spills its side texture down onto this face, continuing its visible side.
 *
 * <p>Beside LambdaBetterGrass (0.1.7, the user's rules): each side face of one of its blocks is
 * full (drawn with the top texture), blended (fancy mode's partial blend) or left alone, which
 * {@link BetterGrassRules} works out for a neighbour as LambdaBetterGrass does, and the face's
 * own sprite shows for the face being drawn on.
 * <ul>
 *   <li>A full side spills like a source, from the set of the source's top layer: grass, never the
 *       dirt of the vanilla side. Soil beside it (dirt under grass) takes the source's fringe instead.</li>
 *   <li>A blended side spills nothing, and receives nothing from the source's sets.</li>
 *   <li>A side LambdaBetterGrass changed never takes the vanilla side's band.</li>
 *   <li>Sets from other sources (moss, snow) draw on its sides whatever it did there.</li>
 *   <li>A source it draws snowy under a plant isn't a source, and a step continues only a side it
 *       left alone.</li>
 * </ul>
 * With LambdaBetterGrass missing or off, every side is one it left alone, and this draws exactly
 * what it did before.
 */
final class VarietyProcessor implements QuadProcessor {
    /** Draws nothing; used when a file couldn't be set up. */
    static final QuadProcessor NOTHING = (quad, sprite, level, pos, appearanceState, state, random, pass, context)
        -> ProcessingResult.NEXT_PROCESSOR;

    /** For each face, its texture-space left, down, right and up, the same map Continuity's overlays use. */
    private static final Direction[][] TEXTURE_DIRECTIONS = new Direction[6][];

    static {
        for (Direction face : Direction.values()) {
            Direction up = face == Direction.UP ? Direction.NORTH : face == Direction.DOWN ? Direction.SOUTH : Direction.UP;
            Direction left = face.getAxisDirection() == Direction.AxisDirection.NEGATIVE
                ? up.getClockWise(face.getAxis())
                : up.getCounterClockWise(face.getAxis());
            TEXTURE_DIRECTIONS[face.ordinal()] = new Direction[] {left, up.getOpposite(), left.getOpposite(), up};
        }
    }

    private final VarietySettings settings;
    private final VarietyLayout layout;
    private final TextureAtlasSprite[] sprites;
    private final TriState ambientOcclusion;
    private final BlockTintSource tintSource;

    VarietyProcessor(VarietySettings settings, Function<Identifier, TextureAtlasSprite> spriteGetter) {
        this.settings = settings;
        this.layout = settings.layout();
        this.sprites = new TextureAtlasSprite[layout.pieceCount()];
        Identifier folder = Identifier.parse(settings.folder());
        for (int piece : settings.pieces()) {
            TextureAtlasSprite sprite = spriteGetter.apply(folder.withSuffix(Integer.toString(piece)));
            if (!sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                sprites[piece] = sprite;
            }
        }
        BlockState tint = settings.tintState();
        // Like Continuity: a glowing tint block turns ambient occlusion off for its overlays.
        this.ambientOcclusion = tint == null ? TriState.TRUE : TriState.of(tint.getLightEmission() == 0);
        this.tintSource = tint == null || settings.tintIndex() < 0
            ? null
            : Minecraft.getInstance().getBlockColors().getTintSource(tint, settings.tintIndex());
    }

    @Override
    public ProcessingResult processQuad(MutableQuadView quad, TextureAtlasSprite sprite, BlockAndTintGetter level, BlockPos pos,
                                        BlockState appearanceState, BlockState state, RandomSource random, int pass,
                                        ProcessingContext context) {
        if (!VarietyMethod.isAvailable()) {
            return ProcessingResult.NEXT_PROCESSOR;
        }
        try {
            draw(quad, sprite, level, pos, appearanceState, state, context);
        } catch (LinkageError | RuntimeException failure) {
            VarietyMethod.failed("drawing " + settings.folder() + " at " + pos.toShortString(), failure);
        }
        return ProcessingResult.NEXT_PROCESSOR;
    }

    private void draw(MutableQuadView quad, TextureAtlasSprite sprite, BlockAndTintGetter level, BlockPos pos,
                      BlockState appearanceState, BlockState state, ProcessingContext context) {
        Direction face = quad.lightFace();
        if (!onFirstLayer(quad, face, appearanceState)) {
            return;
        }
        BetterGrassRules.Side own = ownSide(face, sprite, appearanceState);
        boolean onFace = settings.faces().contains(face) && takesPieces(own);
        boolean leftToBetterGrass = settings.faces().contains(face) && !onFace;
        boolean stepsHere = settings.steps() && face == Direction.UP;
        DrawAudit.Recorder audit = DrawAudit.recorder();
        if ((!onFace && !stepsHere) || !isUnitSquare(quad)) {
            if (audit != null) {
                audit.face(settings.folder(), pos.asLong(), face, appearanceState, leftToBetterGrass, "");
            }
            return;
        }
        BetterGrass.Settings betterGrass = settings.betterGrassRole() == null ? BetterGrass.Settings.ABSENT : BetterGrass.settings();
        Direction[] directions = TEXTURE_DIRECTIONS[face.ordinal()];
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        Pieces pieces = new Pieces(face, pos, directions, context.getExtraQuadEmitter(), level, audit != null);
        if (onFace) {
            drawEdges(pieces, level, pos, appearanceState, state, face, own, betterGrass, directions, probe);
        }
        if (stepsHere) {
            drawSteps(pieces, level, pos, appearanceState, state, betterGrass, directions, probe);
        }
        if (audit != null) {
            audit.face(settings.folder(), pos.asLong(), face, appearanceState, leftToBetterGrass, pieces.drawn());
        }
    }

    /**
     * Whether this quad is the one of its face that pieces go on. A face drawn in two layers (a
     * grass side and its tinted fringe) is two quads, and the method runs for each; drawing on
     * the bottom layer's quad only emits every piece once.
     */
    private boolean onFirstLayer(QuadView quad, Direction face, BlockState appearanceState) {
        int[] tints = settings.firstLayerTints().get(appearanceState.getBlock());
        if (tints == null || face == Direction.DOWN) {
            return true;
        }
        int wanted = face == Direction.UP ? tints[0] : tints[1];
        return wanted == VarietySettings.ANY_TINT || quad.tintIndex() == wanted;
    }

    /**
     * What LambdaBetterGrass did with this face of the receiver. It changes a side by swapping the
     * face's sprite inside the block's own model, before this method sees the quad: a side still
     * showing one of the block's own side textures is one it left alone, its blend masks end in
     * "_blend_up", "_blend_up_m" or "_arch", and anything else it swapped in (its connected side, a
     * snowy side under a plant) shows the full top or snow.
     */
    private BetterGrassRules.Side ownSide(Direction face, TextureAtlasSprite sprite, BlockState appearanceState) {
        if (!face.getAxis().isHorizontal() || !settings.betterGrassBlocks().contains(appearanceState.getBlock())) {
            return BetterGrassRules.Side.VANILLA;
        }
        boolean snowy = appearanceState.hasProperty(BlockStateProperties.SNOWY)
            && appearanceState.getValue(BlockStateProperties.SNOWY);
        Set<Identifier> ownSides = snowy ? settings.betterGrassOwnSnowySides() : settings.betterGrassOwnSides();
        Identifier name = sprite.contents().name();
        if (ownSides.contains(name)) {
            return BetterGrassRules.Side.VANILLA;
        }
        String path = name.getPath();
        return path.endsWith("_blend_up") || path.endsWith("_blend_up_m") || path.endsWith("_arch")
            ? BetterGrassRules.Side.BLEND
            : BetterGrassRules.Side.FULL;
    }

    /**
     * Whether this set draws on a face LambdaBetterGrass treated as {@code own}. Sets from other
     * sources draw over whatever it did. A source's own sets never draw on a blended side, and on a
     * full side only its top layer does, beside full sides.
     */
    private boolean takesPieces(BetterGrassRules.Side own) {
        if (own == BetterGrassRules.Side.VANILLA || settings.betterGrassRole() == null) {
            return true;
        }
        return own == BetterGrassRules.Side.FULL && (settings.betterGrassRole() == VarietySettings.BetterGrassRole.TOP
            || settings.betterGrassRole() == VarietySettings.BetterGrassRole.BOTH);
    }

    private void drawEdges(Pieces pieces, BlockAndTintGetter level, BlockPos pos, BlockState appearanceState, BlockState state,
                           Direction face, BetterGrassRules.Side own, BetterGrass.Settings betterGrass, Direction[] directions,
                           BlockPos.MutableBlockPos probe) {
        boolean[] spills = new boolean[4];
        boolean beside = face.getAxis().isHorizontal() && settings.betterGrassRole() != null;
        boolean soil = settings.betterGrassSoil().contains(appearanceState.getBlock());
        for (int edge = 0; edge < 4; edge++) {
            probe.setWithOffset(pos, directions[edge]).move(face);
            if (level.getBlockState(probe).isSolidRender()) {
                continue;
            }
            probe.setWithOffset(pos, directions[edge]);
            BlockState neighbour = level.getBlockState(probe);
            BlockState appearance = neighbour.getAppearance(level, probe, face, state, pos);
            if (!isSource(neighbour, appearance, probe, level, appearanceState, betterGrass)) {
                continue;
            }
            spills[edge] = !beside || settings.betterGrassRole().spillsBeside(
                settings.betterGrassRules().side(level, probe, neighbour, face, betterGrass), own, soil);
        }
        for (int edge = 0; edge < 4; edge++) {
            if (spills[edge]) {
                pieces.band(edge);
            }
        }
        if (settings.blend()) {
            return;
        }
        for (int corner = 0; corner < 4; corner++) {
            if (spills[corner] && spills[(corner + 1) % 4]) {
                pieces.innerCorner(corner);
            }
        }
    }

    /**
     * A source standing on a neighbour one step up shows its side face above this block's top edge,
     * so its side texture spills down onto this face. The neighbour under it must be a solid block
     * (a real step), and the space above this block open so that side face is visible. Beside
     * LambdaBetterGrass, only a side it left alone is continued: a full side can't stand over a
     * step, and a blended one is green down to its bottom corner.
     */
    private void drawSteps(Pieces pieces, BlockAndTintGetter level, BlockPos pos, BlockState appearanceState, BlockState state,
                           BetterGrass.Settings betterGrass, Direction[] directions, BlockPos.MutableBlockPos probe) {
        probe.setWithOffset(pos, Direction.UP);
        if (level.getBlockState(probe).isSolidRender()) {
            return;
        }
        for (int edge = 0; edge < 4; edge++) {
            probe.setWithOffset(pos, directions[edge]);
            if (!level.getBlockState(probe).isSolidRender()) {
                continue;
            }
            probe.move(Direction.UP);
            BlockState upper = level.getBlockState(probe);
            Direction toward = directions[edge].getOpposite();
            BlockState appearance = upper.getAppearance(level, probe, toward, state, pos);
            if (!isSource(upper, appearance, probe, level, appearanceState, betterGrass)) {
                continue;
            }
            if (settings.betterGrassRole() == null
                || settings.betterGrassRules().side(level, probe, upper, toward, betterGrass) == BetterGrassRules.Side.VANILLA) {
                pieces.step(edge);
            }
        }
    }

    private boolean isSource(BlockState neighbour, BlockState appearance, BlockPos at, BlockAndTintGetter level,
                             BlockState receiverAppearance, BetterGrass.Settings betterGrass) {
        return appearance.getBlock() != receiverAppearance.getBlock()
            && settings.source().test(appearance)
            && neighbour.isCollisionShapeFullBlock(level, at)
            && !(settings.betterGrassRole() != null && settings.betterGrassRules().drawnSnowy(level, at, neighbour, betterGrass));
    }

    /** Continuity draws overlays only on quads that fill their block face; partial faces are left alone. */
    private static boolean isUnitSquare(QuadView quad) {
        int first;
        int second;
        switch (quad.lightFace().getAxis()) {
            case X -> {
                first = 1;
                second = 2;
            }
            case Y -> {
                first = 0;
                second = 2;
            }
            default -> {
                first = 0;
                second = 1;
            }
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            if (!isZeroOrOne(quad.posByIndex(vertex, first)) || !isZeroOrOne(quad.posByIndex(vertex, second))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZeroOrOne(float value) {
        return Math.abs(value) < 0.0001f || Math.abs(value - 1) < 0.0001f;
    }

    /** Picks and emits the pieces for one face, working out its corner classes once. */
    private final class Pieces {
        private final Direction face;
        private final Direction[] directions;
        private final QuadEmitter emitter;
        private final int[] cornerClasses = new int[4];
        private final int centreX;
        private final int centreY;
        private final int centreZ;
        private final int color;
        private final StringBuilder drawn;

        Pieces(Direction face, BlockPos pos, Direction[] directions, QuadEmitter emitter, BlockAndTintGetter level,
               boolean recording) {
            this.face = face;
            this.drawn = recording ? new StringBuilder() : null;
            this.directions = directions;
            this.emitter = emitter;
            // Half-block units: the face's centre, from which corners and edge midpoints are one step away.
            this.centreX = pos.getX() * 2 + 1 + face.getStepX();
            this.centreY = pos.getY() * 2 + 1 + face.getStepY();
            this.centreZ = pos.getZ() * 2 + 1 + face.getStepZ();
            for (int corner = 0; corner < 4; corner++) {
                Direction first = directions[corner];
                Direction second = directions[(corner + 1) % 4];
                cornerClasses[corner] = VarietyHash.cornerClass(centreX + first.getStepX() + second.getStepX(),
                    centreY + first.getStepY() + second.getStepY(), centreZ + first.getStepZ() + second.getStepZ(),
                    layout.classes());
            }
            this.color = tintSource == null ? -1 : tintSource.colorInWorld(settings.tintState(), level, pos);
        }

        void band(int edge) {
            Direction toward = directions[edge];
            int wave = VarietyHash.wave(centreX + toward.getStepX(), centreY + toward.getStepY(), centreZ + toward.getStepZ(),
                layout.waves());
            emit(layout.band(edge, startClass(edge), endClass(edge), wave));
        }

        void step(int edge) {
            emit(layout.step(edge));
        }

        void innerCorner(int corner) {
            emit(layout.innerCorner(corner, cornerClasses[corner]));
        }

        /** The pieces emitted so far, when recording for {@link DrawAudit}. */
        String drawn() {
            return drawn == null ? "" : drawn.toString().trim();
        }

        private int startClass(int edge) {
            return cornerClasses[EdgeShape.EDGE_START[edge]];
        }

        private int endClass(int edge) {
            return cornerClasses[EdgeShape.EDGE_END[edge]];
        }

        /** Emits a piece over the whole face, the same way Continuity emits its overlay tiles. */
        private void emit(int piece) {
            TextureAtlasSprite sprite = sprites[piece];
            if (sprite == null) {
                return;
            }
            if (drawn != null) {
                drawn.append(piece).append(' ');
            }
            emitter.square(face, 0, 0, 1, 1, 0);
            emitter.color(color, color, color, color);
            emitter.uv(0, sprite.getU0(), sprite.getV0());
            emitter.uv(1, sprite.getU0(), sprite.getV1());
            emitter.uv(2, sprite.getU1(), sprite.getV1());
            emitter.uv(3, sprite.getU1(), sprite.getV0());
            emitter.atlas(QuadAtlas.BLOCK);
            emitter.animated(false);
            emitter.chunkLayer(settings.layer());
            emitter.itemRenderType(settings.layer() == ChunkSectionLayer.TRANSLUCENT
                ? Sheets.translucentBlockItemSheet()
                : Sheets.cutoutBlockItemSheet());
            emitter.ambientOcclusion(ambientOcclusion);
            emitter.emit();
        }
    }
}
