package com.gilfort.civmod.job;

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
    /** The drop-off inventory is full. */
    DROP_OFF_FULL("drop_off_full"),
    /** The scan found nothing this job can produce. */
    NO_RESOURCES("no_resources");

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
        return this == NO_TOOL || this == NO_WORK_PLACE || this == DROP_OFF_FULL || this == NO_RESOURCES;
    }

    /**
     * @return the translated status
     */
    public Component displayName() {
        return Component.translatable("civmod.job.status." + id);
    }
}
