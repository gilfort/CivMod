package com.gilfort.civmod.job;

import java.util.List;

import com.gilfort.civmod.food.FoodVariety;
import com.gilfort.civmod.food.FoodVariety.Diet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The food variety need of a tier (vault {@code Systems/Needs.md}): the settler ate the foods of the tier's {@link Diet}
 * within its window. One {@link Need} per tier that asks for a different diet: 3 foods at tier 2, 5 at tier 4, 10 at tier 5.
 * The favourite meal of tier 5 joins this need later (spec #317, story #322).
 */
final class FoodVarietyNeed implements Need {

    private final int tier;

    /**
     * @param tier the tier the need belongs to
     */
    FoodVarietyNeed(int tier) {
        this.tier = tier;
    }

    @Override
    public int tier() {
        return tier;
    }

    @Override
    public Component name() {
        return Component.translatable("civmod.need.food_variety");
    }

    @Override
    public Item icon() {
        return Items.APPLE;
    }

    @Override
    public Check check(Context context) {
        Diet diet = Diet.forTier(tier);
        FoodVariety variety = context.settler().getFood().variety();
        Component detail = Component.translatable("civmod.need.food_variety.detail", variety.distinct(context.day(), diet), diet.foods(), diet.days());
        List<ItemStack> icons = variety.window(context.day(), diet).stream()
                .flatMap(id -> BuiltInRegistries.ITEM.getOptional(id).stream()).map(ItemStack::new).toList();
        return new Check(!variety.isShort(context.day(), diet), detail, icons);
    }
}
