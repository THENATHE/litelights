package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Only the game's own structured /seed reply is read; plain text never is. */
class SeedReplyTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theGamesReplyGivesItsSeed() {
        Component reply = Component.translatable("commands.seed.success", ComponentUtils.copyOnClickText("-4172144997902289642"));
        assertEquals(OptionalLong.of(-4172144997902289642L), SeedGeneration.seedReply(reply, 0));
        Component wrapped = Component.literal("[Server] ").append(reply);
        assertEquals(OptionalLong.of(-4172144997902289642L), SeedGeneration.seedReply(wrapped, 0));
    }

    @Test
    void plainTextIsNotRead() {
        assertFalse(SeedGeneration.seedReply(Component.literal("Seed: [-4172144997902289642]"), 0).isPresent());
        assertFalse(SeedGeneration.seedReply(Component.translatable("chat.type.text", "Steve", "my seed is 12345"), 0).isPresent());
    }
}
