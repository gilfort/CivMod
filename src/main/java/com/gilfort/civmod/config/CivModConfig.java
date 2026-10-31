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

    /** Most Settlers a settlement can have; 0 = unlimited. */
    public static final ModConfigSpec.IntValue MAX_SETTLERS_PER_SETTLEMENT = BUILDER
            .comment("Most Settlers a settlement can have (running Villager conversions count); 0 = unlimited.",
                    "Every Settler costs server tick time.")
            .defineInRange("maxSettlersPerSettlement", 0, 0, 1024);

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
            .comment("Settlers carrying less food than this (nutrition) fetch provisions from a Clerk (food point) in the morning.")
            .defineInRange("provisionNutrition", 6, 0, 100);

    /** Food points regained by a day of foraging. */
    public static final ModConfigSpec.IntValue FORAGING_FOOD = BUILDER
            .comment("Food points a settler regains by foraging for a day when no food is available.")
            .defineInRange("foragingFood", 5, 0, 20);

    /** Maximum number of in-game days caught up for unloaded settlers. */
    public static final ModConfigSpec.IntValue CATCH_UP_MAX_DAYS = BUILDER
            .comment("Unloaded settlers catch up at most this many in-game days of missed work.")
            .defineInRange("catchUpMaxDays", 10, 0, 100);

    /** Multiplier on the output caught up while unloaded. */
    public static final ModConfigSpec.DoubleValue CATCH_UP_MULTIPLIER = BUILDER
            .comment("Multiplier on the output settlers produce while unloaded (1.0 = as if the player was there).")
            .defineInRange("catchUpMultiplier", 1.0, 0.0, 10.0);

    /** The built config spec, registered in the mod constructor. */
    /** Largest distance between a settler's sleeping place and a hearth for the Fire need. */
    public static final ModConfigSpec.IntValue HEARTH_RANGE = BUILDER
            .comment("Fire need: largest distance in blocks between a settler's sleeping place and a hearth.",
                    "A settler whose bed is further away sleeps at the camp instead.")
            .defineInRange("hearthRange", 24, 1, 256);

    /** How many days the window of the Food variety need reaches back, today included. */
    public static final ModConfigSpec.IntValue FOOD_VARIETY_DAYS = BUILDER
            .comment("Food variety need: how many in-game days the window reaches back, today included.",
                    "It always covers at least the last 6 food items eaten.")
            .defineInRange("foodVarietyDays", 3, 1, 14);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private CivModConfig() {
    }
}
