package com.thenathe.litelights.overlays.pack;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;

/**
 * Lists the generated pack in the client's pack repository: always on, pinned to the top, so it
 * shows in the Resource Packs screen but can't be dragged around or switched off there (the
 * settings screen is where overlays are turned off).
 */
public final class GeneratedPackSource implements RepositorySource {
    private static final PackLocationInfo LOCATION = new PackLocationInfo(GeneratedPack.ID,
        Component.translatable("litelights.overlays.pack.title"), PackSource.BUILT_IN, Optional.empty());
    private static final PackSelectionConfig SELECTION = new PackSelectionConfig(true, Pack.Position.TOP, true);

    @Override
    public void loadPacks(Consumer<Pack> result) {
        Pack.Metadata metadata = new Pack.Metadata(Component.translatable("litelights.overlays.pack.description"),
            PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
        result.accept(new Pack(LOCATION, new Pack.ResourcesSupplier() {
            @Override
            public PackMetadataResources openMetadata(PackLocationInfo location) {
                return new GeneratedPack(location);
            }

            @Override
            public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata ignored) {
                return Stream.of(new GeneratedPack(location));
            }
        }, metadata, SELECTION));
    }
}
