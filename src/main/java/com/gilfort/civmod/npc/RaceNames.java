package com.gilfort.civmod.npc;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * Name lists per race, loaded from {@code data/<namespace>/civmod_names/<race>.json}
 * ({@code {"npc": [...], "settlement": [...]}}). Files of the same race from all datapacks are merged, so packs
 * can extend existing races or add new ones (vault {@code Systems/NPC Population.md}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class RaceNames extends SimplePreparableReloadListener<Map<Identifier, RaceNames.NameSet>> {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("race_names");

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_names");
    private static Map<Identifier, NameSet> names = Map.of();

    /**
     * Names of one race.
     *
     * @param npc        person names
     * @param settlement settlement names
     */
    public record NameSet(List<String> npc, List<String> settlement) {

        /** JSON codec. */
        public static final Codec<NameSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().optionalFieldOf("npc", List.of()).forGetter(NameSet::npc),
                Codec.STRING.listOf().optionalFieldOf("settlement", List.of()).forGetter(NameSet::settlement)
        ).apply(instance, NameSet::new));

        /** Empty name set. */
        public static final NameSet EMPTY = new NameSet(List.of(), List.of());
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new RaceNames());
    }

    /**
     * @param race the race id
     * @return the names of that race (empty if none are defined)
     */
    public static NameSet get(Identifier race) {
        return names.getOrDefault(race, NameSet.EMPTY);
    }

    /**
     * Picks a random name, avoiding the used ones while alternatives exist.
     *
     * @param candidates the available names
     * @param used       names already in use
     * @param random     the random source
     * @return a name, or empty if there are no candidates
     */
    public static Optional<String> pick(List<String> candidates, Collection<String> used, RandomSource random) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<String> unused = candidates.stream().filter(name -> !used.contains(name)).toList();
        List<String> pool = unused.isEmpty() ? candidates : unused;
        return Optional.of(pool.get(random.nextInt(pool.size())));
    }

    @Override
    protected Map<Identifier, NameSet> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<Identifier, NameSet> result = new HashMap<>();
        for (Map.Entry<Identifier, List<Resource>> entry : FILES.listMatchingResourceStacks(manager).entrySet()) {
            Identifier race = FILES.fileToId(entry.getKey());
            List<String> npc = new ArrayList<>();
            List<String> settlement = new ArrayList<>();
            for (Resource resource : entry.getValue()) {
                try (Reader reader = resource.openAsReader()) {
                    NameSet set = NameSet.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
                    set.npc().stream().filter(name -> !npc.contains(name)).forEach(npc::add);
                    set.settlement().stream().filter(name -> !settlement.contains(name)).forEach(settlement::add);
                } catch (Exception e) {
                    CivMod.LOGGER.error("Failed to load names {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                }
            }
            result.put(race, new NameSet(List.copyOf(npc), List.copyOf(settlement)));
        }
        return result;
    }

    @Override
    protected void apply(Map<Identifier, NameSet> prepared, ResourceManager manager, ProfilerFiller profiler) {
        names = Map.copyOf(prepared);
        CivMod.LOGGER.info("Loaded name lists for {} race(s)", names.size());
    }
}
