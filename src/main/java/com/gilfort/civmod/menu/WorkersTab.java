package com.gilfort.civmod.menu;

import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.AutoAssignment;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
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
 * or name that opens the settler window; plus the leaf collection toggle (Lumberjack), the treasure toggle
 * (Fisherman) and the "Auto tool supply" toggle (Clerk). Its data is the synced {@link WorkersTabData}; the server changes the state and sends the data again
 * ({@link JobBlockMenu#resend}), the client only renders and asks. JobBlocks are upgraded with the JobBlock Upgrade
 * items, not here ({@link com.gilfort.civmod.block.JobBlockUpgrade}).
 */
public final class WorkersTab extends JobBlockTab {

    /** Buttons: set leaf collection off / on (Lumberjack, {@link #leavesButtonId}). */
    private static final int LEAVES = 0;
    /** Buttons: set the treasure option off / on (Fisherman, {@link #treasureButtonId}). */
    private static final int TREASURE = LEAVES + 2;
    /** Buttons: set the Clerk's automatic tool supply off / on ({@link #toolSupplyButtonId}). */
    private static final int TOOL_SUPPLY = TREASURE + 2;

    private final TabData<WorkersTabData> data = new TabData<>(WorkersTabData.EMPTY,
            (menu, level, pos) -> WorkersTabData.of(level, pos, menu.getInfo().jobType(), menu.getInfo().level()), WorkersTabData.Sync::new);

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
    public Optional<TabData<?>> synced() {
        return Optional.of(data);
    }

    /**
     * @return the Workers data as last synced
     */
    public TabData<WorkersTabData> data() {
        return data;
    }

    @Override
    int buttons() {
        return TOOL_SUPPLY + 2;
    }

    /**
     * The toggle buttons carry the wanted state instead of a toggle, so two players clicking the same switch agree with
     * what they see.
     *
     * @param on the wanted state of leaf collection
     * @return the menu button id that sets it
     */
    public int leavesButtonId(boolean on) {
        return buttonId(LEAVES + (on ? 1 : 0));
    }

    /**
     * Like {@link #leavesButtonId}, the treasure toggle carries the wanted state.
     *
     * @param on the wanted state of the Fisherman's treasure
     * @return the menu button id that sets it
     */
    public int treasureButtonId(boolean on) {
        return buttonId(TREASURE + (on ? 1 : 0));
    }

    /**
     * Like {@link #leavesButtonId}, the tool supply toggle carries the wanted state.
     *
     * @param on the wanted state of the Clerk's automatic tool supply
     * @return the menu button id that sets it
     */
    public int toolSupplyButtonId(boolean on) {
        return buttonId(TOOL_SUPPLY + (on ? 1 : 0));
    }

    @Override
    boolean clickButton(Player player, int button) {
        if (button < TREASURE) {
            setLeaves(button - LEAVES == 1);
        } else if (button < TOOL_SUPPLY) {
            setTreasure(button - TREASURE == 1);
        } else {
            menu().editSettlement((data, pos) -> JobAssignments.setAutoToolSupply(data, pos, button - TOOL_SUPPLY == 1));
        }
        return true;
    }

    private void setLeaves(boolean on) {
        menu().editSettlement((data, pos) -> JobAssignments.setLeaves(data, pos, on));
    }

    private void setTreasure(boolean on) {
        menu().editSettlement((data, pos) -> JobAssignments.setTreasure(data, pos, on));
    }

    /**
     * Server: sets the automatic assignment toggle of a Job line; switching it on may assign settlers at once.
     *
     * @param job the Job of the line
     * @param on  the wanted state
     * @return whether the state changed (false for a Job the block does not offer or a block outside a settlement)
     */
    public boolean setAuto(Job job, boolean on) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.validSite();
        if (site.isEmpty() || !AutoAssignment.setAuto(site.get().level(), site.get().pos(), job, on)) {
            return false;
        }
        menu.resend();
        return true;
    }

    /** The assignee of this JobBlock that is this settler, empty if it left (the row a click was meant for is gone). */
    private static Optional<SettlementBlock.Assignee> assignee(JobBlockMenu.Site site, UUID settler) {
        return JobBlockSite.blockAt(site.level(), site.pos()).flatMap(block -> block.assignee(settler));
    }

    /**
     * Server: ✕ removes one settler from the JobBlock at once; its skill and EXP stay with it.
     *
     * @param settler the settler of the row
     * @return whether it works here (and is unassigned now)
     */
    public boolean unassign(UUID settler) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.validSite();
        if (site.isEmpty() || assignee(site.get(), settler).isEmpty()) {
            return false;
        }
        SettlementWorld.unassign(site.get().level(), site.get().pos(), settler);
        menu.resend();
        return true;
    }

    /**
     * Server: sets the "Auto" switch of a Deliveryman's job at this JobBlock (it works Delivery Slips only while off).
     *
     * @param settler the settler of the row
     * @param on      the wanted state
     * @return whether it is a loaded Deliveryman of this block and now has that state
     */
    public boolean setWorkerAuto(UUID settler, boolean on) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.validSite();
        if (site.isEmpty() || assignee(site.get(), settler).filter(found -> found.job() == ModJobs.DELIVERYMAN.get()).isEmpty()
                || !(site.get().level().getEntity(settler) instanceof Settler found) || !found.setJobAuto(site.get().pos(), on)) {
            return false;
        }
        menu.resend();
        return true;
    }

    /**
     * Server: opens the settler window of a loaded worker at home; it stays open while the player stays at the JobBlock
     * and the settler works there.
     *
     * @param player  the player clicking
     * @param settler the settler of the row
     * @return whether it works here, is loaded and at home, and opens its window
     */
    public boolean open(Player player, UUID settler) {
        Optional<JobBlockMenu.Site> site = menu().validSite();
        if (site.isEmpty() || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        Optional<SettlementBlock.Assignee> assignee = assignee(site.get(), settler);
        if (assignee.isEmpty() || !WorkersTabData.Worker.of(site.get().level(), site.get().pos(), assignee.get()).opensWindow()
                || !(site.get().level().getEntity(settler) instanceof Settler found)) {
            return false;
        }
        found.openWindow(serverPlayer, Optional.of(site.get().pos()));
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
            JobBlockMenu.open(player, containerId).flatMap(menu -> menu.tab(WorkersTab.class)).ifPresent(tab -> {
                if (open) {
                    tab.open(player, settler);
                } else {
                    tab.unassign(settler);
                }
            });
        }
    }

    /**
     * Client → server: switch the "Auto" of a Deliveryman row, keyed by the settler.
     *
     * @param containerId the menu's container id
     * @param settler     the settler of the row
     * @param on          the wanted state
     */
    public record WorkerAuto(int containerId, UUID settler, boolean on) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<WorkerAuto> TYPE = new Type<>(CivMod.id("workers_tab_worker_auto"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, WorkerAuto> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, WorkerAuto::containerId,
                UUIDUtil.STREAM_CODEC, WorkerAuto::settler,
                ByteBufCodecs.BOOL, WorkerAuto::on,
                WorkerAuto::new);

        @Override
        public Type<WorkerAuto> type() {
            return TYPE;
        }
    }
}
