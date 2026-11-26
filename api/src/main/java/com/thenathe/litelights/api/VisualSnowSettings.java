package com.thenathe.litelights.api;

/**
 * The visual snow's look rows far snow reads (core's Weather page, Visual snow), as saved: far
 * snow is far terrain's own row since far-snow-115 and doesn't wait for the Visual snow switch, so
 * neither do the looks it borrows.
 *
 * @param leafTops Snowy leaves at Tops only: far leaves white only on top
 * @param grass    White grass in snow: far grass and ferns standing in snow are white
 */
public record VisualSnowSettings(boolean leafTops, boolean grass) {
    public static final VisualSnowSettings OFF = new VisualSnowSettings(false, false);
}
