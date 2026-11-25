package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.mixin.lightpost.RenderTypeInvoker;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Outline markers: a second, invisible copy of each thing that gets an enchantment outline, drawn
 * only into the outline's mask.
 *
 * <p>When an enchanted item is submitted in a hand (first person, or third person in the level) or
 * a piece of enchanted armour is worn within reach, its geometry is submitted once more with a
 * marker render type. In the game's own passes a marker draws nothing (its vertex shader puts every
 * corner off screen). The outline replays the prepared draws keeping only the markers, drawn for
 * real ({@link #MASK}): a silhouette in the colour the outline should glow (the glow colour, or a
 * trimmed piece's trim colour, carried as the vertex colour) with depth to compare with the level.
 *
 * <p>So the mask doesn't depend on vanilla's glint draws: the shimmer can be hidden without losing
 * the outline, held items are told apart from dropped and framed ones (which share every pipeline),
 * and each armour piece can carry its own colour.
 */
public final class OutlineMarkers {
    /** How far away something in the level still gets an outline, in blocks. */
    static final double REACH = 64.0;

    private static final Identifier SHADER = LiteLightsClient.id("outline/marker");
    /** Plain white, for markers of models whose own texture doesn't decide their shape (shields, tridents). */
    private static final Identifier PLAIN_TEXTURE = LiteLightsClient.id("textures/misc/outline_marker.png");

    private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexShader(SHADER)
        .withFragmentShader(SHADER)
        .withVertexBinding(0, DefaultVertexFormat.ENTITY)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withCull(false)
        .buildSnippet();

    /** In the game's passes: nothing reaches the screen, and nothing is written. */
    static final RenderPipeline HIDDEN = RenderPipeline.builder(SNIPPET)
        .withLocation(LiteLightsClient.id("pipeline/outline_marker"))
        .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
        .build();
    /** In the outline's mask: the marker's colour, and its depth in the level's convention (larger is nearer). */
    static final RenderPipeline MASK = RenderPipeline.builder(SNIPPET)
        .withLocation(LiteLightsClient.id("pipeline/outline_marker_mask"))
        .withShaderDefine("LITE_OUTLINE_MASK")
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .build();
    /** The mask's depth where nothing was drawn: the far end of {@link #MASK}'s depth. */
    static final float MASK_CLEAR_DEPTH = 0.0f;

    private final LightPostFrame lightPost;
    private final Map<Identifier, RenderType> renderTypes = new ConcurrentHashMap<>();
    /** Trim palettes read so far, by palette id; an empty array for one that couldn't be read. */
    private final Map<Identifier, int[]> palettes = new HashMap<>();
    private final MarkerCollector markerCollector = new MarkerCollector();
    private final QuadInstance quadInstance = new QuadInstance();
    private final Vector3f translation = new Vector3f();
    private final float[] colour = new float[3];

    private int levelMarkers;
    private int handMarkers;

    /** The item being submitted gets an outline if it is enchanted; it is in the level (or in the hand). */
    private boolean itemOutlined;
    private boolean itemInLevel;

    /** The first-person hand's items are being submitted, by vanilla or by a mod drawing them in its place. */
    private boolean submittingHand;

    /** The armour piece being submitted gets an outline, in this colour, with the layer texture last seen. */
    private boolean armourOutlined;
    private int armourColour;
    private @Nullable Identifier armourTexture;

    OutlineMarkers(LightPostFrame lightPost) {
        this.lightPost = lightPost;
    }

    // --- Frames ---------------------------------------------------------------------------------

    /** The level's submissions start. */
    public void beginLevel() {
        levelMarkers = 0;
    }

    /** The first-person hand's submissions start. */
    public void beginHand() {
        handMarkers = 0;
        submittingHand = true;
    }

    /** The first-person hand's submissions are over (or there was no hand to submit this frame). */
    public void endHand() {
        submittingHand = false;
    }

    int levelMarkers() {
        return levelMarkers;
    }

    int handMarkers() {
        return handMarkers;
    }

    /** Resources are reloading: palettes may have changed. */
    public void forgetPalettes() {
        palettes.clear();
    }

    // --- Items ----------------------------------------------------------------------------------

    /**
     * An item stack is about to be submitted. In the level, an item in someone's hand within reach
     * gets an outline; outside the level, an item in the first-person hand does. Dropped items, item
     * frames and heads don't.
     */
    public void beginItem(ItemDisplayContext context, PoseStack poseStack) {
        itemInLevel = RenderSystem.isRenderingLevel;
        boolean inHand = itemInLevel
            ? isThirdPersonHand(context) && withinReach(poseStack)
            : heldInFirstPerson(context, submittingHand);
        itemOutlined = inHand && outlinesOn(RenderSettingsAccess.current().outlines());
    }

    /**
     * Outside the level, whether an item is held in the first-person hand. Vanilla submits those
     * with first-person contexts, which the GUI never uses. First-person animation mods such as
     * Punchy! replace vanilla's hand with an arm model of their own, holding its items in
     * third-person contexts, in the same place and with their own pose, so the outline's replay of
     * the hand's draws follows them as it follows vanilla's. Outside the level those contexts
     * otherwise belong to the inventory's player preview, so they only count while the first-person
     * hand is being submitted.
     */
    static boolean heldInFirstPerson(ItemDisplayContext context, boolean submittingHand) {
        return context.firstPerson() || submittingHand && isThirdPersonHand(context);
    }

    private static boolean isThirdPersonHand(ItemDisplayContext context) {
        return context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }

    public void endItem() {
        itemOutlined = false;
    }

    /** Whether a layer of the current item, enchanted or not, gets an outline. */
    public boolean outlinesItem(boolean enchanted) {
        return itemOutlined && enchanted;
    }

    /** Whether outlined items and armour are drawn without vanilla's shimmer. */
    public boolean hidesShimmer() {
        return RenderSettingsAccess.current().outlines().hideShimmer();
    }

    /** Markers for an outlined item's quads: one per texture atlas the quads come from (nearly always one). */
    public void submitItemMarkers(SubmitNodeCollector collector, PoseStack poseStack, ItemQuads quads) {
        List<BakedQuad> all = quads.all();
        if (all.isEmpty()) {
            return;
        }
        int markerColour = userColour();
        Identifier firstAtlas = all.getFirst().materialInfo().sprite().atlasLocation();
        boolean oneAtlas = true;
        for (BakedQuad quad : all) {
            if (!quad.materialInfo().sprite().atlasLocation().equals(firstAtlas)) {
                oneAtlas = false;
                break;
            }
        }
        if (oneAtlas) {
            submitQuads(collector, poseStack, all, firstAtlas, markerColour);
        } else {
            Map<Identifier, List<BakedQuad>> byAtlas = new HashMap<>();
            for (BakedQuad quad : all) {
                byAtlas.computeIfAbsent(quad.materialInfo().sprite().atlasLocation(), atlas -> new ArrayList<>()).add(quad);
            }
            byAtlas.forEach((atlas, atlasQuads) -> submitQuads(collector, poseStack, atlasQuads, atlas, markerColour));
        }
        countMarker(itemInLevel);
    }

    private void submitQuads(SubmitNodeCollector collector, PoseStack poseStack, List<BakedQuad> quads, Identifier atlas, int markerColour) {
        collector.submitCustomGeometry(poseStack, renderType(atlas), (pose, buffer) -> {
            quadInstance.setColor(markerColour);
            for (BakedQuad quad : quads) {
                buffer.putBakedQuad(pose, quad, quadInstance);
            }
        });
    }

    /**
     * A collector that turns a special item model's submissions (a shield, a trident) into markers
     * in the real collector, and drops everything else.
     */
    public SubmitNodeCollector markerCollector(SubmitNodeCollector real) {
        markerCollector.begin(real, userColour());
        countMarker(itemInLevel);
        return markerCollector;
    }

    // --- Armour ---------------------------------------------------------------------------------

    /**
     * A piece of equipment is about to be submitted. Enchanted pieces worn within reach in the level
     * get an outline, in the trim's colour if they are trimmed and trim colours are on.
     */
    public void beginArmour(ItemStack stack, EquipmentClientInfo.LayerType layerType, PoseStack poseStack) {
        armourTexture = null;
        RenderSettings.Outlines outlines = RenderSettingsAccess.current().outlines();
        armourOutlined = RenderSystem.isRenderingLevel && stack.hasFoil() && outlines.wornArmour() && outlinesOn(outlines)
            && withinReach(poseStack);
        if (armourOutlined) {
            armourColour = armourColour(stack, layerType, outlines);
        }
    }

    public void endArmour() {
        armourOutlined = false;
        armourTexture = null;
    }

    public boolean outlinesArmour() {
        return armourOutlined;
    }

    /** The texture of the armour layer about to be submitted, which the marker needs for its cut-out. */
    public void armourLayerTexture(Identifier texture) {
        armourTexture = texture;
    }

    /** The marker for the armour layer just submitted. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void submitArmourMarker(OrderedSubmitNodeCollector collector, Model model, Object state, PoseStack poseStack,
                                   int lightCoords, int overlayCoords) {
        if (!armourOutlined || armourTexture == null) {
            return;
        }
        collector.submitModel(model, state, poseStack, renderType(armourTexture), lightCoords, overlayCoords, armourColour, null, 0);
        countMarker(true);
    }

    private int armourColour(ItemStack stack, EquipmentClientInfo.LayerType layerType, RenderSettings.Outlines outlines) {
        // Vanilla draws no trim on the baby layer, so there is no trim colour to show either.
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (outlines.trimColours() && trim != null && layerType != EquipmentClientInfo.LayerType.HUMANOID_BABY) {
            TrimMaterial material = trim.material().value();
            TextColor nameColour = material.description().getStyle().getColor();
            Integer nameRgb = nameColour != null ? nameColour.getValue() : null;
            int[] palette = nameRgb == null ? palette(material.paletteId()) : null;
            if (OutlineColours.trim(nameRgb, palette, outlines.saturation(), colour)) {
                return OutlineColours.packed(colour);
            }
        }
        return userColour();
    }

    /** The pixels of a trim palette ({@code textures/palettes/<id>.png}), or null if it can't be read. */
    private int @Nullable [] palette(Identifier paletteId) {
        int[] pixels = palettes.computeIfAbsent(paletteId, OutlineMarkers::readPalette);
        return pixels.length == 0 ? null : pixels;
    }

    private static int[] readPalette(Identifier paletteId) {
        Identifier location = paletteId.withPath(path -> "textures/palettes/" + path + ".png");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            return new int[0];
        }
        try (InputStream input = resource.get().open(); NativeImage image = NativeImage.read(input)) {
            return image.getPixels();
        } catch (IOException | RuntimeException error) {
            LiteLightsClient.LOG.debug("Couldn't read the trim palette {}", location, error);
            return new int[0];
        }
    }

    // --- Shared ---------------------------------------------------------------------------------

    /** The outline is on, visible and allowed here, and its shaders work. */
    private boolean outlinesOn(RenderSettings.Outlines outlines) {
        return outlines.enchantmentOutlines() && outlines.intensity() > 0.0f && lightPost.outlinesAllowed()
            && RenderFeatureStatus.mayRun(RenderFeature.ENCHANTMENT_OUTLINES);
    }

    /** The pose's origin is camera-relative in the level, so its length is the distance from the camera. */
    private boolean withinReach(PoseStack poseStack) {
        poseStack.last().pose().getTranslation(translation);
        return translation.lengthSquared() <= REACH * REACH;
    }

    private int userColour() {
        RenderSettings.Outlines outlines = RenderSettingsAccess.current().outlines();
        OutlineColours.linear(outlines.colour(), outlines.saturation(), colour);
        return OutlineColours.packed(colour);
    }

    private void countMarker(boolean inLevel) {
        if (inLevel) {
            levelMarkers++;
        } else {
            handMarkers++;
        }
    }

    /** A marker render type cutting out with this texture's alpha. */
    private RenderType renderType(Identifier texture) {
        return renderTypes.computeIfAbsent(texture, location -> RenderTypeInvoker.litelights$create("litelights_outline_marker",
            RenderSetup.builder(HIDDEN).withTexture("Sampler0", location).createRenderSetup()));
    }

    static RenderPipeline[] pipelines() {
        return new RenderPipeline[] {HIDDEN, MASK};
    }

    /** See {@link #markerCollector}. Every model, model part and its own texture's UVs become a plain marker. */
    private final class MarkerCollector extends DroppingCollector {
        private @Nullable SubmitNodeCollector real;
        private int markerColour;

        void begin(SubmitNodeCollector real, int markerColour) {
            this.real = real;
            this.markerColour = markerColour;
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords,
                                    int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
            if (real != null) {
                real.submitModel(model, state, poseStack, renderType(PLAIN_TEXTURE), lightCoords, overlayCoords, markerColour, null, 0);
            }
        }
    }
}
