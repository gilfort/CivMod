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

    /** Food points lost per awake day (06:00–20:00) without work. */
    public static final ModConfigSpec.DoubleValue HUNGER_BASE_PER_DAY = BUILDER
            .comment("Food points a settler loses per awake day (06:00-20:00), independent of work.")
            .defineInRange("hungerBasePerDay", 8.0, 0.0, 100.0);

    /** Additional food points lost per real minute of actual work (× job factor). */
    public static final ModConfigSpec.DoubleValue HUNGER_PER_WORK_MINUTE = BUILDER
            .comment("Additional food points a settler loses per real minute of actual work (multiplied by the job factor).")
            .defineInRange("hungerPerWorkMinute", 0.6, 0.0, 20.0);

    /** Job factor of the Lumberjack. */
    public static final ModConfigSpec.DoubleValue HUNGER_FACTOR_LUMBERJACK = BUILDER
            .comment("Work hunger factor of the Lumberjack.")
            .defineInRange("hungerFactorLumberjack", 1.0, 0.0, 10.0);

    /** Job factor of the Miner. */
    public static final ModConfigSpec.DoubleValue HUNGER_FACTOR_MINER = BUILDER
            .comment("Work hunger factor of the Miner.")
            .defineInRange("hungerFactorMiner", 1.0, 0.0, 10.0);

    /** Job factor of the Fisherman. */
    public static final ModConfigSpec.DoubleValue HUNGER_FACTOR_FISHERMAN = BUILDER
            .comment("Work hunger factor of the Fisherman.")
            .defineInRange("hungerFactorFisherman", 0.5, 0.0, 10.0);

    /** Lowest appetite a settler can roll. */
    public static final ModConfigSpec.DoubleValue APPETITE_MIN = BUILDER
            .comment("Lowest appetite multiplier a new settler can roll.")
            .defineInRange("appetiteMin", 0.8, 0.1, 1.0);

    /** Highest appetite a settler can roll. */
    public static final ModConfigSpec.DoubleValue APPETITE_MAX = BUILDER
            .comment("Highest appetite multiplier a new settler can roll.")
            .defineInRange("appetiteMax", 1.25, 1.0, 10.0);

    /** Nutrition a settler wants to carry as provisions. */
    public static final ModConfigSpec.IntValue PROVISION_NUTRITION = BUILDER
            .comment("Settlers carrying less food than this (nutrition) fetch provisions from a pantry in the morning.")
            .defineInRange("provisionNutrition", 6, 0, 100);

    /** Food points regained by a day of foraging. */
    public static final ModConfigSpec.IntValue FORAGING_FOOD = BUILDER
            .comment("Food points a settler regains by foraging for a day when no food is available.")
            .defineInRange("foragingFood", 5, 0, 20);

    /** The built config spec, registered in the mod constructor. */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private CivModConfig() {
    }
}
