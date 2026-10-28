package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * How a Producing Job makes an amount of an item with its checked patterns (vault {@code Systems/Producing Jobs.md},
 * {@code Jobs/Craftsperson.md}), for every recipe type of its profile: the
 * full need is computed down to base inputs; inputs it already holds come first, then the stock; a missing input it
 * has a pattern for is crafted first in its own buffer (intermediates, never put into storage), a missing base input
 * without a pattern is reported ("Missing: 6× Cobblestone").
 *
 * @param item       the requested item
 * @param amount     how many of it are made
 * @param steps      the crafts in order (intermediates first)
 * @param takes      what is taken from the stock
 * @param missing    every base input that is missing, empty if the plan can be done
 * @param operations crafting operations (one craft each, intermediates count)
 */
public record CraftPlan(Item item, int amount, List<Step> steps, Map<Item, Integer> takes, List<Request.Missing> missing, int operations) {

    /** Patterns deeper than this are not followed (guards against long or cyclic chains). */
    private static final int MAX_DEPTH = 8;

    /**
     * One pattern crafted a number of times.
     *
     * @param pattern the pattern
     * @param inputs  the ingredients of one craft
     * @param crafts  how often it is crafted
     */
    public record Step(Pattern pattern, List<Ingredient> inputs, int crafts) {
    }

    /**
     * @param level   the level
     * @param profile the producer's profile (its input filter)
     * @param checked the checked patterns of the producer
     * @param item    the item to make
     * @param amount  how many
     * @param held    what the Craftsperson already holds in its buffer (used first)
     * @param stock   what it can take from its collect inventory and the Clerk storage
     * @return the plan, with {@link #missing()} set if it cannot be done
     */
    public static CraftPlan plan(ServerLevel level, ProducingProfile profile, List<Pattern> checked, Item item, int amount,
            Map<Item, Integer> held, Map<Item, Integer> stock) {
        Planner planner = new Planner(level, profile, checked, held, stock);
        planner.craft(item, amount, 0);
        List<Request.Missing> missing = planner.missing.entrySet().stream()
                .map(entry -> new Request.Missing(entry.getKey(), entry.getValue())).toList();
        int operations = planner.steps.stream().mapToInt(Step::crafts).sum();
        return new CraftPlan(item, amount, List.copyOf(planner.steps), Map.copyOf(planner.takes), missing, operations);
    }

    /**
     * The most of an item that can be made in one round: what the materials allow and the inputs taken fit into the
     * buffer.
     *
     * @param level   the level
     * @param profile the producer's profile
     * @param checked the checked patterns
     * @param item    the item to make
     * @param wanted  how many are wanted at most
     * @param held    what the Craftsperson holds
     * @param stock   what it can take
     * @param room    how many items it can still take into its buffer
     * @return the amount, 0 if not even one can be made
     */
    public static int most(ServerLevel level, ProducingProfile profile, List<Pattern> checked, Item item, int wanted, Map<Item, Integer> held,
            Map<Item, Integer> stock, int room) {
        int low = 0;
        int high = Math.max(0, wanted);
        while (low < high) {
            int middle = (low + high + 1) / 2;
            CraftPlan plan = plan(level, profile, checked, item, middle, held, stock);
            if (plan.missing().isEmpty() && plan.taken() <= room) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /**
     * @return how many items are taken from the stock
     */
    public int taken() {
        return takes.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Resolves the need recursively against a virtual stock. */
    private static final class Planner {

        private final ServerLevel level;
        private final ProducingProfile profile;
        private final List<Pattern> checked;
        /** Items held or crafted and not used yet. */
        private final Map<Item, Integer> pool;
        private final Map<Item, Integer> stock;
        private final Map<Item, Integer> takes = new LinkedHashMap<>();
        private final Map<Item, Integer> missing = new LinkedHashMap<>();
        private final List<Step> steps = new ArrayList<>();
        private final Set<Item> crafting = new HashSet<>();

        Planner(ServerLevel level, ProducingProfile profile, List<Pattern> checked, Map<Item, Integer> held, Map<Item, Integer> stock) {
            this.level = level;
            this.profile = profile;
            this.checked = checked;
            this.pool = new LinkedHashMap<>(held);
            this.stock = new LinkedHashMap<>(stock);
        }

        /** Crafts {@code amount} of an item into the pool (always crafted, never taken). */
        void craft(Item item, int amount, int depth) {
            Optional<Pattern> pattern = checked.stream().filter(found -> found.output() == item).findFirst();
            if (pattern.isEmpty() || depth > MAX_DEPTH || !crafting.add(item)) {
                missing.merge(item, amount, Integer::sum);
                return;
            }
            int crafts = (amount + pattern.get().count() - 1) / pattern.get().count();
            List<Ingredient> inputs = Patterns.inputs(level, profile, pattern.get());
            for (Ingredient input : inputs) {
                obtain(input, crafts, depth);
            }
            crafting.remove(item);
            steps.add(new Step(pattern.get(), inputs, crafts));
            pool.merge(item, crafts * pattern.get().count(), Integer::sum);
        }

        /** Puts {@code amount} items matching an ingredient aside: held first, then the stock, then crafted. */
        private void obtain(Ingredient input, int amount, int depth) {
            int rest = use(pool, input, amount, null);
            rest = use(stock, input, rest, takes);
            if (rest <= 0) {
                return;
            }
            Optional<Pattern> pattern = checked.stream().filter(found -> found.valid() && !crafting.contains(found.output())
                    && input.test(new ItemStack(found.output()))).findFirst();
            if (pattern.isPresent() && depth < MAX_DEPTH) {
                craft(pattern.get().output(), rest, depth + 1);
                rest = use(pool, input, rest, null);
            }
            if (rest > 0) {
                missing.merge(PatternRecipes.representative(input), rest, Integer::sum);
            }
        }

        /** Takes up to {@code amount} matching items out of a store, the biggest stack of a kind first. */
        private static int use(Map<Item, Integer> store, Ingredient input, int amount, Map<Item, Integer> record) {
            int rest = amount;
            List<Map.Entry<Item, Integer>> matching = store.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0 && input.test(new ItemStack(entry.getKey())))
                    .sorted(Comparator.comparingInt((Map.Entry<Item, Integer> entry) -> entry.getValue()).reversed()).toList();
            for (Map.Entry<Item, Integer> entry : matching) {
                if (rest <= 0) {
                    break;
                }
                int used = Math.min(rest, entry.getValue());
                store.put(entry.getKey(), entry.getValue() - used);
                if (record != null) {
                    record.merge(entry.getKey(), used, Integer::sum);
                }
                rest -= used;
            }
            return rest;
        }
    }
}
