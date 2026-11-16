package com.thenathe.litelights.config;

/**
 * What a feature's rows show besides their value. Feature owners set it through {@link Feature};
 * the settings screen reads it every frame, so a change shows at once.
 *
 * <p>Reasons are plain text or translation keys: the screen shows the translation when one
 * exists, otherwise the text as written.
 */
public sealed interface RowState {
    /** The feature works; the row is a normal setting. */
    record Ready() implements RowState {
    }

    /** Not built yet. The row is greyed out with "Coming soon"; its value is kept and saved. */
    record ComingSoon() implements RowState {
    }

    /**
     * Turned off for a reason the player can't change from here, such as another mod doing the
     * same job ("Big Water is installed") or a graphics API that can't run it.
     */
    record Unavailable(String reason) implements RowState {
    }

    /**
     * Another mod draws this, so LiteLights steps aside. The row is greyed out with
     * "Handled by {@code modName}".
     */
    record HandledBy(String modName) implements RowState {
    }

    /** The feature hit an error. The row shows the reason and a Retry button that runs {@code retry}. */
    record Failed(String reason, Runnable retry) implements RowState {
    }

    RowState READY = new Ready();
    RowState COMING_SOON = new ComingSoon();

    /** Whether the row's control can be used. */
    default boolean editable() {
        return this instanceof Ready || this instanceof Failed;
    }
}
