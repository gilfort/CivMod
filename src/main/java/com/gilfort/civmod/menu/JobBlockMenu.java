package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.JobCategory;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.registry.ModMenuTypes;

import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu of a JobBlock: tab "Storage" (27 slots + player inventory), tab "Info" and, on the Mission Board, tab
 * "Missions".
 */
public class JobBlockMenu extends CivMenu {

    /** Storage tab. */
    public static final int TAB_STORAGE = 0;
    /** Info tab. */
    public static final int TAB_INFO = 1;
    /** Missions tab (Mission Board only). */
    public static final int TAB_MISSIONS = 2;

    /** Button id: free the JobBlock (remove the job from its settler). */
    public static final int BUTTON_UNASSIGN = 50;
    /** Button id: toggle leaf collection (Lumberjack). */
    public static final int BUTTON_TOGGLE_LEAVES = 51;
    /** Button id: upgrade to the next level. */
    public static final int BUTTON_UPGRADE = 52;
    /** Button ids from here: set the mark of a mission offer entry ({@link #markButtonId}). */
    private static final int BUTTON_MARK_MISSION = 100;
    /** Upper bound (exclusive) of the mark button ids. */
    private static final int BUTTON_MARK_MISSION_END = BUTTON_MARK_MISSION + 2 * MissionRules.MAX_OFFER_SIZE;
    /** Assign buttons per offer entry: unlock, then one per board settler. */
    private static final int ASSIGN_SLOTS = 1 + SettlementRules.MAX_MISSION_BOARD_CAPACITY;
    /** Button ids from here: assign or unlock a mission offer entry ({@link #assignButtonId}). */
    private static final int BUTTON_ASSIGN_MISSION = BUTTON_MARK_MISSION_END;
    /** Upper bound (exclusive) of the assign button ids. */
    private static final int BUTTON_ASSIGN_MISSION_END = BUTTON_ASSIGN_MISSION + ASSIGN_SLOTS * MissionRules.MAX_OFFER_SIZE;

    private final Container container;
    private JobBlockInfo info;

