package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.UpgradeCosts;
import com.gilfort.civmod.settlement.AutoAssignment;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Info" tab of every JobBlock: Job, level and skill cap, settlement status, the Job lines with their automatic
 * assignment toggles, upgrade costs and the buttons Upgrade, Free and (Lumberjack) leaf collection. Its data is the
 * window's {@link JobBlockInfo}.
 */
public final class InfoTab extends JobBlockTab {

    /** Button: free the JobBlock (remove the job from its settler). */
    public static final int UNASSIGN = 0;
    /** Button: upgrade to the next level. */
    public static final int UPGRADE = 1;
    /** Button: toggle leaf collection (Lumberjack). */
    public static final int TOGGLE_LEAVES = 2;
    /** Buttons from here: set the automatic assignment toggle of a Job line ({@link #autoButtonId}). */
    private static final int AUTO_ASSIGN = 3;
    /** Most Job lines of a JobBlock with an automatic assignment toggle. */
    private static final int MAX_JOB_LINES = 16;

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.job_block.tab.info");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.BOOK);
    }

    @Override
    int buttons() {
        return AUTO_ASSIGN + 2 * MAX_JOB_LINES;
    }

    /**
     * The toggle buttons carry the wanted state instead of a toggle, so two players clicking the same line agree with
     * what they see.
     *
     * @param line the index of the Job line ({@link JobBlockInfo#places()})
     * @param on   the wanted state of its automatic assignment
     * @return the menu button id that sets it
     */
    public int autoButtonId(int line, boolean on) {
        return buttonId(AUTO_ASSIGN + 2 * line + (on ? 1 : 0));
    }

    @Override
    boolean clickButton(Player player, int button) {
        JobBlockMenu menu = menu();
        return switch (button) {
            case UNASSIGN -> {
                menu.setInfo(menu.getInfo().withoutAssignees());
                menu.site().ifPresent(site -> SettlementWorld.unassign(site.level(), site.pos()));
                yield true;
            }
            case UPGRADE -> upgrade(player);
            case TOGGLE_LEAVES -> {
                menu.setInfo(menu.getInfo().withLeavesToggled());
                menu.site().ifPresent(site -> JobAssignments.toggleLeaves(SettlementData.get(site.level()), site.pos()));
                yield true;
            }
            default -> setAuto((button - AUTO_ASSIGN) / 2, (button - AUTO_ASSIGN) % 2 == 1);
        };
    }

    /** Sets the automatic assignment toggle of a Job line; switching it on may assign settlers at once (fresh snapshot). */
    private boolean setAuto(int line, boolean on) {
        JobBlockMenu menu = menu();
        JobBlockInfo info = menu.getInfo();
        if (line >= info.places().size() || !info.inSettlement()) {
            return false;
        }
        Job job = info.places().get(line).job();
        Optional<JobBlockMenu.Site> site = menu.site();
        if (site.isPresent()) {
            if (!AutoAssignment.setAuto(site.get().level(), site.get().pos(), job, on)) {
                return false;
            }
            menu.refreshInfo(site.get());
        } else {
            menu.setInfo(info.withAuto(job, on));
        }
        return true;
    }

    private boolean upgrade(Player player) {
        JobBlockMenu menu = menu();
        JobBlockInfo info = menu.getInfo();
        int next = info.level() + 1;
        if (next > SettlementRules.MAX_LEVEL || (!player.isCreative() && !info.canAffordNext(player.getInventory()))) {
            return false;
        }
        Optional<JobBlockMenu.Site> site = menu.site();
        if (site.isEmpty()) {
            menu.setInfo(info.withLevel(next));
            return true;
        }
        List<UpgradeCosts.Cost> costs = UpgradeCosts.get(info.jobType(), next);
        if (!player.isCreative()) {
            if (!UpgradeCosts.canAfford(player.getInventory(), costs)) {
                return false;
            }
            UpgradeCosts.pay(player.getInventory(), costs);
        }
        JobBlockMenu.Site found = site.get();
        found.jobBlock().setJobLevel(next);
        SettlementWorld.setLevel(found.level(), found.pos(), next);
        found.level().playSound(null, found.pos(), SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6F, 1.0F);
        menu.setInfo(JobBlockInfo.of(found.level(), found.pos(), info.jobType(), next));
        return true;
    }
}
