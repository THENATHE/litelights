package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.LiteMath;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBiomeTags;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3fc;

/**
 * LiteLights' own table of biome atmospheres, and how biomes it doesn't know get one. The particles'
 * {@link com.thenathe.litelights.render.feature.particles.BiomeClimate} sorts modded biomes by the
 * same Conventional tags in the same order; keep the two in step.
 *
 * <p>Every colour here was chosen for LiteLights: overworld hazes stay pale and close to the
 * vanilla sky so distant land fades the way it does in real air, each family leaning a little
 * toward its ground (sand, foliage, snow, water). Nether hazes are darker and stronger because the
 * Nether has no sky to fade into.
 */
public final class BiomeAtmospheres {
    // Overworld families. Density is a multiple of BiomeAtmosphere.OVERWORLD_DENSITY.
    static final BiomeAtmosphere TEMPERATE = BiomeAtmosphere.overworld(0xBCD2EE, 1.00f, 0.35f);
    static final BiomeAtmosphere MEADOW = BiomeAtmosphere.overworld(0xC2D6EC, 0.95f, 0.45f);
    static final BiomeAtmosphere FOREST = BiomeAtmosphere.overworld(0xB4CCDB, 1.15f, 0.50f).withSkyTint(0.98f, 1.00f, 1.00f);
    static final BiomeAtmosphere DARK_FOREST = BiomeAtmosphere.overworld(0x9DB2B4, 1.35f, 0.70f).withSkyTint(0.95f, 0.98f, 0.97f);
    static final BiomeAtmosphere CHERRY = BiomeAtmosphere.overworld(0xD9CCE2, 1.05f, 0.45f).withSkyTint(1.03f, 0.98f, 1.03f);
    static final BiomeAtmosphere TAIGA = BiomeAtmosphere.overworld(0xAFC7D9, 1.20f, 0.60f).withSkyTint(0.97f, 1.00f, 1.03f);
    static final BiomeAtmosphere SNOWY = BiomeAtmosphere.overworld(0xD3E1F2, 1.15f, 0.30f).withSkyTint(0.96f, 1.00f, 1.06f);
    static final BiomeAtmosphere PEAKS = BiomeAtmosphere.overworld(0xC3D4EC, 0.85f, 0.20f).withSkyTint(0.97f, 1.00f, 1.04f)
        .withWind(1.0f);
    static final BiomeAtmosphere WINDSWEPT = BiomeAtmosphere.overworld(0xBACFE6, 0.95f, 0.30f).withWind(1.0f);
    static final BiomeAtmosphere DESERT = BiomeAtmosphere.overworld(0xE6D6B6, 1.25f, 0.05f)
        .withSkyTint(1.06f, 1.00f, 0.90f).withStorms(1.0f, 1.0f, 0.0f).withWind(1.0f);
    static final BiomeAtmosphere BADLANDS = BiomeAtmosphere.overworld(0xE2BA98, 1.15f, 0.05f)
        .withSkyTint(1.08f, 0.97f, 0.88f).withStorms(1.0f, 0.0f, 0.0f).withWind(1.0f);
    static final BiomeAtmosphere SAVANNA = BiomeAtmosphere.overworld(0xDCD2B0, 1.20f, 0.10f)
        .withSkyTint(1.04f, 1.00f, 0.92f).withStorms(0.8f, 0.0f, 0.0f);
    static final BiomeAtmosphere WINDSWEPT_SAVANNA = SAVANNA.withWind(1.0f);
    static final BiomeAtmosphere JUNGLE = BiomeAtmosphere.overworld(0xAFCFC0, 1.45f, 0.85f).withSkyTint(0.96f, 1.02f, 0.98f);
    static final BiomeAtmosphere SWAMP = BiomeAtmosphere.overworld(0x9EB095, 1.60f, 0.90f)
        .withSkyTint(0.94f, 0.98f, 0.90f).withStorms(0.0f, 0.0f, 1.0f);
    static final BiomeAtmosphere MANGROVE = BiomeAtmosphere.overworld(0x9AB39E, 1.60f, 0.90f)
        .withSkyTint(0.94f, 0.99f, 0.93f).withStorms(0.0f, 0.0f, 1.0f);
    static final BiomeAtmosphere PALE_GARDEN = BiomeAtmosphere.overworld(0xACB1B1, 1.90f, 0.85f)
        .withSkyTint(0.90f, 0.92f, 0.93f).withStorms(0.0f, 0.0f, 1.0f);
    static final BiomeAtmosphere OCEAN = BiomeAtmosphere.overworld(0xB5D2F2, 1.20f, 0.25f).withSkyTint(0.96f, 1.00f, 1.05f);
    static final BiomeAtmosphere WARM_OCEAN = BiomeAtmosphere.overworld(0xBDE0F0, 1.15f, 0.20f).withSkyTint(0.95f, 1.02f, 1.04f);
    static final BiomeAtmosphere COLD_OCEAN = BiomeAtmosphere.overworld(0xC4D5EE, 1.20f, 0.30f).withSkyTint(0.96f, 1.00f, 1.06f);
    static final BiomeAtmosphere BEACH = BiomeAtmosphere.overworld(0xC9DAEE, 1.05f, 0.25f);
    static final BiomeAtmosphere MUSHROOM = BiomeAtmosphere.overworld(0xD1BDDA, 1.30f, 0.60f).withSkyTint(1.03f, 0.95f, 1.05f);
    static final BiomeAtmosphere LUSH_CAVES = BiomeAtmosphere.overworld(0x8FAE88, 1.20f, 0.40f);
    static final BiomeAtmosphere STONE_CAVES = BiomeAtmosphere.overworld(0x8E8880, 1.10f, 0.10f);
    static final BiomeAtmosphere SULFUR_CAVES = BiomeAtmosphere.overworld(0xA6A274, 1.30f, 0.20f);
    static final BiomeAtmosphere DEEP_DARK = BiomeAtmosphere.overworld(0x3A4650, 1.40f, 0.10f);

