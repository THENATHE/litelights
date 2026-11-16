package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.mixin.particles.FireBlockInvoker;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Enhanced fire: lit smoke and embers around fires and lit campfires, burn marks under fires
 * ({@link BurnMarks}) and ember veins in the flammable blocks joined to them ({@link EmberVeins}).
 * The fire's own glow comes from bloom: fire is an emissive material.
 *
 * <p>Fires are found by looking through the chunk sections around the camera, a few sections a
 * tick, so a full pass takes about a second and no single tick pays for it. Each section's
 * palette says whether it can hold fire at all, so almost every section is skipped without reading
 * a block; only sections with fire are read in full. When a pass ends, its fires become the known
 * fires and its nearest ones the emitters until the next pass ends. Block changes keep the known
 * fires up to date in between, so smoke stops, marks fade and veins update the moment a fire
 * appears or goes out.
 */
final class FireEffects {
    private static final int MAX_EMITTERS = 40;
    /** The most fires kept track of for marks and veins (the nearest 40 also emit particles). */
    private static final int MAX_FIRES = 1024;
    private static final int SECTION_REACH = 2;
    private static final int SECTION_SPAN = SECTION_REACH * 2 + 1;
    /** Sections around the camera: five by five columns, three sections high. */
    private static final int SECTIONS_PER_PASS = SECTION_SPAN * SECTION_SPAN * 3;
    private static final int SECTIONS_PER_TICK = 4;
    /** Fires further than this from the camera are left out. */
    private static final int REACH_BLOCKS = 40;

    private static final byte TYPE_FIRE = 0;
    private static final byte TYPE_SOUL_FIRE = 1;
    private static final byte TYPE_CAMPFIRE = 2;
    private static final byte TYPE_SOUL_CAMPFIRE = 3;

    private static final int EMBER_COLOR = 0xFFFFA23C;
    private static final int SOUL_EMBER_COLOR = 0xFF66E4FF;
    /** Smoke starts lit by the fire under it and cools to grey as it rises (see SMOKE's fade colour). */
    private static final int LIT_SMOKE_COLOR = 0x86B07A52;
    private static final int SOUL_SMOKE_COLOR = 0x765C7A86;

    // Spawn rates per emitter per tick, and mean lifetimes in ticks. Every fire shares the fire
    // category's slice of the cap: past a handful of fires each one gets a smaller rate, so the
    // standing count of smoke and embers stays within their shares of it and neither crowds out the
    // other.
    private static final float SMOKE_RATE = 0.24f;
    private static final int SMOKE_LIFE = 100;
    private static final int SMOKE_LIFE_SPREAD = 60;
    private static final float SMOKE_SHARE = 0.6f;
    private static final float EMBER_RATE = 0.4f;
    private static final float CAMPFIRE_EMBER_RATE = 0.25f;
    private static final int EMBER_LIFE = 30;
    private static final int EMBER_LIFE_SPREAD = 40;
    private static final float EMBER_SHARE = 0.4f;

    private static final Predicate<BlockState> MAY_BURN = state -> state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
        || state.is(BlockTags.CAMPFIRES);

    // The emitters in use, and the ones the scan in progress is collecting.
    private long[] emitters = new long[MAX_EMITTERS];
    private byte[] emitterTypes = new byte[MAX_EMITTERS];
    private int emitterCount;
    private long[] found = new long[MAX_EMITTERS];
    private byte[] foundTypes = new byte[MAX_EMITTERS];
    private final double[] foundDistance = new double[MAX_EMITTERS];
    private int foundCount;
    private int scanIndex;
    private int scanCentreX;
    private int scanCentreY;
    private int scanCentreZ;

    /** Every fire known within reach, by type; and the ones the scan in progress has found. */
    private Long2ByteOpenHashMap fires = new Long2ByteOpenHashMap();
    private Long2ByteOpenHashMap scanned = new Long2ByteOpenHashMap();
    private final LongArrayList veinSeeds = new LongArrayList();
    private final EmberVeins veins = new EmberVeins();
    private final BurnMarks marks = new BurnMarks();

    /** Whether Enhanced fire ran this tick; block changes are ignored while it doesn't. */
    private boolean active;
    private boolean veinsOn;
    private ClientLevel level;
    private long gameTime;
    private double cameraX;
    private double cameraY;
    private double cameraZ;

    FireEffects() {
        // A missing position reads as no type at all, never as TYPE_FIRE (0).
        fires.defaultReturnValue((byte) -1);
        scanned.defaultReturnValue((byte) -1);
    }

