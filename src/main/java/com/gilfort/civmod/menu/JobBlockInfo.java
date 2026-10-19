package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.UpgradeCosts;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.OutcomeRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
 * @param assignees      the settlers working this JobBlock with their Job
 * @param places         the offered Jobs with taken / places
 * @param collectLeaves  whether a Lumberjack also collects leaves
 * @param byDeliveryman  whether Deliverymen carry a Craftsperson's results ("Delivery: Deliveryman")
 * @param costs          upgrade costs per target level (index 0 = level 2)
 * @param missionTab   the missions and Mission Job settlers (Mission Board only, else empty)
 */
public record JobBlockInfo(Job jobType, int level, Component settlementName, boolean inSettlement, boolean connected,
        int distance, int required, List<SettlementBlock.Assignee> assignees, List<JobPlaces.Line> places, boolean collectLeaves, boolean byDeliveryman,
        List<List<CostEntry>> costs,
        MissionTab missionTab) {

    /**
     * Server → client: the info tab changed on the server beyond what the client predicted (an automatic assignment
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
     * An upgrade cost as shown to the client.
     *
     * @param icon  the item shown (count 1)
     * @param tag   the item tag that pays it, if it is a tag cost
     * @param count the count
     */
    public record CostEntry(ItemStack icon, Optional<Identifier> tag, int count) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, CostEntry> STREAM_CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, CostEntry::icon,
                Identifier.STREAM_CODEC.apply(ByteBufCodecs::optional), CostEntry::tag,
                ByteBufCodecs.VAR_INT, CostEntry::count,
                CostEntry::new);

        /**
         * @param cost a server-side cost
         * @return the entry
         */
        public static CostEntry of(UpgradeCosts.Cost cost) {
            return new CostEntry(cost.icon().copyWithCount(1), cost.tag().map(TagKey::location), cost.count());
        }

        /**
         * @param stack an item stack
         * @return whether it pays this cost
         */
        public boolean matches(ItemStack stack) {
            return !stack.isEmpty() && tag.map(id -> stack.is(TagKey.create(Registries.ITEM, id))).orElseGet(() -> stack.is(icon.getItem()));
        }

        /**
         * @param inventory a player inventory
         * @return how many matching items it holds
         */
        public int count(Inventory inventory) {
            int have = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (matches(stack)) {
                    have += stack.getCount();
                }
            }
            return have;
        }
    }

    /**
     * @param inventory a player inventory
     * @return whether the next upgrade is affordable (false at the maximum level)
     */
    public boolean canAffordNext(Inventory inventory) {
        if (level >= SettlementRules.MAX_LEVEL) {
            return false;
        }
        return nextCosts().stream().allMatch(cost -> cost.count(inventory) >= cost.count());
    }

    /**
     * @return the costs of the next level (empty at the maximum)
     */
    public List<CostEntry> nextCosts() {
        int index = level - 1;
        return index >= 0 && index < costs.size() ? costs.get(index) : List.of();
    }

    /**
     * @param newLevel the new level
     * @return a copy with another level
     */
    public JobBlockInfo withLevel(int newLevel) {
        return new JobBlockInfo(jobType, newLevel, settlementName, inSettlement, connected, distance, required, assignees,
                JobPlaces.lines(jobType, newLevel, assignees, autoJobs()), collectLeaves, byDeliveryman, costs, missionTab);
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
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, assignees, changed, collectLeaves,
                byDeliveryman, costs, missionTab);
    }

    /**
     * @param jobType the job
     * @return the upgrade costs of all levels (index 0 = level 2)
     */
    public static List<List<CostEntry>> costsOf(Job jobType) {
        List<List<CostEntry>> result = new java.util.ArrayList<>();
        for (int target = SettlementRules.MIN_LEVEL + 1; target <= SettlementRules.MAX_LEVEL; target++) {
            result.add(UpgradeCosts.get(jobType, target).stream().map(CostEntry::of).toList());
        }
        return List.copyOf(result);
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
            SettlementBlock.Assignee.STREAM_CODEC.apply(ByteBufCodecs.list()), JobBlockInfo::assignees,
            JobPlaces.Line.STREAM_CODEC.apply(ByteBufCodecs.list()), JobBlockInfo::places,
            ByteBufCodecs.BOOL, JobBlockInfo::collectLeaves,
            ByteBufCodecs.BOOL, JobBlockInfo::byDeliveryman,
            CostEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).apply(ByteBufCodecs.list()), JobBlockInfo::costs,
            MissionTab.STREAM_CODEC, JobBlockInfo::missionTab,
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
        int cap = SettlementRules.skillCap(jobLevel);
        for (SettlementBlock.Assignee assignee : assignees) {
            if (!(level.getEntity(assignee.id()) instanceof Settler settler)) {
                continue;
            }
            settler.getWork().missionTrip().ifPresent(trip -> taken.add(new TakenMission(assignee.name(), trip.mission(), trip.departed())));
            settler.getJobs().stream().filter(job -> job.jobBlock().equals(pos) && job.jobType().category() == JobCategory.MISSION).findFirst()
                    .ifPresent(job -> settlers.add(new MissionSettler(assignee.id(), assignee.name(), job.jobType(),
                            settler.getSkills().effectiveLevel(job.jobType(), cap))));
        }
        return new JobBlockInfo(jobType, jobLevel,
                settlement.map(Settlement::displayName).orElse(Component.empty()),
                settlement.isPresent(), status.connected(), status.distance(), status.required(),
                List.copyOf(assignees), JobPlaces.lines(jobType, jobLevel, assignees, block.map(SettlementBlock::autoAssign).orElse(java.util.Set.of())),
                block.map(SettlementBlock::collectLeaves).orElse(false), block.map(SettlementBlock::byDeliveryman).orElse(false),
                costsOf(jobType),
                new MissionTab(SettlementWorld.missionOffer(level, pos), taken, settlers, OutcomeRules.get()));
    }

    /**
     * @return a copy without assignees
     */
    public JobBlockInfo withoutAssignees() {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, List.of(),
                JobPlaces.lines(jobType, level, List.of(), autoJobs()), collectLeaves, byDeliveryman, costs,
                new MissionTab(missionTab.offer(), List.of(), List.of(), missionTab.rules()));
    }

    /**
     * @return a copy with leaf collection toggled
     */
    public JobBlockInfo withLeavesToggled() {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, assignees, places, !collectLeaves,
                byDeliveryman, costs, missionTab);
    }

    /**
     * @return a copy with the Craftsperson's delivery toggled (Self / Deliveryman)
     */
    public JobBlockInfo withDeliveryToggled() {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, assignees, places, collectLeaves,
                !byDeliveryman, costs, missionTab);
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
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, assignees, places, collectLeaves, byDeliveryman, costs,
                changed);
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
                costsOf(jobType), MissionTab.EMPTY);
    }
}
