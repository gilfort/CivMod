package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.config.CivModConfig;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Carries out the meal intents of the {@link Schedule} for the Settler Shell (vault {@code Systems/Needs.md}): eat from
 * the pockets, fetch food at a food point (Clerk JobBlock), wait for food, forage; plus hunger, a player feeding the
 * settler and complaining. It keeps the running state of a meal and reads and writes the settler's
 * {@link SettlerFood}. It answers the shell with a {@link BodyStep}; only the shell moves the settler and sets its
 * activity. Catch-up eats through the same parts on the spot.
 */
final class SettlerMeals {

    /** Ticks a meal from the pockets takes. */
    private static final int EAT_TICKS = 30;
    /** Ticks a foraging settler walks before it vanishes. */
    private static final int FORAGE_WALK_TICKS = 600;
    /** Players within this distance get the hunger message. */
    private static final double COMPLAIN_RANGE = 32.0;

    private final SettlerWork shell;
    private final Settler settler;
    private double weightedWorkTicks;
    private int eatTimer;
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
        return SettlerFood.choose(pocketStacks(), settler.getFood().level()).isPresent();
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
     * @return the Clerk JobBlock this settler waits at for food (its own as a Clerk, else the nearest), empty if the
     *         settlement has none
     */
    Optional<BlockPos> clerkToWaitAt(ServerLevel level) {
        Optional<BlockPos> own = shell.ownClerk();
        if (own.isPresent()) {
            return own;
        }
        return SettlementData.get(level).settlement(settler.getSettlementId())
                .flatMap(settlement -> ClerkStorage.nearestClerk(settlement, settler.blockPosition()));
    }

    /**
     * @param level   the level
     * @param dayTime the day time of the decision
     * @return a lookup of the nearest food point with food, for one decision
     */
    FoodLookup lookup(ServerLevel level, long dayTime) {
        return new FoodLookup(level, dayTime);
    }

