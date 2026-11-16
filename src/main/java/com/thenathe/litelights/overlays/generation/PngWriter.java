package com.thenathe.litelights.overlays.generation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Encodes RGBA images as PNG in plain Java. Tiles are tiny, and doing it here keeps the worker
 * threads away from native image buffers.
 */
public final class PngWriter {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngWriter() {
    }

    /** @param argb pixels row by row, 0xAARRGGBB */
    public static byte[] encode(int[] argb, int width, int height) {
        try {
            // Each row starts with filter type 0 (none), then RGBA bytes.
            byte[] raw = new byte[height * (width * 4 + 1)];
            int at = 0;
            for (int y = 0; y < height; y++) {
                raw[at++] = 0;
                for (int x = 0; x < width; x++) {
                    int pixel = argb[y * width + x];
                    raw[at++] = (byte) (pixel >>> 16);
                    raw[at++] = (byte) (pixel >>> 8);
                    raw[at++] = (byte) pixel;
                    raw[at++] = (byte) (pixel >>> 24);
                }
            }
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(raw.length / 8 + 64);
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream stream = new DeflaterOutputStream(compressed, deflater)) {
                stream.write(raw);
            } finally {
                // Release the native zlib state now rather than whenever the deflater is collected.
                deflater.end();
            }

            ByteArrayOutputStream file = new ByteArrayOutputStream();
            file.write(SIGNATURE);
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            DataOutputStream headerData = new DataOutputStream(header);
            headerData.writeInt(width);
            headerData.writeInt(height);
            headerData.writeByte(8);
            headerData.writeByte(6);
            headerData.writeByte(0);
            headerData.writeByte(0);
            headerData.writeByte(0);
            writeChunk(file, "IHDR", header.toByteArray());
            writeChunk(file, "IDAT", compressed.toByteArray());
            writeChunk(file, "IEND", new byte[0]);
            return file.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory PNG encoding failed", impossible);
        }
    }

    private static void writeChunk(ByteArrayOutputStream file, String type, byte[] data) throws IOException {
        DataOutputStream out = new DataOutputStream(file);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
