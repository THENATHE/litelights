package com.thenathe.litelights.api;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod every module ships in: its id, its log and its namespace. */
public final class LiteLightsMod {
    public static final String MOD_ID = "litelights";
    public static final Logger LOG = LoggerFactory.getLogger("LiteLights");

    private LiteLightsMod() {
    }

    /** An id in LiteLights' namespace. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
