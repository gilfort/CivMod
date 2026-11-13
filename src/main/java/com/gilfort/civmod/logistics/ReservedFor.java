package com.gilfort.civmod.logistics;

import java.util.Locale;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * Who an amount of stock in a Clerk is held for (vault {@code Systems/Sub-Requests.md}, {@code Systems/Needs.md}). The
 * result of a sub-request waits for its parent request, a tool made for a settler waits for that settler, and a tool on
 * its way to a collect inventory waits for its Delivery task.
 *
 * @param kind what kind of holder it is
 * @param id   the id of that holder (a request id, a settler UUID or a Delivery task id)
 */
public record ReservedFor(Kind kind, UUID id) {

    /** The kinds of holders; every kind says in {@code Holds} when its hold ends. */
    public enum Kind {
        /** A request: the hold ends once the request is gone. */
        REQUEST,
        /** A settler: the hold ends when the tool is handed over or the settler leaves the settlement. */
        SETTLER,
        /** A Delivery task: the hold ends once the task is done or a Deliveryman took it. */
        DELIVERY
    }

    private static final Codec<Kind> KIND_CODEC = Codec.STRING.comapFlatMap(name -> {
        try {
            return DataResult.success(Kind.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return DataResult.error(() -> "Unknown holder kind " + name);
        }
    }, kind -> kind.name().toLowerCase(Locale.ROOT));

    /** Save codec. */
    public static final Codec<ReservedFor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            KIND_CODEC.fieldOf("kind").forGetter(ReservedFor::kind),
            UUIDUtil.CODEC.fieldOf("id").forGetter(ReservedFor::id)
    ).apply(instance, ReservedFor::new));

    /**
     * @param request a request id
     * @return the holder "this request"
     */
    public static ReservedFor request(UUID request) {
        return new ReservedFor(Kind.REQUEST, request);
    }

    /**
     * @param settler a settler UUID
     * @return the holder "this settler"
     */
    public static ReservedFor settler(UUID settler) {
        return new ReservedFor(Kind.SETTLER, settler);
    }

    /**
     * @param task a Delivery task id
     * @return the holder "this Delivery task"
     */
    public static ReservedFor delivery(UUID task) {
        return new ReservedFor(Kind.DELIVERY, task);
    }
}
