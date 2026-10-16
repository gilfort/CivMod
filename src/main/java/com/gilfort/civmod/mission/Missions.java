package com.gilfort.civmod.mission;

import java.util.LinkedHashMap;
import java.util.Map;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.NewDatapackRegistryEvent;

/**
 * The {@code civmod:mission} datapack registry: missions are loaded at server start from
 * {@code data/<namespace>/civmod/mission/} and synced to clients (the board GUI shows them).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class Missions {

    /** Registry key of {@code civmod:mission}. */
    public static final ResourceKey<Registry<Mission>> KEY = ResourceKey.createRegistryKey(CivMod.id("mission"));

    private Missions() {
    }

    @SubscribeEvent
    static void onNewRegistry(NewDatapackRegistryEvent event) {
        event.worldRegistry(KEY, Mission.CODEC, Mission.CODEC);
    }

    /**
     * @param registries the registry access of a level
     * @return all missions by id, in registry order
     */
    public static Map<Identifier, Mission> all(HolderLookup.Provider registries) {
        Map<Identifier, Mission> missions = new LinkedHashMap<>();
        registries.lookupOrThrow(KEY).listElements().forEach(holder -> missions.put(holder.key().identifier(), holder.value()));
        return missions;
    }
}
