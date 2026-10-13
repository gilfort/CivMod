package com.gilfort.civmod.job;

import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.settlement.JobType;
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
 * Skill rules (vault {@code Systems/JobSkill Level.md}): EXP curve and base EXP per job from
 * {@code data/<namespace>/civmod_skill/levels.json}; files of all packs are merged, later entries override.
 *
 * @param base     EXP needed for level 1 → 2 minus {@code perLevel}
 * @param perLevel additional EXP per level
 * @param baseExp  base EXP per produced resource and job
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record SkillRules(int base, int perLevel, Map<JobType, Integer> baseExp) {

    /** Highest skill level. */
    public static final int MAX_LEVEL = 100;
    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("skill_rules");
    /** Values used when no file is loaded. */
    public static final SkillRules FALLBACK = new SkillRules(15, 5, Map.of(JobType.LUMBERJACK, 1, JobType.MINER, 1, JobType.FISHERMAN, 5));

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_skill");
    private static SkillRules current = FALLBACK;

    private record Part(Optional<Integer> base, Optional<Integer> perLevel, Map<JobType, Integer> baseExp) {
        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("base").forGetter(Part::base),
                Codec.INT.optionalFieldOf("per_level").forGetter(Part::perLevel),
                Codec.unboundedMap(JobType.CODEC, Codec.INT).optionalFieldOf("base_exp", Map.of()).forGetter(Part::baseExp)
        ).apply(instance, Part::new));
    }

    /**
     * @return the loaded rules
     */
    public static SkillRules get() {
        return current;
    }

    /**
     * @param level the current level
     * @return EXP needed to reach the next level
     */
    public int expToNext(int level) {
        return base + perLevel * level;
    }

    /**
     * @param jobType a job
     * @return its base EXP per produced resource
     */
    public int baseExp(JobType jobType) {
        return baseExp.getOrDefault(jobType, FALLBACK.baseExp().getOrDefault(jobType, 1));
    }

    /**
     * Merges JSON files in order (later ones override).
     *
     * @param files parsed JSON files
     * @return the merged rules
     */
    public static SkillRules merge(List<JsonElement> files) {
        int base = FALLBACK.base();
        int perLevel = FALLBACK.perLevel();
        Map<JobType, Integer> baseExp = new EnumMap<>(FALLBACK.baseExp());
        for (JsonElement file : files) {
            Part part = Part.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            base = part.base().orElse(base);
            perLevel = part.perLevel().orElse(perLevel);
            baseExp.putAll(part.baseExp());
        }
        return new SkillRules(base, perLevel, Map.copyOf(baseExp));
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<SkillRules> {

        @Override
        protected SkillRules prepare(ResourceManager manager, ProfilerFiller profiler) {
            List<JsonElement> files = new ArrayList<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream()
                    .filter(entry -> FILES.fileToId(entry.getKey()).getPath().equals("levels"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        for (Resource resource : entry.getValue()) {
                            try (Reader reader = resource.openAsReader()) {
                                files.add(JsonParser.parseReader(reader));
                            } catch (Exception e) {
                                CivMod.LOGGER.error("Failed to read skill rules {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                            }
                        }
                    });
            try {
                return merge(files);
            } catch (Exception e) {
                CivMod.LOGGER.error("Invalid skill rules, using fallback values", e);
                return FALLBACK;
            }
        }

        @Override
        protected void apply(SkillRules prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = prepared;
        }
    }
}
