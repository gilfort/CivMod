package com.gilfort.civmod.job;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Which block states a collecting Job counts and which state it rolls the loot of (vault
 * {@code Systems/Resource Collection Jobs.md}): plants count only when ripe, so an unripe berry bush gives nothing.
 * A scan counts by block type ({@link BlockCountScan}); the roll uses the type's ripe state, whose loot the plant drops
 * when harvested.
 * <ul>
 * <li>Crops ({@link CropBlock}, also modded ones): only at their maximum age.</li>
 * <li>Sweet berry bushes: age 2 or 3 (with berries); the roll uses age 3.</li>
 * <li>Cocoa and nether wart: only at their maximum age.</li>
 * <li>Cave vines: only with glow berries.</li>
 * <li>Two-block plants (tall grass, large fern): only the lower half, so each plant counts once.</li>
 * <li>Every other block: any state; the roll uses its default state.</li>
 * </ul>
 */
public final class PlantStates {

    /** The youngest age at which a sweet berry bush carries berries. */
    private static final int BERRY_BUSH_RIPE_AGE = 2;

    private PlantStates() {
    }

    /**
     * @param state a block state of a counted type
     * @return whether a collecting Job counts it (ripe, the lower half of a two-block plant)
     */
    public static boolean counts(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (block instanceof SweetBerryBushBlock) {
            return state.getValue(SweetBerryBushBlock.AGE) >= BERRY_BUSH_RIPE_AGE;
        }
        if (block instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.AGE) == CocoaBlock.MAX_AGE;
        }
        if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) == NetherWartBlock.MAX_AGE;
        }
        if (state.hasProperty(CaveVines.BERRIES)) {
            return state.getValue(CaveVines.BERRIES);
        }
        return true;
    }

    /**
     * @param block a counted block type
     * @return the state whose loot a roll of that type produces (its ripe state, else the default state)
     */
    public static BlockState rolled(Block block) {
        BlockState state = block.defaultBlockState();
        if (block instanceof CropBlock crop) {
            return crop.getStateForAge(crop.getMaxAge());
        }
        if (block instanceof SweetBerryBushBlock) {
            return state.setValue(SweetBerryBushBlock.AGE, SweetBerryBushBlock.MAX_AGE);
        }
        if (block instanceof CocoaBlock) {
            return state.setValue(CocoaBlock.AGE, CocoaBlock.MAX_AGE);
        }
        if (block instanceof NetherWartBlock) {
            return state.setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE);
        }
        if (state.hasProperty(CaveVines.BERRIES)) {
            return state.setValue(CaveVines.BERRIES, true);
        }
        return state;
    }
}
