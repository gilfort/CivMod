package com.gilfort.civmod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server configuration of CivMod ({@code serverconfig/civmod-server.toml}, per world).
 */
public final class CivModConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Enables additional debug logging. */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enables additional debug logging.")
            .define("debugLogging", false);

    /** Number of settlers arriving at a newly founded settlement. */
    public static final ModConfigSpec.IntValue STARTING_SETTLERS = BUILDER
            .comment("Number of settlers arriving at a newly founded settlement.",
                    "Homeless settlers that already joined count towards this number.")
            .defineInRange("startingSettlers", 4, 0, 32);

    /** Limits founding to one settlement per player per in-game day. */
    public static final ModConfigSpec.BooleanValue LIMIT_FOUNDING_PER_DAY = BUILDER
            .comment("If true, each player can found at most one settlement per in-game day.",
                    "Mitigates founding and dissolving settlements to farm starting settlers.")
            .define("limitFoundingPerDay", false);

    /** The built config spec, registered in the mod constructor. */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private CivModConfig() {
    }
}
