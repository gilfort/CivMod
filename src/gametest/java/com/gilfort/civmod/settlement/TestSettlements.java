package com.gilfort.civmod.settlement;

import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * The shared Center Block setup of the GameTests: every test that needs a settlement founds it here.
 * <p>
 * The founding goes around the exclusion zones on purpose: tests of a batch stand closer together than the exclusion
 * radius of a center (75), so a real placement would refuse every test but the first. Inside a test everything else
 * follows the normal rules. The Center Block stands {@value #CENTER_HEIGHT} blocks above the JobBlock it serves, clear of
 * floors, work places and walking settlers, and connects that JobBlock at horizontal distance 0.
 */
public final class TestSettlements {

    /** How far above the first JobBlock of a settlement its Center Block stands. */
    private static final int CENTER_HEIGHT = 12;

    private TestSettlements() {
    }

    /**
     * Founds a settlement for a JobBlock position, unless a connected JobBlock already reaches it (the same settlement
     * of the test).
     *
     * @param level the dimension
     * @param pos   the JobBlock position
     * @return the Center Block position, empty if the position was already in reach
     */
    public static Optional<BlockPos> center(ServerLevel level, BlockPos pos) {
        SettlementData data = SettlementData.get(level);
        if (reached(data, pos)) {
            return Optional.empty();
        }
        BlockPos center = pos.above(CENTER_HEIGHT);
        centerAt(level, center);
        return Optional.of(center);
    }

    /**
     * Founds a settlement with a Center Block at exactly this position, whatever reaches it.
     *
     * @param level  the dimension
     * @param center the Center Block position
     */
    public static void centerAt(ServerLevel level, BlockPos center) {
        found(SettlementData.get(level), center);
        level.setBlock(center, ModBlocks.HUMAN_CENTER_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * Puts a JobBlock into the test's settlement, founding one first if nothing reaches it.
     *
     * @param helper   the test
     * @param relative the JobBlock position relative to the test
     * @param block    the JobBlock
     */
    public static void setBlock(GameTestHelper helper, BlockPos relative, Block block) {
        center(helper.getLevel(), helper.absolutePos(relative));
        helper.setBlock(relative, block);
    }

    /**
     * Puts a JobBlock into the world's settlement data, founding one first if nothing reaches it.
     *
     * @param level the dimension
     * @param pos   the JobBlock position
     * @param block the JobBlock
     * @param flags the block update flags
     */
    public static void setBlock(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState block, int flags) {
        center(level, pos);
        level.setBlock(pos, block, flags);
    }

    /**
     * Registers a JobBlock without a block in the world, in a settlement of the given data; founds one first if nothing
     * reaches it, with a Center Block in the data only.
     *
     * @param data  the settlement data, usually fresh
     * @param pos   the JobBlock position
     * @param job   the offered job
     * @param level the JobBlock level
     * @return how the block was integrated
     */
    public static SettlementManager.Placement place(SettlementData data, BlockPos pos, Job job, int level) {
        if (!reached(data, pos)) {
            found(data, pos.above(CENTER_HEIGHT));
        }
        return SettlementManager.place(data, pos, job, level, SettlementManager.Listener.NONE);
    }

    /**
     * Registers a JobBlock without a block in the world, in the settlement data of the test's level; a Center Block that
     * founds a settlement stands in the world, so it goes with the test area.
     *
     * @param helper the test
     * @param pos    the absolute JobBlock position
     * @param job    the offered job
     * @param level  the JobBlock level
     * @return how the block was integrated
     */
    public static SettlementManager.Placement place(GameTestHelper helper, BlockPos pos, Job job, int level) {
        center(helper.getLevel(), pos);
        return SettlementManager.place(SettlementData.get(helper.getLevel()), pos, job, level, SettlementManager.Listener.NONE);
    }

    /**
     * Removes a settlement's Center Block from the world and lets its time run out.
     *
     * @param level      the dimension
     * @param settlement the settlement
     */
    public static void dissolve(ServerLevel level, Settlement settlement) {
        settlement.blocks().stream().filter(SettlementBlock::isCenter).map(SettlementBlock::pos).toList()
                .forEach(center -> level.destroyBlock(center, false));
        SettlementWorld.morning(level, DayClock.day(level) + SettlementRules.CENTERLESS_DAYS + 1);
    }

    /**
     * A settlement as an earlier version saved it: connected JobBlocks, no Center Block and no Center grace.
     *
     * @param data      the settlement data
     * @param positions the JobBlock positions
     * @return the settlement
     */
    public static Settlement earlierVersion(SettlementData data, BlockPos... positions) {
        Settlement settlement = data.create(0L, SettlementColors.DEFAULT, Settlement.SETTLERS_ARRIVED);
        for (BlockPos pos : positions) {
            data.addBlock(settlement, new SettlementBlock(pos, ModJobs.LUMBERJACK.get(), SettlementRules.MIN_LEVEL, true));
        }
        return settlement;
    }

    private static boolean reached(SettlementData data, BlockPos pos) {
        return data.settlementContaining(pos).isPresent();
    }

    private static void found(SettlementData data, BlockPos center) {
        Settlement settlement = data.create(0L, SettlementColors.DEFAULT, Settlement.SETTLERS_ARRIVED);
        data.addBlock(settlement, SettlementBlock.center(center, ModJobs.ADMINISTRATOR.get(), SettlementRules.MIN_LEVEL, Races.HUMAN));
    }
}
