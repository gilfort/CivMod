package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.logistics.CraftPlan;
import com.gilfort.civmod.logistics.ItemKey;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

/**
 * One claimed part of one request, from fetching the inputs to the delivery (vault {@code Jobs/Craftsperson.md},
 * {@code Systems/Producing Jobs.md}), or the crafts of an always-produce pattern, without a request.
 * Kept in the {@link Workplace} of the Producing Job; not saved (a claim lost by unloading is released at the next
 * decision).
 */
final class Round {

    /** The step of a round; WAIT: input deliveries are on their way to the collect inventory. */
    enum Phase { WAIT, FETCH, CRAFT, DELIVER }

    /** The claimed request, empty for an always-produce pattern. */
    final Optional<UUID> request;
    final ItemKey item;
    final List<CraftPlan.Step> steps;
    /** Inputs still to take, by thing (item and relevant components). */
    final Map<ItemKey, Integer> toTake;
    /** Places still to fetch from (collect inventory, then the Clerk; only the collect inventory after input deliveries). */
    final List<BlockPos> sources;
    /** The input deliveries the round waits for (on "Delivery: Deliveryman"). */
    final List<UUID> inputs;
    /** The tools of innate recipes the settler lacks on its belt, taken at the collect inventory (empty: none). */
    final List<Ingredient> tools;
    /** The kinds of item the executions of the round drew from a {@code drops} recipe, delivered with {@link #item}. */
    final Set<Item> drawn = new HashSet<>();
    /** What the executions left over from their inputs (empty buckets), by item: delivered with {@link #item}, no more than this. */
    private final Map<Item, Integer> remainders = new HashMap<>();
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
     * @param request the claimed request, empty for an always-produce pattern
     * @param item    the thing made (item and relevant components)
     * @param plan    how the claimed amount is made
     * @param takes   the inputs (and fuel) to fetch, by item
     * @param sources where the inputs are fetched (empty: everything is held already)
     * @param inputs  the input deliveries bringing inputs to the collect inventory first (empty: none)
     * @param tools   the tools of innate recipes to take at the collect inventory (empty: none)
     */
    Round(Optional<UUID> request, ItemKey item, CraftPlan plan, Map<ItemKey, Integer> takes, List<BlockPos> sources, List<UUID> inputs, List<Ingredient> tools) {
        this.request = request;
        this.item = item;
        this.steps = plan.steps();
        this.toTake = new LinkedHashMap<>(takes);
        this.sources = new ArrayList<>(sources);
        this.inputs = List.copyOf(inputs);
        this.tools = List.copyOf(tools);
        this.phase = !inputs.isEmpty() ? Phase.WAIT : sources.isEmpty() ? Phase.CRAFT : Phase.FETCH;
    }

    /**
     * Notes what one execution left over from its inputs.
     *
     * @param leftovers the remainders of the execution
     */
    void addRemainders(List<ItemStack> leftovers) {
        leftovers.forEach(left -> remainders.merge(left.getItem(), left.getCount(), Integer::sum));
    }

    /**
     * Takes what the round delivers out of the buffer: the item, every kind drawn from a {@code drops} recipe and the
     * remainders its executions left (not the spare items of that kind the buffer holds besides).
     *
     * @param buffer the Job's buffer
     * @return the stacks to deliver
     */
    List<ItemStack> takeProduct(JobBuffer buffer) {
        List<ItemStack> product = new ArrayList<>(buffer.takeAll(stack -> item.matches(stack) || drawn.contains(stack.getItem())));
        remainders.forEach((kind, count) -> {
            for (int left = count; left > 0; left--) {
                ItemStack one = buffer.takeOne(stack -> stack.is(kind));
                if (one.isEmpty()) {
                    break;
                }
                ItemStack last = product.isEmpty() ? ItemStack.EMPTY : product.getLast();
                if (ItemStack.isSameItemSameComponents(last, one) && last.getCount() < last.getMaxStackSize()) {
                    last.grow(1);
                } else {
                    product.add(one);
                }
            }
        });
        return product;
    }

    /** The nearest stations looked up are forgotten: one was replaced ({@code station_after}). */
    void forgetStations() {
        stations.clear();
    }

    /**
     * @return whether it works an always-produce pattern instead of a request
     */
    boolean always() {
        return request.isEmpty();
    }

    /**
     * @return whether every step is crafted
     */
    boolean crafted() {
        return step >= steps.size();
    }
}
