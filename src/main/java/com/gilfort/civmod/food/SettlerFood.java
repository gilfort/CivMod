package com.gilfort.civmod.food;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Food state of a settler (vault {@code Systems/Needs.md}): a vanilla {@link FoodData} (food level 0–20, saturation
 * buffer) driven by exhaustion like the player's, the settler's appetite and the meal / foraging state of the day.
 * <p>
 * Every {@value #EXHAUSTION_PER_POINT} exhaustion first removes one saturation, then one food level – the same rule
 * as vanilla {@code FoodData.tick}, which needs a player and is therefore not called.
 */
public final class SettlerFood {

    /** Full food level. */
    public static final int MAX = 20;
    /** Below this food level a settler eats during work. */
    public static final int HUNGRY = 10;
    /** Exhaustion per food point (vanilla). */
    public static final float EXHAUSTION_PER_POINT = 4.0F;
    /** Length of the awake day in ticks (06:00–20:00). */
    public static final long AWAKE_TICKS = 14000L;
    /** Ticks per real minute. */
    public static final double TICKS_PER_MINUTE = 1200.0;
    /**
     * Marks "no day" in the per-day flags. Days may be negative in tests with a frozen clock; saves written before
     * this used {@code -1}, which is read as "no day" as well.
     */
    public static final long NO_DAY = Long.MIN_VALUE;
    /** "No day" in saves written before {@link #DAY_FORMAT}. */
    private static final long LEGACY_NO_DAY = -1L;
    /** Save format of the days: from this format on, every saved day is a real day (absent = {@link #NO_DAY}). */
    private static final int DAY_FORMAT = 2;
    /** Items settlers never eat. */
    public static final TagKey<Item> INEDIBLE = TagKey.create(Registries.ITEM, CivMod.id("settler_inedible"));

    private final FoodData data = new FoodData();
    private float exhaustion;
    private float appetite = -1.0F;
    private long eatenDay = NO_DAY;
    private long morningDay = NO_DAY;
    private long complaintDay = NO_DAY;
    private long forageDay = NO_DAY;
    private boolean away;
    private @Nullable BlockPos forageSpot;

    /**
     * @return the food level (0–20, the drumstick bar)
     */
    public int level() {
        return data.getFoodLevel();
    }

    /**
     * @return the saturation buffer (0 – food level)
     */
    public float saturation() {
        return data.getSaturationLevel();
    }

    /**
     * @param random random source for the first roll
     * @param min    lowest appetite
     * @param max    highest appetite
     * @return the appetite multiplier, rolled on first use
     */
    public float appetite(RandomSource random, double min, double max) {
        if (appetite <= 0.0F) {
            appetite = rollAppetite(random, min, max);
        }
        return appetite;
    }

    /**
     * @param appetite the appetite multiplier
     */
    public void setAppetite(float appetite) {
        this.appetite = appetite;
    }

    /**
     * Adds exhaustion and converts it like vanilla: saturation first, then food level.
     *
     * @param amount the exhaustion
     */
    public void exhaust(float amount) {
        exhaustion += Math.max(0.0F, amount);
        while (exhaustion >= EXHAUSTION_PER_POINT) {
            exhaustion -= EXHAUSTION_PER_POINT;
            if (data.getSaturationLevel() > 0.0F) {
                data.setSaturation(Math.max(data.getSaturationLevel() - 1.0F, 0.0F));
            } else {
                data.setFoodLevel(Math.max(data.getFoodLevel() - 1, 0));
            }
        }
    }

    /**
     * Eats a food item like a player (vanilla {@link FoodData#eat(FoodProperties)}).
     *
     * @param food the food properties
     */
    public void eat(FoodProperties food) {
        data.eat(food);
    }

    /**
     * Adds food points without saturation (foraging).
     *
     * @param points the food points
     */
    public void addFood(int points) {
        data.setFoodLevel(Math.min(MAX, data.getFoodLevel() + points));
    }

    /**
     * @param level the food level (tests)
     */
    public void setLevel(int level) {
        data.setFoodLevel(level);
        data.setSaturation(Math.min(data.getSaturationLevel(), level));
    }

    /**
     * @param saturation the saturation buffer (tests)
     */
    public void setSaturation(float saturation) {
        data.setSaturation(saturation);
    }

    // ---------------------------------------------------------------- day state
    // The Schedule's "already handled today" flags, one set for live play and catch-up, saved with the settler.

    /**
     * @return the day of the last evening meal
     */
    public long eatenDay() {
        return eatenDay;
    }

    /**
     * @param day the day of the evening meal
     */
    public void setEatenDay(long day) {
        eatenDay = day;
    }

    /**
     * @return the day whose morning was already handled (provisions / foraging decision)
     */
    public long morningDay() {
        return morningDay;
    }

    /**
     * @param day the day whose morning was handled
     */
    public void setMorningDay(long day) {
        morningDay = day;
    }

    /**
     * @param day the current day
     * @return whether the settler has not complained to players yet today (and marks it as done)
     */
    public boolean complainToday(long day) {
        if (complaintDay == day) {
            return false;
        }
        complaintDay = day;
        return true;
    }

    /**
     * @return the day the settler forages on, {@link #NO_DAY} if not foraging
     */
    public long forageDay() {
        return forageDay;
    }

    /**
     * @return whether the settler forages today
     */
    public boolean isForaging() {
        return forageDay != NO_DAY;
    }

    /**
     * @return whether the foraging settler has vanished
     */
    public boolean isAway() {
        return away;
    }

    /**
     * @return where the foraging settler vanished
     */
    public Optional<BlockPos> forageSpot() {
        return Optional.ofNullable(forageSpot);
    }

    /**
     * Starts a day of foraging.
     *
     * @param day the day
     */
    public void startForaging(long day) {
        forageDay = day;
        away = false;
        forageSpot = null;
    }

    /**
     * @param spot where the settler vanishes
     */
    public void vanish(BlockPos spot) {
        away = true;
        forageSpot = spot.immutable();
    }

    /**
     * Ends foraging (the settler is back).
     */
    public void endForaging() {
        forageDay = NO_DAY;
        away = false;
        forageSpot = null;
    }

    // ---------------------------------------------------------------- rules

    /**
     * Exhaustion of one decision step: base hunger while awake plus work hunger, times the appetite.
     *
     * @param awakeTicks       awake ticks (06:00–20:00, not sleeping, not away)
     * @param weightedWorkTicks work ticks already multiplied with the job factor
     * @param basePerDay       food points per awake day
     * @param perWorkMinute    food points per work minute
     * @param appetite         the appetite multiplier
     * @return the exhaustion to add
     */
    public static float exhaustionFor(long awakeTicks, double weightedWorkTicks, double basePerDay, double perWorkMinute, double appetite) {
        double points = basePerDay * awakeTicks / AWAKE_TICKS + perWorkMinute * weightedWorkTicks / TICKS_PER_MINUTE;
        return (float) (points * EXHAUSTION_PER_POINT * appetite);
    }

    /**
     * Rolls an appetite: log-symmetric around 1, normal values are more likely than the extremes.
     *
     * @param random random source
     * @param min    lowest value
     * @param max    highest value
     * @return the appetite
     */
    public static float rollAppetite(RandomSource random, double min, double max) {
        double t = (random.nextDouble() + random.nextDouble()) / 2.0;
        return (float) Math.exp(Math.log(min) + (Math.log(max) - Math.log(min)) * t);
    }

    /**
     * @param appetite an appetite multiplier
     * @return the lang key describing it
     */
    public static String appetiteKey(float appetite) {
        if (appetite < 0.9F) {
            return "civmod.settler.appetite.light";
        }
        return appetite > 1.1F ? "civmod.settler.appetite.big" : "civmod.settler.appetite.normal";
    }

    /**
     * @param stack an item stack
     * @return whether settlers eat it (food component, not tagged {@link #INEDIBLE})
     */
    public static boolean isEdible(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD) && !stack.is(INEDIBLE);
    }

    /**
     * @param stack an item stack
     * @return its vanilla nutrition (0 if not food)
     */
    public static int nutrition(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    /**
     * No overeating: food is only eaten if it does not push the level above {@value #MAX}, unless the settler is below
     * {@value #HUNGRY}.
     *
     * @param level     the food level
     * @param nutrition the nutrition of the item
     * @return whether the item may be eaten
     */
    public static boolean mayEat(int level, int nutrition) {
        return level < MAX && (level < HUNGRY || level + nutrition <= MAX);
    }

    /**
     * Chooses the food that brings the level closest to {@value #MAX}.
     *
     * @param stacks candidate stacks (any items)
     * @param level  the food level
     * @return the index of the chosen stack, empty if nothing may be eaten
     */
    public static Optional<Integer> choose(List<ItemStack> stacks, int level) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!isEdible(stack) || !mayEat(level, nutrition(stack))) {
                continue;
            }
            int distance = Math.abs(MAX - (level + nutrition(stack)));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best < 0 ? Optional.empty() : Optional.of(best);
    }

    /**
     * @param stack the eaten stack (before shrinking)
     * @return what is left over after eating one item (bowl, bottle), empty if nothing
     */
    public static ItemStack remainder(ItemStack stack) {
        UseRemainder remainder = stack.get(DataComponents.USE_REMAINDER);
        return remainder == null ? ItemStack.EMPTY : remainder.convertInto().create();
    }

    /**
     * @param container the settler inventory
     * @param start     first pocket slot
     * @param size      number of pocket slots
     * @return the total nutrition of edible items in the pockets
     */
    public static int pocketNutrition(Container container, int start, int size) {
        int total = 0;
        for (int i = start; i < start + size; i++) {
            ItemStack stack = container.getItem(i);
            if (isEdible(stack)) {
                total += nutrition(stack) * stack.getCount();
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- persistence

    /**
     * @param output the settler's save output
     */
    public void save(ValueOutput output) {
        ValueOutput food = output.child("food");
        data.addAdditionalSaveData(food);
        food.putFloat("exhaustion", exhaustion);
        food.putFloat("appetite", appetite);
        food.putInt("day_format", DAY_FORMAT);
        putDay(food, "eaten_day", eatenDay);
        putDay(food, "morning_day", morningDay);
        putDay(food, "complaint_day", complaintDay);
        putDay(food, "forage_day", forageDay);
        food.putBoolean("away", away);
        if (forageSpot != null) {
            food.putLong("forage_spot", forageSpot.asLong());
        }
    }

    /**
     * Loads the food state; settlers saved before food existed start full.
     *
     * @param input the settler's save input
     */
    public void load(ValueInput input) {
        input.child("food").ifPresent(food -> {
            data.readAdditionalSaveData(food);
            exhaustion = food.getFloatOr("exhaustion", 0.0F);
            appetite = food.getFloatOr("appetite", -1.0F);
            boolean legacy = food.getIntOr("day_format", 0) < DAY_FORMAT;
            eatenDay = day(food, "eaten_day", legacy);
            morningDay = day(food, "morning_day", legacy);
            complaintDay = day(food, "complaint_day", legacy);
            forageDay = day(food, "forage_day", legacy);
            away = food.getBooleanOr("away", false);
            forageSpot = food.getLong("forage_spot").map(BlockPos::of).orElse(null);
        });
    }

    /** Writes a day; {@link #NO_DAY} is left out. */
    private static void putDay(ValueOutput food, String key, long day) {
        if (day != NO_DAY) {
            food.putLong(key, day);
        }
    }

    /**
     * Reads a day: absent is {@link #NO_DAY}; in saves from before {@link #DAY_FORMAT}, {@code -1} is as well.
     */
    private static long day(ValueInput food, String key, boolean legacy) {
        long day = food.getLongOr(key, NO_DAY);
        return legacy && day == LEGACY_NO_DAY ? NO_DAY : day;
    }
}
