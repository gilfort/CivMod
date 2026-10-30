package com.gilfort.civmod.menu;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.npc.Settler;

import com.gilfort.civmod.npc.SettlerJobs;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.registry.ModMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Menu of a settler: tabs Overview, Jobs (priority list), Inventory (tool belt, offhand, pockets) and Mission (the
 * settler's one Mission Job).
 * <p>
 * Job edits and dismissal are menu button clicks; job edits are applied identically on client and server.
 */
public class SettlerMenu extends CivMenu {

    /** Overview tab. */
    public static final int TAB_OVERVIEW = 0;
    /** Jobs tab. */
    public static final int TAB_JOBS = 1;
    /** Inventory tab. */
    public static final int TAB_INVENTORY = 2;
    /** Mission tab. */
    public static final int TAB_MISSION = 3;
    /** First of the job tabs (one per job, shown below the panel). */
    public static final int FIRST_JOB_TAB = 4;
    /** Maximum number of job tabs. */
    public static final int MAX_JOB_TABS = 6;

    /** Button id: dismiss a homeless settler. */
    public static final int BUTTON_DISMISS = 900;

    /** Items accepted in the tool belt. */
    public static final TagKey<Item> SETTLER_TOOLS = TagKey.create(Registries.ITEM, CivMod.id("settler_tools"));

    /** Panel height (taller than a chest to fit belt, pockets and player inventory). */
    public static final int IMAGE_HEIGHT = 184;
    /** Y of the tool belt row. */
    public static final int BELT_Y = 28;
    /** Y of the pocket row. */
    public static final int POCKETS_Y = 60;
    /** X of the offhand slot. */
    public static final int OFFHAND_X = 8 + 5 * 18;
    /** Number of settler slots in the menu (belt, offhand, pockets). */
    private static final int SETTLER_SLOTS = Settler.ARMOR_START;

    /** Distance (squared) within which the window stays open: 8 blocks, like a container. */
    private static final double REACH_SQUARED = 64.0;

    private final @Nullable Settler settler;
    /** The JobBlock the window was opened from (Workers tab), empty when opened at the settler. */
    private final Optional<BlockPos> anchor;
    private SettlerInfo info;

    /**
     * Client constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param buffer      extra data written by the server
     */
    public SettlerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, null, new SimpleContainer(Settler.INVENTORY_SIZE), SettlerInfo.STREAM_CODEC.decode(buffer), Optional.empty());
    }

    /**
     * Server constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param settler     the settler
     * @param info        the info snapshot
     */
    public SettlerMenu(int containerId, Inventory inventory, Settler settler, SettlerInfo info) {
        this(containerId, inventory, settler, info, Optional.empty());
    }

    /**
     * Server constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param settler     the settler
     * @param info        the info snapshot
     * @param anchor      the JobBlock the window was opened from (Workers tab): it also stays open while the player
     *                    stays at that block
     */
    public SettlerMenu(int containerId, Inventory inventory, Settler settler, SettlerInfo info, Optional<BlockPos> anchor) {
        this(containerId, inventory, settler, settler.getInventory(), info, anchor);
    }

    private SettlerMenu(int containerId, Inventory inventory, @Nullable Settler settler, Container container, SettlerInfo info,
            Optional<BlockPos> anchor) {
        super(ModMenuTypes.SETTLER.get(), containerId, FIRST_JOB_TAB + MAX_JOB_TABS);
        this.settler = settler;
        this.info = info;
        this.anchor = anchor;
        for (int i = 0; i < Settler.BELT_SIZE; i++) {
            addSlot(new CivSlot(this, container, Settler.BELT_START + i, 8 + i * 18, BELT_Y, TAB_INVENTORY) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return stack.is(SETTLER_TOOLS);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
        addTabSlot(container, Settler.OFFHAND, OFFHAND_X, BELT_Y, TAB_INVENTORY);
        for (int i = 0; i < Settler.POCKETS_SIZE; i++) {
            addTabSlot(container, Settler.POCKETS_START + i, 8 + i * 18, POCKETS_Y, TAB_INVENTORY);
        }
        addPlayerInventory(inventory, 8, IMAGE_HEIGHT - 82, TAB_INVENTORY);
    }

    /**
     * @return the current info (jobs are kept up to date with edits)
     */
    public SettlerInfo getInfo() {
        return info;
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId >= SettlerJobs.MOVE_UP && buttonId < SettlerJobs.END) {
            info = info.withJobs(SettlerJobs.apply(info.jobs(), buttonId));
            if (settler != null && settler.level() instanceof ServerLevel level) {
                // Frees the JobBlocks of removed jobs.
                SettlementWorld.setJobs(level, settler, info.jobs());
            }
            return true;
        }
        if (buttonId == BUTTON_DISMISS) {
            if (settler != null && settler.isHomeless() && player instanceof ServerPlayer serverPlayer) {
                settler.dismiss();
                serverPlayer.closeContainer();
            }
            return true;
        }
        return super.clickMenuButton(player, buttonId);
    }

    /**
     * The job switcher (vault {@code Systems/Jobs.md}): switches the job at an index to another Job unlocked at its
     * JobBlock. The client applies it to its snapshot and sends {@link SwitchJob}; the server switches the settler
     * ({@link SettlementWorld#switchJob}) and takes a fresh snapshot.
     *
     * @param player the player using the window
     * @param index  the index of the job in the priority list
     * @param job    the Job to switch to, one the job's state offers ({@link SettlerInfo.JobState#switchTo()})
     * @return whether the job was switched
     */
    public boolean switchJob(Player player, int index, Job job) {
        if (index < 0 || index >= info.jobs().size() || index >= info.jobStates().size() || !info.jobStates().get(index).switchTo().contains(job)) {
            return false;
        }
        if (settler == null) {
            info = info.withSwitched(index, job);
            return true;
        }
        if (!(settler.level() instanceof ServerLevel level) || !stillValid(player)
                || !SettlementWorld.switchJob(level, info.jobs().get(index).jobBlock(), settler, job)) {
            return false;
        }
        info = SettlerInfo.of(settler);
        return true;
    }

    /**
     * Client → server: the job switcher's choice.
     *
     * @param containerId the settler window's container id
     * @param index       the index of the job in the priority list
     * @param job         the Job to switch to
     */
    public record SwitchJob(int containerId, int index, Job job) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<SwitchJob> TYPE = new Type<>(CivMod.id("settler_switch_job"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, SwitchJob> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SwitchJob::containerId,
                ByteBufCodecs.VAR_INT, SwitchJob::index,
                Job.STREAM_CODEC, SwitchJob::job,
                SwitchJob::new);

        @Override
        public Type<SwitchJob> type() {
            return TYPE;
        }

        /**
         * Applies the choice to the player's open settler window.
         *
         * @param player the player
         */
        public void handle(ServerPlayer player) {
            if (player.containerMenu instanceof SettlerMenu menu && menu.containerId == containerId) {
                menu.switchJob(player, index, job);
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = slotIndex < SETTLER_SLOTS
                ? moveItemStackTo(stack, SETTLER_SLOTS, slots.size(), true)
                : moveItemStackTo(stack, 0, SETTLER_SLOTS, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    /**
     * Open while the player is near the settler, or near the JobBlock the window was opened from as long as the settler
     * still works there (once unassigned that block stops counting, like any settler window out of reach).
     */
    @Override
    public boolean stillValid(Player player) {
        return settler == null || settler.isAlive() && (player.distanceToSqr(settler) < REACH_SQUARED
                || anchor.filter(pos -> settler.jobAt(pos).isPresent() && player.distanceToSqr(Vec3.atCenterOf(pos)) < REACH_SQUARED).isPresent());
    }
}