    // Dimensions without a sky: absolute densities per block.
    static final BiomeAtmosphere NETHER_WASTES = BiomeAtmosphere.skyless(0x4E1A10, 0.0125f);
    static final BiomeAtmosphere CRIMSON_FOREST = BiomeAtmosphere.skyless(0x570E12, 0.0135f);
    static final BiomeAtmosphere WARPED_FOREST = BiomeAtmosphere.skyless(0x1C2C33, 0.0115f);
    static final BiomeAtmosphere SOUL_SAND_VALLEY = BiomeAtmosphere.skyless(0x264A4C, 0.0140f);
    static final BiomeAtmosphere BASALT_DELTAS = BiomeAtmosphere.skyless(0x6E6872, 0.0200f);
    static final BiomeAtmosphere END = BiomeAtmosphere.skyless(0x1E1626, 0.0040f);

    private static final Map<ResourceKey<Biome>, BiomeAtmosphere> VANILLA = Map.ofEntries(
        Map.entry(Biomes.PLAINS, TEMPERATE),
        Map.entry(Biomes.SUNFLOWER_PLAINS, TEMPERATE),
        Map.entry(Biomes.RIVER, TEMPERATE.withDensity(BiomeAtmosphere.OVERWORLD_DENSITY * 1.10f)),
        Map.entry(Biomes.MEADOW, MEADOW),
        Map.entry(Biomes.FOREST, FOREST),
        Map.entry(Biomes.FLOWER_FOREST, FOREST),
        Map.entry(Biomes.BIRCH_FOREST, FOREST),
        Map.entry(Biomes.OLD_GROWTH_BIRCH_FOREST, FOREST),
        Map.entry(Biomes.DAPPLED_FOREST, FOREST),
        Map.entry(Biomes.DARK_FOREST, DARK_FOREST),
        Map.entry(Biomes.CHERRY_GROVE, CHERRY),
        Map.entry(Biomes.TAIGA, TAIGA),
        Map.entry(Biomes.OLD_GROWTH_PINE_TAIGA, TAIGA),
        Map.entry(Biomes.OLD_GROWTH_SPRUCE_TAIGA, TAIGA),
        Map.entry(Biomes.SNOWY_TAIGA, SNOWY),
        Map.entry(Biomes.SNOWY_PLAINS, SNOWY),
        Map.entry(Biomes.ICE_SPIKES, SNOWY),
        Map.entry(Biomes.GROVE, SNOWY),
        Map.entry(Biomes.SNOWY_SLOPES, SNOWY),
        Map.entry(Biomes.SNOWY_BEACH, SNOWY),
        Map.entry(Biomes.FROZEN_RIVER, SNOWY),
        Map.entry(Biomes.FROZEN_PEAKS, PEAKS),
        Map.entry(Biomes.JAGGED_PEAKS, PEAKS),
        Map.entry(Biomes.STONY_PEAKS, PEAKS),
        Map.entry(Biomes.WINDSWEPT_HILLS, WINDSWEPT),
        Map.entry(Biomes.WINDSWEPT_GRAVELLY_HILLS, WINDSWEPT),
        Map.entry(Biomes.WINDSWEPT_FOREST, WINDSWEPT),
        Map.entry(Biomes.WINDSWEPT_SAVANNA, WINDSWEPT_SAVANNA),
        Map.entry(Biomes.STONY_SHORE, BEACH),
        Map.entry(Biomes.BEACH, BEACH),
        Map.entry(Biomes.DESERT, DESERT),
        Map.entry(Biomes.BADLANDS, BADLANDS),
        Map.entry(Biomes.ERODED_BADLANDS, BADLANDS),
        Map.entry(Biomes.WOODED_BADLANDS, BADLANDS),
        Map.entry(Biomes.SAVANNA, SAVANNA),
        Map.entry(Biomes.SAVANNA_PLATEAU, SAVANNA),
        Map.entry(Biomes.JUNGLE, JUNGLE),
        Map.entry(Biomes.SPARSE_JUNGLE, JUNGLE.withDensity(BiomeAtmosphere.OVERWORLD_DENSITY * 1.25f)),
        Map.entry(Biomes.BAMBOO_JUNGLE, JUNGLE),
        Map.entry(Biomes.SWAMP, SWAMP),
        Map.entry(Biomes.MANGROVE_SWAMP, MANGROVE),
        Map.entry(Biomes.PALE_GARDEN, PALE_GARDEN),
        Map.entry(Biomes.OCEAN, OCEAN),
        Map.entry(Biomes.DEEP_OCEAN, OCEAN),
        Map.entry(Biomes.LUKEWARM_OCEAN, OCEAN),
        Map.entry(Biomes.DEEP_LUKEWARM_OCEAN, OCEAN),
        Map.entry(Biomes.WARM_OCEAN, WARM_OCEAN),
        Map.entry(Biomes.COLD_OCEAN, COLD_OCEAN),
        Map.entry(Biomes.DEEP_COLD_OCEAN, COLD_OCEAN),
        Map.entry(Biomes.FROZEN_OCEAN, COLD_OCEAN),
        Map.entry(Biomes.DEEP_FROZEN_OCEAN, COLD_OCEAN),
        Map.entry(Biomes.MUSHROOM_FIELDS, MUSHROOM),
        Map.entry(Biomes.LUSH_CAVES, LUSH_CAVES),
        Map.entry(Biomes.DRIPSTONE_CAVES, STONE_CAVES),
        Map.entry(Biomes.SULFUR_CAVES, SULFUR_CAVES),
        Map.entry(Biomes.DEEP_DARK, DEEP_DARK),
        Map.entry(Biomes.NETHER_WASTES, NETHER_WASTES),
        Map.entry(Biomes.CRIMSON_FOREST, CRIMSON_FOREST),
        Map.entry(Biomes.WARPED_FOREST, WARPED_FOREST),
        Map.entry(Biomes.SOUL_SAND_VALLEY, SOUL_SAND_VALLEY),
        Map.entry(Biomes.BASALT_DELTAS, BASALT_DELTAS),
        Map.entry(Biomes.THE_END, END),
        Map.entry(Biomes.END_HIGHLANDS, END),
        Map.entry(Biomes.END_MIDLANDS, END),
        Map.entry(Biomes.SMALL_END_ISLANDS, END),
        Map.entry(Biomes.END_BARRENS, END),
        Map.entry(Biomes.THE_VOID, TEMPERATE)
    );

