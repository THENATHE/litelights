package com.thenathe.litelights.overlays.continuity;

import com.thenathe.litelights.overlays.generation.OverlayGenerator;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Function;
import me.pepperbell.continuity.api.client.CachingPredicates;
import me.pepperbell.continuity.api.client.CtmLoader;
import me.pepperbell.continuity.api.client.CtmLoaderRegistry;
import me.pepperbell.continuity.api.client.CtmProperties;
import me.pepperbell.continuity.api.client.QuadProcessor;
import net.minecraft.resources.Identifier;

/**
 * The only place that registers anything with Continuity. Built against Continuity 3.0.1+26.3's
 * public API ({@code me.pepperbell.continuity.api.client}); nothing from its internals is used.
 */
final class ContinuityHooks {
    private ContinuityHooks() {
    }

    /** @return {@code null} when the method is registered, otherwise why it couldn't be */
    static String register() {
        String mismatch = apiMismatch();
        if (mismatch != null) {
            return mismatch;
        }
        CtmLoaderRegistry registry = CtmLoaderRegistry.get();
        CtmLoader<?> overlay = registry.getLoader("overlay");
        if (overlay == null) {
            return "Continuity has no 'overlay' method to build on";
        }
        registry.registerLoader(OverlayGenerator.VARIETY_METHOD, new VarietyLoader(overlay));
        if (!(registry.getLoader(OverlayGenerator.VARIETY_METHOD) instanceof VarietyLoader)) {
            return "Continuity didn't keep the registered method";
        }
        return null;
    }

    /**
     * Checks that the API still has every method the overlays module calls, and that the module
     * still implements every method Continuity will call. A Continuity that added an abstract method or
     * changed a signature would otherwise fail in the middle of a reload.
     */
    private static String apiMismatch() {
        List<Class<?>[]> implemented = List.of(
            new Class<?>[] {CtmLoader.class, VarietyLoader.class},
            new Class<?>[] {CtmProperties.Factory.class, VarietyLoader.PropertiesFactory.class},
            new Class<?>[] {QuadProcessor.Factory.class, VarietyLoader.ProcessorFactory.class},
            new Class<?>[] {QuadProcessor.class, VarietyProcessor.class});
        for (Class<?>[] pair : implemented) {
            for (Method required : pair[0].getMethods()) {
                if (!Modifier.isAbstract(required.getModifiers())) {
                    continue;
                }
                try {
                    Method found = pair[1].getMethod(required.getName(), required.getParameterTypes());
                    if (Modifier.isAbstract(found.getModifiers())) {
                        return pair[0].getSimpleName() + "." + required.getName() + " isn't implemented";
                    }
                } catch (NoSuchMethodException missing) {
                    return pair[0].getSimpleName() + "." + required.getName() + " is new and isn't implemented";
                }
            }
        }
        try {
            CtmLoaderRegistry.class.getMethod("get");
            CtmLoaderRegistry.class.getMethod("getLoader", String.class);
            CtmLoaderRegistry.class.getMethod("registerLoader", String.class, CtmLoader.class);
            CtmLoader.class.getMethod("getPropertiesFactory");
            CtmLoader.class.getMethod("getPredicatesFactory");
            CachingPredicates.Factory.class.getMethod("createPredicates", CtmProperties.class, Function.class);
            QuadProcessor.ProcessingContext.class.getMethod("getExtraQuadEmitter");
            CtmProperties.class.getMethod("getSpriteDependencies");
            Identifier.class.getMethod("parse", String.class);
        } catch (NoSuchMethodException missing) {
            return "Continuity's API no longer has " + missing.getMessage();
        }
        return null;
    }
}
