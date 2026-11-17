package com.thenathe.litelights.render.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.feature.lightpost.EnchantmentOutline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Shader loop safety caps: the switch line of loop_caps.glsl, each cap against the most the Java
 * side and the shaders' own call sites ask for, and an audit of every loop in LiteLights' shaders.
 */
class ShaderLoopCapsTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/litelights/shaders");
    private static final Pattern DEFINE_LINE = Pattern.compile("(?m)^#define LITE_LOOP_CAPS\\s*$");
    private static final Pattern LOOP = Pattern.compile("\\b(for|while)\\s*\\(([^;{]*?;)?([^;{]*?)(;[^{]*)?\\)\\s*\\{");

    /**
     * Loops whose bound isn't a constant, each read by hand (DECISIONS, caps097). A new loop of that
     * kind fails the audit until it has a cap or a reason here.
     */
    private static final Map<String, String> REVIEWED = Map.of(
        "include/water/reflection.glsl|i <= steps", "capped: LITE_WATER_TRACE_MAX_STEPS",
        "include/water/reflection.glsl|r < refinements", "capped: LITE_WATER_TRACE_MAX_REFINEMENTS",
        "post/outline_level_spread.fsh|i <= reach", "capped: LITE_OUTLINE_MAX_REACH",
        "include/farterrain/far_water.glsl|s < farSteps", "both callers pass a literal, 8 and 3 (the STEPS table's length is 8)",
        "include/farterrain/far_water.glsl|i < found", "found counts at most one shore per direction, so at most 8",
        "include/farterrain/far_water.glsl|j >= 0 && heights[j] > value", "j starts below i and only falls");

    @Test
    void capsOnKeepsTheIncludeAsWritten() throws IOException {
        String source = read("include/loop_caps.glsl");
        assertTrue(DEFINE_LINE.matcher(source).find(), "loop_caps.glsl must define LITE_LOOP_CAPS on a line of its own");
        assertEquals(source, ReloadDefines.withDefine(source, ShaderLoopCaps.DEFINE, true));
    }

    @Test
    void capsOffLeavesOutOnlyTheDefine() throws IOException {
        String source = read("include/loop_caps.glsl");
        String off = ReloadDefines.withDefine(source, ShaderLoopCaps.DEFINE, false);
        assertFalse(DEFINE_LINE.matcher(off).find());
        assertTrue(off.contains("#ifdef LITE_LOOP_CAPS"));
        assertTrue(off.contains("#define LITELIGHTS_LOOP_CAPS_GLSL"));
        assertEquals(source.lines().count(), off.lines().count());
    }

    @Test
    void reflectionCapsSitAboveFineDetail() throws IOException {
        String reflection = read("include/water/reflection.glsl");
        int maxSteps = Arrays.stream(RenderSettings.MirrorDetail.values()).mapToInt(RenderSettings.MirrorDetail::reflectionSteps).max().orElseThrow();
        int stepsCap = constant(reflection, "LITE_WATER_TRACE_MAX_STEPS");
        assertTrue(stepsCap >= maxSteps * 3 / 2, "step cap " + stepsCap + " too close to " + maxSteps);

        // Refinements are chosen by the callers in surface.glsl: the last argument of each trace.
        int maxRefinements = 0;
        Matcher call = Pattern.compile("lite_water_trace\\(([^;]*)\\);").matcher(read("include/water/surface.glsl"));
        int calls = 0;
        while (call.find()) {
            String arguments = call.group(1);
            String last = arguments.substring(arguments.lastIndexOf(',') + 1);
            // "steps > 16 ? 5 : 3" picks 5 or 3; the 16 is the test, not a count.
            last = last.substring(last.indexOf('?') + 1);
            Matcher number = Pattern.compile("\\b\\d+\\b").matcher(last);
            while (number.find()) {
                maxRefinements = Math.max(maxRefinements, Integer.parseInt(number.group()));
            }
            calls++;
        }
        assertEquals(2, calls, "water and ice each trace once");
        int refinementsCap = constant(reflection, "LITE_WATER_TRACE_MAX_REFINEMENTS");
        assertTrue(maxRefinements > 0 && refinementsCap > maxRefinements, "refinement cap " + refinementsCap + " against " + maxRefinements);
    }

    @Test
    void outlineCapSitsAboveTheWidestBlur() throws IOException {
        int cap = constant(read("post/outline_level_spread.fsh"), "LITE_OUTLINE_MAX_REACH");
        assertTrue(cap >= EnchantmentOutline.MAX_TAPS * 3 / 2, "reach cap " + cap + " too close to " + EnchantmentOutline.MAX_TAPS);
    }

    @Test
    void cappedShadersIncludeTheSwitch() throws IOException {
        assertTrue(read("include/water/reflection.glsl").contains("#include <litelights:loop_caps.glsl>"));
        assertTrue(read("post/outline_level_spread.fsh").contains("#include <litelights:loop_caps.glsl>"));
    }

    /** Every loop is bounded by a constant in its condition, or has been reviewed above, and no body moves its counter. */
    @Test
    void everyLoopHasAConstantBoundOrAReview() throws IOException {
        List<String> unreviewed = new ArrayList<>();
        int loops = 0;
        try (Stream<Path> files = Files.walk(SHADERS)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = SHADERS.relativize(file).toString().replace('\\', '/');
                String source = withoutComments(Files.readString(file));
                Matcher loop = LOOP.matcher(source);
                while (loop.find()) {
                    loops++;
                    String condition = loop.group(3).trim();
                    if (!constantBound(condition) && !REVIEWED.containsKey(name + "|" + condition)) {
                        unreviewed.add(name + ": " + condition);
                    }
                    // A constant bound only holds if the body leaves the counter alone.
                    Matcher counter = Pattern.compile("int\\s+(\\w+)").matcher(loop.group(2) == null ? "" : loop.group(2));
                    if (counter.find() && changesCounter(body(source, loop.end() - 1), counter.group(1))) {
                        unreviewed.add(name + ": the body of '" + condition + "' changes " + counter.group(1));
                    }
                }
            }
        }
        assertTrue(loops >= 30, "the audit found only " + loops + " loops");
        assertTrue(unreviewed.isEmpty(), "loops without a constant bound: " + unreviewed);
    }

    /** A conjunct of the condition compares a name with a literal or an upper-case constant. */
    private static boolean constantBound(String condition) {
        for (String part : condition.split("&&")) {
            if (part.trim().matches("\\w+\\s*(<|<=)\\s*(\\d+|[A-Z][A-Z0-9_]*)")) {
                return true;
            }
        }
        return false;
    }

    /** The loop body from its opening brace to the matching closing one. */
    private static String body(String source, int openingBrace) {
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(openingBrace, index + 1);
            }
        }
        return source.substring(openingBrace);
    }

    private static boolean changesCounter(String body, String counter) {
        return Pattern.compile("\\b" + counter + "\\s*(\\+\\+|--|[-+*/]?=(?!=))|(\\+\\+|--)" + counter + "\\b").matcher(body).find();
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", "");
    }

    private static int constant(String source, String name) {
        Matcher matcher = Pattern.compile("const int " + name + " = (\\d+);").matcher(source);
        assertTrue(matcher.find(), name + " not found");
        return Integer.parseInt(matcher.group(1));
    }

    private static String read(String path) throws IOException {
        return Files.readString(SHADERS.resolve(path));
    }
}
