package com.thenathe.litelights.render.feature.farterrain.land;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * A tile's voxels on disk. Every tile starts with the format version, so a later format can read
 * this one and convert it (upgrades never lose data). Version 2 (version 1 is the same, but its
 * coarse tiles' solid voxels have no insets in their light bytes, so they read without them):
 *
 * <pre>
 * byte   version (1)
 * byte   level
 * int    minY
 * short  height (voxel rows)
 * then, Deflate-compressed:
 *   16 longs   which columns were seen
 *   1024 shorts the biome of each column
 *   per column (x fastest, then z), bottom to top: runs of (state id, length) as varints
 *   per column, bottom to top: runs of (light byte, length), the length a varint
 * </pre>
 *
 * Runs follow the columns upward, where land is most alike: a typical seen chunk is a few
 * kilobytes. Unseen columns are a single run of air.
 */
public final class TileCodec {
    public static final int VERSION = 2;
    /** The first version, before coarse voxels kept their insets ({@link TileVoxels#inset}). */
    static final int VERSION_WITHOUT_INSETS = 1;
    private static final int HEADER_BYTES = 8;
    /** Fast Deflate: the runs have already taken out most of what it would find. */
    private static final int COMPRESSION = 2;

    private TileCodec() {
    }

    public static byte[] encode(TileVoxels tile) {
        // The body is built in a plain array and compressed in one go: streams written a byte at a
        // time cost several times the work itself.
        Body body = new Body(64 * 1024);
        for (long word : tile.known) {
            body.writeLong(word);
        }
        for (short biome : tile.biomes) {
            body.writeShort(biome);
        }
        for (int column = 0; column < TileVoxels.COLUMNS; column++) {
            writeStateRuns(body, tile, column);
        }
        for (int column = 0; column < TileVoxels.COLUMNS; column++) {
            writeLightRuns(body, tile, column);
        }
        Deflater deflater = new Deflater(COMPRESSION);
        try {
            deflater.setInput(body.bytes, 0, body.length);
            deflater.finish();
            byte[] out = new byte[HEADER_BYTES + body.length / 4 + 64];
            out[0] = (byte) VERSION;
            out[1] = (byte) tile.level;
            out[2] = (byte) (tile.minY >>> 24);
            out[3] = (byte) (tile.minY >>> 16);
            out[4] = (byte) (tile.minY >>> 8);
            out[5] = (byte) tile.minY;
            out[6] = (byte) (tile.height >>> 8);
            out[7] = (byte) tile.height;
            int length = HEADER_BYTES;
            while (!deflater.finished()) {
                if (length == out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                length += deflater.deflate(out, length, out.length - length);
            }
            return Arrays.copyOf(out, length);
        } finally {
            deflater.end();
        }
    }

    private static void writeStateRuns(Body out, TileVoxels tile, int column) {
        int y = 0;
        while (y < tile.height) {
            int state = tile.states[column + y * TileVoxels.COLUMNS];
            int run = 1;
            while (y + run < tile.height && tile.states[column + (y + run) * TileVoxels.COLUMNS] == state) {
                run++;
            }
            out.writeVarInt(state);
            out.writeVarInt(run);
            y += run;
        }
    }

    private static void writeLightRuns(Body out, TileVoxels tile, int column) {
        int y = 0;
        while (y < tile.height) {
            byte light = tile.light[column + y * TileVoxels.COLUMNS];
            int run = 1;
            while (y + run < tile.height && tile.light[column + (y + run) * TileVoxels.COLUMNS] == light) {
                run++;
            }
            out.writeByte(light);
            out.writeVarInt(run);
            y += run;
        }
    }

    /** A growable byte array written big-endian, as DataOutputStream would. */
    private static final class Body {
        byte[] bytes;
        int length;

        Body(int capacity) {
            bytes = new byte[capacity];
        }

        private void room(int more) {
            if (length + more > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + more));
            }
        }

        void writeByte(int value) {
            room(1);
            bytes[length++] = (byte) value;
        }

        void writeShort(int value) {
            room(2);
            bytes[length++] = (byte) (value >>> 8);
            bytes[length++] = (byte) value;
        }

        void writeLong(long value) {
            room(8);
            for (int shift = 56; shift >= 0; shift -= 8) {
                bytes[length++] = (byte) (value >>> shift);
            }
        }

        void writeVarInt(int value) {
            room(5);
            while ((value & ~0x7F) != 0) {
                bytes[length++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            bytes[length++] = (byte) value;
        }
    }

    /** Reads a tile written by any version this build knows. */
    public static TileVoxels decode(byte[] data) throws IOException {
        if (data.length < 8) {
            throw new IOException("Far terrain tile too short: " + data.length + " bytes");
        }
        int version = data[0] & 0xFF;
        if (version != VERSION && version != VERSION_WITHOUT_INSETS) {
            throw new IOException("Far terrain tile version " + version + " is newer than this build reads (" + VERSION + ")");
        }
        int level = data[1];
        int minY = (data[2] & 0xFF) << 24 | (data[3] & 0xFF) << 16 | (data[4] & 0xFF) << 8 | (data[5] & 0xFF);
        int height = (data[6] & 0xFF) << 8 | (data[7] & 0xFF);
        if (level < 0 || level > TileKey.MAX_LEVEL || height <= 0 || height > 4096) {
            throw new IOException("Far terrain tile header is damaged (level " + level + ", height " + height + ")");
        }
        TileVoxels tile = new TileVoxels(level, minY, height);
        tile.hasInsets = version >= VERSION;
        byte[] body;
        Inflater inflater = new Inflater();
        try (InflaterInputStream inflating = new InflaterInputStream(new ByteArrayInputStream(data, 8, data.length - 8), inflater, 16384)) {
            body = inflating.readAllBytes();
        } finally {
            inflater.end();
        }
        Reader in = new Reader(body);
        for (int i = 0; i < tile.known.length; i++) {
            tile.known[i] = in.readLong();
        }
        for (int i = 0; i < TileVoxels.COLUMNS; i++) {
            tile.biomes[i] = (short) in.readShort();
        }
        for (int column = 0; column < TileVoxels.COLUMNS; column++) {
            int y = 0;
            while (y < height) {
                int state = in.readVarInt();
                int run = readRun(in, height - y);
                for (int i = 0; i < run; i++) {
                    tile.states[column + (y + i) * TileVoxels.COLUMNS] = state;
                }
                y += run;
            }
        }
        for (int column = 0; column < TileVoxels.COLUMNS; column++) {
            int y = 0;
            while (y < height) {
                byte light = (byte) in.readByte();
                int run = readRun(in, height - y);
                for (int i = 0; i < run; i++) {
                    tile.light[column + (y + i) * TileVoxels.COLUMNS] = light;
                }
                y += run;
            }
        }
        return tile;
    }

    private static int readRun(Reader in, int room) throws IOException {
        int run = in.readVarInt();
        if (run <= 0 || run > room) {
            throw new IOException("Far terrain tile run of " + run + " doesn't fit its column");
        }
        return run;
    }

    /** Reads the body big-endian, as DataInputStream would, straight from the array. */
    private static final class Reader {
        private final byte[] bytes;
        private int position;

        Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        int readByte() throws IOException {
            if (position >= bytes.length) {
                throw new IOException("Far terrain tile ends early");
            }
            return bytes[position++] & 0xFF;
        }

        int readShort() throws IOException {
            return readByte() << 8 | readByte();
        }

        long readLong() throws IOException {
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = value << 8 | readByte();
            }
            return value;
        }

        int readVarInt() throws IOException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = readByte();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("Far terrain tile has a number that is too long");
        }
    }
}
