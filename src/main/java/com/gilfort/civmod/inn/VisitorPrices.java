package com.gilfort.civmod.inn;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * What a visitor of an inn asks for to join the settlement (vault {@code Systems/NPC Population.md}, section 6). The price follows
 * the trade: one datapack file per Job at {@code data/<namespace>/civmod_visitor_prices/<job>.json} names an item and a base
 * amount, as in {@code {"item": "minecraft:anvil", "amount": 2}}. The file name is the id of the Job, so a pack overrides an entry by
 * shipping the same id and an addon adds one for its own Job. A Specialization without a file asks what its Base Job asks. A Job
 * without any entry asks for an item of the tag {@code civmod:visitor_price_default} (emeralds by default) in the amount of the
 * {@link InnRules#defaultPriceAmount() default price}. The amount grows with the level the visitor has in its trade
 * ({@link #scaledAmount}); nothing here touches the world.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VisitorPrices {

    /** The items a visitor asks for when its trade has no entry; an addon can add to the tag. */
    private static final TagKey<Item> DEFAULT_TAG = TagKey.create(Registries.ITEM, CivMod.id("visitor_price_default"));

    /** Reload listener id. */
    private static final Identifier ID = CivMod.id("visitor_prices");
    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_visitor_prices");
    /** Every level adds this share of the base amount. */
    private static final int LEVEL_STEPS_PER_BASE = 10;
    /** The most items a price file can ask for. */
    private static final int MAX_BASE_AMOUNT = 64;
    private static Map<Identifier, Entry> loaded = Map.of();

    private VisitorPrices() {
    }

    /**
     * One price file: what a trade asks at level 0.
     *
     * @param item   the item asked for
     * @param amount the base amount (1 to 64)
     */
    public record Entry(Item item, int amount) {

        /** File codec. */
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.ITEM.byNameCodec().validate(item -> item == Items.AIR ? DataResult.error(() -> "A price cannot be air")
                        : DataResult.success(item)).fieldOf("item").forGetter(Entry::item),
                Codec.intRange(1, MAX_BASE_AMOUNT).fieldOf("amount").forGetter(Entry::amount)
        ).apply(instance, Entry::new));
    }

    /**
     * What one visitor asks for.
     *
     * @param item   the item
     * @param amount the amount, at least 1 and at most one stack
     */
    public record Price(Item item, int amount) {

        /**
         * @return the price as one stack
         */
        public ItemStack stack() {
            return new ItemStack(item, amount);
        }

        /**
         * @param held the stack the player holds
         * @return whether the stack holds the item of the price, in any amount
         */
        public boolean isItem(ItemStack held) {
            return held.is(item);
        }
    }

    /**
     * @return the loaded price files by the id of their Job
     */
    public static Map<Identifier, Entry> entries() {
        return loaded;
    }

    /**
     * @param job a Job
     * @return the entry of the Job, or of its Base Job when it is a Specialization without one; empty if neither has an entry
     */
    public static Optional<Entry> entry(Job job) {
        Optional<Job> candidate = Optional.of(job);
        while (candidate.isPresent()) {
            Entry found = loaded.get(candidate.get().id());
            if (found != null) {
                return Optional.of(found);
            }
            candidate = candidate.get().parent();
        }
        return Optional.empty();
    }

    /**
     * The price of a visitor: the entry of its trade at its level, or the default.
     *
     * @param trade   the Job the visitor names as its trade
     * @param level   the visitor's level in that Job
     * @param visitor the visitor's UUID; it picks one item from the default tag, so a visitor always asks for the same
     * @return the price
     */
    public static Price price(Job trade, int level, UUID visitor) {
        Optional<Entry> entry = entry(trade);
        Item item = entry.map(Entry::item).orElseGet(() -> defaultItem(visitor));
        int base = entry.map(Entry::amount).orElseGet(() -> InnRules.get().defaultPriceAmount());
        return new Price(item, scaledAmount(base, level, item.getDefaultMaxStackSize()));
    }

    /**
     * The amount grows by a tenth of the base amount per level: base x (1 + level / 10), rounded half up, at least 1 and at most one
     * stack. It is computed on a talk and on hiring only.
     *
     * @param base     the base amount of the entry
     * @param level    the visitor's level in its trade
     * @param maxStack the stack size of the item
     * @return the amount asked for
     */
    public static int scaledAmount(int base, int level, int maxStack) {
        int rounded = (base * (LEVEL_STEPS_PER_BASE + level) + LEVEL_STEPS_PER_BASE / 2) / LEVEL_STEPS_PER_BASE;
        return Math.max(1, Math.min(rounded, maxStack));
    }

    /** An item of the default tag in the order of its ids, picked by the visitor's UUID; emeralds while the tag is empty. */
    private static Item defaultItem(UUID visitor) {
        List<Item> items = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(DEFAULT_TAG)) {
            items.add(holder.value());
        }
        items.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
        return items.isEmpty() ? Items.EMERALD : items.get(Math.floorMod(visitor.hashCode(), items.size()));
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<Map<Identifier, Entry>> {

        @Override
        protected Map<Identifier, Entry> prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<Identifier, Entry> files = new TreeMap<>();
            // The top pack's file per id: a pack overrides a single price by shipping the same id.
            FILES.listMatchingResources(manager).forEach((path, resource) -> {
                Identifier id = FILES.fileToId(path);
                read(id, resource).ifPresent(entry -> files.put(id, entry));
            });
            return files;
        }

        private static Optional<Entry> read(Identifier id, Resource resource) {
            try (Reader reader = resource.openAsReader()) {
                DataResult<Entry> parsed = Entry.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader));
                parsed.error().ifPresent(error -> CivMod.LOGGER.error("Ignoring invalid visitor price {} from pack {}: {}", id, resource.sourcePackId(),
                        error.message()));
                return parsed.result();
            } catch (Exception e) {
                CivMod.LOGGER.error("Ignoring unreadable visitor price {} from pack {}", id, resource.sourcePackId(), e);
                return Optional.empty();
            }
        }

        @Override
        protected void apply(Map<Identifier, Entry> prepared, ResourceManager manager, ProfilerFiller profiler) {
            loaded = Collections.unmodifiableMap(new LinkedHashMap<>(prepared));
            CivMod.LOGGER.info("Loaded {} visitor price(s)", prepared.size());
        }
    }
}
