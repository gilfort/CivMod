package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.api.BiomePosition;
import com.gilfort.civmod.api.BlockingReason;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.OccupiedPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.biome.Biome;

/**
 * What a settler keeps for one job at its JobBlock (vault {@code Systems/Resource Collection Jobs.md},
 * {@code Systems/JobBlock.md}): the buffer, the status shown in the job tab, the time a blocked job waits, the work
 * place of the day with its biome and biome bonuses and the scan lifecycle (requested, scanning, ready, rescanned per
 * day or when centre or radius change), and for a Producing Job the current {@link Round}, the requests skipped for a while and a pending trip to
 * the drop-off. The {@link ScanScheduler} is only used from here.
 * <p>
 * The {@link SettlerWork} holds one Workplace per JobBlock; forgetting the job drops the whole Workplace, so nothing of
 * it survives. Buffer, work place of the day (with the biome read there) and scan are saved with the settler
 * ({@link SettlerWork.BuffersSnapshot}, the biome since #104); status, blocked time and the fractional production of the
 * {@link WorkRules}, the round and the skipped requests are not.
 */
final class Workplace {

    /** Ticks a blocked job waits before it is checked again. */
    static final int BLOCKED_RETRY = 600;

    /** What a scan was made for; a different key triggers a rescan. */
    private record ScanKey(BlockPos center, int radius, long day) {
    }

    /** The biome read at a place (the work place or the JobBlock) for a day. */
    private record BiomeOfDay(long day, BlockPos place, ResourceKey<Biome> biome) {
    }

    /** The biome bonuses of a Job resolved at the {@link BiomeOfDay}. */
    private record BonusOfDay(long day, BlockPos place, Job job, BiomeBonus bonus) {
    }

    private final BlockPos jobBlock;
    private JobBuffer buffer = new JobBuffer();
    private JobStatus status = JobStatus.IDLE;
    /** What the status is about (e.g. "Collecting – <block>", a blocking reason; not saved). */
    private Optional<net.minecraft.network.chat.Component> statusSubject = Optional.empty();
    /** The icon of a blocking reason, for the problem bubble (not saved). */
    private Optional<Item> statusIcon = Optional.empty();
    private long blockedUntil;
    private @Nullable BlockPos workPlaceOfDay;
    private long workPlaceDay = -1L;
    /** Whether the last choice of the work place of the day found only places occupied by others (not saved). */
    private boolean noFreeWorkPlace;
    /** The biome of the day's biome position, read once per day and place (saved with the work place of the day). */
    private @Nullable BiomeOfDay biomeOfDay;
    /** The biome bonuses resolved at {@link #biomeOfDay} (not saved: resolved again from the loaded files). */
    private @Nullable BonusOfDay bonusOfDay;
    private @Nullable ScanResult scan;
    private @Nullable ScanKey scanKey;
    /** The dropped item of each resource type per tool kind, for the Max checks of every roll; cleared with the scan. */
    private final Map<DropKey, Item> dropped = new HashMap<>();
    /** The scan in flight; its result is only stored while it is still the pending one. */
    private @Nullable ScanKey scanning;
    /** Counts scan requests; a result whose request is no longer the latest (restored in between) is dropped. */
    private int scanRequest;
    private boolean catchUpEnded;
    /** Fractional production of the {@link WorkRules} between calls (not saved). */
    private double production;
    /** The round of a Producing Job (not saved). */
    private @Nullable Round round;
    /** Requests a Producing Job skips until a time (live: game time, catch-up: the simulated clock; not saved). */
    private final Map<UUID, Long> skips = new HashMap<>();
    /** A Producing Job takes its buffer to the drop-off before the next round (not saved). */
    private boolean dropOffPending;

    /**
     * @param jobBlock the JobBlock position
     */
    Workplace(BlockPos jobBlock) {
        this.jobBlock = jobBlock;
    }

    // ---------------------------------------------------------------- production

    /**
     * @return the fractional production carried over by the {@link WorkRules} (0–1)
     */
    double production() {
        return production;
    }

    /**
     * @param production the fractional production to carry over
     */
    void setProduction(double production) {
        this.production = production;
    }

    // ---------------------------------------------------------------- producing round

    /**
     * @return the round of a Producing Job, null between rounds
     */
    @Nullable Round round() {
        return round;
    }

    /**
     * @param round the new round, null to end it
     */
    void setRound(@Nullable Round round) {
        this.round = round;
    }

    /**
     * @return the requests a Producing Job skips, with the time until which they are skipped (mutable)
     */
    Map<UUID, Long> skips() {
        return skips;
    }

    /**
     * @return whether the buffer goes to the drop-off before the next round
     */
    boolean dropOffPending() {
        return dropOffPending;
    }

