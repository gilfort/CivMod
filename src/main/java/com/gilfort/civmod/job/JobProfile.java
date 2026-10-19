package com.gilfort.civmod.job;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.CategoryProfile;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The job-specific parts of a Resource Collection Job (vault {@code Systems/Resource Collection Jobs.md}): tool,
 * work places, scan and output. Walking, fatigue, buffer, delivery and food are shared ({@link SettlerWork}).
 */
public interface JobProfile extends CategoryProfile {

    @Override
    default JobCategory category() {
        return JobCategory.RESOURCE_COLLECTION;
    }

    /**
     * @param stack an item stack
     * @return whether it is the job's tool
     */
    boolean isTool(ItemStack stack);

    /**
     * @return the tool shown in the "no tool" thought bubble (the job's tool in its simplest usable form)
     */
    net.minecraft.world.item.Item toolIcon();

    /**
     * @param block the JobBlock
     * @return a second tool the job wants with these settings (e.g. shears for leaves), if any
     */
    default Optional<Predicate<ItemStack>> secondaryTool(SettlementBlock block) {
        return Optional.empty();
    }

    /**
     * @param level the level
     * @param pos   a position
     * @return whether it is a valid (loaded) work place
     */
    boolean isWorkPlace(ServerLevel level, BlockPos pos);

    /**
     * @param level the level
     * @param pos   a position
     * @return whether a player may set the block as work place with the Scroll of Assignment
     */
    boolean isAssignableWorkPlace(Level level, BlockPos pos);

