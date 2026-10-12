package com.gilfort.civmod.registry;

import com.gilfort.civmod.CivMod;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry holder for all items (including block items) added by CivMod.
 * <p>
 * Every item needs a matching {@code item.civmod.<name>} entry in {@code en_us.json}.
 */
public final class ModItems {

    /** Deferred register for items in the {@code civmod} namespace. */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CivMod.MOD_ID);

    /** Item of the {@link ModBlocks#LUMBERJACK_BLOCK}. */
    public static final DeferredItem<BlockItem> LUMBERJACK_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.LUMBERJACK_BLOCK);
    /** Item of the {@link ModBlocks#MINER_BLOCK}. */
    public static final DeferredItem<BlockItem> MINER_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.MINER_BLOCK);
    /** Item of the {@link ModBlocks#FISHERMAN_BLOCK}. */
    public static final DeferredItem<BlockItem> FISHERMAN_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.FISHERMAN_BLOCK);

    /** Render-only item for the red X above unconnected JobBlocks; not obtainable. */
    public static final DeferredItem<Item> UNCONNECTED_MARKER = ITEMS.registerSimpleItem("unconnected_marker");

    private ModItems() {
    }

    /**
     * Attaches the item register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
