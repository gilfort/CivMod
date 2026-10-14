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
 * A JobBlock as recorded by its settlement, including its work points and the assigned settler
 * (vault {@code Systems/Scroll of Assignment.md}).
 */
public final class SettlementBlock {

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
            UUIDUtil.CODEC.optionalFieldOf("assignee").forGetter(SettlementBlock::assignee),
            Codec.STRING.optionalFieldOf("assignee_name").forGetter(SettlementBlock::assigneeName),
            Codec.BOOL.optionalFieldOf("collect_leaves", false).forGetter(SettlementBlock::collectLeaves)
    ).apply(instance, SettlementBlock::new));

    private final BlockPos pos;
    private final Job jobType;
    private int level;
    private boolean connected;
    private final List<BlockPos> workPoints = new ArrayList<>();
    private Optional<BlockPos> collect;
    private Optional<BlockPos> dropOff;
    private Optional<UUID> assignee;
    private Optional<String> assigneeName;
    private boolean collectLeaves;

    /**
     * @param pos       the block position
     * @param jobType   the offered job
     * @param level     the JobBlock level
     * @param connected whether the block is connected to the settlement chain
     */
    public SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected) {
        this(pos, jobType, level, connected, List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected, List<BlockPos> workPoints,
            Optional<BlockPos> collect, Optional<BlockPos> dropOff, Optional<UUID> assignee, Optional<String> assigneeName,
            boolean collectLeaves) {
        this.pos = pos.immutable();
        this.jobType = jobType;
        this.level = SettlementRules.clampLevel(level);
        this.connected = connected;
        this.workPoints.addAll(workPoints);
        this.collect = collect;
        this.dropOff = dropOff;
        this.assignee = assignee;
        this.assigneeName = assigneeName;
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
     * @return the settler working this JobBlock
     */
    public Optional<UUID> assignee() {
        return assignee;
    }

    /**
     * @return the name of the settler working this JobBlock (for display while it is unloaded)
     */
    public Optional<String> assigneeName() {
        return assigneeName;
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

    void setAssignee(Optional<UUID> assignee, Optional<String> name) {
        this.assignee = assignee;
        this.assigneeName = name;
    }
}
