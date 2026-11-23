package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.chronicle.Diary;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.food.FoodVariety;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.Schedule.Activity;
import com.gilfort.civmod.job.Schedule.Decision;
import com.gilfort.civmod.job.Schedule.Intent;
import com.gilfort.civmod.job.Schedule.MealPurpose;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Carries out the meal intents of the {@link Schedule} for the Settler Shell (vault {@code Systems/Needs.md}): eat from
 * the pockets, fetch food at a food point (Pantry), wait for food, forage; plus hunger, a player feeding the
 * settler and complaining. It keeps the running state of a meal and reads and writes the settler's
 * {@link SettlerFood}. It answers the shell with a {@link BodyStep}; only the shell moves the settler and sets its
 * activity. Catch-up eats through the same parts on the spot.
 */
final class SettlerMeals {

    /** Ticks between the sound and the particles of eating. */
    private static final int EFFECT_INTERVAL_TICKS = 6;
    /** Ticks a foraging settler walks before it vanishes. */
    private static final int FORAGE_WALK_TICKS = 600;
    /** Food value gained per level of a working Tavern Host: +1 % (Lv 100: +100 %). */
    private static final double MEAL_BONUS_PER_LEVEL = 0.01;
    /** Players within this distance get the hunger message. */
    private static final double COMPLAIN_RANGE = 32.0;

    /**
     * A meal the settler took at a food point and eats beside it, one item at a time: where it took the food, the day it
     * counts for, and the factor on the food value that the counter gives.
     *
     * @param point the food point
     * @param day   the day of the meal
     * @param bonus the factor on the food value of a meal at a working Tavern Host's counter, 1.0 without
     */
    private record CounterMeal(ClerkStorage.FoodPoint point, long day, double bonus) {
    }

    private final SettlerWork shell;
    private final Settler settler;
    private double weightedWorkTicks;
    /** Ticks left until the item in the mouth is eaten. */
    private int eatTimer;
    /** The item the settler eats now, a display copy (empty while it does not eat). */
    private ItemStack eating = ItemStack.EMPTY;
    /** The meal taken at a food point; null while the settler eats from its pockets. */
    private @Nullable CounterMeal counterMeal;
    private boolean eatUntilFull;
    private boolean hungryNoFood;
    private long forageStart;
    private @Nullable MealPurpose mealPurpose;
    /** The food point the settler walks to or visits. */
    private ClerkStorage.@Nullable FoodPoint foodPoint;

