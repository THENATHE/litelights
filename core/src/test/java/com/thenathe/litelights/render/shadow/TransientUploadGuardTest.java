package com.thenathe.litelights.render.shadow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransientUploadGuardTest {
    @Test
    void openGlWithoutPersistentMappingWaits() {
        // macOS's OpenGL 4.1, or any driver without ARB_buffer_storage.
        assertTrue(TransientUploadGuard.needsGuard("OpenGL", false));
    }

    @Test
    void openGlWithPersistentMappingDoesNothing() {
        // The RX 9070 on Mesa: nothing changes there.
        assertFalse(TransientUploadGuard.needsGuard("OpenGL", true));
    }

    @Test
    void vulkanDoesNothing() {
        assertFalse(TransientUploadGuard.needsGuard("Vulkan", false));
        assertFalse(TransientUploadGuard.needsGuard("Vulkan", true));
    }
}
