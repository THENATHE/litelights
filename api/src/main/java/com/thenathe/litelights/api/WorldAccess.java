package com.thenathe.litelights.api;

import org.jspecify.annotations.Nullable;

/** The world the player is in. */
public interface WorldAccess {
    /** The world and dimension the player is in now, or null outside a world. Client thread. */
    @Nullable WorldIdentity current();
}
