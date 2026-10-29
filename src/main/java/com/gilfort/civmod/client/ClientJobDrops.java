package com.gilfort.civmod.client;

import java.util.List;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.JobDrops;
import com.gilfort.civmod.registry.ModJobs;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The NPC drop pool entries the server sent ({@link JobDrops.Sync}), for the recipe viewers' display-only "Settler
 * Gathering" category (vault {@code Systems/Resource Collection Jobs.md}, decisions 2026-10-29). They arrive before the
 * recipes, so the viewers have them when they reload.
 */
public final class ClientJobDrops {

    private static List<JobDrops.Drop> drops = List.of();

    private ClientJobDrops() {
    }

    /**
     * @param payload the entries
     * @param context the network context
     */
    public static void handle(JobDrops.Sync payload, IPayloadContext context) {
        drops = payload.drops();
    }

    /**
     * @return the entries given to at least one Job, ordered by file id
     */
    public static List<JobDrops.Drop> drops() {
        return drops.stream().filter(drop -> !drop.entry().jobs().isEmpty()).toList();
    }

    /**
     * @return every Job with an entry, each once
     */
    public static List<Job> allJobs() {
        return drops().stream().flatMap(drop -> jobs(drop).stream()).distinct().toList();
    }

    /**
     * @param drop an entry
     * @return its Jobs (unknown ids left out)
     */
    public static List<Job> jobs(JobDrops.Drop drop) {
        return drop.entry().jobs().stream().flatMap(id -> ModJobs.REGISTRY.getOptional(id).stream()).toList();
    }
}
