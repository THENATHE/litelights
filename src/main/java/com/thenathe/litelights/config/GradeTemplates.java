package com.thenathe.litelights.config;

import com.thenathe.litelights.config.LiteLightsOptions.ColourGrade;
import com.thenathe.litelights.config.LiteLightsOptions.GradeSchedule;
import com.thenathe.litelights.config.LiteLightsOptions.GradeTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ready-made colour grades that fill the grade sliders in one click. A template is not a mode:
 * it only sets slider values, which stay free to move afterwards, and nothing remembers which
 * template was used (the screen just recognises sliders that still match one).
 *
 * <p>Each template has a whole-day grade and one per time of day. The per-time sets start from
 * the whole-day look and lean warmer at dawn and dusk and cooler and calmer at night, the same way
 * light does, except where a template is about one time of day itself (Golden hour). The looks are
 * LiteLights' own, made to echo well-known film and grading styles with the existing sliders;
 * the reasoning for each is in docs/DECISIONS.md (ui15).
 */
public final class GradeTemplates {
    /** One template: slider values by slider name (the last part of the option id) for each set of sliders. */
    public record Template(String key, Map<GradeTime, Map<String, Float>> values) {
        /** The value this template gives one slider of one set; sliders it doesn't name are neutral. */
        public float value(GradeTime time, String slider) {
            Float value = values.get(time).get(slider);
            return value != null ? value : neutral(slider);
        }

        public String translationKey() {
            return "litelights.grading.template." + key;
        }
    }

    /** The sets of sliders a template fills, other than the whole-day set. */
    private static final List<GradeTime> TIMES = List.of(GradeTime.DAWN, GradeTime.MIDDAY, GradeTime.DUSK, GradeTime.NIGHT);

