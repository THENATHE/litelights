package com.thenathe.litelights.overlays;

import com.thenathe.litelights.overlays.generation.SourceTexture;
import com.thenathe.litelights.overlays.pack.PackStack;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;

/**
 * The game's own textures and models, read from the Minecraft jar on the test classpath, so tests
 * can check the overlays against what vanilla 26.3 really ships.
 */
public final class VanillaAssets {
    private static final String PROBE = "/assets/minecraft/textures/block/stone.png";
    private static PackStack packs;

    private VanillaAssets() {
    }

    /** A block texture by its path under {@code textures/block}, such as {@code stone_bricks}. */
    public static SourceTexture blockTexture(String name) {
        String id = "minecraft:block/" + name;
        try (InputStream stream = VanillaAssets.class.getResourceAsStream("/assets/minecraft/textures/block/" + name + ".png")) {
            if (stream == null) {
                throw new IllegalArgumentException("vanilla has no texture " + name);
            }
            return firstFrame(id, ImageIO.read(stream));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The texture a model names, such as {@code minecraft:block/grass_block_top}. */
    public static SourceTexture texture(String id) {
        return blockTexture(id.substring(id.indexOf("block/") + "block/".length()));
    }

    /** The vanilla jar as the only resource pack, for reading blockstates and models. */
    public static synchronized PackStack packs() {
        if (packs == null) {
            URL probe = VanillaAssets.class.getResource(PROBE);
            if (probe == null) {
                throw new IllegalStateException("the Minecraft jar isn't on the test classpath");
            }
            String jar = probe.toString();
            URI root = URI.create(jar.substring(0, jar.indexOf("!/") + 2));
            FileSystem files;
            try {
                files = FileSystems.newFileSystem(root, Map.of());
            } catch (FileSystemAlreadyExistsException alreadyOpen) {
                files = FileSystems.getFileSystem(root);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            Path top = files.getPath("/");
            PackLocationInfo info = new PackLocationInfo("vanilla", Component.literal("vanilla"), PackSource.BUILT_IN, Optional.empty());
            packs = new PackStack(List.of(new PathPackResources(info, top)));
        }
        return packs;
    }

    private static SourceTexture firstFrame(String id, BufferedImage image) {
        int size = image.getWidth();
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = image.getRGB(x, y);
            }
        }
        return new SourceTexture(id, size, pixels, image.getHeight() > size);
    }
}
