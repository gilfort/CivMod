package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.entertainment.Tavern;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.food.TavernDrinks;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/**
 * The drinks of one Tavern guest (vault {@code Systems/Entertainment.md}, Tavern drinks): it fetches a drink at the
 * counter of its Pantry like a meal (the same queue and service time) and drinks it in several sips, with a pause between two.
 * <p>
 * A settler drinks at its seat: a finished drink gives it a little Entertainment while the Tavern condition holds, and the empty
 * bottle goes into its pockets and back into the Pantry with the next order. A visitor of an inn drinks on its feet near the
 * counter ({@code VisitorDrinks}): it gains no points, and the empty bottle goes straight back into the Pantry, as a visitor
 * carries nothing out of the inn. That difference is the {@link Manner} of the guest. Both count their drinks in the settler's
 * {@link Entertainment}, which the limit of the evening reads.
 * <p>
 * It only says what it wants ({@link SettlerTavern} turns that into a {@link BodyStep}). The state is a small counter per
 * guest that drinks: nothing is saved, nothing is scanned, and the sips run on the tick the guest has anyway. What counts for the
 * evening (the drinks taken, the points they gave) is saved with the settler's {@link Entertainment}.
 */
public final class SettlerDrinks {

    /** What a guest standing at the counter learns. */
    public enum Counter {
        /** Its turn has not come yet. */
        WAIT,
        /** It took its drink and goes back to its seat. */
        SERVED,
        /** The counter gave it nothing (no drink left): it goes back to its seat. */
        EMPTY_HANDED,
        /** It has no order at the counter (it gave up, or never went): it goes back to its seat. */
        NO_ORDER
    }

    /** How a kind of guest differs in its drinking: where it sips, where the empty bottle goes and what a finished drink gives. */
    public interface Manner {

        /**
         * @param guest the guest
         * @return whether the guest is where it sips: a settler at its seat, a visitor on its feet
         */
        boolean inPlace(Settler guest);

        /**
         * @param level  the level
         * @param bottle what is left of a drink
         * @param pantry the Pantry the drink came from
         * @return what did not fit where the bottle goes; it lies on the ground
         */
        ItemStack putAway(ServerLevel level, ItemStack bottle, BlockPos pantry);

        /**
         * The last sip is drunk and the bottle given back.
         *
         * @param level  the level
         * @param pantry the Pantry the drink came from
         * @param day    the day of the evening
         */
        void finished(ServerLevel level, BlockPos pantry, long day);
    }

    /** A settler: it sips at its seat, keeps the bottle in its pockets and gains points while the Tavern condition holds. */
    private static final class Seated implements Manner {
        private final SettlerWork shell;

        Seated(SettlerWork shell) {
            this.shell = shell;
        }

        @Override
        public boolean inPlace(Settler guest) {
            return guest.isSitting();
        }

        @Override
        public ItemStack putAway(ServerLevel level, ItemStack bottle, BlockPos pantry) {
            return shell.meals().addToPockets(bottle);
        }

        @Override
        public void finished(ServerLevel level, BlockPos pantry, long day) {
            if (Tavern.conditionHolds(level, pantry)) {
                shell.countDrinkDrunk(EntertainmentRules.get().drinkPoints(ClerkCounter.clerkSkill(level, pantry)), day);
            }
            shell.observe();
        }
    }

    /** Ticks a guest does not look into the stock again after it found no drink there. */
    private static final long NO_DRINK_WAIT_TICKS = 600L;
    /** Ticks a guest waits for its turn at the counter at most (a long queue, a Tavern Host who does not come back). */
    private static final long ORDER_GIVE_UP_TICKS = 1200L;
    /** Ticks between the drink arriving at the seat and its first sip. */
    private static final int FIRST_SIP_DELAY_TICKS = 1;
    private static final float SIP_VOLUME = 0.5F;

    /**
     * The way to the counter.
     *
     * @param target where the guest queues
     * @param reach  how close it has to come, horizontally
     */
    public record Trip(BlockPos target, double reach) {
    }

    /** The trip of a guest: where it queues and since when it has been on its way. */
    private record Order(BlockPos pantry, BlockPos target, double reach, long since) {
    }

    /** The drink in hand and how far it is drunk. */
    private static final class Drink {
        private final ItemStack item;
        private final BlockPos pantry;
        private final long day;
        private int sipsLeft;
        private boolean sipping;
        private int ticksLeft;

