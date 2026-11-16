package com.thenathe.litelights.render.feature.water.splash;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamsReflowingSprayTest {
    private static Properties settings(String... pairs) {
        Properties values = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            values.setProperty(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    @Test
    void itsDefaultsSpray() {
        assertTrue(StreamsReflowingSpray.spraysWith(settings()));
        assertTrue(StreamsReflowingSpray.spraysWith(settings("streamBubbles", "2", "particleDensity", "1.0")));
        assertTrue(StreamsReflowingSpray.spraysWith(settings("streamBubbles", "1")));
    }

    @Test
    void sprayTurnedOffGivesTheWaterfallsBack() {
        assertFalse(StreamsReflowingSpray.spraysWith(settings("streamBubbles", "0")));
        assertFalse(StreamsReflowingSpray.spraysWith(settings("streamBubbles", "2", "particleDensity", "0")));
    }

    @Test
    void unreadableValuesCountAsItsDefault() {
        assertTrue(StreamsReflowingSpray.spraysWith(settings("streamBubbles", "lots", "particleDensity", "?")));
    }
}
