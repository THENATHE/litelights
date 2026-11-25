package com.thenathe.litelights.gui;

import com.thenathe.litelights.config.ValueFormat;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Turns option numbers into the text on sliders and in the description. */
public final class ValueFormats {
    private ValueFormats() {
    }

    public static Component format(ValueFormat format, double value) {
        return switch (format) {
            case PLAIN -> Component.literal(trimmed(value));
            case PERCENT -> Component.literal(Math.round(value * 100) + "%");
            case SIGNED_PERCENT -> Component.literal(signed(Math.round(value * 100)) + "%");
            case DEGREES -> Component.literal(signed(Math.round(value)) + "°");
            case STOPS -> Component.translatable("litelights.format.stops", signedDecimal(value));
            case BLOCKS -> Component.translatable(value == 1.0 ? "litelights.format.block" : "litelights.format.blocks", trimmed(value));
            case BLOCK_SQUARE -> Component.translatable("litelights.format.block_square", trimmed(value));
        };
    }

    private static String trimmed(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "");
    }

    private static String signed(long value) {
        return value > 0 ? "+" + value : Long.toString(value);
    }

    private static String signedDecimal(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        if (text.equals("-0")) {
            text = "0";
        }
        return value > 0.0001 ? "+" + text : text;
    }
}
