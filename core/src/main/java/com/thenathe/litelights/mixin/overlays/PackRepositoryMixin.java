package com.thenathe.litelights.mixin.overlays;

import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.overlays.pack.GeneratedPackSource;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the generated overlay pack to the client's resource pack repository (the one holding
 * vanilla's packs), unless the overlays module is off for the session.
 */
@Mixin(PackRepository.class)
abstract class PackRepositoryMixin {
    @Shadow
    @Final
    @Mutable
    private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void litelights$addOverlayPack(RepositorySource[] given, CallbackInfo info) {
        boolean clientRepository = sources.stream().anyMatch(ClientPackSource.class::isInstance);
        if (clientRepository && OverlaysModule.active()) {
            Set<RepositorySource> withGenerated = new LinkedHashSet<>(sources);
            withGenerated.add(new GeneratedPackSource());
            sources = withGenerated;
        }
    }
}
