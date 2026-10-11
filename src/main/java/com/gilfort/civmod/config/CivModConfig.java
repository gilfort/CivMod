package com.gilfort.civmod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server configuration of CivMod ({@code serverconfig/civmod-server.toml}, per world).
 */
public final class CivModConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Placeholder option; replace with real settings once features exist. */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enables additional debug logging.")
            .define("debugLogging", false);

    /** The built config spec, registered in the mod constructor. */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private CivModConfig() {
    }
}
