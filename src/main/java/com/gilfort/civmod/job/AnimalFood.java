package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * The breeding food of an animal type (vault {@code Jobs/Animal Farmer.md}, decisions of 2026-11-15). The animal itself
 * says what feeds it ({@link Animal#isFood}, the test breeding uses), so a modded animal needs no datapack entry. The foods
 * are worked out once at a live animal and kept per entity type until the tags reload.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
final class AnimalFood {

    /** What every loot roll costs: the amount that breeds a new animal. */
    static final int PER_ROLL = 2;

    /** The foods of each entity type in registry order. The tag reload event may come from the client thread. */
    private static final Map<EntityType<?>, List<Item>> FOODS = new ConcurrentHashMap<>();

    private AnimalFood() {
    }

    /**
     * @param type   the animal type
     * @param sample a live animal of the type, asked only when the foods are not known yet
     * @return the items that feed the type, in registry order (empty when nothing does); not present while the foods are
     *         unknown and no animal of the type is there
     */
    static Optional<List<Item>> of(EntityType<?> type, Supplier<Optional<Animal>> sample) {
        List<Item> known = FOODS.get(type);
        if (known != null) {
            return Optional.of(known);
        }
        Optional<List<Item>> worked = sample.get().map(AnimalFood::workOut);
        worked.ifPresent(foods -> FOODS.put(type, foods));
        return worked;
    }

    private static List<Item> workOut(Animal animal) {
        List<Item> foods = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR && animal.isFood(new ItemStack(item))) {
                foods.add(item);
            }
        }
        return Collections.unmodifiableList(foods);
    }

    /**
     * @param item an item
     * @return whether it feeds an animal type whose foods are known
     */
    static boolean isFood(Item item) {
        return FOODS.values().stream().anyMatch(foods -> foods.contains(item));
    }

    /** A tag reload can change what feeds an animal: the foods are worked out again at the next roll. */
    @SubscribeEvent
    static void onTagsUpdated(TagsUpdatedEvent event) {
        FOODS.clear();
    }
}
