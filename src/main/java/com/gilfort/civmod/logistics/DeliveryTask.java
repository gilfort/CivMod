package com.gilfort.civmod.logistics;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;

/**
 * A Delivery task of the settlement (vault {@code Jobs/Deliveryman.md}): a pickup – empty the drop-off {@code source}
 * of a Resource Collection Job into the Home Clerk of its JobBlock. At most one open task per source; a Deliveryman
 * that takes it locks it for the others ({@link Taker}) until it is done, released or the day is over.
 *
 * @param source   the drop-off (a container or the JobBlock itself)
 * @param jobBlock the JobBlock whose drop-off it is (its Home Clerk is the target)
 * @param created  the game time the task was created (waiting time)
 * @param urgent   whether the drop-off was full ("Drop-off full")
 * @param taker    the Deliveryman working on it (not saved: a reload frees it)
 */
public record DeliveryTask(BlockPos source, BlockPos jobBlock, long created, boolean urgent, Optional<Taker> taker) {

    /**
     * The Deliveryman a task is locked to.
     *
     * @param settler the Deliveryman settler
     * @param name    its name (for the Deliveries tab)
     * @param day     the day it took the task; a lock of an earlier day is over
     */
    public record Taker(UUID settler, String name, long day) {
    }

    /** Save codec (without the taker). */
    public static final Codec<DeliveryTask> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("source").forGetter(DeliveryTask::source),
            BlockPos.CODEC.fieldOf("job_block").forGetter(DeliveryTask::jobBlock),
            Codec.LONG.fieldOf("created").forGetter(DeliveryTask::created),
            Codec.BOOL.optionalFieldOf("urgent", false).forGetter(DeliveryTask::urgent)
    ).apply(instance, (source, jobBlock, created, urgent) -> new DeliveryTask(source, jobBlock, created, urgent, Optional.empty())));

    /**
     * @param day the day now
     * @return the Deliveryman working on it today, empty if the task is free
     */
    public Optional<Taker> takerOn(long day) {
        return taker.filter(found -> found.day() == day);
    }

    /**
     * @param taker the new Deliveryman, empty to free the task
     * @return a copy with that taker
     */
    public DeliveryTask withTaker(Optional<Taker> taker) {
        return new DeliveryTask(source, jobBlock, created, urgent, taker);
    }

    /**
     * @return an urgent copy
     */
    public DeliveryTask asUrgent() {
        return new DeliveryTask(source, jobBlock, created, true, taker);
    }
}
