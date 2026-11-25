package com.thenathe.litelights.render.feature.farterrain.land;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * 16 by 16 tiles of one level in one file. The header lists every tile's {@link TileMeta} and the
 * length of its data, so opening a store reads headers only; a tile's data is read when first
 * needed and kept. Written whole, through a temporary file, so a crash mid-write leaves the old
 * file whole. Thread-safe (every method holds the region's lock).
 *
 * <pre>
 * int    magic 'LLFR'
 * short  format version (1)
 * byte   level
 * int    region x, region z
 * int    tile count
 * per tile: short index (x + z * 16), short lowest row, short highest row, long revision,
 *           4 x 32 shorts edge tops, int data length
 * then each tile's data ({@link TileCodec}), in header order
 * </pre>
 */
public final class RegionFile {
    public static final int SIZE = 16;
    public static final int MAGIC = 0x4C4C4652;
    public static final int VERSION = 1;

    private static final class Entry {
        TileMeta meta;
        byte[] data;
        long offset;
        int length;
    }

    private final Path file;
    private final int level;
    private final int regionX;
    private final int regionZ;
    private final Map<Integer, Entry> entries = new TreeMap<>();
    private boolean dirty;
    private long lastUsed;

    public RegionFile(Path file, int level, int regionX, int regionZ) {
        this.file = file;
        this.level = level;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    public static int regionOf(int tileCoordinate) {
        return Math.floorDiv(tileCoordinate, SIZE);
    }

    public static int indexOf(long key) {
        return Math.floorMod(TileKey.x(key), SIZE) + Math.floorMod(TileKey.z(key), SIZE) * SIZE;
    }

    public long keyOf(int index) {
        return TileKey.of(level, regionX * SIZE + index % SIZE, regionZ * SIZE + index / SIZE);
    }

    /** Reads the header: every tile's meta, no tile data. A missing file is an empty region. */
    public synchronized void readHeader() throws IOException {
        entries.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 16384))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Not a far terrain region: " + file);
            }
            int version = in.readShort();
            if (version != VERSION) {
                throw new IOException("Far terrain region version " + version + " is newer than this build reads: " + file);
            }
            int storedLevel = in.readByte();
            int storedX = in.readInt();
            int storedZ = in.readInt();
            if (storedLevel != level || storedX != regionX || storedZ != regionZ) {
                throw new IOException("Far terrain region holds another region: " + file);
            }
            int count = in.readInt();
            if (count < 0 || count > SIZE * SIZE) {
                throw new IOException("Far terrain region header is damaged: " + file);
            }
            long offset = headerBytes(count);
            for (int i = 0; i < count; i++) {
                int index = in.readShort();
                int lowest = in.readShort();
                int highest = in.readShort();
                long revision = in.readLong();
                int[][] tops = new int[4][TileVoxels.SIZE];
                for (int[] edge : tops) {
                    for (int j = 0; j < edge.length; j++) {
                        edge[j] = in.readShort();
                    }
                }
                int length = in.readInt();
                if (index < 0 || index >= SIZE * SIZE || length < 0) {
                    throw new IOException("Far terrain region header is damaged: " + file);
                }
                Entry entry = new Entry();
                entry.meta = new TileMeta(lowest, highest, new EdgeTops(tops), revision);
                entry.offset = offset;
                entry.length = length;
                entries.put(index, entry);
                offset += length;
            }
        }
    }

    private static long headerBytes(int count) {
        return 4 + 2 + 1 + 4 + 4 + 4 + (long) count * (2 + 2 + 2 + 8 + 4 * TileVoxels.SIZE * 2 + 4);
    }

    public synchronized Map<Long, TileMeta> metas() {
        Map<Long, TileMeta> metas = new TreeMap<>();
        for (Map.Entry<Integer, Entry> entry : entries.entrySet()) {
            metas.put(keyOf(entry.getKey()), entry.getValue().meta);
        }
        return metas;
    }

    /** A tile's stored data, or null if the region doesn't hold it. Reads it from disk the first time. */
    public synchronized byte[] data(long key) throws IOException {
        lastUsed = System.nanoTime();
        Entry entry = entries.get(indexOf(key));
        if (entry == null) {
            return null;
        }
        if (entry.data == null) {
            entry.data = readData(entry);
        }
        return entry.data;
    }

    private byte[] readData(Entry entry) throws IOException {
        byte[] data = new byte[entry.length];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            long position = entry.offset;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read < 0) {
                    throw new IOException("Far terrain region ends early: " + file);
                }
                position += read;
            }
        }
        return data;
    }

    public synchronized void put(long key, TileMeta meta, byte[] data) {
        lastUsed = System.nanoTime();
        Entry entry = entries.computeIfAbsent(indexOf(key), index -> new Entry());
        entry.meta = meta;
        entry.data = data;
        entry.length = data.length;
        dirty = true;
    }

    public synchronized boolean dirty() {
        return dirty;
    }

    /** Writes the whole region if anything changed. */
    public synchronized void save() throws IOException {
        if (!dirty) {
            return;
        }
        for (Entry entry : entries.values()) {
            if (entry.data == null) {
                entry.data = readData(entry);
            }
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream stream = Files.newOutputStream(temporary);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(stream, 65536))) {
            out.writeInt(MAGIC);
            out.writeShort(VERSION);
            out.writeByte(level);
            out.writeInt(regionX);
            out.writeInt(regionZ);
            out.writeInt(entries.size());
            for (Map.Entry<Integer, Entry> each : entries.entrySet()) {
                Entry entry = each.getValue();
                out.writeShort(each.getKey());
                out.writeShort(entry.meta.lowestRow());
                out.writeShort(entry.meta.highestRow());
                out.writeLong(entry.meta.revision());
                for (int edge = 0; edge < 4; edge++) {
                    for (int i = 0; i < TileVoxels.SIZE; i++) {
                        out.writeShort(entry.meta.edges().top(edge, i));
                    }
                }
                out.writeInt(entry.data.length);
            }
            for (Entry entry : entries.values()) {
                out.write(entry.data);
            }
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        long offset = headerBytes(entries.size());
        for (Entry entry : entries.values()) {
            entry.offset = offset;
            offset += entry.length;
        }
        dirty = false;
    }

    /** Lets go of tile data that is safely on disk; it is read again when needed. Returns the bytes let go. */
    public synchronized long dropData() {
        if (dirty) {
            return 0;
        }
        long freed = 0;
        for (Entry entry : entries.values()) {
            if (entry.data != null) {
                freed += entry.data.length;
                entry.data = null;
            }
        }
        return freed;
    }

    public synchronized long heldBytes() {
        long held = 0;
        for (Entry entry : entries.values()) {
            if (entry.data != null) {
                held += entry.data.length;
            }
        }
        return held;
    }

    public synchronized long lastUsed() {
        return lastUsed;
    }

    public synchronized int tileCount() {
        return entries.size();
    }
}
