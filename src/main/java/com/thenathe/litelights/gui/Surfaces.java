package com.thenathe.litelights.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The shapes LiteLights' screens are drawn from. Boxes have their corner pixels cut, which
 * gives a soft, rounded look at every GUI scale without any textures.
 */
public final class Surfaces {
    private Surfaces() {
    }

    /** A filled box with its four corner pixels left out. */
    public static void rounded(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int colour) {
        if (w < 3 || h < 3) {
            graphics.fill(x, y, x + w, y + h, colour);
            return;
        }
        graphics.fill(x + 1, y, x + w - 1, y + h, colour);
        graphics.fill(x, y + 1, x + 1, y + h - 1, colour);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, colour);
    }

    /** A rounded box with a one-pixel edge in another colour. */
    public static void framed(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int fill, int edge) {
        rounded(graphics, x, y, w, h, edge);
        rounded(graphics, x + 1, y + 1, w - 2, h - 2, fill);
    }

    /** The big panels: an edge, the fill, and a soft shadow to the right and below. */
    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x + 2, y + h, x + w, y + h + 2, Theme.PANEL_SHADOW);
        graphics.fill(x + w, y + 2, x + w + 2, y + h + 2, Theme.PANEL_SHADOW);
        framed(graphics, x, y, w, h, Theme.PANEL, Theme.PANEL_EDGE);
    }

    public static void card(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        framed(graphics, x, y, w, h, Theme.CARD, Theme.CARD_EDGE);
    }

    /** A sunken field: sliders, the search box. */
    public static void well(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int edge) {
        framed(graphics, x, y, w, h, Theme.WELL, edge);
    }

    /** A button face; {@code lit} adds a lighter top line. */
    public static void plate(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int face) {
        framed(graphics, x, y, w, h, face, Theme.shade(face, -0.45F));
        graphics.fill(x + 2, y + 1, x + w - 2, y + 2, Theme.shade(face, 0.18F));
    }

    /** A small label chip, such as a cost badge: text on a tinted pill with a coloured edge. */
    public static void chip(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int colour) {
        framed(graphics, x, y, w, h, Theme.withAlpha(Theme.shade(colour, -0.75F), 0xFF), Theme.shade(colour, -0.25F));
    }

    public static void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int colour) {
        graphics.fill(x + 1, y, x + w - 1, y + 1, colour);
        graphics.fill(x + 1, y + h - 1, x + w - 1, y + h, colour);
        graphics.fill(x, y + 1, x + 1, y + h - 1, colour);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, colour);
    }
}
