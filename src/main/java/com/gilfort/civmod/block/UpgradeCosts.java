package com.gilfort.civmod.block;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.registry.ModJobs;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * JobBlock upgrade costs (vault {@code Systems/JobBlock.md}) from {@code data/<namespace>/civmod_upgrades/<job>.json} for the
 * job {@code <namespace>:<job>}: per target level (2–5) a list of items or tags with counts. Files of all packs are merged per level.
 * A JobBlock whose job has no file (e.g. an addon that ships none) uses the default costs from
 * {@code data/civmod/civmod_upgrades/default.json}, with one warning per job on every reload.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class UpgradeCosts {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("upgrade_costs");

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_upgrades");
    /** The reserved file id of the default costs ({@code civmod:default} is no job). */
    private static final Identifier DEFAULT_ID = CivMod.id("default");
    private static Loaded loaded = Loaded.EMPTY;

    private UpgradeCosts() {
    }

    /**
     * One cost entry: an item or an item tag with a count.
     *
     * @param item  the item (if no tag)
     * @param tag   the tag (if no item)
     * @param count the count
     */
    public record Cost(Optional<Item> item, Optional<TagKey<Item>> tag, int count) {

        /** JSON codec ({@code {"item": "...", "count": n}} or {@code {"tag": "...", "count": n}}). */
        public static final Codec<Cost> CODEC = RecordCodecBuilder.<Cost>create(instance -> instance.group(
                BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("item").forGetter(Cost::item),
                TagKey.codec(Registries.ITEM).optionalFieldOf("tag").forGetter(Cost::tag),
                Codec.intRange(1, 10000).fieldOf("count").forGetter(Cost::count)
        ).apply(instance, Cost::new)).validate(cost -> cost.item().isPresent() != cost.tag().isPresent()
                ? DataResult.success(cost) : DataResult.error(() -> "A cost needs exactly one of \"item\" or \"tag\""));

        /**
         * @param stack an item stack
         * @return whether it pays this cost
         */
        public boolean matches(ItemStack stack) {
            return !stack.isEmpty() && tag.map(stack::is).orElseGet(() -> item.map(stack::is).orElse(false));
        }

        /**
         * @return an item shown for this cost (first item of the tag)
         */
        public ItemStack icon() {
            Item shown = item.orElseGet(() -> tag.flatMap(key -> BuiltInRegistries.ITEM.get(key))
                    .flatMap(set -> set.stream().findFirst()).map(Holder::value).orElse(Items.BARRIER));
            return new ItemStack(shown, count);
        }
    }

    /**
     * @param job      the job of the JobBlock
     * @param newLevel the level to upgrade to
     * @return the costs (the default costs if the job has no file; empty if the file or the defaults define none for the level)
     */
    public static List<Cost> get(Job job, int newLevel) {
        return loaded.costs().getOrDefault(job, loaded.defaults()).getOrDefault(newLevel, List.of());
    }

    /**
     * @param inventory a player inventory
     * @param costs     the costs
     * @return whether the inventory holds everything
     */
    public static boolean canAfford(Inventory inventory, List<Cost> costs) {
        for (Cost cost : costs) {
            int have = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (cost.matches(stack)) {
                    have += stack.getCount();
                }
            }
            if (have < cost.count()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the costs from an inventory (check {@link #canAfford} first).
     *
     * @param inventory a player inventory
     * @param costs     the costs
     */
    public static void pay(Inventory inventory, List<Cost> costs) {
        for (Cost cost : costs) {
            int missing = cost.count();
            for (int i = 0; i < inventory.getContainerSize() && missing > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (cost.matches(stack)) {
                    int taken = Math.min(missing, stack.getCount());
                    stack.shrink(taken);
                    missing -= taken;
                }
            }
        }
        inventory.setChanged();
    }

    /**
     * Merges files of one job in order (later files replace levels they define).
     *
     * @param files parsed JSON files
     * @return target level → costs
     */
    public static Map<Integer, List<Cost>> merge(List<JsonElement> files) {
        Map<Integer, List<Cost>> merged = new HashMap<>();
        Codec<Map<String, List<Cost>>> codec = Codec.unboundedMap(Codec.STRING, Cost.CODEC.listOf());
        for (JsonElement file : files) {
            codec.parse(JsonOps.INSTANCE, file).getOrThrow().forEach((level, list) -> merged.put(Integer.parseInt(level), List.copyOf(list)));
        }
        return Map.copyOf(merged);
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<Loaded> {

        @Override
        protected Loaded prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<Job, List<JsonElement>> files = new HashMap<>();
            List<JsonElement> defaultFiles = new ArrayList<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                Identifier id = FILES.fileToId(entry.getKey());
                Job job = ModJobs.REGISTRY.getValue(id);
                if (job == null && !id.equals(DEFAULT_ID)) {
                    CivMod.LOGGER.warn("Upgrade costs {} name no known job", entry.getKey());
                    return;
                }
                for (Resource resource : entry.getValue()) {
                    try (Reader reader = resource.openAsReader()) {
                        JsonElement file = JsonParser.parseReader(reader);
                        if (job == null) {
                            defaultFiles.add(file);
                        } else {
                            files.computeIfAbsent(job, key -> new ArrayList<>()).add(file);
                        }
                    } catch (Exception e) {
                        CivMod.LOGGER.error("Failed to read upgrade costs {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                    }
                }
            });
            Map<Job, Map<Integer, List<Cost>>> result = new HashMap<>();
            files.forEach((job, list) -> {
                try {
                    result.put(job, merge(list));
                } catch (Exception e) {
                    CivMod.LOGGER.error("Invalid upgrade costs for {}", job, e);
                }
            });
            Map<Integer, List<Cost>> defaults = Map.of();
            try {
                defaults = merge(defaultFiles);
            } catch (Exception e) {
                CivMod.LOGGER.error("Invalid default upgrade costs {}", DEFAULT_ID, e);
            }
            jobBlockJobs().filter(job -> !result.containsKey(job)).forEach(job -> CivMod.LOGGER.warn(
                    "No upgrade costs for {} (data/{}/civmod_upgrades/{}.json), its JobBlock uses the default costs", job,
                    job.id().getNamespace(), job.id().getPath()));
            return new Loaded(result, defaults);
        }

        @Override
        protected void apply(Loaded prepared, ResourceManager manager, ProfilerFiller profiler) {
            loaded = prepared;
        }

        /** The Jobs offered by a registered JobBlock, CivMod's and addons'. */
        private static Stream<Job> jobBlockJobs() {
            return BuiltInRegistries.BLOCK.stream().filter(JobBlock.class::isInstance).map(block -> ((JobBlock) block).getJobType()).distinct();
        }
    }

    /**
     * The loaded costs.
     *
     * @param costs    job → target level → costs, for every job with a file
     * @param defaults target level → costs for jobs without a file
     */
    private record Loaded(Map<Job, Map<Integer, List<Cost>>> costs, Map<Integer, List<Cost>> defaults) {

        static final Loaded EMPTY = new Loaded(Map.of(), Map.of());

        Loaded {
            costs = Map.copyOf(costs);
        }
    }
}
