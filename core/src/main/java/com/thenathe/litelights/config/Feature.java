package com.thenathe.litelights.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A piece of LiteLights that one or more options belong to, such as "shadows" or "bloom". The
 * feature's owner reports its state here and every row of the feature follows it.
 *
 * <p>Every feature starts {@link RowState.Ready}: all of LiteLights' features are built. A piece
 * that isn't yet would be marked {@link #markComingSoon()} where its option is registered, and its
 * rows would stay greyed out while it is.
 */
public final class Feature {
    private static final Map<String, Feature> FEATURES = new ConcurrentHashMap<>();

    private final String id;
    private final List<Option<?>> options = Collections.synchronizedList(new ArrayList<>());
    private volatile RowState state = RowState.READY;

    private Feature(String id) {
        this.id = id;
    }

    /** The feature with this id, created on first use. */
    public static Feature of(String id) {
        return FEATURES.computeIfAbsent(id, Feature::new);
    }

    public String id() {
        return id;
    }

    public RowState state() {
        return state;
    }

    public void markReady() {
        state = RowState.READY;
    }

    public void markComingSoon() {
        state = RowState.COMING_SOON;
    }

    public void markUnavailable(String reason) {
        state = new RowState.Unavailable(reason);
    }

    public void markHandledBy(String modName) {
        state = new RowState.HandledBy(modName);
    }

    /**
     * Reports an error. The row shows {@code reason} and a Retry button; pressing it puts the
     * feature back to ready and runs {@code retry}, which should call this again if it fails.
     */
    public void markFailed(String reason, Runnable retry) {
        state = new RowState.Failed(reason, retry);
    }

    /** Called by the Retry button. */
    public void retry() {
        if (state instanceof RowState.Failed failed) {
            state = RowState.READY;
            failed.retry().run();
        }
    }

    /** The options of this feature in the order they were registered; the first one leads. */
    public List<Option<?>> options() {
        synchronized (options) {
            return List.copyOf(options);
        }
    }

    /** The row that shows the feature's reason and Retry button; the others are only greyed. */
    public boolean isLead(Option<?> option) {
        synchronized (options) {
            return !options.isEmpty() && options.getFirst() == option;
        }
    }

    void add(Option<?> option) {
        options.add(option);
    }
}
