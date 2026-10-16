package com.gilfort.civmod.settlement;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A JobBlock as a settler sees it: its settlement block and everything the settler needs from it (level, skill cap,
 * drop-off, collect inventory). The one way to resolve a JobBlock position outside the
 * settlement storage; {@link SettlementData} stays the store behind it.
 * <p>
 * A site is a live view of the saved data: read it where it is needed and do not keep it across ticks (the JobBlock
 * may be removed in between).
 *
 * @param settlement the settlement owning the JobBlock
 * @param block      the JobBlock as recorded by its settlement
 */
public record JobBlockSite(Settlement settlement, SettlementBlock block) {

    /**
     * @param level    the dimension
     * @param jobBlock a JobBlock position
     * @return the site of the JobBlock at that position, empty if no settlement records one there
     */
    public static Optional<JobBlockSite> at(ServerLevel level, BlockPos jobBlock) {
        return at(SettlementData.get(level), jobBlock);
    }

    /**
     * @param level    the dimension
     * @param jobBlock a JobBlock position
     * @return the level of that JobBlock, {@link SettlementRules#MIN_LEVEL} if no settlement records one there
     */
    public static int levelAt(ServerLevel level, BlockPos jobBlock) {
        return at(level, jobBlock).map(JobBlockSite::level).orElse(SettlementRules.MIN_LEVEL);
    }

    /**
     * @param level    the dimension
     * @param jobBlock a JobBlock position
     * @return the JobBlock at that position as recorded by its settlement, empty if no settlement records one there
     */
    public static Optional<SettlementBlock> blockAt(ServerLevel level, BlockPos jobBlock) {
        return blockAt(SettlementData.get(level), jobBlock);
    }

    static Optional<JobBlockSite> at(SettlementData data, BlockPos jobBlock) {
        return data.settlementAt(jobBlock).flatMap(settlement -> settlement.block(jobBlock).map(block -> new JobBlockSite(settlement, block)));
    }

    static Optional<SettlementBlock> blockAt(SettlementData data, BlockPos jobBlock) {
        return at(data, jobBlock).map(JobBlockSite::block);
    }

    /**
     * @return the JobBlock position
     */
    public BlockPos pos() {
        return block.pos();
    }

    /**
     * @return the JobBlock level
     */
    public int level() {
        return block.level();
    }

    /**
     * @return the maximum skill level a settler reaches at this JobBlock
     */
    public int skillCap() {
        return SettlementRules.skillCap(block.level());
    }

    /**
     * @return where output is delivered: the drop-off inventory, else the JobBlock itself
     */
    public BlockPos dropOff() {
        return block.dropOff().orElse(block.pos());
    }

    /**
     * @return where tools are fetched: the collect inventory, else the JobBlock itself
     */
    public BlockPos collect() {
        return block.collect().orElse(block.pos());
    }
}
