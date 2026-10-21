package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Missions" tab of the Mission Board (vault {@code Systems/Missions.md}): the daily offer with marks and
 * assignments, and the missions its settlers took. Its data is the window's {@link JobBlockInfo#missionTab()}.
 */
public final class MissionsTab extends JobBlockTab {

    /** Mark buttons: two per offer entry (unmark, mark). */
    private static final int MARK_BUTTONS = 2 * MissionRules.MAX_OFFER_SIZE;
    /** Assign buttons per offer entry: unlock, then one per board settler. */
    private static final int ASSIGN_SLOTS = 1 + SettlementRules.MAX_SETTLERS;

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.job_block.tab.missions");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.MAP);
    }

    @Override
    int buttons() {
        return MARK_BUTTONS + ASSIGN_SLOTS * MissionRules.MAX_OFFER_SIZE;
    }

    /**
     * The mark buttons carry the wanted state instead of a toggle, so two players clicking the same entry agree with
     * what they see.
     *
     * @param index  a mission offer index
     * @param marked the wanted mark
     * @return the menu button id that sets it
     */
    public int markButtonId(int index, boolean marked) {
        return buttonId(2 * index + (marked ? 1 : 0));
    }

    /**
     * Assign buttons name the settler by its index in the snapshot the player sees
     * ({@link JobBlockInfo.MissionTab#settlers()}); the server assigns the settler of its own snapshot at that index.
     *
     * @param index   a mission offer index
     * @param settler the settler index in the board's settler list, {@code -1} to unlock the mission
     * @return the menu button id that assigns or unlocks it
     */
    public int assignButtonId(int index, int settler) {
        return buttonId(MARK_BUTTONS + ASSIGN_SLOTS * index + settler + 1);
    }

    @Override
    boolean clickButton(Player player, int button) {
        if (button < MARK_BUTTONS) {
            return mark(button / 2, button % 2 == 1);
        }
        return assign((button - MARK_BUTTONS) / ASSIGN_SLOTS, (button - MARK_BUTTONS) % ASSIGN_SLOTS - 1);
    }

    private boolean mark(int index, boolean marked) {
        JobBlockMenu menu = menu();
        JobBlockInfo info = menu.getInfo();
        if (index >= info.missions().size()) {
            return false;
        }
        Optional<JobBlockMenu.Site> site = menu.site();
        if (site.isPresent() && !MissionOffers.setMarked(SettlementData.get(site.get().level()), site.get().pos(), index,
                info.missions().get(index).mission(), marked)) {
            return false;
        }
        menu.setInfo(info.withMarked(index, marked));
        return true;
    }

    /** Assigns an offer entry to a settler of the board ({@code settler} = index in the snapshot) or unlocks it ({@code -1}). */
    private boolean assign(int index, int settler) {
        JobBlockMenu menu = menu();
        JobBlockInfo info = menu.getInfo();
        List<JobBlockInfo.MissionSettler> settlers = info.missionTab().settlers();
        if (index >= info.missions().size() || settler >= settlers.size()) {
            return false;
        }
        Identifier mission = info.missions().get(index).mission();
        Optional<JobBlockInfo.MissionSettler> chosen = settler < 0 ? Optional.empty() : Optional.of(settlers.get(settler));
        Optional<JobBlockMenu.Site> site = menu.site();
        if (site.isPresent()) {
            SettlementData data = SettlementData.get(site.get().level());
            boolean changed = chosen.isPresent()
                    ? MissionOffers.assign(data, site.get().pos(), index, mission, chosen.get().id(), chosen.get().job(),
                            Missions.all(site.get().level().registryAccess()))
                    : MissionOffers.unlock(data, site.get().pos(), index, mission);
            if (!changed) {
                return false;
            }
        }
        menu.setInfo(info.withAssignee(index, chosen.map(JobBlockInfo.MissionSettler::id)));
        return true;
    }
}
