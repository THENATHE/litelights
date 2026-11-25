package com.thenathe.litelights.render.feature.farterrain.land;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One dimension's far terrain on disk: the block and biome name tables and a folder of region
 * files per level ({@code L0} to {@code L7}). Opening reads the tables and every region's header,
 * so what is stored where is known at once and tiles are decoded only when drawn. Tile data read
 * from disk stays in memory up to a budget ({@link #trim}).
 *
 * <p>Writes come from one thread (the store worker); reads from any. The name tables are written
 * before the regions on every flush, so a stored id always has its name on disk.
 */
public final class FarStore {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.dat");

    private final Path folder;
    private final NameTable blocks = new NameTable("minecraft:air");
    private final NameTable biomes = new NameTable("minecraft:plains");
    private final Map<Long, RegionFile> regions = new ConcurrentHashMap<>();
    private final Map<Long, TileMeta> metas = new ConcurrentHashMap<>();
    /** Per level, the tiles that hold data themselves or somewhere under them. */
    private final List<Set<Long>> present = new ArrayList<>();
    private final AtomicLong revisions = new AtomicLong();
    private volatile long diskBytes;

    public FarStore(Path folder) {
        this.folder = folder;
        for (int level = 0; level <= TileKey.MAX_LEVEL; level++) {
            present.add(ConcurrentHashMap.newKeySet());
        }
    }

    public Path folder() {
        return folder;
    }

    public NameTable blocks() {
        return blocks;
    }

    public NameTable biomes() {
        return biomes;
    }

    /** Reads the tables and every region header. A store that doesn't exist yet opens empty. */
    public void open() throws IOException {
        blocks.load(folder.resolve("blocks.txt"));
        biomes.load(folder.resolve("biomes.txt"));
        long highestRevision = 0;
        for (int level = 0; level <= TileKey.MAX_LEVEL; level++) {
            Path levelFolder = levelFolder(level);
            if (!Files.isDirectory(levelFolder)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(levelFolder, "r.*.dat")) {
                for (Path file : files) {
                    Matcher name = REGION_NAME.matcher(file.getFileName().toString());
                    if (!name.matches()) {
                        continue;
                    }
                    int regionX = Integer.parseInt(name.group(1));
                    int regionZ = Integer.parseInt(name.group(2));
                    RegionFile region = new RegionFile(file, level, regionX, regionZ);
                    region.readHeader();
                    regions.put(TileKey.of(level, regionX, regionZ), region);
                    for (Map.Entry<Long, TileMeta> entry : region.metas().entrySet()) {
                        metas.put(entry.getKey(), entry.getValue());
                        markPresent(entry.getKey());
                        highestRevision = Math.max(highestRevision, entry.getValue().revision());
                    }
                }
            }
        }
        revisions.set(highestRevision);
        diskBytes = measureDisk();
    }

    private Path levelFolder(int level) {
        return folder.resolve("L" + level);
    }

    private RegionFile region(long key, boolean create) {
        int level = TileKey.level(key);
        int regionX = RegionFile.regionOf(TileKey.x(key));
        int regionZ = RegionFile.regionOf(TileKey.z(key));
        long regionKey = TileKey.of(level, regionX, regionZ);
        RegionFile region = regions.get(regionKey);
        if (region == null && create) {
            region = regions.computeIfAbsent(regionKey, ignored ->
                new RegionFile(levelFolder(level).resolve("r." + regionX + "." + regionZ + ".dat"), level, regionX, regionZ));
        }
        return region;
    }

    private void markPresent(long key) {
        long tile = key;
        for (int level = TileKey.level(key); level <= TileKey.MAX_LEVEL; level++) {
            if (!present.get(level).add(tile)) {
                return;
            }
            tile = TileKey.parent(tile);
        }
    }

    public TileMeta meta(long key) {
        return metas.get(key);
    }

    /** Whether this tile, or any tile under it, holds data. */
    public boolean present(long key) {
        return present.get(TileKey.level(key)).contains(key);
    }

    public int tileCount() {
        return metas.size();
    }

    public int tileCount(int level) {
        int count = 0;
        for (Long key : metas.keySet()) {
            if (TileKey.level(key) == level) {
                count++;
            }
        }
        return count;
    }

    /** The tile's voxels, or null if nothing is stored for it. */
    public TileVoxels read(long key) throws IOException {
        RegionFile region = region(key, false);
        byte[] data = region == null ? null : region.data(key);
        return data == null ? null : TileCodec.decode(data);
    }

    /** Stores a tile and returns its new meta. */
    public TileMeta write(long key, TileVoxels tile, BlockKinds kinds) {
        TileMeta meta = TileMeta.of(tile, kinds, revisions.incrementAndGet());
        region(key, true).put(key, meta, TileCodec.encode(tile));
        metas.put(key, meta);
        markPresent(key);
        return meta;
    }

    /** Writes the name tables, then every region that changed. */
    public void flush() throws IOException {
        if (blocks.changedSinceSave()) {
            blocks.save(folder.resolve("blocks.txt"));
        }
        if (biomes.changedSinceSave()) {
            biomes.save(folder.resolve("biomes.txt"));
        }
        boolean wrote = false;
        for (RegionFile region : regions.values()) {
            if (region.dirty()) {
                region.save();
                wrote = true;
            }
        }
        if (wrote) {
            diskBytes = measureDisk();
        }
    }

    /** Lets go of the tile data read from disk, oldest first, until at most {@code budget} bytes are held. */
    public long trim(long budget) {
        List<RegionFile> held = new ArrayList<>(regions.values());
        long total = 0;
        for (RegionFile region : held) {
            total += region.heldBytes();
        }
        if (total <= budget) {
            return total;
        }
        held.sort(Comparator.comparingLong(RegionFile::lastUsed));
        for (RegionFile region : held) {
            if (total <= budget) {
                break;
            }
            total -= region.dropData();
        }
        return total;
    }

    public long heldBytes() {
        long total = 0;
        for (RegionFile region : regions.values()) {
            total += region.heldBytes();
        }
        return total;
    }

    /** Bytes on disk, as of the last open or flush. */
    public long diskBytes() {
        return diskBytes;
    }

    private long measureDisk() {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException gone) {
                    return 0L;
                }
            }).sum();
        } catch (IOException unreadable) {
            return diskBytes;
        }
    }

    /** Deletes everything stored for this dimension. Nothing may use the store afterwards. */
    public void delete() throws IOException {
        regions.clear();
        metas.clear();
        for (Set<Long> level : present) {
            level.clear();
        }
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
        diskBytes = 0;
    }
}
