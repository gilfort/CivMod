package com.gilfort.civmod.block;

import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Using a Ledger on a Clerk JobBlock (vault {@code Jobs/Clerk.md}, decisions of 2026-11-02): each adds 9 active stock
 * rule slots, at most {@value SettlementRules#MAX_LEDGERS} per block, with no settler, skill or level condition.
 */
final class Ledgers {

    private Ledgers() {
    }

    /**
     * Adds a Ledger to the Clerk JobBlock or tells the player why not (action bar); on success the item is used (not with
     * infinite materials).
     *
     * @param level    the dimension
     * @param pos      the Clerk JobBlock position
     * @param jobBlock its block entity
     * @param player   the player clicking
     * @param stack    the Ledger stack
     * @return whether a Ledger was added
     */
    static boolean use(ServerLevel level, BlockPos pos, JobBlockEntity jobBlock, Player player, ItemStack stack) {
        int ledgers = jobBlock.getLedgers();
        if (ledgers >= SettlementRules.MAX_LEDGERS) {
            player.sendOverlayMessage(Component.translatable("civmod.ledger.max", SettlementRules.MAX_LEDGERS).withStyle(ChatFormatting.RED));
            return false;
        }
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        jobBlock.setLedgers(ledgers + 1);
        SettlementWorld.setLedgers(level, pos, ledgers + 1);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 1.0F);
        player.sendOverlayMessage(Component.translatable("civmod.ledger.done", ledgers + 1, SettlementRules.MAX_LEDGERS));
        return true;
    }
}
