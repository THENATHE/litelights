package com.thenathe.litelights.render.modules;

import com.thenathe.litelights.api.ClimateAccess;
import com.thenathe.litelights.render.feature.climate.AlmanacVersion;

final class CoreClimateAccess implements ClimateAccess {
    @Override
    public boolean almanacAtLeast(String version) {
        return AlmanacVersion.atLeast(version);
    }
}
