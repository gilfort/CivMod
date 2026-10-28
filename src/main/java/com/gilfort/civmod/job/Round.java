package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.logistics.CraftPlan;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

/**
 * One claimed part of one request, from fetching the inputs to the delivery (vault {@code Jobs/Craftsperson.md},
 * {@code Systems/Producing Jobs.md}).
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
    /** The tools of innate recipes the settler lacks on its belt, taken at {@link #toolSource} (empty: none). */
    final List<Ingredient> tools;
    /** Where the tools are fetched: the collect inventory. */
    final BlockPos toolSource;
    /** The nearest station per station tag of a recipe type other than the work place of the day's, looked up once. */
    final Map<TagKey<Block>, Optional<BlockPos>> stations = new HashMap<>();
    /**
     * The charges running in the CRAFT phase: per station, the progress (operations) of the execution it works now. A
     * Job with parallel stations runs one charge per station; the inputs leave the buffer when an execution is done.
     */
    final Map<BlockPos, Double> charges = new LinkedHashMap<>();
    /** The vanilla cooking speed per station a cooking step is worked at ({@link Furnaces#cookingSpeed}), looked up once. */
    final Map<BlockPos, Double> cookingSpeeds = new HashMap<>();
    /** The stations the current step is worked at (the station of the step first), chosen at the last decision. */
    List<BlockPos> chargeStations = List.of();
    Phase phase;
    int step;
    int crafted;

    /**
     * @param request the claimed request
     * @param item    the requested item
     * @param plan    how the claimed amount is made
     * @param takes   the inputs (and fuel) to fetch, by item
     * @param sources where the inputs are fetched (empty: everything is held already)
     * @param inputs     the input deliveries bringing inputs to the collect inventory first (empty: none)
     * @param tools      the tools of innate recipes to take at the collect inventory (empty: none)
     * @param toolSource the collect inventory
     */
    Round(UUID request, Item item, CraftPlan plan, Map<Item, Integer> takes, List<BlockPos> sources, List<UUID> inputs, List<Ingredient> tools,
            BlockPos toolSource) {
        this.request = request;
        this.item = item;
        this.steps = plan.steps();
        this.toTake = new LinkedHashMap<>(takes);
        this.sources = new ArrayList<>(sources);
        this.inputs = List.copyOf(inputs);
        this.tools = List.copyOf(tools);
        this.toolSource = toolSource;
        this.phase = !inputs.isEmpty() ? Phase.WAIT : sources.isEmpty() ? Phase.CRAFT : Phase.FETCH;
    }

    /**
     * @return whether every step is crafted
     */
    boolean crafted() {
        return step >= steps.size();
    }
}
