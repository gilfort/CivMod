package com.gilfort.civmod.origin;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.origin.RaceDefinition.NameList;
import com.gilfort.civmod.origin.RaceDefinition.SurnameRule;
import com.gilfort.civmod.registry.ModJobs;
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
 * The origin definitions of all races (vault {@code Systems/Settler Origin.md}), loaded from three datapack folders and merged over
 * all packs, so packs can extend a race or add a new one:
 * <ul>
 * <li>{@code civmod_races/<race>.json}: the first-name list per name culture, the surname rules with their weights and the
 * table of each slot ({@code place}, {@code upbringing}, {@code turning_point}, {@code reason}).</li>
 * <li>{@code civmod_origin_tables/<table>.json}: {@code {"entries": [...]}}, see {@link OriginEntry}. An entry of a later pack
 * replaces the entry with the same id.</li>
 * <li>{@code civmod_first_names/<culture list>.json}: {@code {"male": [...], "female": [...], "unisex": [...]}}.</li>
 * </ul>
 * Entries that break the rules of their slot are logged and left out. The data is server only: the client builds every text
 * from language keys.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class OriginData extends SimplePreparableReloadListener<Map<Identifier, RaceDefinition>> {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("origins");
    /** The name culture a race falls back to. */
    public static final String DEFAULT_CULTURE = "en";

    private static final FileToIdConverter RACE_FILES = FileToIdConverter.json("civmod_races");
    private static final FileToIdConverter TABLE_FILES = FileToIdConverter.json("civmod_origin_tables");
    private static final FileToIdConverter NAME_FILES = FileToIdConverter.json("civmod_first_names");
    private static final int MAX_FAVOURED_JOBS = 2;
    private static final Codec<List<OriginEntry>> TABLE = OriginEntry.CODEC.listOf().fieldOf("entries").codec();
    private static final Set<String> warnedCultures = new HashSet<>();
    private static Map<Identifier, RaceDefinition> races = Map.of();

    /**
     * What a race file holds before the tables it names are resolved.
     *
     * @param firstNames the first-name list per name culture
     * @param surnames   the surname rules
     * @param tables     the table per slot
     */
    private record RaceFile(Map<String, Identifier> firstNames, List<SurnameRule> surnames, Map<String, Identifier> tables) {

        private static final Codec<RaceFile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.unboundedMap(Codec.STRING, Identifier.CODEC).optionalFieldOf("first_names", Map.of()).forGetter(RaceFile::firstNames),
                SurnameRule.CODEC.listOf().optionalFieldOf("surnames", List.of()).forGetter(RaceFile::surnames),
                Codec.unboundedMap(Codec.STRING, Identifier.CODEC).optionalFieldOf("tables", Map.of()).forGetter(RaceFile::tables)
        ).apply(instance, RaceFile::new));

        /** Folds a later file over this one: its names, tables and surname rules of the same key win. */
        RaceFile merge(RaceFile later) {
            Map<String, Identifier> mergedNames = new LinkedHashMap<>(firstNames);
            mergedNames.putAll(later.firstNames);
            Map<String, Identifier> mergedTables = new LinkedHashMap<>(tables);
            mergedTables.putAll(later.tables);
            Map<SurnameType, SurnameRule> rules = new LinkedHashMap<>();
            surnames.forEach(rule -> rules.put(rule.type(), rule));
            later.surnames.forEach(rule -> rules.put(rule.type(), rule));
            return new RaceFile(mergedNames, List.copyOf(rules.values()), mergedTables);
        }
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new OriginData());
    }

    /**
     * @param race the race id
     * @return its origin definition, empty if no pack defines it
     */
    public static Optional<RaceDefinition> race(Identifier race) {
        return Optional.ofNullable(races.get(race));
    }

    /**
     * @return the races that have an origin definition, in a stable order (empty before the first data reload)
     */
    public static List<Identifier> races() {
        return races.keySet().stream().sorted().toList();
    }

    /**
     * @param race    a race
     * @param culture the name culture from the config
     * @return the race's first names for the culture; without a list for it the English one, with a log line the first time
     */
    public static NameList firstNames(RaceDefinition race, String culture) {
        NameList names = race.firstNames().get(culture);
        if (names != null) {
            return names;
        }
        if (warnedCultures.add(race.id() + "/" + culture)) {
            CivMod.LOGGER.warn("Race {} has no first names for the name culture '{}', using '{}'", race.id(), culture, DEFAULT_CULTURE);
        }
        return race.firstNames().getOrDefault(DEFAULT_CULTURE, NameList.EMPTY);
    }

    @Override
    protected Map<Identifier, RaceDefinition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<Identifier, RaceFile> raceFiles = new HashMap<>();
        read(manager, RACE_FILES, RaceFile.CODEC).forEach((race, files) -> raceFiles.put(race, files.stream().reduce(RaceFile::merge).orElseThrow()));
        Map<Identifier, List<OriginEntry>> tables = new HashMap<>();
        read(manager, TABLE_FILES, TABLE).forEach((table, files) -> tables.put(table, mergeEntries(files)));
        Map<Identifier, NameList> names = new HashMap<>();
        read(manager, NAME_FILES, NameList.CODEC).forEach((list, files) -> names.put(list, mergeNames(files)));

        Map<Identifier, RaceDefinition> result = new HashMap<>();
        raceFiles.forEach((race, file) -> result.put(race, resolve(race, file, tables, names)));
        return result;
    }

    @Override
    protected void apply(Map<Identifier, RaceDefinition> prepared, ResourceManager manager, ProfilerFiller profiler) {
        races = Map.copyOf(prepared);
        warnedCultures.clear();
        CivMod.LOGGER.info("Loaded origin definitions for {} race(s)", races.size());
    }

    private static <T> Map<Identifier, List<T>> read(ResourceManager manager, FileToIdConverter files, Codec<T> codec) {
        Map<Identifier, List<T>> result = new HashMap<>();
        for (Map.Entry<Identifier, List<Resource>> entry : files.listMatchingResourceStacks(manager).entrySet()) {
            Identifier id = files.fileToId(entry.getKey());
            for (Resource resource : entry.getValue()) {
                try (Reader reader = resource.openAsReader()) {
                    result.computeIfAbsent(id, key -> new ArrayList<>())
                            .add(codec.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow());
                } catch (Exception e) {
                    CivMod.LOGGER.error("Failed to load origin data {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                }
            }
        }
        return result;
    }

    private static List<OriginEntry> mergeEntries(List<List<OriginEntry>> files) {
        Map<Identifier, OriginEntry> byId = new LinkedHashMap<>();
        files.forEach(entries -> entries.forEach(entry -> byId.put(entry.id(), entry)));
        return List.copyOf(byId.values());
    }

    private static NameList mergeNames(List<NameList> files) {
        return new NameList(distinct(files, NameList::male), distinct(files, NameList::female), distinct(files, NameList::unisex));
    }

    private static List<String> distinct(List<NameList> files, Function<NameList, List<String>> part) {
        return files.stream().flatMap(file -> part.apply(file).stream()).distinct().toList();
    }

    private static RaceDefinition resolve(Identifier race, RaceFile file, Map<Identifier, List<OriginEntry>> tables,
            Map<Identifier, NameList> names) {
        Map<String, NameList> firstNames = new LinkedHashMap<>();
        file.firstNames().forEach((culture, list) -> {
            if (names.containsKey(list)) {
                firstNames.put(culture, names.get(list));
            } else {
                CivMod.LOGGER.error("Race {} names the missing first-name list {} for '{}'", race, list, culture);
            }
        });
        Map<SurnameType, List<OriginEntry>> surnameEntries = new LinkedHashMap<>();
        for (SurnameRule rule : file.surnames()) {
            rule.table().ifPresent(table -> surnameEntries.put(rule.type(), slot(race, "surname " + rule.type().getSerializedName(),
                    tables.get(table), entry -> checkJobs(entry, 0, Integer.MAX_VALUE))));
        }
        return new RaceDefinition(race, Map.copyOf(firstNames), file.surnames(),
                slot(race, "place", table(file, tables, "place"), entry -> entry.biomes().isEmpty() ? "no biomes" : ""),
                slot(race, "upbringing", table(file, tables, "upbringing"), entry -> checkJobs(entry, 1, MAX_FAVOURED_JOBS)),
                slot(race, "turning_point", table(file, tables, "turning_point"), entry -> ""),
                slot(race, "reason", table(file, tables, "reason"), entry -> entry.channels().isEmpty() ? "no channels" : ""),
                Map.copyOf(surnameEntries));
    }

    private static List<OriginEntry> table(RaceFile file, Map<Identifier, List<OriginEntry>> tables, String slot) {
        return tables.get(file.tables().get(slot));
    }

    /** Keeps the entries of a table that follow the rules of their slot; {@code check} returns the problem, or an empty string. */
    private static List<OriginEntry> slot(Identifier race, String slot, List<OriginEntry> entries, Function<OriginEntry, String> check) {
        if (entries == null) {
            CivMod.LOGGER.error("Race {} has no table for '{}'", race, slot);
            return List.of();
        }
        List<OriginEntry> valid = new ArrayList<>();
        for (OriginEntry entry : entries) {
            String problem = check.apply(entry);
            if (problem.isEmpty()) {
                valid.add(entry);
            } else {
                CivMod.LOGGER.error("Race {}: left out {} entry {}: {}", race, slot, entry.id(), problem);
            }
        }
        return List.copyOf(valid);
    }

    /** The problem with the jobs of an entry: each must be a registered Base Job, and there must be {@code minimum} to {@code maximum} of them. */
    private static String checkJobs(OriginEntry entry, int minimum, int maximum) {
        if (entry.jobs().size() < minimum || entry.jobs().size() > maximum) {
            return "needs " + minimum + " to " + maximum + " jobs";
        }
        for (Identifier id : entry.jobs()) {
            Optional<Job> job = ModJobs.REGISTRY.getOptional(id);
            if (job.isEmpty() || job.get().parent().isPresent()) {
                return "'" + id + "' is not a registered Base Job";
            }
        }
        return "";
    }
}
