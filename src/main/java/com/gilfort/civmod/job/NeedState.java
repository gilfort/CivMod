package com.gilfort.civmod.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Where a settler stands on the need tiers (vault {@code Systems/Needs.md}), saved with the settler. Plain rules: the
 * shell brings the settler's tier and the highest tier whose needs are met. A tier counts as met when its needs and
 * those of every lower tier are met.
 * <p>
 * Unmet at the morning check: one grace day at the level it works at, the next morning one tier lower, at the top of that
 * tier. Met again: it climbs back at once ({@link #climb}).
 *
 * @param workTier   the tier the settler works at most at; {@link #MAX_TIER} (no limit) until it is downgraded
 * @param graceDay   the day of the running grace day, {@link #NO_DAY} if none
 * @param checkedDay the day of the last morning check, {@link #NO_DAY} if none
 */
public record NeedState(int workTier, long graceDay, long checkedDay) {

    /** The highest need tier. */
    public static final int MAX_TIER = 5;
    /** Levels per tier. */
    private static final int LEVELS_PER_TIER = 20;
    /** Marks "no day". */
    public static final long NO_DAY = Long.MIN_VALUE;
    /** A settler nothing has happened to. */
    public static final NeedState START = new NeedState(MAX_TIER, NO_DAY, NO_DAY);

    /** Save codec; a save without need tiers starts at {@link #START}. */
    public static final Codec<NeedState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("work_tier", MAX_TIER).forGetter(NeedState::workTier),
            Codec.LONG.optionalFieldOf("grace_day", NO_DAY).forGetter(NeedState::graceDay),
            Codec.LONG.optionalFieldOf("checked_day", NO_DAY).forGetter(NeedState::checkedDay)
    ).apply(instance, NeedState::new));

    /**
     * @param level a level (1–100)
     * @return its tier: Lv 1–20, 21–40, 41–60, 61–80, 81+
     */
    public static int tierOf(int level) {
        return Math.clamp((level - 1) / LEVELS_PER_TIER + 1, 1, MAX_TIER);
    }

    /**
     * @param tier a tier
     * @return the highest level of that tier
     */
    public static int topLevel(int tier) {
        return tier >= MAX_TIER ? SkillRules.MAX_LEVEL : tier * LEVELS_PER_TIER;
    }

    /**
     * @param level a level
     * @return the level the settler works at instead
     */
    public int limit(int level) {
        return Math.min(level, topLevel(workTier));
    }

    /**
     * @return whether the settler is on a grace day (it shows what it misses)
     */
    public boolean inGrace() {
        return graceDay != NO_DAY;
    }

    /**
     * @param tier the settler's tier
     * @return whether nothing can change before the next morning: no re-check needed
     */
    public boolean settled(int tier) {
        return !inGrace() && workTier >= tier;
    }

    /**
     * The check at wake-up.
     *
     * @param day       the day
     * @param tier      the settler's tier
     * @param highestMet the highest tier whose needs are met (0: not even tier 1)
     * @return the state for today
     */
    public NeedState morning(long day, int tier, int highestMet) {
        int current = Math.min(workTier, tier);
        if (highestMet >= current) {
            return new NeedState(workTier, graceDay, day).climb(tier, highestMet);
        }
        if (!inGrace() || graceDay >= day || current <= 1) {
            return new NeedState(workTier, day, day);
        }
        int lower = current - 1;
        return new NeedState(lower, highestMet >= lower ? NO_DAY : day, day);
    }

    /**
     * Met again: the settler climbs back at once and a grace day ends.
     *
     * @param tier       the settler's tier
     * @param highestMet the highest tier whose needs are met (0: not even tier 1)
     * @return the state now; unchanged if the needs of the tier it works at are still unmet
     */
    public NeedState climb(int tier, int highestMet) {
        if (highestMet >= tier) {
            return new NeedState(MAX_TIER, NO_DAY, checkedDay);
        }
        return highestMet >= Math.min(workTier, tier) ? new NeedState(Math.max(workTier, highestMet), NO_DAY, checkedDay) : this;
    }
}