    /**
     * Finds a work place when none is set (at most once per day).
     *
     * @param level    the level
     * @param jobBlock the JobBlock position
     * @return the work place, if any
     */
    @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock);

    /**
     * @param block     the JobBlock
     * @param poiOfDay the work place of the day
     * @return the centre of the scan
     */
    BlockPos scanCenter(SettlementBlock block, BlockPos poiOfDay);

    /**
     * @return whether the scan is repeated every day (otherwise only when centre or radius change)
     */
    boolean rescanDaily();

    /**
     * @param center the scan centre
     * @param radius the horizontal radius
     * @return a new scan
     */
    SectionScan<? extends ScanResult> createScan(BlockPos center, int radius);

    /**
     * @return whether an empty scan stops the job (status "No resources")
     */
    default boolean needsResources() {
        return false;
    }

    /**
     * @param scan the scan result
     * @return production speed factor from the area (1 = normal)
     */
    default double productivity(@Nullable ScanResult scan) {
        return 1.0;
    }

    /**
     * Produces one resource.
     *
     * @param context the production context
     * @return the produced items and the tool to wear, empty if nothing was produced
     */
    Optional<Produced> produce(Context context);

    /**
     * @param tool  the current tool (may be empty)
     * @param block a found block type
     * @return whether the tool can obtain that type (shown greyed out otherwise)
     */
    default boolean canObtain(ItemStack tool, Identifier block) {
        return true;
    }

    /**
     * @return whether the settler strikes its work place continuously (axe / pickaxe); otherwise the profile animates
     *         itself in {@link #produce}
     */
    default boolean swings() {
        return true;
    }

    /**
     * @return whether the JobBlock offers the "collect leaves" option (shears as secondary tool)
     */
    default boolean hasLeavesOption() {
        return false;
    }

    /**
     * @return how close (horizontally) the settler must be to its work place
     */
    default double reach() {
        return 2.5;
    }

    /**
     * @param skillLevel the skill level
     * @param tool       the tool
     * @return resources per second (before the area productivity)
     */
    default double speed(int skillLevel, ItemStack tool) {
        return LumberjackOutput.speed(skillLevel) * ToolTiers.multiplier(ToolTiers.tier(tool));
    }

    /**
     * The main resources of the job in an area (logs, stones and ores, fish): when every one of them is excluded at
     * the JobBlock or at its Max at the Home Clerk the job pauses ("All resources excluded" / "Maximum reached" /
     * "Nothing to produce"); rare drops and treasure never keep it running.
     *
     * @param scan the scan result
     * @return the main resource types, by default the found block types
     */
    default List<Identifier> mainResources(ScanResult scan) {
        return scan.foundBlocks();
    }

    /**
     * The item a found type drops, for the Max check of its Home Clerk's stock rule (vault
     * {@code Systems/Resource Collection Jobs.md}): by default the block's item.
     *
     * @param level the level
     * @param pos   where it would be mined (loot context origin)
     * @param type  a found type (block id, for the Fisherman the fish item id)
     * @param tool  the tool it would be obtained with (Silk Touch / Fortune considered; may be empty)
     * @return the dropped item ({@code minecraft:air} if it drops nothing)
     */
    default Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        return BuiltInRegistries.BLOCK.getValue(type).asItem();
    }

    /**
     * The types a roll leaves out of its group (vault {@code Systems/Resource Collection Jobs.md}): those excluded at
     * the JobBlock and those whose dropped item is at its Max at the Home Clerk.
     *
     * @param context the production context
     * @param types   the types of the rolled group
     * @return the types left out
     */
    default Set<Identifier> leftOut(Context context, Collection<Identifier> types) {
        Set<Identifier> excluded = context.block().excluded();
        if (context.atMax().isEmpty()) {
            return excluded;
        }
        Set<Identifier> left = new HashSet<>(excluded);
        for (Identifier type : types) {
            if (!left.contains(type) && context.atMax().contains(context.dropped().apply(type))) {
                left.add(type);
            }
        }
        return left;
    }

    /**
     * What the JobBlock's Found tab lists (by default the found block types); each entry can be excluded.
     *
     * @param scan   the scan result
     * @param worker the settler working the JobBlock, if any (without one no tool marks)
     * @return the entries
     */
    default List<Found> found(ScanResult scan, Optional<Worker> worker) {
        return scan.foundBlocks().stream()
                .map(block -> new Found(block, new ItemStack(BuiltInRegistries.BLOCK.getValue(block)),
                        worker.map(found -> canObtain(found.tool(), block)).orElse(true)))
                .filter(found -> !found.icon().isEmpty())
                .toList();
    }

    /**
     * Scan info the Found tab shows above the entries (e.g. the Fisherman's water and treasure chance).
     *
     * @param scan   the scan result
     * @param worker the settler working the JobBlock, if any
     * @return the lines, one per value
     */
    default List<Component> foundLines(ScanResult scan, Optional<Worker> worker) {
        return List.of();
    }

    /**
     * A found resource listed in the Found tab.
     *
     * @param type      the type (block id, for the Fisherman the fish item id); the key of an exclusion
     * @param icon      the icon
     * @param available whether the working settler's tool can obtain it (always without a settler)
     */
    record Found(Identifier type, ItemStack icon, boolean available) {
    }

    /**
     * The settler working a JobBlock, as far as the Found tab needs it.
     *
     * @param tool       its best tool of the job (empty without one)
     * @param skillLevel its effective skill level in the job
     */
    record Worker(ItemStack tool, int skillLevel) {
    }

    /**
     * What one production step needs.
     *
     * @param level      the level
     * @param settler    the settler
     * @param scan       the scan result (null while the first scan runs)
     * @param poi        the work place
     * @param tool       the job's tool
     * @param secondary  the secondary tool, if any
     * @param block      the JobBlock
     * @param skillLevel the skill level
     * @param atMax      the items at their Max at the Home Clerk (left out of the roll)
     * @param dropped    the item a resource type drops with the tool ({@link #dropped}, cached per scan)
     */
    record Context(ServerLevel level, Settler settler, @Nullable ScanResult scan, BlockPos poi, ItemStack tool, Optional<ItemStack> secondary,
            SettlementBlock block, int skillLevel, Set<Item> atMax, Function<Identifier, Item> dropped) {
    }

    /**
     * Result of one production step.
     *
     * @param stacks the produced items
     * @param worn   the tool that loses durability
     */
    record Produced(List<ItemStack> stacks, ItemStack worn) {
    }
}
