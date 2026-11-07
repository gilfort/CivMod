package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Station Types of a JobBlock (vault {@code Systems/JobBlock.md}): 5 slots that unlock the Specializations of its
 * Base Job at this JobBlock. The Base Job holds no slot, the JobBlock always offers it; one slot is open per JobBlock
 * level (level 1: the first, level 5: all 5), so a JobBlock has up to 5 Specializations, and the Base Job's own station
 * is no unlock item. An unlock item put into an open slot is consumed and
 * unlocks every Specialization of the Base Job naming it; put into a filled slot it replaces the old one, and settlers
 * working a Job no longer unlocked fall back to the Base Job (its library stays, unusable until it is unlocked again);
 * a settler already holding the Base Job at another JobBlock leaves this one instead.
 * The unlock item is not the work place: a Specialization still needs its station in the world.
 * <p>
 * Every JobBlock whose Base Job has Specializations has Station Types, the Mission Board included: its slots unlock the
 * Mission Specializations of the Adventurer, if a mod registers any.
 */
public final class StationTypes {

    /** Slots per JobBlock: one per level. */
    public static final int SLOTS = 5;

    private StationTypes() {
    }

    /**
     * What putting an item into a Station Types slot did.
     */
    public enum PutResult {
        /** An empty slot unlocked the item's Jobs. */
        UNLOCKED,
        /** A filled slot was replaced; Jobs no longer unlocked fell back to the Base Job. */
        REPLACED,
        /** The slot is not open at the JobBlock's level. */
        LOCKED,
        /** The item unlocks no Specialization of this JobBlock, or it is the Base Job's own station. */
        UNLOCKS_NOTHING,
        /** Its Jobs are unlocked already. */
        ALREADY_UNLOCKED,
        /** No JobBlock with Station Types at that position. */
        NO_JOB_BLOCK
    }

    /**
     * One line of the "?" overview: an unlock item and every Job it unlocks.
     *
     * @param item the unlock item
     * @param jobs the Jobs naming it, in registry order
     */
    public record Unlock(Item item, List<Job> jobs) {

        /**
         * @param item the unlock item
         * @param jobs the Jobs naming it
         */
        public Unlock {
            jobs = List.copyOf(jobs);
        }
    }

    /**
     * @param base a Base Job
     * @return its Specializations, in registry order
     */
    public static List<Job> specializations(Job base) {
        return ModJobs.REGISTRY.stream().filter(job -> job.parent().filter(base::equals).isPresent()).toList();
    }

    /**
     * @param blockJob the Job of a JobBlock
     * @return whether its JobBlock has Station Types: its Base Job has Specializations
     */
    public static boolean hasStationTypes(Job blockJob) {
        return !specializations(blockJob).isEmpty();
    }

