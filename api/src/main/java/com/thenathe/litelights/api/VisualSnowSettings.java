package com.thenathe.litelights.api;

/**
 * The visual snow's rows far terrain reads (core's Weather page, Visual snow), each with the master
 * switch folded in.
 *
 * @param far      Snow on far terrain: far land shows the season's snow as it lies now
 * @param leafTops Snowy leaves at Tops only: far leaves white only on top
 * @param grass    White grass in snow: far grass and ferns standing in snow are white
 */
public record VisualSnowSettings(boolean far, boolean leafTops, boolean grass) {
    public static final VisualSnowSettings OFF = new VisualSnowSettings(false, false, false);
}