    /**
     * @param pending whether the buffer goes to the drop-off before the next round
     */
    void setDropOffPending(boolean pending) {
        dropOffPending = pending;
    }

    // ---------------------------------------------------------------- buffer and status

    /**
     * @return the buffer of the job
     */
    JobBuffer buffer() {
        return buffer;
    }

    /**
     * @return the status of the job
     */
    JobStatus status() {
        return status;
    }

    /**
     * @param status the new status
     */
    void setStatus(JobStatus status) {
        this.status = status;
        this.statusSubject = Optional.empty();
        this.statusIcon = Optional.empty();
    }

    /**
     * @param status  the new status
     * @param subject what it is about
     */
    void setStatus(JobStatus status, net.minecraft.network.chat.Component subject) {
        this.status = status;
        this.statusSubject = Optional.of(subject);
        this.statusIcon = Optional.empty();
    }

    /**
     * @return what the status is about, if it names something
     */
    Optional<net.minecraft.network.chat.Component> statusSubject() {
        return statusSubject;
    }

    /**
     * Blocks the job for {@value #BLOCKED_RETRY} ticks with a status explaining why.
     *
     * @param status   the status
     * @param gameTime the game time now
     */
    void block(JobStatus status, long gameTime) {
        setStatus(status);
        blockedUntil = gameTime + BLOCKED_RETRY;
    }

    /**
     * Blocks the job for {@value #BLOCKED_RETRY} ticks with its profile's reason (status {@link JobStatus#BLOCKED}).
     *
     * @param reason   the reason
     * @param gameTime the game time now
     */
    void block(BlockingReason reason, long gameTime) {
        block(JobStatus.BLOCKED, gameTime);
        statusSubject = Optional.of(reason.text());
        statusIcon = reason.icon();
    }

    /**
     * @return the icon of the blocking reason while the status is {@link JobStatus#BLOCKED}, if it has one
     */
    Optional<Item> statusIcon() {
        return statusIcon;
    }

    /** Ends a block early: the next decision checks the job again. */
    void unblock() {
        blockedUntil = 0L;
    }

    /**
     * @param gameTime the game time now
     * @return whether the job still waits after being blocked
     */
    boolean isBlocked(long gameTime) {
        return blockedUntil > gameTime;
    }

    // ---------------------------------------------------------------- work place of the day and scan

    /**
     * The work place of the day: kept for the day while it is still a work place, otherwise chosen anew among the
     * JobBlock's work points (or by the job's own search); places occupied by other settlers are skipped
     * ({@link OccupiedPlaces}).
     *
     * @param level   the level
     * @param settler the settler
     * @param profile the job's profile
     * @param site    the JobBlock site
     * @param day     the day
     * @return the work place, null if there is none ({@link #noFreeWorkPlace} tells why)
     */
    @Nullable BlockPos workPlaceOfDay(ServerLevel level, Settler settler, ResourceCollectionProfile profile, JobBlockSite site, long day) {
        return workPlaceOfDay(level, settler, pos -> profile.isWorkPlace(level, pos), free -> profile.autoSearch(level, site.pos(), free), site, day);
    }

    /**
     * The work place of the day for any work place rule (a Producing Job's station). The place is occupied by the
     * settler for the day; a new place (the old one is no longer a work place, or another settler occupies it) moves
     * the occupation. Valid scroll work points that are all occupied leave the settler without a work place (no
     * auto-search then): the player chose where the job works.
     *
     * @param level       the level
     * @param settler     the settler
     * @param isWorkPlace whether a position is a work place of the job
     * @param autoSearch  the job's own search for a place that passes the given "free" test, used without a valid
     *                    scroll work point (may return null)
     * @param site        the JobBlock site
     * @param day         the day
     * @return the work place, null if there is none ({@link #noFreeWorkPlace} tells why)
     */
    @Nullable BlockPos workPlaceOfDay(ServerLevel level, Settler settler, Predicate<BlockPos> isWorkPlace,
            Function<Predicate<BlockPos>, @Nullable BlockPos> autoSearch, JobBlockSite site, long day) {
        Settlement settlement = site.settlement();
        UUID self = settler.getUUID();
        boolean[] occupiedSeen = new boolean[1];
        Predicate<BlockPos> free = pos -> {
            boolean isFree = OccupiedPlaces.isFreeFor(settlement, pos, self, day);
            occupiedSeen[0] |= !isFree;
            return isFree;
        };
        BlockPos current = workPlaceOfDay;
        if (current != null && workPlaceDay == day && isWorkPlace.test(current) && free.test(current)) {
            occupy(settlement, settler, current, day);
            noFreeWorkPlace = false;
            return current;
        }
        List<BlockPos> points = site.block().workPoints().stream().filter(isWorkPlace).toList();
        List<BlockPos> candidates = new ArrayList<>(points.stream().filter(free).toList());
        BlockPos chosen = points.isEmpty() ? autoSearch.apply(free)
                : candidates.isEmpty() ? null : candidates.get(level.getRandom().nextInt(candidates.size()));
        if (current != null && !current.equals(chosen)) {
            // The occupation moves with the work place (or ends without one).
            OccupiedPlaces.release(settlement, current, self);
        }
        noFreeWorkPlace = chosen == null && occupiedSeen[0];
        if (chosen != null) {
            workPlaceOfDay = chosen;
            workPlaceDay = day;
            occupy(settlement, settler, chosen, day);
        } else {
            workPlaceOfDay = null;
            workPlaceDay = -1L;
        }
        return chosen;
    }

