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

    /** Break chance per real minute (and increase per minute without break), in percent. */
    public static final ModConfigSpec.IntValue BREAK_CHANCE_PERCENT = BUILDER
            .comment("Fatigue: break chance per real minute of work in percent; grows by this value every minute without break.")
            .defineInRange("breakChancePercent", 15, 0, 100);

    /** Real minutes without break after a break. */
    public static final ModConfigSpec.IntValue BREAK_FREE_MINUTES = BUILDER
            .comment("Fatigue: real minutes of uninterrupted work after a break.")
            .defineInRange("breakFreeMinutes", 5, 0, 60);

    /** Minimum break duration in real seconds. */
    public static final ModConfigSpec.IntValue BREAK_MIN_SECONDS = BUILDER
            .comment("Fatigue: minimum break duration in real seconds.")
            .defineInRange("breakMinSeconds", 20, 0, 600);

    /** Maximum break duration in real seconds. */
    public static final ModConfigSpec.IntValue BREAK_MAX_SECONDS = BUILDER
            .comment("Fatigue: maximum break duration in real seconds.")
            .defineInRange("breakMaxSeconds", 45, 0, 600);

    /** Tool durability used per produced resource. */
    public static final ModConfigSpec.DoubleValue TOOL_WEAR_MULTIPLIER = BUILDER
            .comment("Tool durability used per produced resource (1.0 = one point per resource).")
            .defineInRange("toolWearMultiplier", 1.0, 0.0, 10.0);

    /** Seconds without movement progress after which a settler teleports to its target. */
    public static final ModConfigSpec.IntValue ANTI_STUCK_SECONDS = BUILDER
            .comment("A settler that makes no progress towards its target for this many seconds teleports there.")
            .defineInRange("antiStuckSeconds", 60, 5, 600);

    /** The built config spec, registered in the mod constructor. */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private CivModConfig() {
    }
}