        Drink(ItemStack item, BlockPos pantry, long day, int sips) {
            this.item = item;
            this.pantry = pantry;
            this.day = day;
            this.sipsLeft = sips;
            this.ticksLeft = FIRST_SIP_DELAY_TICKS;
        }
    }

    private final SettlerWork shell;
    private final Settler settler;
    private final Manner manner;
    private @Nullable Order order;
    private @Nullable Drink drink;
    /** Game time before which the guest does not read the stock again. */
    private long noDrinkUntil;

    /**
     * The drinks of a settler.
     *
     * @param shell the work object of the settler
     */
    SettlerDrinks(SettlerWork shell) {
        this(shell, new Seated(shell));
    }

    /**
     * @param shell  the work object of the guest: a settler's, or a visitor's, which a visitor has without running it
     * @param manner how this kind of guest drinks
     */
    public SettlerDrinks(SettlerWork shell, Manner manner) {
        this.shell = shell;
        this.settler = shell.settler();
        this.manner = manner;
    }

    /**
     * The drink in the guest's hand, shown in its hand on the way to the seat and while it sips.
     *
     * @return a copy of the drink, empty while the guest holds none
     */
    public ItemStack inHand() {
        Drink current = drink;
        return current == null ? ItemStack.EMPTY : current.item.copy();
    }

    /**
     * @return whether the guest is on its way to the counter, waits there or holds a drink
     */
    public boolean busy() {
        return order != null || drink != null;
    }

    /**
     * @return whether the guest holds a drink
     */
    public boolean holds() {
        return drink != null;
    }

    /**
     * The guest's next trip to the counter. A guest already on its way goes on while the Tavern Host works and the wait is not
     * too long. A seated guest (a visitor: on its feet) without a drink starts a trip when the evening allows one: fewer drinks than the evening's limit,
     * enough time before the shift ends, the Tavern Host on shift and at the counter, and a drink in the stock (the stock is
     * read here, and not again for a while when it holds none).
     *
     * @param level  the level
     * @param pantry the Pantry whose seat the guest booked
     * @return the walk to the counter, empty if the guest stays where it is
     */
    public Optional<Trip> order(ServerLevel level, BlockPos pantry) {
        dropIfStale(level);
        if (drink != null) {
            return Optional.empty();
        }
        long now = level.getGameTime();
        long dayTime = DayClock.dayTime(level);
        Order trip = order;
        if (trip != null) {
            if (now - trip.since() < ORDER_GIVE_UP_TICKS && ClerkStorage.working(level, trip.pantry(), dayTime)) {
                return Optional.of(walk(trip));
            }
            order = null;
            return Optional.empty();
        }
        if (!manner.inPlace(settler) || !mayOrder(level, pantry, now, dayTime)) {
            return Optional.empty();
        }
        if (!ClerkStorage.stock(level, pantry, false).contains(TavernDrinks::is)) {
            noDrinkUntil = now + NO_DRINK_WAIT_TICKS;
            return Optional.empty();
        }
        ClerkStorage.Visit visit = shell.reachableVisit(level, pantry, ClerkStorage.visit(level, pantry, dayTime));
        if (!visit.counter()) {
            return Optional.empty();
        }
        order = new Order(pantry, visit.target(), visit.reach(), now);
        return Optional.of(walk(order));
    }

    private static Trip walk(Order trip) {
        return new Trip(trip.target(), trip.reach());
    }

    private boolean mayOrder(ServerLevel level, BlockPos pantry, long now, long dayTime) {
        return now >= noDrinkUntil && Tavern.ordersOpen(dayTime) && ClerkStorage.working(level, pantry, dayTime)
                && shell.drinksOn(DayClock.day(level)) < EntertainmentRules.get().drinks().maxPerEvening();
    }

    /**
     * The guest stands at the counter: it waits for its turn, then takes a drink from the stock (the Tavern Host gains EXP and
     * swings its arm), puts the empty bottles of earlier drinks back and has the drink in hand.
     *
     * @param level the level
     * @return whether it still waits, took its drink or got none
     */
    public Counter atCounter(ServerLevel level) {
        Order trip = order;
        if (trip == null) {
            return Counter.NO_ORDER;
        }
        if (!shell.waitAtCounter(level, trip.pantry())) {
            return Counter.WAIT;
        }
        order = null;
        Stock stock = ClerkStorage.stock(level, trip.pantry(), false);
        shell.meals().returnContainers(stock);
        ItemStack taken = takeDrink(stock);
        if (taken.isEmpty()) {
            noDrinkUntil = level.getGameTime() + NO_DRINK_WAIT_TICKS;
            return Counter.EMPTY_HANDED;
        }
        long day = DayClock.day(level);
        shell.countDrinkTaken(day);
        ClerkCounter.handOut(level, trip.pantry(), true);
        drink = new Drink(taken, trip.pantry(), day, EntertainmentRules.get().drinks().sipCount(level.getRandom()));
        return Counter.SERVED;
    }

