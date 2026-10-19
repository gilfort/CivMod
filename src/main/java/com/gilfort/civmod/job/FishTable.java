package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The fish a Fisherman can catch (vault {@code Jobs/Fisherman.md}, {@code Systems/Resource Collection Jobs.md}): the
 * items of the server's loaded fishing fish loot table {@code minecraft:gameplay/fishing/fish} (all datapacks merged)
 * with their weights, resolved again after every datapack reload. Item entries count with their weight, the children of
 * composite entries with theirs; a tag entry lists every item of the tag with the entry's weight; a nested table shares
 * the entry's weight among its fish by their own weights. An item listed twice keeps its first entry. A fish roll
 * picks among the fish not excluded at the JobBlock, by weight, and the modifiers of the picked entry (and of the pools
 * and tables around it) apply to the caught item. Conditions and NeoForge global loot modifiers are not evaluated.
 *
 * @param fish the fish with their weights, in table order
 */
public record FishTable(List<Fish> fish) {

    /** The vanilla table, used without a server or when the loaded table holds no fish. */
    public static final FishTable FALLBACK = new FishTable(List.of(new Fish(Items.COD, 60), new Fish(Items.SALMON, 25),
            new Fish(Items.TROPICAL_FISH, 2), new Fish(Items.PUFFERFISH, 13)));

    /** Nested tables deeper than this are not followed (a table that nests itself). */
    private static final int MAX_DEPTH = 8;

    private static ReloadableServerRegistries.@Nullable Holder resolvedFor;
    private static FishTable current = FALLBACK;

    /**
     * One fish of the table.
     *
     * @param item      the item
     * @param weight    its weight in the table
     * @param modifiers the item modifiers of its entry and of the pools and tables around it, innermost first
     */
    public record Fish(Item item, double weight, List<Holder<LootItemFunction>> modifiers) {

        /**
         * @param item      the item
         * @param weight    its weight in the table
         * @param modifiers the item modifiers, innermost first
         */
        public Fish {
            modifiers = List.copyOf(modifiers);
        }

        /**
         * A fish without modifiers.
         *
         * @param item   the item
         * @param weight its weight in the table
         */
        public Fish(Item item, double weight) {
            this(item, weight, List.of());
        }

        /**
         * @return the item id (the key of an exclusion)
         */
        public Identifier id() {
            return BuiltInRegistries.ITEM.getKey(item);
        }