    /** Overworld families for biomes from other mods, matched by their tags in this order. */
    private static final Map<TagKey<Biome>, BiomeAtmosphere> FAMILIES_BY_TAG = familiesByTag();

    private final Map<Biome, BiomeAtmosphere> cache = new IdentityHashMap<>();
    private boolean moddedColours = true;

    /** Forgets every looked-up biome: the registry or the setting changed. */
    public void clear() {
        cache.clear();
    }

    public void setModdedColours(boolean on) {
        if (on != moddedColours) {
            moddedColours = on;
            cache.clear();
        }
    }

    /** The atmosphere of a biome in a dimension, from the table or worked out from the biome. */
    public BiomeAtmosphere of(Holder<Biome> biome, DimensionType dimension) {
        BiomeAtmosphere known = cache.get(biome.value());
        if (known == null) {
            known = lookUp(biome, dimension);
            cache.put(biome.value(), known);
        }
        return known;
    }

    private BiomeAtmosphere lookUp(Holder<Biome> biome, DimensionType dimension) {
        BiomeAtmosphere vanilla = biome.unwrapKey().map(VANILLA::get).orElse(null);
        if (vanilla != null) {
            return vanilla;
        }
        Biome value = biome.value();
        int fog = rgbOf(value.getAttributes().applyModifier(EnvironmentAttributes.FOG_COLOR,
            dimension.attributes().applyModifier(EnvironmentAttributes.FOG_COLOR, EnvironmentAttributes.FOG_COLOR.defaultValue())));
        int sky = rgbOf(value.getAttributes().applyModifier(EnvironmentAttributes.SKY_COLOR,
            dimension.attributes().applyModifier(EnvironmentAttributes.SKY_COLOR, EnvironmentAttributes.SKY_COLOR.defaultValue())));
        int water = value.getWaterColor() & 0xFFFFFF;

        if (!dimension.hasSkyLight()) {
            // A cave or Nether-like dimension: its own fog colour, as thick as its own fog distance asks.
            float fogEnd = value.getAttributes().applyModifier(EnvironmentAttributes.FOG_END_DISTANCE,
                dimension.attributes().applyModifier(EnvironmentAttributes.FOG_END_DISTANCE, EnvironmentAttributes.FOG_END_DISTANCE.defaultValue()));
            float density = Math.clamp(2.0f / Math.max(fogEnd, 1.0f), 0.003f, 0.05f);
            int haze = moddedColours ? SkyFogMath.derivedHazeColor(fog, fog, water) : 0x8A6A60;
            return BiomeAtmosphere.skyless(darkenForSkyless(haze), density);
        }

        BiomeAtmosphere family = familyOf(biome);
        if (!moddedColours) {
            return TEMPERATE.withDensity(family.density()).withWeatherOf(family);
        }
        int derived = SkyFogMath.derivedHazeColor(fog, sky, water);
        if (family == TEMPERATE) {
            return family.withHaze(derived);
        }
        // A family LiteLights knows: keep its character, take half the colour from the biome itself.
        int familyRgb = LiteMath.packRgb(family.hazeRed(), family.hazeGreen(), family.hazeBlue());
        return family.withHaze(LiteMath.mixRgb(familyRgb, derived, 0.5f));
    }

