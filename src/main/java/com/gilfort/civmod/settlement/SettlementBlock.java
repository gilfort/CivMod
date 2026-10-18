package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.mission.MissionOffer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;

/**
 * A JobBlock as recorded by its settlement, including its work points and the assigned settlers with their Job
 * (vault {@code Systems/Scroll of Assignment.md}); places per Job in {@link JobPlaces}. The Mission Board keeps its
 * daily {@link MissionOffer}.
 */
public final class SettlementBlock {

    /**
     * A settler working a JobBlock.
     *
     * @param id   the settler UUID
     * @param name the settler name (for display while it is unloaded)
     * @param job  the Job it holds at this block ({@link JobPlaces})
     */
    public record Assignee(UUID id, String name, Job job) {

        /** Network codec. */
        public static final StreamCodec<ByteBuf, Assignee> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Assignee::id,
                net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8, Assignee::name,
                Job.STREAM_CODEC, Assignee::job,
                Assignee::new);
    }

    /**
     * An assignee as saved; saves before JobBlocks offered several Jobs have no Job (read as the block's Job).
     *
     * @param id   the settler UUID
     * @param name the settler name
     * @param job  the Job it holds at this block
     */
    private record SavedAssignee(UUID id, String name, Optional<Job> job) {

        static final Codec<SavedAssignee> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(SavedAssignee::id),
                Codec.STRING.fieldOf("name").forGetter(SavedAssignee::name),
                Job.CODEC.optionalFieldOf("job").forGetter(SavedAssignee::job)
        ).apply(instance, SavedAssignee::new));

        static SavedAssignee of(Assignee assignee) {
            return new SavedAssignee(assignee.id(), assignee.name(), Optional.of(assignee.job()));
        }
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

    /**
     * The Clerk's fields, grouped for the save codec (saved flat, next to the other fields).
     *
     * @param storage    the linked containers
     * @param standSpots the stand spots
     * @param spotOfDay  the stand spot of the current half day
     * @param stockRules the stock rules
     * @param served     the Served Clerks of a Warehouse, in linking order
     */
    private record ClerkFields(List<BlockPos> storage, List<StandSpot> standSpots, SpotOfDay spotOfDay, List<StockRule> stockRules,
            List<BlockPos> served) {

        static final MapCodec<ClerkFields> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BlockPos.CODEC.listOf().optionalFieldOf("storage", List.of()).forGetter(ClerkFields::storage),
                StandSpot.CODEC.listOf().optionalFieldOf("stand_spots", List.of()).forGetter(ClerkFields::standSpots),
                SpotOfDay.CODEC.optionalFieldOf("spot_of_day", SpotOfDay.NONE).forGetter(ClerkFields::spotOfDay),
                StockRule.CODEC.listOf().optionalFieldOf("stock_rules", List.of()).forGetter(ClerkFields::stockRules),
                BlockPos.CODEC.listOf().optionalFieldOf("served_clerks", List.of()).forGetter(ClerkFields::served)
        ).apply(instance, ClerkFields::new));

        static final ClerkFields NONE = new ClerkFields(List.of(), List.of(), SpotOfDay.NONE, List.of(), List.of());
    }

    /**
     * Checkmarks removed because the checkmark limit dropped (vault {@code Jobs/Craftsperson.md}), shown in the GUI.
     *
     * @param removed how many checkmarks were removed
     * @param limit   the new limit
     */
    public record Trim(int removed, int limit) {

        /** Save codec. */
        public static final Codec<Trim> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("removed").forGetter(Trim::removed),
                Codec.INT.fieldOf("limit").forGetter(Trim::limit)
        ).apply(instance, Trim::new));

        /** Network codec. */
        public static final StreamCodec<ByteBuf, Trim> STREAM_CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.VAR_INT, Trim::removed,
                net.minecraft.network.codec.ByteBufCodecs.VAR_INT, Trim::limit,
                Trim::new);
    }

    /**
     * The Craftsperson's fields, grouped for the save codec (saved flat, next to the other fields).
     *
     * @param patterns   the pattern library
     * @param checkLimit the checkmark limit of the settler last seen working it
     * @param trim       the last checkmarks removed by a dropped limit
     */
    private record CraftspersonFields(List<Pattern> patterns, int checkLimit, Optional<Trim> trim) {

        static final MapCodec<CraftspersonFields> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Pattern.CODEC.listOf().optionalFieldOf("patterns", List.of()).forGetter(CraftspersonFields::patterns),
                Codec.INT.optionalFieldOf("check_limit", Patterns.BASE_CHECKMARKS).forGetter(CraftspersonFields::checkLimit),
                Trim.CODEC.optionalFieldOf("check_trim").forGetter(CraftspersonFields::trim)
        ).apply(instance, CraftspersonFields::new));

        static final CraftspersonFields NONE = new CraftspersonFields(List.of(), Patterns.BASE_CHECKMARKS, Optional.empty());
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
            SavedAssignee.CODEC.listOf().optionalFieldOf("assignees", List.of())
                    .forGetter(block -> block.assignees.stream().map(SavedAssignee::of).toList()),
            // Saves before the Mission Board stored one assignee; read only.
            UUIDUtil.CODEC.optionalFieldOf("assignee").forGetter(block -> Optional.empty()),
            Codec.STRING.optionalFieldOf("assignee_name").forGetter(block -> Optional.empty()),
            Codec.BOOL.optionalFieldOf("collect_leaves", false).forGetter(SettlementBlock::collectLeaves),
            MissionOffer.CODEC.optionalFieldOf("mission_offer", MissionOffer.EMPTY).forGetter(SettlementBlock::missionOffer),
            ClerkFields.MAP_CODEC.forGetter(SettlementBlock::clerkFields),
            CraftspersonFields.MAP_CODEC.forGetter(SettlementBlock::craftspersonFields),
            BlockPos.CODEC.optionalFieldOf("home_clerk").forGetter(SettlementBlock::homeClerk)
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
    private final List<StockRule> stockRules = new ArrayList<>();
    private final List<BlockPos> served = new ArrayList<>();
    private Optional<BlockPos> homeClerk;
    private final List<Pattern> patterns = new ArrayList<>();
    private int checkLimit;
    private Optional<Trim> trim;

    /**
     * @param pos       the block position
     * @param jobType   the offered job
     * @param level     the JobBlock level
     * @param connected whether the block is connected to the settlement chain
     */
    public SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected) {
        this(pos, jobType, level, connected, List.of(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), false,
                MissionOffer.EMPTY, ClerkFields.NONE, CraftspersonFields.NONE, Optional.empty());
    }

    private SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected, List<BlockPos> workPoints,
            Optional<BlockPos> collect, Optional<BlockPos> dropOff, List<SavedAssignee> assignees, Optional<UUID> legacyAssignee,
            Optional<String> legacyAssigneeName, boolean collectLeaves, MissionOffer missionOffer, ClerkFields clerk,
            CraftspersonFields craftsperson, Optional<BlockPos> homeClerk) {
        this.pos = pos.immutable();
        this.jobType = jobType;
        this.level = SettlementRules.clampLevel(level);
        this.connected = connected;
        this.workPoints.addAll(workPoints);
        this.collect = collect;
        this.dropOff = dropOff;
        assignees.forEach(saved -> this.assignees.add(new Assignee(saved.id(), saved.name(), saved.job().orElse(jobType))));
        legacyAssignee.filter(id -> assignees.isEmpty())
                .ifPresent(id -> this.assignees.add(new Assignee(id, legacyAssigneeName.orElse("?"), jobType)));
        this.collectLeaves = collectLeaves;
        this.missionOffer = missionOffer;
        this.storage.addAll(clerk.storage());
        this.standSpots.addAll(clerk.standSpots());
        this.spotOfDay = clerk.spotOfDay();
        this.stockRules.addAll(clerk.stockRules());
        this.served.addAll(clerk.served());
        this.homeClerk = homeClerk;
        this.patterns.addAll(craftsperson.patterns());
        this.checkLimit = craftsperson.checkLimit();
        this.trim = craftsperson.trim();
    }

    private ClerkFields clerkFields() {
        return new ClerkFields(List.copyOf(storage), List.copyOf(standSpots), spotOfDay, List.copyOf(stockRules), List.copyOf(served));
    }

    private CraftspersonFields craftspersonFields() {
        return new CraftspersonFields(List.copyOf(patterns), checkLimit, trim);
    }

    /**
     * @return the Craftsperson's pattern library, by slot (read-only; Craftsperson only, else always empty)
     */
    public List<Pattern> patterns() {
        return Collections.unmodifiableList(patterns);
    }

    List<Pattern> mutablePatterns() {
        return patterns;
    }

    /**
     * @return the checkmark limit of the settler last seen working this Craftsperson JobBlock
     */
    public int checkLimit() {
        return checkLimit;
    }

    void setCheckLimit(int checkLimit) {
        this.checkLimit = checkLimit;
    }

    /**
     * @return the checkmarks last removed by a dropped limit, shown until the library is edited
     */
    public Optional<Trim> trim() {
        return trim;
    }

    void setTrim(Optional<Trim> trim) {
        this.trim = trim;
    }

    /**
     * @return the Clerk's stock rules (read-only; Clerk only, else always empty)
     */
    public List<StockRule> stockRules() {
        return Collections.unmodifiableList(stockRules);
    }

    /**
     * @param slot a rule slot
     * @return the stock rule in that slot
     */
    public Optional<StockRule> stockRule(int slot) {
        return stockRules.stream().filter(rule -> rule.slot() == slot).findFirst();
    }

    List<StockRule> mutableStockRules() {
        return stockRules;
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

    /**
     * @return the Served Clerks of this Warehouse, in linking order (read-only; Clerk only, else always empty; the
     *         active ones: {@link Warehouses#activeServed})
     */
    public List<BlockPos> served() {
        return Collections.unmodifiableList(served);
    }

    List<BlockPos> mutableServed() {
        return served;
    }

    /**
     * @return the Home Clerk: the nearest Clerk JobBlock of the settlement (a Clerk JobBlock is its own), empty while
     *         the settlement has none ({@link Warehouses#nearestClerk})
     */
    public Optional<BlockPos> homeClerk() {
        return homeClerk;
    }

    void setHomeClerk(Optional<BlockPos> homeClerk) {
        this.homeClerk = homeClerk;
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
     * @param settler a settler UUID
     * @return that settler's entry, if it works this JobBlock
     */
    public Optional<Assignee> assignee(UUID settler) {
        return assignees.stream().filter(assignee -> assignee.id().equals(settler)).findFirst();
    }

    /**
     * @param job a Job
     * @return the first settler holding that Job at this JobBlock (e.g. the Clerk of a Clerk JobBlock)
     */
    public Optional<Assignee> assignee(Job job) {
        return assignees.stream().filter(assignee -> assignee.job() == job).findFirst();
    }

    /**
     * @return how many settlers this JobBlock holds in its own Job at its level (other Jobs: {@link JobPlaces})
     */
    public int capacity() {
        return SettlementRules.capacity(jobType, level);
    }

    /**
     * @param settler a settler UUID
     * @return whether that settler works this JobBlock
     */
    public boolean isAssignee(UUID settler) {
        return assignee(settler).isPresent();
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
