package com.gilfort.civmod.registry;

import java.util.function.Supplier;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry holder for all entity types of CivMod, plus their attributes.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ModEntityTypes {

    /** Deferred register for entity types in the {@code civmod} namespace. */
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, CivMod.MOD_ID);

    /** The settler NPC. */
    public static final Supplier<EntityType<Settler>> SETTLER = ENTITY_TYPES.register("settler",
            id -> EntityType.Builder.<Settler>of(Settler::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, id)));

    private ModEntityTypes() {
    }

    @SubscribeEvent
    static void onCreateAttributes(EntityAttributeCreationEvent event) {
        event.put(SETTLER.get(), Settler.createAttributes().build());
    }

    /**
     * Attaches the entity type register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }
}
