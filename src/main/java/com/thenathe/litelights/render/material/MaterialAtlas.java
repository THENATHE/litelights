package com.thenathe.litelights.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.vegetation.VegetationMaterials;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The material texture: laid out exactly like the block atlas, each sprite's area filled with that
 * sprite's material id, so a shader reads a fragment's material from the atlas coordinate it already
 * has. No vertex format changes.
 *
 * <p>It is stored smaller than the atlas: one texel per grid cell, where the grid is the largest
 * power of two (up to 16) that every sprite's position and size are multiples of. The stitcher
 * aligns sprites for mipmapping, so the grid is normally 16 and the texture 1/256 of the atlas.
 *
 * <p>Built when the block atlas changes and a level is present (block tags come from the server),
 * on a background thread; uploaded on the render thread.
 */
public final class MaterialAtlas implements AutoCloseable {
    private record SpriteArea(int x, int y, int width, int height) {
    }

    private record Built(int width, int height, int gridShift, byte[] ids) {
    }

    private Map<Identifier, SpriteArea> sprites = Map.of();
    private int atlasWidth;
    private int atlasHeight;
    private boolean dirty;
    private @Nullable CompletableFuture<Built> building;
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private int gridShift;

    /** Called when the block atlas is (re)stitched. */
    public synchronized void onAtlasUploaded(int width, int height, Map<Identifier, TextureAtlasSprite> regions) {
        Map<Identifier, SpriteArea> areas = new HashMap<>();
        for (TextureAtlasSprite sprite : regions.values()) {
            // The atlas pads each sprite, and getX()/getY() give the padded slot; the texture the
            // shaders sample starts where its UV starts.
            int x = Math.round(sprite.getU0() * width);
            int y = Math.round(sprite.getV0() * height);
            areas.put(sprite.contents().name(), new SpriteArea(x, y, sprite.contents().width(), sprite.contents().height()));
        }
        sprites = areas;
        atlasWidth = width;
        atlasHeight = height;
        dirty = true;
    }

    /** A new level (its block tags come from the server): classify again. */
    public synchronized void requestRebuild() {
        dirty = true;
    }

    /** Once per frame on the render thread. */
    public synchronized void update(boolean levelPresent) {
        if (building != null && building.isDone()) {
            try {
                upload(building.join());
                RenderFeatureStatus.succeeded(RenderFeature.MATERIALS);
            } catch (Exception error) {
                RenderFeatureStatus.failed(RenderFeature.MATERIALS, error);
            }
            building = null;
        }
        if (dirty && levelPresent && building == null && atlasWidth > 0 && RenderFeatureStatus.mayRun(RenderFeature.MATERIALS)) {
            dirty = false;
            Map<Identifier, SpriteArea> spriteSnapshot = sprites;
            int width = atlasWidth;
            int height = atlasHeight;
            BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
            building = CompletableFuture.supplyAsync(() -> build(spriteSnapshot, width, height, models), Util.backgroundExecutor());
        }
    }

    public @Nullable GpuTextureView view() {
        return view;
    }

    public int gridShift() {
        return gridShift;
    }

    private static Built build(Map<Identifier, SpriteArea> sprites, int atlasWidth, int atlasHeight, BlockStateModelSet models) {
        Map<Identifier, Integer> materialBySprite = classifySprites(sprites, models);
        int grid = 16;
        for (SpriteArea area : sprites.values()) {
            grid = Math.min(grid, Integer.lowestOneBit(area.x | area.y | area.width | area.height | 16));
        }
        int shift = Integer.numberOfTrailingZeros(grid);
        int width = Math.max(1, atlasWidth >> shift);
        int height = Math.max(1, atlasHeight >> shift);
        byte[] ids = new byte[width * height];
        for (Map.Entry<Identifier, SpriteArea> entry : sprites.entrySet()) {
            int material = materialBySprite.getOrDefault(entry.getKey(), MaterialIds.DEFAULT);
            if (material == MaterialIds.DEFAULT) {
                continue;
            }
            SpriteArea area = entry.getValue();
            for (int y = area.y >> shift; y < (area.y + area.height) >> shift; y++) {
                for (int x = area.x >> shift; x < (area.x + area.width) >> shift; x++) {
                    ids[x + y * width] = (byte) material;
                }
            }
        }
        LiteLightsClient.LOG.info("Material texture built: {} sprites, {}x{} (grid {})", sprites.size(), width, height, grid);
        return new Built(width, height, shift, ids);
    }

    private static Map<Identifier, Integer> classifySprites(Map<Identifier, SpriteArea> sprites, BlockStateModelSet models) {
        Map<Identifier, Integer> result = new HashMap<>();
        for (Identifier sprite : sprites.keySet()) {
            int byName = MaterialClassifier.forSpriteName(sprite.getPath());
            if (byName != MaterialIds.DEFAULT) {
                result.put(sprite, byName);
            }
        }
        // Glowing is decided after every block state has been seen: a sprite glows only when every
        // state that uses it glows. Shared sprites otherwise went to whichever block came last, so
        // the beacon made glass (and obsidian) glow, and a lit furnace its unlit sides.
        Set<Identifier> usedGlowing = new HashSet<>();
        Set<Identifier> usedNotGlowing = new HashSet<>();
        RandomSource random = RandomSource.create(42L);
        List<BlockStateModelPart> parts = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int blockMaterial = MaterialClassifier.forBlock(state);
                parts.clear();
                random.setSeed(42L);
                models.get(state).collectParts(random, parts);
                if (blockMaterial != MaterialIds.DEFAULT) {
                    blockMaterial = VegetationMaterials.checkModel(blockMaterial, parts);
                }
                Set<Identifier> users = blockMaterial == MaterialIds.GLOWING ? usedGlowing : usedNotGlowing;
                for (BlockStateModelPart part : parts) {
                    for (Direction direction : Direction.values()) {
                        claimSprites(part.getQuads(direction), blockMaterial, users, result);
                    }
                    claimSprites(part.getQuads(null), blockMaterial, users, result);
                }
            }
        }
        for (Identifier sprite : usedGlowing) {
            int claimed = result.getOrDefault(sprite, MaterialIds.DEFAULT);
            result.put(sprite, MaterialClassifier.withGlow(claimed, usedNotGlowing.contains(sprite)));
        }
        return result;
    }

    private static void claimSprites(List<BakedQuad> quads, int blockMaterial, Set<Identifier> users, Map<Identifier, Integer> result) {
        for (int i = 0, count = quads.size(); i < count; i++) {
            Identifier sprite = quads.get(i).materialInfo().sprite().contents().name();
            users.add(sprite);
            if (blockMaterial != MaterialIds.DEFAULT && blockMaterial != MaterialIds.GLOWING) {
                int current = result.getOrDefault(sprite, MaterialIds.DEFAULT);
                result.put(sprite, MaterialClassifier.combine(blockMaterial, current));
            }
        }
    }

    private void upload(Built built) {
        var device = RenderSystem.getDevice();
        if (texture == null || texture.getWidth(0) != built.width || texture.getHeight(0) != built.height) {
            closeTexture();
            texture = device.createTexture("LiteLights materials", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.R8_UNORM, built.width, built.height, 1, 1);
            view = device.createTextureView(texture);
        }
        ByteBuffer data = MemoryUtil.memAlloc(built.ids.length);
        try {
            data.put(built.ids).flip();
            device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, built.width, built.height);
        } finally {
            MemoryUtil.memFree(data);
        }
        gridShift = built.gridShift;
    }

    private void closeTexture() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

    @Override
    public synchronized void close() {
        closeTexture();
    }
}
