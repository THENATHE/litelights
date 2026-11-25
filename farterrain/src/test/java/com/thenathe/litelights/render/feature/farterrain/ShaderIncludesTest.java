package com.thenathe.litelights.render.feature.farterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Shaders can't be fenced off by the build the way classes are, so this test does it: the far
 * terrain module's shaders include only their own files and the include files the API lists
 * (api/shader-includes.txt), and core's shaders include none of the module's. The paths come
 * from farterrain/build.gradle.
 */
class ShaderIncludesTest {
    private static final Pattern INCLUDE = Pattern.compile("^[ \\t]*#[ \\t]*include[ \\t]*<([a-z0-9_.-]+):([^>]+)>", Pattern.MULTILINE);

    private static final String MODULE = System.getProperty("litelights.module", "farterrain");
    private static final Path MODULE_SHADERS = path("litelights.moduleShaders");
    private static final Path CORE_SHADERS = path("litelights.coreShaders");
    private static final Path API_LIST = path("litelights.shaderIncludeList");

    private record Include(Path file, int line, String namespace, String path) {
        @Override
        public String toString() {
            return file + ":" + line + " includes <" + namespace + ":" + path + ">";
        }
    }

    @Test
    void moduleShadersIncludeOnlyTheirOwnFilesAndTheApi() {
        Set<String> allowed = apiList();
        List<Include> refused = new ArrayList<>();
        for (Include include : includes(MODULE_SHADERS)) {
            if (!include.namespace().equals("litelights")) {
                continue; // the game's and Sodium's own includes aren't LiteLights'
            }
            boolean own = include.path().startsWith(MODULE + "/") && Files.isRegularFile(MODULE_SHADERS.resolve("include").resolve(include.path()));
            if (!own && !allowed.contains(include.path())) {
                refused.add(include);
            }
        }
        assertEquals(List.of(), refused, "includes outside the module and outside api/shader-includes.txt");
    }

    @Test
    void moduleShadersStayInTheModulesOwnFolders() {
        List<String> stray;
        try (Stream<Path> files = Files.walk(MODULE_SHADERS)) {
            stray = files.filter(Files::isRegularFile)
                .map(file -> MODULE_SHADERS.relativize(file).toString().replace('\\', '/'))
                .filter(name -> !name.startsWith(MODULE + "/") && !name.startsWith("include/" + MODULE + "/"))
                .sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        assertEquals(List.of(), stray, "a module's shaders go under shaders/" + MODULE + "/ and shaders/include/" + MODULE + "/");
    }

    @Test
    void everyListedIncludeIsInCoresLibrary() {
        Set<String> listed = apiList();
        assertFalse(listed.isEmpty());
        List<String> missing = listed.stream()
            .filter(name -> !Files.isRegularFile(CORE_SHADERS.resolve("include").resolve(name)))
            .sorted().toList();
        assertEquals(List.of(), missing, "api/shader-includes.txt names files core's include library doesn't have");
    }

    @Test
    void coreShadersIncludeNothingOfTheModule() {
        List<Include> reaching = includes(CORE_SHADERS).stream()
            .filter(include -> include.namespace().equals("litelights") && include.path().startsWith(MODULE + "/"))
            .toList();
        assertEquals(List.of(), reaching, "core's shaders reach into the " + MODULE + " module");
    }

    @Test
    void theCheckSeesTheModulesIncludes() {
        // Guards the test itself: a pattern that matched nothing would let everything through.
        assertTrue(includes(MODULE_SHADERS).stream().anyMatch(include -> include.path().equals(MODULE + "/far_light.glsl")));
    }

    private static Set<String> apiList() {
        try {
            return Files.readAllLines(API_LIST).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toSet());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static List<Include> includes(Path shaders) {
        List<Include> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(shaders)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String source = Files.readString(file);
                Matcher matcher = INCLUDE.matcher(source);
                while (matcher.find()) {
                    int line = (int) source.substring(0, matcher.start()).lines().count() + 1;
                    found.add(new Include(shaders.relativize(file), line, matcher.group(1), matcher.group(2)));
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return found;
    }

    private static Path path(String property) {
        String value = System.getProperty(property);
        if (value == null) {
            throw new IllegalStateException(property + " isn't set; run the test through Gradle (farterrain/build.gradle)");
        }
        return Path.of(value);
    }
}
