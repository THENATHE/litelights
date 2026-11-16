package com.thenathe.litelights.overlays.pack;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * Read access to the resource packs of one reload, minus the generated pack itself, so the
 * generator sees exactly what the player's pack order shows and never reads its own output.
 */
public final class PackStack {
    /** Lowest priority first, the same order the resource manager uses. */
    private final List<PackResources> packs;

    public PackStack(List<PackResources> packs) {
        this.packs = List.copyOf(packs);
    }

    /** The bytes of the highest-priority copy of a resource, or {@code null} if no pack has it. */
    public byte[] read(Identifier location) throws IOException {
        for (int i = packs.size() - 1; i >= 0; i--) {
            IoSupplier<InputStream> supplier = packs.get(i).getResource(PackType.CLIENT_RESOURCES, location);
            if (supplier != null) {
                try (InputStream stream = supplier.get()) {
                    return stream.readAllBytes();
                }
            }
        }
        return null;
    }

    /** Every file under {@code directory} in every pack, with the pack that holds it. */
    public void listAll(String directory, BiConsumer<PackResources, Identifier> visitor) {
        for (PackResources pack : packs) {
            for (String namespace : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                pack.listResources(PackType.CLIENT_RESOURCES, namespace, directory,
                    (location, supplier) -> visitor.accept(pack, location));
            }
        }
    }

    public byte[] readFrom(PackResources pack, Identifier location) throws IOException {
        IoSupplier<InputStream> supplier = pack.getResource(PackType.CLIENT_RESOURCES, location);
        if (supplier == null) {
            return null;
        }
        try (InputStream stream = supplier.get()) {
            return stream.readAllBytes();
        }
    }
}
