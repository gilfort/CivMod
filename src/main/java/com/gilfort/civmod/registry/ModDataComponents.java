package com.gilfort.civmod.registry;

import java.util.function.Supplier;

import com.gilfort.civmod.CivMod;
import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry holder for all data component types of CivMod.
 */
public final class ModDataComponents {

    /** Deferred register for data component types in the {@code civmod} namespace. */
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CivMod.MOD_ID);

    /** Level of a JobBlock item; kept when the block is broken and re-placed. */
    public static final Supplier<DataComponentType<Integer>> JOB_LEVEL = DATA_COMPONENTS.registerComponentType("job_level",
            builder -> builder.persistent(Codec.intRange(1, 5)).networkSynchronized(ByteBufCodecs.VAR_INT));

    private ModDataComponents() {
    }

    /**
     * Attaches the data component register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
    }
}
