package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.mission.OutcomeRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * What the "Missions" tab of a Mission Board shows (vault {@code Systems/Missions.md}): the daily offer, the missions
 * the board's settlers took and the settlers a mission can be assigned to. The server reads it while the window is open
 * (which also refills the offer on a new day) and sends it whenever it changed ({@link Sync}); the client only renders.
 *
 * @param offer    the daily mission offer
 * @param taken    the missions settlers of this board took (reserved or on the way; loaded settlers only)
 * @param settlers the settlers of this board with their Mission Job (loaded settlers only), for assignments
 * @param rules    the outcome constants, so the client shows the chances of the outcome roll
 * @param available the missions the board's unlocked Mission Jobs may take, which the Auto view lists
 * @param auto     the missions ticked for auto-enable
 */
public record MissionsTabData(List<MissionOffer.Entry> offer, List<TakenMission> taken, List<MissionSettler> settlers, OutcomeRules rules,
        List<Identifier> available, List<Identifier> auto) {

    /** No missions (any other JobBlock, or before the first sync). */
    public static final MissionsTabData EMPTY = new MissionsTabData(List.of(), List.of(), List.of(), OutcomeRules.FALLBACK, List.of(), List.of());

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, MissionsTabData> STREAM_CODEC = StreamCodec.composite(
            MissionOffer.Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), MissionsTabData::offer,
            TakenMission.STREAM_CODEC.apply(ByteBufCodecs.list()), MissionsTabData::taken,
            MissionSettler.STREAM_CODEC.apply(ByteBufCodecs.list()), MissionsTabData::settlers,
            OutcomeRules.STREAM_CODEC, MissionsTabData::rules,
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), MissionsTabData::available,
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), MissionsTabData::auto,
            MissionsTabData::new);

    /**
     * @param offer    the daily mission offer
     * @param taken    the missions settlers of this board took
     * @param settlers the settlers of this board with their Mission Job
     * @param rules    the outcome constants
     * @param available the missions the board's unlocked Mission Jobs may take (the Auto view lists them)
     * @param auto     the missions ticked for auto-enable
     */
    public MissionsTabData {
        offer = List.copyOf(offer);
        taken = List.copyOf(taken);
        settlers = List.copyOf(settlers);
        available = List.copyOf(available);
        auto = List.copyOf(auto);
    }

    /**
     * A settler of a Mission Board that a mission can be assigned to.
     *
     * @param id    the settler UUID
     * @param name  the settler name
     * @param job   its Mission Job
     * @param skill its effective Mission Job skill (capped by the board level)
     */
    public record MissionSettler(UUID id, String name, Job job, int skill) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, MissionSettler> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, MissionSettler::id,
                ByteBufCodecs.STRING_UTF8, MissionSettler::name,
                Job.STREAM_CODEC, MissionSettler::job,
                ByteBufCodecs.VAR_INT, MissionSettler::skill,
                MissionSettler::new);
    }

    /**
     * A mission a settler of the board took: it left the offer.
     *
     * @param settler  the settler name
     * @param mission  the mission id
     * @param departed whether the settler is on its way (otherwise it leaves the next morning)
     */
    public record TakenMission(String settler, Identifier mission, boolean departed) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, TakenMission> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, TakenMission::settler,
                Identifier.STREAM_CODEC, TakenMission::mission,
                ByteBufCodecs.BOOL, TakenMission::departed,
                TakenMission::new);
    }

    /**
     * @param id a settler UUID
     * @return that settler of the board, if loaded
     */
    public Optional<MissionSettler> settler(UUID id) {
        return settlers.stream().filter(settler -> settler.id().equals(id)).findFirst();
    }

    /**
     * @return the settlers as the pick rule sees them (same order as {@link #settlers()})
     */
    public List<MissionRules.Picker> pickers() {
        return settlers.stream().map(settler -> new MissionRules.Picker(settler.id(), settler.job(), settler.skill())).toList();
    }

    /**
     * @param level the dimension
     * @param pos   the Mission Board position
     * @return the current data of that board; refills the offer on the first read of a new day
     */
    public static MissionsTabData of(ServerLevel level, BlockPos pos) {
        List<SettlementBlock.Assignee> assignees = JobBlockSite.blockAt(level, pos).map(SettlementBlock::assignees).orElse(List.of());
        List<TakenMission> taken = new ArrayList<>();
        List<MissionSettler> settlers = new ArrayList<>();
        for (SettlementBlock.Assignee assignee : assignees) {
            if (!(level.getEntity(assignee.id()) instanceof Settler settler)) {
                continue;
            }
            settler.getWork().missionTrip().ifPresent(trip -> taken.add(new TakenMission(assignee.name(), trip.mission(), trip.departed())));
            settler.getJobs().stream().filter(job -> job.jobBlock().equals(pos) && job.jobType().category() == JobCategory.MISSION).findFirst()
                    .ifPresent(job -> settlers.add(new MissionSettler(assignee.id(), assignee.name(), job.jobType(),
                            settler.getWork().effectiveLevel(job))));
        }
        List<MissionOffer.Entry> offer = SettlementWorld.missionOffer(level, pos);
        SettlementData data = SettlementData.get(level);
        List<Identifier> available = MissionOffers.available(data, pos, Missions.all(level.registryAccess()));
        List<Identifier> auto = JobBlockSite.blockAt(level, pos).map(block -> block.missionOffer().auto().stream()
                .sorted(Comparator.comparing(Identifier::toString)).toList()).orElse(List.of());
        return new MissionsTabData(offer, taken, settlers, OutcomeRules.get(), available, auto);
    }

    /**
     * Server → client: the Missions tab of the open menu changed.
     *
     * @param containerId the menu's container id
     * @param tab         the tab
     */
    public record Sync(int containerId, MissionsTabData tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("missions_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                MissionsTabData.STREAM_CODEC, Sync::tab,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: mark or unmark an offered mission. The client names the mission it saw at that place; if the
     * offer changed since (a refill, another player), nothing happens.
     *
     * @param containerId the menu's container id
     * @param index       the offer entry
     * @param mission     the mission the client saw there
     * @param marked      the wanted mark
     */
    public record Mark(int containerId, int index, Identifier mission, boolean marked) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Mark> TYPE = new Type<>(CivMod.id("missions_tab_mark"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Mark> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Mark::containerId,
                ByteBufCodecs.VAR_INT, Mark::index,
                Identifier.STREAM_CODEC, Mark::mission,
                ByteBufCodecs.BOOL, Mark::marked,
                Mark::new);

        @Override
        public Type<Mark> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: tick or untick a mission for auto-enable.
     *
     * @param containerId the menu's container id
     * @param mission     the mission
     * @param enabled     the wanted tick
     */
    public record AutoMission(int containerId, Identifier mission, boolean enabled) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<AutoMission> TYPE = new Type<>(CivMod.id("missions_tab_auto"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, AutoMission> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AutoMission::containerId,
                Identifier.STREAM_CODEC, AutoMission::mission,
                ByteBufCodecs.BOOL, AutoMission::enabled,
                AutoMission::new);

        @Override
        public Type<AutoMission> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: assign an offered mission to a settler of the board, named by its UUID, or unlock it. Like
     * {@link Mark}, the client names the mission it saw at that place.
     *
     * @param containerId the menu's container id
     * @param index       the offer entry
     * @param mission     the mission the client saw there
     * @param settler     the settler, empty to unlock the mission
     */
    public record Assign(int containerId, int index, Identifier mission, Optional<UUID> settler) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Assign> TYPE = new Type<>(CivMod.id("missions_tab_assign"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Assign> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Assign::containerId,
                ByteBufCodecs.VAR_INT, Assign::index,
                Identifier.STREAM_CODEC, Assign::mission,
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), Assign::settler,
                Assign::new);

        @Override
        public Type<Assign> type() {
            return TYPE;
        }
    }
}
