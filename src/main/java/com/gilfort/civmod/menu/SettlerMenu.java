package com.gilfort.civmod.menu;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import java.util.List;

import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.SettlerJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.registry.ModMenuTypes;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
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

/**
 * Menu of a settler: tabs Overview, Jobs (priority list) and Inventory (tool belt, offhand, pockets).
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
    /** First of the job tabs (one per job, shown below the panel). */
    public static final int FIRST_JOB_TAB = 3;
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

    private final @Nullable Settler settler;
    private SettlerInfo info;

    /**
     * Client constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param buffer      extra data written by the server
     */
    public SettlerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, null, new SimpleContainer(Settler.INVENTORY_SIZE), SettlerInfo.STREAM_CODEC.decode(buffer));
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
        this(containerId, inventory, settler, settler.getInventory(), info);
    }

    private SettlerMenu(int containerId, Inventory inventory, @Nullable Settler settler, Container container, SettlerInfo info) {
        super(ModMenuTypes.SETTLER.get(), containerId, FIRST_JOB_TAB + MAX_JOB_TABS);
        this.settler = settler;
        this.info = info;
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
            List<SettlerJob> before = info.jobs();
            info = info.withJobs(SettlerJobs.apply(before, buttonId));
            if (settler != null && settler.level() instanceof ServerLevel level) {
                settler.setJobs(info.jobs());
                // Free JobBlocks of removed jobs.
                before.stream().filter(job -> !info.jobs().contains(job))
                        .forEach(job -> JobAssignments.unassign(SettlementData.get(level), job.jobBlock()));
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

    @Override
    public boolean stillValid(Player player) {
        return settler == null || settler.isAlive() && player.distanceToSqr(settler) < 64.0;
    }
}
