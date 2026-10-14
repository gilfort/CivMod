package com.gilfort.civmod.job;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.registry.ModJobs;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * Datapack overrides of a Job's balancing values (vault {@code Systems/Jobs.md}) from
 * {@code data/<namespace>/civmod_jobs/<job>.json} for the job {@code <namespace>:<job>}. Files of all packs are merged per
 * job, later values override; values not set keep the Job's default. Upgrade costs live in
 * {@link com.gilfort.civmod.block.UpgradeCosts}.
 *
 * @param hungerFactor work hunger factor
 * @param baseExp      base EXP per produced resource
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record JobBalancing(Optional<Double> hungerFactor, Optional<Integer> baseExp) {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("job_balancing");
    /** No override. */
    public static final JobBalancing NONE = new JobBalancing(Optional.empty(), Optional.empty());

    private static final Codec<JobBalancing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.doubleRange(0.0, 10.0).optionalFieldOf("hunger_factor").forGetter(JobBalancing::hungerFactor),
            Codec.intRange(0, 10000).optionalFieldOf("base_exp").forGetter(JobBalancing::baseExp)
    ).apply(instance, JobBalancing::new));

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_jobs");
    private static Map<Job, JobBalancing> current = Map.of();

    /**
     * @param job a job
     * @return the loaded overrides of that job
     */
    public static JobBalancing of(Job job) {
        return current.getOrDefault(job, NONE);
    }

    /**
     * Merges the files of one job in order (later values override).
     *
     * @param files parsed JSON files
     * @return the merged overrides
     */
    public static JobBalancing merge(List<JsonElement> files) {
        JobBalancing merged = NONE;
        for (JsonElement file : files) {
            JobBalancing part = CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            merged = new JobBalancing(part.hungerFactor().or(merged::hungerFactor), part.baseExp().or(merged::baseExp));
        }
        return merged;
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<Map<Job, JobBalancing>> {

        @Override
        protected Map<Job, JobBalancing> prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<Job, List<JsonElement>> files = new HashMap<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                Identifier jobId = FILES.fileToId(entry.getKey());
                Job job = ModJobs.REGISTRY.getValue(jobId);
                if (job == null) {
                    CivMod.LOGGER.warn("Job balancing {} names no known job", entry.getKey());
                    return;
                }
                for (Resource resource : entry.getValue()) {
                    try (Reader reader = resource.openAsReader()) {
                        files.computeIfAbsent(job, key -> new ArrayList<>()).add(JsonParser.parseReader(reader));
                    } catch (Exception e) {
                        CivMod.LOGGER.error("Failed to read job balancing {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                    }
                }
            });
            Map<Job, JobBalancing> result = new HashMap<>();
            files.forEach((job, list) -> {
                try {
                    result.put(job, merge(list));
                } catch (Exception e) {
                    CivMod.LOGGER.error("Invalid job balancing for {}, using its defaults", job, e);
                }
            });
            return result;
        }

        @Override
        protected void apply(Map<Job, JobBalancing> prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = Map.copyOf(prepared);
        }
    }
}
