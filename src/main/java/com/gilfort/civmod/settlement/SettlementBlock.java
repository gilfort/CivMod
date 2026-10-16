package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.mission.MissionOffer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;

/**
 * A JobBlock as recorded by its settlement, including its work points and the assigned settlers
 * (vault {@code Systems/Scroll of Assignment.md}); every JobBlock holds one settler, the Mission Board several
 * ({@link SettlementRules#capacity}) and keeps its daily {@link MissionOffer}.
 */
public final class SettlementBlock {

    /**
     * A settler working a JobBlock.
     *
     * @param id   the settler UUID
     * @param name the settler name (for display while it is unloaded)
     */
    public record Assignee(UUID id, String name) {

        /** Save codec. */
        public static final Codec<Assignee> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Assignee::id),
                Codec.STRING.fieldOf("name").forGetter(Assignee::name)
        ).apply(instance, Assignee::new));
    }

    /**
     * A Clerk stand spot: the block the Clerk stands on and the direction it faces (vault {@code Jobs/Clerk.md}).
     *
     * @param pos    the block the Clerk stands on
     * @param facing the direction the Clerk faces
     */
    public record StandSpot(BlockPos pos, Direction facing) {

        /** Save codec. */
        public static final Codec<StandSpot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(StandSpot::pos),
                Direction.CODEC.fieldOf("facing").forGetter(StandSpot::facing)
        ).apply(instance, StandSpot::new));

        /** Network codec. */
        public static final StreamCodec<ByteBuf, StandSpot> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, StandSpot::pos,
                Direction.STREAM_CODEC, StandSpot::facing,
                StandSpot::new);
    }

    /**
     * The stand spot a Clerk uses in one half of a day.
     *
     * @param period the half day it was picked for ({@code day * 2}, {@code + 1} from 12:00)
     * @param index  the index into the stand spots
     */
    public record SpotOfDay(long period, int index) {

        /** No spot picked yet. */
        public static final SpotOfDay NONE = new SpotOfDay(Long.MIN_VALUE, -1);

        /** Save codec. */
        public static final Codec<SpotOfDay> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("period").forGetter(SpotOfDay::period),
                Codec.INT.fieldOf("index").forGetter(SpotOfDay::index)
        ).apply(instance, SpotOfDay::new));
    }

    /** Maximum number of work POIs per JobBlock. */
    public static final int MAX_WORK_POINTS = 8;

    /** Save codec. */
    public static final Codec<SettlementBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(SettlementBlock::pos),
            Job.CODEC.fieldOf("job").forGetter(SettlementBlock::jobType),
            Codec.INT.fieldOf("level").forGetter(SettlementBlock::level),
            Codec.BOOL.fieldOf("connected").forGetter(SettlementBlock::connected),
            BlockPos.CODEC.listOf().optionalFieldOf("work", List.of()).forGetter(SettlementBlock::workPoints),
            BlockPos.CODEC.optionalFieldOf("collect").forGetter(SettlementBlock::collect),
            BlockPos.CODEC.optionalFieldOf("drop_off").forGetter(SettlementBlock::dropOff),
            Assignee.CODEC.listOf().optionalFieldOf("assignees", List.of()).forGetter(SettlementBlock::assignees),
            // Saves before the Mission Board stored one assignee; read only.
            UUIDUtil.CODEC.optionalFieldOf("assignee").forGetter(block -> Optional.empty()),
            Codec.STRING.optionalFieldOf("assignee_name").forGetter(block -> Optional.empty()),
            Codec.BOOL.optionalFieldOf("collect_leaves", false).forGetter(SettlementBlock::collectLeaves),
            MissionOffer.CODEC.optionalFieldOf("mission_offer", MissionOffer.EMPTY).forGetter(SettlementBlock::missionOffer),
            BlockPos.CODEC.listOf().optionalFieldOf("storage", List.of()).forGetter(SettlementBlock::storage),
            StandSpot.CODEC.listOf().optionalFieldOf("stand_spots", List.of()).forGetter(SettlementBlock::standSpots),
            SpotOfDay.CODEC.optionalFieldOf("spot_of_day", SpotOfDay.NONE).forGetter(SettlementBlock::spotOfDay)
    ).apply(instance, SettlementBlock::new));

    private final BlockPos pos;
    private final Job jobType;
    private int level;
    private boolean connected;
    private final List<BlockPos> workPoints = new ArrayList<>();
    private Optional<BlockPos> collect;
    private Optional<BlockPos> dropOff;
    private final List<Assignee> assignees = new ArrayList<>();
    private boolean collectLeaves;
    private MissionOffer missionOffer;
    private final List<BlockPos> storage = new ArrayList<>();
    private final List<StandSpot> standSpots = new ArrayList<>();
    private SpotOfDay spotOfDay;

    /**
     * @param pos       the block position
     * @param jobType   the offered job
     * @param level     the JobBlock level
     * @param connected whether the block is connected to the settlement chain
     */
    public SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected) {
        this(pos, jobType, level, connected, List.of(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), false,
                MissionOffer.EMPTY, List.of(), List.of(), SpotOfDay.NONE);
    }

    private SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected, List<BlockPos> workPoints,
            Optional<BlockPos> collect, Optional<BlockPos> dropOff, List<Assignee> assignees, Optional<UUID> legacyAssignee,
            Optional<String> legacyAssigneeName, boolean collectLeaves, MissionOffer missionOffer, List<BlockPos> storage,
            List<StandSpot> standSpots, SpotOfDay spotOfDay) {
        this.pos = pos.immutable();
        this.jobType = jobType;
        this.level = SettlementRules.clampLevel(level);
        this.connected = connected;
        this.workPoints.addAll(workPoints);
        this.collect = collect;
        this.dropOff = dropOff;
        this.assignees.addAll(assignees);
        legacyAssignee.filter(id -> assignees.isEmpty()).ifPresent(id -> this.assignees.add(new Assignee(id, legacyAssigneeName.orElse("?"))));
        this.collectLeaves = collectLeaves;
        this.missionOffer = missionOffer;
        this.storage.addAll(storage);
        this.standSpots.addAll(standSpots);
        this.spotOfDay = spotOfDay;
    }

    /**
     * @return the containers linked as Clerk storage, in linking order (read-only; Clerk only, else always empty)
     */
    public List<BlockPos> storage() {
        return Collections.unmodifiableList(storage);
    }

    /**
     * @return the Clerk stand spots, in setting order (read-only; Clerk only, else always empty)
     */
    public List<StandSpot> standSpots() {
        return Collections.unmodifiableList(standSpots);
    }

    /**
     * @return the stand spot the Clerk uses in the current half day
     */
    public SpotOfDay spotOfDay() {
        return spotOfDay;
    }

    List<BlockPos> mutableStorage() {
        return storage;
    }

    List<StandSpot> mutableStandSpots() {
        return standSpots;
    }

    void setSpotOfDay(SpotOfDay spotOfDay) {
        this.spotOfDay = spotOfDay;
    }

    /**
     * @return the daily mission offer (Mission Board only, else always empty)
     */
    public MissionOffer missionOffer() {
        return missionOffer;
    }

    void setMissionOffer(MissionOffer missionOffer) {
        this.missionOffer = missionOffer;
    }

    /**
     * @return whether a Lumberjack also collects leaves (with shears)
     */
    public boolean collectLeaves() {
        return collectLeaves;
    }

    void setCollectLeaves(boolean collectLeaves) {
        this.collectLeaves = collectLeaves;
    }

    /**
     * @return the block position
     */
    public BlockPos pos() {
        return pos;
    }

    /**
     * @return the offered job
     */
    public Job jobType() {
        return jobType;
    }

    /**
     * @return the JobBlock level
     */
    public int level() {
        return level;
    }

    /**
     * @return whether the block is connected to the settlement chain
     */
    public boolean connected() {
        return connected;
    }

    /**
     * @return the work POIs; the first one is the primary POI (read-only)
     */
    public List<BlockPos> workPoints() {
        return Collections.unmodifiableList(workPoints);
    }

    /**
     * @return the collect inventory (tool supply), empty = JobBlock inventory
     */
    public Optional<BlockPos> collect() {
        return collect;
    }

    /**
     * @return the drop-off inventory, empty = JobBlock inventory
     */
    public Optional<BlockPos> dropOff() {
        return dropOff;
    }

    /**
     * @return the settlers working this JobBlock, in assignment order (read-only)
     */
    public List<Assignee> assignees() {
        return Collections.unmodifiableList(assignees);
    }

    /**
     * @return how many settlers this JobBlock holds at its level
     */
    public int capacity() {
        return SettlementRules.capacity(jobType, level);
    }

    /**
     * @param settler a settler UUID
     * @return whether that settler works this JobBlock
     */
    public boolean isAssignee(UUID settler) {
        return assignees.stream().anyMatch(assignee -> assignee.id().equals(settler));
    }

    void setLevel(int level) {
        this.level = SettlementRules.clampLevel(level);
    }

    void setConnected(boolean connected) {
        this.connected = connected;
    }

    List<BlockPos> mutableWorkPoints() {
        return workPoints;
    }

    void setCollect(Optional<BlockPos> collect) {
        this.collect = collect.map(BlockPos::immutable);
    }

    void setDropOff(Optional<BlockPos> dropOff) {
        this.dropOff = dropOff.map(BlockPos::immutable);
    }

    List<Assignee> mutableAssignees() {
        return assignees;
    }
}
