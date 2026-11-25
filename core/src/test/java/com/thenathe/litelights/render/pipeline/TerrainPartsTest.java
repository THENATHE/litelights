package com.thenathe.litelights.render.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.config.LiteLightsOptions;
import com.thenathe.litelights.render.pipeline.TerrainParts.Part;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Mac troubleshooting: the switch lines of terrain_parts.glsl against the Java parts, the shaders
 * that test them, the rows' defaults and the log line.
 */
class TerrainPartsTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/litelights/shaders");
    private static final Pattern SWITCH_LINE = Pattern.compile("(?m)^#define (LITE_TERRAIN_\\w+)[ \\t]*$");
    private static final List<String> HOSTS = List.of("core/terrain.fsh", "core/terrain.vsh", "core/block.fsh", "core/block.vsh",
        "sodium/block_layer_opaque.fsh", "sodium/block_layer_opaque.vsh");
    /** Hosts with LiteLights' fragment stage, each with a vanilla main for "Use vanilla terrain shading". */
    private static final List<String> FRAGMENT_HOSTS = List.of("core/terrain.fsh", "core/block.fsh", "sodium/block_layer_opaque.fsh");

    @Test
    void theIncludesSwitchLinesAreTheJavaParts() throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        expected.add(TerrainParts.LITELIGHTS_SHADING);
        for (Part part : Part.values()) {
            expected.add(part.define());
        }
        Set<String> lines = new LinkedHashSet<>();
        Matcher matcher = SWITCH_LINE.matcher(read("include/terrain_parts.glsl"));
        while (matcher.find()) {
            lines.add(matcher.group(1));
        }
        assertEquals(expected, lines);
    }

    @Test
    void eachSwitchLeavesOutOnlyItsOwnLine() throws IOException {
        String source = read("include/terrain_parts.glsl");
        List<String> defines = new ArrayList<>(List.of(TerrainParts.LITELIGHTS_SHADING));
        for (Part part : Part.values()) {
            defines.add(part.define());
        }
        for (String define : defines) {
            assertEquals(source, ReloadDefines.withDefine(source, define, true));
            String off = ReloadDefines.withDefine(source, define, false);
            assertFalse(Pattern.compile("(?m)^#define " + define + "[ \\t]*$").matcher(off).find(), define);
            assertEquals(source.lines().count(), off.lines().count(), define);
            assertEquals(source.replace("#define " + define + "\n", ""), off.replaceFirst("// " + define + " left out[^\n]*\n", ""), define);
        }
    }

    @Test
    void vanillaShadingLeavesOutEveryPart() throws IOException {
        String source = read("include/terrain_parts.glsl");
        int master = source.indexOf("#ifndef " + TerrainParts.LITELIGHTS_SHADING);
        assertTrue(master > 0, "the include undefines the parts when the master is left out");
        String block = source.substring(master, source.indexOf("#endif", master));
        for (Part part : Part.values()) {
            assertTrue(block.contains("#undef " + part.define() + "\n"), part.define());
        }
    }

    @Test
    void theHostsTestThePartsAndNothingSharedDoes() throws IOException {
        for (String host : HOSTS) {
            String source = read(host);
            int parts = source.indexOf("#include <litelights:terrain_parts.glsl>");
            assertTrue(parts > 0, host + " includes terrain_parts.glsl");
            int firstOther = source.indexOf("#include <litelights:", parts + 1);
            assertTrue(firstOther < 0 || source.lastIndexOf("#ifdef " + TerrainParts.LITELIGHTS_SHADING, firstOther) > parts,
                host + " includes LiteLights' other files only with LiteLights' shading");
        }
        for (String host : FRAGMENT_HOSTS) {
            String source = read(host);
            assertTrue(source.indexOf("void main()") != source.lastIndexOf("void main()"), host + " has a vanilla main");
        }
        // Every part is tested somewhere, and only by the hosts or inside lite_water_surface, which
        // only the hosts call: entities and items share the hook files and keep every part.
        StringBuilder testers = new StringBuilder();
        for (String host : HOSTS) {
            testers.append(read(host));
        }
        testers.append(read("include/water/surface.glsl"));
        for (Part part : Part.values()) {
            assertTrue(Pattern.compile("(#ifdef |defined\\()" + part.define() + "\\b").matcher(testers).find(), part.define() + " is tested");
        }
        Set<String> allowed = new LinkedHashSet<>(HOSTS);
        allowed.add("include/terrain_parts.glsl");
        allowed.add("include/water/surface.glsl");
        try (Stream<Path> files = Files.walk(SHADERS)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = SHADERS.relativize(file).toString().replace('\\', '/');
                if (!allowed.contains(name)) {
                    assertFalse(Files.readString(file).contains("LITE_TERRAIN_"), name + " must not test a Mac troubleshooting part");
                }
            }
        }
    }

    @Test
    void everyPartStartsInAndHasANewId() {
        assertFalse(LiteLightsOptions.VANILLA_TERRAIN_SHADING.on());
        assertEquals("general.mac.vanilla_terrain_shading", LiteLightsOptions.VANILLA_TERRAIN_SHADING.id());
        assertEquals("compatibility/mac", LiteLightsOptions.VANILLA_TERRAIN_SHADING.section());
        for (Part part : Part.values()) {
            assertTrue(part.option().on(), part.name());
            assertTrue(part.option().id().startsWith("general.mac.terrain_"), part.option().id());
            assertEquals("compatibility/mac", part.option().section());
            assertTrue(part.option().active(), part.name());
        }
        LiteLightsOptions.VANILLA_TERRAIN_SHADING.set(true);
        try {
            for (Part part : Part.values()) {
                assertFalse(part.option().active(), part.name() + " can't be changed while vanilla shading is on");
            }
        } finally {
            LiteLightsOptions.VANILLA_TERRAIN_SHADING.reset();
        }
    }

    @Test
    void theLogLineSaysWhatTheShadersLeftOut() {
        assertNull(TerrainParts.describe(null, part -> null));
        assertEquals("all terrain parts in", TerrainParts.describe(true, part -> true));
        assertEquals("all terrain parts in", TerrainParts.describe(true, part -> null));
        assertEquals("vanilla terrain shading (every terrain part compiled out)", TerrainParts.describe(false, part -> true));
        assertEquals("terrain parts compiled out: water, sway",
            TerrainParts.describe(true, part -> part != Part.WATER && part != Part.SWAY));
        assertEquals("terrain parts compiled out: water, caustics, glass weather, shadows, light extras, fog, sway",
            TerrainParts.describe(true, part -> false));
    }

    private static String read(String name) throws IOException {
        return Files.readString(SHADERS.resolve(name));
    }
}
