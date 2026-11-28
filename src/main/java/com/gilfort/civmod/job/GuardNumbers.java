package com.gilfort.civmod.job;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The numbers of a Guard Job that a datapack can set in its job file ({@code guard} in {@code civmod_jobs/<job>.json}, see
 * {@link JobBalancing}). The duty EXP is the job's {@code base_exp} like for every Job.
 *
 * @param killExpPerHealth the EXP one point of a killed mob's max health is worth for every level of the Guard Post
 *                         ({@code kill_exp_per_health}, default {@value GuardCombat#DEFAULT_KILL_EXP_PER_HEALTH}); empty keeps the default
 */
public record GuardNumbers(Optional<Double> killExpPerHealth) {

    /** The upper bound of {@code kill_exp_per_health}: a kill worth more than this per point of health is a typing mistake. */
    private static final double MAX_KILL_EXP_PER_HEALTH = 100.0;

    /** Nothing set. */
    public static final GuardNumbers NONE = new GuardNumbers(Optional.empty());

    /** Codec of the {@code guard} object. */
    public static final Codec<GuardNumbers> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.doubleRange(0.0, MAX_KILL_EXP_PER_HEALTH).optionalFieldOf("kill_exp_per_health").forGetter(GuardNumbers::killExpPerHealth)
    ).apply(instance, GuardNumbers::new));

    /**
     * @return the EXP one point of max health is worth per level of the Guard Post
     */
    public double killExpPerHealthOrDefault() {
        return killExpPerHealth.orElse(GuardCombat.DEFAULT_KILL_EXP_PER_HEALTH);
    }

    /**
     * @param earlier the numbers of the files before
     * @return these numbers, with what they leave open taken from the earlier ones
     */
    GuardNumbers over(GuardNumbers earlier) {
        return new GuardNumbers(killExpPerHealth.or(earlier::killExpPerHealth));
    }
}
