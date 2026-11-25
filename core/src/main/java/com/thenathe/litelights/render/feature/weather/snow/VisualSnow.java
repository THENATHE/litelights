package com.thenathe.litelights.render.feature.weather.snow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.overlays.pack.BetterGrass;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.climate.AlmanacVersion;
import com.thenathe.litelights.render.feature.climate.LocalClimate;
import com.thenathe.litelights.render.frame.DimensionGate;
import java.io.Reader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Visual snowlogging's snow in the chunk meshes (Weather page, Visual snow): fake snow on blocks
 * that can't hold snow and the snowy overlay over every snow's edges. Once a client tick it reads
 * the settings, the season's layer shares (Almanac's, or measured from the loaded land without it),
 * LambdaBetterGrass's better snow and the snow texture, publishes them as one {@link SnowState} for
 * the mesh threads, and builds again the sections a change reaches: every loaded one when a switch
 * or the climate changes, and only those with a column in a changed point bucket when a share passes
 * a step.
 *
 * <p>The models of the blocks that can take snow are wrapped as they bake ({@link VisualSnowModel});
 * nothing else is wrapped, and off, the wrappers add nothing.
 */
public final class VisualSnow {
    private static final String BETTER_GRASS_STATES = "bettergrass/states";
    /** How often far sections are forgotten, in ticks. */
    private static final int FORGET_EVERY = 100;
    private static volatile SnowState state = SnowState.OFF;
    /** Counts changes of what the visual snow draws: a switch, LambdaBetterGrass, or a share passing a step. */
    private static volatile long changes;
    private static volatile Set<Identifier> betterGrassLayerBlocks = Set.of();
    private static @Nullable SnowClimate almanac;
    private static final SnowShareMeter METER = new SnowShareMeter();
    private static Set<Block> betterGrassBlocks = Set.of();
    private static Set<Identifier> betterGrassBlocksFrom = Set.of();
    private static long climateChanges = -1;
    private static int ticks;
    private static int rebuiltSections;
    private static boolean warned;
    /** QA only: shares to draw by in place of the season's, or null. */
    private static volatile @Nullable SnowShares sharesForQa;

    private VisualSnow() {
    }

