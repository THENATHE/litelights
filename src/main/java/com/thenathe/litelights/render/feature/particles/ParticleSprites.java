package com.thenathe.litelights.render.feature.particles;

import com.mojang.blaze3d.platform.NativeImage;
import com.thenathe.litelights.LiteLightsClient;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Texture coordinates of every kind's sprites in the particle atlas. The atlas picks up everything
 * under {@code assets/litelights/textures/particle/}, so no atlas file is needed. Coordinates are
 * looked up again whenever the atlas has been rebuilt (a resource reload).
 *
 * <p>Soft billboards (mist, haze, dust clouds, snow) are trimmed to their sprite's content: their
 * sprites are soft round puffs in the middle of a mostly clear square, and a large puff near the
 * camera drew every clear texel too. The trimmed quad covers the same texels, so it looks the same
 * and asks the GPU for about half the pixels. The trim is read from the image itself, so a
 * resource pack's own sprites are trimmed to theirs.
 */
final class ParticleSprites {
    /** Fire's decals, which lie on block faces and aren't particle kinds: a sprite name and its frames. */
    enum Decal {
        BURN_MARK("scorch", 2),
        EMBER_VEIN("ember_vein", 4);

        final String name;
        final int frames;

        Decal(String name, int frames) {
            this.name = name;
            this.frames = frames;
        }

        List<String> spriteNames() {
            return IntStream.range(0, frames).mapToObj(frame -> name + "_" + frame).toList();
        }
    }

    /**
     * Per kind id, per frame: u0, u1, v0, v1, then the trimmed quad's half width and half height
     * as shares of the full square's (1 for kinds that aren't trimmed, whose UVs are the sprite's).
     */
    private final float[][][] normal = new float[ParticleKind.all().size()][][];
    private final float[][][] hd = new float[ParticleKind.all().size()][][];
    private final float[][][] decals = new float[Decal.values().length][][];
    private boolean ready;
    /** QA only: draw every sprite whole, to time what the trim saves. */
    boolean trimOff;

    /** The particle atlas was stitched again: every sprite moved, so the coordinates are read anew. */
    void forget() {
        ready = false;
    }

    /** Reads the sprite coordinates if the atlas changed since; returns whether sprites are usable. */
    boolean refresh() {
        if (ready) {
            return true;
        }
        TextureAtlas atlas;
        try {
            atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
        } catch (RuntimeException notYet) {
            return false;
        }
        int missing = 0;
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        for (ParticleKind kind : ParticleKind.all()) {
            ResourceManager trimFrom = trimmed(kind) && !trimOff ? resources : null;
            normal[kind.id] = resolve(atlas, kind.sprites, trimFrom);
            hd[kind.id] = resolve(atlas, kind.hdSprites, trimFrom);
            for (String name : kind.sprites) {
                if (atlas.getSprite(LiteLightsClient.id(name)) == atlas.missingSprite()) {
                    missing++;
                }
            }
        }
        for (Decal decal : Decal.values()) {
            decals[decal.ordinal()] = resolve(atlas, decal.spriteNames(), null);
            for (String name : decal.spriteNames()) {
                if (atlas.getSprite(LiteLightsClient.id(name)) == atlas.missingSprite()) {
                    missing++;
                }
            }
        }
        if (missing > 0) {
            LiteLightsClient.LOG.warn("{} particle sprites are missing from the particle atlas", missing);
        }
        ready = true;
        return true;
    }

    /** Soft billboards are the large see-through puffs worth trimming; everything else is drawn whole. */
    static boolean trimmed(ParticleKind kind) {
        return kind.layer == ParticleKind.Layer.SOFT && kind.shape == ParticleKind.Shape.BILLBOARD;
    }

    /** Each sprite's coordinates; trimmed to its content when {@code trimFrom} is given. */
    private static float[][] resolve(TextureAtlas atlas, List<String> names, ResourceManager trimFrom) {
        float[][] frames = new float[names.size()][];
        for (int i = 0; i < names.size(); i++) {
            TextureAtlasSprite sprite = atlas.getSprite(LiteLightsClient.id(names.get(i)));
            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float v0 = sprite.getV0();
            float v1 = sprite.getV1();
            float[] trim = trimFrom == null || sprite == atlas.missingSprite() ? NO_TRIM : readTrim(trimFrom, names.get(i));
            float middleU = (u0 + u1) * 0.5f;
            float middleV = (v0 + v1) * 0.5f;
            float halfU = (u1 - u0) * 0.5f * trim[0];
            float halfV = (v1 - v0) * 0.5f * trim[1];
            frames[i] = new float[] {middleU - halfU, middleU + halfU, middleV - halfV, middleV + halfV, trim[0], trim[1]};
        }
        return frames;
    }

    private static final float[] NO_TRIM = {1.0f, 1.0f};

    /** The trim of one sprite's image, or none if it can't be read. */
    private static float[] readTrim(ResourceManager resources, String name) {
        Optional<Resource> resource = resources.getResource(LiteLightsClient.id("textures/particle/" + name + ".png"));
        if (resource.isEmpty()) {
            return NO_TRIM;
        }
        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    argb[y * width + x] = image.getPixel(x, y);
                }
            }
            return trimToContent(argb, width, height);
        } catch (IOException | RuntimeException unreadable) {
            LiteLightsClient.LOG.warn("Couldn't read particle sprite {} to trim it; drawing it whole", name, unreadable);
            return NO_TRIM;
        }
    }

    /**
     * The smallest quad, centred on the square like the whole sprite, that still holds every texel
     * that isn't fully clear: its half width and half height as shares of the square's, in whole
     * texels so the trimmed quad maps exactly the same texels. A fully clear sprite stays whole.
     */
    static float[] trimToContent(int[] argb, int width, int height) {
        int minX = width;
        int maxX = -1;
        int minY = height;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((argb[y * width + x] >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0 || width % 2 != 0 || height % 2 != 0) {
            return NO_TRIM;
        }
        int halfWidth = width / 2;
        int halfHeight = height / 2;
        int reachX = Math.max(halfWidth - minX, maxX + 1 - halfWidth);
        int reachY = Math.max(halfHeight - minY, maxY + 1 - halfHeight);
        return new float[] {reachX / (float) halfWidth, reachY / (float) halfHeight};
    }

    float[][] frames(ParticleKind kind, boolean hdStyle) {
        return hdStyle ? hd[kind.id] : normal[kind.id];
    }

    float[][] frames(Decal decal) {
        return decals[decal.ordinal()];
    }
}
