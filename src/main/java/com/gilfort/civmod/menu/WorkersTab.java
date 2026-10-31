package com.gilfort.civmod.menu;

import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.AutoAssignment;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.job_block.tab.workers");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.NAME_TAG);
    }

    @Override
    public Group group() {
        return Group.WORKERS;
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
        if (button == TOGGLE_LEAVES) {
            menu.setInfo(menu.getInfo().withLeavesToggled());
            menu.site().ifPresent(site -> JobAssignments.toggleLeaves(SettlementData.get(site.level()), site.pos()));
            return true;
        }
        return setAuto((button - AUTO_ASSIGN) / 2, (button - AUTO_ASSIGN) % 2 == 1);
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

    /** The listed worker that is this settler, empty if it left the list (the row a click was meant for is gone). */
    private Optional<JobBlockInfo.Worker> worker(UUID settler) {
        return menu().getInfo().workers().stream().filter(worker -> worker.assignee().id().equals(settler)).findFirst();
    }

    /**
     * ✕: removes one settler from the JobBlock at once; its skill and EXP stay with it (fresh snapshot). The client
     * applies it to its snapshot and sends {@link Worker}; the server unassigns.
     *
     * @param settler the settler of the row
     * @return whether it was listed (and is unassigned now)
     */
    public boolean unassign(UUID settler) {
        JobBlockMenu menu = menu();
        if (worker(settler).isEmpty()) {
            return false;
        }
        Optional<JobBlockMenu.Site> site = menu.site();
        if (site.isPresent()) {
            SettlementWorld.unassign(site.get().level(), site.get().pos(), settler);
            menu.refreshInfo(site.get());
        } else {
            menu.setInfo(menu.getInfo().withoutWorker(settler));
        }
        return true;
    }

    /**
     * Opens the settler window of a loaded worker at home (server); it stays open while the player stays at the JobBlock
     * and the settler works there.
     *
     * @param player  the player clicking
     * @param settler the settler of the row
     * @return whether it is listed and opens its window
     */
    public boolean open(Player player, UUID settler) {
        if (worker(settler).filter(JobBlockInfo.Worker::opensWindow).isEmpty()) {
            return false;
        }
        Optional<JobBlockMenu.Site> site = menu().site();
        if (site.isPresent() && player instanceof ServerPlayer serverPlayer && site.get().level().getEntity(settler) instanceof Settler found) {
            found.openWindow(serverPlayer, Optional.of(site.get().pos()));
        }
        return true;
    }

    /**
     * Client → server: a click on a settler row of the open JobBlock window, keyed by the settler (the list may have
     * changed since the client's last sync).
     *
     * @param containerId the menu's container id
     * @param settler     the settler of the row
     * @param open        true: open its settler window, false: unassign it (✕)
     */
    public record Worker(int containerId, UUID settler, boolean open) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Worker> TYPE = new Type<>(CivMod.id("workers_tab_worker"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Worker> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Worker::containerId,
                UUIDUtil.STREAM_CODEC, Worker::settler,
                ByteBufCodecs.BOOL, Worker::open,
                Worker::new);

        @Override
        public Type<Worker> type() {
            return TYPE;
        }

        /**
         * Applies the click to the player's open JobBlock window, while it is still valid.
         *
         * @param player the player
         */
        public void handle(ServerPlayer player) {
            if (player.containerMenu instanceof JobBlockMenu menu && menu.containerId == containerId && menu.stillValid(player)) {
                menu.tab(WorkersTab.class).ifPresent(tab -> {
                    if (open) {
                        tab.open(player, settler);
                    } else {
                        tab.unassign(settler);
                    }
                });
            }
        }
    }
}
