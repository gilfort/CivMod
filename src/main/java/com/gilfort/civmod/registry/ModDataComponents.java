package com.gilfort.civmod.registry;

import java.util.function.Supplier;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.item.ScrollBinding;
import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.Item;
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

    /**
     * Starter tool of a new JobBlock item: put into the block's inventory on its first placement. A broken JobBlock
     * drops without it, so breaking and re-placing never creates tools.
     */
    public static final Supplier<DataComponentType<Holder<Item>>> STARTER_TOOL = DATA_COMPONENTS.registerComponentType("starter_tool",
            builder -> builder.persistent(Item.CODEC).networkSynchronized(Item.STREAM_CODEC));

    /** What a Scroll of Assignment is bound to. */
    public static final Supplier<DataComponentType<ScrollBinding>> SCROLL_BINDING = DATA_COMPONENTS.registerComponentType("scroll_binding",
            builder -> builder.persistent(ScrollBinding.CODEC).networkSynchronized(ScrollBinding.STREAM_CODEC));

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
