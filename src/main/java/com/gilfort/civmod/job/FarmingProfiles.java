package com.gilfort.civmod.job;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.FarmCurve;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.SimpleCollectionProfile;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * The plant collecting Jobs of the Gatherer JobBlock (vault {@code Jobs/Gatherer.md}, {@code Jobs/Plantation Farmer.md}):
 * {@link SimpleCollectionProfile}s over a block tag each, sharing one farm curve.
 */
public final class FarmingProfiles {

    /** What the Gatherer collects from: wild bushes, grass, ferns, mushrooms, leaf litter. */
    public static final TagKey<Block> GATHERABLES = TagKey.create(Registries.BLOCK, CivMod.id("gatherables"));
    /** What the Plantation Farmer collects from: sugar cane, bamboo, cactus, cocoa, vines, melons, pumpkins, kelp, sea pickles. */
    public static final TagKey<Block> PLANTATIONS = TagKey.create(Registries.BLOCK, CivMod.id("plantations"));
    /** The farm curve of the plant Jobs: {@code k} 0.005, cap 100 / 350 / 750 / 1350 / 2000 counted plants. */
    public static final FarmCurve PLANT_CURVE = new FarmCurve(0.005, List.of(100, 350, 750, 1350, 2000));

    private FarmingProfiles() {
    }

    /**
     * @return the Gatherer's profile: bare-handed, the loot of the ripe {@link #GATHERABLES}
     */
    public static ResourceCollectionProfile gatherer() {
        return SimpleCollectionProfile.builder(GATHERABLES).build();
    }

    /**
     * @return the Plantation Farmer's profile: an axe, the loot of the {@link #PLANTATIONS}, each block of a stacked
     *         plant counted
     */
    public static ResourceCollectionProfile plantationFarmer() {
        return SimpleCollectionProfile.builder(PLANTATIONS).tool(ItemTags.AXES, () -> Items.STONE_AXE).build();
    }
}
