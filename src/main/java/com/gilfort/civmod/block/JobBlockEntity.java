package com.gilfort.civmod.block;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.registry.ModBlockEntityTypes;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
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
 * Block entity of a {@link JobBlock}: 27-slot storage (default drop-off), JobBlock level, a Clerk's Ledgers and a client-visible copy
 * of the settlement connection state. The settlement data itself is authoritative.
 */
public class JobBlockEntity extends BaseContainerBlockEntity {

    /** The shrine does not glow. */
    public static final int GLOW_NONE = 0;
    /** The shrine glows; a solid block above hides the beam, so only sparks rise. */
    public static final int GLOW_SPARKS = 1;
    /** The shrine glows and a beam rises like a beacon's. */
    public static final int GLOW_BEAM = 2;


    /** Number of storage slots. */
    public static final int SLOTS = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private int jobLevel = SettlementRules.MIN_LEVEL;
    /** Ledgers used on a Clerk JobBlock (vault {@code Jobs/Clerk.md}); the settlement entry keeps a copy. */
    private int ledgers;
    private int settlementId = Settlement.NO_SETTLEMENT;
    private boolean connected = true;
    /** The shrine glow of a Center Block; client-visible, not saved. */
    private int glow = GLOW_NONE;
    /** Counts every change of the slots (not saved): the Clerk stock view re-reads them when it moves. */
    private int changes;

    /**
     * @param pos   the block position
     * @param state the block state
     */
    public JobBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntityTypes.JOB_BLOCK.get(), pos, state);
    }

    /**
     * @return a counter that moves on every change of the slots (and on other changes of the block entity)
     */
    public int changes() {
        return changes;
    }

    @Override
    public void setChanged() {
        changes++;
        super.setChanged();
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        changes++;
        return super.removeItemNoUpdate(slot);
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
     * @return the Ledgers used on this Clerk JobBlock (0 for any other JobBlock)
     */
    public int getLedgers() {
        return ledgers;
    }

    /**
     * Sets the Ledgers after one was used (the settlement entry is updated separately).
     *
     * @param ledgers the new count
     */
    void setLedgers(int ledgers) {
        this.ledgers = Math.clamp(ledgers, 0, SettlementRules.MAX_LEDGERS);
        setChanged();
    }

    /**
     * @return the settlement id, {@link Settlement#NO_SETTLEMENT} if not registered yet
     */
    public int getSettlementId() {
        return settlementId;
    }

    /**
     * @return how the Center Block's shrine glows: {@link #GLOW_NONE}, {@link #GLOW_SPARKS} or {@link #GLOW_BEAM}
     */
    public int getGlow() {
        return glow;
    }

    /**
     * Sets the shrine glow and syncs it (not saved: the glow is recomputed every second).
     *
     * @param glow {@link #GLOW_NONE}, {@link #GLOW_SPARKS} (glowing under a solid block) or {@link #GLOW_BEAM}
     */
    public void setGlow(int glow) {
        if (this.glow == glow) {
            return;
        }
        this.glow = glow;
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * @return whether the block is not connected to a settlement chain, also without a settlement
     */
    public boolean isUnconnected() {
        return !connected;
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
        output.putInt("ledgers", ledgers);
        output.putInt("settlement", settlementId);
        output.putBoolean("connected", connected);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        changes++;
        jobLevel = SettlementRules.clampLevel(input.getIntOr("job_level", SettlementRules.MIN_LEVEL));
        ledgers = Math.clamp(input.getIntOr("ledgers", 0), 0, SettlementRules.MAX_LEDGERS);
        settlementId = input.getIntOr("settlement", Settlement.NO_SETTLEMENT);
        connected = input.getBooleanOr("connected", true);
        glow = input.getIntOr("glow", GLOW_NONE);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("job_level", jobLevel);
        tag.putInt("settlement", settlementId);
        tag.putBoolean("connected", connected);
        tag.putInt("glow", glow);
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
        ledgers = Math.clamp(components.getOrDefault(ModDataComponents.LEDGERS.get(), 0), 0, SettlementRules.MAX_LEDGERS);
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
        // Without Ledgers the item stays plain, so it stacks with new Clerk JobBlocks.
        if (ledgers > 0) {
            components.set(ModDataComponents.LEDGERS.get(), ledgers);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // The settlement may have changed while this chunk was unloaded (e.g. auto-connect); clients receive the
        // corrected state with the chunk data, so no block update is sent here.
        if (level instanceof ServerLevel serverLevel) {
            SettlementData.get(serverLevel).unsettledAt(worldPosition).ifPresent(block -> {
                settlementId = Settlement.NO_SETTLEMENT;
                connected = false;
                syncRegistered(serverLevel, block);
            });
            JobBlockSite.at(serverLevel, worldPosition).ifPresent(site -> {
                settlementId = site.settlement().id();
                connected = site.block().connected();
                syncRegistered(serverLevel, site.block());
            });
        }
    }

    /**
     * Placement registers with the default level and no Ledgers before the block entity exists; this syncs the real
     * ones. Deferred: onLoad may run during chunk loading, where other blocks must not be touched.
     */
    private void syncRegistered(ServerLevel serverLevel, SettlementBlock registered) {
        if (registered.level() != jobLevel) {
            int target = jobLevel;
            serverLevel.getServer().execute(() -> SettlementWorld.setLevel(serverLevel, worldPosition, target));
        }
        if (registered.ledgers() != ledgers) {
            int target = ledgers;
            serverLevel.getServer().execute(() -> SettlementWorld.setLedgers(serverLevel, worldPosition, target));
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
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel serverLevel) {
            ClerkStorage.forgetStock(serverLevel, this);
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
        changes++;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    /**
     * Sends the menu's info snapshot to the client on every way the menu opens: a click on the block, and also vanilla
     * opening it without CivMod's code (a player in spectator mode), which sends no other data.
     */
    @Override
    public void writeClientSideData(AbstractContainerMenu menu, net.minecraft.network.RegistryFriendlyByteBuf buffer) {
        if (menu instanceof com.gilfort.civmod.menu.JobBlockMenu jobBlockMenu) {
            JobBlockInfo.STREAM_CODEC.encode(buffer, jobBlockMenu.getInfo());
        }
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