    void clear() {
        emitterCount = 0;
        foundCount = 0;
        scanIndex = 0;
        fires.clear();
        scanned.clear();
        veins.clear();
        marks.clear();
        active = false;
        level = null;
    }

    int emitterCount() {
        return emitterCount;
    }

    int knownFires() {
        return fires.size();
    }

    EmberVeins veins() {
        return veins;
    }

    BurnMarks marks() {
        return marks;
    }

    boolean hasDecals() {
        return !veins.isEmpty() || !marks.isEmpty();
    }

    void spawn(Surroundings s, SpawnQueue queue) {
        if (!s.effectsAllowed || !s.settings.particles().enhancedFire()) {
            if (active) {
                clear();
            }
            return;
        }
        active = true;
        level = s.level;
        gameTime = s.gameTime;
        cameraX = s.cameraX;
        cameraY = s.cameraY;
        cameraZ = s.cameraZ;
        scanStep(s);
        updateDecals(s);
        spawnParticles(s, queue);
    }

    private void updateDecals(Surroundings s) {
        boolean wantVeins = s.settings.particles().emberVeins();
        if (wantVeins != veinsOn) {
            veinsOn = wantVeins;
            veins.clear();
            if (wantVeins) {
                veins.markDirty(gameTime - 100);
            }
        }
        if (veinsOn && veins.wantsSearch(gameTime)) {
            searchVeins(s.level);
        }
        veins.tick(s.level);
        marks.tick();
    }

    /** Runs the vein search from every known plain fire (soul fire never spreads, so it starts none). */
    private void searchVeins(ClientLevel searchLevel) {
        veinSeeds.clear();
        for (ObjectIterator<Long2ByteMap.Entry> iterator = fires.long2ByteEntrySet().fastIterator(); iterator.hasNext(); ) {
            Long2ByteMap.Entry entry = iterator.next();
            if (entry.getByteValue() == TYPE_FIRE) {
                veinSeeds.add(entry.getLongKey());
            }
        }
        veins.search(searchLevel, veinSeeds, gameTime);
    }

    /** QA only: veins left on blocks that have burnt away or been broken. */
    int strandedVeins() {
        return level == null ? 0 : veins.countStranded(level);
    }

    /** QA only: runs the vein search now and returns how long it took, in nanoseconds. */
    long forceVeinSearch() {
        if (!active || level == null || !veinsOn) {
            return 0L;
        }
        searchVeins(level);
        return veins.lastSearchNanos();
    }

    private void spawnParticles(Surroundings s, SpawnQueue queue) {
        float scale = queue.rateScale();
        int share = queue.categoryCap(ParticleCategory.FIRE);
        int smokeEmitters = 0;
        for (int i = 0; i < emitterCount; i++) {
            if (!isCampfire(emitterTypes[i])) {
                smokeEmitters++;
            }
        }
        float smokeRate = Math.min(SMOKE_RATE * scale,
            SMOKE_SHARE * share / ((SMOKE_LIFE + SMOKE_LIFE_SPREAD * 0.5f) * Math.max(1, smokeEmitters)));
        float emberLimit = EMBER_SHARE * share / ((EMBER_LIFE + EMBER_LIFE_SPREAD * 0.5f) * Math.max(1, emitterCount));

        RandomSource random = s.random;
        BlockPos.MutableBlockPos cursor = s.cursor;
        for (int i = 0; i < emitterCount && queue.room(ParticleCategory.FIRE) > 0; i++) {
            if (!fires.containsKey(emitters[i])) {
                // Went out since the last pass.
                continue;
            }
            cursor.set(emitters[i]);
            double x = cursor.getX() + 0.5;
            double y = cursor.getY();
            double z = cursor.getZ() + 0.5;
            byte type = emitterTypes[i];
            boolean soul = type == TYPE_SOUL_FIRE || type == TYPE_SOUL_CAMPFIRE;
            boolean campfire = isCampfire(type);
            int light = LightCoordsUtil.FULL_BRIGHT;

            float emberRate = Math.min((campfire ? CAMPFIRE_EMBER_RATE : EMBER_RATE) * scale, emberLimit);
            int embers = ParticleBudget.countFromRate(emberRate, random.nextFloat());
            for (int e = 0; e < embers; e++) {
                queue.add(ParticleKind.EMBER, x + (random.nextFloat() - 0.5f) * 0.7f, y + 0.4 + random.nextFloat() * 0.5,
                    z + (random.nextFloat() - 0.5f) * 0.7f, (random.nextFloat() - 0.5f) * 0.04f, 0.04f + random.nextFloat() * 0.05f,
                    (random.nextFloat() - 0.5f) * 0.04f, 0.06f + random.nextFloat() * 0.04f, EMBER_LIFE + random.nextInt(EMBER_LIFE_SPREAD),
                    soul ? SOUL_EMBER_COLOR : EMBER_COLOR, light, random.nextInt());
            }
            // Campfires already send up vanilla's tall smoke; plain fire gets LiteLights' lit smoke.
            if (!campfire && random.nextFloat() < smokeRate) {
                double smokeY = y + 0.8 + random.nextFloat() * 0.3;
                queue.add(ParticleKind.SMOKE, x + (random.nextFloat() - 0.5f) * 0.5f, smokeY, z + (random.nextFloat() - 0.5f) * 0.5f,
                    (random.nextFloat() - 0.5f) * 0.02f, 0.03f + random.nextFloat() * 0.02f, (random.nextFloat() - 0.5f) * 0.02f,
                    0.22f + random.nextFloat() * 0.12f, SMOKE_LIFE + random.nextInt(SMOKE_LIFE_SPREAD),
                    soul ? SOUL_SMOKE_COLOR : LIT_SMOKE_COLOR, s.lightAt(x, smokeY, z), random.nextInt());
            }
        }
    }