        /**
         * The caught item: one of the fish with the modifiers of its entry applied (count, components).
         *
         * @param level  the level
         * @param origin where the hook is
         * @param tool   the fishing rod
         * @param fisher who fishes, if known
         * @return the caught stack
         */
        public ItemStack caught(ServerLevel level, Vec3 origin, ItemStack tool, @Nullable Entity fisher) {
            ItemStack stack = new ItemStack(item);
            if (modifiers.isEmpty()) {
                return stack;
            }
            LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, origin)
                    .withParameter(LootContextParams.TOOL, tool)
                    .withOptionalParameter(LootContextParams.THIS_ENTITY, fisher)
                    .create(LootContextParamSets.FISHING);
            LootContext context = new LootContext.Builder(params).create(Optional.empty());
            for (Holder<LootItemFunction> modifier : modifiers) {
                stack = modifier.value().apply(stack, context);
            }
            return stack;
        }
    }

    /**
     * @param fish the fish
     */
    public FishTable {
        fish = List.copyOf(fish);
    }

    /**
     * @return the fish table of the running server, resolved once per datapack load ({@link #FALLBACK} without one)
     */
    public static FishTable get() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return FALLBACK;
        }
        // A reload replaces the registries holder: resolve again.
        ReloadableServerRegistries.Holder registries = server.reloadableRegistries();
        if (registries != resolvedFor) {
            resolvedFor = registries;
            current = of(registries);
            CivMod.LOGGER.info("Loaded {} fish from the fishing loot table", current.fish().size());
        }
        return current;
    }

    private static FishTable of(ReloadableServerRegistries.Holder registries) {
        LootTable table = registries.getLootTable(BuiltInLootTables.FISHING_FISH);
        try {
            HolderLookup.Provider lookup = registries.lookup();
            JsonElement json = LootTable.DIRECT_CODEC.encodeStart(lookup.createSerializationContext(JsonOps.INSTANCE), table).getOrThrow();
            FishTable resolved = resolve(lookup, json);
            return resolved.fish().isEmpty() ? FALLBACK : resolved;
        } catch (RuntimeException e) {
            CivMod.LOGGER.error("Failed to read the fishing fish loot table, using the vanilla fish", e);
            return FALLBACK;
        }
    }

    /**
     * Resolves the fish of a loot table in its JSON form: references to item tags and other loot tables are looked up
     * in the registries.
     *
     * @param registries the server's registries (loot tables, item tags, item modifiers)
     * @param table      the loot table JSON
     * @return the fish
     */
    public static FishTable resolve(HolderLookup.Provider registries, JsonElement table) {
        Resolver resolver = new Resolver(registries);
        resolver.table(table, 1.0, true, List.of(), 0);
        return new FishTable(resolver.fish);
    }

    /** Walks a table: its pools, their entries, nested tables and tags. */
    private static final class Resolver {

        private final HolderLookup.Provider registries;
        private final DynamicOps<JsonElement> ops;
        private final List<Fish> fish = new ArrayList<>();

        private Resolver(HolderLookup.Provider registries) {
            this.registries = registries;
            this.ops = registries.createSerializationContext(JsonOps.INSTANCE);
        }

        /**
         * @param share     the weight the whole table has (1 for the top table, whose entry weights count as they are)
         * @param top       whether it is the top table (entry weights not divided by the pool's total)
         * @param modifiers the modifiers around the table, innermost first
         */
        private void table(JsonElement element, double share, boolean top, List<Holder<LootItemFunction>> modifiers, int depth) {
            if (!element.isJsonObject() || depth > MAX_DEPTH) {
                return;
            }
            JsonObject table = element.getAsJsonObject();
            List<Holder<LootItemFunction>> around = with(table, modifiers);
            for (JsonElement pool : array(table, "pools")) {
                if (!pool.isJsonObject()) {
                    continue;
                }
                List<Candidate> candidates = new ArrayList<>();
                array(pool.getAsJsonObject(), "entries").forEach(entry -> candidates(entry, with(pool.getAsJsonObject(), around), candidates));
                double total = candidates.stream().mapToDouble(Candidate::weight).sum();
                for (Candidate candidate : candidates) {
                    double weight = top ? candidate.weight() : total <= 0.0 ? 0.0 : share * candidate.weight() / total;
                    candidate.add(this, weight, depth);
                }
            }
        }

        /** The choices an entry offers in its pool: composite entries add their children. */
        private void candidates(JsonElement element, List<Holder<LootItemFunction>> around, List<Candidate> out) {
            if (!element.isJsonObject()) {
                return;
            }
            JsonObject entry = element.getAsJsonObject();
            String type = type(entry);
            int weight = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
            List<Holder<LootItemFunction>> modifiers = with(entry, around);
            switch (type) {
                case "alternatives", "group", "sequence" -> array(entry, "children").forEach(child -> candidates(child, modifiers, out));
                case "item" -> item(entry.has("name") ? entry.get("name").getAsString() : "")
                        .ifPresent(item -> out.add(new Candidate(weight, List.of(item), null, modifiers)));
                case "tag" -> {
                    List<Item> items = items(entry.get("items"));
                    boolean expand = entry.has("expand") && entry.get("expand").getAsBoolean();
                    if (expand) {
                        items.forEach(item -> out.add(new Candidate(weight, List.of(item), null, modifiers)));
                    } else if (!items.isEmpty()) {
                        // All items at once: listed with an equal share of the entry's weight.
                        out.add(new Candidate(weight, items, null, modifiers));
                    }
                }
                case "loot_table" -> {
                    List<JsonElement> tables = tables(entry.get("value"));
                    boolean expand = entry.has("expand") && entry.get("expand").getAsBoolean();
                    if (expand) {
                        tables.forEach(table -> out.add(new Candidate(weight, List.of(), List.of(table), modifiers)));
                    } else if (!tables.isEmpty()) {
                        out.add(new Candidate(weight, List.of(), tables, modifiers));
                    }
                }
                default -> {
                    // Empty, dynamic and slot entries hold no fish.
                }
            }
        }

        private void addFish(Item item, double weight, List<Holder<LootItemFunction>> modifiers) {
            if (weight > 0.0 && fish.stream().noneMatch(known -> known.item() == item)) {
                fish.add(new Fish(item, weight, modifiers));
            }
        }

        private static String type(JsonObject entry) {
            String type = entry.has("type") ? entry.get("type").getAsString() : "";
            return type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
        }

        private static List<JsonElement> array(JsonObject object, String key) {
            if (!object.has(key) || !object.get(key).isJsonArray()) {
                return List.of();
            }
            List<JsonElement> elements = new ArrayList<>();
            object.getAsJsonArray(key).forEach(elements::add);
            return elements;
        }

        /** The modifiers around plus the object's own one (applied first). */
        private List<Holder<LootItemFunction>> with(JsonObject object, List<Holder<LootItemFunction>> around) {
            if (!object.has("modifier")) {
                return around;
            }
            Optional<Holder<LootItemFunction>> own = LootItemFunctions.CODEC.parse(ops, object.get("modifier")).resultOrPartial(CivMod.LOGGER::warn);
            if (own.isEmpty()) {
                return around;
            }
            List<Holder<LootItemFunction>> modifiers = new ArrayList<>();
            modifiers.add(own.get());
            modifiers.addAll(around);
            return modifiers;
        }

        private static Optional<Item> item(String name) {
            Identifier id = Identifier.tryParse(name);
            return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id).filter(found -> !found.equals(Items.AIR));
        }

        /** The items of a tag entry: {@code "#tag"}, one id or a list of ids. */
        private List<Item> items(@Nullable JsonElement value) {
            List<Item> items = new ArrayList<>();
            for (String name : names(value)) {
                if (name.startsWith("#")) {
                    Identifier id = Identifier.tryParse(name.substring(1));
                    Optional<HolderSet.Named<Item>> tag = id == null ? Optional.empty()
                            : registries.lookupOrThrow(Registries.ITEM).get(TagKey.create(Registries.ITEM, id));
                    tag.ifPresent(set -> set.forEach(holder -> items.add(holder.value())));
                } else {
                    item(name).ifPresent(items::add);
                }
            }
            return items;
        }

        /** The tables of a nested entry: inline tables, ids or a loot table tag, as JSON. */
        private List<JsonElement> tables(@Nullable JsonElement value) {
            List<JsonElement> tables = new ArrayList<>();
            if (value == null) {
                return tables;
            }
            List<JsonElement> parts = new ArrayList<>();
            if (value.isJsonArray()) {
                value.getAsJsonArray().forEach(parts::add);
            } else {
                parts.add(value);
            }
            HolderLookup.RegistryLookup<LootTable> lookup = registries.lookupOrThrow(Registries.LOOT_TABLE);
            for (JsonElement part : parts) {
                if (part.isJsonObject()) {
                    tables.add(part);
                    continue;
                }
                String name = part.getAsString();
                List<LootTable> found = new ArrayList<>();
                if (name.startsWith("#")) {
                    Identifier id = Identifier.tryParse(name.substring(1));
                    Optional<HolderSet.Named<LootTable>> tag = id == null ? Optional.empty()
                            : lookup.get(TagKey.create(Registries.LOOT_TABLE, id));
                    tag.ifPresent(set -> set.forEach(holder -> found.add(holder.value())));
                } else {
                    Identifier id = Identifier.tryParse(name);
                    if (id != null) {
                        lookup.get(ResourceKey.create(Registries.LOOT_TABLE, id)).ifPresent(holder -> found.add(holder.value()));
                    }
                }
                found.forEach(table -> LootTable.DIRECT_CODEC.encodeStart(ops, table).resultOrPartial(CivMod.LOGGER::warn).ifPresent(tables::add));
            }
            return tables;
        }

        private static List<String> names(@Nullable JsonElement value) {
            List<String> names = new ArrayList<>();
            if (value == null) {
                return names;
            }
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                array.forEach(element -> names.add(element.getAsString()));
            } else if (value.isJsonPrimitive()) {
                names.add(value.getAsString());
            }
            return names;
        }
    }

    /**
     * One choice of a pool: items (one, or several at once) or nested tables (one, or several at once).
     *
     * @param weight    its weight in the pool
     * @param items     the items it gives
     * @param tables    the nested tables it rolls, null for items
     * @param modifiers its modifiers and those around it, innermost first
     */
    private record Candidate(int weight, List<Item> items, @Nullable List<JsonElement> tables, List<Holder<LootItemFunction>> modifiers) {

        private void add(Resolver resolver, double share, int depth) {
            if (tables != null) {
                tables.forEach(table -> resolver.table(table, share / tables.size(), false, modifiers, depth + 1));
            } else {
                items.forEach(item -> resolver.addFish(item, share / items.size(), modifiers));
            }
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
    public Optional<Fish> pick(Set<Identifier> excluded, RandomSource random) {
        List<Fish> enabled = fish.stream().filter(entry -> !excluded.contains(entry.id())).toList();
        double total = enabled.stream().mapToDouble(Fish::weight).sum();
        if (total <= 0.0) {
            return Optional.empty();
        }
        double roll = random.nextDouble() * total;
        for (Fish entry : enabled) {
            roll -= entry.weight();
            if (roll < 0.0) {
                return Optional.of(entry);
            }
        }
        return Optional.of(enabled.getLast());
    }
}