    /**
     * @param shell the Settler Shell
     */
    SettlerMeals(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    // ---------------------------------------------------------------- hunger

    /**
     * Settles the hunger since the last decision: awake time and the weighted work ticks, nothing while away.
     *
     * @param level the level
     */
    void accountHunger(ServerLevel level) {
        SettlerFood food = settler.getFood();
        if (food.isAway() || shell.missions().isAway()) {
            weightedWorkTicks = 0.0;
            return;
        }
        Schedule.Moment moment = shell.moment(level.getOverworldClockTime());
        long awake = moment.dayTime() < shell.dayTimes(moment.day()).bed() && !settler.isSleeping() ? SettlerWork.DECISION_INTERVAL : 0L;
        exhaust(level, awake, weightedWorkTicks);
        weightedWorkTicks = 0.0;
    }

    /**
     * Hunger of a stretch of time (live since the last decision, or a simulated catch-up hour).
     *
     * @param level      the level
     * @param awakeTicks the ticks awake
     * @param workTicks  the work ticks, weighted by the jobs' hunger factors
     */
    void exhaust(ServerLevel level, long awakeTicks, double workTicks) {
        SettlerFood food = settler.getFood();
        float appetite = food.appetite(level.getRandom(), CivModConfig.APPETITE_MIN.get(), CivModConfig.APPETITE_MAX.get());
        food.exhaust(SettlerFood.exhaustionFor(awakeTicks, workTicks, CivModConfig.HUNGER_BASE_PER_DAY.get(),
                CivModConfig.HUNGER_PER_WORK_MINUTE.get(), appetite));
    }

    /**
     * Counts one tick of work of a job for hunger.
     *
     * @param job the job
     */
    void countWorkTick(SettlerJob job) {
        weightedWorkTicks += job.jobType().hungerFactor();
    }

    /** A settler that is no longer hungry stops showing that it is hungry without food. */
    void checkHunger() {
        if (settler.getFood().level() >= SettlerFood.HUNGRY) {
            hungryNoFood = false;
        }
    }

    /**
     * @return whether the settler is hungry and found no food (a problem it shows)
     */
    boolean hungryNoFood() {
        return hungryNoFood;
    }

    /**
     * The hunger state of the food need ("Hungry – no food", foraging, starving).
     *
     * @param foodPoint whether a food point has food for the settler now
     * @return the state as text, empty while the settler is not in it
     */
    Optional<Component> hungerState(java.util.function.BooleanSupplier foodPoint) {
        SettlerFood food = settler.getFood();
        String key = null;
        if (food.isForaging()) {
            key = "foraging";
        } else if (food.level() < SettlerFood.HUNGRY && !hasPocketMeal() && !foodPoint.getAsBoolean()) {
            key = food.level() <= 0 ? "starving" : "hungry";
        }
        return Optional.ofNullable(key).map(k -> Component.translatable("civmod.settler.status." + k));
    }

    /**
     * @param activity the current activity
     * @return a food-related status that replaces the job status, if any
     */
    Optional<Component> foodStatus(Activity activity) {
        SettlerFood food = settler.getFood();
        String key = null;
        if (food.isForaging()) {
            key = "foraging";
        } else if (activity == Activity.EATING) {
            key = "eating";
        } else if (activity == Activity.FETCH_FOOD) {
            key = "fetching_food";
        } else if (activity == Activity.WAIT_FOR_FOOD) {
            key = "waiting_for_food";
        } else if (hungryNoFood) {
            key = food.level() <= 0 ? "starving" : "hungry";
        }
        return Optional.ofNullable(key).map(k -> Component.translatable("civmod.settler.status." + k));
    }

    /**
     * Notes the food marks of a Schedule decision for today (live and catch-up): the morning plan, the evening meal, the
     * start of waiting for food.
     *
     * @param decision the decision
     * @param day      the day
     * @param dayTime  the day time of the decision
     */
    void mark(Decision decision, long day, long dayTime) {
        SettlerFood food = settler.getFood();
        if (decision.morningHandled()) {
            food.setMorningDay(day);
        }
        if (decision.eveningMealHandled()) {
            food.setEatenDay(day);
        }
        if (decision.waitStarted()) {
            food.startWaiting(day, dayTime);
        }
    }

    /**
     * Shows that the settler is hungry without food: an angry particle, once a day a message to nearby players.
     *
     * @param level the level
     * @param day   the day
     */
    void complain(ServerLevel level, long day) {
        hungryNoFood = true;
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER, settler.getX(), settler.getY() + 2.1, settler.getZ(),
                1, 0.3, 0.1, 0.3, 0.0);
        if (settler.getFood().complainToday(day)) {
            Component message = Component.translatable("civmod.settler.hungry_message",
                    settler.getName());
            for (net.minecraft.server.level.ServerPlayer player : level.players()) {
                if (player.distanceToSqr(settler) <= COMPLAIN_RANGE * COMPLAIN_RANGE) {
                    player.sendOverlayMessage(message);
                }
            }
        }
    }

    // ---------------------------------------------------------------- facts for the Schedule snapshot

