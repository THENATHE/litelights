package com.thenathe.litelights.mixin.overlays;

import com.thenathe.litelights.overlays.pack.OverlayBuilds;
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

/** Starts the overlay build the moment a reload knows its packs, ahead of every reload listener. */
@Mixin(ReloadableResourceManager.class)
abstract class ReloadableResourceManagerMixin {
    @Inject(method = "createReload", at = @At("HEAD"))
    private void litelights$startOverlayBuild(Executor backgroundExecutor, Executor mainThreadExecutor,
                                           CompletableFuture<Unit> initialTask, List<PackResources> resourcePacks,
                                           CallbackInfoReturnable<ReloadInstance> info) {
        OverlayBuilds.onReloadStarting(resourcePacks);
    }
}
