package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.Map;

import com.gilfort.civmod.api.Job;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Skill levels and EXP of a settler, one per job type (vault {@code Systems/JobSkill Level.md}). Kept when a job is
 * removed or the settler moves to another JobBlock of the same type.
 */
public final class SettlerSkills {

    /** Hundredths in a whole EXP point: a bonus is a percentage, so the fraction it leaves is counted in these. */
    private static final int PERCENT = 100;

    /**
     * Level and EXP of one job.
     *
     * @param level the level (1–100)
     * @param exp   EXP towards the next level
     * @param carry the fraction of a point that the talent bonus has earned and not yet paid out, in hundredths (0 to 99)
     */
    public record Skill(int level, int exp, int carry) {

        /** Save codec. */
        public static final Codec<Skill> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("level").forGetter(Skill::level),
                Codec.INT.fieldOf("exp").forGetter(Skill::exp),
                Codec.intRange(0, PERCENT - 1).optionalFieldOf("carry", 0).forGetter(Skill::carry)
        ).apply(instance, Skill::new));

        /**
         * @param level the level
         * @param exp   EXP towards the next level
         */
        public Skill(int level, int exp) {
            this(level, exp, 0);
        }

        /** Starting skill. */
        public static final Skill START = new Skill(1, 0);
    }

    /** Save codec. */
    public static final Codec<Map<Job, Skill>> CODEC = Codec.unboundedMap(Job.CODEC, Skill.CODEC);

    private final Map<Job, Skill> skills = new HashMap<>();

    /**
     * @param jobType a job
     * @return the skill in that job
     */
    public Skill get(Job jobType) {
        return skills.getOrDefault(jobType, Skill.START);
    }

    /**
     * @param jobType a job
     * @param skill   the skill
     */
    public void set(Job jobType, Skill skill) {
        skills.put(jobType, skill);
    }

    /**
     * @param jobType a job
     * @param cap     the skill cap of the JobBlock
     * @return the level the settler works at (its level, at most the cap)
     */
    public int effectiveLevel(Job jobType, int cap) {
        return Math.min(get(jobType).level(), cap);
    }

    /**
     * What an addition of EXP did.
     *
     * @param levels the levels gained
     * @param exp    the whole EXP points the action earned: its amount, the bonus and the fraction paid out from before
     */
    public record Added(int levels, int exp) {

        /** Nothing was added (a skill at its cap, an amount of 0). */
        public static final Added NONE = new Added(0, 0);
    }

    /**
     * Adds EXP; at the cap excess EXP is discarded. A bonus adds a percentage of the amount. The fraction of a point that the bonus
     * leaves is kept in the skill and paid out with a later action, so a bonus on an action worth 1 EXP is not rounded away.
     *
     * @param jobType      a job
     * @param amount       the EXP
     * @param cap          the skill cap of the JobBlock
     * @param rules        the skill rules
     * @param bonusPercent the extra EXP in percent of the amount, 0 for none
     * @return the levels gained and the EXP earned
     */
    public Added addExp(Job jobType, int amount, int cap, SkillRules rules, int bonusPercent) {
        Skill skill = get(jobType);
        int limit = Math.min(cap, SkillRules.MAX_LEVEL);
        if (skill.level() >= limit || amount <= 0) {
            return Added.NONE;
        }
        int earned = amount * (PERCENT + bonusPercent) + skill.carry();
        int level = skill.level();
        int points = earned / PERCENT;
        int exp = skill.exp() + points;
        int carry = earned % PERCENT;
        while (level < limit && exp >= rules.expToNext(level)) {
            exp -= rules.expToNext(level);
            level++;
        }
        if (level >= limit) {
            exp = 0;
            carry = 0;
        }
        int gained = level - skill.level();
        skills.put(jobType, new Skill(level, exp, carry));
        return new Added(gained, points);
    }

    /**
     * @return a copy of all skills (saving)
     */
    public Map<Job, Skill> snapshot() {
        return Map.copyOf(skills);
    }

    /**
     * @param saved saved skills
     */
    public void restore(Map<Job, Skill> saved) {
        skills.clear();
        skills.putAll(saved);
    }
}
