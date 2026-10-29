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

    /** The farm curve of the Animal Farmer: {@code k} 0.1, cap 20 / 40 / 60 / 80 / 100 counted animals. */
    public static final FarmCurve ANIMAL_CURVE = new FarmCurve(0.1, List.of(20, 40, 60, 80, 100));
    /** The farm curve of the Beekeeper: {@code k} 0.2, cap 10 / 20 / 30 / 40 / 50 counted hives. */
    public static final FarmCurve HIVE_CURVE = new FarmCurve(0.2, List.of(10, 20, 30, 40, 50));

    private FarmingProfiles() {
    }

    /**
     * @return the Gatherer's profile: bare-handed, the loot of the ripe {@link #GATHERABLES}
     */
    public static ResourceCollectionProfile gatherer() {
        return SimpleCollectionProfile.builder(GATHERABLES).dropPool(CivMod.id("gatherer")).build();
    }

    /**
     * @return the Plantation Farmer's profile: an axe, the loot of the {@link #PLANTATIONS}, each block of a stacked
     *         plant counted
     */
    public static ResourceCollectionProfile plantationFarmer() {
        return SimpleCollectionProfile.builder(PLANTATIONS).tool(ItemTags.AXES, () -> Items.STONE_AXE).dropPool(CivMod.id("plantation_farmer")).build();
    }
}