    /**
     * Occupies a further place of the job for the day (a Producing Job's station of another recipe type).
     *
     * @param settlement the settlement of the JobBlock
     * @param settler    the settler
     * @param pos        the place
     * @param day        the day
     */
    void occupy(Settlement settlement, Settler settler, BlockPos pos, long day) {
        OccupiedPlaces.occupy(settlement, pos, settler.getUUID(), settler.getName().getString(), jobBlock, day);
    }

    /**
     * @return whether the last choice of the work place of the day found work places, but every one occupied by
     *         another settler ("No free work place" instead of "No work place")
     */
    boolean noFreeWorkPlace() {
        return noFreeWorkPlace;
    }

    /**
     * The biome bonuses acting on the job today ({@link BiomeBonuses#today}). The biome of the Job's biome position is
     * read once per day and place and kept for that day: a biome change at the place or a data reload takes effect with
     * the next day's choice. A work place chosen anew during the day (the old one is no longer a work place) reads its
     * own biome.
     *
     * @param level the level
     * @param job   the Job held at the JobBlock
     * @return the bonuses ({@link BiomeBonus#NONE} while no work place is chosen)
     */
    BiomeBonus biomeBonus(ServerLevel level, Job job) {
        BiomePosition position = BiomeBonuses.position(job);
        BlockPos place = switch (position) {
            case NONE -> null;
            case JOB_BLOCK -> jobBlock;
            case WORK_PLACE -> workPlaceOfDay;
        };
        if (place == null) {
            return BiomeBonus.NONE;
        }
        long day = position == BiomePosition.JOB_BLOCK ? DayClock.day(level) : workPlaceDay;
        BonusOfDay cached = bonusOfDay;
        if (cached != null && cached.day() == day && cached.job() == job && cached.place().equals(place)) {
            return cached.bonus();
        }
        BiomeBonus bonus = BiomeBonuses.resolve(job, biome(level, place, day));
        bonusOfDay = new BonusOfDay(day, place, job, bonus);
        return bonus;
    }

    /** The biome of a place for a day: the one read (or restored) for that day and place, else the place's biome now. */
    private Holder<Biome> biome(ServerLevel level, BlockPos place, long day) {
        BiomeOfDay known = biomeOfDay;
        if (known != null && known.day() == day && known.place().equals(place)) {
            Optional<Holder.Reference<Biome>> restored = level.registryAccess().lookupOrThrow(Registries.BIOME).get(known.biome());
            if (restored.isPresent()) {
                return restored.get();
            }
        }
        Holder<Biome> biome = level.getBiome(place);
        biomeOfDay = biome.unwrapKey().map(key -> new BiomeOfDay(day, place, key)).orElse(null);
        return biome;
    }

    /**
     * Requests a scan around the work place unless the current or pending one was made for the same centre, radius
     * and (for daily scans) day.
     *
     * @param level   the level
     * @param profile the job's profile
     * @param site    the JobBlock site
     * @param poi     the work place of the day
     * @param day     the day
     * @param dayTime the time of day
     */
    void ensureScan(ServerLevel level, ResourceCollectionProfile profile, JobBlockSite site, BlockPos poi, long day, long dayTime) {
        ScanKey key = new ScanKey(profile.scanCenter(site.pos(), site.block().workPoints(), poi), profile.scanRadius(site.level()),
                profile.rescanDaily() ? day : -1L);
        if (key.equals(scanKey) || scanning != null) {
            return;
        }
        scanning = key;
        int request = ++scanRequest;
        // Spread daily scans over the first real minute of the workday; later starts (e.g. after loading) begin at once.
        long delay = profile.rescanDaily() && dayTime < SettlerWork.SCAN_SPREAD_TICKS ? level.getRandom().nextInt(SettlerWork.SCAN_SPREAD_TICKS) : 0;
        ScanScheduler.submit(level, profile.createScan(key.center(), key.radius()), delay, result -> {
            // Only the pending scan lands (not one from before a restore); a scan of a forgotten job lands in its
            // dropped Workplace, which nothing reads.
            if (request != scanRequest) {
                return;
            }
            scan = result;
            scanKey = key;
            scanning = null;
            dropped.clear();
            // The JobBlock keeps the latest scan for its Found tab.
            SavedScan.of(jobBlock, key.center(), key.radius(), key.day(), result)
                    .ifPresent(saved -> JobAssignments.rememberScan(SettlementData.get(level), jobBlock, saved));
        });
    }