    /**
     * @param base a Base Job
     * @return the "?" overview: every unlock item of its Specializations with the Jobs it unlocks, in registry order;
     *         without the Base Job's own station, which unlocks nothing ({@link #unlockedBy})
     */
    public static List<Unlock> overview(Job base) {
        Map<Item, List<Job>> byItem = new LinkedHashMap<>();
        for (Job job : specializations(base)) {
            job.unlockItem().filter(item -> !unlockedBy(base, item).isEmpty())
                    .ifPresent(item -> byItem.computeIfAbsent(item, key -> new ArrayList<>()).add(job));
        }
        return byItem.entrySet().stream().map(entry -> new Unlock(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * @param base a Base Job
     * @param item an item
     * @return the Specializations of the Base Job the item unlocks; none for the Base Job's own station
     *         ({@link Job#unlockItem()}), even if a Specialization names it
     */
    public static List<Job> unlockedBy(Job base, Item item) {
        if (item == Items.AIR || base.unlockItem().filter(item::equals).isPresent()) {
            return List.of();
        }
        return specializations(base).stream().filter(job -> job.unlockItem().filter(item::equals).isPresent()).toList();
    }

    /**
     * @param level the JobBlock level
     * @return how many slots are open at that level (one per level)
     */
    public static int open(int level) {
        return Math.clamp(level, 1, SLOTS);
    }

    /**
     * @param slot a slot
     * @param open how many slots are open ({@link #open(int)})
     * @return whether the slot takes an unlock item
     */
    public static boolean isOpen(int slot, int open) {
        return slot >= 0 && slot < open;
    }

    /**
     * @param block a JobBlock
     * @return the Jobs of its Base Job it offers: the Base Job, then the Specializations unlocked by its slots in registry
     *         order
     */
    public static List<Job> unlocked(SettlementBlock block) {
        Job blockJob = block.jobType();
        if (!hasStationTypes(blockJob)) {
            return List.of(blockJob);
        }
        List<Job> unlocked = new ArrayList<>();
        unlocked.add(blockJob);
        List<Item> filled = block.stationTypes().stream().filter(item -> item != Items.AIR).toList();
        for (Job job : specializations(blockJob)) {
            if (job.unlockItem().filter(filled::contains).isPresent()) {
                unlocked.add(job);
            }
        }
        return List.copyOf(unlocked);
    }

    /**
     * @param block a JobBlock
     * @return how many of its open slots hold no unlock item; none for a JobBlock without Station Types (the Mission Board
     *         has none while no Mission Specialization is registered, so its offer is one pool of the Base Job)
     */
    public static int emptySlots(SettlementBlock block) {
        if (!hasStationTypes(block.jobType())) {
            return 0;
        }
        int open = open(block.level());
        int empty = 0;
        for (int slot = 0; slot < open; slot++) {
            if (slot >= block.stationTypes().size() || block.stationTypes().get(slot) == Items.AIR) {
                empty++;
            }
        }
        return empty;
    }

    /**
     * @param block a JobBlock
     * @param job   a Job
     * @return whether the JobBlock offers it now (its Base Job, or a Specialization unlocked there)
     */
    public static boolean isUnlocked(SettlementBlock block, Job job) {
        return unlocked(block).contains(job);
    }

    /**
     * Puts one unlock item into a Station Types slot of a JobBlock (vault {@code Systems/JobBlock.md}). An item that
     * unlocks something new is consumed (one taken from the stack); replacing a slot makes settlers working a Job no
     * longer unlocked fall back to the Base Job, or leave the JobBlock if they hold the Base Job at another one (loaded
     * ones at once, others at their next membership check).
     *
     * @param level the dimension
     * @param pos   the JobBlock
     * @param slot  the slot (0 to {@value #SLOTS} − 1)
     * @param stack the item to put (one is taken when accepted)
     * @return what happened
     */
    public static PutResult put(ServerLevel level, BlockPos pos, int slot, ItemStack stack) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = JobBlockSite.blockAt(data, pos).filter(block -> hasStationTypes(block.jobType()));
        if (found.isEmpty()) {
            return PutResult.NO_JOB_BLOCK;
        }
        SettlementBlock block = found.get();
        if (!isOpen(slot, open(block.level()))) {
            return PutResult.LOCKED;
        }
        Item item = stack.getItem();
        if (stack.isEmpty() || unlockedBy(block.jobType(), item).isEmpty()) {
            return PutResult.UNLOCKS_NOTHING;
        }
        if (block.stationTypes().contains(item)) {
            return PutResult.ALREADY_UNLOCKED;
        }
        boolean replaced = block.stationTypes().get(slot) != Items.AIR;
        stack.shrink(1);
        block.setStationType(slot, item);
        List<Job> unlocked = unlocked(block);
        Job base = block.jobType();
        List<UUID> lost = block.assignees().stream()
                .filter(assignee -> assignee.job().parent().filter(base::equals).isPresent() && !unlocked.contains(assignee.job()))
                .map(SettlementBlock.Assignee::id).toList();
        lost.forEach(id -> SettlementWorld.fallBackToBaseJob(level, pos, id));
        data.changed();
        return replaced ? PutResult.REPLACED : PutResult.UNLOCKED;
    }
}
