package com.thenathe.litelights.overlays.continuity;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.function.Function;
import me.pepperbell.continuity.api.client.CachingPredicates;
import me.pepperbell.continuity.api.client.CtmLoader;
import me.pepperbell.continuity.api.client.CtmProperties;
import me.pepperbell.continuity.api.client.QuadProcessor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Continuity's loader for the variety method.
 *
 * <p>Each file is first read by Continuity's own {@code overlay} loader, through the public API.
 * That object is what Continuity keeps: it decides the file's place in the drawing order, puts
 * every piece named in {@code tiles} into the block atlas, and tells Continuity which blocks the
 * file applies to. This loader only adds the drawing: a processor that picks pieces by position.
 * Using Continuity's own properties object, rather than a new kind, keeps the drawing order
 * consistent with every other pack's files.
 */
final class VarietyLoader implements CtmLoader<CtmProperties> {
    /** What each file's {@code variety.*} keys said, by the properties object Continuity keeps for it. */
    private static final Map<CtmProperties, VarietySettings> SETTINGS = Collections.synchronizedMap(new WeakHashMap<>());
    /** LambdaBetterGrass's rules, read once per resource reload from the resources it reads them from. */
    private static final Map<ResourceManager, BetterGrassRules> BETTER_GRASS_RULES = Collections.synchronizedMap(new WeakHashMap<>());

    private final CtmLoader<CtmProperties> overlay;
    private final PropertiesFactory propertiesFactory = new PropertiesFactory();
    private final ProcessorFactory processorFactory = new ProcessorFactory();

    @SuppressWarnings("unchecked")
    VarietyLoader(CtmLoader<?> overlay) {
        // Only properties made by the overlay loader itself are ever handed back to it.
        this.overlay = (CtmLoader<CtmProperties>) overlay;
    }

    @Override
    public CtmProperties.Factory<CtmProperties> getPropertiesFactory() {
        return propertiesFactory;
    }

    @Override
    public QuadProcessor.Factory<CtmProperties> getProcessorFactory() {
        return processorFactory;
    }

    @Override
    public CachingPredicates.Factory<CtmProperties> getPredicatesFactory() {
        return overlay.getPredicatesFactory();
    }

    final class PropertiesFactory implements CtmProperties.Factory<CtmProperties> {
        @Override
        public CtmProperties createProperties(Properties properties, Identifier resourceId, PackResources pack, int packPriority,
                                              ResourceManager resourceManager, String method) {
            if (!VarietyMethod.isAvailable()) {
                return null;
            }
            try {
                VarietySettings settings = VarietySettings.parse(properties,
                    () -> BETTER_GRASS_RULES.computeIfAbsent(resourceManager, BetterGrassRules::load));
                CtmProperties base = overlay.getPropertiesFactory()
                    .createProperties(properties, resourceId, pack, packPriority, resourceManager, "overlay");
                if (base != null) {
                    SETTINGS.put(base, settings);
                }
                return base;
            } catch (LinkageError | RuntimeException failure) {
                VarietyMethod.failed("reading " + resourceId, failure);
                return null;
            }
        }
    }

    final class ProcessorFactory implements QuadProcessor.Factory<CtmProperties> {
        @Override
        public QuadProcessor createProcessor(CtmProperties properties, Function<Identifier, TextureAtlasSprite> spriteGetter) {
            VarietySettings settings = SETTINGS.get(properties);
            if (settings == null || !VarietyMethod.isAvailable()) {
                return VarietyProcessor.NOTHING;
            }
            try {
                return new VarietyProcessor(settings, spriteGetter);
            } catch (LinkageError | RuntimeException failure) {
                VarietyMethod.failed("setting up " + settings.folder(), failure);
                return VarietyProcessor.NOTHING;
            }
        }
    }
}
