package com.gilfort.civmod.registry;

import java.util.function.Supplier;

import com.gilfort.civmod.CivMod;
import com.mojang.serialization.Codec;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Registry holder for all data attachment types of CivMod.
 */
public final class ModAttachments {

    /** Deferred register for attachment types in the {@code civmod} namespace. */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, CivMod.MOD_ID);

    /**
     * The Remaining Heat of a station a fuel-burning Producing Job works at (vault {@code Jobs/Smelter.md}), in ticks of
     * vanilla burn time, kept on the station's block entity: it survives the day and the settler, and goes with the
     * block when it is broken.
     */
    public static final Supplier<AttachmentType<Integer>> REMAINING_HEAT = ATTACHMENT_TYPES.register("remaining_heat",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("ticks"), heat -> heat > 0).build());

    private ModAttachments() {
    }

    /**
     * Attaches the attachment type register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
