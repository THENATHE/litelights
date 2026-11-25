package com.thenathe.litelights.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.thenathe.litelights.render.pipeline.ReloadDefines;
import com.thenathe.litelights.render.startup.StartupTrace;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When a resource reload starts compiling shaders and when its pipelines are in place, for the start-up log.
 * Both hooks are optional: if a Minecraft update renames these methods, the log loses two lines instead of
 * the game failing to start. The include hook is required: without it LiteLights' reload switches
 * (ReloadDefines, Shader loop safety caps among them) couldn't be switched off.
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

    /** Sets the switch lines of LiteLights' includes as each reload reads them (ReloadDefines). */
    @WrapOperation(method = "loadInclude", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/pipeline/ShaderSource$CachedIncludeSource;create(Lnet/minecraft/resources/Identifier;Ljava/lang/String;)Lcom/mojang/renderpearl/api/pipeline/ShaderSource$CachedIncludeSource;"))
    private static ShaderSource.CachedIncludeSource litelights$reloadDefines(Identifier include, String source,
                                                                              Operation<ShaderSource.CachedIncludeSource> create) {
        return create.call(include, ReloadDefines.loaded(include, source));
    }
}
