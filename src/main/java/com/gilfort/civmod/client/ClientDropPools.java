package com.gilfort.civmod.client;

import java.util.List;
import java.util.Map;

import com.gilfort.civmod.api.DropOutcome;
import com.gilfort.civmod.api.Drops;
import com.gilfort.civmod.job.DropPools;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The drop pools the server sent ({@link DropPools.Sync}), for the recipe viewers' "Job Recipes" category (vault
 * {@code Systems/Producing Jobs.md}).
 */
public final class ClientDropPools {

    private static Map<Identifier, List<DropOutcome>> pools = Map.of();

    private ClientDropPools() {
    }

    /**
     * @param payload the pools
     * @param context the network context
     */
    public static void handle(DropPools.Sync payload, IPayloadContext context) {
        pools = Map.copyOf(payload.pools());
    }

    /**
     * @param drops the {@code drops} of a recipe
     * @return the outcomes to show: the pool's merged outcomes, else the recipe's own
     */
    public static List<DropOutcome> outcomes(Drops drops) {
        return drops.resolve(id -> pools.getOrDefault(id, List.of()));
    }
}
