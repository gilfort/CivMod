package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.job.BiomeBonuses;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.OutcomeRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * Snapshot of a JobBlock's state shown in the header line and the Workers tab; sent to the client when the menu opens.
 *
 * @param jobType        the offered job
 * @param level          the JobBlock level
 * @param settlementName the settlement name (or "unnamed" text)
 * @param inSettlement   whether the block belongs to a settlement
 * @param connected      whether the block is connected to the settlement chain
 * @param distance       distance to the nearest connected JobBlock (unconnected only)
 * @param required       distance needed to connect (unconnected only)
 * @param workers        the settlers working this JobBlock: Job, level and mission state (the Workers tab)
 * @param places         the offered Jobs with taken / places
 * @param collectLeaves  whether a Lumberjack also collects leaves
 * @param byDeliveryman  whether Deliverymen carry a Craftsperson's results ("Delivery: Deliveryman")
 * @param missionTab   the missions and Mission Job settlers (Mission Board only, else empty)
 * @param biome          today's biome bonus line and the "?" overview of the Job's biome bonuses
 */
public record JobBlockInfo(Job jobType, int level, Component settlementName, boolean inSettlement, boolean connected,
        int distance, int required, List<Worker> workers, List<JobPlaces.Line> places, boolean collectLeaves, boolean byDeliveryman,
        MissionTab missionTab, BiomeInfo biome) {

    /**
     * The biome bonuses of the JobBlock's Job (vault {@code Systems/JobBlock.md}, decisions on biome bonuses).
     *
     * @param biome    today's biome, empty while no biome is known
     * @param effects  its effects ({@link BiomeBonusText#effects}), empty without an effect
     * @param overview the "?" overview of every biome with an effect for the Job ({@link BiomeBonusText#overview})
     */
    public record BiomeInfo(Optional<Component> biome, List<Component> effects, List<Component> overview) {

        /** No biome known, no overview (before the first sync). */
        public static final BiomeInfo NONE = new BiomeInfo(Optional.empty(), List.of(), List.of());

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, BiomeInfo> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs::optional), BiomeInfo::biome,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), BiomeInfo::effects,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), BiomeInfo::overview,
                BiomeInfo::new);

        /**
         * @param biome    today's biome
         * @param effects  its effects
         * @param overview the overview lines
         */
        public BiomeInfo {
            effects = List.copyOf(effects);
            overview = List.copyOf(overview);
        }

        /** @return today's biome with its effects ("Desert · Ore chance ×1.5"), empty while no biome is known */
        public Optional<Component> today() {
            return biome.map(name -> BiomeBonusText.line(name, effects));
        }

        /**
         * Today's biome of the first loaded settler working the JobBlock that has one; without such a settler the
         * biome the Job reads without a work place (a Logistics Job's JobBlock).
         *
         * @param level     the level
         * @param pos       the JobBlock position
         * @param job       the JobBlock's Job
         * @param assignees the settlers working it
         * @return the info
         */
        static BiomeInfo of(ServerLevel level, BlockPos pos, Job job, List<SettlementBlock.Assignee> assignees) {
            Job read = job;
            BiomeBonus today = BiomeBonus.NONE;
            for (SettlementBlock.Assignee assignee : assignees) {
                if (level.getEntity(assignee.id()) instanceof Settler settler) {
                    today = CivModApi.biomeBonus(settler, pos);
                    read = assignee.job();
                    if (today.biome().isPresent()) {
                        break;
                    }
                }
            }
            if (today.biome().isEmpty()) {
                read = job;
                today = BiomeBonuses.today(level, job, pos, null);
            }
            return new BiomeInfo(BiomeBonusText.biome(today), BiomeBonusText.effects(read, today), BiomeBonusText.overview(job));
        }
    }

    /**
     * Server → client: the info changed on the server beyond what the client predicted (an automatic assignment
     * toggle switched on assigned settlers).
     *
     * @param containerId the menu's container id
     * @param info        the new info
     */
    public record Sync(int containerId, JobBlockInfo info) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("job_block_info"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                JobBlockInfo.STREAM_CODEC, Sync::info,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * What the missions tab of a Mission Board shows.
     *
     * @param offer    the daily mission offer
     * @param taken    the missions settlers of this board took (reserved or on the way; loaded settlers only)
     * @param settlers the settlers of this board with their Mission Job (loaded settlers only), for assignments
     * @param rules    the outcome constants, so the client shows the chances of the outcome roll
     */
    public record MissionTab(List<MissionOffer.Entry> offer, List<TakenMission> taken, List<MissionSettler> settlers, OutcomeRules rules) {

        /** No missions (any other JobBlock). */
        public static final MissionTab EMPTY = new MissionTab(List.of(), List.of(), List.of(), OutcomeRules.FALLBACK);

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, MissionTab> STREAM_CODEC = StreamCodec.composite(
                MissionOffer.Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), MissionTab::offer,
                TakenMission.STREAM_CODEC.apply(ByteBufCodecs.list()), MissionTab::taken,
                MissionSettler.STREAM_CODEC.apply(ByteBufCodecs.list()), MissionTab::settlers,
                OutcomeRules.STREAM_CODEC, MissionTab::rules,
                MissionTab::new);

        /**
         * @param offer    the daily mission offer
         * @param taken    the missions settlers of this board took
         * @param settlers the settlers of this board with their Mission Job
         * @param rules    the outcome constants
         */
        public MissionTab {
            offer = List.copyOf(offer);
            taken = List.copyOf(taken);
            settlers = List.copyOf(settlers);
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

        /** A copy with one offer entry changed. */
        MissionTab withEntry(int index, UnaryOperator<MissionOffer.Entry> change) {
            if (index < 0 || index >= offer.size()) {
                return this;
            }
            List<MissionOffer.Entry> changed = new ArrayList<>(offer);
            changed.set(index, change.apply(offer.get(index)));
            return new MissionTab(changed, taken, settlers, rules);
        }
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
     * A row of the Workers tab: a settler working the JobBlock (vault {@code Systems/GUI.md}, "Info becomes Workers").
     *
     * @param assignee the settler and the Job it holds here
     * @param loaded   whether the settler entity is loaded (only then its window opens and its level is known)
     * @param level    its skill level in that Job, 0 while unloaded
     * @param skin     its skin index ({@code Settler#getSkin}), -1 while unloaded
     * @param away     the mission it is away on, empty at home (also while it only reserved one)
     */
    public record Worker(SettlementBlock.Assignee assignee, boolean loaded, int level, int skin, Optional<Away> away) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Worker> STREAM_CODEC = StreamCodec.composite(
                SettlementBlock.Assignee.STREAM_CODEC, Worker::assignee,
                ByteBufCodecs.BOOL, Worker::loaded,
                ByteBufCodecs.VAR_INT, Worker::level,
                ByteBufCodecs.INT, Worker::skin,
                Away.STREAM_CODEC.apply(ByteBufCodecs::optional), Worker::away,
                Worker::new);

        /**
         * A mission a worker is away on.
         *
         * @param mission  the mission id
         * @param daysLeft the days until it is back ({@link #daysLeft})
         */
        public record Away(Identifier mission, int daysLeft) {

            /** Network codec. */
            public static final StreamCodec<RegistryFriendlyByteBuf, Away> STREAM_CODEC = StreamCodec.composite(
                    Identifier.STREAM_CODEC, Away::mission,
                    ByteBufCodecs.VAR_INT, Away::daysLeft,
                    Away::new);
        }

        /** A worker whose entity is not loaded: name and Job only. */
        static Worker unloaded(SettlementBlock.Assignee assignee) {
            return new Worker(assignee, false, 0, -1, Optional.empty());
        }

        /** A loaded worker at the world time {@code now}. */
        static Worker of(SettlementBlock.Assignee assignee, Settler settler, long now) {
            Optional<Away> away = settler.getWork().missionTrip().filter(MissionTrip::departed)
                    .map(trip -> new Away(trip.mission(), daysLeft(now, trip.returnTime())));
            return new Worker(assignee, true, settler.getSkills().get(assignee.job()).level(), settler.getSkin(), away);
        }

        /**
         * @param now        the world time
         * @param returnTime the world time of the return
         * @return the whole days until then, rounded up, at least 1 ("back in 2 d")
         */
        public static int daysLeft(long now, long returnTime) {
            return (int) Math.max(1L, Math.ceilDiv(returnTime - now, DayClock.DAY_TICKS));
        }

        /**
         * @return whether a click on the head or name opens the settler window (loaded and not away)
         */
        public boolean opensWindow() {
            return loaded && away.isEmpty();
        }
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
     * @return the offered Jobs, in line order
     */
    private List<Job> offeredJobs() {
        return places.stream().map(JobPlaces.Line::job).toList();
    }

    /**
     * @return the Jobs with automatic assignment switched on
     */
    private List<Job> autoJobs() {
        return places.stream().filter(JobPlaces.Line::auto).map(JobPlaces.Line::job).toList();
    }

    /**
     * @param job a Job of the block
     * @param on  the new state of its automatic assignment toggle
     * @return a copy with that toggle set
     */
    public JobBlockInfo withAuto(Job job, boolean on) {
        List<JobPlaces.Line> changed = places.stream()
                .map(line -> line.job() == job ? new JobPlaces.Line(line.job(), line.taken(), line.places(), on) : line).toList();
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, workers, changed, collectLeaves,
                byDeliveryman, missionTab, biome);
    }

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, JobBlockInfo> STREAM_CODEC = StreamCodec.composite(
            Job.STREAM_CODEC, JobBlockInfo::jobType,
            ByteBufCodecs.VAR_INT, JobBlockInfo::level,
            ComponentSerialization.STREAM_CODEC, JobBlockInfo::settlementName,
            ByteBufCodecs.BOOL, JobBlockInfo::inSettlement,
            ByteBufCodecs.BOOL, JobBlockInfo::connected,
            ByteBufCodecs.VAR_INT, JobBlockInfo::distance,
            ByteBufCodecs.VAR_INT, JobBlockInfo::required,
            Worker.STREAM_CODEC.apply(ByteBufCodecs.list()), JobBlockInfo::workers,
            JobPlaces.Line.STREAM_CODEC.apply(ByteBufCodecs.list()), JobBlockInfo::places,
            ByteBufCodecs.BOOL, JobBlockInfo::collectLeaves,
            ByteBufCodecs.BOOL, JobBlockInfo::byDeliveryman,
            MissionTab.STREAM_CODEC, JobBlockInfo::missionTab,
            BiomeInfo.STREAM_CODEC, JobBlockInfo::biome,
            JobBlockInfo::new);

    /**
     * @param level    the dimension
     * @param pos      the JobBlock position
     * @param jobType  the offered job
     * @param jobLevel the JobBlock level
     * @return the current info of that JobBlock
     */
    public static JobBlockInfo of(ServerLevel level, BlockPos pos, Job jobType, int jobLevel) {
        SettlementManager.Status status = SettlementWorld.status(level, pos);
        Optional<Settlement> settlement = status.settlement();
        Optional<SettlementBlock> block = JobBlockSite.blockAt(level, pos);
        List<SettlementBlock.Assignee> assignees = block.map(SettlementBlock::assignees).orElse(List.of());
        List<TakenMission> taken = new ArrayList<>();
        List<MissionSettler> settlers = new ArrayList<>();
        List<Worker> workers = new ArrayList<>();
        long now = level.getOverworldClockTime();
        for (SettlementBlock.Assignee assignee : assignees) {
            if (!(level.getEntity(assignee.id()) instanceof Settler settler)) {
                workers.add(Worker.unloaded(assignee));
                continue;
            }
            workers.add(Worker.of(assignee, settler, now));
            settler.getWork().missionTrip().ifPresent(trip -> taken.add(new TakenMission(assignee.name(), trip.mission(), trip.departed())));
            settler.getJobs().stream().filter(job -> job.jobBlock().equals(pos) && job.jobType().category() == JobCategory.MISSION).findFirst()
                    .ifPresent(job -> settlers.add(new MissionSettler(assignee.id(), assignee.name(), job.jobType(),
                            settler.getWork().effectiveLevel(job))));
        }
        return new JobBlockInfo(jobType, jobLevel,
                settlement.map(Settlement::displayName).orElse(Component.empty()),
                settlement.isPresent(), status.connected(), status.distance(), status.required(),
                List.copyOf(workers), JobPlaces.lines(jobType, jobLevel, block.map(JobPlaces::offered).orElseGet(() -> JobPlaces.offered(jobType)),
                        assignees, block.map(SettlementBlock::autoAssign).orElse(Set.of())),
                block.map(SettlementBlock::collectLeaves).orElse(false), block.map(SettlementBlock::byDeliveryman).orElse(false),
                new MissionTab(SettlementWorld.missionOffer(level, pos), taken, settlers, OutcomeRules.get()),
                BiomeInfo.of(level, pos, jobType, assignees));
    }

    /**
     * @return the settlers working this JobBlock with their Job
     */
    public List<SettlementBlock.Assignee> assignees() {
        return workers.stream().map(Worker::assignee).toList();
    }

    /**
     * @param settler a settler UUID
     * @return a copy without that settler (the client's prediction of ✕ in the Workers tab)
     */
    JobBlockInfo withoutWorker(UUID settler) {
        List<Worker> left = workers.stream().filter(worker -> !worker.assignee().id().equals(settler)).toList();
        String name = workers.stream().filter(worker -> worker.assignee().id().equals(settler)).map(worker -> worker.assignee().name()).findFirst()
                .orElse("");
        List<TakenMission> takenLeft = missionTab.taken().stream().filter(entry -> !entry.settler().equals(name)).toList();
        List<MissionSettler> settlersLeft = missionTab.settlers().stream().filter(entry -> !entry.id().equals(settler)).toList();
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, left,
                JobPlaces.lines(jobType, level, offeredJobs(), left.stream().map(Worker::assignee).toList(), autoJobs()), collectLeaves, byDeliveryman,
                new MissionTab(missionTab.offer(), takenLeft, settlersLeft, missionTab.rules()), biome);
    }

    /**
     * @return a copy with leaf collection toggled
     */
    public JobBlockInfo withLeavesToggled() {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, workers, places, !collectLeaves,
                byDeliveryman, missionTab, biome);
    }

    /**
     * @return a copy with the Craftsperson's delivery toggled (Self / Deliveryman)
     */
    public JobBlockInfo withDeliveryToggled() {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, workers, places, collectLeaves,
                !byDeliveryman, missionTab, biome);
    }

    /**
     * @param index  a mission offer index
     * @param marked the new mark
     * @return a copy with that offer entry marked or unmarked
     */
    public JobBlockInfo withMarked(int index, boolean marked) {
        return withMissionTab(missionTab.withEntry(index, entry -> entry.withMarked(marked)));
    }

    /**
     * @param index    a mission offer index
     * @param assignee the settler the mission is assigned to, empty to unlock it
     * @return a copy with that offer entry assigned or unlocked
     */
    public JobBlockInfo withAssignee(int index, Optional<UUID> assignee) {
        return withMissionTab(missionTab.withEntry(index, entry -> entry.withAssignee(assignee)));
    }

    private JobBlockInfo withMissionTab(MissionTab changed) {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, workers, places, collectLeaves, byDeliveryman,
                changed, biome);
    }

    /**
     * @return the daily mission offer (Mission Board only)
     */
    public List<MissionOffer.Entry> missions() {
        return missionTab.offer();
    }

    /**
     * @return the missions settlers of this board took
     */
    public List<TakenMission> taken() {
        return missionTab.taken();
    }

    /**
     * @param jobType  the offered job
     * @param jobLevel the JobBlock level
     * @return info without settlement data
     */
    public static JobBlockInfo unknown(Job jobType, int jobLevel) {
        return new JobBlockInfo(jobType, jobLevel, Component.empty(), false, false, 0, 0, List.of(), JobPlaces.lines(jobType, jobLevel, List.of()), false, false,
                MissionTab.EMPTY, BiomeInfo.NONE);
    }
}
