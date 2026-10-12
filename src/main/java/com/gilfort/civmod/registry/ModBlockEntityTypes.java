package com.gilfort.civmod.registry;

import java.util.function.Supplier;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.block.JobBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;

/**
 * Registry holder for all block entity types of CivMod, plus their capabilities.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ModBlockEntityTypes {

    /** Deferred register for block entity types in the {@code civmod} namespace. */
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CivMod.MOD_ID);

    /** Block entity shared by all JobBlocks. */
    public static final Supplier<BlockEntityType<JobBlockEntity>> JOB_BLOCK = BLOCK_ENTITY_TYPES.register("job_block",
            () -> new BlockEntityType<>(JobBlockEntity::new, ModBlocks.JOB_BLOCKS.stream().map(block -> (JobBlock) block.get())
                    .toArray(JobBlock[]::new)));

    private ModBlockEntityTypes() {
    }

    /**
     * Exposes JobBlock storage to item pipes (hoppers use the vanilla container directly).
     *
     * @param event the capability registration event
     */
    @SubscribeEvent
    static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, JOB_BLOCK.get(), (jobBlock, side) -> VanillaContainerWrapper.of(jobBlock));
    }

    /**
     * Attaches the block entity type register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
