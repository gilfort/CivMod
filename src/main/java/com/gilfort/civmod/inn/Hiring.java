package com.gilfort.civmod.inn;

import java.util.Optional;

import com.gilfort.civmod.chronicle.Diary;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerSpawner;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.Visit;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * A player hires a visitor of an inn (vault {@code Systems/NPC Population.md}, section 6): right-click with the price stack. The visitor
 * needs a free village bed (a guest bed does not count) and a settlement below {@code maxSettlersPerSettlement}. Then it takes the
 * price, the same entity becomes a settler of the settlement, moves into the free bed nearest to the Pantry and leaves its guest bed
 * free. If it cannot, it shakes its head, the action bar names the reason and the stack stays with the player. The price is worked
 * out on a click and nothing here ticks.
 */
public final class Hiring {

    /** What a click with a stack on a visitor did. */
    public enum Outcome {
        /** The stack does not hold the item of the price; nothing happened. */
        WRONG_ITEM,
        /** The stack holds fewer items than the price asks for. */
        NOT_ENOUGH,
        /** The settlement is at {@code maxSettlersPerSettlement}. */
        SETTLEMENT_FULL,
        /** No bed of the settlement is free (guest beds do not count). */
        NO_FREE_BED,
        /** The settlement has no visit of this visitor, so there is nobody to hire. */
        NO_VISIT,
        /** The visitor is a settler now. */
        HIRED
    }

    private Hiring() {
    }

    /**
     * The player offers a stack to a visitor.
     *
     * @param player  the player
     * @param visitor the visitor that was clicked
     * @param held    the stack in the player's hand; the price is taken from it when the visitor is hired
     * @return what happened
     */
    public static Outcome hire(ServerPlayer player, Settler visitor, ItemStack held) {
        VisitorOffer offer = VisitorOffer.of(visitor);
        if (!offer.price().isItem(held)) {
            return Outcome.WRONG_ITEM;
        }
        ServerLevel level = player.level();
        SettlementData data = SettlementData.get(level);
        Optional<Settlement> settlement = data.settlement(visitor.getVisitedSettlementId());
        Optional<Visit> visit = settlement.flatMap(found -> found.inn().visit(visitor.getUUID()));
        if (visit.isEmpty()) {
            return refuse(player, visitor, Outcome.NO_VISIT,Component.translatable("civmod.visitor.hire.no_visit", visitor.getName()));
        }
        if (held.getCount() < offer.price().amount()) {
            return refuse(player, visitor, Outcome.NOT_ENOUGH, Component.translatable("civmod.visitor.hire.not_enough", visitor.getName(),
                    offer.price().amount(), offer.price().stack().getItemName()));
        }
        if (SettlerSpawner.isFull(settlement.get())) {
            return refuse(player, visitor, Outcome.SETTLEMENT_FULL, Component.translatable("civmod.visitor.hire.full", visitor.getName()));
        }
        Optional<BlockPos> bed = SettlerBeds.freeBedsInSettlement(level, settlement.get(), visit.get().pantry()).stream().findFirst();
        if (bed.isEmpty()) {
            return refuse(player, visitor, Outcome.NO_FREE_BED, Component.translatable("civmod.visitor.hire.no_bed", visitor.getName()));
        }
        held.consume(offer.price().amount(), player);
        join(level, data, settlement.get(), visitor, bed.get());
        player.sendOverlayMessage(Component.translatable("civmod.visitor.hired", visitor.getName()));
        return Outcome.HIRED;
    }

    /** The visitor ends its visit, which frees its guest bed, and becomes a settler with the bed; its diary says it joined. */
    private static void join(ServerLevel level, SettlementData data, Settlement settlement, Settler visitor, BlockPos bed) {
        data.updateInn(settlement, inn -> inn.withoutVisit(visitor.getUUID()));
        visitor.becomeSettler(data, settlement);
        SettlerBeds.claim(level, data, settlement, visitor.getUUID(), bed);
        Diary.joined(visitor);
    }

    /** As the Villager conversion does: the visitor shakes its head and the action bar names the reason. */
    private static Outcome refuse(ServerPlayer player, Settler visitor, Outcome outcome, Component reason) {
        visitor.makeSound(SoundEvents.VILLAGER_NO);
        player.sendOverlayMessage(reason);
        return outcome;
    }
}
