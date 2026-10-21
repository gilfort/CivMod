package com.gilfort.civmod.job;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.api.BiomePosition;
import com.gilfort.civmod.api.CategoryProfile;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * Biome bonuses (vault {@code Systems/JobBlock.md}, decisions on biome bonuses): one datapack file per bonus at
 * {@code data/<namespace>/civmod_biome_bonuses/<name>.json}, and the single place that applies the combining rules
 * ({@link #resolve}) and the biome position of a Job ({@link #today}).
 * <p>
 * A file names its Jobs as an exact list (no inheritance to Specializations), its biomes as a biome id, a list or a
 * {@code #tag}, and optional effects: {@code speed} and {@code rare_chance} (factors 0.1–10) and {@code double_drop}
 * (entries of {@code items}, an item id, list or {@code #item tag}, and {@code chance}, −1 … 10). A pack overrides a
 * single file by shipping the same id; an invalid file is logged and ignored.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BiomeBonuses {

    /** Reload listener id. */
    private static final Identifier ID = CivMod.id("biome_bonuses");

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_biome_bonuses");
    private static final Codec<List<ExtraCodecs.TagOrElementLocation>> IDS_OR_TAGS = ExtraCodecs.compactListCodec(ExtraCodecs.TAG_OR_ELEMENT_ID,
            ExtraCodecs.nonEmptyList(ExtraCodecs.TAG_OR_ELEMENT_ID.listOf()));
    private static final Codec<Double> FACTOR = Codec.doubleRange(0.1, 10.0);
    private static Map<Identifier, BonusFile> loaded = Map.of();

    private BiomeBonuses() {
    }

    /**
     * One {@code double_drop} entry.
     *
     * @param items  item ids and {@code #item tags}
     * @param chance the chance (−1 … 10)
     */
    public record DoubleDropEntry(List<ExtraCodecs.TagOrElementLocation> items, double chance) {

        static final Codec<DoubleDropEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                IDS_OR_TAGS.fieldOf("items").forGetter(DoubleDropEntry::items),
                Codec.doubleRange(-1.0, 10.0).fieldOf("chance").forGetter(DoubleDropEntry::chance)
        ).apply(instance, DoubleDropEntry::new));
    }

    /**
     * One bonus file.
     *
     * @param jobs       the Jobs it acts on (exact list)
     * @param biomes     the biome ids and {@code #biome tags} it matches
     * @param speed      the factor on the work speed
     * @param rareChance the factor on the rare group's chance
     * @param doubleDrop the double drop entries
     */
    public record BonusFile(List<Job> jobs, List<ExtraCodecs.TagOrElementLocation> biomes, Optional<Double> speed, Optional<Double> rareChance,
            List<DoubleDropEntry> doubleDrop) {

        static final Codec<BonusFile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ExtraCodecs.nonEmptyList(Job.CODEC.listOf()).fieldOf("jobs").forGetter(BonusFile::jobs),
                IDS_OR_TAGS.fieldOf("biomes").forGetter(BonusFile::biomes),
                FACTOR.optionalFieldOf("speed").forGetter(BonusFile::speed),
                FACTOR.optionalFieldOf("rare_chance").forGetter(BonusFile::rareChance),
                DoubleDropEntry.CODEC.listOf().optionalFieldOf("double_drop", List.of()).forGetter(BonusFile::doubleDrop)
        ).apply(instance, BonusFile::new));

        /**
         * @param biome a biome
         * @return whether the file matches it
         */
        public boolean matches(Holder<Biome> biome) {
            return BiomeBonuses.matches(biomes, biome, Registries.BIOME);
        }
    }

    /**
     * The match of a file's {@code biomes} and a {@code double_drop} entry's {@code items}.
     *
     * @param <T>      the registry's element type
     * @param entries  ids and {@code #tags}
     * @param holder   a biome or an item
     * @param registry the registry the ids and tags belong to
     * @return whether one entry names the holder or a tag it is in
     */
    public static <T> boolean matches(List<ExtraCodecs.TagOrElementLocation> entries, Holder<T> holder, ResourceKey<? extends Registry<T>> registry) {
        for (ExtraCodecs.TagOrElementLocation entry : entries) {
            if (entry.tag() ? holder.is(TagKey.create(registry, entry.id())) : holder.is(entry.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param json a bonus file
     * @return the parsed file, or the error that makes it invalid
     */
    public static DataResult<BonusFile> parse(JsonElement json) {
        return BonusFile.CODEC.parse(JsonOps.INSTANCE, json);
    }

    /**
     * @return the loaded bonus files by id
     */
    public static Map<Identifier, BonusFile> files() {
        return loaded;
    }

    /**
     * The bonuses the loaded files give a Job at a biome.
     *
     * @param job   the Job
     * @param biome the biome
     * @return the resolved bonuses (with the biome, even without an effect)
     */
    public static BiomeBonus resolve(Job job, Holder<Biome> biome) {
        boolean rareGroup = job.profile(ResourceCollectionProfile.class).flatMap(ResourceCollectionProfile::rareGroup).isPresent();
        double speed = 1.0;
        double rare = 1.0;
        List<Identifier> speedSources = new ArrayList<>();
        List<Identifier> rareSources = new ArrayList<>();
        List<BiomeBonus.DoubleDrop> doubleDrops = new ArrayList<>();
        for (Map.Entry<Identifier, BonusFile> entry : loaded.entrySet()) {
            BonusFile file = entry.getValue();
            if (!file.jobs().contains(job) || !file.matches(biome)) {
                continue;
            }
            if (file.speed().isPresent()) {
                speed *= file.speed().get();
                speedSources.add(entry.getKey());
            }
            if (rareGroup && file.rareChance().isPresent()) {
                rare *= file.rareChance().get();
                rareSources.add(entry.getKey());
            }
            file.doubleDrop().forEach(drop -> doubleDrops.add(new BiomeBonus.DoubleDrop(drop.items(), drop.chance(), entry.getKey())));
        }
        return new BiomeBonus(biome.unwrapKey(), new BiomeBonus.Factor(speed, speedSources), new BiomeBonus.Factor(rare, rareSources), doubleDrops);
    }

    /**
     * @param level the level
     * @param job   the Job
     * @param pos   a position
     * @return the bonuses the Job gets at the biome of that position
     */
    public static BiomeBonus at(ServerLevel level, Job job, BlockPos pos) {
        return resolve(job, level.getBiome(pos));
    }

    /**
     * @param job a Job
     * @return where its biome is read: its profile's choice, without a profile its category's
     */
    public static BiomePosition position(Job job) {
        return job.profile(CategoryProfile.class).map(CategoryProfile::biomePosition).orElseGet(() -> BiomePosition.of(job.category()));
    }

    /**
     * The bonuses acting on a Job at its JobBlock by the Job's biome position.
     *
     * @param level     the level
     * @param job       the Job
     * @param jobBlock  the JobBlock position
     * @param workPlace the work place of the day (station of the day), null if none is chosen
     * @return the bonuses ({@link BiomeBonus#NONE} for a Job without biome position or without a work place)
     */
    public static BiomeBonus today(ServerLevel level, Job job, BlockPos jobBlock, @Nullable BlockPos workPlace) {
        return switch (position(job)) {
            case NONE -> BiomeBonus.NONE;
            case JOB_BLOCK -> at(level, job, jobBlock);
            case WORK_PLACE -> workPlace == null ? BiomeBonus.NONE : at(level, job, workPlace);
        };
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<Map<Identifier, BonusFile>> {

        @Override
        protected Map<Identifier, BonusFile> prepare(ResourceManager manager, ProfilerFiller profiler) {
            // Sorted by id: the order of the sources and of the double drop entries does not depend on the packs.
            Map<Identifier, BonusFile> files = new TreeMap<>();
            // The top pack's file per id: a pack overrides a single bonus by shipping the same id.
            FILES.listMatchingResources(manager).forEach((path, resource) -> {
                Identifier id = FILES.fileToId(path);
                read(id, resource).ifPresent(file -> files.put(id, file));
            });
            return files;
        }

        private static Optional<BonusFile> read(Identifier id, Resource resource) {
            try (Reader reader = resource.openAsReader()) {
                DataResult<BonusFile> parsed = parse(JsonParser.parseReader(reader));
                parsed.error().ifPresent(error -> CivMod.LOGGER.error("Ignoring invalid biome bonus {} from pack {}: {}", id, resource.sourcePackId(),
                        error.message()));
                return parsed.result();
            } catch (Exception e) {
                CivMod.LOGGER.error("Ignoring unreadable biome bonus {} from pack {}", id, resource.sourcePackId(), e);
                return Optional.empty();
            }
        }

        @Override
        protected void apply(Map<Identifier, BonusFile> prepared, ResourceManager manager, ProfilerFiller profiler) {
            // A copy in insertion order keeps the sorted order.
            loaded = Collections.unmodifiableMap(new LinkedHashMap<>(prepared));
            CivMod.LOGGER.info("Loaded {} biome bonus(es)", prepared.size());
        }
    }
}
