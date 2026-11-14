package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Compostable;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.NumberDispatcher;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.level.storage.loot.providers.number.ints.WeightedListValue;

/**
 * The compost chance of an item for the Composter (vault {@code Jobs/Composter.md}), in hundredths of a point: the
 * average layers a vanilla composter adds for it. Vanilla keeps the layers as the item's {@code compostable} component
 * with a value provider (a weighted list of 0 and 1 layers, 30 to 85 percent); the average of that list is the chance.
 * The provider's special case for an empty composter (always one layer) is left out, as it belongs to the block, not to
 * the item. A provider this class cannot average counts as no chance and is logged once.
 * <p>
 * The chances are looked up once per datapack load: the cache is dropped when the recipe set is replaced.
 */
public final class CompostChances {

    /** The recipe set the cache was built for (replaced on every datapack reload). */
    private static @Nullable RecipeMap cachedFrom;
    private static final Map<Item, Integer> CHANCES = new HashMap<>();

    private CompostChances() {
    }

    /**
     * @param level a level
     * @param stack a stack
     * @return the stack's compost chance in hundredths of a point (30 for seeds), 0 if it cannot be composted
     */
    public static int percent(ServerLevel level, ItemStack stack) {
        RecipeMap recipes = level.recipeAccess().recipeMap();
        if (recipes != cachedFrom) {
            CHANCES.clear();
            cachedFrom = recipes;
        }
        if (!stack.has(DataComponents.COMPOSTABLE)) {
            return 0;
        }
        return CHANCES.computeIfAbsent(stack.getItem(), item -> chanceOf(level.getServer().reloadableRegistries().lookup(),stack.get(DataComponents.COMPOSTABLE)));
    }

    private static int chanceOf(HolderLookup.Provider registries, Compostable compostable) {
        OptionalInt average = switch (compostable.layers()) {
            case ResolvableInt.Constant constant -> OptionalInt.of(constant.value() * CompostPoints.HUNDREDTHS);
            case ResolvableInt.Reference reference -> registries.lookupOrThrow(Registries.CONTEXT_INT_PROVIDER).get(reference.key())
                    .map(holder -> average(holder.value())).orElse(OptionalInt.empty());
        };
        if (average.isEmpty()) {
            CivMod.LOGGER.warn("The compost value of {} cannot be averaged: the Composter treats it as no compost", compostable);
        }
        return average.orElse(0);
    }

    /** The average of a value provider in hundredths, empty for a kind of provider that is not handled. */
    private static OptionalInt average(ContextIntProvider provider) {
        return switch (provider) {
            case ConstantValue constant -> OptionalInt.of(constant.value() * CompostPoints.HUNDREDTHS);
            case NumberDispatcher dispatcher -> average(dispatcher.defaultValue().value());
            case WeightedListValue list -> averageOf(list);
            default -> OptionalInt.empty();
        };
    }

    private static OptionalInt averageOf(WeightedListValue list) {
        long weights = 0;
        long weighted = 0;
        for (Weighted<Holder<ContextIntProvider>> entry : list.distribution().unwrap()) {
            OptionalInt part = average(entry.value().value());
            if (part.isEmpty()) {
                return OptionalInt.empty();
            }
            weights += entry.weight();
            weighted += (long) entry.weight() * part.getAsInt();
        }
        return weights == 0 ? OptionalInt.empty() : OptionalInt.of((int) Math.round((double) weighted / weights));
    }
}
