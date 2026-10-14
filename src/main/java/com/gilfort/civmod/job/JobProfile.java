package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The job-specific parts of a Resource Collection Job (vault {@code Systems/Resource Collection Jobs.md}): tool,
 * work places, scan and output. Walking, fatigue, buffer, delivery and food are shared ({@link SettlerWork}).
 */
public interface JobProfile {

    /**
     * @param stack an item stack
     * @return whether it is the job's tool
     */
    boolean isTool(ItemStack stack);

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
     * What the job tab shows as found: by default the found block types.
     *
     * @param scan       the scan result
     * @param tool       the current tool (may be empty)
     * @param skillLevel the skill level
     * @return the entries
     */
    default List<Found> found(ScanResult scan, ItemStack tool, int skillLevel) {
        return scan.foundBlocks().stream()
                .map(block -> new Found(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(block)), canObtain(tool, block),
                        List.<net.minecraft.network.chat.Component>of()))
                .filter(found -> !found.icon().isEmpty())
                .toList();
    }

    /**
     * A found resource shown in the job tab.
     *
     * @param icon      the icon
     * @param available whether the current tool can obtain it
     * @param tooltip   tooltip lines (empty: the item name)
     */
    record Found(ItemStack icon, boolean available, List<net.minecraft.network.chat.Component> tooltip) {
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
     */
    record Context(ServerLevel level, Settler settler, @Nullable ScanResult scan, BlockPos poi, ItemStack tool, Optional<ItemStack> secondary,
            SettlementBlock block, int skillLevel) {
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