    private static boolean isCampfire(byte type) {
        return type == TYPE_CAMPFIRE || type == TYPE_SOUL_CAMPFIRE;
    }

    private static boolean leavesMarks(byte type) {
        return type == TYPE_FIRE || type == TYPE_SOUL_FIRE;
    }

    /**
     * A block changed in the client's level (client thread). Keeps the known fires, the marks and
     * the veins up to date; costs a few comparisons when the change has nothing to do with fire.
     */
    void onBlockChanged(BlockPos position, BlockState oldState, BlockState newState) {
        if (!active || level == null) {
            return;
        }
        byte oldType = typeOf(oldState);
        byte newType = typeOf(newState);
        long key = position.asLong();
        veins.blockChanged(level, key, newState);
        if (oldType >= 0 || newType >= 0) {
            if (newType >= 0 && withinReach(position.getX(), position.getY(), position.getZ())) {
                if (fires.size() < MAX_FIRES) {
                    fires.put(key, newType);
                }
                scanned.put(key, newType);
            } else {
                fires.remove(key);
                scanned.remove(key);
            }
            if (oldType == TYPE_FIRE || newType == TYPE_FIRE) {
                veins.markDirty(gameTime);
            }
            if (newType >= 0 && leavesMarks(newType)) {
                marks.fireAt(level, key);
            } else if (oldType >= 0 && leavesMarks(oldType)) {
                marks.fireGone(key);
            }
        } else if (veins.touches(key) || touchesFire(key, newState)) {
            veins.markDirty(gameTime);
        }
        marks.blockChanged(level, position, newState);
    }

    /** A flammable block placed against a fire starts a chain the veins should follow. */
    private boolean touchesFire(long position, BlockState newState) {
        if (fires.isEmpty() || !((FireBlockInvoker) Blocks.FIRE).litelights$canBurn(newState)) {
            return false;
        }
        for (int face = 0; face < 6; face++) {
            long next = BlockPos.offset(position, EmberVeinReach.FACE_X[face], EmberVeinReach.FACE_Y[face], EmberVeinReach.FACE_Z[face]);
            if (fires.get(next) == TYPE_FIRE) {
                return true;
            }
        }
        return false;
    }

    /** Adds this frame's burn marks and ember veins to the batch (render thread, after the particles). */
    void drawDecals(ParticleRenderer renderer, ParticleSprites sprites, double camX, double camY, double camZ, float seconds) {
        if (!marks.isEmpty()) {
            marks.draw(renderer, sprites.frames(ParticleSprites.Decal.BURN_MARK), camX, camY, camZ);
        }
        if (!veins.isEmpty()) {
            veins.draw(renderer, sprites.frames(ParticleSprites.Decal.EMBER_VEIN), camX, camY, camZ, seconds);
        }
    }