    private static BiomeAtmosphere familyOf(Holder<Biome> biome) {
        for (Map.Entry<TagKey<Biome>, BiomeAtmosphere> entry : FAMILIES_BY_TAG.entrySet()) {
            if (biome.is(entry.getKey())) {
                return entry.getValue();
            }
        }
        return TEMPERATE;
    }

    /** Skyless hazes fill the whole view at full strength, so they stay dark. */
    private static int darkenForSkyless(int rgb) {
        return LiteMath.packRgb(BiomeAtmosphere.red(rgb) * 0.45f, BiomeAtmosphere.green(rgb) * 0.45f, BiomeAtmosphere.blue(rgb) * 0.45f);
    }

    private static int rgbOf(Vector3fc colour) {
        return LiteMath.packRgb(colour.x(), colour.y(), colour.z());
    }

    private static Map<TagKey<Biome>, BiomeAtmosphere> familiesByTag() {
        Map<TagKey<Biome>, BiomeAtmosphere> families = new LinkedHashMap<>();
        families.put(ConventionalBiomeTags.IS_CAVE, STONE_CAVES);
        families.put(ConventionalBiomeTags.IS_SWAMP, SWAMP);
        families.put(ConventionalBiomeTags.IS_DESERT, DESERT);
        families.put(ConventionalBiomeTags.IS_BADLANDS, BADLANDS);
        families.put(BiomeTags.IS_BADLANDS, BADLANDS);
        families.put(ConventionalBiomeTags.IS_SAVANNA, SAVANNA);
        families.put(BiomeTags.IS_SAVANNA, SAVANNA);
        families.put(ConventionalBiomeTags.IS_MUSHROOM, MUSHROOM);
        families.put(ConventionalBiomeTags.IS_JUNGLE, JUNGLE);
        families.put(BiomeTags.IS_JUNGLE, JUNGLE);
        families.put(ConventionalBiomeTags.IS_SNOWY, SNOWY);
        families.put(ConventionalBiomeTags.IS_ICY, SNOWY);
        families.put(ConventionalBiomeTags.IS_OCEAN, OCEAN);
        families.put(BiomeTags.IS_OCEAN, OCEAN);
        families.put(ConventionalBiomeTags.IS_BEACH, BEACH);
        families.put(ConventionalBiomeTags.IS_DARK_FOREST, DARK_FOREST);
        families.put(ConventionalBiomeTags.IS_TAIGA, TAIGA);
        families.put(BiomeTags.IS_TAIGA, TAIGA);
        families.put(ConventionalBiomeTags.IS_FOREST, FOREST);
        families.put(BiomeTags.IS_FOREST, FOREST);
        families.put(ConventionalBiomeTags.IS_MOUNTAIN_PEAK, PEAKS);
        families.put(ConventionalBiomeTags.IS_WINDSWEPT, WINDSWEPT);
        families.put(ConventionalBiomeTags.IS_MOUNTAIN, WINDSWEPT);
        families.put(ConventionalBiomeTags.IS_DRY_OVERWORLD, SAVANNA.withStorms(0.0f, 0.0f, 0.0f));
        families.put(ConventionalBiomeTags.IS_WET_OVERWORLD, JUNGLE.withDensity(BiomeAtmosphere.OVERWORLD_DENSITY * 1.3f));
        return families;
    }
}
