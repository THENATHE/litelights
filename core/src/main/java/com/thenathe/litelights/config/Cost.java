package com.thenathe.litelights.config;

import java.util.Locale;

/** Roughly what a feature costs in frame time while it is on, shown as a badge on its row. */
public enum Cost {
    FREE,
    LOW,
    MEDIUM,
    HIGH;

    public String translationKey() {
        return "litelights.cost." + name().toLowerCase(Locale.ROOT);
    }
}
