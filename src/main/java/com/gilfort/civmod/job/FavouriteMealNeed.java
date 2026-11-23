package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.food.FoodVariety.Diet;
import com.gilfort.civmod.food.SettlerFood;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The favourite meal need of tier 5 (vault {@code Systems/Needs.md}): the settler ate its favourite meal within the window of the tier 5
 * diet, together with the 10 foods of {@link FoodVarietyNeed}. A settler without a favourite meal (its race has no tag, or it was built
 * by hand) is asked for none. While the favourite is hidden the row shows "?" and neither its text nor its icon (also not in the thought
 * bubble) gives it away; it names the meal once the settler told it or ate it.
 */
final class FavouriteMealNeed implements Need {

    /** The tier the need belongs to. */
    static final int TIER = 5;

    @Override
    public int tier() {
        return TIER;
    }

    @Override
    public Component name() {
        return Component.translatable("civmod.need.favourite_meal");
    }

    @Override
    public Item icon() {
        return Items.CAKE;
    }

    @Override
    public Item icon(Context context) {
        SettlerFood food = context.settler().getFood();
        return food.favouriteKnown() ? food.favourite().flatMap(BuiltInRegistries.ITEM::getOptional).orElseGet(this::icon) : icon();
    }

    @Override
    public Check check(Context context) {
        SettlerFood food = context.settler().getFood();
        Optional<Identifier> favourite = food.favourite();
        if (favourite.isEmpty()) {
            return new Check(true, Component.translatable("civmod.need.favourite_meal.none"));
        }
        Diet diet = Diet.forTier(TIER);
        boolean eaten = food.variety().window(context.day(), diet).contains(favourite.get());
        if (!food.favouriteKnown()) {
            return new Check(eaten, Component.translatable("civmod.need.favourite_meal.unknown"));
        }
        ItemStack meal = BuiltInRegistries.ITEM.getOptional(favourite.get()).map(ItemStack::new).orElse(ItemStack.EMPTY);
        Component name = meal.isEmpty() ? Component.literal(favourite.get().getPath()) : meal.getHoverName();
        return new Check(eaten, Component.translatable("civmod.need.favourite_meal." + (eaten ? "met" : "missing"), name, diet.days()),
                meal.isEmpty() ? List.of() : List.of(meal));
    }
}
