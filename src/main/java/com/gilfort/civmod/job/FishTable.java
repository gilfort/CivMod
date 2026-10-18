package com.gilfort.civmod.job;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.CivMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * The fish a Fisherman can catch (vault {@code Jobs/Fisherman.md}, {@code Systems/Resource Collection Jobs.md}): the
 * items of the fishing fish loot table {@code minecraft:gameplay/fishing/fish} with their weights, read on datapack load
 * (modded fish added to the table appear). A fish roll picks among the fish not excluded at the JobBlock, by weight.
 *
 * @param fish the fish with their weights, in table order
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record FishTable(List<Fish> fish) {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("fish_table");
    /** The vanilla table, used when the file cannot be read. */
    public static final FishTable FALLBACK = new FishTable(List.of(new Fish(Items.COD, 60), new Fish(Items.SALMON, 25),
            new Fish(Items.TROPICAL_FISH, 2), new Fish(Items.PUFFERFISH, 13)));

    private static final Identifier FILE = Identifier.withDefaultNamespace("loot_table/gameplay/fishing/fish.json");
    private static FishTable current = FALLBACK;

    /**
     * One fish of the table.
     *
     * @param item   the item
     * @param weight its weight in the table
     */
    public record Fish(Item item, int weight) {

        /**
         * @return the item id (the key of an exclusion)
         */
        public Identifier id() {
            return BuiltInRegistries.ITEM.getKey(item);
        }
    }

    /**
     * @param fish the fish
     */
    public FishTable {
        fish = List.copyOf(fish);
    }

    /**
     * @return the loaded table
     */
    public static FishTable get() {
        return current;
    }

    /**
     * Reads the item entries of a loot table file, also nested ones (alternatives, groups); an entry without a weight
     * weighs 1, items that do not exist are skipped.
     *
     * @param table the loot table JSON
     * @return the fish
     */
    public static FishTable parse(JsonElement table) {
        List<Fish> fish = new ArrayList<>();
        if (table.isJsonObject() && table.getAsJsonObject().has("pools")) {
            for (JsonElement pool : table.getAsJsonObject().getAsJsonArray("pools")) {
                if (pool.isJsonObject() && pool.getAsJsonObject().has("entries")) {
                    pool.getAsJsonObject().getAsJsonArray("entries").forEach(entry -> collect(entry, fish));
                }
            }
        }
        return new FishTable(fish);
    }

    private static void collect(JsonElement element, List<Fish> fish) {
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject entry = element.getAsJsonObject();
        if (entry.has("children")) {
            entry.getAsJsonArray("children").forEach(child -> collect(child, fish));
        }
        String type = entry.has("type") ? entry.get("type").getAsString() : "";
        if (!(type.equals("minecraft:item") || type.equals("item")) || !entry.has("name")) {
            return;
        }
        Identifier id = Identifier.tryParse(entry.get("name").getAsString());
        int weight = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
        Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id).filter(found -> !found.equals(Items.AIR));
        if (item.isPresent() && weight > 0 && fish.stream().noneMatch(known -> known.item().equals(item.get()))) {
            fish.add(new Fish(item.get(), weight));
        }
    }

    /**
     * @return the ids of all fish (a Fisherman's main resources)
     */
    public List<Identifier> ids() {
        return fish.stream().map(Fish::id).toList();
    }

    /**
     * Picks a fish by weight among the enabled ones.
     *
     * @param excluded the fish excluded at the JobBlock
     * @param random   the random source
     * @return the fish, empty if every fish is excluded
     */
    public Optional<Item> pick(Set<Identifier> excluded, RandomSource random) {
        List<Fish> enabled = fish.stream().filter(entry -> !excluded.contains(entry.id())).toList();
        int total = enabled.stream().mapToInt(Fish::weight).sum();
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (Fish entry : enabled) {
            roll -= entry.weight();
            if (roll < 0) {
                return Optional.of(entry.item());
            }
        }
        return Optional.of(enabled.getLast().item());
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<FishTable> {

        @Override
        protected FishTable prepare(ResourceManager manager, ProfilerFiller profiler) {
            Optional<Resource> resource = manager.getResource(FILE);
            if (resource.isEmpty()) {
                return FALLBACK;
            }
            try (Reader reader = resource.get().openAsReader()) {
                FishTable table = parse(JsonParser.parseReader(reader));
                return table.fish().isEmpty() ? FALLBACK : table;
            } catch (Exception e) {
                CivMod.LOGGER.error("Failed to read the fishing fish loot table, using the vanilla fish", e);
                return FALLBACK;
            }
        }

        @Override
        protected void apply(FishTable prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = prepared;
            CivMod.LOGGER.info("Loaded {} fish from the fishing loot table", prepared.fish().size());
        }
    }
}
