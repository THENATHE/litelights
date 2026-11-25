package com.thenathe.litelights.mixin.render;

import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A resource reload compiles every shader again, so shader compile failures from before it are
 * forgotten as it starts: removing a resource pack that broke an include brings the feature back.
 * Trim palettes read for the enchantment outline's colours are forgotten too.
 */
@Mixin(ReloadableResourceManager.class)
abstract class ReloadableResourceManagerMixin {
    @Inject(method = "createReload", at = @At("HEAD"))
    private void litelights$forgetCompileFailures(Executor backgroundExecutor, Executor mainThreadExecutor,
                                                  CompletableFuture<Unit> initialTask, List<PackResources> resourcePacks,
                                                  CallbackInfoReturnable<ReloadInstance> info) {
        RenderFeatureStatus.clearCompileFailures();
        LightPost.get().markers().forgetPalettes();
    }
}
