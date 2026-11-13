package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.gilfort.civmod.settlement.DeliveryOrders;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What a Deliveryman takes from the source of a Delivery Slip and where it goes (vault {@code Jobs/Deliveryman.md}),
 * worked out once per visit while he stands at the source: the filter, "keep at the source", "fill target up to" and
 * what each target still takes decide. It reads the targets once ({@link Stock#fits}), never changes anything.
 *
 * @param legs      the targets to visit in order, each with what it gets; empty: nothing to move
 * @param next      where the round robin goes next: the position in the slip's targets after the last leg
 */
public record SlipPlan(List<Leg> legs, int next) {

    /**
     * One stop of the trip.
     *
     * @param target the target container
     * @param items  how many of each item it gets
     */
    public record Leg(BlockPos target, Map<Item, Integer> items) {

        /**
         * @param target the target container
         * @param items  how many of each item it gets
         */
        public Leg {
            items = Map.copyOf(items);
        }
    }

    /**
     * @param legs the targets to visit in order
     * @param next where the round robin goes next
     */
    public SlipPlan {
        legs = List.copyOf(legs);
    }

    /**
     * @return whether there is nothing to move
     */
    public boolean empty() {
        return legs.isEmpty();
    }

    /**
     * @return how many of each item the Deliveryman takes at the source, in all legs
     */
    public Map<Item, Integer> take() {
        Map<Item, Integer> all = new LinkedHashMap<>();
        legs.forEach(leg -> leg.items().forEach((item, count) -> all.merge(item, count, Integer::sum)));
        return all;
    }

    /**
     * Works out a visit.
     *
     * @param slip          the slip
     * @param clock         its clock (where the round robin stands)
     * @param source        what the source holds
     * @param targetStock   what a target takes
     * @param carryStacks   how many stacks the Deliveryman can carry
     * @param random        for the random variant
     * @return the plan
     */
    public static SlipPlan of(DeliverySlip slip, DeliveryOrders.Clock clock, Stock source, Function<BlockPos, Stock> targetStock,
            int carryStacks, RandomSource random) {
        Map<Item, Integer> spare = new LinkedHashMap<>();
        source.counts().forEach((item, count) -> {
            int free = count - slip.keep();
            if (free > 0 && slip.accepts(new ItemStack(item))) {
                spare.put(item, free);
            }
        });
        List<BlockPos> order = targetOrder(slip, clock, random);
        List<Leg> legs = new ArrayList<>();
        Map<Item, Integer> taken = new LinkedHashMap<>();
        for (BlockPos target : order) {
            Map<Item, Integer> items = fill(slip, targetStock.apply(target), spare, taken, carryStacks);
            if (!items.isEmpty()) {
                legs.add(new Leg(target, items));
                // One target per visit for the round robin variants, so the load spreads over the visits.
                if (slip.variant() == DeliverySlip.Variant.ROUND_ROBIN || slip.variant() == DeliverySlip.Variant.FORCED_ROUND_ROBIN) {
                    break;
                }
            }
        }
        int next = legs.isEmpty() ? clock.next() : (slip.targets().indexOf(legs.get(legs.size() - 1).target()) + 1) % slip.targets().size();
        return new SlipPlan(legs, next);
    }

    /**
     * A cheap look at the targets before a task is made, without the source: does any target have an inventory, is a
     * listed item below the fill limit and does it fit? A blacklist slip cannot be checked item by item, so a target with
     * an inventory counts as having room.
     *
     * @param slip        the slip
     * @param targetStock what a target takes
     * @return whether at least one target may take something
     */
    public static boolean roomLeft(DeliverySlip slip, Function<BlockPos, Stock> targetStock) {
        for (BlockPos pos : slip.targets()) {
            Stock target = targetStock.apply(pos);
            if (target.isEmpty()) {
                continue;
            }
            if (slip.blacklist() || slip.filter().stream().anyMatch(item -> (slip.fillTo() <= 0 || target.count(item) < slip.fillTo())
                    && target.fits(new ItemStack(item)) > 0)) {
                return true;
            }
        }
        return false;
    }

    /** What one target gets of the spare items, within what the Deliveryman can still carry. */
    private static Map<Item, Integer> fill(DeliverySlip slip, Stock target, Map<Item, Integer> spare, Map<Item, Integer> taken,
            int carryStacks) {
        Map<Item, Integer> items = new LinkedHashMap<>();
        if (target.isEmpty()) {
            return items;
        }
        for (Map.Entry<Item, Integer> entry : spare.entrySet()) {
            Item item = entry.getKey();
            int want = entry.getValue() - taken.getOrDefault(item, 0);
            if (slip.fillTo() > 0) {
                want = Math.min(want, slip.fillTo() - target.count(item));
            }
            want = Math.min(want, carryRoom(taken, item, carryStacks));
            int fit = want > 0 ? target.fits(new ItemStack(item, want)) : 0;
            if (fit > 0) {
                items.put(item, fit);
                taken.merge(item, fit, Integer::sum);
            }
        }
        return items;
    }

    /** How many more of an item fit the carry inventory next to what is taken already. */
    private static int carryRoom(Map<Item, Integer> taken, Item item, int carryStacks) {
        int maxStack = new ItemStack(item).getMaxStackSize();
        int others = taken.entrySet().stream().filter(entry -> entry.getKey() != item)
                .mapToInt(entry -> stacks(entry.getValue(), new ItemStack(entry.getKey()).getMaxStackSize())).sum();
        return Math.max(0, (carryStacks - others) * maxStack - taken.getOrDefault(item, 0));
    }

    private static int stacks(int count, int maxStack) {
        return (count + maxStack - 1) / maxStack;
    }

    /**
     * The targets in the order the variant tries them.
     *
     * @param slip   the slip
     * @param clock  its clock
     * @param random for the random variant
     * @return all targets in order; for the forced round robin only the next one
     */
    private static List<BlockPos> targetOrder(DeliverySlip slip, DeliveryOrders.Clock clock, RandomSource random) {
        List<BlockPos> targets = new ArrayList<>(slip.targets());
        BlockPos from = slip.source().orElseThrow(() -> new IllegalStateException("A filled slip has a source: " + slip));
        switch (slip.variant()) {
            case NEAREST -> targets.sort(Comparator.comparingDouble(target -> target.distSqr(from)));
            case FURTHEST -> targets.sort(Comparator.comparingDouble((BlockPos target) -> target.distSqr(from)).reversed());
            case RANDOM -> {
                for (int i = targets.size() - 1; i > 0; i--) {
                    Collections.swap(targets, i, random.nextInt(i + 1));
                }
            }
            case ROUND_ROBIN, FORCED_ROUND_ROBIN -> Collections.rotate(targets, -(clock.next() % targets.size()));
        }
        return slip.variant() == DeliverySlip.Variant.FORCED_ROUND_ROBIN ? targets.subList(0, 1) : targets;
    }
}
