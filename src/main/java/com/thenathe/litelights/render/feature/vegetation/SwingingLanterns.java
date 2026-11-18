package com.thenathe.litelights.render.feature.vegetation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.mixin.particles.BiomeManagerAccessor;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.particles.LeafWind;
import com.thenathe.litelights.render.feature.skyfog.StormAtmosphere;
import com.thenathe.litelights.render.feature.weather.Wind;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.pipeline.ReloadDefines;
import com.thenathe.litelights.render.pipeline.TerrainParts;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Swinging lanterns, chains and hanging signs: finds the hanging columns near the camera and lists
 * the nearest in the frame block, where the terrain shaders swing them
 * (shaders/include/vegetation/hanging.glsl); {@link HangingSwing} holds the maths both sides share.
 *
 * <p>A column ends at the bottom in a hanging lantern, a hanging sign or a vertical chain with air
 * under it, and goes up through the vertical chains above that to the block it hangs from. Sections
 * coming into reach are read a few at a time, once, and only those whose palette holds a hanging
 * lantern, a vertical chain or a hanging sign are read block by block. A block change touching any of
 * those reads its chunk again at once, so placing or breaking one shows straight away, and a chunk
 * that loads again is read again.
 *
 * <p>A hanging sign's board is a block model and swings in the shaders like a lantern; its text is
 * drawn by the sign's block entity renderer, which turns it the same way here
 * ({@link #turnSignText}).
 */
public final class SwingingLanterns {
    private static final Feature FEATURE = Feature.of("vegetation.sway_lanterns");

    /** Sections read each tick; a full pass of the reach takes under a second. */
    private static final int SECTIONS_PER_TICK = 160;
    private static final int SECTION_REACH = 6;
    private static final int SECTION_SPAN = SECTION_REACH * 2 + 1;
    private static final int SECTIONS_PER_PASS = SECTION_SPAN * SECTION_SPAN * SECTION_SPAN;
    private static final double REACH = HangingSwing.FADE_END;
    /** The longest chain followed up from a column's bottom. */
    private static final int MAX_CHAIN = 256;
    /** Something under a hanging sign that reaches this close to its top touches the board, which then can't swing. */
    private static final double TOUCHING = 0.9;

    private static final Predicate<BlockState> MAY_HANG = state -> isHangingLantern(state) || isVerticalChain(state)
        || state.getBlock() instanceof CeilingHangingSignBlock || state.getBlock() instanceof WallHangingSignBlock;

    /** One hanging column, packed as the frame block holds it (see HangingSwing). */
    record Column(int x, int bottomY, int z, int packedBottom, int packedPivot) {
        long bottom() {
            return BlockPos.asLong(x, bottomY, z);
        }
    }

    private static final Column[] NONE = new Column[0];

    private static final SwingingLanterns INSTANCE = new SwingingLanterns();

    /** The columns whose bottoms are in each section read, by section key. */
    private final Long2ObjectOpenHashMap<Column[]> sections = new Long2ObjectOpenHashMap<>();
    /**
     * Every section read, with columns or not. A section is read once while it stays in reach;
     * block changes and chunk loads read it again.
     */
    private final LongOpenHashSet read = new LongOpenHashSet();
    private boolean listChanged;
    private double listedAtX;
    private double listedAtY;
    private double listedAtZ;
    private final List<Column> nearby = new ArrayList<>();
    private Column[] listed = NONE;
    private final Long2IntOpenHashMap listedSigns = new Long2IntOpenHashMap();
    private final int[] cells = new int[HangingSwing.CELL_ROWS];
    private final int[] columns = new int[HangingSwing.MAX_COLUMNS * 4];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private @Nullable ClientLevel level;
    private boolean active;
    private int scanIndex;
    private int scanCentreX;
    private int scanCentreY;
    private int scanCentreZ;
    private double cameraX;
    private double cameraY;
    private double cameraZ;

    // This frame's swing and camera, for the sign text (the same values the frame block gets).
    private int frameCameraX;
    private int frameCameraY;
    private int frameCameraZ;
    private final float[] frameCameraFraction = new float[3];
    private float frameSeconds;
    private float frameHeading;
    private float frameStrength;
    private float amplitudeScale = 1.0f;
    private float strengthForQa = -1.0f;
    private final float[] tilt = new float[2];
    private final float[] wind = new float[2];

    private @Nullable String problemShown;
    private boolean stateShown;

    // QA: time spent finding and listing columns.
    private long tickNanos;
    private int ticksTimed;

    private SwingingLanterns() {
        listedSigns.defaultReturnValue(-1);
    }

    public static SwingingLanterns get() {
        return INSTANCE;
    }

    static void initialize() {
        LiteRenderEvents.FRAME_UPDATE.register(INSTANCE::onFrameUpdate);
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
        ClientChunkEvents.CHUNK_LOAD.register((chunkLevel, chunk) -> INSTANCE.forgetChunk(chunk.getPos().x(), chunk.getPos().z()));
        ClientChunkEvents.CHUNK_UNLOAD.register((chunkLevel, chunk) -> INSTANCE.forgetChunk(chunk.getPos().x(), chunk.getPos().z()));
    }

    /** For QA pictures: scales every swing, like the plants' amplitude scale. 1 is normal. */
    public void setAmplitudeScale(float scale) {
        amplitudeScale = scale;
    }

    /** For QA pictures: blows the lanterns' wind at a set strength whatever the weather; below 0 the weather decides again. */
    public void setStrengthForQa(float strength) {
        strengthForQa = strength;
    }

    /** How many columns the frame block lists now (QA). */
    public int listedCount() {
        return listed.length;
    }

    /** QA: ticks timed and the milliseconds they took since the last call, then starts again. */
    public double[] takeTickReadingsForQa() {
        double[] readings = {ticksTimed, tickNanos / 1.0e6};
        ticksTimed = 0;
        tickNanos = 0L;
        return readings;
    }

    // --- Finding the columns (client thread) ---

    private void tick(Minecraft minecraft) {
        ClientLevel current = minecraft.level;
        if (current != level) {
            clear();
            level = current;
        }
        if (current == null || !active || minecraft.getCameraEntity() == null) {
            if (!sections.isEmpty() || listed.length > 0) {
                clear();
            }
            return;
        }
        long started = System.nanoTime();
        var camera = minecraft.gameRenderer.mainCamera().position();
        cameraX = camera.x;
        cameraY = camera.y;
        cameraZ = camera.z;
        scanStep(current);
        double movedX = cameraX - listedAtX;
        double movedY = cameraY - listedAtY;
        double movedZ = cameraZ - listedAtZ;
        if (listChanged || movedX * movedX + movedY * movedY + movedZ * movedZ > 1.0) {
            list();
        }
        tickNanos += System.nanoTime() - started;
        ticksTimed++;
    }

    private void clear() {
        sections.clear();
        read.clear();
        nearby.clear();
        listed = NONE;
        listedSigns.clear();
        scanIndex = 0;
    }

    private void scanStep(ClientLevel current) {
        if (scanIndex == 0) {
            scanCentreX = SectionPos.blockToSectionCoord(cameraX);
            scanCentreY = SectionPos.blockToSectionCoord(cameraY);
            scanCentreZ = SectionPos.blockToSectionCoord(cameraZ);
            forgetDistantSections();
        }
        int end = Math.min(SECTIONS_PER_PASS, scanIndex + SECTIONS_PER_TICK);
        for (; scanIndex < end; scanIndex++) {
            int column = scanIndex / SECTION_SPAN;
            int sectionX = scanCentreX - SECTION_REACH + column % SECTION_SPAN;
            int sectionZ = scanCentreZ - SECTION_REACH + column / SECTION_SPAN;
            int sectionY = scanCentreY - SECTION_REACH + scanIndex % SECTION_SPAN;
            if (!read.contains(SectionPos.asLong(sectionX, sectionY, sectionZ))) {
                readSection(current, sectionX, sectionY, sectionZ);
            }
        }
        if (scanIndex >= SECTIONS_PER_PASS) {
            scanIndex = 0;
        }
    }

    private void forgetDistantSections() {
        ObjectIterator<Long2ObjectMap.Entry<Column[]>> iterator = sections.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            long key = iterator.next().getLongKey();
            if (!inReach(key)) {
                iterator.remove();
                listChanged = true;
            }
        }
        read.removeIf((long key) -> !inReach(key));
    }

    private boolean inReach(long sectionKey) {
        return Math.abs(SectionPos.x(sectionKey) - scanCentreX) <= SECTION_REACH + 1
            && Math.abs(SectionPos.y(sectionKey) - scanCentreY) <= SECTION_REACH + 1
            && Math.abs(SectionPos.z(sectionKey) - scanCentreZ) <= SECTION_REACH + 1;
    }

    /** A chunk loaded (again) or unloaded: what was read of it no longer holds. */
    private void forgetChunk(int chunkX, int chunkZ) {
        if (read.isEmpty()) {
            return;
        }
        if (sections.keySet().removeIf((long key) -> SectionPos.x(key) == chunkX && SectionPos.z(key) == chunkZ)) {
            listChanged = true;
        }
        read.removeIf((long key) -> SectionPos.x(key) == chunkX && SectionPos.z(key) == chunkZ);
    }

    /** Reads one section's columns again, replacing what was known of it. */
    private void readSection(ClientLevel current, int sectionX, int sectionY, int sectionZ) {
        long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
        ChunkAccess chunk = current.getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            // Not loaded yet: read it when the pass comes round again.
            forgetSection(key);
            return;
        }
        read.add(key);
        int index = chunk.getSectionIndexFromSectionY(sectionY);
        if (index < 0 || index >= chunk.getSections().length) {
            forgetSection(key);
            return;
        }
        LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir() || !section.maybeHas(MAY_HANG)) {
            forgetSection(key);
            return;
        }
        List<Column> found = new ArrayList<>(2);
        int baseX = sectionX << 4;
        int baseY = sectionY << 4;
        int baseZ = sectionZ << 4;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (MAY_HANG.test(state)) {
                        Column column = columnEndingAt(current, baseX + x, baseY + y, baseZ + z, state);
                        if (column != null) {
                            found.add(column);
                        }
                    }
                }
            }
        }
        if (found.isEmpty()) {
            forgetSection(key);
        } else {
            sections.put(key, found.toArray(NONE));
            listChanged = true;
        }
    }

    private void forgetSection(long key) {
        if (sections.remove(key) != null) {
            listChanged = true;
        }
    }

    /**
     * A block changed in the client's level. When it is, or was, part of a hanging column, or sits
     * right under one (a sign's board or a loose chain end may now touch it), its chunk is read again.
     */
    public void onBlockChanged(ClientLevel changedIn, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!active || changedIn != level) {
            return;
        }
        if (!MAY_HANG.test(oldState) && !MAY_HANG.test(newState) && !MAY_HANG.test(changedIn.getBlockState(pos.above()))) {
            return;
        }
        int sectionX = SectionPos.blockToSectionCoord(pos.getX());
        int sectionZ = SectionPos.blockToSectionCoord(pos.getZ());
        for (int sectionY = changedIn.getMinSectionY(); sectionY <= changedIn.getMaxSectionY(); sectionY++) {
            readSection(changedIn, sectionX, sectionY, sectionZ);
        }
        list();
    }

    /** The column whose bottom is this block, or null when it isn't the bottom of one that swings. */
    private @Nullable Column columnEndingAt(ClientLevel current, int x, int y, int z, BlockState state) {
        int bottom;
        int pivot;
        int mode = HangingSwing.FREE;
        if (isHangingLantern(state)) {
            // The lantern's body starts a sixteenth up; the block under it stays out of the column.
            bottom = y * 16;
            pivot = (topOfChain(current, x, y + 1, z) + 1) * 16;
        } else if (state.getBlock() instanceof CeilingHangingSignBlock) {
            if (!hangsFree(current, x, y - 1, z)) {
                return null;
            }
            bottom = y * 16 - 1;
            int top = topOfChain(current, x, y + 1, z);
            pivot = (top + 1) * 16;
            // Under a chain the sign swings every way with it; hung from a block, only to and from its face.
            if (top == y) {
                mode = 1 + state.getValue(BlockStateProperties.ROTATION_16);
            }
        } else if (state.getBlock() instanceof WallHangingSignBlock) {
            if (!hangsFree(current, x, y - 1, z)) {
                return null;
            }
            // It hangs from the middle of its bar, a sixteenth below the block's top.
            bottom = y * 16 - 1;
            pivot = y * 16 + 15;
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            mode = 1 + Math.round(facing.toYRot() / 22.5f) % 16;
        } else if (isVerticalChain(state)) {
            // A loose chain end swings; a chain that runs on into anything else is held at both ends.
            if (!current.getBlockState(cursor.set(x, y - 1, z)).isAir()) {
                return null;
            }
            bottom = y * 16 - 1;
            pivot = (topOfChain(current, x, y, z) + 1) * 16;
        } else {
            return null;
        }
        int cycles = HangingSwing.cyclesPerHour(pivot - bottom, x, z);
        return new Column(x, y, z, HangingSwing.packBottom(bottom, cycles), HangingSwing.packPivot(pivot, mode));
    }

    /** The highest block of the vertical chain starting at {@code fromY}, or {@code fromY - 1} when there is none. */
    private int topOfChain(ClientLevel current, int x, int fromY, int z) {
        int top = fromY - 1;
        int highest = Math.min(current.getMaxY(), fromY + MAX_CHAIN);
        for (int y = fromY; y <= highest && isVerticalChain(current.getBlockState(cursor.set(x, y, z))); y++) {
            top = y;
        }
        return top;
    }

    /**
     * Whether a hanging sign's board, which reaches the bottom of its block, is clear of the block
     * under it. A lantern hanging from the sign keeps it still too, so the two stay together.
     */
    private boolean hangsFree(ClientLevel current, int x, int y, int z) {
        BlockState below = current.getBlockState(cursor.set(x, y, z));
        if (below.isAir()) {
            return true;
        }
        if (isHangingLantern(below)) {
            return false;
        }
        VoxelShape shape = below.getShape(current, cursor);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) < TOUCHING;
    }

    static boolean isHangingLantern(BlockState state) {
        return (state.getBlock() instanceof LanternBlock || state.is(BlockTags.LANTERNS))
            && state.getValueOrElse(BlockStateProperties.HANGING, false);
    }

    static boolean isVerticalChain(BlockState state) {
        return (state.getBlock() instanceof ChainBlock || state.is(BlockTags.CHAINS) || state.is(ConventionalBlockTags.CHAINS))
            && state.getValueOrElse(BlockStateProperties.AXIS, Direction.Axis.X) == Direction.Axis.Y;
    }

    /** Lists the nearest columns within reach for the frame block, and which of them are signs. */
    private void list() {
        listChanged = false;
        listedAtX = cameraX;
        listedAtY = cameraY;
        listedAtZ = cameraZ;
        nearby.clear();
        for (Column[] inSection : sections.values()) {
            for (Column column : inSection) {
                if (distanceSquared(column) <= REACH * REACH) {
                    nearby.add(column);
                }
            }
        }
        if (nearby.size() > HangingSwing.MAX_COLUMNS) {
            nearby.sort(Comparator.comparingDouble(this::distanceSquared));
        }
        int count = Math.min(nearby.size(), HangingSwing.MAX_COLUMNS);
        listed = nearby.subList(0, count).toArray(NONE);
        listedSigns.clear();
        Arrays.fill(cells, 0);
        for (int i = 0; i < count; i++) {
            Column column = listed[i];
            cells[column.z() & 31] |= 1 << (column.x() & 31);
            columns[i * 4] = column.x();
            columns[i * 4 + 1] = column.z();
            columns[i * 4 + 2] = column.packedBottom();
            columns[i * 4 + 3] = column.packedPivot();
            if (level != null && isSign(level.getBlockState(cursor.set(column.x(), column.bottomY(), column.z())))) {
                listedSigns.put(column.bottom(), i);
            }
        }
    }

    private static boolean isSign(BlockState state) {
        return state.getBlock() instanceof CeilingHangingSignBlock || state.getBlock() instanceof WallHangingSignBlock;
    }

    private double distanceSquared(Column column) {
        double dx = column.x() + 0.5 - cameraX;
        double dy = column.bottomY() + 0.5 - cameraY;
        double dz = column.z() + 0.5 - cameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

    // --- Each frame (render thread) ---

    private void onFrameUpdate(FrameState frame, RenderSettings settings) {
        String problem = shaderProblem();
        showState(problem);
        // Like the plants' sway, one of a modded dimension's effects.
        active = problem == null && settings.vegetation().swayingLanterns() && DimensionGate.allows(Aspect.EFFECTS);
        ClientLevel current = Minecraft.getInstance().level;
        if (!active || current == null) {
            frame.swing.set(0.0f, amplitudeScale, 0.0f, 0.0f);
            frameStrength = 0.0f;
            return;
        }
        frameCameraX = frame.cameraBlockX;
        frameCameraY = frame.cameraBlockY;
        frameCameraZ = frame.cameraBlockZ;
        frameCameraFraction[0] = frame.cameraFraction.x;
        frameCameraFraction[1] = frame.cameraFraction.y;
        frameCameraFraction[2] = frame.cameraFraction.z;
        frameSeconds = frame.time.y;
        frameHeading = frame.featureValuesD.y;
        frameStrength = strengthForQa >= 0.0f ? strengthForQa : windStrength(current, frame.weather.x, frame.weather.y);
        frame.swing.set(frameStrength, amplitudeScale, listed.length, 0.0f);
        System.arraycopy(cells, 0, frame.swingCells, 0, cells.length);
        System.arraycopy(columns, 0, frame.swingColumns, 0, listed.length * 4);
    }

    /**
     * How hard the wind blows on the lanterns, 0 (calm) to 1 (the strongest storm): LiteLights'
     * wind with the storms' share of it (rain, thunder, sandstorms and blizzards), the same wind the
     * particles blow in.
     */
    private float windStrength(ClientLevel current, float rain, float thunder) {
        long seed = ((BiomeManagerAccessor) current.getBiomeManager()).litelights$biomeZoomSeed();
        StormAtmosphere storms = StormAtmosphere.current();
        float storm = Wind.stormStrength(rain, thunder, storms.sandstorm(), storms.blizzard());
        Wind.sample(seed, current.getOverworldClockTime(), current.getGameTime(), storm, wind);
        return LeafWind.strength(wind[0], wind[1]);
    }

    /** The columns swing in LiteLights' terrain shaders, in the sway part; without those, nothing swings. */
    private static @Nullable String shaderProblem() {
        String problem = RenderFeatureStatus.problem(RenderFeature.TERRAIN_SHADING);
        if (problem == null) {
            problem = RenderFeatureStatus.problem(RenderFeature.SODIUM_TERRAIN);
        }
        if (problem == null && (Boolean.FALSE.equals(ReloadDefines.built(TerrainParts.LITELIGHTS_SHADING))
            || Boolean.FALSE.equals(ReloadDefines.built(TerrainParts.Part.SWAY.define())))) {
            problem = "the block shaders were built without sway (General > Compatibility)";
        }
        return problem;
    }

    private void showState(@Nullable String problem) {
        if (stateShown && Objects.equals(problem, problemShown)) {
            return;
        }
        stateShown = true;
        problemShown = problem;
        if (problem == null) {
            FEATURE.markReady();
        } else {
            FEATURE.markUnavailable("Swaying lanterns can't run: " + problem);
        }
    }

    /**
     * Turns a hanging sign's text with its board, as the shader turns the board: about the column's
     * pivot by its tilt. The pose is at the sign block's corner. Returns false, leaving the pose
     * alone, for a sign that doesn't swing.
     */
    public boolean turnSignText(BlockPos signPos, PoseStack pose) {
        if (!active) {
            return false;
        }
        int slot = listedSigns.get(signPos.asLong());
        if (slot < 0 || slot >= listed.length) {
            return false;
        }
        Column column = listed[slot];
        // The distance as the shader works it out, from the camera block and its fraction.
        float weightX = (column.x() - frameCameraX) + 0.5f - frameCameraFraction[0];
        float weightY = HangingSwing.bottomSixteenths(column.packedBottom()) / 16.0f - (frameCameraY + frameCameraFraction[1]);
        float weightZ = (column.z() - frameCameraZ) + 0.5f - frameCameraFraction[2];
        float fade = HangingSwing.fade((float) Math.sqrt(weightX * weightX + weightY * weightY + weightZ * weightZ));
        HangingSwing.tilt(column.x(), column.z(), column.packedBottom(), column.packedPivot(), frameSeconds, frameHeading, frameStrength, tilt);
        float tiltX = tilt[0] * fade * amplitudeScale;
        float tiltZ = tilt[1] * fade * amplitudeScale;
        float angle = (float) Math.sqrt(tiltX * tiltX + tiltZ * tiltZ);
        if (angle < 1.0e-5f) {
            return false;
        }
        float pivotY = HangingSwing.pivotSixteenths(column.packedPivot()) / 16.0f - signPos.getY();
        pose.translate(0.5f, pivotY, 0.5f);
        pose.rotate(new Quaternionf().rotationAxis(angle, -tiltZ / angle, 0.0f, tiltX / angle));
        pose.translate(-0.5f, -pivotY, -0.5f);
        return true;
    }
}
