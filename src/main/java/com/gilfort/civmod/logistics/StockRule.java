package com.gilfort.civmod.logistics;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;

/**
 * A Clerk's stock rule (vault {@code Jobs/Clerk.md}): "keep at least {@code amount} of {@code item}". While its Clerk
 * is on shift a rule below its amount posts one request for the missing amount, with the rule's priority.
 *
 * @param slot     the rule's place in the Stock tab (rows of 9; 9 rules per JobBlock level are active)
 * @param item     the item (a specific item, no tags)
 * @param amount   the amount to keep ({@value #MIN_AMOUNT}–{@value #MAX_AMOUNT})
 * @param priority the priority ({@value #MIN_PRIORITY}–{@value #MAX_PRIORITY}, higher first)
 * @param mode     the rule mode; only {@link Mode#MINIMUM} exists so far
 */
public record StockRule(int slot, Item item, int amount, int priority, Mode mode) {

    /** Rules per JobBlock level (one row). */
    public static final int RULES_PER_LEVEL = 9;
    /** Lowest priority. */
    public static final int MIN_PRIORITY = -100;
    /** Highest priority. */
    public static final int MAX_PRIORITY = 100;
    /** Smallest amount. */
    public static final int MIN_AMOUNT = 1;
    /** Largest amount (a double chest of 64-stacks). */
    public static final int MAX_AMOUNT = 3456;

    /**
     * How a rule compares the stock with its amount. Exact and Maximum come with the Deliveryman.
     */
    public enum Mode implements StringRepresentable {
        /** Keep at least the amount: below it, request the missing amount (no hysteresis). */
        MINIMUM("minimum");

        /** Save codec. */
        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
        /** Network codec. */
        public static final StreamCodec<ByteBuf, Mode> STREAM_CODEC = ByteBufCodecs.idMapper(index -> values()[index], Mode::ordinal);

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** Save codec. */
    public static final Codec<StockRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("slot").forGetter(StockRule::slot),
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(StockRule::item),
            Codec.INT.fieldOf("amount").forGetter(StockRule::amount),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(StockRule::priority),
            Mode.CODEC.optionalFieldOf("mode", Mode.MINIMUM).forGetter(StockRule::mode)
    ).apply(instance, StockRule::new));

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, StockRule> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StockRule::slot,
            ByteBufCodecs.registry(Registries.ITEM), StockRule::item,
            ByteBufCodecs.VAR_INT, StockRule::amount,
            ByteBufCodecs.VAR_INT, StockRule::priority,
            Mode.STREAM_CODEC, StockRule::mode,
            StockRule::new);

    /**
     * @param slot     the rule's place
     * @param item     the item
     * @param amount   the amount to keep
     * @param priority the priority
     * @param mode     the rule mode
     */
    public StockRule {
        amount = Math.clamp(amount, MIN_AMOUNT, MAX_AMOUNT);
        priority = Math.clamp(priority, MIN_PRIORITY, MAX_PRIORITY);
    }

    /**
     * @param slot     the rule's place
     * @param item     the item
     * @param amount   the amount to keep
     * @param priority the priority
     * @return a "keep at least" rule
     */
    public static StockRule minimum(int slot, Item item, int amount, int priority) {
        return new StockRule(slot, item, amount, priority, Mode.MINIMUM);
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
