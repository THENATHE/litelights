package com.thenathe.litelights.config;

import com.google.gson.JsonObject;
import com.thenathe.litelights.config.LiteLightsOptions.AntiAliasing;
import com.thenathe.litelights.config.LiteLightsOptions.Clouds;
import com.thenathe.litelights.config.LiteLightsOptions.DistanceFill;
import com.thenathe.litelights.config.LiteLightsOptions.FarShadows;
import com.thenathe.litelights.config.LiteLightsOptions.GlassDetail;
import com.thenathe.litelights.config.LiteLightsOptions.JumpSplash;
import com.thenathe.litelights.config.LiteLightsOptions.LightModel;
import com.thenathe.litelights.config.LiteLightsOptions.MirrorDetail;
import com.thenathe.litelights.config.LiteLightsOptions.MistStyle;
import com.thenathe.litelights.config.LiteLightsOptions.ParticleDensity;
import com.thenathe.litelights.config.LiteLightsOptions.PrecipitationLook;
import com.thenathe.litelights.config.LiteLightsOptions.ShadowDetail;
import com.thenathe.litelights.config.LiteLightsOptions.SnowSandSprites;
import com.thenathe.litelights.config.LiteLightsOptions.SpawnDistance;
import com.thenathe.litelights.config.LiteLightsOptions.SunbeamQuality;
import com.thenathe.litelights.config.LiteLightsOptions.WeatherDensity;
import java.util.List;

/**
 * The first-time setup (defaults098): on a fresh install the title screen asks how powerful the
 * computer is, and each answer sets LiteLights' quality and distance settings to suit. The
 * creator's recommended keeps the defaults. Only options that cost frame time are in the presets;
 * looks (palettes, colours, strengths) stay as they are in every answer.
 *
 * <p>The question is asked once: when the game started without a settings file, and setup hasn't
 * been finished yet. Players upgrading from an earlier version already have a file, so they never
 * see it and nothing of theirs changes. The General page can open it again.
 */
public final class FirstSetup {
    /** The module in litelights.json that remembers setup was finished. */
    static final String MODULE = "first_setup";

    public enum Tier { INTEGRATED, EVERYDAY, GAMING, TOP }

    /**
     * Each preset's values, one row per option, in the order of the answers: integrated graphics
     * or an old computer, a new non-gaming computer, an older or budget gaming computer, and a top
     * of the line gaming computer. The defaults sit between the middle two. The lowest answer
     * keeps every effect and only takes its cheapest setting; Mist style stays Mist field even at
     * the top, where 3D puffs would cost several milliseconds in heavy rain. DECISIONS.md, under
     * defaults098, has the same table with the defaults beside it.
     */
    static final List<Preset<?>> PRESETS = List.of(
        //     option                                       integrated / old            new non-gaming              older or budget gaming      top of the line
        preset(LiteLightsOptions.ANTI_ALIASING,             AntiAliasing.OFF,           AntiAliasing.OFF,           AntiAliasing.FXAA,          AntiAliasing.TAA),
        preset(LiteLightsOptions.WORLD_SHADOWS,             true,                       true,                       true,                       true),
        preset(LiteLightsOptions.LIGHT_MODEL,               LightModel.SHADOWS_ONLY,    LightModel.RELIT,           LightModel.RELIT,           LightModel.RELIT),
        preset(LiteLightsOptions.SHADOW_DETAIL,             ShadowDetail.FASTEST,       ShadowDetail.SHARP,         ShadowDetail.SHARPEST,      ShadowDetail.ULTRA),
        preset(LiteLightsOptions.SHADOW_REACH,              64,                         128,                        256,                        512),
        preset(LiteLightsOptions.FAR_SHADOWS,               FarShadows.SCREEN_SPACE,    FarShadows.SCREEN_SPACE,    FarShadows.ADVANCED,        FarShadows.ADVANCED),
        preset(LiteLightsOptions.CLOUDS,                    Clouds.FLAT,                Clouds.FLAT,                Clouds.FANCY,               Clouds.FANCY),
        preset(LiteLightsOptions.CLOUD_LAYERS,              1,                          1,                          2,                          2),
        preset(LiteLightsOptions.SUNBEAM_QUALITY,           SunbeamQuality.LOW,         SunbeamQuality.LOW,         SunbeamQuality.HIGH,        SunbeamQuality.ULTRA),
        preset(LiteLightsOptions.WATER_MIRRORS,             true,                       true,                       true,                       true),
        preset(LiteLightsOptions.MIRROR_DETAIL,             MirrorDetail.COARSE,        MirrorDetail.COARSE,        MirrorDetail.BALANCED,      MirrorDetail.FINE),
        preset(LiteLightsOptions.OCEAN_WAVES,               true,                       true,                       true,                       true),
        preset(LiteLightsOptions.UNDERWATER_BLUR,           false,                      false,                      false,                      true),
        preset(LiteLightsOptions.JUMP_SPLASH,               JumpSplash.BASIC,           JumpSplash.ADVANCED,        JumpSplash.ADVANCED,        JumpSplash.ADVANCED),
        preset(LiteLightsOptions.PARTICLE_DENSITY,          ParticleDensity.SPARSE,     ParticleDensity.SPARSE,     ParticleDensity.FULL,       ParticleDensity.FULL),
        preset(LiteLightsOptions.PARTICLE_DISTANCE,         SpawnDistance.NEAR,         SpawnDistance.NEAR,         SpawnDistance.MEDIUM,       SpawnDistance.VERY_FAR),
        preset(LiteLightsOptions.PARTICLE_DISTANCE_FILL,    DistanceFill.SPREAD,        DistanceFill.SPREAD,        DistanceFill.SPREAD,        DistanceFill.FILL),
        preset(LiteLightsOptions.SNOW_SAND_SPRITES,         SnowSandSprites.VANILLA,    SnowSandSprites.VANILLA,    SnowSandSprites.VANILLA,    SnowSandSprites.DETAILED),
        preset(LiteLightsOptions.PRECIPITATION,             PrecipitationLook.VANILLA_PLUS, PrecipitationLook.LITELIGHTS, PrecipitationLook.LITELIGHTS, PrecipitationLook.LITELIGHTS),
        preset(LiteLightsOptions.WEATHER_DENSITY,           WeatherDensity.LIGHT,       WeatherDensity.LIGHT,       WeatherDensity.MEDIUM,      WeatherDensity.DENSE),
        preset(LiteLightsOptions.WEATHER_DISTANCE,          SpawnDistance.NEAR,         SpawnDistance.NEAR,         SpawnDistance.MEDIUM,       SpawnDistance.VERY_FAR),
        preset(LiteLightsOptions.WEATHER_DISTANCE_FILL,     DistanceFill.SPREAD,        DistanceFill.SPREAD,        DistanceFill.SPREAD,        DistanceFill.FILL),
        preset(LiteLightsOptions.MIST_STYLE,                MistStyle.FIELD,            MistStyle.FIELD,            MistStyle.FIELD,            MistStyle.FIELD),
        preset(LiteLightsOptions.GLASS_DETAIL,              GlassDetail.TEXELS_16,      GlassDetail.TEXELS_16,      GlassDetail.TEXELS_16,      GlassDetail.TEXELS_32)
    );

