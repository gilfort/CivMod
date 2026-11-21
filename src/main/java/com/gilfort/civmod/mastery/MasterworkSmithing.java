package com.gilfort.civmod.mastery;

import com.gilfort.civmod.CivMod;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * A masterwork upgraded at a smithing table by a player (vault {@code Systems/Producing Jobs.md}). The table builds the result
 * from the base's components, so it still has the old item's durability, tool speed and attributes. NeoForge has no event on
 * the result while it is offered, only when a player takes it, so that is when the bonuses are worked out over the new item:
 * the table's preview shows the old values until then.
 *
 * <p>A click on the result hands the event the stack the player gets. A shift click hands it a copy and puts the real stack
 * into the inventory first, so the inventory is checked as well; only stacks that are stale masterworks change.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MasterworkSmithing {

    private MasterworkSmithing() {
    }

    @SubscribeEvent
    static void onSmithed(PlayerEvent.ItemSmithedEvent event) {
        Player player = event.getEntity();
        Masterwork.refresh(event.getResult());
        Masterwork.refresh(player.containerMenu.getCarried());
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            Masterwork.refresh(inventory.getItem(slot));
        }
    }
}