    /** The nearest food point (Clerk JobBlock) with food, looked up once and only when asked. */
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
                .flatMap(settlement -> ClerkStorage.nearestFood(level, settlement, settler.blockPosition(), dayTime, shell.ownClerk(),
                        clerk -> !shell.counterUnreachable(level, clerk)));
    }

    // ---------------------------------------------------------------- meal intents

    /**
     * Starts eating provisions on the spot ({@link Intent#EAT_FROM_POCKETS}, {@link Intent#EAT_UNTIL_FULL}).
     *
     * @param untilFull keep eating until full (evening) instead of one item
     * @return eat, or stay if there is nothing to eat
     */
    BodyStep pocketMeal(boolean untilFull) {
        if (!hasPocketMeal()) {
            return BodyStep.STAY;
        }
        eatTimer = EAT_TICKS;
        eatUntilFull = untilFull;
        hungryNoFood = false;
        return BodyStep.EAT;
    }

    /**
     * One tick of a meal from the pockets: eating effects, an item eaten when the timer runs out.
     *
     * @param level the level
     * @return stay while eating; idle when nothing is left to eat, done after the meal
     */
    BodyStep eatTick(ServerLevel level) {
        Optional<Integer> choice = SettlerFood.choose(pocketStacks(), settler.getFood().level());
        if (choice.isEmpty()) {
            return BodyStep.DONE_NO_DECISION;
        }
        ItemStack stack = settler.getInventory().getItem(Settler.POCKETS_START + choice.get());
        if (--eatTimer % 6 == 0 && eatTimer > 0) {
            eatingEffects(level, stack);
        }
        if (eatTimer > 0) {
            return BodyStep.STAY;
        }
        ItemStack one = stack.split(1);
        settler.getInventory().setChanged();
        eatItem(level, one, null, today(level));
        if (eatUntilFull && hasPocketMeal()) {
            eatTimer = EAT_TICKS;
            return BodyStep.STAY;
        }
        return BodyStep.DONE;
    }

    /**
     * Walks to a food point with food ({@link Intent#FETCH_FOOD}).
     *
     * @param level   the level
     * @param found   the food point
     * @param purpose why the settler fetches food
     * @return walk to the food point (its block slots if this settler cannot reach the counter)
     */
    BodyStep fetchFood(ServerLevel level, ClerkStorage.FoodPoint found, MealPurpose purpose) {
        ClerkStorage.FoodPoint point = new ClerkStorage.FoodPoint(found.clerk(), shell.reachableVisit(level, found.clerk(), found.visit()));
        mealPurpose = purpose;
        foodPoint = point;
        hungryNoFood = false;
        return new BodyStep.Walk(Activity.FETCH_FOOD, point.visit().target(), point.visit().reach());
    }

    /**
     * At the food point: waits for its turn at a working Clerk's counter, then eats and takes provisions.
     *
     * @param level the level
     * @return stay while waiting at the counter, done after the visit
     */
    BodyStep atFoodPoint(ServerLevel level) {
        ClerkStorage.FoodPoint point = foodPoint;
        if (point != null) {
            boolean counter = point.visit().counter() && shell.ownClerk().filter(point.clerk()::equals).isEmpty();
            if (counter && !shell.waitAtCounter(level, point.clerk())) {
                return BodyStep.STAY;
            }
            if (visitFoodPoint(level, point, today(level), DayClock.dayTime(level), point.visit().counter())
                    && counter) {
                ClerkCounter.handOut(level, point.clerk(), true);
            }
        }
        mealPurpose = null;
        foodPoint = null;
        return BodyStep.DONE;
    }

    /**
     * Waits for food at the queue of the nearest Clerk JobBlock ({@link Intent#WAIT_FOR_FOOD}, where its counter is
     * now). A Clerk waits at its own counter: it works its shift meanwhile.
     *
     * @param level the level
     * @return work (a Clerk), walk to the queue, or stand still without a Clerk JobBlock
     */
    BodyStep waitForFood(ServerLevel level) {
        if (shell.ownClerk().isPresent()) {
            return BodyStep.WORK;
        }
        Optional<BlockPos> clerk = clerkToWaitAt(level);
        if (clerk.isEmpty()) {
            return BodyStep.STOP;
        }
        ClerkStorage.Visit visit = shell.reachableVisit(level, clerk.get(), ClerkStorage.visit(level, clerk.get(), DayClock.dayTime(level)));
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
        Optional<Integer> choice = SettlerFood.choose(pocketStacks(), settler.getFood().level());
        choice.ifPresent(slot -> eatItem(level, settler.getInventory().getItem(Settler.POCKETS_START + slot).split(1), null, day));
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
        if (visitFoodPoint(level, point, day, dayTime, true)) {
            ClerkCounter.handOut(level, point.clerk(), false);
        }
        mealPurpose = null;
    }

    // ---------------------------------------------------------------- eating

    /**
     * At a food point: eats until full (not in the morning unless hungry), puts back leftover containers and refills
     * the provisions, from what the access rule lets the settler reach.
     *
     * @param day       the day of the meal
     * @param atCounter whether the settler is served at the counter of a working Clerk (catch-up: always)
     * @return whether a working Clerk handed something out (food from its whole storage at the counter)
     */
    private boolean visitFoodPoint(ServerLevel level, ClerkStorage.FoodPoint point, long day, long dayTime, boolean atCounter) {
        boolean own = shell.ownClerk().filter(point.clerk()::equals).isPresent();
        Stock stock = own ? ClerkStorage.stock(level, point.clerk(), true) : ClerkStorage.stock(level, point.clerk(), dayTime, atCounter);
        if (stock.isEmpty()) {
            return false;
        }
        boolean handedOut = false;
        SettlerFood food = settler.getFood();
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(i);
            if (stack.is(Items.BOWL) || stack.is(Items.GLASS_BOTTLE)) {
                settler.getInventory().setItem(i, stock.insert(stack));
            }
        }
        if (Schedule.eatsAtFoodPoint(mealPurpose, food.level())) {
            while (food.level() < SettlerFood.MAX) {
                Optional<Integer> slot = food.variety().choose(stock.contents(), food.level(), day, CivModConfig.FOOD_VARIETY_DAYS.get());
                if (slot.isEmpty()) {
                    break;
                }
                ItemStack item = stock.takeOne(slot.get());
                if (item.isEmpty()) {
                    break;
                }
                handedOut = true;
                eatItem(level, item, stock, day);
            }
        }
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
        return handedOut && stock.counter() && !own;
    }

    /** The day meals are noted with outside catch-up: the day the needs are checked on. */
    private long today(ServerLevel level) {
        return shell.moment(level.getOverworldClockTime()).day();
    }

    private void recordMeal(ItemStack item, long day) {
        settler.getFood().variety().record(BuiltInRegistries.ITEM.getKey(item.getItem()), day);
    }

    private void eatItem(ServerLevel level, ItemStack item, @Nullable Stock stock, long day) {
        net.minecraft.world.food.FoodProperties properties = item.get(net.minecraft.core.component.DataComponents.FOOD);
        if (properties == null) {
            return;
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

    private List<ItemStack> pocketStacks() {
        List<ItemStack> stacks = new ArrayList<>(Settler.POCKETS_SIZE);
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            stacks.add(settler.getInventory().getItem(i));
        }
        return stacks;
    }
}
