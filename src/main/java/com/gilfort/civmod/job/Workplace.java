package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What a settler keeps for one job at its JobBlock (vault {@code Systems/Resource Collection Jobs.md},
 * {@code Systems/JobBlock.md}): the buffer, the status shown in the job tab, the time a blocked job waits, the work
 * place of the day and the scan lifecycle (requested, scanning, ready, rescanned per day or when centre or radius
 * change). The {@link ScanScheduler} is only used from here.
 * <p>
 * The {@link SettlerWork} holds one Workplace per JobBlock; forgetting the job drops the whole Workplace, so nothing of
 * it survives. Buffer, work place of the day and scan are saved with the settler (shape unchanged since before the
 * Workplace: {@link SettlerWork.BuffersSnapshot}); status, blocked time and the fractional production of the
 * {@link WorkRules} are not.
 */
final class Workplace {

    /** Ticks a blocked job waits before it is checked again. */
    static final int BLOCKED_RETRY = 600;

    /** What a scan was made for; a different key triggers a rescan. */
    private record ScanKey(BlockPos center, int radius, long day) {
    }

    private final BlockPos jobBlock;
    private JobBuffer buffer = new JobBuffer();
    private JobStatus status = JobStatus.IDLE;
    private long blockedUntil;
    private @Nullable BlockPos workPlaceOfDay;
    private long workPlaceDay = -1L;
    private @Nullable ScanResult scan;
    private @Nullable ScanKey scanKey;
    /** The scan in flight; its result is only stored while it is still the pending one. */
    private @Nullable ScanKey scanning;
    /** Counts scan requests; a result whose request is no longer the latest (restored in between) is dropped. */
    private int scanRequest;
    private boolean catchUpEnded;
    /** Fractional production of the {@link WorkRules} between calls (not saved). */
    private double production;

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
    }

    /**
     * Blocks the job for {@value #BLOCKED_RETRY} ticks with a status explaining why.
     *
     * @param status   the status
     * @param gameTime the game time now
     */
    void block(JobStatus status, long gameTime) {
        this.status = status;
        blockedUntil = gameTime + BLOCKED_RETRY;
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
     * JobBlock's work points (or by the job's own search).
     *
     * @param level   the level
     * @param profile the job's profile
     * @param site    the JobBlock site
     * @param day     the day
     * @return the work place, null if there is none
     */
    @Nullable BlockPos workPlaceOfDay(ServerLevel level, JobProfile profile, JobBlockSite site, long day) {
        BlockPos current = workPlaceOfDay;
        if (current != null && workPlaceDay == day && profile.isWorkPlace(level, current)) {
            return current;
        }
        List<BlockPos> candidates = new ArrayList<>(site.block().workPoints().stream().filter(pos -> profile.isWorkPlace(level, pos)).toList());
        BlockPos chosen = candidates.isEmpty() ? profile.autoSearch(level, site.pos())
                : candidates.get(level.getRandom().nextInt(candidates.size()));
        if (chosen != null) {
            workPlaceOfDay = chosen;
            workPlaceDay = day;
        }
        return chosen;
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
    void ensureScan(ServerLevel level, JobProfile profile, JobBlockSite site, BlockPos poi, long day, long dayTime) {
        ScanKey key = new ScanKey(profile.scanCenter(site.block(), poi), SettlementRules.joinRadius(site.level()), profile.rescanDaily() ? day : -1L);
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
        });
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
        return current == null ? Optional.empty() : Optional.of(new SettlerWork.WorkPlaceEntry(jobBlock, current, workPlaceDay));
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
     * Drops the saved parts (buffer, work place of the day, scan) before a restore, and the scan in flight: its result
     * is ignored when it arrives, and the next plan asks for a scan again if the restored one does not fit.
     */
    void clearSaved() {
        buffer = new JobBuffer();
        workPlaceOfDay = null;
        workPlaceDay = -1L;
        scan = null;
        scanKey = null;
        scanning = null;
        scanRequest++;
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
    }

    /**
     * @param saved the saved scan (ignored without a result)
     */
    void restoreScan(SavedScan saved) {
        ScanResult result = saved.result();
        if (result != null) {
            scan = result;
            scanKey = new ScanKey(saved.center(), saved.radius(), saved.day());
        }
    }
}
