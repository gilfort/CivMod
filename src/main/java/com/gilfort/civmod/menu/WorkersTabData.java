package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.BiomeBonuses;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Bonuses;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * What the "Workers" tab of a JobBlock shows and the header tooltip reads (vault {@code Systems/JobBlock.md},
 * {@code Systems/GUI.md}): the block's settlement state, one {@link Worker} per settler, the offered Jobs with their
 * places, the block options, today's biome and the Center Block's blessing. The server reads it while the window is open
 * and sends it whenever it changed ({@link Sync}); the client only renders it. Times are absolute clock values of the
 * overworld clock; the client counts down with the same clock, so a running blessing or mission sends nothing.
 *
 * @param settlementName  the settlement name (or "unnamed" text)
 * @param inSettlement    whether the block belongs to a settlement
 * @param connected       whether the block is connected to the settlement chain
 * @param distance        distance to the nearest connected JobBlock (unconnected only)
 * @param required        distance needed to connect (unconnected only)
 * @param workers         the settlers working this JobBlock: Job, level and mission state
 * @param places          the offered Jobs with taken / places
 * @param collectLeaves   whether a Lumberjack also collects leaves
 * @param byDeliveryman   whether Deliverymen carry a Craftsperson's results ("Delivery: Deliveryman")
 * @param biome           today's biome and its effects
 * @param blessingEnd     at the Center Block: the clock value at which the settlement's work speed blessing ends, 0 = none
 * @param storageUnlinked whether this is a Clerk JobBlock without a linked container (the header asks to link one)
 * @param treasure        whether a Fisherman may catch treasure (the "Treasure" toggle of its JobBlock)
 * @param autoToolSupply  whether a Clerk gets missing tools made and delivered for its settlers (the "Auto tool supply" toggle)
 */
