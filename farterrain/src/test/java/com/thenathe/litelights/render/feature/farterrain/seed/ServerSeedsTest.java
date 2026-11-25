package com.thenathe.litelights.render.feature.farterrain.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The per-server seed store: every signed 64-bit seed kept exactly, an unreadable file kept, nothing shown. */
class ServerSeedsTest {
    @Test
    void seedsSurviveAndKeepEveryBit(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("server-seeds.json");
        ServerSeeds seeds = new ServerSeeds(file);
        seeds.putSeed("Play.Example.org", Long.MIN_VALUE, ServerSeeds.Source.TYPED);
        seeds.putSeed("[::1]:25570", -1L, ServerSeeds.Source.SEED_COMMAND);
        seeds.setChoice("play.example.org:25565", ServerSeeds.Choice.ALWAYS);

        ServerSeeds again = new ServerSeeds(file);
        ServerSeeds.Entry example = again.get("play.example.org.");
        assertNotNull(example);
        assertEquals(Long.MIN_VALUE, example.seed());
        assertEquals(ServerSeeds.Choice.ALWAYS, example.choice());
        assertEquals(-1L, again.get("[::1]:25570").seed());
        assertNull(again.get("play.example.org:25566"), "another port is another server");
        // Kept as text, so JSON tools that read numbers as doubles can't round it.
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"-9223372036854775808\""));
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        again.forget("play.example.org");
        assertNull(new ServerSeeds(file).get("play.example.org"));
    }

    @Test
    void anUnreadableFileIsKept(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("server-seeds.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        ServerSeeds seeds = new ServerSeeds(file);
        assertNotNull(seeds.problem());
        seeds.putSeed("example.org", 42L, ServerSeeds.Source.TYPED);
        assertEquals(42L, seeds.get("example.org").seed(), "kept for this session");
        assertEquals("{ not json", Files.readString(file, StandardCharsets.UTF_8), "the file was not written over");
    }

    @Test
    void typedSeedsAreWholeNumbersOnly() {
        assertEquals(OptionalLong.of(-4172144997902289642L), ServerSeeds.parse(" -4172144997902289642 "));
        assertEquals(OptionalLong.of(Long.MAX_VALUE), ServerSeeds.parse("+9223372036854775807"));
        assertFalse(ServerSeeds.parse("9223372036854775808").isPresent(), "past a long");
        assertFalse(ServerSeeds.parse("hello").isPresent(), "text seeds are hashed by the game when a world is made; a server's /seed shows the number");
        assertFalse(ServerSeeds.parse("").isPresent());
    }

    @Test
    void entriesNeverShowTheSeed() {
        ServerSeeds.Entry entry = new ServerSeeds.Entry(1234567890123L, ServerSeeds.Source.TYPED, ServerSeeds.Choice.ASK);
        assertFalse(entry.toString().contains("1234567890123"));
    }
}
