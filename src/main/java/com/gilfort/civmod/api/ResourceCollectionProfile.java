package com.gilfort.civmod.api;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.job.LumberjackOutput;
import com.gilfort.civmod.job.ToolTiers;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The specifics of a Resource Collection Job (vault {@code Systems/Resource Collection Jobs.md}): tool, work places,
 * scan and output. The category's loop does everything else: fetching the tool, walking, fatigue, the buffer and its
 * delivery to the drop-off, food, EXP, catch-up, the JobBlock's Found tab with its exclusions and the Max of the Home
 * Clerk's stock rules.
 * <p>
 * Most collecting Jobs count blocks and produce their drops: {@link SimpleCollectionProfile#builder} builds such a
 * profile from a block tag and a tool tag. Implement this interface for Jobs with their own scan or roll (CivMod's
 * Lumberjack, Miner and Fisherman do).
 * <p>
 * A scan runs spread over ticks ({@link SectionScan}, one chunk section per step) whenever the scan centre or radius
 * change, or every day ({@link #rescanDaily()}); the settler keeps working with the previous result meanwhile.
 */
@ApiStatus.Experimental
public interface ResourceCollectionProfile extends CategoryProfile {

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
    Item toolIcon();

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
     * Finds a work place when the JobBlock has no valid work point (at most once per decision without one).
     *
     * @param level    the level
     * @param jobBlock the JobBlock position
     * @return the work place, if any
     */
    @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock);

    /**
     * @param jobBlock   the JobBlock position
     * @param workPoints the JobBlock's work points set with the Scroll of Assignment, in order
     * @param workPlace  the work place of the day
     * @return the centre of the scan, by default the first work point, else the JobBlock
     */
    default BlockPos scanCenter(BlockPos jobBlock, List<BlockPos> workPoints, BlockPos workPlace) {
        return workPoints.isEmpty() ? jobBlock : workPoints.get(0);
    }


    /**
     * @param jobBlockLevel the JobBlock level (1–5)
     * @return the horizontal radius of the scan, by default the settlement radius of the JobBlock (10 × level)
     */
    default int scanRadius(int jobBlockLevel) {
        return SettlementRules.joinRadius(jobBlockLevel);
    }

    /**
     * @return whether the scan is repeated every day (otherwise only when centre or radius change)
     */
    boolean rescanDaily();

    /**
     * @param center the scan centre
     * @param radius the horizontal radius ({@link #scanRadius})
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
     * Why the Job cannot work, in its own words (vault {@code Systems/Job Categories.md}, status "Blocked"). Asked at
     * every work decision; a reason pauses the job like "Drop-off full": the settler works its next priority and
     * checks again after 30 s. The reason is the job's status text in the settler window, and the settler's problem
     * bubble shows its icon (or the generic "blocked" marker). Neutral by default.
     *
     * @param scan the finished scan, or null when no work place was found
     * @return the reason, empty when the Job can work
     */
    default Optional<BlockingReason> blockingReason(@Nullable ScanResult scan) {
        return Optional.empty();
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
        Set<Identifier> excluded = context.excluded();
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
    @ApiStatus.Experimental
    record Found(Identifier type, ItemStack icon, boolean available) {
    }

    /**
     * The settler working a JobBlock, as far as the Found tab needs it.
     *
     * @param tool       its best tool of the job (empty without one)
     * @param skillLevel its effective skill level in the job
     */
    @ApiStatus.Experimental
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
     * @param excluded   the types excluded at the JobBlock
     * @param skillLevel the skill level
     * @param atMax      the items at their Max at the Home Clerk (left out of the roll)
     * @param dropped    the item a resource type drops with the tool ({@link #dropped}, cached per scan)
     */
    @ApiStatus.Experimental
    record Context(ServerLevel level, PathfinderMob settler, @Nullable ScanResult scan, BlockPos poi, ItemStack tool, Set<Identifier> excluded, int skillLevel, Set<Item> atMax, Function<Identifier, Item> dropped) {
    }

    /**
     * Result of one production step.
     *
     * @param stacks the produced items
     * @param worn   the tool that loses durability
     */
    @ApiStatus.Experimental
    record Produced(List<ItemStack> stacks, ItemStack worn) {
    }
}