    /** Reads the next few sections of the current pass; at the end of a pass, its fires take over. */
    private void scanStep(Surroundings s) {
        if (scanIndex == 0) {
            scanCentreX = SectionPos.blockToSectionCoord(s.cameraX);
            scanCentreY = SectionPos.blockToSectionCoord(s.cameraY);
            scanCentreZ = SectionPos.blockToSectionCoord(s.cameraZ);
            foundCount = 0;
            scanned.clear();
        }
        int end = Math.min(SECTIONS_PER_PASS, scanIndex + SECTIONS_PER_TICK);
        for (; scanIndex < end; scanIndex++) {
            int column = scanIndex / 3;
            int sectionX = scanCentreX - SECTION_REACH + column % SECTION_SPAN;
            int sectionZ = scanCentreZ - SECTION_REACH + column / SECTION_SPAN;
            int sectionY = scanCentreY - 1 + scanIndex % 3;
            ChunkAccess chunk = s.level.getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= chunk.getSections().length) {
                continue;
            }
            LevelChunkSection section = chunk.getSection(index);
            if (!section.hasOnlyAir() && section.maybeHas(MAY_BURN)) {
                scanSection(s, section, sectionX << 4, sectionY << 4, sectionZ << 4);
            }
        }
        if (scanIndex >= SECTIONS_PER_PASS) {
            finishPass(s);
        }
    }

    private void finishPass(Surroundings s) {
        scanIndex = 0;
        long[] positions = emitters;
        emitters = found;
        found = positions;
        byte[] types = emitterTypes;
        emitterTypes = foundTypes;
        foundTypes = types;
        emitterCount = foundCount;

        Long2ByteOpenHashMap previous = fires;
        fires = scanned;
        scanned = previous;
        if (plainFiresDiffer(previous, fires)) {
            veins.markDirty(gameTime);
        }
        marks.keepOnly(fires);
        for (ObjectIterator<Long2ByteMap.Entry> iterator = fires.long2ByteEntrySet().fastIterator(); iterator.hasNext(); ) {
            Long2ByteMap.Entry entry = iterator.next();
            if (leavesMarks(entry.getByteValue())) {
                marks.fireAt(s.level, entry.getLongKey());
            }
        }
    }

    /** Whether the plain fires (the ones that start veins) of two sets differ. */
    private static boolean plainFiresDiffer(Long2ByteOpenHashMap before, Long2ByteOpenHashMap after) {
        return !plainFiresAllIn(before, after) || !plainFiresAllIn(after, before);
    }

    /** Whether every plain fire of one set is a plain fire in the other too. */
    private static boolean plainFiresAllIn(Long2ByteOpenHashMap fireTypes, Long2ByteOpenHashMap other) {
        for (ObjectIterator<Long2ByteMap.Entry> iterator = fireTypes.long2ByteEntrySet().fastIterator(); iterator.hasNext(); ) {
            Long2ByteMap.Entry entry = iterator.next();
            if (entry.getByteValue() == TYPE_FIRE && other.get(entry.getLongKey()) != TYPE_FIRE) {
                return false;
            }
        }
        return true;
    }

    private void scanSection(Surroundings s, LevelChunkSection section, int baseX, int baseY, int baseZ) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    byte type = typeOf(state);
                    if (type >= 0) {
                        offer(s, baseX + x, baseY + y, baseZ + z, type);
                    }
                }
            }
        }
    }

    private static byte typeOf(BlockState state) {
        if (state.is(Blocks.FIRE)) {
            return TYPE_FIRE;
        }
        if (state.is(Blocks.SOUL_FIRE)) {
            return TYPE_SOUL_FIRE;
        }
        if (state.is(BlockTags.CAMPFIRES) && state.getValueOrElse(CampfireBlock.LIT, false)) {
            return state.is(Blocks.SOUL_CAMPFIRE) ? TYPE_SOUL_CAMPFIRE : TYPE_CAMPFIRE;
        }
        return -1;
    }

    private boolean withinReach(int x, int y, int z) {
        double dx = x + 0.5 - cameraX;
        double dy = y + 0.5 - cameraY;
        double dz = z + 0.5 - cameraZ;
        return dx * dx + dy * dy + dz * dz <= REACH_BLOCKS * REACH_BLOCKS;
    }

    /** Records a fire found by the scan, and keeps the nearest as emitters: once full, a nearer one replaces the furthest. */
    private void offer(Surroundings s, int x, int y, int z, byte type) {
        double dx = x + 0.5 - s.cameraX;
        double dy = y + 0.5 - s.cameraY;
        double dz = z + 0.5 - s.cameraZ;
        double distance = dx * dx + dy * dy + dz * dz;
        if (distance > REACH_BLOCKS * REACH_BLOCKS) {
            return;
        }
        long position = BlockPos.asLong(x, y, z);
        if (scanned.size() < MAX_FIRES) {
            scanned.put(position, type);
        }
        int slot = foundCount;
        if (foundCount == MAX_EMITTERS) {
            slot = 0;
            for (int i = 1; i < MAX_EMITTERS; i++) {
                if (foundDistance[i] > foundDistance[slot]) {
                    slot = i;
                }
            }
            if (foundDistance[slot] <= distance) {
                return;
            }
        } else {
            foundCount++;
        }
        found[slot] = position;
        foundTypes[slot] = type;
        foundDistance[slot] = distance;
    }
}
