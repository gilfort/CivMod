package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * CivMod's built-in Jobs, for addons that attach Specializations to them ({@link Job.Builder#parent}) or name them in
 * data. Mission Jobs are Specializations of the {@link #ADVENTURER}, unlocked on the Mission Board by
 * their unlock item ({@link Job.Builder#unlockItem}) in a Station Types slot.
 */
@ApiStatus.Experimental
public final class CivModJobs {

    /** Resource Collection: fells trees. */
    public static final DeferredHolder<Job, Job> LUMBERJACK = job("lumberjack");
    /** Resource Collection: mines stone and ore. */
    public static final DeferredHolder<Job, Job> MINER = job("miner");
    /** Resource Collection: fishes. */
    public static final DeferredHolder<Job, Job> FISHERMAN = job("fisherman");
    /** Mission: the Base Job of the Mission Board and parent of every Mission Job. */
    public static final DeferredHolder<Job, Job> ADVENTURER = job("adventurer");
    /** Logistics (internal): the store and food point of a settlement. */
    public static final DeferredHolder<Job, Job> CLERK = job("clerk");
    /** Logistics (internal): carries items between JobBlocks; works on the Clerk JobBlock. */
    public static final DeferredHolder<Job, Job> DELIVERYMAN = job("deliveryman");
    /** Producing: crafts requested items from its patterns. */
    public static final DeferredHolder<Job, Job> CRAFTSPERSON = job("craftsperson");
    /** Producing: Specialization of the Craftsperson, crafts redstone recipes fast; unlocked by a Crafter. */
    public static final DeferredHolder<Job, Job> MECHANIC = job("mechanic");
    /** Producing: smelts requested items at furnaces, several in parallel, burning fuel. */
    public static final DeferredHolder<Job, Job> SMELTER = job("smelter");
    /** Producing: Specialization of the Craftsperson, crafts metal recipes and netherite upgrades; unlocked by a smithing table. */
    public static final DeferredHolder<Job, Job> SMITH = job("smith");

    private CivModJobs() {
    }

    private static DeferredHolder<Job, Job> job(String path) {
        return DeferredHolder.create(CivModApi.JOB_REGISTRY, Identifier.fromNamespaceAndPath(CivModApi.MOD_ID, path));
    }
}
