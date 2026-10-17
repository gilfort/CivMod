package com.gilfort.civmod.block;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.registry.ModBlockEntityTypes;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Block entity of a {@link JobBlock}: 27-slot storage (default drop-off), JobBlock level and a client-visible copy
 * of the settlement connection state. The settlement data itself is authoritative.
 */
public class JobBlockEntity extends BaseContainerBlockEntity {

    /** Number of storage slots. */
    public static final int SLOTS = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private int jobLevel = SettlementRules.MIN_LEVEL;
    private int settlementId = -1;
    private boolean connected = true;

    /**
     * @param pos   the block position
     * @param state the block state
     */
    public JobBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntityTypes.JOB_BLOCK.get(), pos, state);
    }

    /**
     * @return the JobBlock level
     */
    public int getJobLevel() {
        return jobLevel;
    }

    /**
     * Sets the level after an upgrade (the settlement entry is updated separately).
     *
     * @param jobLevel the new level
     */
    public void setJobLevel(int jobLevel) {
        this.jobLevel = SettlementRules.clampLevel(jobLevel);
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * @return the settlement id, {@code -1} if not registered yet
     */
    public int getSettlementId() {
        return settlementId;
    }

    /**
     * @return whether the block is registered in a settlement but not connected to its chain
     */
    public boolean isUnconnected() {
        return settlementId >= 0 && !connected;
    }

    /**
     * Updates the client-visible settlement state and syncs it.
     *
     * @param settlementId the settlement id
     * @param connected    whether the block is connected
     */
    public void setSettlement(int settlementId, boolean connected) {
        if (this.settlementId == settlementId && this.connected == connected) {
            return;
        }
        this.settlementId = settlementId;
        this.connected = connected;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putInt("job_level", jobLevel);
        output.putInt("settlement", settlementId);
        output.putBoolean("connected", connected);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        jobLevel = SettlementRules.clampLevel(input.getIntOr("job_level", SettlementRules.MIN_LEVEL));
        settlementId = input.getIntOr("settlement", -1);
        connected = input.getBooleanOr("connected", true);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("job_level", jobLevel);
        tag.putInt("settlement", settlementId);
        tag.putBoolean("connected", connected);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        jobLevel = SettlementRules.clampLevel(components.getOrDefault(ModDataComponents.JOB_LEVEL.get(), SettlementRules.MIN_LEVEL));
        // A new JobBlock item puts its starter tool into the inventory; it is never collected back, so it is used up.
        Holder<Item> starterTool = components.get(ModDataComponents.STARTER_TOOL.get());
        if (starterTool != null) {
            addStarterTool(new ItemStack(starterTool));
        }
    }

    private void addStarterTool(ItemStack tool) {
        for (int slot = 0; slot < items.size(); slot++) {
            if (items.get(slot).isEmpty()) {
                items.set(slot, tool);
                setChanged();
                return;
            }
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(ModDataComponents.JOB_LEVEL.get(), jobLevel);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // The settlement may have changed while this chunk was unloaded (e.g. auto-connect); clients receive the
        // corrected state with the chunk data, so no block update is sent here.
        if (level instanceof ServerLevel serverLevel) {
            JobBlockSite.at(serverLevel, worldPosition).ifPresent(site -> {
                settlementId = site.settlement().id();
                connected = site.block().connected();
                // Placement registers with the default level before the block entity exists; sync the real level.
                if (site.level() != jobLevel) {
                    // Deferred: onLoad may run during chunk loading, where other blocks must not be touched.
                    BlockPos pos = worldPosition;
                    int level = jobLevel;
                    serverLevel.getServer().execute(() -> SettlementWorld.setLevel(serverLevel, pos, level));
                }
            });
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel serverLevel) {
            SettlementWorld.remove(serverLevel, pos);
        }
    }

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        if (!(getBlockState().getBlock() instanceof JobBlock jobBlock)) {
            return null;
        }
        Job jobType = jobBlock.getJobType();
        JobBlockInfo info = level instanceof ServerLevel serverLevel
                ? JobBlockInfo.of(serverLevel, worldPosition, jobType, jobLevel)
                : JobBlockInfo.unknown(jobType, jobLevel);
        return new JobBlockMenu(containerId, inventory, this, info);
    }
}
