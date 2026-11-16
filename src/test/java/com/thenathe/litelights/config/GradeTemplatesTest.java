package com.thenathe.litelights.config;

import com.thenathe.litelights.config.LiteLightsOptions.GradeSchedule;
import com.thenathe.litelights.config.LiteLightsOptions.GradeTime;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The grade templates: in range, distinct, applied to the right sliders, and never a mode. */
class GradeTemplatesTest {
    @TempDir
    static Path folder;

    @BeforeAll
    static void loadOptions() {
        if (!LiteLightsConfig.loaded()) {
            LiteLightsConfig.useFile(folder.resolve("litelights.json"));
        }
        LiteLightsConfig.init();
    }

    @AfterEach
    void resetGrades() {
        LiteLightsOptions.GRADE_SCHEDULE.reset();
        for (GradeTime time : GradeTime.values()) {
            LiteLightsOptions.grade(time).sliders().forEach(Option::reset);
        }
    }

    @Test
    void everyValueFitsItsSlider() {
        for (GradeTemplates.Template template : GradeTemplates.ALL) {
            for (GradeTime time : GradeTime.values()) {
                for (FloatOption slider : LiteLightsOptions.grade(time).sliders()) {
                    float value = template.value(time, GradeTemplates.sliderName(slider));
                    assertTrue(value >= slider.min() && value <= slider.max(), template.key() + " " + slider.id() + " = " + value);
                }
            }
        }
    }

    @Test
    void templatesDifferFromEachOther() {
        Set<String> looks = new HashSet<>();
        for (GradeTemplates.Template template : GradeTemplates.ALL) {
            StringBuilder look = new StringBuilder();
            for (GradeTime time : GradeTime.values()) {
                for (FloatOption slider : LiteLightsOptions.grade(time).sliders()) {
                    look.append(template.value(time, GradeTemplates.sliderName(slider))).append(',');
                }
            }
            assertTrue(looks.add(look.toString()), template.key() + " repeats another template");
        }
    }

    @Test
    void neutralLeavesThePictureAlone() {
        for (GradeTime time : GradeTime.values()) {
            for (FloatOption slider : LiteLightsOptions.grade(time).sliders()) {
                String name = GradeTemplates.sliderName(slider);
                assertEquals(name.endsWith("_strength") ? 1.0f : 0.0f, GradeTemplates.NEUTRAL.value(time, name), slider.id());
            }
        }
    }

    @Test
    void theDefaultTemplateIsTheDefaultGrade() {
        assertSame(GradeTemplates.LITELIGHTS_DEFAULT, GradeTemplates.matching(GradeSchedule.BY_TIME_OF_DAY));
        assertSame(GradeTemplates.NEUTRAL, GradeTemplates.matching(GradeSchedule.ALL_DAY));
    }

    @Test
    void byTimeOfDayFillsTheFourTimesAndLeavesTheWholeDaySet() {
        GradeTemplates.apply(GradeTemplates.GOLDEN_HOUR, GradeSchedule.BY_TIME_OF_DAY);
        assertEquals(0.28f, LiteLightsOptions.grade(GradeTime.DUSK).temperature().value(), 1.0e-6f);
        assertEquals(0.14f, LiteLightsOptions.grade(GradeTime.MIDDAY).temperature().value(), 1.0e-6f);
        assertEquals(-0.04f, LiteLightsOptions.grade(GradeTime.NIGHT).temperature().value(), 1.0e-6f);
        assertEquals(0.0f, LiteLightsOptions.grade(GradeTime.ALL_DAY).temperature().value());
        assertSame(GradeTemplates.GOLDEN_HOUR, GradeTemplates.matching(GradeSchedule.BY_TIME_OF_DAY));
    }

    @Test
    void allDayFillsOnlyTheWholeDaySet() {
        GradeTemplates.apply(GradeTemplates.BLEACH_BYPASS, GradeSchedule.ALL_DAY);
        assertEquals(-0.45f, LiteLightsOptions.grade(GradeTime.ALL_DAY).saturation().value(), 1.0e-6f);
        assertEquals(0.07f, LiteLightsOptions.grade(GradeTime.DAWN).saturation().value(), 1.0e-6f);
        assertSame(GradeTemplates.BLEACH_BYPASS, GradeTemplates.matching(GradeSchedule.ALL_DAY));
    }

    @Test
    void movingASliderAfterwardsIsYourOwn() {
        GradeTemplates.apply(GradeTemplates.FILM_PRINT, GradeSchedule.ALL_DAY);
        LiteLightsOptions.grade(GradeTime.ALL_DAY).contrast().set(0.5f);
        assertNull(GradeTemplates.matching(GradeSchedule.ALL_DAY));
        assertEquals(0.5f, LiteLightsOptions.grade(GradeTime.ALL_DAY).contrast().value(), 1.0e-6f);
    }

    @Test
    void timesOfDayLeanWithTheLight() {
        GradeTemplates.Template film = GradeTemplates.FILM_PRINT;
        assertTrue(film.value(GradeTime.DUSK, "temperature") > film.value(GradeTime.MIDDAY, "temperature"));
        assertTrue(film.value(GradeTime.NIGHT, "temperature") < film.value(GradeTime.MIDDAY, "temperature"));
        assertEquals(film.value(GradeTime.ALL_DAY, "contrast"), film.value(GradeTime.NIGHT, "contrast"));
    }
}
