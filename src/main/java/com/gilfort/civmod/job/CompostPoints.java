package com.gilfort.civmod.job;

/**
 * The Composter's point counter (vault {@code Jobs/Composter.md}): every composted item adds its compost chance as
 * points, 7 points make 1 bone meal. There are no random numbers; the points that do not make a bone meal yet are kept,
 * so the yield over a long run equals the vanilla composter's. Points are counted in hundredths (a chance of 30 percent is
 * 0.3 points), which keeps the sum exact.
 */
public final class CompostPoints {

    /** The points that make one bone meal, as in a vanilla composter's seven layers. */
    public static final int POINTS_PER_BONE_MEAL = 7;
    /** How many hundredths make one point. */
    public static final int HUNDREDTHS = 100;
    /** The hundredths of a point that make one bone meal. */
    public static final int BONE_MEAL_COST = POINTS_PER_BONE_MEAL * HUNDREDTHS;

    private CompostPoints() {
    }

    /**
     * What one composted item makes.
     *
     * @param boneMeal the bone meal it completed (usually 0 or 1)
     * @param rest     the points left over, in hundredths, always less than {@link #BONE_MEAL_COST}
     */
    public record Step(int boneMeal, int rest) {
    }

    /**
     * Adds the chance of one item to the points kept so far.
     *
     * @param rest    the points kept so far, in hundredths (0 or more)
     * @param percent the item's compost chance, in hundredths of a point (30 for seeds)
     * @return the bone meal made and the points left
     */
    public static Step add(int rest, int percent) {
        if (rest < 0 || percent < 0) {
            throw new IllegalArgumentException("Points cannot be negative: rest " + rest + ", chance " + percent);
        }
        int total = rest + percent;
        return new Step(total / BONE_MEAL_COST, total % BONE_MEAL_COST);
    }
}
