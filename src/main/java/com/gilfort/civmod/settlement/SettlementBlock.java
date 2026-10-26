package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.SavedScan;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * A JobBlock as recorded by its settlement, including its work points and the assigned settlers with their Job
 * (vault {@code Systems/Scroll of Assignment.md}); places per Job in {@link JobPlaces}, the Station Types slots in
 * {@link StationTypes}, one pattern {@link Library} per Producing Job. The Mission Board keeps its daily
 * {@link MissionOffer}.
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
     * The state line of a Producing JobBlock's Patterns tab (vault {@code Systems/Producing Jobs.md}): shown until the
     * library is next edited (a pattern put, toggled or cleared) or replaced by a newer notice; not saved.
     */
    public sealed interface Notice {

        /**
         * A new pattern stayed unchecked because the checkmark limit was reached.
         *
         * @param limit the limit
         */
        record AtLimit(int limit) implements Notice {
        }

        /**
         * Checkmarks were removed because the checkmark limit dropped.
         *
         * @param removed how many checkmarks were removed
         * @param limit   the new limit
         */
        record Trimmed(int removed, int limit) implements Notice {
        }
    }

    /**
     * The pattern library of one Producing Job at this JobBlock (vault {@code Systems/Producing Jobs.md}): its patterns
     * by slot, the checkmark limit of the settler last seen working it and the Patterns tab's state line (not saved).
     */
    public static final class Library {

        private final List<Pattern> patterns = new ArrayList<>();
        private int checkLimit = Patterns.BASE_CHECKMARKS;
        private Optional<Notice> notice = Optional.empty();
        /** The checkmark limit each settler working this library last reported (the best one counts). */
        private final Map<UUID, Integer> workerLimits = new HashMap<>();

        /**
         * @return the checkmark limit each settler working this library last reported, by settler
         */
        Map<UUID, Integer> workerLimits() {
            return workerLimits;
        }

        /**
         * @return the patterns, by slot (read-only)
         */
        public List<Pattern> patterns() {
            return Collections.unmodifiableList(patterns);
        }

        List<Pattern> mutablePatterns() {
            return patterns;
        }

        /**
         * @return the checkmark limit of the best settler working this library, as last applied
         */
        public int checkLimit() {
            return checkLimit;
        }

        void setCheckLimit(int checkLimit) {
            this.checkLimit = checkLimit;
        }

        /**
         * @return the Patterns tab's state line: a new pattern left unchecked at the limit, or the checkmarks removed by a
         *         dropped limit; shown until the library is next edited (not saved)
         */
        public Optional<Notice> notice() {
            return notice;
        }

        void setNotice(Optional<Notice> notice) {
            this.notice = notice;
        }
    }

    /**
     * A library as saved.
     *
     * @param job        the Job it belongs to
     * @param patterns   its patterns
     * @param checkLimit its checkmark limit
     * @param workers    the checkmark limit each settler working it last reported
     */
    private record SavedLibrary(Job job, List<Pattern> patterns, int checkLimit, Map<UUID, Integer> workers) {

        static final Codec<SavedLibrary> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Job.CODEC.fieldOf("job").forGetter(SavedLibrary::job),
                Pattern.CODEC.listOf().optionalFieldOf("patterns", List.of()).forGetter(SavedLibrary::patterns),
                Codec.INT.optionalFieldOf("check_limit", Patterns.BASE_CHECKMARKS).forGetter(SavedLibrary::checkLimit),
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("workers", Map.of()).forGetter(SavedLibrary::workers)
        ).apply(instance, SavedLibrary::new));
    }

    /**
     * The Producing fields, grouped for the save codec (saved flat, next to the other fields). The one shared library
     * saved before Station Types ({@code patterns}) is dropped on load; a library of a Job that no longer exists drops
     * every library of the block.
     *
     * @param libraries     the pattern library per Job
     * @param byDeliveryman whether Deliverymen carry the results ("Delivery: Deliveryman"), else the producer itself
     * @param fuel          the fuel list of the Fuel tab in priority order, empty while the player never changed it
     *                      (the Job's default list then)
     */
    private record ProducingFields(List<SavedLibrary> libraries, boolean byDeliveryman, Optional<List<Item>> fuel) {

        static final MapCodec<ProducingFields> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                SavedLibrary.CODEC.listOf().lenientOptionalFieldOf("libraries", List.of()).forGetter(ProducingFields::libraries),
                Codec.BOOL.optionalFieldOf("by_deliveryman", false).forGetter(ProducingFields::byDeliveryman),
                BuiltInRegistries.ITEM.byNameCodec().listOf().lenientOptionalFieldOf("fuel").forGetter(ProducingFields::fuel)
        ).apply(instance, ProducingFields::new));

        static final ProducingFields NONE = new ProducingFields(List.of(), false, Optional.empty());
    }

    /**
     * The settings of a JobBlock's places, grouped for the save codec (saved flat, next to the other fields).
     *
     * @param autoAssign the Jobs whose free places jobless settlers take by themselves
     * @param excluded   the found resource types excluded by the player (Resource Collection Jobs; kept when a scan no
     *                   longer finds them)
     * @param scan       the last scan of a settler working the JobBlock (the Found tab without a settler)
     * @param stationTypes the unlock items in the Station Types slots, by slot ({@link Items#AIR}: empty; slot 0 is the
     *                   Base Job's station and never read back)
     */
    private record PlaceFields(List<Job> autoAssign, Set<Identifier> excluded, Optional<SavedScan> scan, List<Item> stationTypes) {

        static final MapCodec<PlaceFields> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Job.CODEC.listOf().optionalFieldOf("auto_assign", List.of()).forGetter(PlaceFields::autoAssign),
                Identifier.CODEC.listOf().optionalFieldOf("excluded", List.of()).forGetter(fields -> fields.excluded().stream().sorted().toList()),
                SavedScan.CODEC.optionalFieldOf("found_scan").forGetter(PlaceFields::scan),
                BuiltInRegistries.ITEM.byNameCodec().listOf().lenientOptionalFieldOf("station_types", List.of()).forGetter(PlaceFields::stationTypes)
        ).apply(instance, (autoAssign, excluded, scan, stationTypes) -> new PlaceFields(autoAssign, Set.copyOf(excluded), scan, stationTypes)));

        static final PlaceFields NONE = new PlaceFields(List.of(), Set.of(), Optional.empty(), List.of());
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
            ProducingFields.MAP_CODEC.forGetter(SettlementBlock::producingFields),
            BlockPos.CODEC.optionalFieldOf("home_clerk").forGetter(SettlementBlock::homeClerk),
            PlaceFields.MAP_CODEC.forGetter(SettlementBlock::placeFields)
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
    private final Map<Job, Library> libraries = new LinkedHashMap<>();
    private boolean byDeliveryman;
    private Optional<List<Item>> fuel;
    private final Set<Identifier> excluded = new HashSet<>();
    private Optional<SavedScan> foundScan;
    private final Set<Job> autoAssign = new LinkedHashSet<>();
    private final List<Item> stationTypes = new ArrayList<>(Collections.nCopies(StationTypes.SLOTS, Items.AIR));

    /**
     * @param pos       the block position
     * @param jobType   the offered job
     * @param level     the JobBlock level
     * @param connected whether the block is connected to the settlement chain
     */
    public SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected) {
        this(pos, jobType, level, connected, List.of(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), false,
                MissionOffer.EMPTY, ClerkFields.NONE, ProducingFields.NONE, Optional.empty(), PlaceFields.NONE);
    }

    private SettlementBlock(BlockPos pos, Job jobType, int level, boolean connected, List<BlockPos> workPoints,
            Optional<BlockPos> collect, Optional<BlockPos> dropOff, List<SavedAssignee> assignees, Optional<UUID> legacyAssignee,
            Optional<String> legacyAssigneeName, boolean collectLeaves, MissionOffer missionOffer, ClerkFields clerk,
            ProducingFields producing, Optional<BlockPos> homeClerk, PlaceFields place) {
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
        // One rule per item and Clerk: an older save may hold more; the first one stays.
        Set<Item> ruled = new HashSet<>();
        clerk.stockRules().stream().filter(rule -> ruled.add(rule.item())).forEach(this.stockRules::add);
        this.served.addAll(clerk.served());
        this.homeClerk = homeClerk;
        producing.libraries().forEach(saved -> {
            Library library = library(saved.job());
            library.mutablePatterns().addAll(saved.patterns());
            library.setCheckLimit(saved.checkLimit());
            library.workerLimits().putAll(saved.workers());
        });
        this.byDeliveryman = producing.byDeliveryman();
        this.fuel = producing.fuel().map(List::copyOf);
        this.autoAssign.addAll(place.autoAssign());
        this.excluded.addAll(place.excluded());
        this.foundScan = place.scan();
        // Slot 0 holds the Base Job's station; only the unlock items of the other slots are kept.
        for (int slot = 1; slot < Math.min(StationTypes.SLOTS, place.stationTypes().size()); slot++) {
            this.stationTypes.set(slot, place.stationTypes().get(slot));
        }
    }

    private PlaceFields placeFields() {
        return new PlaceFields(List.copyOf(autoAssign), Set.copyOf(excluded), foundScan, List.copyOf(stationTypes));
    }

    /**
     * @return the unlock items in the Station Types slots, by slot (read-only, {@value StationTypes#SLOTS} entries,
     *         {@link Items#AIR} for an empty slot; slot 0 stands for the Base Job's station, see {@link StationTypes})
     */
    public List<Item> stationTypes() {
        return Collections.unmodifiableList(stationTypes);
    }

    void setStationType(int slot, Item item) {
        stationTypes.set(slot, item);
    }

    /**
     * @return the found resource types excluded at this JobBlock (read-only; Resource Collection Jobs, else empty)
     */
    public Set<Identifier> excluded() {
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * @param type a found resource type
     * @return whether it is excluded now (toggled)
     */
    boolean toggleExcluded(Identifier type) {
        if (!excluded.remove(type)) {
            excluded.add(type);
            return true;
        }
        return false;
    }

    /**
     * @return the last scan of a settler working this JobBlock, if any
     */
    public Optional<SavedScan> foundScan() {
        return foundScan;
    }

    void setFoundScan(SavedScan scan) {
        foundScan = Optional.of(scan);
    }

    /**
     * @return the Jobs whose free places jobless settlers take by themselves (vault {@code Systems/Scroll of
     *         Assignment.md}); default none (read-only; Jobs no longer offered at the block's level may stay in it)
     */
    public Set<Job> autoAssign() {
        return Collections.unmodifiableSet(autoAssign);
    }

    /**
     * @param job a Job
     * @param on  whether jobless settlers take its free places by themselves
     * @return whether the state changed
     */
    boolean setAutoAssign(Job job, boolean on) {
        return on ? autoAssign.add(job) : autoAssign.remove(job);
    }

    private ClerkFields clerkFields() {
        return new ClerkFields(List.copyOf(storage), List.copyOf(standSpots), spotOfDay, List.copyOf(stockRules), List.copyOf(served));
    }

    private ProducingFields producingFields() {
        List<SavedLibrary> saved = libraries.entrySet().stream().filter(entry -> !entry.getValue().patterns().isEmpty())
                .map(entry -> new SavedLibrary(entry.getKey(), List.copyOf(entry.getValue().patterns()), entry.getValue().checkLimit(),
                        Map.copyOf(entry.getValue().workerLimits()))).toList();
        return new ProducingFields(saved, byDeliveryman, fuel);
    }

    /**
     * @return the fuel list set in the Fuel tab in priority order, empty while the player never changed it (the Job's
     *         default list applies then, see {@link Fuels})
     */
    public Optional<List<Item>> fuel() {
        return fuel;
    }

    void setFuel(List<Item> fuel) {
        this.fuel = Optional.of(List.copyOf(fuel));
    }

    /**
     * @return whether Deliverymen carry the Craftsperson's results ("Delivery: Deliveryman"); default the Craftsperson
     *         delivers itself
     */
    public boolean byDeliveryman() {
        return byDeliveryman;
    }

    void setByDeliveryman(boolean byDeliveryman) {
        this.byDeliveryman = byDeliveryman;
    }

    /**
     * @param job a Producing Job of this JobBlock
     * @return its pattern library, by slot (read-only; empty for a Job without one)
     */
    public List<Pattern> patterns(Job job) {
        Library library = libraries.get(job);
        return library == null ? List.of() : library.patterns();
    }

    /**
     * @param job a Producing Job of this JobBlock
     * @return the checkmark limit of its library: the one of the best settler working it, as last applied
     */
    public int checkLimit(Job job) {
        Library library = libraries.get(job);
        return library == null ? Patterns.BASE_CHECKMARKS : library.checkLimit();
    }

    /**
     * @param job a Producing Job of this JobBlock
     * @return the state line of its Patterns tab (not saved)
     */
    public Optional<Notice> notice(Job job) {
        Library library = libraries.get(job);
        return library == null ? Optional.empty() : library.notice();
    }

    /**
     * @return the Jobs whose library holds a pattern, in the order their libraries were made
     */
    public List<Job> libraryJobs() {
        return libraries.entrySet().stream().filter(entry -> !entry.getValue().patterns().isEmpty()).map(Map.Entry::getKey).toList();
    }

    /**
     * @param job a Producing Job of this JobBlock
     * @return the settlers whose checkmark limit its library keeps (read-only)
     */
    public Set<UUID> workers(Job job) {
        Library library = libraries.get(job);
        return library == null ? Set.of() : Collections.unmodifiableSet(library.workerLimits().keySet());
    }

    Library library(Job job) {
        return libraries.computeIfAbsent(job, key -> new Library());
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

    /**
     * Switches the Job a settler holds here, keeping its place in the assignee order; the old Job's library forgets the
     * settler's checkmark limit.
     *
     * @param settler the settler UUID
     * @param job     the new Job
     */
    void setAssigneeJob(UUID settler, Job job) {
        for (int i = 0; i < assignees.size(); i++) {
            Assignee assignee = assignees.get(i);
            if (assignee.id().equals(settler)) {
                forgetWorker(assignee.job(), settler);
                assignees.set(i, new Assignee(settler, assignee.name(), job));
            }
        }
    }

    /**
     * Removes a settler from this JobBlock; its Job's library forgets its checkmark limit.
     *
     * @param settler the settler UUID
     * @return whether it worked here
     */
    boolean removeAssignee(UUID settler) {
        assignee(settler).ifPresent(assignee -> forgetWorker(assignee.job(), settler));
        return assignees.removeIf(assignee -> assignee.id().equals(settler));
    }

    /**
     * Removes every settler from this JobBlock; the libraries forget their checkmark limits.
     */
    void clearAssignees() {
        assignees.clear();
        libraries.values().forEach(library -> library.workerLimits().clear());
    }

    private void forgetWorker(Job job, UUID settler) {
        Library library = libraries.get(job);
        if (library != null) {
            library.workerLimits().remove(settler);
        }
    }
}
