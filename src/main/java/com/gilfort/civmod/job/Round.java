package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gilfort.civmod.logistics.CraftPlan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * One claimed part of one request, from fetching the inputs to the delivery (vault {@code Jobs/Craftsperson.md}).
 * Kept in the {@link Workplace} of the Producing Job; not saved (a claim lost by unloading is released at the next
 * decision).
 */
final class Round {

    /** The step of a round; WAIT: input deliveries are on their way to the collect inventory. */
    enum Phase { WAIT, FETCH, CRAFT, DELIVER }

    final UUID request;
    final Item item;
    final List<CraftPlan.Step> steps;
    /** Inputs still to take, by item. */
    final Map<Item, Integer> toTake;
    /** Places still to fetch from (collect inventory, then the Clerk; only the collect inventory after input deliveries). */
    final List<BlockPos> sources;
    /** The input deliveries the round waits for (on "Delivery: Deliveryman"). */
    final List<UUID> inputs;
    Phase phase;
    int step;
    int crafted;

    /**
     * @param request the claimed request
     * @param item    the requested item
     * @param plan    how the claimed amount is made
     * @param sources where the inputs are fetched (empty: everything is held already)
     * @param inputs  the input deliveries bringing inputs to the collect inventory first (empty: none)
     */
    Round(UUID request, Item item, CraftPlan plan, List<BlockPos> sources, List<UUID> inputs) {
        this.request = request;
        this.item = item;
        this.steps = plan.steps();
        this.toTake = new LinkedHashMap<>(plan.takes());
        this.sources = new ArrayList<>(sources);
        this.inputs = List.copyOf(inputs);
        this.phase = !inputs.isEmpty() ? Phase.WAIT : sources.isEmpty() ? Phase.CRAFT : Phase.FETCH;
    }

    /**
     * @return whether every step is crafted
     */
    boolean crafted() {
        return step >= steps.size();
    }
}
