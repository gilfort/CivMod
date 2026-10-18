package com.gilfort.civmod.logistics;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;

/**
 * A Clerk's stock rule (vault {@code Jobs/Clerk.md}): one rule per item with the optional fields Min, Max and Order
 * ({@value #NOT_SET} = not set; at least one of Min / Max is set, Min ≤ Max, Order only with Min). Below Min the rule
 * opens a request up to its {@linkplain #target() target stock}; at Max collecting jobs stop producing the item and the
 * stock above it is surplus for the Warehouse. Min = Max is "exact", both set a band.
 *
 * @param slot     the rule's place in the Stock tab (rows of 9; 9 rules per JobBlock level are active)
 * @param item     the item (a specific item, no tags)
 * @param min      below this stock a request opens ({@value #NOT_SET}: never a request)
 * @param max      the cap ({@value #NOT_SET}: none)
 * @param order    how much a request orders on top of Min ({@value #NOT_SET}: up to Max, else up to Min)
 * @param priority the priority ({@value #MIN_PRIORITY}–{@value #MAX_PRIORITY}, higher first)
 */
public record StockRule(int slot, Item item, int min, int max, int order, int priority) {

    /** Rules per JobBlock level (one row). */
    public static final int RULES_PER_LEVEL = 9;
    /** Lowest priority. */
    public static final int MIN_PRIORITY = -100;
    /** Highest priority. */
    public static final int MAX_PRIORITY = 100;
    /** A field that is not set ("—"). */
    public static final int NOT_SET = 0;
    /** Largest amount of a field (a double chest of 64-stacks). */
    public static final int MAX_AMOUNT = 3456;

    /** Save codec; saves before Min / Max / Order have a Minimum rule's {@code amount}, loaded as Min. */
    public static final Codec<StockRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("slot").forGetter(StockRule::slot),
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(StockRule::item),
            Codec.INT.optionalFieldOf("min", NOT_SET).forGetter(StockRule::min),
            Codec.INT.optionalFieldOf("max", NOT_SET).forGetter(StockRule::max),
            Codec.INT.optionalFieldOf("order", NOT_SET).forGetter(StockRule::order),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(StockRule::priority),
            Codec.INT.optionalFieldOf("amount").forGetter(rule -> Optional.empty())
    ).apply(instance, (slot, item, min, max, order, priority, amount) ->
            new StockRule(slot, item, min == NOT_SET && max == NOT_SET ? amount.orElse(NOT_SET) : min, max, order, priority)));

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, StockRule> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StockRule::slot,
            ByteBufCodecs.registry(Registries.ITEM), StockRule::item,
            ByteBufCodecs.VAR_INT, StockRule::min,
            ByteBufCodecs.VAR_INT, StockRule::max,
            ByteBufCodecs.VAR_INT, StockRule::order,
            ByteBufCodecs.VAR_INT, StockRule::priority,
            StockRule::new);

    /**
     * Fields out of range are clamped; a Max below Min is raised to Min; Order without Min is dropped.
     *
     * @param slot     the rule's place
     * @param item     the item
     * @param min      Min, {@value #NOT_SET} if not set
     * @param max      Max, {@value #NOT_SET} if not set
     * @param order    Order, {@value #NOT_SET} if not set
     * @param priority the priority
     */
    public StockRule {
        min = Math.clamp(min, NOT_SET, MAX_AMOUNT);
        max = Math.clamp(max, NOT_SET, MAX_AMOUNT);
        if (min != NOT_SET && max != NOT_SET && max < min) {
            max = min;
        }
        order = min == NOT_SET ? NOT_SET : Math.clamp(order, NOT_SET, MAX_AMOUNT);
        priority = Math.clamp(priority, MIN_PRIORITY, MAX_PRIORITY);
    }

    /**
     * @param slot     the rule's place
     * @param item     the item
     * @param min      Min
     * @param priority the priority
     * @return a rule with Min only ("keep at least")
     */
    public static StockRule minimum(int slot, Item item, int min, int priority) {
        return new StockRule(slot, item, min, NOT_SET, NOT_SET, priority);
    }

    /**
     * @param value the new Min ({@value #NOT_SET}: not set)
     * @return a copy with that Min, clamped to a set Max (Min cannot be raised above it)
     */
    public StockRule withMin(int value) {
        int clamped = Math.clamp(value, NOT_SET, MAX_AMOUNT);
        return new StockRule(slot, item, hasMax() ? Math.min(clamped, max) : clamped, max, order, priority);
    }

    /**
     * @param value the new Max ({@value #NOT_SET}: not set)
     * @return a copy with that Max, clamped to Min (a set Max cannot be lowered below it)
     */
    public StockRule withMax(int value) {
        int clamped = Math.clamp(value, NOT_SET, MAX_AMOUNT);
        return new StockRule(slot, item, min, clamped == NOT_SET ? NOT_SET : Math.max(clamped, min), order, priority);
    }

    /**
     * @param value the new Order ({@value #NOT_SET}: not set; dropped without Min)
     * @return a copy with that Order
     */
    public StockRule withOrder(int value) {
        return new StockRule(slot, item, min, max, value, priority);
    }

    /**
     * @param value the new priority
     * @return a copy with that priority
     */
    public StockRule withPriority(int value) {
        return new StockRule(slot, item, min, max, order, value);
    }

    /**
     * @param value the new item
     * @return a copy for that item, with the same fields
     */
    public StockRule withItem(Item value) {
        return new StockRule(slot, value, min, max, order, priority);
    }

    /**
     * @return whether Min is set (the rule creates requests)
     */
    public boolean hasMin() {
        return min != NOT_SET;
    }

    /**
     * @return whether Max is set (the cap of collecting jobs, surplus above it)
     */
    public boolean hasMax() {
        return max != NOT_SET;
    }

    /**
     * @return whether the rule is complete: at least one of Min / Max is set
     */
    public boolean valid() {
        return hasMin() || hasMax();
    }

    /**
     * @return the target stock an open request fills up to: min(Min + Order, Max); without Order Max, without Max Min
     *         ({@value #NOT_SET} without Min)
     */
    public int target() {
        if (!hasMin()) {
            return NOT_SET;
        }
        if (order == NOT_SET) {
            return hasMax() ? max : min;
        }
        return hasMax() ? Math.min(min + order, max) : min + order;
    }

    /**
     * @param level the Clerk JobBlock level
     * @return how many rule slots are active at that level (9 / 18 / 27 / 36 / 45)
     */
    public static int slots(int level) {
        return RULES_PER_LEVEL * level;
    }

    /**
     * @param level the Clerk JobBlock level
     * @return whether this rule is active at that level (extra rules after a downgrade are kept but inactive)
     */
    public boolean activeAt(int level) {
        return slot >= 0 && slot < slots(level);
    }
}
