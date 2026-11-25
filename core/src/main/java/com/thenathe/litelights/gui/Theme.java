package com.thenathe.litelights.gui;

import com.thenathe.litelights.config.Cost;

/**
 * LiteLights' colours: warm dark panels, one amber accent, and soft cards. Colours are
 * 0xAARRGGBB. Sub-screens (such as the overlays hierarchy editor) should draw with these too.
 */
public final class Theme {
    /** Over the live world while the screen is docked: light enough to judge changes by. */
    public static final int WORLD_DIM_DOCKED = 0x38000000;
    public static final int WORLD_DIM_FULL = 0x90000000;

    public static final int PANEL = 0xF01E1A16;
    public static final int PANEL_EDGE = 0xFF3D342B;
    public static final int PANEL_SHADOW = 0x60000000;
    public static final int CARD = 0xFF29231D;
    public static final int CARD_EDGE = 0xFF3A3129;
    public static final int WELL = 0xFF14110E;
    public static final int WELL_EDGE = 0xFF0C0A08;
    public static final int RAISED = 0xFF3A3129;
    public static final int RAISED_HOT = 0xFF4A3F33;
    public static final int RULE = 0xFF352D25;
    public static final int HOVER = 0x18FFE7C4;

    public static final int TEXT = 0xFFF1E8D8;
    public static final int TEXT_DIM = 0xFFA99A84;
    public static final int TEXT_OFF = 0xFF6F6456;
    public static final int TEXT_STRONG = 0xFFFFFFFF;
    public static final int TITLE = 0xFFFFF4E2;

    public static final int ACCENT = 0xFFE8A33D;
    public static final int ACCENT_HOT = 0xFFF4B95E;
    public static final int ACCENT_DEEP = 0xFF9C6420;
    public static final int ACCENT_TEXT = 0xFF2A1A06;
    public static final int ACCENT_WASH = 0x33E8A33D;
    public static final int CAPTION = 0xFFD9A35A;

    public static final int CHANGED = 0xFFF0C062;
    public static final int GOOD = 0xFF93CF77;
    public static final int ERROR = 0xFFEF7B62;
    public static final int ERROR_PLATE = 0xFF7A3226;
    public static final int NOTICE = 0xFFD7B98A;

    private Theme() {
    }

    public static int costColour(Cost cost) {
        return switch (cost) {
            case FREE -> 0xFF7FB069;
            case LOW -> 0xFFB8C26A;
            case MEDIUM -> ACCENT;
            case HIGH -> 0xFFE2694E;
        };
    }

    /** Frame time colour: green under 60 FPS' budget, amber under 30 FPS', red above. */
    public static int frameTimeColour(double millis) {
        if (millis <= 16.7) {
            return GOOD;
        }
        return millis <= 33.4 ? ACCENT : ERROR;
    }

    /** Moves each channel a fraction of the way toward white (positive) or black (negative), keeping alpha. */
    public static int shade(int argb, float amount) {
        int target = amount > 0 ? 255 : 0;
        float k = Math.abs(amount);
        int red = Math.round(channel(argb, 16) + (target - channel(argb, 16)) * k);
        int green = Math.round(channel(argb, 8) + (target - channel(argb, 8)) * k);
        int blue = Math.round(channel(argb, 0) + (target - channel(argb, 0)) * k);
        return argb & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    public static int withAlpha(int argb, int alpha) {
        return alpha << 24 | argb & 0xFFFFFF;
    }

    private static int channel(int argb, int shift) {
        return argb >> shift & 0xFF;
    }
}
