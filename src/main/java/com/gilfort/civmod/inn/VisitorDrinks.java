package com.gilfort.civmod.inn;

import java.util.Optional;

import com.gilfort.civmod.job.SettlerDrinks;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The beer of a visitor in the Tavern evening (vault {@code Systems/NPC Population.md}, "The visitor's day"): it fetches a drink at the
 * counter of its Pantry in the queue of the settlers, with the same service time, and sips it standing, with the same sips, pauses,
 * limit of the evening and last hour as a settler ({@link SettlerDrinks}). It never sits down, gains no points and takes the beer
 * free from the Pantry's stock.
 * <p>
 * {@link VisitorDay} decides when the visitor goes to the counter and walks it there; this class waits at the counter and sips. The
 * state is a small counter per visitor that drinks and an empty one otherwise, so a visitor that drinks nothing costs nothing.
 * Nothing is saved: after a reload a drink in the hand is gone and counts as taken.
 */
final class VisitorDrinks {

    /** A visitor sips on its feet, gains no points and carries nothing out of the inn: the empty bottle goes back into the Pantry. */
    private static final SettlerDrinks.Manner STANDING = new SettlerDrinks.Manner() {
        @Override
        public boolean inPlace(Settler guest) {
            return true;
        }

        @Override
        public ItemStack putAway(ServerLevel level, ItemStack bottle, BlockPos pantry) {
            return ClerkStorage.stock(level, pantry, false).insert(bottle);
        }

        @Override
        public void finished(ServerLevel level, BlockPos pantry, long day) {
            // A visitor gains no points, and it shows its own hand (VisitorDrinks#showInHand), so the shell does not look at it.
        }
    };

    private final Settler visitor;
    private final SettlerDrinks drinks;
    /** Whether the visitor stands at the counter and asks for its turn every tick. */
    private boolean atCounter;
    /** Whether the visitor's main hand shows its drink now (set by {@link #showInHand}). */
    private boolean handShowsDrink;

    VisitorDrinks(Settler visitor) {
        this.visitor = visitor;
        this.drinks = new SettlerDrinks(visitor.getWork(), STANDING);
    }

    /**
     * One decision step in the evening, for a visitor that has eaten: whether it goes to the counter for a drink.
     *
     * @param level  the level
     * @param pantry the Pantry of its inn
     * @return the walk to the counter, empty if the visitor holds a drink, stands at the counter already or drinks nothing now
     */
    Optional<SettlerDrinks.Trip> tripToCounter(ServerLevel level, BlockPos pantry) {
        Optional<SettlerDrinks.Trip> trip = drinks.order(level, pantry);
        if (trip.isEmpty()) {
            atCounter = false;
        }
        return atCounter ? Optional.empty() : trip;
    }

    /** The visitor stands within reach of the counter: from now on it asks for its turn every tick. */
    void arrived() {
        atCounter = true;
    }

    /**
     * @return whether the visitor is on its way to the counter, waits there or holds a drink: it does not stroll then
     */
    boolean busy() {
        return drinks.busy();
    }

    /**
     * @return whether {@link #tick} and {@link #drop} have anything to do: the visitor drinks, or its hand still shows a drink
     *         that the drinks put down since (a stale drink is dropped inside {@code drinks.order}), which {@link #showInHand}
     *         takes out of the hand
     */
    private boolean needsTick() {
        return drinks.busy() || handShowsDrink;
    }

    /**
     * One tick of the visitor: waits for its turn at the counter, counts down a sip or a pause. Nothing happens while the visitor
     * drinks nothing or is in a conversation.
     *
     * @param level the level
     */
    void tick(ServerLevel level) {
        if (!needsTick() || visitor.getWork().talkHoldsDecision()) {
            return;
        }
        if (atCounter && drinks.atCounter(level) != SettlerDrinks.Counter.WAIT) {
            atCounter = false;
        }
        drinks.sip(level);
        showInHand();
    }

    /** The visitor leaves the Tavern evening (day, night, hired): a drink it holds is put back and its hand is empty. */
    void drop() {
        if (!needsTick()) {
            return;
        }
        drinks.drop();
        atCounter = false;
        showInHand();
    }

    /** The display copy of the drink is in the main hand while the visitor holds one: no shell shows it for a visitor. */
    private void showInHand() {
        boolean holds = drinks.holds();
        if (holds != handShowsDrink) {
            handShowsDrink = holds;
            visitor.setItemSlot(EquipmentSlot.MAINHAND, holds ? drinks.inHand() : ItemStack.EMPTY);
        }
    }

    @Override
    public String toString() {
        return "VisitorDrinks[busy=" + drinks.busy() + ", atCounter=" + atCounter + "]";
    }
}
