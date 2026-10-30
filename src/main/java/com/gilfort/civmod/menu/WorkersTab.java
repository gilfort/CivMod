package com.gilfort.civmod.menu;

import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.AutoAssignment;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Workers" tab of every JobBlock (vault {@code Systems/GUI.md}, "Info becomes Workers"): per offered Job its line
 * with the automatic assignment button, below it one row per settler with ✕ (unassigns it at once) and a click on head
 * or name that opens the settler window; plus the leaf collection toggle (Lumberjack). Its data is the window's
 * {@link JobBlockInfo} ({@link JobBlockInfo#workers()}). JobBlocks are upgraded with the JobBlock Upgrade items, not here
 * ({@link com.gilfort.civmod.block.JobBlockUpgrade}).
 */
public final class WorkersTab extends JobBlockTab {

    /** Button: toggle leaf collection (Lumberjack). */
    public static final int TOGGLE_LEAVES = 0;
    /** Buttons from here: set the automatic assignment toggle of a Job line ({@link #autoButtonId}). */
    private static final int AUTO_ASSIGN = 1;
    /** Most Job lines of a JobBlock with an automatic assignment toggle. */
    private static final int MAX_JOB_LINES = 16;
    /** Buttons from here: unassign a worker ({@link #unassignButtonId}). */
    private static final int UNASSIGN = AUTO_ASSIGN + 2 * MAX_JOB_LINES;
    /** Most workers of a JobBlock with their own buttons. */
    private static final int MAX_WORKERS = 32;
    /** Buttons from here: open a worker's settler window ({@link #openButtonId}). */
    private static final int OPEN = UNASSIGN + MAX_WORKERS;

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.job_block.tab.workers");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.NAME_TAG);
    }

    @Override
    int buttons() {
        return OPEN + MAX_WORKERS;
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

    /**
     * @param worker the index of the worker ({@link JobBlockInfo#workers()})
     * @return the menu button id of its ✕
     */
    public int unassignButtonId(int worker) {
        return buttonId(UNASSIGN + worker);
    }

    /**
     * @param worker the index of the worker ({@link JobBlockInfo#workers()})
     * @return the menu button id that opens its settler window
     */
    public int openButtonId(int worker) {
        return buttonId(OPEN + worker);
    }

    @Override
    boolean clickButton(Player player, int button) {
        JobBlockMenu menu = menu();
        if (button == TOGGLE_LEAVES) {
            menu.setInfo(menu.getInfo().withLeavesToggled());
            menu.site().ifPresent(site -> JobAssignments.toggleLeaves(SettlementData.get(site.level()), site.pos()));
            return true;
        }
        if (button < UNASSIGN) {
            return setAuto((button - AUTO_ASSIGN) / 2, (button - AUTO_ASSIGN) % 2 == 1);
        }
        if (button < OPEN) {
            return unassign(button - UNASSIGN);
        }
        return open(player, button - OPEN);
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

    /** ✕: removes one settler from the JobBlock at once; its skill and EXP stay with it (fresh snapshot). */
    private boolean unassign(int worker) {
        JobBlockMenu menu = menu();
        JobBlockInfo info = menu.getInfo();
        if (worker >= info.workers().size()) {
            return false;
        }
        UUID settler =info.workers().get(worker).assignee().id();
        Optional<JobBlockMenu.Site> site = menu.site();
        if (site.isPresent()) {
            SettlementWorld.unassign(site.get().level(), site.get().pos(), settler);
            menu.refreshInfo(site.get());
        } else {
            menu.setInfo(info.withoutWorker(settler));
        }
        return true;
    }

    /** Opens the settler window of a loaded worker at home; it stays open while the player stays at the JobBlock. */
    private boolean open(Player player, int worker) {
        JobBlockInfo info = menu().getInfo();
        if (worker >= info.workers().size() || !info.workers().get(worker).opensWindow()) {
            return false;
        }
        Optional<JobBlockMenu.Site> site = menu().site();
        if (site.isPresent() && player instanceof ServerPlayer serverPlayer
                && site.get().level().getEntity(info.workers().get(worker).assignee().id()) instanceof Settler settler) {
            settler.openWindow(serverPlayer, Optional.of(site.get().pos()));
        }
        return true;
    }
}
