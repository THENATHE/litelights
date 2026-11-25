package com.thenathe.litelights.render.timing;

import com.thenathe.litelights.config.BoolOption;
import com.thenathe.litelights.config.EnumOption;
import com.thenathe.litelights.config.FloatOption;
import com.thenathe.litelights.config.IntOption;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbOptionFlipTest {
    private enum Look { OFF, FLAT, FANCY }

    @Test
    void aSwitchWithoutValuesFlipsToItsOtherValueAndBack() {
        BoolOption option = BoolOption.builder("test.switch", true).at("general", "test").build();
        AbOptionFlip flip = AbOptionFlip.of(option, "");
        flip.apply(AbSchedule.Side.B);
        assertEquals(false, option.get());
        flip.apply(AbSchedule.Side.A);
        assertEquals(true, option.get());
    }

    @Test
    void stoppingPutsBackTheValueFromBeforeTheRun() {
        EnumOption<Look> option = EnumOption.builder("test.look", Look.class, Look.FANCY).at("sky", "test").build();
        option.set(Look.FLAT);
        AbOptionFlip flip = AbOptionFlip.of(option, "fancy, off");
        flip.apply(AbSchedule.Side.A);
        assertEquals(Look.FANCY, option.get());
        flip.apply(AbSchedule.Side.B);
        assertEquals(Look.OFF, option.get());
        flip.restore();
        assertEquals(Look.FLAT, option.get());
    }

    @Test
    void numbersAreReadAsTheOptionsType() {
        IntOption reach = IntOption.builder("test.reach", 192, 64, 512, 16).at("shadows", "test").build();
        FloatOption strength = FloatOption.builder("test.strength", 1.0f, 0.0f, 2.0f, 0.05f).at("lighting", "test").build();
        assertEquals(256, AbOptionFlip.parse(reach, "256"));
        assertEquals(1.5f, AbOptionFlip.parse(strength, "1.5"));
    }

    @Test
    void valuesThatDontFitTheOptionAreRefused() {
        BoolOption option = BoolOption.builder("test.switch", true).at("general", "test").build();
        EnumOption<Look> look = EnumOption.builder("test.look", Look.class, Look.FANCY).at("sky", "test").build();
        IntOption reach = IntOption.builder("test.reach", 192, 64, 512, 16).at("shadows", "test").build();
        assertThrows(IllegalArgumentException.class, () -> AbOptionFlip.of(option, "yes,no"));
        assertThrows(IllegalArgumentException.class, () -> AbOptionFlip.of(look, "fancy"));
        assertThrows(IllegalArgumentException.class, () -> AbOptionFlip.of(look, "fancy,ultra"));
        assertThrows(IllegalArgumentException.class, () -> AbOptionFlip.of(look, ""));
        assertThrows(IllegalArgumentException.class, () -> AbOptionFlip.of(reach, "far,near"));
    }
}
