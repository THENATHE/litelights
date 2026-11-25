package com.thenathe.litelights.render.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.mixin.render.PreparedFrameAccessor;
import com.thenathe.litelights.render.pipeline.FilteringRenderPass;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.jspecify.annotations.Nullable;

/**
 * Translucent render types that other mods build without Improved Transparency pipelines, such as
 * the wakes of Wakes 1.4.1. With Improved Transparency on, vanilla 26.3 sends every translucent
 * draw through its OIT stages, and a render type without OIT pipelines throws there ("does not have
 * OIT pipelines set up"), which crashes the game with or without LiteLights.
 *
 * <p>Such a draw is left out of the three OIT stages ({@code mixin.render.PreparedRenderTypeMixin}),
 * and once vanilla has composited the OIT result into the main target, it is drawn there with the
 * render type's own pipeline, blended as it would be with Improved Transparency off. Those draws go
 * over every other translucent surface, which suits what such mods draw (marks on the water's
 * surface). Nothing changes for a frame without one, and nothing changes with Improved Transparency
 * off.
 */
public final class NoOitRenderTypes {
    private static final Set<String> REPORTED = new HashSet<>();
    private static boolean skippedThisFrame;
    private static boolean replaying;
    private static boolean drawingOne;
    private static boolean broken;

    private NoOitRenderTypes() {
    }

    /** Whether the draw after the composite failed and is off for the rest of the session. */
    public static boolean switchedOff() {
        return broken;
    }

    /** Called instead of an OIT stage's draw of a render type without OIT pipelines. */
    public static void skippedOitDraw(String renderType) {
        skippedThisFrame = true;
        if (REPORTED.add(renderType)) {
            LiteLightsClient.LOG.info("Render type {} has no Improved Transparency pipelines; it is drawn after the OIT composite instead", renderType);
        }
    }

    /**
     * Whether a render type's ordinary draw should be left out: during the replay after the
     * composite, everything that already went through the OIT stages is.
     */
    public static boolean skipOrdinaryDraw(boolean hasOitPipelines) {
        if (!replaying) {
            return false;
        }
        drawingOne = !hasOitPipelines;
        return hasOitPipelines;
    }

    public static void ordinaryDrawDone() {
        drawingOne = false;
    }

    /** After vanilla's OIT composite, with no pass open: the draws the OIT stages left out. */
    public static void drawSkipped(FeatureRenderDispatcher.PreparedFrame frame) {
        if (!skippedThisFrame) {
            return;
        }
        skippedThisFrame = false;
        if (broken) {
            return;
        }
        PreparedFrameAccessor accessor = (PreparedFrameAccessor) (Object) frame;
        FeatureFrameContext context = accessor.litelights$context();
        SubmitNodeStorage storage = accessor.litelights$submitNodeStorage();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (context == null || storage == null || main.getColorTextureView() == null) {
            return;
        }
        beginReplay();
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights translucent without OIT", main.getColorTextureView(), Optional.empty(),
            main.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            OnlyUnsortedDraws filtered = new OnlyUnsortedDraws(pass);
            for (SubmitNodeCollection collection : storage.getSubmitsPerOrder().values()) {
                accessor.litelights$executePhase(collection.oitTranslucent, context, null, filtered);
            }
        } catch (RuntimeException error) {
            // Left out from now on, as vanilla would have had to: the frame and the game go on.
            broken = true;
            LiteLightsClient.LOG.error("Couldn't draw the render types without Improved Transparency pipelines; they stay hidden", error);
        } finally {
            endReplay();
        }
    }

    static void beginReplay() {
        replaying = true;
    }

    static void endReplay() {
        replaying = false;
        drawingOne = false;
    }

    /** Whether the draw now under way goes through the replay's filter. */
    static boolean drawingOne() {
        return drawingOne;
    }

    /** Passes on only the draws of render types without OIT pipelines; the OIT stages drew the rest. */
    private static final class OnlyUnsortedDraws extends FilteringRenderPass {
        OnlyUnsortedDraws(RenderPass target) {
            begin(target);
        }

        @Override
        protected @Nullable CompiledRenderPipeline replacementFor(CompiledRenderPipeline pipeline) {
            return drawingOne() ? pipeline : null;
        }
    }
}
