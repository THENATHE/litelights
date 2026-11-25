package com.thenathe.litelights.overlays.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.overlays.generation.GeneratedFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** E10: an export that can't be written leaves no {@code .zip.part} in the resource pack folder. */
class PackExporterTest {
    @BeforeAll
    static void version() {
        SharedConstants.tryDetectVersion();
    }

    @Test
    void aFinishedExportIsOneZip(@TempDir Path folder) throws IOException {
        Path target = folder.resolve("export.zip");
        PackExporter.write(GeneratedFiles.EMPTY, target);
        assertEquals(List.of("export.zip"), names(folder));
        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertTrue(zip.getEntry("pack.mcmeta") != null && zip.getEntry("pack.png") != null);
        }
    }

    @Test
    void aFailedExportLeavesNothingBehind(@TempDir Path folder) throws IOException {
        // A folder of that name, with something in it, can't be replaced by the finished zip.
        Path target = folder.resolve("export.zip");
        Files.createDirectories(target);
        Files.writeString(target.resolve("in the way"), "");

        assertThrows(IOException.class, () -> PackExporter.write(GeneratedFiles.EMPTY, target));
        assertEquals(List.of("export.zip"), names(folder), "the .zip.part is deleted");
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
