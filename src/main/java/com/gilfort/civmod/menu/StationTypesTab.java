package com.gilfort.civmod.menu;

import java.util.Optional;

import com.gilfort.civmod.settlement.StationTypes;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Station Types" tab of a Producing JobBlock (vault {@code Systems/JobBlock.md}): its 5 slots for unlock items,
 * with the player inventory to pick them from ({@link StationTypesTabData}, synced while the window is open). Shown when
 * the Base Job has Specializations; a menu button per slot puts the carried item into it.
 */
public final class StationTypesTab extends JobBlockTab {

    private final TabData<StationTypesTabData> data = new TabData<>(StationTypesTabData.EMPTY,
            (menu, level, pos) -> StationTypesTabData.of(level, pos), StationTypesTabData.Sync::new);
    private final boolean specializations;

    /**
     * @param specializations whether the Base Job has Producing Specializations (else the tab is hidden)
     */
    StationTypesTab(boolean specializations) {
        this.specializations = specializations;
    }

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.station_types.tab");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.SMITHING_TABLE);
    }

    @Override
    public boolean shown() {
        return specializations;
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
     * @return the Station Types as last synced
     */
    public TabData<StationTypesTabData> data() {
        return data;
    }

    @Override
    int buttons() {
        return StationTypes.SLOTS;
    }

    /**
     * @param slot a Station Types slot
     * @return the menu button id that puts the carried item into it
     */
    public int slotButtonId(int slot) {
        return buttonId(slot);
    }

    /**
     * Puts one of the carried items into a Station Types slot ({@link StationTypes#put}) and sends the tabs at once.
     */
    @Override
    boolean clickButton(Player player, int slot) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.site();
        if (site.isEmpty()) {
            // Client: the server decides; the carried stack follows from it.
            return true;
        }
        if (!menu.stillValid(menu.player())) {
            return false;
        }
        StationTypes.PutResult result = StationTypes.put(site.get().level(), site.get().pos(), slot, menu.getCarried());
        if (result != StationTypes.PutResult.UNLOCKED && result != StationTypes.PutResult.REPLACED) {
            return false;
        }
        menu.refreshInfo(site.get());
        menu.syncSoon();
        return true;
    }
}
