package com.gilfort.civmod.registry;

import com.gilfort.civmod.CivMod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry holder for all blocks added by CivMod.
 * <p>
 * Every block needs a matching block item in {@link ModItems}, a blockstate, a model and a
 * {@code block.civmod.<name>} entry in {@code en_us.json}.
 */
public final class ModBlocks {

    /** Deferred register for blocks in the {@code civmod} namespace. */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CivMod.MOD_ID);

    private ModBlocks() {
    }

    /**
     * Attaches the block register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
