package com.gilfort.civmod.menu;

import java.util.Optional;

import com.gilfort.civmod.settlement.JobType;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;

/**
 * Snapshot of a JobBlock's state shown in the info tab; sent to the client when the menu opens.
 *
 * @param jobType        the offered job
 * @param level          the JobBlock level
 * @param settlementName the settlement name (or "unnamed" text)
 * @param inSettlement   whether the block belongs to a settlement
 * @param connected      whether the block is connected to the settlement chain
 * @param distance       distance to the nearest connected JobBlock (unconnected only)
 * @param required       distance needed to connect (unconnected only)
 * @param assignee       name of the settler working this JobBlock, empty if free
 * @param collectLeaves  whether a Lumberjack also collects leaves
 */
public record JobBlockInfo(JobType jobType, int level, Component settlementName, boolean inSettlement, boolean connected,
        int distance, int required, String assignee, boolean collectLeaves) {

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, JobBlockInfo> STREAM_CODEC = StreamCodec.composite(
            JobType.STREAM_CODEC, JobBlockInfo::jobType,
            ByteBufCodecs.VAR_INT, JobBlockInfo::level,
            ComponentSerialization.STREAM_CODEC, JobBlockInfo::settlementName,
            ByteBufCodecs.BOOL, JobBlockInfo::inSettlement,
            ByteBufCodecs.BOOL, JobBlockInfo::connected,
            ByteBufCodecs.VAR_INT, JobBlockInfo::distance,
            ByteBufCodecs.VAR_INT, JobBlockInfo::required,
            ByteBufCodecs.STRING_UTF8, JobBlockInfo::assignee,
            ByteBufCodecs.BOOL, JobBlockInfo::collectLeaves,
            JobBlockInfo::new);

    /**
     * @param level    the dimension
     * @param pos      the JobBlock position
     * @param jobType  the offered job
     * @param jobLevel the JobBlock level
     * @return the current info of that JobBlock
     */
    public static JobBlockInfo of(ServerLevel level, BlockPos pos, JobType jobType, int jobLevel) {
        SettlementManager.Status status = SettlementWorld.status(level, pos);
        Optional<Settlement> settlement = status.settlement();
        return new JobBlockInfo(jobType, jobLevel,
                settlement.map(Settlement::displayName).orElse(Component.empty()),
                settlement.isPresent(), status.connected(), status.distance(), status.required(),
                settlement.flatMap(s -> s.block(pos)).flatMap(SettlementBlock::assigneeName).orElse(""),
                settlement.flatMap(s -> s.block(pos)).map(SettlementBlock::collectLeaves).orElse(false));
    }

    /**
     * @return a copy without assignee
     */
    public JobBlockInfo withoutAssignee() {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, "", collectLeaves);
    }

    /**
     * @return a copy with leaf collection toggled
     */
    public JobBlockInfo withLeavesToggled() {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, assignee, !collectLeaves);
    }

    /**
     * @param jobType  the offered job
     * @param jobLevel the JobBlock level
     * @return info without settlement data
     */
    public static JobBlockInfo unknown(JobType jobType, int jobLevel) {
        return new JobBlockInfo(jobType, jobLevel, Component.empty(), false, false, 0, 0, "", false);
    }
}
