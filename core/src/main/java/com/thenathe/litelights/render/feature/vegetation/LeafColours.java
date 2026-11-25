package com.thenathe.litelights.render.feature.vegetation;

import com.mojang.blaze3d.platform.NativeImage;
import com.thenathe.litelights.render.feature.water.SpriteMean;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The colours a block shows in the world, as far as fallen and falling leaves need them: how a leaf
 * block looks on average (its texture's average colour times its tint, so oak follows the biome's
 * foliage, birch and spruce keep their fixed colours and cherry, azalea and pale oak their own), and
 * the tint the ground's top is drawn with.
 *
 * <p>Texture averages are read once per sprite from the texture file it was stitched from (the
 * first frame and any others alike, weighted by alpha, so the holes in a leaf texture don't darken
 * it) and kept until the block atlas is stitched again. Render thread only.
 */
public final class LeafColours {
    private static final int WHITE = 0xFFFFFF;
    private static final Map<Identifier, Integer> MEANS = new HashMap<>();

    private LeafColours() {
    }

    /** The block atlas was stitched again: textures may have changed. */
    public static void forget() {
        MEANS.clear();
    }

    /** How a leaf block at {@code pos} looks on average, as 0xRRGGBB. */
    public static int leaves(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
        int mean = MEANS.computeIfAbsent(sprite.contents().name(), LeafColours::readMean);
        return multiply(mean, tint(state, level, pos));
    }

    /** The tint a block's faces are drawn with (its first tint layer), or white for an untinted block. */
    public static int tint(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
        return source == null ? WHITE : source.colorInWorld(state, level, pos) & WHITE;
    }

    static int multiply(int first, int second) {
        int red = ((first >> 16) & 0xFF) * ((second >> 16) & 0xFF) / 255;
        int green = ((first >> 8) & 0xFF) * ((second >> 8) & 0xFF) / 255;
        int blue = (first & 0xFF) * (second & 0xFF) / 255;
        return red << 16 | green << 8 | blue;
    }

    private static int readMean(Identifier sprite) {
        float[] mean = SpriteMean.FALLBACK;
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager()
            .getResource(sprite.withPath(path -> "textures/" + path + ".png"));
        if (resource.isPresent()) {
            try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
                mean = SpriteMean.of(image.getPixelsABGR());
            } catch (IOException | RuntimeException unreadable) {
                mean = SpriteMean.FALLBACK;
            }
        }
        return Math.round(mean[0] * 255.0f) << 16 | Math.round(mean[1] * 255.0f) << 8 | Math.round(mean[2] * 255.0f);
    }
}
