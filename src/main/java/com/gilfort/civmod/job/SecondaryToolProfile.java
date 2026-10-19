package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.function.Predicate;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.world.item.ItemStack;

/**
 * A built-in Resource Collection profile that wants a second tool by its JobBlock's settings (CivMod's Lumberjack:
 * shears while "collect leaves" is set, vault {@code Jobs/Lumberjack.md}). Internal: not part of the addon API, the
 * Work rules fetch the tool and hand it to {@link #produce(ResourceCollectionProfile.Context, Optional)}.
 */
interface SecondaryToolProfile extends ResourceCollectionProfile {

    /**
     * @param block the JobBlock
     * @return the second tool the job wants with these settings, if any
     */
    Optional<Predicate<ItemStack>> secondaryTool(SettlementBlock block);

    /**
     * Produces one resource with the second tool at hand.
     *
     * @param context   the production context
     * @param secondary the settler's best second tool, empty if the settings want none or it has none
     * @return the produced items and the tool to wear, empty if nothing was produced
     */
    Optional<Produced> produce(Context context, Optional<ItemStack> secondary);

    @Override
    default Optional<Produced> produce(Context context) {
        return produce(context, Optional.empty());
    }
}
