package com.thenathe.litelights.render.api;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.Feature;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The health of each render feature, for the settings screen. A feature that throws is switched
 * off and retried after a growing delay (5 s, 10 s, 20 s, up to 5 min) instead of staying off for
 * the session; the settings row shows {@link #problem} and can call {@link #retryNow}.
 *
 * <p>A shader that fails to compile is retried the same way, but only a few times: the same source
 * fails the same way, so after {@link #COMPILE_ATTEMPTS} attempts it waits for the Retry button or
 * the next resource reload (a resource pack removed or fixed), which clears every compile failure.
 */
public final class RenderFeatureStatus {
    private static final long FIRST_RETRY_MILLIS = 5_000L;
    private static final long LONGEST_RETRY_MILLIS = 300_000L;
    private static final int COMPILE_ATTEMPTS = 3;
    private static final long SAME_ATTEMPT_MILLIS = 1_000L;
    private static final long NEVER = Long.MAX_VALUE;

    private static final Map<RenderFeature, Entry> ENTRIES = new EnumMap<>(RenderFeature.class);

    private RenderFeatureStatus() {
    }

    private static final class Entry {
        @Nullable String problem;
        int failures;
        long retryAtMillis;
        boolean permanent;
        /** The problem is a shader that didn't compile; cleared on every resource reload. */
        boolean fromCompile;
        /**
         * The original shader stands in for the broken one. The game keeps that pipeline until the
         * next resource reload, so only a reload can try the LiteLights shader again.
         */
        boolean needsReload;
        long lastCompileFailureMillis = Long.MIN_VALUE / 2;
    }

    /** Records a failure that may go away (a GPU hiccup, a resource reload mid-build). */
    public static synchronized void failed(RenderFeature feature, Throwable error) {
        Entry entry = ENTRIES.computeIfAbsent(feature, key -> new Entry());
        long now = System.currentTimeMillis();
        if (entry.fromCompile && now < entry.retryAtMillis) {
            // The feature tripped over the pipeline whose compile failure was just recorded; that
            // entry already holds the reason and the retry schedule.
            return;
        }
        entry.fromCompile = false;
        entry.needsReload = false;
        entry.failures++;
        entry.problem = error.getClass().getSimpleName() + ": " + error.getMessage();
        long delay = retryDelay(entry.failures);
        entry.retryAtMillis = now + delay;
        LiteLightsClient.LOG.error("{} failed (attempt {}), retrying in {} s", feature, entry.failures, delay / 1000, error);
        if (feature.settingsFeature() != null) {
            Feature.of(feature.settingsFeature()).markFailed(entry.problem, () -> retryNow(feature));
        }
    }

    /**
     * Records a LiteLights shader of the feature that failed to compile. {@code hasFallback} says
     * whether the original shader was compiled in its place.
     */
    public static synchronized void compileFailed(RenderFeature feature, String problem, boolean hasFallback) {
        Entry entry = ENTRIES.computeIfAbsent(feature, key -> new Entry());
        if (entry.permanent) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!entry.fromCompile) {
            entry.failures = 0;
        }
        entry.fromCompile = true;
        entry.needsReload |= hasFallback;
        entry.problem = problem;
        // One request for a pipeline compiles it up to twice (the current pipeline cache, then the
        // fallback cache), and a reload compiles a feature's pipelines together: count that as one try.
        if (now - entry.lastCompileFailureMillis >= SAME_ATTEMPT_MILLIS) {
            entry.failures++;
        }
        entry.lastCompileFailureMillis = now;
        boolean givingUp = entry.needsReload || entry.failures >= COMPILE_ATTEMPTS;
        entry.retryAtMillis = givingUp ? NEVER : now + retryDelay(entry.failures);
        if (givingUp) {
            LiteLightsClient.LOG.error("{} stays off until Retry or the next resource reload: {}", feature, problem);
        }
        if (feature.settingsFeature() != null) {
            Feature.of(feature.settingsFeature()).markFailed(problem, () -> retryNow(feature));
        }
    }

    /**
     * Forgets every shader compile failure, so the next compile decides afresh. Called as a
     * resource reload starts, before any pipeline is compiled again.
     */
    public static synchronized void clearCompileFailures() {
        List<String> rows = new ArrayList<>();
        ENTRIES.entrySet().removeIf(entry -> {
            if (!entry.getValue().fromCompile) {
                return false;
            }
            String row = entry.getKey().settingsFeature();
            if (row != null) {
                rows.add(row);
            }
            return true;
        });
        markReady(rows.toArray(String[]::new));
    }

    private static long retryDelay(int failures) {
        return Math.min(LONGEST_RETRY_MILLIS, FIRST_RETRY_MILLIS << Math.min(failures - 1, 16));
    }

    /** Records a problem that retrying can't fix (an unsupported mod version). */
    public static synchronized void markUnsupported(RenderFeature feature, String problem) {
        Entry entry = ENTRIES.computeIfAbsent(feature, key -> new Entry());
        entry.problem = problem;
        entry.permanent = true;
        if (feature.settingsFeature() != null) {
            Feature.of(feature.settingsFeature()).markUnavailable(problem);
        }
    }

    /** Whether the feature may run now: never failed, or its retry delay has passed. */
    public static synchronized boolean mayRun(RenderFeature feature) {
        Entry entry = ENTRIES.get(feature);
        return entry == null || (!entry.permanent && System.currentTimeMillis() >= entry.retryAtMillis);
    }

    public static synchronized void succeeded(RenderFeature feature) {
        Entry entry = ENTRIES.get(feature);
        if (entry != null && !entry.permanent) {
            ENTRIES.remove(feature);
            if (feature.settingsFeature() != null) {
                Feature.of(feature.settingsFeature()).markReady();
            }
        }
    }

    /**
     * Marks settings features ready once the render features behind them really draw, which clears
     * a state left by an earlier failure. Rows whose feature has a lasting problem (an unsupported
     * version, a shader that didn't compile) are left as they are.
     */
    public static synchronized void markReady(String... settingsFeatures) {
        for (String id : settingsFeatures) {
            boolean blocked = false;
            for (RenderFeature feature : RenderFeature.values()) {
                Entry entry = ENTRIES.get(feature);
                boolean lasting = entry != null && (entry.permanent || entry.fromCompile);
                blocked |= lasting && id.equals(feature.settingsFeature());
            }
            if (!blocked) {
                Feature.of(id).markReady();
            }
        }
    }

    /** A short reason for the settings row, or null when the feature is healthy. */
    public static synchronized @Nullable String problem(RenderFeature feature) {
        Entry entry = ENTRIES.get(feature);
        return entry == null ? null : entry.problem;
    }

    /**
     * The settings row's retry button. A shader whose original stands in for it can only be
     * compiled again by a resource reload, so that is what Retry starts.
     */
    public static void retryNow(RenderFeature feature) {
        boolean reload;
        synchronized (RenderFeatureStatus.class) {
            Entry entry = ENTRIES.get(feature);
            if (entry == null || entry.permanent) {
                return;
            }
            reload = entry.needsReload;
            if (entry.fromCompile) {
                entry.failures = 0;
            }
            entry.retryAtMillis = 0L;
        }
        if (reload) {
            Minecraft.getInstance().reloadResourcePacks();
        }
    }
}
