package com.gilfort.civmod.npc;

import java.util.Optional;

import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.inn.Hiring;
import com.gilfort.civmod.job.PlayerTalk;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * What a player's right-click with the main hand does to a settler: food in the hand feeds it, a piece of clothing in the hand puts it on,
 * Shift with an empty hand talks to it ({@link PlayerTalk}), anything else opens its window. A visitor of an inn ignores food, is hired with the price stack
 * ({@link Hiring}) and is asked to stay longer in its window.
 */
final class SettlerInteraction {

    private SettlerInteraction() {
    }

    /**
     * @param settler the settler that was clicked
     * @param player  the player
     * @param held    the stack in the player's main hand
     */
    static void onRightClick(Settler settler, ServerPlayer player, ItemStack held) {
        if (settler.isVisitor()) {
            // The price in the hand hires the visitor; it eats at the Pantry and nowhere else, so food does nothing.
            if (!held.isEmpty() && Hiring.hire(player, settler, held) != Hiring.Outcome.WRONG_ITEM) {
                return;
            }
        } else if (SettlerFood.isEdible(held)) {
            feed(settler, player, held);
            return;
        }
        if (dress(settler, player, held)) {
            return;
        }
        // A settler that does not talk (asleep) opens its window like on a plain right-click.
        if (!(player.isShiftKeyDown() && held.isEmpty() && PlayerTalk.talk(settler, player).isPresent())) {
            settler.openWindow(player, Optional.empty());
        }
    }

    /** A piece of clothing in the hand goes into its slot and the piece worn there goes back to the player. */
    private static boolean dress(Settler settler, ServerPlayer player, ItemStack held) {
        Optional<ItemStack> old = settler.getClothing().equip(held.copyWithCount(1));
        if (old.isEmpty()) {
            return false;
        }
        held.consume(1, player);
        if (!old.get().isEmpty() && !player.getInventory().add(old.get())) {
            player.spawnAtLocation(player.level(), old.get());
        }
        return true;
    }

    private static void feed(Settler settler, ServerPlayer player, ItemStack held) {
        ItemStack one = held.copyWithCount(1);
        ItemStack rest = settler.getWork().feed(player.level(), one);
        if (ItemStack.matches(rest, one)) {
            player.sendOverlayMessage(Component.translatable("civmod.settler.full", settler.getName()));
            return;
        }
        held.consume(1, player);
        if (!rest.isEmpty() && !ItemStack.isSameItemSameComponents(rest, one) && !player.getInventory().add(rest)) {
            player.spawnAtLocation(player.level(), rest);
        }
    }
}
