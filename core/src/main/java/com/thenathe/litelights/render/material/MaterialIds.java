package com.thenathe.litelights.render.material;

/** Material ids, as stored in the material texture. Keep in step with material.glsl. */
public final class MaterialIds {
    public static final int DEFAULT = 0;
    public static final int WATER = 1;
    public static final int LAVA = 2;
    public static final int ICE = 3;
    public static final int GLASS = 4;
    public static final int LEAVES = 5;
    public static final int PLANT = 6;
    public static final int GLOWING = 7;
    public static final int METAL = 8;
    public static final int SNOW = 9;
    public static final int SAND = 10;
    public static final int FIRE = 11;
    // Vegetation: see com.thenathe.litelights.render.feature.vegetation.VegetationMaterials.
    public static final int GRASS = 12;
    public static final int GRASS_UPPER = 13;
    public static final int PLANT_UPPER = 14;
    public static final int PLANT_STILL = 15;
    // Farmland has a wet look of its own, so puddles leave it alone (include/weather/puddles.glsl).
    public static final int FARMLAND = 16;
    // Ores, by the rock their texture is drawn on (OreRocks decides from the textures in use), and
    // gem-like blocks: Glint on ores and crystals (shimmer099).
    public static final int ORE_STONE = 17;
    public static final int ORE_DEEPSLATE = 18;
    public static final int ORE_NETHERRACK = 19;
    public static final int CRYSTAL = 20;

    private MaterialIds() {
    }

    public static boolean isOre(int material) {
        return material >= ORE_STONE && material <= ORE_NETHERRACK;
    }

    /** Ores and crystals: hard, plain blocks apart from their glint. */
    public static boolean isOreOrCrystal(int material) {
        return isOre(material) || material == CRYSTAL;
    }
}