    public static final Template LITELIGHTS_DEFAULT = defaults();
    public static final Template NEUTRAL = new Template("neutral", byTime(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

    /** Print film: denser blacks, gently rolled highlights, warm midtones, blues leaning cyan, rich but quiet colour. */
    public static final Template FILM_PRINT = withLight("film_print", Map.of(
            "contrast", 0.12F, "shadows", -0.05F, "highlights", -0.06F, "saturation", -0.05F, "vibrance", 0.10F,
            "temperature", 0.04F, "tint", 0.02F, "red_hue", 2.0F, "blue_hue", -4.0F));

    /** A portrait negative: low contrast and lifted shadows, soft highlights, warm skin, muted olive greens. */
    public static final Template PORTRA_STYLE = withLight("portra_style", Map.of(
            "contrast", -0.10F, "shadows", 0.10F, "highlights", -0.08F, "saturation", -0.10F, "vibrance", 0.06F,
            "temperature", 0.07F, "red_strength", 1.02F, "green_strength", 0.98F, "green_hue", -4.0F));

    /**
     * The blockbuster split: warm tones pushed toward orange, blues and greens toward teal. Kept
     * gentle, since without split toning the turn also reaches the sky.
     */
    public static final Template TEAL_AND_ORANGE = withLight("teal_and_orange", Map.of(
            "contrast", 0.08F, "vibrance", 0.12F, "temperature", 0.04F,
            "red_hue", 6.0F, "green_hue", 5.0F, "blue_hue", -7.0F));

    /** Skipping the bleach: hard contrast, silvery highlights and colour drained to about half. */
    public static final Template BLEACH_BYPASS = withLight("bleach_bypass", Map.of(
            "exposure", -0.05F, "contrast", 0.28F, "shadows", -0.08F, "highlights", 0.06F,
            "saturation", -0.45F, "vibrance", -0.10F));

    /** Three-strip colour: saturated primaries, strong reds, deep blues, firm contrast. */
    public static final Template TECHNICOLOR_STYLE = withLight("technicolor_style", Map.of(
            "contrast", 0.10F, "saturation", 0.18F, "vibrance", 0.08F, "temperature", 0.02F,
            "red_strength", 1.06F, "green_strength", 1.02F, "blue_hue", -2.0F));

    /** The hour after sunrise and before sunset all day: warm, glowing, a little brighter in the highlights. */
    public static final Template GOLDEN_HOUR = new Template("golden_hour", byTime(
            Map.of("temperature", 0.22F, "tint", 0.03F, "shadows", 0.03F, "highlights", 0.04F, "saturation", 0.06F, "vibrance", 0.10F),
            Map.of("temperature", 0.24F, "tint", 0.03F, "highlights", 0.05F, "saturation", 0.08F, "vibrance", 0.16F),
            Map.of("temperature", 0.14F, "tint", 0.02F, "highlights", 0.03F, "vibrance", 0.06F),
            Map.of("temperature", 0.28F, "tint", 0.04F, "highlights", 0.05F, "saturation", 0.10F, "vibrance", 0.18F, "red_strength", 1.03F),
            Map.of("temperature", -0.04F, "vibrance", 0.04F)));

    /** In the order the screen lists them. */
    public static final List<Template> ALL = List.of(LITELIGHTS_DEFAULT, FILM_PRINT, PORTRA_STYLE, TEAL_AND_ORANGE,
            BLEACH_BYPASS, TECHNICOLOR_STYLE, GOLDEN_HOUR, NEUTRAL);

    private GradeTemplates() {
    }

    /**
     * Fills the sliders the page edits with a template: the four times of day when grading goes
     * by time of day, the whole-day set otherwise.
     */
    public static void apply(Template template, GradeSchedule schedule) {
        for (GradeTime time : setsFor(schedule)) {
            ColourGrade grade = LiteLightsOptions.grade(time);
            for (FloatOption slider : grade.sliders()) {
                slider.set(template.value(time, sliderName(slider)));
            }
        }
    }

    /**
     * The template the edited sliders match exactly, or null when they are the player's own.
     * Neutral is tried first: the default's whole-day set is neutral too, and there that is the
     * clearer name.
     */
    public static Template matching(GradeSchedule schedule) {
        if (matches(NEUTRAL, schedule)) {
            return NEUTRAL;
        }
        for (Template template : ALL) {
            if (matches(template, schedule)) {
                return template;
            }
        }
        return null;
    }

    static boolean matches(Template template, GradeSchedule schedule) {
        for (GradeTime time : setsFor(schedule)) {
            for (FloatOption slider : LiteLightsOptions.grade(time).sliders()) {
                // Sliders snap to their step, so compare to half a step.
                if (Math.abs(slider.value() - template.value(time, sliderName(slider))) > slider.step() * 0.5F) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<GradeTime> setsFor(GradeSchedule schedule) {
        return schedule == GradeSchedule.BY_TIME_OF_DAY ? TIMES : List.of(GradeTime.ALL_DAY);
    }

    /** A slider's name: the last part of its id, such as "temperature" or "red_strength". */
    static String sliderName(FloatOption slider) {
        return slider.id().substring(slider.id().lastIndexOf('.') + 1);
    }

    /** The value that leaves the picture alone: 1 for the channel strengths, 0 for the rest. */
    static float neutral(String slider) {
        return slider.endsWith("_strength") ? 1.0F : 0.0F;
    }

    /**
     * A look worn all day, with the light's own lean at each time: a little warmer and more
     * colourful at dawn and dusk, cooler and calmer at night.
     */
    private static Template withLight(String key, Map<String, Float> look) {
        return new Template(key, byTime(look,
                shifted(look, Map.of("temperature", 0.06F, "vibrance", 0.06F)),
                look,
                shifted(look, Map.of("temperature", 0.08F, "vibrance", 0.08F)),
                shifted(look, Map.of("temperature", -0.06F, "saturation", -0.06F))));
    }

    private static Map<String, Float> shifted(Map<String, Float> look, Map<String, Float> shift) {
        Map<String, Float> values = new HashMap<>(look);
        shift.forEach((slider, amount) -> values.put(slider, values.getOrDefault(slider, neutral(slider)) + amount));
        return Map.copyOf(values);
    }

    private static Map<GradeTime, Map<String, Float>> byTime(Map<String, Float> allDay, Map<String, Float> dawn,
                                                           Map<String, Float> midday, Map<String, Float> dusk,
                                                           Map<String, Float> night) {
        Map<GradeTime, Map<String, Float>> values = new EnumMap<>(GradeTime.class);
        values.put(GradeTime.ALL_DAY, allDay);
        values.put(GradeTime.DAWN, dawn);
        values.put(GradeTime.MIDDAY, midday);
        values.put(GradeTime.DUSK, dusk);
        values.put(GradeTime.NIGHT, night);
        return values;
    }

    /** LiteLights' own default grade, read from the sliders' defaults so the two never drift apart. */
    private static Template defaults() {
        Map<GradeTime, Map<String, Float>> values = new EnumMap<>(GradeTime.class);
        for (GradeTime time : GradeTime.values()) {
            Map<String, Float> sliders = new HashMap<>();
            for (FloatOption slider : LiteLightsOptions.grade(time).sliders()) {
                sliders.put(sliderName(slider), slider.defaultValue());
            }
            values.put(time, Map.copyOf(sliders));
        }
        return new Template("litelights_default", values);
    }
}