    /**
     * Client constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param buffer      extra data written by the server
     */
    public JobBlockMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, new SimpleContainer(JobBlockEntity.SLOTS), JobBlockInfo.STREAM_CODEC.decode(buffer));
    }

    /**
     * Server constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param container   the JobBlock storage
     * @param info        the info snapshot
     */
    public JobBlockMenu(int containerId, Inventory inventory, Container container, JobBlockInfo info) {
        super(ModMenuTypes.JOB_BLOCK.get(), containerId, isMissionBoard(info) ? 3 : 2);
        checkContainerSize(container, JobBlockEntity.SLOTS);
        this.container = container;
        this.info = info;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addTabSlot(container, column + row * 9, 8 + column * 18, 18 + row * 18, TAB_STORAGE);
            }
        }
        addPlayerInventory(inventory, 8, 84, TAB_STORAGE);
    }

    /**
     * @param info a JobBlock info
     * @return whether the JobBlock is a Mission Board (offers a Mission Job)
     */
    public static boolean isMissionBoard(JobBlockInfo info) {
        return info.jobType().category() == JobCategory.MISSION;
    }

    /**
     * The mark buttons carry the wanted state instead of a toggle, so two players clicking the same entry agree with
     * what they see.
     *
     * @param index  a mission offer index
     * @param marked the wanted mark
     * @return the menu button id that sets it
     */
    public static int markButtonId(int index, boolean marked) {
        return BUTTON_MARK_MISSION + 2 * index + (marked ? 1 : 0);
    }

    /**
     * Assign buttons name the settler by its index in the snapshot the player sees
     * ({@link JobBlockInfo.MissionBoard#settlers()}); the server assigns the settler of its own snapshot at that index.
     *
     * @param index   a mission offer index
     * @param settler the settler index in the board's settler list, {@code -1} to unlock the mission
     * @return the menu button id that assigns or unlocks it
     */
    public static int assignButtonId(int index, int settler) {
        return BUTTON_ASSIGN_MISSION + ASSIGN_SLOTS * index + settler + 1;
    }

    /**
     * @return the info snapshot
     */
    public JobBlockInfo getInfo() {
        return info;
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId == BUTTON_UNASSIGN) {
            info = info.withoutAssignees();
            if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
                SettlementWorld.unassign(level, jobBlock.getBlockPos());
            }
            return true;
        }
        if (buttonId == BUTTON_UPGRADE) {
            return upgrade(player);
        }
        if (buttonId >= BUTTON_MARK_MISSION && buttonId < BUTTON_MARK_MISSION_END) {
            int index = (buttonId - BUTTON_MARK_MISSION) / 2;
            boolean marked = (buttonId - BUTTON_MARK_MISSION) % 2 == 1;
            if (index >= info.missions().size()) {
                return false;
            }
            if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level
                    && !MissionOffers.setMarked(SettlementData.get(level), jobBlock.getBlockPos(), index, info.missions().get(index).mission(), marked)) {
                return false;
            }
            info = info.withMarked(index, marked);
            return true;
        }
        if (buttonId >= BUTTON_ASSIGN_MISSION && buttonId < BUTTON_ASSIGN_MISSION_END) {
            return assign((buttonId - BUTTON_ASSIGN_MISSION) / ASSIGN_SLOTS, (buttonId - BUTTON_ASSIGN_MISSION) % ASSIGN_SLOTS - 1);
        }
        if (buttonId == BUTTON_TOGGLE_LEAVES) {
            info = info.withLeavesToggled();
            if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
                JobAssignments.toggleLeaves(SettlementData.get(level), jobBlock.getBlockPos());
            }
            return true;
        }
        return super.clickMenuButton(player, buttonId);
    }

    /** Assigns an offer entry to a settler of the board ({@code settler} = index in the snapshot) or unlocks it ({@code -1}). */
    private boolean assign(int index, int settler) {
        List<JobBlockInfo.MissionSettler> settlers = info.missionBoard().settlers();
        if (index >= info.missions().size() || settler >= settlers.size()) {
            return false;
        }
        Identifier mission = info.missions().get(index).mission();
        Optional<JobBlockInfo.MissionSettler> chosen = settler < 0 ? Optional.empty() : Optional.of(settlers.get(settler));
        if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
            SettlementData data = SettlementData.get(level);
            BlockPos board = jobBlock.getBlockPos();
            boolean changed = chosen.isPresent()
                    ? MissionOffers.assign(data, board, index, mission, chosen.get().id(), chosen.get().job(), Missions.all(level.registryAccess()))
                    : MissionOffers.unlock(data, board, index, mission);
            if (!changed) {
                return false;
            }
        }
        info = info.withAssignee(index, chosen.map(JobBlockInfo.MissionSettler::id));
        return true;
    }

    private boolean upgrade(Player player) {
        int next = info.level() + 1;
        if (next > com.gilfort.civmod.settlement.SettlementRules.MAX_LEVEL || (!player.isCreative() && !info.canAffordNext(player.getInventory()))) {
            return false;
        }
        if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
            java.util.List<com.gilfort.civmod.block.UpgradeCosts.Cost> costs = com.gilfort.civmod.block.UpgradeCosts.get(info.jobType(), next);
            if (!player.isCreative()) {
                if (!com.gilfort.civmod.block.UpgradeCosts.canAfford(player.getInventory(), costs)) {
                    return false;
                }
                com.gilfort.civmod.block.UpgradeCosts.pay(player.getInventory(), costs);
            }
            jobBlock.setJobLevel(next);
            SettlementWorld.setLevel(level, jobBlock.getBlockPos(), next);
            level.playSound(null, jobBlock.getBlockPos(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.BLOCKS,
                    0.6F, 1.0F);
            info = JobBlockInfo.of(level, jobBlock.getBlockPos(), info.jobType(), next);
        } else {
            info = info.withLevel(next);
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = slotIndex < JobBlockEntity.SLOTS
                ? moveItemStackTo(stack, JobBlockEntity.SLOTS, slots.size(), true)
                : moveItemStackTo(stack, 0, JobBlockEntity.SLOTS, false);
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
        return container.stillValid(player);
    }
}
