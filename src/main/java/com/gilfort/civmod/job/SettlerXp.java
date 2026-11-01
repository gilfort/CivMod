package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.XpGain;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/**
 * The XP counter of a settler (vault {@code Systems/Settler XP.md}): vanilla-style experience in points, no levels, no
 * cap, independent of the EXP in {@link SettlerSkills}. It goes with the settler; what repairs tools never reaches it
 * ({@link SettlerTools#mend}).
 */
public final class SettlerXp {

    /** The counter shows a burst each time it passes a multiple of this. */
    private static final int MILESTONE = 100;

    private int points;
    /** The fraction of a point catch-up has earned so far; a few points at most, so it is not saved. */
    private double expectedRest;

    /**
     * @param job       a Job's {@code xp} list ({@link JobBalancing#xp}), if a pack set one
     * @param byDefault the XP the Job's profile gives for the action
     * @param produced  what the action produced
     * @return the gains of the action: the list's matching entries, which add up, in place of the default
     */
    static List<XpGain> gains(Optional<List<XpEntry>> job, XpGain byDefault, List<ItemStack> produced) {
        return job.map(entries -> entries.stream().filter(entry -> entry.matches(produced)).map(XpEntry::gain).toList()).orElse(List.of(byDefault));
    }

    /**
     * @return the XP collected, in points
     */
    public int points() {
        return points;
    }

    /**
     * @param saved saved points
     */
    public void restore(int saved) {
        points = saved;
    }

    /**
     * The whole points some gains give now.
     *
     * @param gains    the gains
     * @param random   the random source
     * @param expected whether to add the expected XP instead of rolling (catch-up); fractions carry over to the next call
     * @return the points
     */
    int take(List<XpGain> gains, RandomSource random, boolean expected) {
        if (!expected) {
            return gains.stream().mapToInt(gain -> gain.roll(random)).sum();
        }
        expectedRest += gains.stream().mapToDouble(XpGain::expected).sum();
        int whole = (int) expectedRest;
        expectedRest -= whole;
        return whole;
    }

    /**
     * @param amount points for the counter
     * @return whether the counter passed a multiple of {@value #MILESTONE}
     */
    boolean store(int amount) {
        int before = points;
        points = (int) Math.min((long) points + amount, Integer.MAX_VALUE);
        return points / MILESTONE > before / MILESTONE;
    }
}