    /**
     * One tick of the guest at its seat: counts down the sip or the pause, and ends the drink after the last sip. Does nothing
     * while the guest holds no drink or does not sit.
     *
     * @param level the level
     */
    public void sip(ServerLevel level) {
        Drink current = drink;
        if (current == null || !manner.inPlace(settler) || --current.ticksLeft > 0) {
            return;
        }
        EntertainmentRules.DrinkRules rules = EntertainmentRules.get().drinks();
        if (!current.sipping) {
            current.sipping = true;
            current.ticksLeft = rules.sipTicks();
            settler.setSipping(true);
            if (!shell.quiet()) {
                level.playSound(null, settler.getX(), settler.getY(), settler.getZ(), SoundEvents.GENERIC_DRINK.value(), SoundSource.NEUTRAL, SIP_VOLUME,
                        SettlerMeals.variedPitch(level.getRandom()));
            }
            return;
        }
        current.sipping = false;
        settler.setSipping(false);
        if (--current.sipsLeft > 0) {
            current.ticksLeft = rules.pauseTicks(level.getRandom());
            return;
        }
        finish(level, current);
    }

    /** The last sip is drunk: the empty bottle is given back, then the guest's manner says what the drink gives it. */
    private void finish(ServerLevel level, Drink finished) {
        drink = null;
        giveBackBottle(level, finished);
        manner.finished(level, finished.pantry, finished.day);
    }

    /**
     * The guest leaves the Tavern (bedtime, its seat gone, an appointment): a drink it holds is put down, its bottle given back,
     * without points; a trip to the counter ends.
     */
    public void drop() {
        order = null;
        Drink current = drink;
        if (current == null) {
            return;
        }
        drink = null;
        settler.setSipping(false);
        if (settler.level() instanceof ServerLevel level) {
            giveBackBottle(level, current);
        }
    }

    /** A drink taken on an evening that is over (the settler went to bed with it, or was loaded much later) is put down. */
    private void dropIfStale(ServerLevel level) {
        Drink current = drink;
        if (current != null && current.day != DayClock.day(level)) {
            drop();
        }
    }

    /** What is left of a drink goes where the guest's manner puts it; what does not fit lies on the ground. */
    private void giveBackBottle(ServerLevel level, Drink finished) {
        spill(level, manner.putAway(level, SettlerFood.remainder(finished.item), finished.pantry));
    }

    private void spill(ServerLevel level, ItemStack rest) {
        if (!rest.isEmpty()) {
            settler.spawnAtLocation(level, rest);
        }
    }

    /**
     * The drinks of a missed evening (the Tavern condition holds): the settler drinks up to {@code catchup_drinks} of them, within
     * the limit of the evening, from what the Pantry's stock holds. A Pantry that is not loaded has an empty stock, so nothing
     * is drunk there. The bottles go back into the stock, the points are written and the Tavern Host gains EXP for each hand-out
     * (without the arm swing).
     *
     * @param level  the level
     * @param pantry the Pantry
     * @param day    the day of the missed evening
     */
    void catchUp(ServerLevel level, BlockPos pantry, long day) {
        EntertainmentRules rules = EntertainmentRules.get();
        int wanted = Math.min(rules.drinks().catchup(), rules.drinks().maxPerEvening() - shell.drinksOn(day));
        if (wanted <= 0) {
            return;
        }
        Stock stock = ClerkStorage.stock(level, pantry, false);
        double points = rules.drinkPoints(ClerkCounter.clerkSkill(level, pantry));
        for (int i = 0; i < wanted; i++) {
            ItemStack taken = takeDrink(stock);
            if (taken.isEmpty()) {
                return;
            }
            ClerkCounter.handOut(level, pantry, false);
            shell.countDrinkTaken(day);
            shell.countDrinkDrunk(points, day);
            spill(level, shell.meals().addToPockets(stock.insert(SettlerFood.remainder(taken))));
        }
    }

    private static ItemStack takeDrink(Stock stock) {
        List<ItemStack> contents = stock.contents();
        for (int slot = 0; slot < contents.size(); slot++) {
            if (TavernDrinks.is(contents.get(slot))) {
                return stock.takeOne(slot);
            }
        }
        return ItemStack.EMPTY;
    }
}
