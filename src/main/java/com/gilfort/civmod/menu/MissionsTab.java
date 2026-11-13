package com.gilfort.civmod.menu;

import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Missions" tab of the Mission Board (vault {@code Systems/Missions.md}): the daily offer with marks and
 * assignments, and the missions its settlers took. Its data is the synced {@link MissionsTabData}; the server changes
 * the offer and sends the data again ({@link JobBlockMenu#resend}), the client only renders and asks
 * ({@link MissionsTabData.Mark}, {@link MissionsTabData.Assign}).
 */
public final class MissionsTab extends JobBlockTab {

    private final TabData<MissionsTabData> data = new TabData<>(MissionsTabData.EMPTY, (menu, level, pos) -> MissionsTabData.of(level, pos),
            MissionsTabData.Sync::new);

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.job_block.tab.missions");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.MAP);
    }

    @Override
    public Optional<TabData<?>> synced() {
        return Optional.of(data);
    }

    /**
     * @return the missions as last synced
     */
    public TabData<MissionsTabData> data() {
        return data;
    }

    /**
     * Server: marks or unmarks an offered mission. The caller names the mission it saw at that index; if the offer
     * changed since, nothing happens.
     *
     * @param index   the offer entry
     * @param mission the mission expected at that index
     * @param marked  the wanted mark
     * @return whether the entry was set
     */
    public boolean mark(int index, Identifier mission, boolean marked) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.validSite();
        if (site.isEmpty() || !MissionOffers.setMarked(SettlementData.get(site.get().level()), site.get().pos(), index, mission, marked)) {
            return false;
        }
        menu.resend();
        return true;
    }

    /**
     * Server: assigns an offered mission to a settler of the board or unlocks it. The caller names the mission it saw at
     * that index; if the offer changed since, nothing happens.
     *
     * @param index   the offer entry
     * @param mission the mission expected at that index
     * @param settler the settler to assign it to, empty to unlock the mission
     * @return whether the entry was changed (false for a settler that is not a loaded settler of this board)
     */
    public boolean assign(int index, Identifier mission, Optional<UUID> settler) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.validSite();
        if (site.isEmpty()) {
            return false;
        }
        SettlementData settlements = SettlementData.get(site.get().level());
        boolean changed;
        if (settler.isPresent()) {
            Optional<MissionsTabData.MissionSettler> chosen = MissionsTabData.of(site.get().level(), site.get().pos()).settler(settler.get());
            changed = chosen.isPresent() && MissionOffers.assign(settlements, site.get().pos(), index, mission, chosen.get().id(), chosen.get().job(),
                    Missions.all(site.get().level().registryAccess()));
        } else {
            changed = MissionOffers.unlock(settlements, site.get().pos(), index, mission);
        }
        if (changed) {
            menu.resend();
        }
        return changed;
    }
}
