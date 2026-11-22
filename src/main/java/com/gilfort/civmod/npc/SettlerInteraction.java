package com.gilfort.civmod.npc;

import java.util.Optional;

import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.inn.Visitors;
import com.gilfort.civmod.job.PlayerTalk;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * What a player's right-click with the main hand does to a settler: food in the hand feeds it, Shift with an empty hand talks to it
 * ({@link PlayerTalk}), anything else opens its window. A visitor of an inn is asked to stay longer by Shift with an empty hand
 * instead (vault {@code Systems/NPC Population.md}).
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
            visitorClick(settler, player, held);
            return;
        }
        if (SettlerFood.isEdible(held)) {
            feed(settler, player, held);
            return;
        }
        // A settler that does not talk (asleep) opens its window like on a plain right-click.
        if (!(player.isShiftKeyDown() && held.isEmpty() && PlayerTalk.talk(settler, player).isPresent())) {
            settler.openWindow(player, Optional.empty());
        }
    }

    /**
     * A visitor eats at the Pantry and nowhere else, so food in the hand does nothing; Shift with an empty hand asks it to stay
     * longer, anything else opens its window.
     */
    private static void visitorClick(Settler visitor, ServerPlayer player, ItemStack held) {
        if (!(player.isShiftKeyDown() && held.isEmpty())) {
            visitor.openWindow(player, Optional.empty());
            return;
        }
        Visitors.Ask result = Visitors.askToStay(player.level(), visitor);
        String key = switch (result) {
            case EXTENDED -> "civmod.visitor.stay.extended";
            case DECLINED, NO_STAY -> "civmod.visitor.stay.declined";
            case ALREADY_ASKED -> "civmod.visitor.stay.already_asked";
        };
        player.sendOverlayMessage(Component.translatable(key, visitor.getName()));
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
