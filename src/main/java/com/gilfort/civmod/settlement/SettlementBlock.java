package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.job.Job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * A JobBlock as recorded by its settlement, including its work points and the assigned settlers
 * (vault {@code Systems/Scroll of Assignment.md}); every JobBlock holds one settler, the Mission Board several
 * ({@link SettlementRules#capacity}).
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
            Codec.BOOL.optionalFieldOf("collect_leaves", false).forGetter(SettlementBlock::collectLeaves)
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

    /**
     * @param pos       the block position
     * @param jobType   the offered job
     * @param level     the JobBlock level
     * @param connected whether the block is connected to the settlement chain
     */
    public SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected) {
        this(pos, jobType, level, connected, List.of(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), false);
    }

    private SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected, List<BlockPos> workPoints,
            Optional<BlockPos> collect, Optional<BlockPos> dropOff, List<Assignee> assignees, Optional<UUID> legacyAssignee,
            Optional<String> legacyAssigneeName, boolean collectLeaves) {
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
