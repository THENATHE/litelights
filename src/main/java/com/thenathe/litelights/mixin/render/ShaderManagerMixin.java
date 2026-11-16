package com.thenathe.litelights.mixin.render;

import com.thenathe.litelights.render.startup.StartupTrace;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When a resource reload starts compiling shaders and when its pipelines are in place, for the start-up log.
 * Both hooks are optional: if a Minecraft update renames these methods, the log loses two lines instead of
 * the game failing to start.
 */
@Mixin(ShaderManager.class)
abstract class ShaderManagerMixin {
    @Inject(method = "reload", at = @At("HEAD"), require = 0)
    private void litelights$shadersStarting(CallbackInfoReturnable<?> info) {
        StartupTrace.reloadStarting();
    }

    @Inject(method = "apply", at = @At("TAIL"), require = 0)
    private void litelights$shadersReady(CallbackInfo info) {
        StartupTrace.reloadFinished();
    }
}
