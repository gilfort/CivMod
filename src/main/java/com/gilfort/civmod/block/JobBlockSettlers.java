package com.gilfort.civmod.block;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementRules;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

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
 * How many settlers hold a Job at a JobBlock per level (vault {@code Systems/JobBlock.md}), from
 * {@code data/<namespace>/civmod_job_blocks/<job>.json} for the Base Job {@code <namespace>:<job>}:
 * {@code "settlers": [1, 1, 2, 2, 3]} (one number per level, 1 to {@value SettlementRules#MAX_SETTLERS}; the last pack
 * naming a list wins). A Job without a list has 1 place on every level. {@code "laborers": 2} sets how many Laborers
 * help at a Resource Collection JobBlock of the Base Job ({@value #DEFAULT_LABORERS} without it). Other entries in the
 * file (the upgrade costs of the former {@code civmod_upgrades} files) are ignored.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobBlockSettlers {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("job_block_settlers");

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_job_blocks");
    /** The key of the settlers list. */
    private static final String SETTLERS_KEY = "settlers";
    /** Settlers per level: one entry per JobBlock level, each 1 to {@value SettlementRules#MAX_SETTLERS}. */
    private static final Codec<List<Integer>> SETTLERS_CODEC = Codec.intRange(1, SettlementRules.MAX_SETTLERS)
            .listOf(SettlementRules.MAX_LEVEL, SettlementRules.MAX_LEVEL);
    /** The key of the Laborers limit. */
    private static final String LABORERS_KEY = "laborers";
    /** Laborers a JobBlock takes without a value in a file (vault {@code Jobs/Laborer.md}). */
    private static final int DEFAULT_LABORERS = 2;
    /** What the files of one pack set say; later packs replace earlier values. */
    private record Loaded(Map<Job, List<Integer>> settlers, Map<Job, Integer> laborers) {
    }

    private static Loaded loaded = new Loaded(Map.of(), Map.of());

    private JobBlockSettlers() {
    }

    /**
     * @param baseJob a Base Job
     * @return how many Laborers help at a JobBlock of that Base Job at most ({@code "laborers": 2} in its file;
     *         {@value #DEFAULT_LABORERS} without one, 0 switches the help off)
     */
    public static int laborers(Job baseJob) {
        return loaded.laborers().getOrDefault(baseJob, DEFAULT_LABORERS);
    }

    /**
     * Merges the {@code laborers} values of one job's files in order (a later value replaces an earlier one).
     *
     * @param files parsed JSON files of one job, in pack order
     * @return the Laborers limit, empty if no file names one
     */
    public static Optional<Integer> mergeLaborers(List<JsonElement> files) {
        Optional<Integer> merged = Optional.empty();
        for (JsonElement file : files) {
            if (file.isJsonObject() && file.getAsJsonObject().has(LABORERS_KEY)) {
                merged = Optional.of(Codec.intRange(0, SettlementRules.MAX_SETTLERS).parse(JsonOps.INSTANCE, file.getAsJsonObject().get(LABORERS_KEY)).getOrThrow());
            }
        }
        return merged;
    }

    /**
     * @param job   a Job
     * @param level a JobBlock level
     * @return how many settlers hold the Job at a JobBlock of that level
     */
    public static int settlers(Job job, int level) {
        List<Integer> settlers = loaded.settlers().get(job);
        return settlers == null ? 1 : settlers.get(SettlementRules.clampLevel(level) - SettlementRules.MIN_LEVEL);
    }

    /**
     * Merges the {@code settlers} lists of one job's files in order (a later list replaces an earlier one; a file
     * without one keeps it).
     *
     * @param files parsed JSON files of one job, in pack order
     * @return the settlers per level (index 0 = level 1), empty if no file names them
     */
    public static Optional<List<Integer>> merge(List<JsonElement> files) {
        Optional<List<Integer>> merged = Optional.empty();
        for (JsonElement file : files) {
            if (file.isJsonObject() && file.getAsJsonObject().has(SETTLERS_KEY)) {
                merged = Optional.of(List.copyOf(SETTLERS_CODEC.parse(JsonOps.INSTANCE, file.getAsJsonObject().get(SETTLERS_KEY)).getOrThrow()));
            }
        }
        return merged;
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<Loaded> {

        @Override
        protected Loaded prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<Job, List<JsonElement>> files = new HashMap<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                Job job = ModJobs.REGISTRY.getValue(FILES.fileToId(entry.getKey()));
                if (job == null) {
                    CivMod.LOGGER.warn("JobBlock file {} names no known job", entry.getKey());
                    return;
                }
                for (Resource resource : entry.getValue()) {
                    try (Reader reader = resource.openAsReader()) {
                        files.computeIfAbsent(job, key -> new ArrayList<>()).add(JsonParser.parseReader(reader));
                    } catch (Exception e) {
                        CivMod.LOGGER.error("Failed to read JobBlock file {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                    }
                }
            });
            Map<Job, List<Integer>> settlers = new HashMap<>();
            Map<Job, Integer> laborers = new HashMap<>();
            files.forEach((job, list) -> {
                try {
                    merge(list).ifPresent(perLevel -> settlers.put(job, perLevel));
                } catch (Exception e) {
                    CivMod.LOGGER.error("Invalid settlers for {} (a list of {} numbers from 1 to {}), its JobBlock holds 1", job,
                            SettlementRules.MAX_LEVEL, SettlementRules.MAX_SETTLERS, e);
                }
                try {
                    mergeLaborers(list).ifPresent(limit -> laborers.put(job, limit));
                } catch (Exception e) {
                    CivMod.LOGGER.error("Invalid laborers for {} (a number from 0 to {}), its JobBlock takes {}", job,
                            SettlementRules.MAX_SETTLERS, DEFAULT_LABORERS, e);
                }
            });
            return new Loaded(Map.copyOf(settlers), Map.copyOf(laborers));
        }

        @Override
        protected void apply(Loaded prepared, ResourceManager manager, ProfilerFiller profiler) {
            loaded = prepared;
        }
    }
}
