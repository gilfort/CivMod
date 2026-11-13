package com.gilfort.civmod.menu;

import java.util.Optional;
import java.util.stream.IntStream;

import com.gilfort.civmod.logistics.SlipSites;
import com.gilfort.civmod.settlement.DeliveryOrders;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SlipOrders;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The slots of the Delivery Orders tab on the server: a view of the {@link DeliveryOrders} of a Clerk JobBlock, so the
 * slips live in the settlement data and not in the block entity. Only filled slips go in, one per slot; every change
 * brings the slip tasks of the Clerk up to date ({@link SlipOrders#sync}).
 */
final class OrdersContainer implements Container {

    private final ServerLevel level;
    private final BlockPos pos;

    /**
     * @param level the level
     * @param pos   the Clerk JobBlock
     */
    OrdersContainer(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    private Optional<JobBlockSite> site() {
        return JobBlockSite.at(level, pos);
    }

    @Override
    public int getContainerSize() {
        return DeliveryOrders.MAX_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        return IntStream.range(0, getContainerSize()).allMatch(slot -> getItem(slot).isEmpty());
    }

    @Override
    public ItemStack getItem(int slot) {
        return site().map(found -> found.block().orders().slip(slot)).orElse(ItemStack.EMPTY);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        // A slot holds one slip: taking any of it takes all.
        ItemStack removed = removeItemNoUpdate(slot);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack stack = getItem(slot).copy();
        site().ifPresent(found -> found.block().orders().setSlip(slot, ItemStack.EMPTY));
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        site().ifPresent(found -> found.block().orders().setSlip(slot, stack));
        setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return site().filter(found -> slot < found.block().orderSlots()).isPresent() && DeliveryOrders.slip(stack).isPresent();
    }

    @Override
    public void setChanged() {
        site().ifPresent(found -> {
            SettlementData data = SettlementData.get(level);
            SlipOrders.sync(data, found.settlement(), found.block(), level.getGameTime(), other -> SlipSites.isMissing(level, other));
            data.setDirty();
        });
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        // The slips belong to the Clerk, a closing menu never empties them.
    }
}
