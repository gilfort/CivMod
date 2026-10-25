package com.gilfort.civmod.menu;

import java.util.Optional;

import com.gilfort.civmod.settlement.Fuels;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Fuel" tab of a JobBlock whose Job burns fuel (vault {@code Jobs/Smelter.md}): up to {@value Fuels#SLOTS} ghost
 * slots in priority order, shared by every settler of the JobBlock, with the player inventory to pick items from
 * ({@link FuelTabData}, synced while the window is open). The client changes the list with {@link FuelTabData.Edit}.
 */
public final class FuelTab extends JobBlockTab {

    private final TabData<FuelTabData> data = new TabData<>(FuelTabData.EMPTY, (menu, level, pos) -> FuelTabData.of(level, pos),
            FuelTabData.Sync::new);

    FuelTab() {
    }

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.fuel.tab");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.COAL);
    }

    @Override
    boolean tall() {
        return true;
    }

    @Override
    boolean showsInventory() {
        return true;
    }

    @Override
    public Optional<TabData<?>> synced() {
        return Optional.of(data);
    }

    /**
     * @return the fuel list as last synced
     */
    public TabData<FuelTabData> data() {
        return data;
    }

    /**
     * Server: changes the fuel list ({@link Fuels}) and sends the tab at once.
     *
     * @param op   the change
     * @param slot the slot it applies to
     * @param item the item to put ({@link FuelTabData.Op#SET})
     */
    public void edit(FuelTabData.Op op, int slot, Item item) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.site().filter(found -> menu.stillValid(menu.player()));
        if (site.isEmpty()) {
            return;
        }
        ServerLevel level = site.get().level();
        BlockPos pos = site.get().pos();
        boolean changed = switch (op) {
            case SET -> Fuels.set(level, pos, slot, item);
            case CLEAR -> Fuels.clear(level, pos, slot);
            case LEFT -> Fuels.move(level, pos, slot, -1);
            case RIGHT -> Fuels.move(level, pos, slot, 1);
        };
        if (changed) {
            menu.syncSoon();
        }
    }
}
