package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.gilfort.civmod.job.JobNeeds;
import com.gilfort.civmod.settlement.StationTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Station Types" tab of a Producing JobBlock or the Mission Board (vault {@code Systems/JobBlock.md}): its 5 slots for unlock items,
 * with the player inventory to pick them from ({@link StationTypesTabData}, synced while the window is open). Shown when
 * the Base Job has Specializations; a menu button per slot puts the carried item into it.
 */
public final class StationTypesTab extends JobBlockTab {

    private final TabData<StationTypesTabData> data = new TabData<>(StationTypesTabData.EMPTY, (menu, level, pos) -> read(level, pos),
            StationTypesTabData.Sync::new);
    /** What the Jobs need, read with the first sync and kept while the window is open ({@link JobNeeds}). */
    private Optional<List<JobNeeds>> needs = Optional.empty();
    private final boolean specializations;

    /**
     * @param specializations whether the Base Job has Specializations the tab unlocks (else the tab is hidden)
     */
    StationTypesTab(boolean specializations) {
        this.specializations = specializations;
    }

    private StationTypesTabData read(ServerLevel level, BlockPos pos) {
        if (needs.isEmpty()) {
            needs = Optional.of(JobNeeds.of(level, pos));
        }
        return StationTypesTabData.of(level, pos, needs.get());
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
    public Group group() {
        return Group.STATION_TYPES;
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
     * @return the unlock items that are not in a slot yet
     */
    @Override
    public Optional<Predicate<ItemStack>> accepts() {
        return Optional.of(stack -> {
            StationTypesTabData tab = data.get();
            return !tab.jobsOf(stack.getItem()).isEmpty() && !tab.slots().contains(stack.getItem());
        });
    }

    /**
     * Puts one of the clicked items into the first open, empty slot ({@link StationTypes#put}); the stack of the
     * inventory slot shrinks by one if the item unlocks something new. Without a free slot nothing happens.
     */
    @Override
    void quickMove(ItemStack stack) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.validSite();
        if (site.isEmpty()) {
            return;
        }
        StationTypesTabData tab = StationTypesTabData.of(site.get().level(), site.get().pos(), List.of());
        for (int slot = 0; slot < StationTypes.SLOTS; slot++) {
            if (tab.isOpen(slot) && tab.item(slot) == Items.AIR) {
                StationTypes.PutResult result = StationTypes.put(site.get().level(), site.get().pos(), slot, stack);
                if (result == StationTypes.PutResult.UNLOCKED) {
                    menu.resend();
                }
                return;
            }
        }
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
        menu.resend();
        return true;
    }
}
