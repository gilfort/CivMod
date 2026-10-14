package com.gilfort.civmod.menu;

import java.util.List;

import com.gilfort.civmod.block.UpgradeCosts;
import com.gilfort.civmod.job.Job;
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
 * @param costs          upgrade costs per target level (index 0 = level 2)
 */
public record JobBlockInfo(Job jobType, int level, Component settlementName, boolean inSettlement, boolean connected,
        int distance, int required, String assignee, boolean collectLeaves, List<List<CostEntry>> costs) {

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
        return new JobBlockInfo(jobType, newLevel, settlementName, inSettlement, connected, distance, required, assignee, collectLeaves, costs);
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
            ByteBufCodecs.STRING_UTF8, JobBlockInfo::assignee,
            ByteBufCodecs.BOOL, JobBlockInfo::collectLeaves,
            CostEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).apply(ByteBufCodecs.list()), JobBlockInfo::costs,
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
        return new JobBlockInfo(jobType, jobLevel,
                settlement.map(Settlement::displayName).orElse(Component.empty()),
                settlement.isPresent(), status.connected(), status.distance(), status.required(),
                settlement.flatMap(s -> s.block(pos)).flatMap(SettlementBlock::assigneeName).orElse(""),
                settlement.flatMap(s -> s.block(pos)).map(SettlementBlock::collectLeaves).orElse(false), costsOf(jobType));
    }

    /**
     * @return a copy without assignee
     */
    public JobBlockInfo withoutAssignee() {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, "", collectLeaves, costs);
    }

    /**
     * @return a copy with leaf collection toggled
     */
    public JobBlockInfo withLeavesToggled() {
        return new JobBlockInfo(jobType, level, settlementName, inSettlement, connected, distance, required, assignee, !collectLeaves, costs);
    }

    /**
     * @param jobType  the offered job
     * @param jobLevel the JobBlock level
     * @return info without settlement data
     */
    public static JobBlockInfo unknown(Job jobType, int jobLevel) {
        return new JobBlockInfo(jobType, jobLevel, Component.empty(), false, false, 0, 0, "", false, costsOf(jobType));
    }
}
