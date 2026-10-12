package com.gilfort.civmod.job;

import com.gilfort.civmod.config.CivModConfig;

import net.minecraft.util.RandomSource;

/**
 * Fatigue of Resource Collection Jobs (vault {@code Jobs/Miner.md}, real-time minutes): every minute without a break
 * the break chance grows by a step; after a break the settler works a guaranteed span without interruption.
 * <p>
 * Advanced in coarse steps by the decision layer; all times are in ticks.
 */
public final class Fatigue {

    private static final int MINUTE = 1200;

    private long workedSinceCheck;
    private long safeTicks;
    private long breakTicks;
    private double chance;

    /**
     * Creates fresh fatigue (no break pending).
     */
    public Fatigue() {
        this.chance = chanceStep();
    }

    /**
     * Advances fatigue by the given working time.
     *
     * @param ticks  the elapsed ticks
     * @param random the random source
     * @return whether the settler is on a break now
     */
    public boolean advance(long ticks, RandomSource random) {
        if (breakTicks > 0) {
            breakTicks = Math.max(0, breakTicks - ticks);
            return breakTicks > 0;
        }
        if (safeTicks > 0) {
            safeTicks = Math.max(0, safeTicks - ticks);
            return false;
        }
        workedSinceCheck += ticks;
        while (workedSinceCheck >= MINUTE) {
            workedSinceCheck -= MINUTE;
            if (random.nextDouble() < chance) {
                startBreak(random);
                return true;
            }
            chance += chanceStep();
        }
        return false;
    }

    private void startBreak(RandomSource random) {
        int min = CivModConfig.BREAK_MIN_SECONDS.get() * 20;
        int max = Math.max(min, CivModConfig.BREAK_MAX_SECONDS.get() * 20);
        breakTicks = min + random.nextInt(max - min + 1);
        safeTicks = CivModConfig.BREAK_FREE_MINUTES.get() * (long) MINUTE;
        workedSinceCheck = 0;
        chance = chanceStep();
    }

    /**
     * @return whether the settler is on a break
     */
    public boolean onBreak() {
        return breakTicks > 0;
    }

    /**
     * @return the current break chance per minute
     */
    public double chance() {
        return chance;
    }

    private static double chanceStep() {
        return CivModConfig.BREAK_CHANCE_PERCENT.get() / 100.0;
    }
}
