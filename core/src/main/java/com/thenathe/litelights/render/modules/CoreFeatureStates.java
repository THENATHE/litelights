package com.thenathe.litelights.render.modules;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.thenathe.litelights.api.DimensionAspect;
import com.thenathe.litelights.api.FeatureStates;
import com.thenathe.litelights.api.ModuleFeature;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import java.util.EnumMap;
import java.util.Map;

/** The modules' features are core's RenderFeature and DimensionOptions.Aspect of the same names. */
final class CoreFeatureStates implements FeatureStates {
    private final Map<ModuleFeature, RenderFeature> features = renderFeatures();
    private final Map<DimensionAspect, Aspect> aspects = aspects();

    static Map<ModuleFeature, RenderFeature> renderFeatures() {
        Map<ModuleFeature, RenderFeature> byName = new EnumMap<>(ModuleFeature.class);
        for (ModuleFeature feature : ModuleFeature.values()) {
            byName.put(feature, RenderFeature.valueOf(feature.name()));
        }
        return byName;
    }

    static Map<DimensionAspect, Aspect> aspects() {
        Map<DimensionAspect, Aspect> byName = new EnumMap<>(DimensionAspect.class);
        for (DimensionAspect aspect : DimensionAspect.values()) {
            byName.put(aspect, Aspect.valueOf(aspect.name()));
        }
        return byName;
    }

    @Override
    public boolean mayRun(ModuleFeature feature) {
        return RenderFeatureStatus.mayRun(features.get(feature));
    }

    @Override
    public void failed(ModuleFeature feature, Throwable error) {
        RenderFeatureStatus.failed(features.get(feature), error);
    }

    @Override
    public void succeeded(ModuleFeature feature) {
        RenderFeatureStatus.succeeded(features.get(feature));
    }

    @Override
    public void claimPipelines(ModuleFeature feature, RenderPipeline... pipelines) {
        PipelineOwners.claim(features.get(feature), pipelines);
    }

    @Override
    public void markUnavailable(String settingsFeature, String reason) {
        Feature.of(settingsFeature).markUnavailable(reason);
    }

    @Override
    public boolean dimensionAllows(DimensionAspect aspect) {
        return DimensionGate.allows(aspects.get(aspect));
    }
}
