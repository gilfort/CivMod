package com.gilfort.civmod.logistics;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * A Delivery task of the settlement (vault {@code Jobs/Deliveryman.md}): a pickup – empty the drop-off {@code source}
 * of a Resource Collection Job into the Home Clerk of its JobBlock – or a result delivery ({@link Result}) – carry a
 * producer's result from its drop-off to the requesting Clerk – or an input delivery ({@link Input}) – carry a
 * producer's inputs from a store to its collect inventory. At most one open pickup per source; a Deliveryman that takes a
 * task locks it for the others ({@link Taker}) until it is done, released or the day is over.
 *
 * @param id       the task id
 * @param source   where the items are picked up (a drop-off, the JobBlock itself, the store a result waits in, or the
 *                 store an input delivery takes from)
 * @param jobBlock the JobBlock whose drop-off it is (its Home Clerk is the target of a pickup); the store itself for a
 *                 relayed result; the producer for an input delivery
 * @param created  the game time the task was created (waiting time)
 * @param urgent   whether the drop-off was full ("Drop-off full")
 * @param result   what a result delivery carries, empty for a pickup
 * @param input    what an input delivery carries, empty for the others
 * @param taker    the Deliveryman working on it (not saved: a reload frees it)
 */
public record DeliveryTask(UUID id, BlockPos source, BlockPos jobBlock, long created, boolean urgent, Optional<Result> result,
        Optional<Input> input, Optional<Taker> taker) {

    /**
     * The Deliveryman a task is locked to.
     *
     * @param settler the Deliveryman settler
     * @param name    its name (for the Deliveries tab)
     * @param day     the day it took the task; a lock of an earlier day is over
     */
    public record Taker(UUID settler, String name, long day) {
    }

    /**
     * What a result delivery carries: {@code count} of {@code item} for the Clerk that requested it. It counts as in
     * delivery for that Clerk's stock rules until it arrives there.
     *
     * @param item    the result
     * @param count   how many
     * @param clerk   the requesting Clerk JobBlock
     * @param relayed whether it waits in another store (the producer's Home Clerk) for the requesting Clerk's Warehouse
     * @param carrier the Deliveryman carrying it, empty while it waits at its source (saved: the load is saved too)
     */
    public record Result(Item item, int count, BlockPos clerk, boolean relayed, Optional<UUID> carrier) {

        /** Save codec. */
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Result::item),
                Codec.INT.fieldOf("count").forGetter(Result::count),
                BlockPos.CODEC.fieldOf("clerk").forGetter(Result::clerk),
                Codec.BOOL.optionalFieldOf("relayed", false).forGetter(Result::relayed),
                UUIDUtil.CODEC.optionalFieldOf("carrier").forGetter(Result::carrier)
        ).apply(instance, Result::new));
    }

    /**
     * What an input delivery carries: the inputs of a producer's round that its source store holds, reserved there while
     * a Deliveryman has the task.
     *
     * @param items   the inputs, by item
     * @param to      where they go: the producer's collect inventory
     * @param carrier the Deliveryman carrying them, empty while they wait at the source (saved: the load is saved too)
     */
    public record Input(Map<Item, Integer> items, BlockPos to, Optional<UUID> carrier) {

        /** Save codec. */
        public static final Codec<Input> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.unboundedMap(BuiltInRegistries.ITEM.byNameCodec(), Codec.INT).fieldOf("items").forGetter(Input::items),
                BlockPos.CODEC.fieldOf("to").forGetter(Input::to),
                UUIDUtil.CODEC.optionalFieldOf("carrier").forGetter(Input::carrier)
        ).apply(instance, Input::new));

        /**
         * @param items   the inputs, by item
         * @param to      the producer's collect inventory
         * @param carrier the Deliveryman carrying them
         */
        public Input {
            items = Map.copyOf(items);
        }

        /**
         * @return how many items in all
         */
        public int count() {
            return items.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** Save codec (without the taker; saves before result deliveries have no id). */
    public static final Codec<DeliveryTask> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.optionalFieldOf("id").forGetter(task -> Optional.of(task.id())),
            BlockPos.CODEC.fieldOf("source").forGetter(DeliveryTask::source),
            BlockPos.CODEC.fieldOf("job_block").forGetter(DeliveryTask::jobBlock),
            Codec.LONG.fieldOf("created").forGetter(DeliveryTask::created),
            Codec.BOOL.optionalFieldOf("urgent", false).forGetter(DeliveryTask::urgent),
            Result.CODEC.optionalFieldOf("result").forGetter(DeliveryTask::result),
            Input.CODEC.optionalFieldOf("input").forGetter(DeliveryTask::input)
    ).apply(instance, (id, source, jobBlock, created, urgent, result, input) -> new DeliveryTask(id.orElseGet(UUID::randomUUID), source,
            jobBlock, created, urgent, result, input, Optional.empty())));

    /**
     * A pickup.
     *
     * @param source   the drop-off
     * @param jobBlock the JobBlock whose drop-off it is
     * @param created  the game time the task was created
     * @param urgent   whether the drop-off was full
     * @param taker    the Deliveryman working on it
     */
    public DeliveryTask(BlockPos source, BlockPos jobBlock, long created, boolean urgent, Optional<Taker> taker) {
        this(UUID.randomUUID(), source, jobBlock, created, urgent, Optional.empty(), Optional.empty(), taker);
    }

    /**
     * @return whether it is a pickup (neither a result nor an input delivery)
     */
    public boolean pickup() {
        return result.isEmpty() && input.isEmpty();
    }

    /**
     * @return the Deliveryman carrying a result or inputs, empty while they wait at the source (and for a pickup)
     */
    public Optional<UUID> carrier() {
        return result.flatMap(Result::carrier).or(() -> input.flatMap(Input::carrier));
    }

    /**
     * @param day the day now
     * @return the Deliveryman working on it today, empty if the task is free
     */
    public Optional<Taker> takerOn(long day) {
        return taker.filter(found -> found.day() == day);
    }

    /**
     * @return whether a Deliveryman carries it (a result or input delivery picked up and not yet unloaded)
     */
    public boolean carried() {
        return carrier().isPresent();
    }

    /**
     * @param taker the new Deliveryman, empty to free the task
     * @return a copy with that taker
     */
    public DeliveryTask withTaker(Optional<Taker> taker) {
        return new DeliveryTask(id, source, jobBlock, created, urgent, result, input, taker);
    }

    /**
     * @return an urgent copy
     */
    public DeliveryTask asUrgent() {
        return new DeliveryTask(id, source, jobBlock, created, true, result, input, taker);
    }

    /**
     * @param changed the new result
     * @return a copy carrying that result
     */
    public DeliveryTask withResult(Result changed) {
        return new DeliveryTask(id, source, jobBlock, created, urgent, Optional.of(changed), input, taker);
    }

    /**
     * @param changed the new input
     * @return a copy carrying that input
     */
    public DeliveryTask withInput(Input changed) {
        return new DeliveryTask(id, source, jobBlock, created, urgent, result, Optional.of(changed), taker);
    }

    /**
     * @param store the store a result now waits in
     * @return a copy of a result delivery waiting there, free and carried by nobody
     */
    public DeliveryTask relayedAt(BlockPos store) {
        Result carriedResult = result.orElseThrow();
        return new DeliveryTask(id, store.immutable(), store.immutable(), created, urgent,
                Optional.of(new Result(carriedResult.item(), carriedResult.count(), carriedResult.clerk(), true, Optional.empty())), Optional.empty(),
                Optional.empty());
    }
}
