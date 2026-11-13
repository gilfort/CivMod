package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The written order on a Delivery Slip (vault {@code Jobs/Deliveryman.md}): take the items its filter lets through out of
 * one {@code source} and bring them to {@code targets}. It is the data component of the slip item; the Warehouse's
 * Delivery Orders tab keeps the filled slips as standing orders.
 *
 * @param id       tells the slip apart from others (its cooldown and round robin position are kept under it)
 * @param source   the one source container, empty while it is not set
 * @param targets  the target containers, in the order the player added them
 * @param blacklist whether the filter is a blacklist (items listed are left out) instead of a whitelist (only those pass)
 * @param filter   the filter items, at most {@value #FILTER_SLOTS}
 * @param variant  which target gets the items
 * @param fillTo   a target is filled up to this many of an item, 0: no limit
 * @param keep     this many of an item stay at the source, 0: none
 * @param status   what the Warehouse found out about the containers (written by it, not by the player)
 */
public record DeliverySlip(UUID id, Optional<BlockPos> source, List<BlockPos> targets, boolean blacklist, List<Item> filter,
        Variant variant, int fillTo, int keep, Status status) {

    /** Slots of the filter. */
    public static final int FILTER_SLOTS = 9;
    /** Largest value of "fill target up to" and "keep at the source". */
    public static final int MAX_LIMIT = 4096;

    /** Which target of a slip the items go to. */
    public enum Variant implements StringRepresentable {
        /** The target nearest to the source first. */
        NEAREST("nearest"),
        /** The target furthest from the source first. */
        FURTHEST("furthest"),
        /** A random target. */
        RANDOM("random"),
        /** One target after the other; a full target is skipped. */
        ROUND_ROBIN("round_robin"),
        /** One target after the other; waits for the next target even when others have space. */
        FORCED_ROUND_ROBIN("forced_round_robin");

        /** Save codec. */
        public static final Codec<Variant> CODEC = StringRepresentable.fromEnum(Variant::values);

        private final String serialized;

        Variant(String serialized) {
            this.serialized = serialized;
        }

        @Override
        public String getSerializedName() {
            return serialized;
        }

        /**
         * @return the position of the variant in the window's cycle, which is also how the menu sends it
         */
        public int index() {
            return List.of(values()).indexOf(this);
        }

        /**
         * @return the variant after this one in the window's cycle
         */
        public Variant next() {
            return values()[(index() + 1) % values().length];
        }
    }

    /** What the Warehouse knows about the containers of a slip. */
    public enum Status implements StringRepresentable {
        /** Nothing is wrong. */
        OK("ok"),
        /** The source is destroyed: the slip is skipped. */
        SOURCE_MISSING("source_missing"),
        /** A target is destroyed: the slip is skipped. */
        TARGET_MISSING("target_missing");

        /** Save codec. */
        public static final Codec<Status> CODEC = StringRepresentable.fromEnum(Status::values);

        private final String serialized;

        Status(String serialized) {
            this.serialized = serialized;
        }

        @Override
        public String getSerializedName() {
            return serialized;
        }
    }

    /** Save codec. */
    public static final Codec<DeliverySlip> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(DeliverySlip::id),
            BlockPos.CODEC.optionalFieldOf("source").forGetter(DeliverySlip::source),
            BlockPos.CODEC.listOf().optionalFieldOf("targets", List.of()).forGetter(DeliverySlip::targets),
            Codec.BOOL.optionalFieldOf("blacklist", false).forGetter(DeliverySlip::blacklist),
            BuiltInRegistries.ITEM.byNameCodec().listOf().optionalFieldOf("filter", List.of()).forGetter(DeliverySlip::filter),
            Variant.CODEC.optionalFieldOf("variant", Variant.NEAREST).forGetter(DeliverySlip::variant),
            Codec.intRange(0, MAX_LIMIT).optionalFieldOf("fill_to", 0).forGetter(DeliverySlip::fillTo),
            Codec.intRange(0, MAX_LIMIT).optionalFieldOf("keep", 0).forGetter(DeliverySlip::keep),
            Status.CODEC.optionalFieldOf("status", Status.OK).forGetter(DeliverySlip::status)
    ).apply(instance, DeliverySlip::new));

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, DeliverySlip> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

    /**
     * @param id        the slip id
     * @param source    the source, if set
     * @param targets   the targets
     * @param blacklist whether the filter is a blacklist
     * @param filter    the filter items
     * @param variant   the delivery variant
     * @param fillTo    fill a target up to this many, 0: no limit
     * @param keep      keep this many at the source, 0: none
     * @param status    what the Warehouse found out
     */
    public DeliverySlip {
        targets = List.copyOf(targets);
        filter = List.copyOf(filter.size() > FILTER_SLOTS ? filter.subList(0, FILTER_SLOTS) : filter);
    }

    /**
     * @return a new slip without source and targets: a whitelist without items, nearest first, no limits
     */
    public static DeliverySlip blank() {
        return new DeliverySlip(UUID.randomUUID(), Optional.empty(), List.of(), false, List.of(), Variant.NEAREST, 0, 0, Status.OK);
    }

    /**
     * @return whether the slip has a source and a target; only such a slip goes into the Delivery Orders tab
     */
    public boolean filled() {
        return source.isPresent() && !targets.isEmpty();
    }

    /**
     * @param stack an item stack
     * @return whether the filter lets it through: a whitelist takes the listed items only (an empty one nothing), a
     *         blacklist everything but the listed items
     */
    public boolean accepts(ItemStack stack) {
        return !stack.isEmpty() && filter.contains(stack.getItem()) != blacklist;
    }

    /**
     * @param pos a block position
     * @return a copy with that source (the target at that position, if any, is dropped: it cannot feed itself)
     */
    public DeliverySlip withSource(BlockPos pos) {
        List<BlockPos> remaining = new ArrayList<>(targets);
        remaining.remove(pos);
        return new DeliverySlip(id, Optional.of(pos.immutable()), remaining, blacklist, filter, variant, fillTo, keep, Status.OK);
    }

    /**
     * Adds a target, or removes it when it is one already; the source cannot be a target.
     *
     * @param pos a container position
     * @return a copy with that target toggled
     */
    public DeliverySlip withTargetToggled(BlockPos pos) {
        if (source.filter(pos::equals).isPresent()) {
            return this;
        }
        List<BlockPos> changed = new ArrayList<>(targets);
        if (!changed.remove(pos)) {
            changed.add(pos.immutable());
        }
        return new DeliverySlip(id, source, changed, blacklist, filter, variant, fillTo, keep, Status.OK);
    }

    /**
     * @param changed the new filter items
     * @return a copy with that filter
     */
    public DeliverySlip withFilter(List<Item> changed) {
        return new DeliverySlip(id, source, targets, blacklist, changed, variant, fillTo, keep, status);
    }

    /**
     * @return a copy with the other filter mode
     */
    public DeliverySlip withBlacklistToggled() {
        return new DeliverySlip(id, source, targets, !blacklist, filter, variant, fillTo, keep, status);
    }

    /**
     * @param changed the new variant
     * @return a copy with that variant
     */
    public DeliverySlip withVariant(Variant changed) {
        return new DeliverySlip(id, source, targets, blacklist, filter, changed, fillTo, keep, status);
    }

    /**
     * @param changed the new limit, clamped to {@code 0} to {@value #MAX_LIMIT}
     * @return a copy that fills a target up to that many
     */
    public DeliverySlip withFillTo(int changed) {
        return new DeliverySlip(id, source, targets, blacklist, filter, variant, Math.clamp(changed, 0, MAX_LIMIT), keep, status);
    }

    /**
     * @param changed the new amount, clamped to {@code 0} to {@value #MAX_LIMIT}
     * @return a copy that keeps that many at the source
     */
    public DeliverySlip withKeep(int changed) {
        return new DeliverySlip(id, source, targets, blacklist, filter, variant, fillTo, Math.clamp(changed, 0, MAX_LIMIT), status);
    }

    /**
     * @param found what the Warehouse found out
     * @return a copy with that status
     */
    public DeliverySlip withStatus(Status found) {
        return new DeliverySlip(id, source, targets, blacklist, filter, variant, fillTo, keep, found);
    }
}
