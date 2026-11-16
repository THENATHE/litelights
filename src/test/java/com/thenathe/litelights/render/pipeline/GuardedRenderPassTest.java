package com.thenathe.litelights.render.pipeline;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import java.lang.reflect.Proxy;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardedRenderPassTest {
    /**
     * The game's render pass and command encoder as far as these tests need them: closing a pass
     * with a debug group open marks it closed and throws without ending it, so the encoder still has
     * a pass open; popping a closed pass throws.
     */
    private static final class FakeEncoder {
        boolean inRenderPass;

        FakePass open() {
            if (inRenderPass) {
                throw new IllegalStateException("Close the existing render pass before creating a new one!");
            }
            inRenderPass = true;
            return new FakePass(this);
        }
    }

    private static final class FakePass {
        final FakeEncoder encoder;
        int openGroups;
        int pops;
        boolean closed;
        final RenderPass api;

        FakePass(FakeEncoder encoder) {
            this.encoder = encoder;
            api = (RenderPass) Proxy.newProxyInstance(RenderPass.class.getClassLoader(), new Class<?>[] {RenderPass.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "pushDebugGroup" -> {
                            requireOpen();
                            openGroups++;
                        }
                        case "popDebugGroup" -> {
                            requireOpen();
                            if (openGroups == 0) {
                                throw new IllegalStateException("Can't pop more debug groups than was pushed!");
                            }
                            openGroups--;
                            pops++;
                        }
                        case "close" -> close();
                        default -> requireOpen();
                    }
                    return null;
                });
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Can't use a closed render pass");
            }
        }

        private void close() {
            if (!closed) {
                closed = true;
                if (openGroups > 0) {
                    throw new IllegalStateException("Render pass had debug groups left open!");
                }
                encoder.inRenderPass = false;
            }
        }
    }

    /** A replay pass like the mob shadow map's: forwards debug groups, drops every draw. */
    private static final class DroppingPass extends FilteringRenderPass {
        DroppingPass(RenderPass target) {
            begin(target);
        }

        @Override
        protected @Nullable CompiledRenderPipeline replacementFor(CompiledRenderPipeline pipeline) {
            return null;
        }
    }

    private static IllegalStateException drawThatThrows() {
        return new IllegalStateException("Render type wakes:wake_wake_tex_16_0 does not have OIT pipelines set up.");
    }

    @Test
    void theGamesPassAloneStaysOpenAfterAThrowWithAGroupOpen() {
        // What 0.1.6 did: the next pass the game opened failed and the game crashed.
        FakeEncoder encoder = new FakeEncoder();
        FakePass pass = encoder.open();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
            try (RenderPass open = pass.api) {
                open.pushDebugGroup(() -> "feature");
                throw drawThatThrows();
            }
        });
        assertEquals("Render pass had debug groups left open!", thrown.getSuppressed()[0].getMessage());
        assertTrue(encoder.inRenderPass);
        assertThrows(IllegalStateException.class, encoder::open);
    }

    @Test
    void aThrowInsideAnOwnedPassEndsItCleanly() {
        FakeEncoder encoder = new FakeEncoder();
        FakePass pass = encoder.open();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
            try (RenderPass guarded = GuardedRenderPass.own(pass.api)) {
                guarded.pushDebugGroup(() -> "feature");
                guarded.pushDebugGroup(() -> "render type");
                throw drawThatThrows();
            }
        });
        assertEquals(0, thrown.getSuppressed().length, "the pass closes without a second error");
        assertTrue(thrown.getMessage().contains("OIT pipelines"), "the draw's own error reaches the feature");
        assertTrue(pass.closed);
        assertEquals(0, pass.openGroups);
        assertFalse(encoder.inRenderPass);
        encoder.open();
    }

    @Test
    void groupsPushedThroughAReplayPassAreCountedToo() {
        FakeEncoder encoder = new FakeEncoder();
        FakePass pass = encoder.open();
        assertThrows(IllegalStateException.class, () -> {
            try (RenderPass guarded = GuardedRenderPass.own(pass.api)) {
                RenderPass replay = new DroppingPass(guarded);
                replay.pushDebugGroup(() -> "feature");
                throw drawThatThrows();
            }
        });
        assertFalse(encoder.inRenderPass);
    }

    @Test
    void aBalancedPassClosesWithoutExtraPops() {
        FakeEncoder encoder = new FakeEncoder();
        FakePass pass = encoder.open();
        try (GuardedRenderPass guarded = GuardedRenderPass.own(pass.api)) {
            guarded.pushDebugGroup(() -> "feature");
            guarded.popDebugGroup();
            assertEquals(0, guarded.openDebugGroups());
        }
        assertEquals(1, pass.pops);
        assertFalse(encoder.inRenderPass);
    }

    @Test
    void aBorrowedPassGetsBackOnlyWhatItCameWith() {
        FakeEncoder encoder = new FakeEncoder();
        FakePass gamesPass = encoder.open();
        gamesPass.api.pushDebugGroup(() -> "the game's own group");
        assertThrows(IllegalStateException.class, () -> {
            try (RenderPass guarded = GuardedRenderPass.borrow(gamesPass.api)) {
                guarded.pushDebugGroup(() -> "LiteLights clouds");
                throw drawThatThrows();
            }
        });
        assertFalse(gamesPass.closed, "the game closes its own pass");
        assertEquals(1, gamesPass.openGroups, "the game's own group is left for the game to pop");
        gamesPass.api.popDebugGroup();
        gamesPass.api.close();
        assertFalse(encoder.inRenderPass);
    }

    @Test
    void closingTwiceIsHarmless() {
        FakeEncoder encoder = new FakeEncoder();
        FakePass pass = encoder.open();
        GuardedRenderPass guarded = GuardedRenderPass.own(pass.api);
        guarded.pushDebugGroup(() -> "feature");
        guarded.close();
        guarded.close();
        assertEquals(1, pass.pops);
        assertFalse(encoder.inRenderPass);
    }

    @Test
    void aPopWithNothingPushedFailsAsTheGameWouldAndKeepsTheCount() {
        FakeEncoder encoder = new FakeEncoder();
        FakePass pass = encoder.open();
        GuardedRenderPass guarded = GuardedRenderPass.own(pass.api);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, guarded::popDebugGroup);
        assertSame(IllegalStateException.class, thrown.getClass());
        assertEquals(0, guarded.openDebugGroups());
        guarded.close();
        assertFalse(encoder.inRenderPass);
    }
}
