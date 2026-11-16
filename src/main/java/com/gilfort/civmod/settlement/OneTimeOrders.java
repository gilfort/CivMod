package com.gilfort.civmod.settlement;

import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Request;

import net.minecraft.core.BlockPos;

/**
 * The Clerk's one-time orders (vault {@code Jobs/Clerk.md}): a request for an item posted by the player instead of a
 * stock rule. It takes no rule slot, has no timeout and stays open until it is delivered to the Clerk or withdrawn; the
 * chain of producers and sub-requests serves it like any other request.
 */
public final class OneTimeOrders {

    /** Most items one order asks for (the amount field takes four digits). */
    public static final int MAX_AMOUNT = 9999;

    private OneTimeOrders() {
    }

    /**
     * Posts a one-time order to a Clerk.
     *
     * @param data   the dimension's settlement data
     * @param clerk  the Clerk JobBlock position
     * @param item   the item (without it a request cannot be made)
     * @param amount the amount, 1 to {@value #MAX_AMOUNT}
     * @return the posted request, empty if the block is no Clerk in a settlement or the item or amount is invalid
     */
    public static Optional<Request> place(SettlementData data, BlockPos clerk, ItemKey item, int amount) {
        if (item.isEmpty() || amount < 1 || amount > MAX_AMOUNT) {
            return Optional.empty();
        }
        return clerkSettlement(data, clerk).map(settlement -> Requests.postOneTime(data, settlement, item, amount, clerk));
    }

    /**
     * Withdraws an open one-time order of a Clerk and its sub-requests (a running craft is finished and delivered).
     *
     * @param data  the dimension's settlement data
     * @param clerk the Clerk JobBlock position
     * @param id    the request id
     * @return whether it was an open one-time order of that Clerk
     */
    public static boolean withdraw(SettlementData data, BlockPos clerk, UUID id) {
        Optional<Settlement> settlement = clerkSettlement(data, clerk);
        if (settlement.isEmpty() || Requests.oneTime(settlement.get(), clerk).stream().noneMatch(order -> order.id().equals(id))) {
            return false;
        }
        Requests.withdraw(data, settlement.get(), id);
        return true;
    }

    private static Optional<Settlement> clerkSettlement(SettlementData data, BlockPos clerk) {
        return data.settlementAt(clerk).filter(settlement -> settlement.block(clerk).filter(ClerkStorage::isClerk).isPresent());
    }
}
