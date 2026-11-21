package com.gilfort.civmod.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Spawner;

/**
 * The Settler spawn egg (vault {@code Systems/NPC Population.md}): spawns one homeless Settler with a random race, skin and
 * name ({@code SettlerSpawner.initializeEggSettler}). Unlike vanilla eggs it never turns a monster spawner into a Settler
 * spawner.
 */
public final class SettlerSpawnEggItem extends SpawnEggItem {

    /**
     * @param properties the item properties, with the Settler entity data ({@link Item.Properties#spawnEgg})
     */
    public SettlerSpawnEggItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().getBlockEntity(context.getClickedPos()) instanceof Spawner) {
            return InteractionResult.PASS;
        }
        return super.useOn(context);
    }
}
