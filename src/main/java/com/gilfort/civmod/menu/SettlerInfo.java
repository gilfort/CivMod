package com.gilfort.civmod.menu;

import java.util.List;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;

/**
 * Snapshot of a settler shown in its GUI; sent to the client when the menu opens.
 *
 * @param name           the settler name
 * @param status         the status text
 * @param homeless       whether the settler belongs to no settlement
 * @param settlementName the settlement name (empty if homeless)
 * @param jobs           the assigned jobs in priority order (Laborer excluded)
 */
public record SettlerInfo(Component name, Component status, boolean homeless, Component settlementName, List<SettlerJob> jobs) {

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, SettlerInfo> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.STREAM_CODEC, SettlerInfo::name,
            ComponentSerialization.STREAM_CODEC, SettlerInfo::status,
            ByteBufCodecs.BOOL, SettlerInfo::homeless,
            ComponentSerialization.STREAM_CODEC, SettlerInfo::settlementName,
            SettlerJob.STREAM_CODEC.apply(ByteBufCodecs.list()), SettlerInfo::jobs,
            SettlerInfo::new);

    /**
     * @param settler a settler (server side)
     * @return its current info
     */
    public static SettlerInfo of(Settler settler) {
        Component settlementName = Component.empty();
        if (settler.level() instanceof ServerLevel level && !settler.isHomeless()) {
            settlementName = SettlementData.get(level).settlement(settler.getSettlementId())
                    .map(Settlement::displayName).orElse(Component.empty());
        }
        return new SettlerInfo(settler.getName(), settler.statusText(), settler.isHomeless(), settlementName, settler.getJobs());
    }

    /**
     * @param jobs the new job list
     * @return a copy with the given jobs
     */
    public SettlerInfo withJobs(List<SettlerJob> jobs) {
        return new SettlerInfo(name, status, homeless, settlementName, List.copyOf(jobs));
    }
}