    /**
     * @return nutrition carried in the pockets
     */
    int provisions() {
        return SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE);
    }

    /**
     * @return whether the pockets hold something the settler may eat now
     */
    boolean hasPocketMeal() {
        return choosePocketMeal().isPresent();
    }

    /**
     * @param day the day
     * @return the day time the settler began waiting for food today, {@link Schedule#NOT_WAITING} if it did not
     */
    long waitStart(long day) {
        SettlerFood food = settler.getFood();
        return food.waitDay() == day ? food.waitTime() : Schedule.NOT_WAITING;
    }

    /**
     * @param level the level
     * @return the Pantry this settler waits at for food (its own as a Tavern Host, else the nearest), empty if the
     *         settlement has none
     */
    Optional<BlockPos> pantryToWaitAt(ServerLevel level) {
        Optional<BlockPos> own = shell.ownPantry();
        if (own.isPresent()) {
            return own;
        }
        return SettlementData.get(level).settlement(settler.getSettlementId())
                .flatMap(settlement -> ClerkStorage.nearestPantry(settlement, settler.blockPosition()));
    }

    /**
     * @param level   the level
     * @param dayTime the day time of the decision
     * @return a lookup of the nearest food point with food, for one decision
     */
    FoodLookup lookup(ServerLevel level, long dayTime) {
        return new FoodLookup(level, dayTime);
    }

    /** The nearest food point (Pantry) with food, looked up once and only when asked. */
    final class FoodLookup {

        private final ServerLevel level;
        private final long dayTime;
        private Optional<ClerkStorage.FoodPoint> point = Optional.empty();
        private boolean looked;

        private FoodLookup(ServerLevel level, long dayTime) {
            this.level = level;
            this.dayTime = dayTime;
        }

        Optional<ClerkStorage.FoodPoint> get() {
            if (!looked) {
                looked = true;
                point = foodPointWithFood(level, dayTime);
            }
            return point;
        }

        /**
         * @return whether a food point has food for the settler now
         */
        boolean hasFood() {
            return get().isPresent();
        }
    }

    private Optional<ClerkStorage.FoodPoint> foodPointWithFood(ServerLevel level, long dayTime) {
        SettlementData data = SettlementData.get(level);
        return data.settlement(settler.getSettlementId())
                .flatMap(settlement -> ClerkStorage.nearestFood(level, settlement, settler.blockPosition(), dayTime, shell.ownPantry(),
                        pantry -> !shell.counterUnreachable(level, pantry)));
    }

    // ---------------------------------------------------------------- meal intents

    /**
     * Starts eating provisions on the spot ({@link Intent#EAT_FROM_POCKETS}, {@link Intent#EAT_UNTIL_FULL}).
     *
     * @param untilFull keep eating until full (evening) instead of one item
     * @return eat, or stay if there is nothing to eat
     */
    BodyStep pocketMeal(boolean untilFull) {
        Optional<Integer> choice = choosePocketMeal();
        if (choice.isEmpty()) {
            return BodyStep.STAY;
        }
        counterMeal = null;
        beginItem(pocketItem(choice.get()));
        eatUntilFull = untilFull;
        hungryNoFood = false;
        return BodyStep.EAT;
    }

    /**
     * One tick of a meal: eating effects, an item eaten when its time is over.
     *
     * @param level the level
     * @return stay while eating; idle when nothing is left to eat, done after the meal
     */
    BodyStep eatTick(ServerLevel level) {
        if (--eatTimer % EFFECT_INTERVAL_TICKS == 0 && eatTimer > 0) {
            eatingEffects(level, eating);
        }
        if (eatTimer > 0) {
            return BodyStep.STAY;
        }
        CounterMeal meal = counterMeal;
        return meal == null ? finishPocketItem(level) : finishCounterItem(level, meal);
    }

    /**
     * @return the item the settler eats now, shown in its hand; empty while it does not eat
     */
    ItemStack eatingItem() {
        return eating;
    }

    /** Starts the time of an item: from the nutrition it gives. */
    private void beginItem(ItemStack stack) {
        eating = stack.copyWithCount(1);
        eatTimer = SettlerFood.eatTicks(SettlerFood.nutrition(stack), CivModConfig.EATING_BASE_SECONDS.get(),
                CivModConfig.EATING_SECONDS_PER_NUTRITION.get(), CivModConfig.EATING_MAX_SECONDS.get());
    }

    private BodyStep finishPocketItem(ServerLevel level) {
        Optional<Integer> choice = choosePocketMeal();
        if (choice.isEmpty()) {
            eating = ItemStack.EMPTY;
            return BodyStep.DONE_NO_DECISION;
        }
        ItemStack one = pocketItem(choice.get()).split(1);
        settler.getInventory().setChanged();
        eatItem(level, one, null, today(level));
        Optional<Integer> next = eatUntilFull ? choosePocketMeal() : Optional.empty();
        if (next.isPresent()) {
            beginItem(pocketItem(next.get()));
            return BodyStep.STAY;
        }
        eating = ItemStack.EMPTY;
        return BodyStep.DONE;
    }

    /** The item eaten beside the counter is done: the next one from the same stock, or the end of the meal. */
    private BodyStep finishCounterItem(ServerLevel level, CounterMeal meal) {
        Stock stock = ClerkStorage.stock(level, meal.point().clerk(), false);
        eatItem(level, eating, stock.isEmpty() ? null : stock, meal.day(), meal.bonus());
        ItemStack next = takeMealItem(stock, meal.day());
        if (!next.isEmpty()) {
            beginItem(next);
            return BodyStep.STAY;
        }
        takeProvisions(stock);
        counterMeal = null;
        eating = ItemStack.EMPTY;
        return BodyStep.DONE;
    }

    /**
     * Walks to a food point with food ({@link Intent#FETCH_FOOD}).
     *
     * @param level   the level
     * @param found   the food point
     * @param purpose why the settler fetches food
     * @return walk to the food point
     */
    BodyStep fetchFood(ServerLevel level, ClerkStorage.FoodPoint found, MealPurpose purpose) {
        ClerkStorage.FoodPoint point = new ClerkStorage.FoodPoint(found.clerk(), shell.reachableVisit(level, found.clerk(), found.visit()));
        mealPurpose = purpose;
        foodPoint = point;
        hungryNoFood = false;
        return new BodyStep.Walk(Activity.FETCH_FOOD, point.visit().target(), point.visit().reach());
    }

    /**
     * At the food point: waits for its turn at a working Tavern Host's counter, then takes the food there. The counter is
     * free for the next customer from then on; the settler eats what it took where it stands, and takes provisions when
     * the meal is over.
     *
     * @param level the level
     * @return stay while waiting at the counter, eat after taking food, done after a visit without a meal
     */
    BodyStep atFoodPoint(ServerLevel level) {
        ClerkStorage.FoodPoint point = foodPoint;
        BodyStep step = BodyStep.DONE;
        if (point != null) {
            boolean counter = point.visit().counter() && shell.ownPantry().filter(point.clerk()::equals).isEmpty();
            if (counter && !shell.waitAtCounter(level, point.clerk())) {
                return BodyStep.STAY;
            }
            step = takeFood(level, point, today(level), DayClock.dayTime(level));
        }
        mealPurpose = null;
        foodPoint = null;
        return step;
    }

    /**
     * Waits for food at the queue of the nearest Pantry ({@link Intent#WAIT_FOR_FOOD}, where its counter is
     * now). A Tavern Host waits at its own counter: it works its shift meanwhile.
     *
     * @param level the level
     * @return work (a Tavern Host), walk to the queue, or stand still without a Pantry
     */
    BodyStep waitForFood(ServerLevel level) {
        if (shell.ownPantry().isPresent()) {
            return BodyStep.WORK;
        }
        Optional<BlockPos> pantry = pantryToWaitAt(level);
        if (pantry.isEmpty()) {
            return BodyStep.STOP;
        }
        ClerkStorage.Visit visit = shell.reachableVisit(level, pantry.get(), ClerkStorage.visit(level, pantry.get(), DayClock.dayTime(level)));
        return new BodyStep.Walk(Activity.WAIT_FOR_FOOD, visit.target(), visit.reach());
    }

    /**
     * Starts foraging for the rest of the day ({@link Intent#START_FORAGING}).
     *
     * @param level the level
     * @param day   the day
     * @return walk out of the settlement
     */
    BodyStep startForaging(ServerLevel level, long day) {
        startForagingNow(day);
        return walkOut(level);
    }

    /**
     * Starts foraging for the rest of the day on the spot (catch-up: the settler is away at once).
     *
     * @param day the day
     */
    void startForagingNow(long day) {
        settler.getFood().startForaging(day);
    }

    /**
     * Foraging ({@link Intent#FORAGE}): the walk towards the border starts (again, e.g. after loading).
     *
     * @param level the level
     * @return walk out of the settlement
     */
    BodyStep.Walk walkOut(ServerLevel level) {
        forageStart = level.getGameTime();
        return new BodyStep.Walk(Activity.FORAGE, shell.outsideTarget(level), SettlerWork.REACH);
    }

    /**
     * Foraging: the settler vanishes after at most {@value #FORAGE_WALK_TICKS} ticks or at the border. When it is over
     * the Schedule says {@link Intent#END_FORAGING}: see {@link #endForaging}.
     *
     * @param level    the level
     * @param atBorder whether the settler reached the border
     * @return whether it vanished now (the shell hides it)
     */
    boolean vanishes(ServerLevel level, boolean atBorder) {
        if (!atBorder && level.getGameTime() - forageStart < FORAGE_WALK_TICKS) {
            return false;
        }
        settler.getFood().vanish(settler.blockPosition());
        return true;
    }

    /**
     * Back from foraging ({@link Intent#END_FORAGING}, live and catch-up), with the day's food.
     *
     * @return whether the settler was away (the shell shows it where it vanished)
     */
    boolean endForaging() {
        SettlerFood food = settler.getFood();
        boolean away = food.isAway();
        food.addFood(Schedule.foragingFood());
        food.endForaging();
        return away;
    }

    // ---------------------------------------------------------------- catch-up meals (on the spot)

    /**
     * Eats one item from the pockets on the spot (catch-up).
     *
     * @param level the level
     * @param day   the simulated day
     * @return whether there was something to eat
     */
    boolean eatFromPockets(ServerLevel level, long day) {
        Optional<Integer> choice = choosePocketMeal();
        choice.ifPresent(slot -> eatItem(level, pocketItem(slot).split(1), null, day));
        return choice.isPresent();
    }

    /**
     * Visits a food point on the spot (catch-up): no walking or queue, the shift alone decides what is reachable.
     *
     * @param level   the level
     * @param point   the food point
     * @param purpose why the settler fetches food
     * @param day     the simulated day
     * @param dayTime the simulated day time
     */
    void visitFoodPointNow(ServerLevel level, ClerkStorage.FoodPoint point, @Nullable MealPurpose purpose, long day, long dayTime) {
        mealPurpose = purpose;
        if (eatAtFoodPoint(level, point, day, dayTime)) {
            ClerkCounter.handOut(level, point.clerk(), false);
        }
        mealPurpose = null;
    }

    // ---------------------------------------------------------------- eating

    /**
     * Live visit at a food point, the settler stands at the counter or the block: puts back leftover containers and takes
     * the first item of the meal (not in the morning unless hungry). Without a meal it takes its provisions at once.
     *
     * @param day the day of the meal
     * @return eat when the settler took food to eat beside the counter, done when there is no meal
     */
    private BodyStep takeFood(ServerLevel level, ClerkStorage.FoodPoint point, long day, long dayTime) {
        Stock stock = ClerkStorage.stock(level, point.clerk(), false);
        if (stock.isEmpty()) {
            return BodyStep.DONE;
        }
        boolean served = servedAtCounter(level, point, dayTime, point.visit().counter());
        returnContainers(stock);
        ItemStack first = Schedule.eatsAtFoodPoint(mealPurpose, settler.getFood().level()) ? takeMealItem(stock, day) : ItemStack.EMPTY;
        boolean handedOut = !first.isEmpty();
        if (handedOut) {
            counterMeal = new CounterMeal(point, day, bonusAtCounter(level, point, served));
            beginItem(first);
        } else {
            handedOut = takeProvisions(stock);
        }
        if (handedOut && served) {
            ClerkCounter.handOut(level, point.clerk(), true);
        }
        return first.isEmpty() ? BodyStep.DONE : BodyStep.EAT;
    }

    /**
     * Catch-up visit at a food point, on the spot: eats until full (not in the morning unless hungry), puts back leftover
     * containers and refills the provisions, from what the access rule lets the settler reach.
     *
     * @param day the day of the meal
     * @return whether a working Tavern Host handed something out (food from its whole storage at the counter)
     */
    private boolean eatAtFoodPoint(ServerLevel level, ClerkStorage.FoodPoint point, long day, long dayTime) {
        Stock stock = ClerkStorage.stock(level, point.clerk(), false);
        if (stock.isEmpty()) {
            return false;
        }
        boolean served = servedAtCounter(level, point, dayTime, true);
        double bonus = bonusAtCounter(level, point, served);
        returnContainers(stock);
        boolean handedOut = false;
        if (Schedule.eatsAtFoodPoint(mealPurpose, settler.getFood().level())) {
            for (ItemStack item = takeMealItem(stock, day); !item.isEmpty(); item = takeMealItem(stock, day)) {
                handedOut = true;
                eatItem(level, item, stock, day, bonus);
            }
        }
        return (takeProvisions(stock) || handedOut) && served;
    }

    /** A working Tavern Host serves a customer at its counter; its meals carry the bonus, provisions do not. */
    private boolean servedAtCounter(ServerLevel level, ClerkStorage.FoodPoint point, long dayTime, boolean atCounter) {
        return atCounter && shell.ownPantry().filter(point.clerk()::equals).isEmpty() && ClerkStorage.working(level, point.clerk(), dayTime);
    }

    private double bonusAtCounter(ServerLevel level, ClerkStorage.FoodPoint point, boolean served) {
        return served ? 1.0 + MEAL_BONUS_PER_LEVEL * ClerkCounter.clerkSkill(level, point.clerk()) : 1.0;
    }

    /** Bowls and bottles of earlier meals go back into the stock. */
    private void returnContainers(Stock stock) {
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(i);
            if (stack.is(Items.BOWL) || stack.is(Items.GLASS_BOTTLE)) {
                settler.getInventory().setItem(i, stock.insert(stack));
            }
        }
    }

    /**
     * Takes the item of the stock the settler eats next: food not eaten lately first, while the settler is not full.
     *
     * @param stock the stock
     * @param day   the day of the meal
     * @return the item, empty if the settler is full or nothing fits
     */
    private ItemStack takeMealItem(Stock stock, long day) {
        SettlerFood food = settler.getFood();
        if (food.level() >= SettlerFood.MAX) {
            return ItemStack.EMPTY;
        }
        // The diets of the settler's own tier and the lower ones (it has to meet them all to keep its work level); on tier 5 the favourite meal too.
        FoodVariety.Wishes wishes = FoodVariety.Wishes.forTier(shell.needTier(), food.favourite());
        Optional<Integer> slot = food.variety().choose(stock.contents(), food.level(), day, wishes);
        return slot.isEmpty() ? ItemStack.EMPTY : stock.takeOne(slot.get());
    }

    /**
     * Fills the pockets with food from the stock up to the provisions the settler wants.
     *
     * @return whether it took something
     */
    private boolean takeProvisions(Stock stock) {
        boolean handedOut = false;
        int wanted = CivModConfig.PROVISION_NUTRITION.get();
        while (provisions() < wanted) {
            List<ItemStack> contents = stock.contents();
            int slot = -1;
            for (int i = 0; i < contents.size(); i++) {
                if (SettlerFood.isEdible(contents.get(i))) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                break;
            }
            ItemStack item = stock.takeOne(slot);
            handedOut |= !item.isEmpty();
            ItemStack rest = addToPockets(item);
            if (!rest.isEmpty()) {
                stock.insert(rest);
                break;
            }
        }
        return handedOut;
    }

    /** The day meals are noted with outside catch-up: the day the needs are checked on. */
    private long today(ServerLevel level) {
        return shell.moment(level.getOverworldClockTime()).day();
    }

    private void recordMeal(ItemStack item, long day) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item.getItem());
        settler.getFood().variety().record(id, day);
        revealFavouriteIfEaten(id, item);
    }

    /** The first time the settler eats its favourite meal it shows: a diary entry and a thought bubble (none while it catches up). */
    private void revealFavouriteIfEaten(Identifier id, ItemStack item) {
        SettlerFood food = settler.getFood();
        if (food.favourite().filter(id::equals).isPresent() && food.revealFavourite()) {
            Diary.favouriteMeal(settler, id, Diary.Reveal.EATEN);
            shell.showFavouriteMeal(item.getItem());
        }
    }

    private void eatItem(ServerLevel level, ItemStack item, @Nullable Stock stock, long day) {
        eatItem(level, item, stock, day, 1.0);
    }

    /**
     * @param bonus the factor on the food value (nutrition and saturation) of a meal at a working Tavern Host's counter
     */
    private void eatItem(ServerLevel level, ItemStack item, @Nullable Stock stock, long day, double bonus) {
        net.minecraft.world.food.FoodProperties properties = item.get(net.minecraft.core.component.DataComponents.FOOD);
        if (properties == null) {
            return;
        }
        if (bonus != 1.0) {
            properties = new net.minecraft.world.food.FoodProperties((int) Math.round(properties.nutrition() * bonus),
                    (float) (properties.saturation() * bonus), properties.canAlwaysEat());
        }
        settler.getFood().eat(properties);
        recordMeal(item, day);
        hungryNoFood = false;
        eatingEffects(level, item);
        ItemStack rest = SettlerFood.remainder(item);
        if (stock != null) {
            rest = stock.insert(rest);
        }
        rest = addToPockets(rest);
        if (!rest.isEmpty()) {
            settler.spawnAtLocation(level, rest);
        }
    }

    /**
     * Feeds the settler one item by a player: eaten if allowed, otherwise stored as provisions.
     *
     * @param level the level
     * @param item  one food item
     * @return what is left of the item (not eaten and not stored), plus any leftover container
     */
    ItemStack feed(ServerLevel level, ItemStack item) {
        SettlerFood food = settler.getFood();
        if (SettlerFood.mayEat(food.level(), SettlerFood.nutrition(item))) {
            net.minecraft.world.food.FoodProperties properties = item.get(net.minecraft.core.component.DataComponents.FOOD);
            if (properties != null) {
                food.eat(properties);
                recordMeal(item, today(level));
                hungryNoFood = false;
                eatingEffects(level, item);
                return SettlerFood.remainder(item);
            }
        }
        return addToPockets(item);
    }

    private void eatingEffects(ServerLevel level, ItemStack item) {
        if (shell.quiet()) {
            return;
        }
        level.playSound(null, settler.getX(), settler.getY(), settler.getZ(), net.minecraft.sounds.SoundEvents.GENERIC_EAT.value(),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, 0.9F + level.getRandom().nextFloat() * 0.2F);
        if (!item.isEmpty()) {
            level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM,
                    net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(item)),
                    settler.getX(), settler.getEyeY() - 0.2, settler.getZ(), 6, 0.2, 0.1, 0.2, 0.05);
        }
    }

    /**
     * @param stack a stack
     * @return what did not fit into the pockets
     */
    private ItemStack addToPockets(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE && !rest.isEmpty(); i++) {
            ItemStack slot = settler.getInventory().getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, rest) && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(rest.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(move);
                rest.shrink(move);
            }
        }
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE && !rest.isEmpty(); i++) {
            if (settler.getInventory().getItem(i).isEmpty()) {
                settler.getInventory().setItem(i, rest);
                rest = ItemStack.EMPTY;
            }
        }
        settler.getInventory().setChanged();
        return rest;
    }

    /** The pocket the settler eats from now, as an index into the pockets. */
    private Optional<Integer> choosePocketMeal() {
        return SettlerFood.choose(pocketStacks(), settler.getFood().level());
    }

    private ItemStack pocketItem(int pocket) {
        return settler.getInventory().getItem(Settler.POCKETS_START + pocket);
    }

    private List<ItemStack> pocketStacks() {
        List<ItemStack> stacks = new ArrayList<>(Settler.POCKETS_SIZE);
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            stacks.add(settler.getInventory().getItem(i));
        }
        return stacks;
    }
}
