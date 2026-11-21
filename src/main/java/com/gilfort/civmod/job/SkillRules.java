package com.gilfort.civmod.job;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
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
 * Skill rules (vault {@code Systems/JobSkill Level.md}): EXP curve from {@code data/<namespace>/civmod_skill/levels.json};
 * files of all packs are merged, later entries override. Base EXP per job comes from the {@link Job} and
 * {@link JobBalancing}.
 *
 * @param base               EXP needed for level 1 → 2 minus {@code perLevel}
 * @param perLevel           additional EXP per level
 * @param talentBonusPercent the extra EXP a settler gains in a Job its upbringing favours, in percent of the EXP of the action
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record SkillRules(int base, int perLevel, int talentBonusPercent) {

    /** Highest skill level. */
    public static final int MAX_LEVEL = 100;
    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("skill_rules");
    /** Values used when no file is loaded. */
    public static final SkillRules FALLBACK = new SkillRules(15, 5, 25);

    /** The highest talent bonus a pack may set. */
    private static final int MAX_TALENT_BONUS_PERCENT = 1000;

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_skill");
    private static SkillRules current = FALLBACK;

    private record Part(Optional<Integer> base, Optional<Integer> perLevel, Optional<Integer> talentBonusPercent) {
        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("base").forGetter(Part::base),
                Codec.INT.optionalFieldOf("per_level").forGetter(Part::perLevel),
                Codec.intRange(0, MAX_TALENT_BONUS_PERCENT).optionalFieldOf("talent_bonus_percent").forGetter(Part::talentBonusPercent)
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
     * Merges JSON files in order (later ones override).
     *
     * @param files parsed JSON files
     * @return the merged rules
     */
    public static SkillRules merge(List<JsonElement> files) {
        int base = FALLBACK.base();
        int perLevel = FALLBACK.perLevel();
        int talentBonusPercent = FALLBACK.talentBonusPercent();
        for (JsonElement file : files) {
            Part part = Part.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            if (file.isJsonObject() && file.getAsJsonObject().has("base_exp")) {
                CivMod.LOGGER.warn("\"base_exp\" in civmod_skill/levels.json is ignored; set it per job in civmod_jobs/<job>.json");
            }
            base = part.base().orElse(base);
            perLevel = part.perLevel().orElse(perLevel);
            talentBonusPercent = part.talentBonusPercent().orElse(talentBonusPercent);
        }
        return new SkillRules(base, perLevel, talentBonusPercent);
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
