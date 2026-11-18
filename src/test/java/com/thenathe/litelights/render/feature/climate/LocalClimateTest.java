package com.thenathe.litelights.render.feature.climate;

import java.util.Set;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which climate source LiteLights picks for the mods installed, and how a seasons mod's answers are read. */
class LocalClimateTest {
    @Test
    void withoutASeasonsModTheBiomeDecidesAsBefore() {
        assertEquals(LocalClimate.Kind.VANILLA, LocalClimate.choose(Set.of()::contains));
        assertEquals(LocalClimate.Kind.VANILLA, LocalClimate.choose(Set.of("sodium", "fabric-api", "litelights")::contains));
    }

    @Test
    void almanacIsPickedFirst() {
        assertEquals(LocalClimate.Kind.ALMANAC, LocalClimate.choose(Set.of("almanac")::contains));
        assertEquals(LocalClimate.Kind.ALMANAC, LocalClimate.choose(Set.of("sereneseasons", "almanac")::contains),
            "even beside another seasons mod (Almanac refuses to load with one, but LiteLights doesn't count on it)");
    }

    @Test
    void otherSeasonsModsGoThroughTheGamesHookedLookup() {
        for (String id : LocalClimate.SEASONS_MOD_IDS) {
            assertEquals(LocalClimate.Kind.SEASONS_MOD, LocalClimate.choose(Set.of(id)::contains), id);
        }
        assertTrue(LocalClimate.SEASONS_MOD_IDS.contains("sereneseasons"), "Serene Seasons");
        assertTrue(LocalClimate.SEASONS_MOD_IDS.contains("seasons"), "Fabric Seasons");
    }

    @Test
    void whatFallsDecidesWhetherItIsColdEnoughForSnow() {
        assertTrue(SeasonsModClimate.snowsHere(Biome.Precipitation.SNOW, false), "winter snow on warm plains");
        assertFalse(SeasonsModClimate.snowsHere(Biome.Precipitation.RAIN, true), "summer rain on snowy plains");
        assertTrue(SeasonsModClimate.snowsHere(Biome.Precipitation.NONE, true), "dry: the biome's own word");
        assertFalse(SeasonsModClimate.snowsHere(Biome.Precipitation.NONE, false));
    }
}