public record WorkersTabData(Component settlementName, boolean inSettlement, boolean connected, int distance, int required, List<Worker> workers,
        List<JobPlaces.Line> places, boolean collectLeaves, boolean byDeliveryman, Biome biome, long blessingEnd, boolean storageUnlinked,
        boolean treasure, boolean autoToolSupply) {

    /** Before the first sync, and for a block that is not registered. */
    public static final WorkersTabData EMPTY = new WorkersTabData(Component.empty(), false, false, 0, 0, List.of(), List.of(), false, false,
            Biome.NONE, 0L, false, true, true);

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, WorkersTabData> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.STREAM_CODEC, WorkersTabData::settlementName,
            ByteBufCodecs.BOOL, WorkersTabData::inSettlement,
            ByteBufCodecs.BOOL, WorkersTabData::connected,
            ByteBufCodecs.VAR_INT, WorkersTabData::distance,
            ByteBufCodecs.VAR_INT, WorkersTabData::required,
            Worker.STREAM_CODEC.apply(ByteBufCodecs.list()), WorkersTabData::workers,
            JobPlaces.Line.STREAM_CODEC.apply(ByteBufCodecs.list()), WorkersTabData::places,
            ByteBufCodecs.BOOL, WorkersTabData::collectLeaves,
            ByteBufCodecs.BOOL, WorkersTabData::byDeliveryman,
            Biome.STREAM_CODEC, WorkersTabData::biome,
            ByteBufCodecs.VAR_LONG, WorkersTabData::blessingEnd,
            ByteBufCodecs.BOOL, WorkersTabData::storageUnlinked,
            ByteBufCodecs.BOOL, WorkersTabData::treasure,
            ByteBufCodecs.BOOL, WorkersTabData::autoToolSupply,
            WorkersTabData::new);

    /**
     * @param settlementName  the settlement name
     * @param inSettlement    whether the block belongs to a settlement
     * @param connected       whether it is connected
     * @param distance        distance to the nearest connected JobBlock
     * @param required        distance needed to connect
     * @param workers         the workers
     * @param places          the offered Jobs
     * @param collectLeaves   leaf collection
     * @param byDeliveryman   delivery
     * @param biome           today's biome
     * @param blessingEnd     the end of the blessing
     * @param storageUnlinked whether the Clerk has no linked container
     * @param treasure        the treasure option
     * @param autoToolSupply  the tool supply option
     */
    public WorkersTabData {
        workers = List.copyOf(workers);
        places = List.copyOf(places);
    }

    /**
     * @return for an unconnected block: whether the settlement has no connected block to reach
     */
    public boolean nothingToReach() {
        return required == 0;
    }

    /**
     * @return the settlers working this JobBlock with their Job
     */
    public List<SettlementBlock.Assignee> assignees() {
        return workers.stream().map(Worker::assignee).toList();
    }

    /**
     * @param now the overworld clock value now
     * @return the ticks the blessing still acts, 0 = none
     */
    public long blessingLeft(long now) {
        return Math.max(0L, blessingEnd - now);
    }

    /**
     * Today's biome of the JobBlock's Job (vault {@code Systems/JobBlock.md}, decisions on biome bonuses).
     *
     * @param biome   today's biome, empty while no biome is known
     * @param effects its effects ({@link BiomeBonusText#effects}), empty without an effect
     */
    public record Biome(Optional<Component> biome, List<Component> effects) {

        /** No biome known. */
        public static final Biome NONE = new Biome(Optional.empty(), List.of());

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Biome> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs::optional), Biome::biome,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), Biome::effects,
                Biome::new);

        /**
         * @param biome   today's biome
         * @param effects its effects
         */
        public Biome {
            effects = List.copyOf(effects);
        }

        /** @return today's biome with its effects ("Desert · Ore chance ×1.5"), empty while no biome is known */
        public Optional<Component> today() {
            return biome.map(name -> BiomeBonusText.line(name, effects));
        }

        /**
         * Today's biome of the first loaded settler working the JobBlock that has one; without such a settler the
         * biome the Job reads without a work place (a Logistics Job's JobBlock).
         */
        private static Biome of(ServerLevel level, BlockPos pos, Job job, List<SettlementBlock.Assignee> assignees) {
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
            return new Biome(BiomeBonusText.biome(today), BiomeBonusText.effects(read, today));
        }
    }

    /**
     * A row of the Workers tab: a settler working the JobBlock (vault {@code Systems/GUI.md}, "Info becomes Workers").
     *
     * @param assignee the settler and the Job it holds here
     * @param loaded   whether the settler entity is loaded (only then its window opens and its level is known)
     * @param level    its skill level in that Job, 0 while unloaded
     * @param skin     its skin index ({@code Settler#getSkin}), -1 while unloaded
     * @param away     the mission it is away on, empty at home (also while it only reserved one)
     * @param auto     for a Deliveryman: its "Auto" switch (whether it also takes the automatic tasks); true for the others
     */
    public record Worker(SettlementBlock.Assignee assignee, boolean loaded, int level, int skin, Optional<Away> away, boolean auto) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Worker> STREAM_CODEC = StreamCodec.composite(
                SettlementBlock.Assignee.STREAM_CODEC, Worker::assignee,
                ByteBufCodecs.BOOL, Worker::loaded,
                ByteBufCodecs.VAR_INT, Worker::level,
                ByteBufCodecs.INT, Worker::skin,
                Away.STREAM_CODEC.apply(ByteBufCodecs::optional), Worker::away,
                ByteBufCodecs.BOOL, Worker::auto,
                Worker::new);

        /**
         * A mission a worker is away on.
         *
         * @param mission    the mission id
         * @param returnTime the overworld clock value of the return; the client counts the days down ({@link #daysLeft})
         */
        public record Away(Identifier mission, long returnTime) {

            /** Network codec. */
            public static final StreamCodec<RegistryFriendlyByteBuf, Away> STREAM_CODEC = StreamCodec.composite(
                    Identifier.STREAM_CODEC, Away::mission,
                    ByteBufCodecs.VAR_LONG, Away::returnTime,
                    Away::new);
        }

        /**
         * @param level    the level
         * @param assignee the settler and its Job at the JobBlock
         * @return its row; a settler whose entity is not loaded has its name and Job only
         */
        static Worker of(ServerLevel level, BlockPos jobBlock, SettlementBlock.Assignee assignee) {
            if (!(level.getEntity(assignee.id()) instanceof Settler settler)) {
                return new Worker(assignee, false, 0, -1, Optional.empty(), true);
            }
            Optional<Away> away = settler.getWork().missionTrip().filter(MissionTrip::departed)
                    .map(trip -> new Away(trip.mission(), trip.returnTime()));
            boolean auto = settler.getJobs().stream().filter(job -> job.jobBlock().equals(jobBlock)).findFirst().map(SettlerJob::auto).orElse(true);
            return new Worker(assignee, true, settler.getSkills().get(assignee.job()).level(), settler.getSkin(), away, auto);
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
     * @param level    the dimension
     * @param pos      the JobBlock position
     * @param jobType  the offered job
     * @param jobLevel the JobBlock level
     * @return the current data of that JobBlock
     */
    public static WorkersTabData of(ServerLevel level, BlockPos pos, Job jobType, int jobLevel) {
        SettlementManager.Status status = SettlementWorld.status(level, pos);
        Optional<Settlement> settlement = status.settlement();
        Optional<SettlementBlock> block = JobBlockSite.blockAt(level, pos);
        List<SettlementBlock.Assignee> assignees = block.map(SettlementBlock::assignees).orElse(List.of());
        List<Worker> workers = new ArrayList<>();
        for (SettlementBlock.Assignee assignee : assignees) {
            workers.add(Worker.of(level, pos, assignee));
        }
        return new WorkersTabData(settlement.map(Settlement::displayName).orElse(Component.empty()),
                settlement.isPresent(), status.connected(), status.distance(), status.required(), workers,
                JobPlaces.lines(jobType, jobLevel, block.map(JobPlaces::offered).orElseGet(() -> JobPlaces.offered(jobType)),
                        assignees, block.map(SettlementBlock::autoAssign).orElse(Set.of())),
                block.map(SettlementBlock::collectLeaves).orElse(false), block.map(SettlementBlock::byDeliveryman).orElse(false),
                Biome.of(level, pos, jobType, assignees),
                blessingEnd(settlement, block, level.getOverworldClockTime()),
                jobType == ModJobs.CLERK.get() && block.map(found -> found.storage().isEmpty()).orElse(false),
                block.map(SettlementBlock::treasure).orElse(true), block.map(SettlementBlock::autoToolSupply).orElse(true));
    }

    private static long blessingEnd(Optional<Settlement> settlement, Optional<SettlementBlock> block, long now) {
        if (settlement.isPresent() && block.isPresent() && block.get().isCenter()) {
            long left = settlement.get().bonuses().remaining(Bonuses.Kind.WORK_SPEED, now);
            return left > 0 ? now + left : 0L;
        }
        return 0L;
    }

    /**
     * Server → client: the Workers tab of the open menu changed.
     *
     * @param containerId the menu's container id
     * @param tab         the tab
     */
    public record Sync(int containerId, WorkersTabData tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("workers_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                WorkersTabData.STREAM_CODEC, Sync::tab,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: switch the automatic assignment of one Job line, named by its Job, not its place in the list.
     *
     * @param containerId the menu's container id
     * @param job         the Job of the line
     * @param on          the wanted state
     */
    public record Auto(int containerId, Job job, boolean on) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Auto> TYPE = new Type<>(CivMod.id("workers_tab_auto"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Auto> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Auto::containerId,
                Job.STREAM_CODEC, Auto::job,
                ByteBufCodecs.BOOL, Auto::on,
                Auto::new);

        @Override
        public Type<Auto> type() {
            return TYPE;
        }
    }
}
