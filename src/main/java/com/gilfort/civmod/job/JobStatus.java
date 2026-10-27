package com.gilfort.civmod.job;

import java.util.Optional;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Status of a settler's job, shown in the settler GUI.
 */
public enum JobStatus {

    /** Outside work hours or waiting for the first decision. */
    IDLE("idle"),
    /** Walking to or working at the work place. */
    WORKING("working"),
    /** A Clerk standing at its counter. */
    SERVING("serving"),
    /** Fetching a tool. */
    FETCHING_TOOL("fetching_tool"),
    /** Delivering the buffer. */
    DELIVERING("delivering"),
    /** On a fatigue break. */
    PAUSING("pausing"),
    /** No usable tool in the belt or the collect inventory. */
    NO_TOOL("no_tool"),
    /** No (reachable) work place. */
    NO_WORK_PLACE("no_work_place"),
    /** Every work place of the job is occupied by another settler today. */
    NO_FREE_WORK_PLACE("no_free_work_place"),
    /** The drop-off inventory is full. */
    DROP_OFF_FULL("drop_off_full"),
    /** The scan found nothing this job can produce. */
    NO_RESOURCES("no_resources"),
    /** Every main resource the scan found is excluded at the JobBlock; checked again every 30 s or when one is included. */
    ALL_EXCLUDED("all_excluded"),
    /** Every main resource the scan found is at its Max at the Home Clerk; checked again every 30 s. */
    MAX_REACHED("max_reached"),
    /** Every main resource the scan found is excluded or at its Max (some of each); checked again every 30 s. */
    NOTHING_TO_PRODUCE("nothing_to_produce"),
    /** No marked mission at or below the settler's skill. */
    NO_MISSION("no_mission"),
    /** A mission is reserved; the settler leaves at the start of the next workday. */
    WAITING_DEPARTURE("waiting_departure"),
    /** Away on a mission. */
    ON_MISSION("on_mission"),
    /** Back from a Villager mission, leading the Villagers to the Mission Board. */
    BRINGING_VILLAGERS("bringing_villagers"),
    /** A producer walking to or crafting at its station. */
    CRAFTING("crafting"),
    /** A producer fetching the inputs of a request. */
    FETCHING_INPUTS("fetching_inputs"),
    /** A producer finds no request it has a checked pattern for. */
    NO_REQUEST("no_request"),
    /** A producer lacks inputs for every request it tried. */
    MISSING_INPUTS("missing_inputs"),
    /** A producer burning fuel finds none of its listed fuels in stock ("Missing: Fuel"). */
    MISSING_FUEL("missing_fuel"),
    /** A Producing Job on "Delivery: Deliveryman" whose inputs are on their way; it works its next priority meanwhile. */
    WAITING_FOR_INPUTS("waiting_for_inputs"),
    /** A Deliveryman walking to or emptying a drop-off ("Collecting – <block>"). */
    COLLECTING("collecting"),
    /** A Deliveryman carrying its load into a store ("Delivering to <Clerk>"). */
    UNLOADING("unloading"),
    /** The store a Deliveryman delivers to is full; it keeps the rest and retries after 30 s. */
    STORAGE_FULL("storage_full"),
    /** A Deliveryman finds no free pickup of its Warehouse. */
    NO_PICKUPS("no_pickups"),
    /**
     * The Job's profile reports its own reason ({@link com.gilfort.civmod.api.BlockingReason}); the status text is the
     * reason. Checked again every 30 s.
     */
    BLOCKED("blocked");

    /** Network codec. */
    public static final StreamCodec<ByteBuf, JobStatus> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], JobStatus::ordinal);

    private final String id;

    JobStatus(String id) {
        this.id = id;
    }

    /**
     * @return whether the job can currently not work (the settler switches to its next priority)
     */
    public boolean isBlocked() {
        // NO_REQUEST and MISSING_INPUTS do not block: the next decision tries again (vault Jobs/Craftsperson.md).
        return this == NO_TOOL || this == NO_WORK_PLACE || this == NO_FREE_WORK_PLACE || this == DROP_OFF_FULL || this == NO_RESOURCES || this == ALL_EXCLUDED || this == MAX_REACHED
                || this == NOTHING_TO_PRODUCE || this == NO_MISSION || this == STORAGE_FULL || this == BLOCKED;
    }

    /**
     * @return whether the job's buffer waits for room where it goes (not delivered after work)
     */
    public boolean waitsForRoom() {
        return this == DROP_OFF_FULL || this == STORAGE_FULL;
    }

    /**
     * @return the translated status
     */
    public Component displayName() {
        return Component.translatable("civmod.job.status." + id);
    }

    /**
     * @param subject what the status is about (e.g. the block a Deliveryman collects at), if any
     * @return the translated status, naming the subject
     */
    public Component displayName(Optional<Component> subject) {
        return subject.<Component>map(found -> Component.translatable("civmod.job.status." + id + ".of", found)).orElseGet(this::displayName);
    }
}
