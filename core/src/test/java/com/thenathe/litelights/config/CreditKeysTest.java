package com.thenathe.litelights.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.render.modules.ModuleRows;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every row credit in the lang files belongs to an option that exists (credit-hearts-115), so an
 * option moved to another page or renamed can't leave its credit behind with no heart to show it.
 */
class CreditKeysTest {
    @TempDir
    static Path folder;

    @BeforeAll
    static void loadOptions() {
        if (!LiteLightsConfig.loaded()) {
            LiteLightsConfig.useFile(folder.resolve("litelights.json"));
        }
        LiteLightsConfig.init();
        ModuleRows.declareFarTerrain();
    }

    @Test
    void everyRowCreditBelongsToAnOption() throws IOException {
        Set<String> owned = new HashSet<>();
        for (Option<?> option : LiteLightsConfig.registry().all()) {
            if (option.creditKey() != null) {
                owned.add(option.creditKey());
            }
        }
        List<String> orphaned = new ArrayList<>();
        for (String file : new String[] {"/assets/litelights/lang/en_us.json", "/assets/litelights_farterrain/lang/en_us.json"}) {
            for (String key : english(file).keySet()) {
                if (key.endsWith(".credit") && !owned.contains(key)) {
                    orphaned.add(key);
                }
            }
        }
        assertEquals(List.of(), orphaned, "credits no option has");
        assertTrue(owned.size() >= 17, "the row credits are still there: " + owned.size());
    }

    private static JsonObject english(String file) throws IOException {
        try (var stream = CreditKeysTest.class.getResourceAsStream(file)) {
            assertNotNull(stream, file);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