    /** Called once from the client entrypoint. */
    public static void register() {
        if (AlmanacVersion.shared()) {
            almanac = new AlmanacSnow();
        }
        PreparableModelLoadingPlugin.register(VisualSnow::readBetterGrass, (layerBlocks, context) -> {
            betterGrassLayerBlocks = layerBlocks;
            SnowHosts.forget();
            context.modifyBlockModelAfterBake().register(ModelModifier.WRAP_LAST_PHASE, (model, bake) -> {
                SnowHosts.Host host = SnowHosts.cached(bake.state());
                return host.any() ? new VisualSnowModel(model, host) : model;
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** How many times what the visual snow draws has changed, so the column map notes plants in snow again. */
    public static long changes() {
        return changes;
    }

    /** The state the mesh threads build by. */
    public static SnowState state() {
        return state;
    }

    /** Sections built again for a change of shares since the world was joined (for QA). */
    public static int rebuiltSections() {
        return rebuiltSections;
    }

    /** The share of snowable open ground measured as white, without Almanac (for QA). */
    public static double measuredShare() {
        return METER.share();
    }

    /** QA only: draws by these shares (every area alike) until cleared with null, and builds every loaded section again. */
    public static void setSharesForQa(@Nullable SnowShares shares) {
        sharesForQa = shares;
    }

    /** QA only: builds every loaded section again, as a switch would. */
    public static void rebuildForQa() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            rebuildLoaded(minecraft, minecraft.level);
        }
    }

    /** Sections known to hold blocks that can take snow (for QA). */
    public static int knownSections() {
        return SnowSections.known();
    }

    /** A mesh thread failed to add the snow: it is turned off on its row with the reason. */
    static void meshFailed(RuntimeException failure) {
        if (!warned) {
            warned = true;
            LiteLightsClient.LOG.error("Visual snow failed while a chunk was meshed", failure);
        }
        RenderFeatureStatus.failed(RenderFeature.VISUAL_SNOW, failure);
    }

    /** The world was left: nothing is kept of it. */
    public static void onWorldLeft() {
        state = SnowState.OFF;
        SnowSections.clear();
        METER.clear();
        climateChanges = -1;
        rebuiltSections = 0;
    }

    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        SnowState before = state;
        SnowState after = level == null ? SnowState.OFF : read(minecraft, level);
        state = after;
        if (level == null) {
            return;
        }
        if (!before.sameLook(after) || !before.shares().equals(after.shares())) {
            changes++;
        }
        if (!before.sameLook(after) && (before.drawsAny() || after.drawsAny())) {
            rebuildLoaded(minecraft, level);
        } else if (after.drawsAny() && after.blocks()) {
            long changes = LocalClimate.changes();
            if ((changes != climateChanges && climateChanges >= 0) || before.shares().seasonalChanged(after.shares())) {
                // The season changed, or an area's snow came or went altogether (snow that lies whatever
                // the season then shows by its neighbours instead): every noted section, warm or not.
                rebuiltSections += SnowSections.rebuildAll(level);
            } else if (!before.shares().equals(after.shares())) {
                rebuiltSections += SnowSections.rebuild(level, before.shares().changedBuckets(after.shares()));
            }
        }
        climateChanges = LocalClimate.changes();
        if (++ticks % FORGET_EVERY == 0 && minecraft.gameRenderer.mainCamera() != null) {
            SnowSections.forgetFar(SectionPos.of(minecraft.gameRenderer.mainCamera().position()),
                minecraft.options.getEffectiveRenderDistance() + 2);
        }
    }

    private static SnowState read(Minecraft minecraft, ClientLevel level) {
        RenderSettings.VisualSnow settings = RenderSettingsAccess.current().weather().visualSnow();
        boolean blocks = settings.blocks();
        boolean drips = settings.drips();
        if (!(blocks || drips) || !DimensionGate.hasWeather(level) || !RenderFeatureStatus.mayRun(RenderFeature.VISUAL_SNOW)) {
            return SnowState.OFF;
        }
        SnowClimate climate = almanac != null ? almanac : SnowClimate.VANILLA;
        SnowShares forced = sharesForQa;
        SnowShares shares = forced != null ? forced : climate.shares(level);
        boolean measured = shares == SnowShares.NONE;
        if (measured && blocks) {
            METER.update(level, climate);
            shares = SnowShares.oneLayer(METER.share());
        }
        TextureAtlasSprite sprite = minecraft.getModelManager().getBlockStateModelSet()
            .getParticleMaterial(Blocks.SNOW.defaultBlockState()).sprite();
        RenderFeatureStatus.succeeded(RenderFeature.VISUAL_SNOW);
        return new SnowState(blocks, drips, shares, measured, climate, betterSnow(), level, sprite);
    }

    /** LambdaBetterGrass's better snow now: on with the mod installed, its mode not off and its better layers on. */
    private static RealSnow.BetterSnow betterSnow() {
        if (!BetterGrass.installed()) {
            return RealSnow.BetterSnow.ABSENT;
        }
        BetterGrass.Settings settings = BetterGrass.settings();
        boolean on = settings.betterLayers() && settings.mode() != BetterGrass.Mode.OFF;
        Set<Identifier> ids = betterGrassLayerBlocks;
        if (ids != betterGrassBlocksFrom) {
            Set<Block> blocks = new HashSet<>();
            for (Identifier id : ids) {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
            }
            betterGrassBlocks = Set.copyOf(blocks);
            betterGrassBlocksFrom = ids;
        }
        RealSnow.BetterSnow now = new RealSnow.BetterSnow(on, betterGrassBlocks);
        RealSnow.BetterSnow last = state.betterSnow();
        return now.equals(last) ? last : now;
    }

    /** Builds every loaded section again: a switch, the texture or LambdaBetterGrass changed. */
    private static void rebuildLoaded(Minecraft minecraft, ClientLevel level) {
        if (minecraft.gameRenderer.mainCamera() == null) {
            return;
        }
        SectionPos camera = SectionPos.of(minecraft.gameRenderer.mainCamera().position());
        int reach = minecraft.options.getEffectiveRenderDistance() + 1;
        level.setSectionRangeDirty(camera.x() - reach, level.getMinSectionY(), camera.z() - reach,
            camera.x() + reach, level.getMaxSectionY(), camera.z() + reach);
    }

    /**
     * The blocks LambdaBetterGrass's resources give its snow layer ({@code bettergrass/states/<block>.json}
     * whose {@code type} is {@code layer}), read the way it reads them, as models load. Empty without it.
     */
    private static CompletableFuture<Set<Identifier>> readBetterGrass(
            net.minecraft.server.packs.resources.PreparableReloadListener.SharedState shared, java.util.concurrent.Executor executor) {
        if (!BetterGrass.installed()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            Set<Identifier> blocks = new HashSet<>();
            Map<Identifier, Resource> found = shared.resourceManager()
                .listResources(BETTER_GRASS_STATES, id -> id.getPath().endsWith(".json"));
            for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
                try (Reader reader = entry.getValue().openAsReader()) {
                    JsonObject definition = JsonParser.parseReader(reader).getAsJsonObject();
                    if (definition.has("type") && "layer".equals(definition.get("type").getAsString())) {
                        String path = entry.getKey().getPath();
                        String name = path.substring(BETTER_GRASS_STATES.length() + 1, path.length() - ".json".length());
                        blocks.add(Identifier.fromNamespaceAndPath(entry.getKey().getNamespace(), name));
                    }
                } catch (Exception unreadable) {
                    // A file LambdaBetterGrass can't read draws no snow either.
                }
            }
            return Set.copyOf(blocks);
        }, executor);
    }
}