    /** What a dropped item depends on: the resource type and the tool's item and enchantments (not its damage). */
    private record DropKey(Identifier type, Item tool, ItemEnchantments enchantments) {
    }

    /**
     * The item a resource type drops with a tool ({@link ResourceCollectionProfile#dropped}), computed once per scan and tool kind:
     * the Max checks ask for it on every production roll.
     *
     * @param level   the level
     * @param profile the job's profile
     * @param pos     where the resource is mined
     * @param type    the resource type
     * @param tool    the tool
     * @return the dropped item
     */
    Item dropped(ServerLevel level, ResourceCollectionProfile profile, BlockPos pos, Identifier type, ItemStack tool) {
        DropKey key = new DropKey(type, tool.getItem(), tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
        Item item = dropped.get(key);
        if (item == null) {
            item = profile.dropped(level, pos, type, tool);
            dropped.put(key, item);
        }
        return item;
    }

    /**
     * @return the latest scan result, null before the first scan is ready
     */
    @Nullable ScanResult scan() {
        return scan;
    }

    /**
     * @return whether the latest scan found nothing and no newer scan is on its way
     */
    boolean foundNothing() {
        return scan != null && scan.isEmpty() && scanning == null;
    }

    // ---------------------------------------------------------------- catch-up

    /**
     * @param ended whether the job is over for the rest of the catch-up run
     */
    void setCatchUpEnded(boolean ended) {
        catchUpEnded = ended;
    }

    /**
     * @return whether the job is over for the rest of the catch-up run
     */
    boolean catchUpEnded() {
        return catchUpEnded;
    }

    // ---------------------------------------------------------------- persistence

    /**
     * @return the saved form of the work place of the day, empty if none was chosen
     */
    Optional<SettlerWork.WorkPlaceEntry> savedWorkPlace() {
        BlockPos current = workPlaceOfDay;
        if (current == null) {
            return Optional.empty();
        }
        BiomeOfDay biome = biomeOfDay;
        Optional<ResourceKey<Biome>> read = biome != null && biome.day() == workPlaceDay && biome.place().equals(current)
                ? Optional.of(biome.biome()) : Optional.empty();
        return Optional.of(new SettlerWork.WorkPlaceEntry(jobBlock, current, workPlaceDay, read));
    }

    /**
     * @return the saved form of the scan, empty if there is none (or its result type is not saved)
     */
    Optional<SavedScan> savedScan() {
        ScanResult result = scan;
        ScanKey key = scanKey;
        return result == null || key == null ? Optional.empty() : SavedScan.of(jobBlock, key.center(), key.radius(), key.day(), result);
    }

    /**
     * Drops the saved parts (buffer, work place of the day, scan) before a restore, the scan in flight (its result is
     * ignored when it arrives, and the next plan asks for a scan again if the restored one does not fit) and the round
     * (its claim is released by the Producing loop).
     */
    void clearSaved() {
        round = null;
        skips.clear();
        dropOffPending = false;
        buffer = new JobBuffer();
        workPlaceOfDay = null;
        workPlaceDay = -1L;
        biomeOfDay = null;
        bonusOfDay = null;
        scan = null;
        scanKey = null;
        scanning = null;
        scanRequest++;
        dropped.clear();
    }

    /**
     * @param saved the saved buffer
     */
    void restoreBuffer(JobBuffer saved) {
        buffer = saved;
    }

    /**
     * @param saved the saved work place of the day
     */
    void restoreWorkPlace(SettlerWork.WorkPlaceEntry saved) {
        workPlaceOfDay = saved.poi();
        workPlaceDay = saved.day();
        // The day's biome stays (a reload on the same day keeps it); the bonuses are resolved again from the loaded files.
        biomeOfDay = saved.biome().map(biome -> new BiomeOfDay(saved.day(), saved.poi(), biome)).orElse(null);
        bonusOfDay = null;
    }

    /**
     * @param saved the saved scan
     */
    void restoreScan(SavedScan saved) {
        scan = saved.result();
        scanKey = new ScanKey(saved.center(), saved.radius(), saved.day());
        dropped.clear();
    }
}