    record Preset<T>(Option<T> option, T integrated, T everyday, T gaming, T top) {
        T value(Tier tier) {
            return switch (tier) {
                case INTEGRATED -> integrated;
                case EVERYDAY -> everyday;
                case GAMING -> gaming;
                case TOP -> top;
            };
        }

        /**
         * Sets the option as the settings screen would let a player: not while another mod handles
         * it or it isn't available (a missing far-terrain mod, Particle Rain's weather), and not to a
         * choice that can't be picked (TAA while it's failed).
         */
        void apply(T value) {
            if (!option.feature().state().editable()) {
                return;
            }
            if (option instanceof EnumOption<?> choice && !choiceEditable(choice, value)) {
                return;
            }
            option.set(value);
        }

        private static <E extends Enum<E>> boolean choiceEditable(EnumOption<E> option, Object value) {
            return option.choiceState(option.type().cast(value)).editable();
        }
    }

    private static <T> Preset<T> preset(Option<T> option, T integrated, T everyday, T gaming, T top) {
        return new Preset<>(option, integrated, everyday, gaming, top);
    }

    private FirstSetup() {
    }

    /** Whether the title screen should ask: a fresh install that hasn't answered yet. */
    public static boolean due() {
        return due(LiteLightsConfig.file());
    }

    static boolean due(ConfigFile file) {
        return file.startedWithoutFile() && !done(file);
    }

    static boolean done(ConfigFile file) {
        return file.hasModule(MODULE) && file.module(MODULE).has("done") && file.module(MODULE).get("done").getAsBoolean();
    }

    /** Sets every option in the presets for this answer, then remembers setup as finished and saves. */
    public static void choose(Tier tier) {
        for (Preset<?> preset : PRESETS) {
            applyFor(preset, tier);
        }
        finish();
    }

    /**
     * The creator's recommended: the preset options back at their defaults. On a fresh install
     * that changes nothing; run again later, it undoes an earlier answer.
     */
    public static void useRecommended() {
        for (Preset<?> preset : PRESETS) {
            preset.option().reset();
        }
        finish();
    }

    /** Closed without an answer: nothing changes, and the question isn't asked again. */
    public static void dismiss() {
        finish();
    }

    private static <T> void applyFor(Preset<T> preset, Tier tier) {
        preset.apply(preset.value(tier));
    }

    private static void finish() {
        markDone(LiteLightsConfig.file());
        LiteLightsConfig.save();
    }

    static void markDone(ConfigFile file) {
        JsonObject module = file.module(MODULE);
        module.addProperty("done", true);
    }
}
