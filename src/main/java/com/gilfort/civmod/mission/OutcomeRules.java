package com.gilfort.civmod.mission;

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

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
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
 * Constants of the mission outcome formula and the mission EXP (vault {@code Systems/Mission Jobs.md}) from
 * {@code data/<namespace>/civmod_mission/outcome.json}; files of all packs are merged, later values override, missing
 * values keep the {@link #FALLBACK}. The arithmetic lives in {@link MissionRules}.
 *
 * @param successBase     success chance at gap 0
 * @param successPerLevel success chance lost per level of gap
 * @param successMin      lowest success chance
 * @param successMax      highest success chance
 * @param deathBase       death chance at gap 0
 * @param deathPerLevel   death chance gained per level of gap
 * @param deathMin        lowest death chance
 * @param deathMax        highest death chance
 * @param successExp      EXP multiplier on success
 * @param failedExp       EXP multiplier on failure
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record OutcomeRules(double successBase, double successPerLevel, double successMin, double successMax, double deathBase,
        double deathPerLevel, double deathMin, double deathMax, double successExp, double failedExp) {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("mission_outcome");
    /** Values used when no file is loaded. */
    public static final OutcomeRules FALLBACK = new OutcomeRules(0.75, 0.02, 0.05, 0.95, 0.03, 0.015, 0.01, 0.60, 2.0, 0.8);

    /** Network codec (the board GUI computes the chances on the client). */
    public static final StreamCodec<ByteBuf, OutcomeRules> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, OutcomeRules::successBase,
            ByteBufCodecs.DOUBLE, OutcomeRules::successPerLevel,
            ByteBufCodecs.DOUBLE, OutcomeRules::successMin,
            ByteBufCodecs.DOUBLE, OutcomeRules::successMax,
            ByteBufCodecs.DOUBLE, OutcomeRules::deathBase,
            ByteBufCodecs.DOUBLE, OutcomeRules::deathPerLevel,
            ByteBufCodecs.DOUBLE, OutcomeRules::deathMin,
            ByteBufCodecs.DOUBLE, OutcomeRules::deathMax,
            ByteBufCodecs.DOUBLE, OutcomeRules::successExp,
            ByteBufCodecs.DOUBLE, OutcomeRules::failedExp,
            OutcomeRules::new);

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_mission");
    private static OutcomeRules current = FALLBACK;

    private record Part(Optional<Double> successBase, Optional<Double> successPerLevel, Optional<Double> successMin,
            Optional<Double> successMax, Optional<Double> deathBase, Optional<Double> deathPerLevel, Optional<Double> deathMin,
            Optional<Double> deathMax, Optional<Double> successExp, Optional<Double> failedExp) {

        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.optionalFieldOf("success_base").forGetter(Part::successBase),
                Codec.DOUBLE.optionalFieldOf("success_per_level").forGetter(Part::successPerLevel),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("success_min").forGetter(Part::successMin),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("success_max").forGetter(Part::successMax),
                Codec.DOUBLE.optionalFieldOf("death_base").forGetter(Part::deathBase),
                Codec.DOUBLE.optionalFieldOf("death_per_level").forGetter(Part::deathPerLevel),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("death_min").forGetter(Part::deathMin),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("death_max").forGetter(Part::deathMax),
                Codec.doubleRange(0.0, 100.0).optionalFieldOf("success_exp").forGetter(Part::successExp),
                Codec.doubleRange(0.0, 100.0).optionalFieldOf("failed_exp").forGetter(Part::failedExp)
        ).apply(instance, Part::new));
    }

    /**
     * @return the loaded rules
     */
    public static OutcomeRules get() {
        return current;
    }

    /**
     * Merges JSON files in order (later values override).
     *
     * @param files parsed JSON files
     * @return the merged rules
     */
    public static OutcomeRules merge(List<JsonElement> files) {
        OutcomeRules merged = FALLBACK;
        for (JsonElement file : files) {
            Part part = Part.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            merged = new OutcomeRules(part.successBase().orElse(merged.successBase()), part.successPerLevel().orElse(merged.successPerLevel()),
                    part.successMin().orElse(merged.successMin()), part.successMax().orElse(merged.successMax()),
                    part.deathBase().orElse(merged.deathBase()), part.deathPerLevel().orElse(merged.deathPerLevel()),
                    part.deathMin().orElse(merged.deathMin()), part.deathMax().orElse(merged.deathMax()),
                    part.successExp().orElse(merged.successExp()), part.failedExp().orElse(merged.failedExp()));
        }
        return merged;
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<OutcomeRules> {

        @Override
        protected OutcomeRules prepare(ResourceManager manager, ProfilerFiller profiler) {
            List<JsonElement> files = new ArrayList<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream()
                    .filter(entry -> FILES.fileToId(entry.getKey()).getPath().equals("outcome"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        for (Resource resource : entry.getValue()) {
                            try (Reader reader = resource.openAsReader()) {
                                files.add(JsonParser.parseReader(reader));
                            } catch (Exception e) {
                                CivMod.LOGGER.error("Failed to read mission outcome rules {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                            }
                        }
                    });
            try {
                return merge(files);
            } catch (Exception e) {
                CivMod.LOGGER.error("Invalid mission outcome rules, using fallback values", e);
                return FALLBACK;
            }
        }

        @Override
        protected void apply(OutcomeRules prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = prepared;
        }
    }
}
