package com.gilfort.civmod.gametest;

import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.origin.OriginPlaces;
import com.gilfort.civmod.origin.Origins;

import net.minecraft.gametest.framework.GameTestServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Keeps the world generator out of the GameTests: on the GameTest server every settlement's biome lookup finds nothing, so the
 * settlements that tests found do not search the climate noise (a settler then comes "from far away"). A test about the origins
 * scripts its own lookup and gives it back with {@link #restore}. The class exists only in the GameTest source set.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class QuietOrigins {

    /** A lookup that finds no biome. */
    public static final OriginPlaces.BiomeLookup NO_HITS = (level, origin, biomeType) -> Optional.empty();

    private QuietOrigins() {
    }

    @SubscribeEvent
    static void onServerStarting(ServerStartingEvent event) {
        // The gametest source set is part of every dev run: only quiet the lookup on the GameTest server.
        if (event.getServer() instanceof GameTestServer) {
            OriginPlaces.script(NO_HITS);
        }
    }

    /** Gives back the quiet lookup and the level's own random source for the origin rolls. */
    static void restore() {
        OriginPlaces.script(NO_HITS);
        Origins.script(null);
    }
}
