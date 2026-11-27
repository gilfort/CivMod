package com.gilfort.civmod.food;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The drinks of the Tavern (vault {@code Systems/Entertainment.md}, Tavern drinks): the items of the tag
 * {@code civmod:tavern_drinks}, beer by default. A Pantry takes them like food, a guest fetches one at the counter and sips it
 * at its seat. A drink never counts as food ({@link SettlerFood#isEdible}). Other mods' drinks join by the tag.
 */
public final class TavernDrinks {

    /** The public tag of the drinks a Pantry takes and a guest orders. */
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, CivMod.id("tavern_drinks"));

    private TavernDrinks() {
    }

    /**
     * @param stack a stack
     * @return whether it is a drink of the Tavern
     */
    public static boolean is(ItemStack stack) {
        return stack.is(TAG);
    }
}
