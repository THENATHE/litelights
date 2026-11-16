package com.thenathe.litelights.mixin.overlayssodium;

import com.thenathe.litelights.overlays.sodium.QuadFacingFix.Status;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Which Sodium the face culling fix goes into, and how the plugin confirms it went in. */
class QuadFacingMixinPluginTest {
    private static final String FACING = "Lnet/caffeinemc/mods/sodium/client/model/quad/properties/ModelQuadFacing;";

    @Test
    void goesIntoTheQuadClassOf092() throws Exception {
        Map<String, ClassNode> classes = Map.of(
            QuadFacingMixinPlugin.QUAD_VIEW, quadView(true, true),
            QuadFacingMixinPlugin.FACING, facing());
        assertEquals(Status.APPLIED, QuadFacingMixinPlugin.classify(classes::get));
    }

    @Test
    void staysOutOfASodiumThatKeepsTheFacingInTheQuadData() throws Exception {
        Map<String, ClassNode> classes = Map.of(
            QuadFacingMixinPlugin.QUAD_VIEW, quadView(false, true),
            QuadFacingMixinPlugin.ENCODING_FORMAT, encodingFormat(true));
        assertEquals(Status.FIXED_IN_SODIUM, QuadFacingMixinPlugin.classify(classes::get));
    }

    @Test
    void staysOutOfAnythingElse() throws Exception {
        Map<String, ClassNode> noLoad = Map.of(
            QuadFacingMixinPlugin.QUAD_VIEW, quadView(true, false),
            QuadFacingMixinPlugin.FACING, facing(),
            QuadFacingMixinPlugin.ENCODING_FORMAT, encodingFormat(false));
        assertEquals(Status.UNKNOWN_SODIUM, QuadFacingMixinPlugin.classify(noLoad::get));
        Map<String, ClassNode> neither = Map.of(
            QuadFacingMixinPlugin.QUAD_VIEW, quadView(false, true),
            QuadFacingMixinPlugin.ENCODING_FORMAT, encodingFormat(false));
        assertEquals(Status.UNKNOWN_SODIUM, QuadFacingMixinPlugin.classify(neither::get));
    }

    /** The real jars, where the QA dependencies are fetched (qa/fetch_deps.py); skipped otherwise. */
    @Test
    void matchesTheSodiumReleaseAndKnowsTheBetaIsFixed() throws Exception {
        Path release = onlyJar(Path.of("qa/deps/sodium-release"));
        Path beta = onlyJar(Path.of("qa/deps/sodium-beta"));
        assumeTrue(release != null && beta != null, "Sodium jars not fetched");
        try (ZipFile releaseJar = new ZipFile(release.toFile()); ZipFile betaJar = new ZipFile(beta.toFile())) {
            assertEquals(Status.APPLIED, QuadFacingMixinPlugin.classify(name -> read(releaseJar, name)), release.getFileName().toString());
            assertEquals(Status.FIXED_IN_SODIUM, QuadFacingMixinPlugin.classify(name -> read(betaJar, name)), beta.getFileName().toString());
        }
    }

    @Test
    void reportsTheFixInOnlyWhenLoadCallsIt() {
        ClassNode target = quadView(true, true);
        assertFalse(QuadFacingMixinPlugin.loadCallsHandler(target));
        MethodNode load = target.methods.stream().filter(method -> method.name.equals("load")).findFirst().orElseThrow();
        load.instructions.insert(new MethodInsnNode(Opcodes.INVOKESPECIAL, "QuadViewImpl",
            "handler$zza000$" + QuadFacingMixinPlugin.HANDLER, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"));
        assertTrue(QuadFacingMixinPlugin.loadCallsHandler(target));
    }

    private static ClassNode quadView(boolean facingField, boolean loadMethod) {
        ClassNode node = new ClassNode();
        node.name = "net/caffeinemc/mods/sodium/client/render/model/QuadViewImpl";
        if (facingField) {
            node.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "normalFace", FACING, null, null));
        }
        if (loadMethod) {
            MethodNode load = new MethodNode(Opcodes.ACC_PUBLIC, "load", "()V", null, null);
            load.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(load);
        }
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "packedFaceNormal", "()I", null, null));
        return node;
    }

    private static ClassNode facing() {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fromPackedNormal", "(I)" + FACING, null, null));
        return node;
    }

    private static ClassNode encodingFormat(boolean facingInData) {
        ClassNode node = new ClassNode();
        if (facingInData) {
            node.methods.add(new MethodNode(Opcodes.ACC_STATIC, "normalFace", "(I)" + FACING, null, null));
        }
        return node;
    }

    private static Path onlyJar(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> jars = files.filter(path -> path.toString().endsWith(".jar")).toList();
            return jars.size() == 1 ? jars.getFirst() : null;
        }
    }

    private static ClassNode read(ZipFile jar, String name) throws IOException {
        try (InputStream stream = jar.getInputStream(jar.getEntry(name.replace('.', '/') + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }
}
