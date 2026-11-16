package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * LiteLights on Distant Horizons' far terrain, through Distant Horizons' public API only
 * ({@code com.seibel.distanthorizons.api}). The only class that names Distant Horizons; it is loaded
 * only when Distant Horizons is installed.
 *
 * <ul>
 *   <li>Its fog: while LiteLights' haze applies, Distant Horizons' fog event is cancelled for the
 *   frame, so the two never stack. Nothing in its config is changed, so there is nothing to restore.</li>
 *   <li>Its terrain: just before Distant Horizons copies its colour texture into the main target,
 *   LiteLights shades that texture with its depth texture and projection, through the textures'
 *   Blaze3D wrappers. Everything Distant Horizons does afterwards, including fading the vanilla
 *   chunks into its terrain, then works from the shaded colour.</li>
 * </ul>
 * If the textures aren't available (Distant Horizons' own OpenGL renderer has no Blaze3D wrappers),
 * the far-terrain row says so and the terrain is left as Distant Horizons draws it.
 */
final class DistantHorizonsHook {
    private final FarTerrain owner;
    private final Matrix4f inverseProjection = new Matrix4f();
    private final float[] matrixValues = new float[16];
    private @Nullable String problem;
    private int shadedFrames;
    private long deferredEvents;

    private DistantHorizonsHook(FarTerrain owner) {
        this.owner = owner;
    }

    static DistantHorizonsHook register(FarTerrain owner) {
        DistantHorizonsHook hook = new DistantHorizonsHook(owner);
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, new DhApiBeforeFogRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiBeforeFogRenderEvent.EventParam> event) {
                if (owner.replacesDistantHorizonsFog()) {
                    event.cancelEvent();
                }
            }
        });
        DhApi.events.bind(DhApiBeforeRenderPassEvent.class, new DhApiBeforeRenderPassEvent() {
            @Override
            public void beforeRender(DhApiEventParam<DhApiRenderParam> event) {
                hook.beforePass(event.value);
            }
        });
        DhApi.events.bind(DhApiBeforeDeferredRenderEvent.class, new DhApiBeforeDeferredRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                hook.beforeDeferred();
            }
        });
        DhApi.events.bind(DhApiBeforeApplyShaderRenderEvent.class, new DhApiBeforeApplyShaderRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                hook.beforeApply(event.value);
            }
        });
        return hook;
    }

    /** True while Distant Horizons is set to draw its terrain. */
    boolean drawing() {
        var configs = DhApi.Delayed.configs;
        return DhApi.Delayed.renderProxy != null && configs != null && Boolean.TRUE.equals(configs.graphics().renderingEnabled().getValue());
    }

    /**
     * Before each of Distant Horizons' render passes. Just before the translucent one its depth
     * texture holds only the ground, which far water compares with the depth after it.
     */
    private void beforePass(DhApiRenderParam render) {
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null || render == null || !RenderFeatureStatus.mayRun(RenderFeature.FAR_TERRAIN)) {
            return;
        }
        try {
            if (render.renderPass == EDhApiRenderPass.OPAQUE_AND_TRANSPARENT) {
                owner.distantHorizonsCombinedPass();
            } else if (render.renderPass == EDhApiRenderPass.TRANSPARENT) {
                captureGround(proxy);
            }
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.FAR_WATER, error);
        }
    }

    /**
     * Before Distant Horizons draws its translucent terrain on its own, after the opaque terrain
     * (its default "complete" transparency): the other moment its depth holds only the ground.
     */
    private void beforeDeferred() {
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null || !RenderFeatureStatus.mayRun(RenderFeature.FAR_TERRAIN)) {
            return;
        }
        deferredEvents++;
        try {
            captureGround(proxy);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.FAR_WATER, error);
        }
    }

    private void captureGround(IDhApiRenderProxy proxy) {
        Object[] depth = blazeTexture(proxy.getDhDepthTextureBlazeWrapper());
        if (depth != null) {
            owner.distantHorizonsBeforeTranslucents((GpuTexture) depth[0]);
        }
    }

    private void beforeApply(DhApiRenderParam render) {
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null || !RenderFeatureStatus.mayRun(RenderFeature.FAR_TERRAIN)) {
            return;
        }
        try {
            Object[] color = blazeTexture(proxy.getDhColorTextureBlazeWrapper());
            Object[] depth = blazeTexture(proxy.getDhDepthTextureBlazeWrapper());
            if (color == null || depth == null) {
                reportProblem("Distant Horizons' textures aren't shared with this renderer; its far terrain is left as it draws it");
                return;
            }
            // Distant Horizons' matrices are row-major (m03 is the x translation); JOML's are column-major.
            render.dhInverseMvmProjectionMatrix.putValuesInArray(matrixValues);
            inverseProjection.set(matrixValues).transpose();
            boolean reversed = proxy.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z;
            boolean zeroToOne = proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE;
            owner.shadeDistantHorizons((GpuTexture) color[0], (GpuTextureView) color[1], (GpuTextureView) depth[1], inverseProjection, reversed, zeroToOne);
            shadedFrames++;
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.FAR_TERRAIN, error);
        }
    }

    /** The texture and view behind a Blaze3D wrapper: Distant Horizons hands them over as an array. */
    private static Object @Nullable [] blazeTexture(DhApiResult<IDhApiBlazeTextureWrapper> result) {
        if (result == null || !result.success || result.payload == null) {
            return null;
        }
        Object wrapped = result.payload.getWrappedMcObject();
        if (wrapped instanceof Object[] parts && parts.length >= 2 && parts[0] instanceof GpuTexture && parts[1] instanceof GpuTextureView) {
            return parts;
        }
        return null;
    }

    private void reportProblem(String reason) {
        if (problem == null) {
            problem = reason;
            LiteLightsClient.LOG.warn(reason);
            Feature.of("far_terrain").markUnavailable(reason);
        }
    }

    String describe() {
        return "drawing=" + drawing() + ", shadedFrames=" + shadedFrames + ", deferredEvents=" + deferredEvents + (problem != null ? ", problem=" + problem : "");
    }
}
