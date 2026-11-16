package com.thenathe.litelights.mixin.overlayssodium;

import com.thenathe.litelights.overlays.sodium.QuadFacingFix;
import com.thenathe.litelights.overlays.sodium.QuadFacingFix.Status;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/**
 * Lets the face culling fix into Sodium only when Sodium and Continuity are both installed and
 * Sodium's quad class is the one the fix was written against (0.9.2): a {@code normalFace} field
 * that {@code load()} leaves alone. Anything else keeps the mixin out, and the Overlays page says
 * why. A Sodium that keeps the facing inside the quad's data (0.9.3 betas) doesn't need it.
 */
public final class QuadFacingMixinPlugin implements IMixinConfigPlugin {
    static final String HANDLER = "litelights$takeOwnFacing";
    static final String QUAD_VIEW = "net.caffeinemc.mods.sodium.client.render.model.QuadViewImpl";
    static final String ENCODING_FORMAT = "net.caffeinemc.mods.sodium.client.render.model.EncodingFormat";
    static final String FACING = "net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing";
    private static final String FACING_DESCRIPTOR = "L" + FACING.replace('.', '/') + ";";

    private boolean apply;

    @Override
    public void onLoad(String mixinPackage) {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("sodium")) {
            QuadFacingFix.checked(Status.NO_SODIUM, "");
        } else if (!loader.isModLoaded("continuity")) {
            QuadFacingFix.checked(Status.NO_CONTINUITY, "");
        } else {
            Status verdict;
            String detail = "Sodium's QuadViewImpl isn't the one this fix was written for";
            try {
                // Read without loading or transforming: these classes must not load before mixins apply.
                verdict = classify(name -> MixinService.getService().getBytecodeProvider().getClassNode(name, false));
            } catch (Exception | LinkageError failure) {
                verdict = Status.UNKNOWN_SODIUM;
                detail = "couldn't read Sodium's quad classes (" + failure + ")";
            }
            apply = verdict == Status.APPLIED;
            if (!apply) {
                QuadFacingFix.checked(verdict, detail);
            }
        }
    }

    /** Reads a class's bytes into a tree, by dotted name. */
    @FunctionalInterface
    interface ClassReader {
        ClassNode read(String name) throws Exception;
    }

    /**
     * What Sodium's quad classes say about the fix: {@link Status#APPLIED} when it fits (the plugin
     * still waits for the mixin to go in before reporting it applied), {@link Status#FIXED_IN_SODIUM}
     * when the facing lives in the quad's data instead of a field, otherwise
     * {@link Status#UNKNOWN_SODIUM}.
     */
    static Status classify(ClassReader classes) throws Exception {
        ClassNode quadView = classes.read(QUAD_VIEW);
        boolean facingField = hasField(quadView, "normalFace", FACING_DESCRIPTOR);
        if (facingField && hasMethod(quadView, "load", "()V", false) && hasMethod(quadView, "packedFaceNormal", "()I", false)
            && hasMethod(classes.read(FACING), "fromPackedNormal", "(I)" + FACING_DESCRIPTOR, true)) {
            return Status.APPLIED;
        }
        if (!facingField && hasMethod(classes.read(ENCODING_FORMAT), "normalFace", "(I)" + FACING_DESCRIPTOR, true)) {
            return Status.FIXED_IN_SODIUM;
        }
        return Status.UNKNOWN_SODIUM;
    }

    private static boolean hasField(ClassNode node, String name, String descriptor) {
        return node.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(descriptor)
            && (field.access & Opcodes.ACC_STATIC) == 0);
    }

    private static boolean hasMethod(ClassNode node, String name, String descriptor, boolean isStatic) {
        return node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor)
            && ((method.access & Opcodes.ACC_STATIC) != 0) == isStatic);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return apply;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    /** The injection is optional, so confirm {@code load()} really calls it before reporting the fix in place. */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (loadCallsHandler(targetClass)) {
            QuadFacingFix.markApplied();
        } else {
            QuadFacingFix.checked(Status.UNKNOWN_SODIUM, "the fix couldn't attach to Sodium's QuadViewImpl.load()");
        }
    }

    static boolean loadCallsHandler(ClassNode targetClass) {
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals("load") || !method.desc.equals("()V")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.contains(HANDLER)) {
                    return true;
                }
            }
        }
        return false;
    }
}
