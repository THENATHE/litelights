package com.thenathe.litelights.render.feature.water;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * What the Enhanced water colours need to know about the water textures in use: each water
 * sprite's average colour (every frame), and whether the water the game draws is vanilla's own
 * texture, the only case where LiteLights' bundled large textures may stand in for it (a resource
 * pack's or another mod's water keeps its own pixels).
 *
 * <p>Worked out once per sprite after each atlas upload, from the texture file the sprite was
 * stitched from, on the render thread (two or three small images).
 */
final class WaterSpriteLooks {
    private static final Identifier VANILLA_STILL = Identifier.withDefaultNamespace("block/water_still");
    private static final Identifier VANILLA_FLOW = Identifier.withDefaultNamespace("block/water_flow");
    private static final String VANILLA_PACK = "vanilla";

    private final Map<Identifier, float[]> means = new HashMap<>();
    private final Map<Identifier, Boolean> fromVanilla = new HashMap<>();

    /** The atlas was stitched again: sprites and the packs behind them may have changed. */
    void forget() {
        means.clear();
        fromVanilla.clear();
    }

    /** The sprite's average colour, 0..1 per channel; vanilla's grey for no sprite. */
    float[] mean(@Nullable TextureAtlasSprite sprite) {
        if (sprite == null) {
            return SpriteMean.FALLBACK;
        }
        return means.computeIfAbsent(sprite.contents().name(), WaterSpriteLooks::readMean);
    }

    /** Whether these are vanilla's water sprites, drawn from vanilla's own textures. */
    boolean vanillaWater(@Nullable TextureAtlasSprite still, @Nullable TextureAtlasSprite flow) {
        return still != null && flow != null
            && still.contents().name().equals(VANILLA_STILL) && flow.contents().name().equals(VANILLA_FLOW)
            && fromVanillaPack(VANILLA_STILL) && fromVanillaPack(VANILLA_FLOW);
    }

    private boolean fromVanillaPack(Identifier sprite) {
        return fromVanilla.computeIfAbsent(sprite, id -> texture(id)
            .map(resource -> VANILLA_PACK.equals(resource.sourcePackId())).orElse(false));
    }

    private static float[] readMean(Identifier sprite) {
        Optional<Resource> resource = texture(sprite);
        if (resource.isEmpty()) {
            return SpriteMean.FALLBACK;
        }
        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            return SpriteMean.of(image.getPixelsABGR());
        } catch (IOException | RuntimeException unreadable) {
            return SpriteMean.FALLBACK;
        }
    }

    private static Optional<Resource> texture(Identifier sprite) {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        return resources.getResource(sprite.withPath(path -> "textures/" + path + ".png"));
    }
}
